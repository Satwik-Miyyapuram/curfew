//! Sync over the network the two devices are already on, with nothing in the middle.
//!
//! A phone and a PC on the same Wi-Fi can talk directly, and when they can, a lock started on one
//! should reach the other in seconds rather than whenever a cloud folder next feels like syncing.
//! This module is that path: a small signed beacon on a multicast group so the devices find each
//! other, and a TCP conversation carrying the same sealed packets every other transport carries.
//!
//! **Why not QUIC.** The roadmap said mDNS + QUIC, and both were dropped deliberately. QUIC's
//! advantages — 0-RTT, multiplexed streams, connection migration — are worth nothing for an
//! exchange that is two messages of a few kilobytes, and taking them would mean a TLS stack, a
//! certificate story, and a second set of identities alongside the device keys that already exist.
//! Every packet here is already sealed and signed by [`crate::wire`], so TLS would be encrypting
//! ciphertext under keys we would then have to explain. mDNS goes the same way: a full responder is
//! a large dependency and an attack surface for a job that is one multicast datagram saying "device
//! X is here, on port Y".
//!
//! **What the LAN can see.** The beacon is in the clear — it has to be, since it is what strangers
//! use to find us — so anyone on the same network learns that Curfew is running, and the device ids
//! of the machines running it. It says nothing about profiles, apps, or whether anything is locked,
//! and the conversation that follows is opaque. A beacon is signed, so it cannot be forged into
//! pointing at somebody else's machine; it is also *only* a hint, and everything that arrives over
//! the connection it advertises is checked again from scratch.

use crate::device::{DeviceId, Identity, PublicIdentity};
use crate::oplog::Log;
use crate::pair::Peers;
use crate::wire::{self, Message, Packet, Received};
use curfew_core::Timestamp;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::{Mutex, MutexGuard};
use std::time::Duration;

/// The multicast group beacons go to. Administratively scoped: routers do not forward it off the
/// local network, which is exactly the reach this transport is meant to have.
pub const GROUP: Ipv4Addr = Ipv4Addr::new(239, 255, 7, 62);
pub const BEACON_PORT: u16 = 50762;

/// How often a device announces itself. Two seconds keeps the worst case for "a lock started on the
/// PC blocks the phone" inside five: one beacon interval, one connection, one exchange.
pub const BEACON_SECONDS: u64 = 2;

/// How long a beacon is believed after it arrives. Longer than the interval so that a single lost
/// datagram — normal on Wi-Fi, where multicast is delivered at the lowest rate and often not at all
/// — does not make a peer flicker out of the list.
pub const BEACON_GRACE_SECONDS: i64 = 15;

/// The largest message this transport will read.
///
/// A first sync after a long time apart is the biggest thing sent, and it is still small; anything
/// past this is either a bug or somebody on the network trying to make us allocate a gigabyte, and
/// both deserve the same answer.
const MAX_FRAME: u32 = 8 * 1024 * 1024;

/// How long to wait on a peer that has stopped talking mid-exchange. A phone that walks out of the
/// room does exactly this, and it must cost seconds, not a hung thread.
const IO_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("the network could not be used: {0}")]
    Io(#[from] io::Error),
    #[error("that was not a Curfew message")]
    Malformed,
    #[error("a message of {0} bytes is past anything this protocol sends")]
    TooLarge(u32),
    #[error(transparent)]
    Wire(#[from] wire::Error),
}

/// What a device shouts on the local network so its peers can find it.
///
/// Signed rather than trusted: without a signature, anyone on the café Wi-Fi could claim to be the
/// user's PC and get the phone to open a connection to them. They still would learn nothing — the
/// conversation is sealed — but they would learn *when*, and a peer list that strangers can fill is
/// not a peer list.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Beacon {
    pub from: DeviceId,
    pub port: u16,
    pub at: Timestamp,
}

impl Beacon {
    fn canonical(&self) -> Vec<u8> {
        serde_json::to_vec(self).expect("a beacon is always serializable")
    }
}

/// A beacon with the signature that makes it worth reading.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SignedBeacon {
    pub beacon: Beacon,
    #[serde(with = "crate::oplog::signature_bytes")]
    pub signature: [u8; 64],
}

impl SignedBeacon {
    pub fn new(me: &Identity, port: u16, at: Timestamp) -> Self {
        let beacon = Beacon { from: me.id(), port, at };
        let signature = me.sign(&beacon.canonical());
        Self { beacon, signature }
    }

    /// Check a beacon against the devices this one is paired with.
    ///
    /// A beacon from a stranger is not an error worth reporting — a network has other things on it —
    /// but it is never a peer. One that is too old is refused too: otherwise a beacon recorded this
    /// morning could be replayed this evening to point the phone at a machine that has since changed
    /// hands.
    pub fn check(&self, peers: &Peers, now: Timestamp) -> bool {
        if (now - self.beacon.at).abs() > BEACON_GRACE_SECONDS {
            return false;
        }
        peers.verify(&self.beacon.from, &self.beacon.canonical(), &self.signature).is_ok()
    }
}

/// Where a peer was last heard from, and when.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Seen {
    pub address: SocketAddr,
    pub at: Timestamp,
}

/// The peers heard from recently, kept by whoever is running the loop.
#[derive(Debug, Default, Clone)]
pub struct Nearby(BTreeMap<DeviceId, Seen>);

impl Nearby {
    /// Record a beacon that has already been checked.
    pub fn heard(&mut self, from: DeviceId, address: SocketAddr, at: Timestamp) {
        self.0.insert(from, Seen { address, at });
    }

    /// Peers heard from inside the grace window, with stale ones dropped.
    ///
    /// Forgetting is as important as remembering: a device that has left the network must stop being
    /// listed as reachable, or the UI will keep promising a sync that cannot happen.
    pub fn fresh(&mut self, now: Timestamp) -> Vec<(DeviceId, SocketAddr)> {
        self.0.retain(|_, seen| now - seen.at <= BEACON_GRACE_SECONDS);
        self.0.iter().map(|(id, seen)| (id.clone(), seen.address)).collect()
    }

    pub fn get(&self, id: &DeviceId) -> Option<&Seen> {
        self.0.get(id)
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

/// Write one length-prefixed packet.
pub fn write_frame(to: &mut impl Write, packet: &Packet) -> Result<(), Error> {
    let bytes = serde_json::to_vec(packet).map_err(|_| Error::Malformed)?;
    let len = u32::try_from(bytes.len()).map_err(|_| Error::TooLarge(u32::MAX))?;
    to.write_all(&len.to_be_bytes())?;
    to.write_all(&bytes)?;
    to.flush()?;
    Ok(())
}

/// Read one length-prefixed packet.
///
/// The length is checked before a single byte is allocated, so a peer cannot ask for memory by
/// claiming to be about to send four gigabytes.
pub fn read_frame(from: &mut impl Read) -> Result<Packet, Error> {
    let mut header = [0u8; 4];
    from.read_exact(&mut header)?;
    let len = u32::from_be_bytes(header);
    if len > MAX_FRAME {
        return Err(Error::TooLarge(len));
    }
    let mut bytes = vec![0u8; len as usize];
    from.read_exact(&mut bytes)?;
    serde_json::from_slice(&bytes).map_err(|_| Error::Malformed)
}

/// The side that called: say what we hold, take what comes back, then send what they were missing.
///
/// Three messages, no state kept between them. If the connection dies at any point, both sides are
/// left holding valid logs that are merely less caught up than they hoped.
///
/// The log and the peers are taken as locks rather than as borrows, and each is held only for the
/// moment a frame is being built or applied — never across a read or a write. Held across the
/// network, they deadlock two devices that call each other at once: each is waiting on the other's
/// answer while holding the very lock the other's answering thread needs, and both sit there until
/// the socket times out. That was a sync measured in tens of seconds that should have taken one.
pub fn dial(
    stream: &mut (impl Read + Write),
    me: &Identity,
    peer: &PublicIdentity,
    peers: &Mutex<Peers>,
    log: &Mutex<Log>,
) -> Result<Received, Error> {
    // Four frames, fixed order, no negotiation: heads out, entries back, their heads, our entries.
    let greeting = wire::pack(me, peer, &wire::greet(&lock(log)));
    write_frame(stream, &greeting)?;

    let theirs = read_frame(stream)?;
    let received = {
        let peers = lock(peers);
        match wire::unpack(me, &peers, &theirs)? {
            Message::Entries(entries) => wire::receive(&mut lock(log), &peers, &entries),
            Message::Heads(_) => return Err(Error::Malformed),
        }
    };

    // Their heads are asked for rather than guessed, so a peer rebuilt from a backup is sent what
    // it actually lacks instead of what we assume it kept.
    let request = read_frame(stream)?;
    let answer = match wire::unpack(me, &lock(peers), &request)? {
        Message::Heads(heads) => wire::pack(me, peer, &wire::answer(&lock(log), &heads)),
        Message::Entries(_) => return Err(Error::Malformed),
    };
    write_frame(stream, &answer)?;
    Ok(received)
}

/// The side that answered. Mirrors [`dial`], and learns who it is talking to from the packet rather
/// than from the address, because an address proves nothing.
pub fn serve(
    stream: &mut (impl Read + Write),
    me: &Identity,
    peers: &Mutex<Peers>,
    log: &Mutex<Log>,
) -> Result<Received, Error> {
    let greeting = read_frame(stream)?;
    let (peer, answer) = {
        let peers = lock(peers);
        let peer =
            peers.active(&greeting.from).map_err(|_| wire::Error::Unpaired)?.identity.clone();
        let heads = match wire::unpack(me, &peers, &greeting)? {
            Message::Heads(heads) => heads,
            Message::Entries(_) => return Err(Error::Malformed),
        };
        let answer = wire::pack(me, &peer, &wire::answer(&lock(log), &heads));
        (peer, answer)
    };
    write_frame(stream, &answer)?;

    let mine = wire::pack(me, &peer, &wire::greet(&lock(log)));
    write_frame(stream, &mine)?;
    let theirs = read_frame(stream)?;
    let peers = lock(peers);
    let received = match wire::unpack(me, &peers, &theirs)? {
        Message::Entries(entries) => wire::receive(&mut lock(log), &peers, &entries),
        Message::Heads(_) => return Err(Error::Malformed),
    };
    Ok(received)
}

/// A lock, taken. Neither is ever held across a panic, so a poisoned one is a bug and not a state.
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().expect("a sync lock is never poisoned")
}

/// Bind the socket beacons are sent and received on.
///
/// Reuse is set because two Curfew processes on one machine — a service and a test, or an upgrade
/// in progress — must not fight over the port, and a device that cannot hear beacons is a device
/// that silently stops syncing.
pub fn beacon_socket() -> io::Result<UdpSocket> {
    if loopback_only() {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0))?;
        socket.set_read_timeout(Some(Duration::from_millis(500)))?;
        return Ok(socket);
    }
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, BEACON_PORT))?;
    socket.join_multicast_v4(&GROUP, &Ipv4Addr::UNSPECIFIED)?;
    socket.set_read_timeout(Some(Duration::from_millis(500)))?;
    Ok(socket)
}

/// Shout once.
pub fn announce(socket: &UdpSocket, me: &Identity, port: u16, now: Timestamp) -> io::Result<()> {
    let beacon = SignedBeacon::new(me, port, now);
    let bytes = serde_json::to_vec(&beacon).expect("a beacon is always serializable");
    socket.send_to(&bytes, SocketAddr::from((GROUP, BEACON_PORT)))?;
    Ok(())
}

/// Wait for one beacon, from anyone.
///
/// Returns `Ok(None)` when nothing arrived before the socket's timeout, which is the ordinary case
/// on a quiet network and not worth waking anything for — and when what arrived was not a beacon
/// at all. Checked by nobody yet: this is the half that blocks, and it is kept apart from the half
/// that needs the peer list so the wait is never spent holding a lock.
pub fn hear(socket: &UdpSocket) -> io::Result<Option<(SignedBeacon, SocketAddr)>> {
    let mut buffer = [0u8; 2048];
    let (read, from) = match socket.recv_from(&mut buffer) {
        Ok(got) => got,
        Err(e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => {
            return Ok(None)
        }
        Err(e) => return Err(e),
    };
    Ok(serde_json::from_slice::<SignedBeacon>(&buffer[..read]).ok().map(|signed| (signed, from)))
}

/// Record a beacon, if it is from a paired device. Returns who it was from.
pub fn accept(
    (signed, from): (SignedBeacon, SocketAddr),
    peers: &Peers,
    nearby: &mut Nearby,
    now: Timestamp,
) -> Option<DeviceId> {
    if !signed.check(peers, now) {
        return None;
    }
    let address = SocketAddr::new(from.ip(), signed.beacon.port);
    nearby.heard(signed.beacon.from.clone(), address, now);
    Some(signed.beacon.from)
}

/// Listen for one beacon, and record it if it is from a paired device: [`hear`] then [`accept`].
pub fn overhear(
    socket: &UdpSocket,
    peers: &Peers,
    nearby: &mut Nearby,
    now: Timestamp,
) -> io::Result<Option<DeviceId>> {
    Ok(hear(socket)?.and_then(|heard| accept(heard, peers, nearby, now)))
}

/// Bind the port peers connect to. Port zero: the operating system picks, and the beacon carries
/// whatever it picked, so nothing has to be reserved or configured.
pub fn listen() -> io::Result<TcpListener> {
    let host = if loopback_only() { Ipv4Addr::LOCALHOST } else { Ipv4Addr::UNSPECIFIED };
    TcpListener::bind((IpAddr::V4(host), 0))
}

/// Whether this process must keep off the network entirely: `CURFEW_LAN_LOOPBACK=1`.
///
/// Test binaries are the reason this exists. Every `cargo test` run compiles a *new* executable
/// with a new content hash, and a new unsigned executable that binds `0.0.0.0` is a new Windows
/// Defender Firewall prompt — one per run, each needing an administrator, none of which the tests
/// need at all: two nodes talking over the loopback interface prove exactly what two nodes talking
/// over the LAN would. So the harness sets this and nothing leaves the machine.
///
/// It is read here rather than passed in because it has to reach every binding site in every crate
/// that starts a node, including the ones a test only reaches indirectly.
/// This crate's own tests never touch the network, so they never have to ask for it; another
/// crate's tests set the variable, because there this crate is an ordinary dependency.
fn loopback_only() -> bool {
    cfg!(test) || std::env::var("CURFEW_LAN_LOOPBACK").is_ok_and(|value| value == "1")
}

/// Connect to a peer that was heard from, with timeouts set so a device that has left the network
/// costs seconds rather than a stuck thread.
pub fn connect(address: SocketAddr) -> io::Result<TcpStream> {
    let stream = TcpStream::connect_timeout(&address, IO_TIMEOUT)?;
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    Ok(stream)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::oplog::Op;
    use crate::pair::Invite;
    use curfew_core::session::{Session, SessionSource};
    use curfew_core::{Lock, LockSet};
    use std::io::Cursor;

    const NOW: Timestamp = 1_788_510_600;

    struct Pair {
        phone: Identity,
        pc: Identity,
        on_phone: Peers,
        on_pc: Peers,
    }

    fn paired() -> Pair {
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
        on_phone.accept(&phone, &Invite::new(&pc, NOW, [9; 16]), NOW).unwrap();
        on_pc.accept(&pc, &Invite::new(&phone, NOW, [9; 16]), NOW).unwrap();
        Pair { phone, pc, on_phone, on_pc }
    }

    fn start(id: &str, profile: &str) -> Op {
        Op::Start {
            session: Box::new(Session {
                id: id.into(),
                profile: profile.into(),
                source: SessionSource::Manual,
                started_at: NOW,
                lock: LockSet::new([Lock::DeviceCredential], None),
            }),
        }
    }

    /// A pipe that hands whatever one side writes to the other, so the conversation can be run
    /// without a network in the way.
    struct Pipe {
        outgoing: Vec<u8>,
        incoming: Cursor<Vec<u8>>,
    }

    impl Pipe {
        fn new(incoming: Vec<u8>) -> Self {
            Self { outgoing: Vec::new(), incoming: Cursor::new(incoming) }
        }
    }

    impl Read for Pipe {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            self.incoming.read(buf)
        }
    }

    impl Write for Pipe {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.outgoing.write(buf)
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    #[test]
    fn a_frame_survives_the_round_trip() {
        let p = paired();
        let packet = wire::pack(&p.pc, &p.phone.public(), &wire::greet(&Log::default()));

        let mut buffer = Vec::new();
        write_frame(&mut buffer, &packet).unwrap();
        assert_eq!(read_frame(&mut Cursor::new(buffer)).unwrap(), packet);
    }

    #[test]
    fn a_frame_that_claims_to_be_enormous_is_refused_before_anything_is_allocated() {
        let mut claimed = u32::MAX.to_be_bytes().to_vec();
        claimed.extend_from_slice(b"not actually four gigabytes");

        assert!(matches!(read_frame(&mut Cursor::new(claimed)), Err(Error::TooLarge(u32::MAX))));
    }

    #[test]
    fn a_truncated_frame_is_an_error_rather_than_a_short_read() {
        let mut truncated = 64u32.to_be_bytes().to_vec();
        truncated.extend_from_slice(b"only a few bytes");

        assert!(matches!(read_frame(&mut Cursor::new(truncated)), Err(Error::Io(_))));
    }

    #[test]
    fn a_stranger_who_connects_is_shown_nothing() {
        let p = paired();
        let stranger = Identity::generate("someone on the café wifi");
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", "deep-work"));

        let mut opening = Pipe::new(Vec::new());
        write_frame(
            &mut opening,
            &wire::pack(&stranger, &p.pc.public(), &wire::greet(&Log::default())),
        )
        .unwrap();

        let mut answerer = Pipe::new(opening.outgoing);
        let result = serve(&mut answerer, &p.pc, &Mutex::new(p.on_pc), &Mutex::new(log));

        assert!(matches!(result, Err(Error::Wire(wire::Error::Unpaired))));
        assert!(answerer.outgoing.is_empty(), "a stranger was sent bytes");
    }

    #[test]
    fn a_beacon_from_a_paired_device_is_believed() {
        let p = paired();
        let beacon = SignedBeacon::new(&p.pc, 40_000, NOW);

        assert!(beacon.check(&p.on_phone, NOW));
        assert!(beacon.check(&p.on_phone, NOW + 5), "a beacon expired far too eagerly");
    }

    #[test]
    fn a_beacon_from_a_stranger_is_not() {
        let p = paired();
        let stranger = Identity::generate("someone else");

        assert!(!SignedBeacon::new(&stranger, 40_000, NOW).check(&p.on_phone, NOW));
    }

    #[test]
    fn a_beacon_pointing_somewhere_else_is_refused() {
        // Without the signature covering the port, anyone could take a real beacon off the wire and
        // re-send it pointing at a machine of their choosing.
        let p = paired();
        let mut beacon = SignedBeacon::new(&p.pc, 40_000, NOW);
        beacon.beacon.port = 40_001;

        assert!(!beacon.check(&p.on_phone, NOW));
    }

    #[test]
    fn an_old_beacon_cannot_be_replayed_later() {
        let p = paired();
        let beacon = SignedBeacon::new(&p.pc, 40_000, NOW);

        assert!(!beacon.check(&p.on_phone, NOW + 3600));
        assert!(!beacon.check(&p.on_phone, NOW - 3600), "a beacon from the future was believed");
    }

    #[test]
    fn a_revoked_device_stops_being_found() {
        let mut p = paired();
        let beacon = SignedBeacon::new(&p.pc, 40_000, NOW);
        p.on_phone.revoke(&p.pc.id(), NOW).unwrap();

        assert!(!beacon.check(&p.on_phone, NOW));
    }

    #[test]
    fn a_peer_that_leaves_the_network_stops_being_listed() {
        let p = paired();
        let mut nearby = Nearby::default();
        nearby.heard(p.pc.id(), "192.168.1.5:40000".parse().unwrap(), NOW);

        assert_eq!(nearby.fresh(NOW + 1).len(), 1);
        assert!(nearby.fresh(NOW + BEACON_GRACE_SECONDS + 1).is_empty());
        assert!(nearby.is_empty(), "a departed peer was still remembered");
    }

    #[test]
    fn a_peer_that_moves_to_a_new_address_is_followed() {
        // Phones change address constantly: sleeping, waking, switching bands.
        let p = paired();
        let mut nearby = Nearby::default();
        nearby.heard(p.pc.id(), "192.168.1.5:40000".parse().unwrap(), NOW);
        nearby.heard(p.pc.id(), "192.168.1.9:40001".parse().unwrap(), NOW + 2);

        assert_eq!(nearby.fresh(NOW + 3), vec![(p.pc.id(), "192.168.1.9:40001".parse().unwrap())]);
    }

    #[test]
    fn beacons_travel_over_a_real_socket_and_land_where_they_should() {
        // Multicast on a loopback-only or locked-down machine is allowed to fail; what must not
        // happen is that it succeeds and is misread.
        let p = paired();
        let Ok(socket) = beacon_socket() else {
            return;
        };
        if announce(&socket, &p.pc, 40_000, NOW).is_err() {
            return;
        }

        let mut nearby = Nearby::default();
        // One datagram may be dropped by the network stack; a few tries is honest, a loop is not.
        for _ in 0..5 {
            if let Ok(Some(id)) = overhear(&socket, &p.on_phone, &mut nearby, NOW) {
                assert_eq!(id, p.pc.id());
                assert_eq!(nearby.get(&p.pc.id()).unwrap().address.port(), 40_000);
                return;
            }
            let _ = announce(&socket, &p.pc, 40_000, NOW);
        }
    }

    #[test]
    fn a_listener_takes_the_port_the_system_gives_it_and_the_beacon_carries_it() {
        let p = paired();
        let listener = listen().expect("a device that cannot listen cannot sync");
        let port = listener.local_addr().unwrap().port();

        assert_ne!(port, 0, "the port the peers are told to use was never resolved");
        let beacon = SignedBeacon::new(&p.pc, port, NOW);
        assert_eq!(beacon.beacon.port, port);
        assert!(beacon.check(&p.on_phone, NOW));
    }

    #[test]
    fn a_whole_exchange_runs_over_real_sockets() {
        let p = paired();
        let mut pc_log = Log::default();
        pc_log.append(&p.pc, NOW, start("pc-1", "deep-work"));

        let listener = listen().unwrap();
        let address =
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), listener.local_addr().unwrap().port());
        let pc_public = p.pc.public();
        let (pc, on_pc) = (p.pc, Mutex::new(p.on_pc));
        let answering = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream.set_read_timeout(Some(IO_TIMEOUT)).unwrap();
            let pc_log = Mutex::new(pc_log);
            serve(&mut stream, &pc, &on_pc, &pc_log).unwrap();
            pc_log.into_inner().unwrap()
        });

        let mut phone_log = Log::default();
        phone_log.append(&p.phone, NOW + 1, start("phone-1", "evenings"));
        let phone_log = Mutex::new(phone_log);
        let mut stream = connect(address).unwrap();
        let received =
            dial(&mut stream, &p.phone, &pc_public, &Mutex::new(p.on_phone), &phone_log).unwrap();

        let pc_log = answering.join().unwrap();
        assert_eq!(received.accepted, 1);
        assert_eq!(phone_log.into_inner().unwrap().replay(NOW + 5), pc_log.replay(NOW + 5));
    }
}
