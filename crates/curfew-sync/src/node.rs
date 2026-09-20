//! The part that actually runs: beacon, listen, dial, repeat.
//!
//! Everything below this is pure — a log, a lattice, a sealed packet — and pure code cannot be late.
//! This module is where lateness lives, so the choices about it are here too: how often to shout,
//! what to do when a peer is unreachable, and what to tell the rest of the program when something
//! arrives. A node owns one log behind a lock and hands out a handle; the service and the Android
//! side both drive it the same way, because "the phone blocks within five seconds of the PC
//! starting a session" is one behaviour and deserves one implementation.
//!
//! Threads rather than an async runtime: three sockets and a timer do not need an executor, and the
//! Android build is smaller and easier to reason about without one.

use crate::device::{DeviceId, Identity};
use crate::lan;
use crate::oplog::{Log, Op, Signed};
use crate::pair::Peers;
use curfew_core::Timestamp;
use std::collections::BTreeMap;
use std::net::{SocketAddr, TcpListener, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// What a node tells the rest of the program when the log changes.
///
/// Deliberately a callback rather than a channel the caller must remember to drain: a missed change
/// means a lock that should be on and is not, and a queue nobody reads is the usual way that
/// happens.
pub type OnChange = Arc<dyn Fn() + Send + Sync>;

/// How many inbound conversations this device will hold at once.
const MAX_CONVERSATIONS: usize = 8;

/// The state a node shares with everything else on the device.
#[derive(Clone)]
pub struct Shared {
    pub identity: Arc<Identity>,
    pub peers: Arc<Mutex<Peers>>,
    pub log: Arc<Mutex<Log>>,
    on_change: Option<OnChange>,
}

impl std::fmt::Debug for Shared {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Shared").field("identity", &self.identity).finish_non_exhaustive()
    }
}

impl Shared {
    pub fn new(identity: Identity, peers: Peers, log: Log) -> Self {
        Self {
            identity: Arc::new(identity),
            peers: Arc::new(Mutex::new(peers)),
            log: Arc::new(Mutex::new(log)),
            on_change: None,
        }
    }

    /// Be told when anything lands. Called on the node's own threads, so the handler must be quick
    /// and must not reach back into the node.
    pub fn notify(mut self, on_change: OnChange) -> Self {
        self.on_change = Some(on_change);
        self
    }

    /// Record something that happened on this device, and let the peers find out on the next pass.
    pub fn record(&self, at: Timestamp, op: Op) -> Signed {
        let signed =
            self.log.lock().expect("the log lock is never poisoned").append(&self.identity, at, op);
        self.changed();
        signed
    }

    /// What this device believes, right now.
    pub fn replay(&self, now: Timestamp) -> crate::oplog::Replay {
        self.log.lock().expect("the log lock is never poisoned").replay(now)
    }

    fn changed(&self) {
        if let Some(on_change) = &self.on_change {
            on_change();
        }
    }
}

/// A running node. Dropping it stops the threads.
pub struct Node {
    shared: Shared,
    address: SocketAddr,
    running: Arc<AtomicBool>,
    threads: Vec<std::thread::JoinHandle<()>>,
    nearby: Arc<Mutex<lan::Nearby>>,
}

impl std::fmt::Debug for Node {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Node").field("address", &self.address).finish_non_exhaustive()
    }
}

/// The clock a node reads. Injectable so tests do not have to wait out real minutes, and so the
/// service can hand in the same clock everything else on the device uses.
pub type Clock = Arc<dyn Fn() -> Timestamp + Send + Sync>;

fn system_clock() -> Clock {
    Arc::new(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as Timestamp)
            .unwrap_or_default()
    })
}

impl Node {
    /// Start listening and shouting.
    ///
    /// A device with no usable multicast — a locked-down network, a container, a phone with the
    /// multicast lock not held — still starts: it listens and answers, it simply will not find peers
    /// on its own. Failing to start here would take away the transport that still works.
    pub fn start(shared: Shared) -> std::io::Result<Self> {
        Self::start_with(shared, system_clock())
    }

    pub fn start_with(shared: Shared, clock: Clock) -> std::io::Result<Self> {
        let listener = lan::listen()?;
        let address = listener.local_addr()?;
        let running = Arc::new(AtomicBool::new(true));
        let nearby = Arc::new(Mutex::new(lan::Nearby::default()));
        let mut threads = Vec::new();

        threads.push(answer_loop(listener, shared.clone(), running.clone()));
        if let Ok(socket) = lan::beacon_socket() {
            threads.push(beacon_loop(
                socket.try_clone()?,
                shared.clone(),
                running.clone(),
                address.port(),
                clock.clone(),
            ));
            threads.push(overhear_loop(
                socket,
                shared.clone(),
                nearby.clone(),
                running.clone(),
                clock,
            ));
        }
        Ok(Self { shared, address, running, threads, nearby })
    }

    pub fn address(&self) -> SocketAddr {
        self.address
    }

    pub fn shared(&self) -> &Shared {
        &self.shared
    }

    /// Peers heard from recently.
    pub fn nearby(&self, now: Timestamp) -> Vec<(DeviceId, SocketAddr)> {
        self.nearby.lock().expect("the nearby lock is never poisoned").fresh(now)
    }

    /// Catch up with one peer, now, rather than waiting for the loop.
    ///
    /// Used when something has just happened locally and the user is watching: starting a lock on
    /// the PC should reach the phone while they are still looking at the screen.
    pub fn push(&self, to: &DeviceId, address: SocketAddr) -> Result<(), lan::Error> {
        let peer = {
            let peers = self.shared.peers.lock().expect("the peers lock is never poisoned");
            peers.active(to).map_err(|_| crate::wire::Error::Unpaired)?.identity.clone()
        };
        let mut stream = lan::connect(address)?;
        // The locks go in as locks: dial takes each only for the instant a frame needs it.
        let received = lan::dial(
            &mut stream,
            &self.shared.identity,
            &peer,
            &self.shared.peers,
            &self.shared.log,
        )?;
        if received.accepted > 0 {
            self.shared.changed();
        }
        Ok(())
    }

    /// Catch up with every peer that has been heard from.
    pub fn push_all(&self, now: Timestamp) {
        for (id, address) in self.nearby(now) {
            // One unreachable peer must not stop the others: a phone that has just left the room is
            // the normal case, not an error to report.
            let _ = self.push(&id, address);
        }
    }

    /// Stop the threads and wait for them.
    pub fn stop(mut self) {
        self.shutdown();
    }

    fn shutdown(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        for thread in self.threads.drain(..) {
            let _ = thread.join();
        }
    }
}

impl Drop for Node {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn answer_loop(
    listener: TcpListener,
    shared: Shared,
    running: Arc<AtomicBool>,
) -> std::thread::JoinHandle<()> {
    // Polled rather than blocked in `accept`, because stopping has to be something this device can
    // decide on its own. A blocking accept can only be woken by a connection, and "connect to
    // yourself to shut down" fails exactly when the network is the thing that has gone wrong.
    let _ = listener.set_nonblocking(true);
    let live = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    std::thread::spawn(move || {
        while running.load(Ordering::SeqCst) {
            let mut stream = match listener.accept() {
                Ok((stream, _)) => stream,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(50));
                    continue;
                }
                Err(_) => continue,
            };
            // The accepted socket inherits non-blocking on some platforms, and the conversation is
            // written against a blocking one.
            let _ = stream.set_nonblocking(false);
            let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
            let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));

            // One conversation per thread. Serving them in turn on this loop would mean a peer that
            // stops talking mid-sentence costs every other device the full read timeout, and the
            // one case where devices all connect at once is the one that matters: several of them
            // waking up on the same network at the same moment. The cap is there because an
            // unbounded thread per inbound connection is a way for anyone on the LAN to exhaust
            // this device; over the cap the connection is simply dropped and the peer retries.
            if live.load(Ordering::SeqCst) >= MAX_CONVERSATIONS {
                continue;
            }
            live.fetch_add(1, Ordering::SeqCst);
            let shared = shared.clone();
            let live = live.clone();
            std::thread::spawn(move || {
                let received =
                    lan::serve(&mut stream, &shared.identity, &shared.peers, &shared.log);
                // A stranger connecting, or a peer disappearing mid-sentence, is ordinary. Nothing
                // was taken in, so there is nothing to say about it.
                if matches!(received, Ok(got) if got.accepted > 0) {
                    shared.changed();
                }
                live.fetch_sub(1, Ordering::SeqCst);
            });
        }
    })
}

fn beacon_loop(
    socket: UdpSocket,
    shared: Shared,
    running: Arc<AtomicBool>,
    port: u16,
    clock: Clock,
) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        while running.load(Ordering::SeqCst) {
            let _ = lan::announce(&socket, &shared.identity, port, clock());
            std::thread::sleep(Duration::from_secs(lan::BEACON_SECONDS));
        }
    })
}

fn overhear_loop(
    socket: UdpSocket,
    shared: Shared,
    nearby: Arc<Mutex<lan::Nearby>>,
    running: Arc<AtomicBool>,
    clock: Clock,
) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        while running.load(Ordering::SeqCst) {
            // The wait comes first and holds nothing. The socket blocks for up to half a second at
            // a time, and a peer list locked for that long is a peer list every conversation on
            // this device queues behind, half a second per frame.
            let Ok(Some(beacon)) = lan::hear(&socket) else { continue };
            let now = clock();
            let heard = {
                let peers = shared.peers.lock().expect("the peers lock is never poisoned");
                let mut nearby = nearby.lock().expect("the nearby lock is never poisoned");
                lan::accept(beacon, &peers, &mut nearby, now)
            };
            // Hearing a peer is the moment to talk to it: it has just told us it is reachable, and
            // waiting for the next tick would spend the seconds the user notices.
            if let Some(id) = heard {
                let address = nearby
                    .lock()
                    .expect("the nearby lock is never poisoned")
                    .get(&id)
                    .map(|s| s.address);
                if let Some(address) = address {
                    let _ = push_from(&shared, &id, address);
                }
            }
        }
    })
}

/// The body of [`Node::push`], usable from the loops that do not hold a `Node`.
fn push_from(shared: &Shared, to: &DeviceId, address: SocketAddr) -> Result<(), lan::Error> {
    let peer = {
        let peers = shared.peers.lock().expect("the peers lock is never poisoned");
        peers.active(to).map_err(|_| crate::wire::Error::Unpaired)?.identity.clone()
    };
    let mut stream = lan::connect(address)?;
    // The locks go in as locks: dial takes each only for the instant a frame needs it.
    let received = lan::dial(&mut stream, &shared.identity, &peer, &shared.peers, &shared.log)?;
    if received.accepted > 0 {
        shared.changed();
    }
    Ok(())
}

/// What a node holds when it also uses a folder. Kept here so a device with both transports runs
/// one loop rather than two that can disagree about what has been sent.
#[derive(Debug, Clone, Default)]
pub struct Reach {
    /// Peers reachable on the network right now.
    pub lan: Vec<DeviceId>,
    /// When each peer last wrote to the shared folder, if one is configured.
    pub folder: BTreeMap<DeviceId, Timestamp>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pair::Invite;
    use curfew_core::session::{Session, SessionSource};
    use curfew_core::{Lock, LockSet};
    use std::sync::atomic::AtomicUsize;
    use std::time::Instant;

    const NOW: Timestamp = 1_788_510_600;

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

    fn two() -> (Shared, Shared) {
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
        on_phone.accept(&phone, &Invite::new(&pc, NOW, [5; 16]), NOW).unwrap();
        on_pc.accept(&pc, &Invite::new(&phone, NOW, [5; 16]), NOW).unwrap();
        (Shared::new(phone, on_phone, Log::default()), Shared::new(pc, on_pc, Log::default()))
    }

    fn loopback(port: u16) -> SocketAddr {
        SocketAddr::from(([127, 0, 0, 1], port))
    }

    /// Wait for something a peer's thread does, up to a bound.
    ///
    /// A push returns once the last frame is written, which is a moment before the other device has
    /// finished folding it in. Everything that asks "did the other side learn this?" therefore has
    /// to wait for the answer rather than assume it, and the bound is the five seconds the exit
    /// criterion allows so that a real failure still fails rather than hanging.
    fn until(condition: impl Fn() -> bool) -> bool {
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if condition() {
                return true;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        condition()
    }

    #[test]
    fn a_session_started_on_the_pc_reaches_the_phone_well_inside_five_seconds() {
        // The Phase 3 exit criterion, measured rather than assumed.
        let (phone_shared, pc_shared) = two();
        let phone = Node::start(phone_shared.clone()).unwrap();
        let pc = Node::start(pc_shared.clone()).unwrap();

        let began = Instant::now();
        pc_shared.record(NOW, start("pc-1", "deep-work"));
        pc.push(&phone_shared.identity.id(), loopback(phone.address().port())).unwrap();
        let arrived = until(|| !phone_shared.replay(NOW + 1).sessions.running.is_empty());
        let took = began.elapsed();

        assert!(arrived, "the phone did not learn about the lock");
        assert!(took < Duration::from_secs(5), "the phone took {took:?} to find out");
    }

    #[test]
    fn what_the_phone_learns_wakes_the_rest_of_the_program() {
        // Enforcement is driven by this callback. A sync that lands silently is a lock that does
        // not apply until something else happens to look.
        let (phone_shared, pc_shared) = two();
        let woken = Arc::new(AtomicUsize::new(0));
        let counter = woken.clone();
        let phone_shared = phone_shared.notify(Arc::new(move || {
            counter.fetch_add(1, Ordering::SeqCst);
        }));

        let phone = Node::start(phone_shared.clone()).unwrap();
        let pc = Node::start(pc_shared.clone()).unwrap();
        pc_shared.record(NOW, start("pc-1", "deep-work"));
        pc.push(&phone_shared.identity.id(), loopback(phone.address().port())).unwrap();

        assert!(
            until(|| woken.load(Ordering::SeqCst) == 1),
            "the phone learned something and said nothing"
        );
    }

    #[test]
    fn nothing_new_wakes_nobody() {
        let (phone_shared, pc_shared) = two();
        let woken = Arc::new(AtomicUsize::new(0));
        let counter = woken.clone();
        let phone_shared = phone_shared.notify(Arc::new(move || {
            counter.fetch_add(1, Ordering::SeqCst);
        }));

        let phone = Node::start(phone_shared.clone()).unwrap();
        let pc = Node::start(pc_shared.clone()).unwrap();
        let to = loopback(phone.address().port());
        pc_shared.record(NOW, start("pc-1", "deep-work"));
        pc.push(&phone_shared.identity.id(), to).unwrap();
        assert!(until(|| woken.load(Ordering::SeqCst) == 1), "the first sync was not announced");
        pc.push(&phone_shared.identity.id(), to).unwrap();

        // The second pass carried the same entry. Give it as long as the first one was allowed
        // before believing it stayed quiet.
        assert!(
            !until(|| woken.load(Ordering::SeqCst) != 1),
            "a repeat sync was announced as news"
        );
    }

    #[test]
    fn a_peer_that_is_not_there_costs_an_error_and_nothing_else() {
        let (phone_shared, pc_shared) = two();
        let pc = Node::start(pc_shared).unwrap();
        // A port nothing is listening on: the phone is asleep, or has left.
        let closed = std::net::TcpListener::bind(loopback(0)).unwrap();
        let port = closed.local_addr().unwrap().port();
        drop(closed);

        assert!(pc.push(&phone_shared.identity.id(), loopback(port)).is_err());
        assert!(pc.shared().replay(NOW).sessions.running.is_empty());
    }

    #[test]
    fn a_device_that_was_revoked_is_not_synced_with() {
        let (phone_shared, pc_shared) = two();
        let phone = Node::start(phone_shared.clone()).unwrap();
        let pc = Node::start(pc_shared.clone()).unwrap();
        pc_shared.record(NOW, start("pc-1", "deep-work"));
        phone_shared.peers.lock().unwrap().revoke(&pc_shared.identity.id(), NOW).unwrap();

        let _ = pc.push(&phone_shared.identity.id(), loopback(phone.address().port()));

        assert!(
            !until(|| !phone_shared.replay(NOW + 1).sessions.running.is_empty()),
            "a removed device still changed what this one believes"
        );
    }

    #[test]
    fn a_node_that_is_dropped_stops_answering() {
        let (phone_shared, _) = two();
        let phone = Node::start(phone_shared).unwrap();
        let address = phone.address();
        phone.stop();

        // Whatever happens next, it must not be a working Curfew conversation.
        if let Ok(mut stream) = std::net::TcpStream::connect(address) {
            use std::io::Read as _;
            let mut buffer = [0u8; 1];
            assert!(
                matches!(stream.read(&mut buffer), Ok(0) | Err(_)),
                "a stopped node was still talking"
            );
        }
    }

    #[test]
    fn both_devices_syncing_at_once_end_up_agreeing() {
        let (phone_shared, pc_shared) = two();
        let phone = Node::start(phone_shared.clone()).unwrap();
        let pc = Node::start(pc_shared.clone()).unwrap();
        phone_shared.record(NOW, start("phone-1", "evenings"));
        pc_shared.record(NOW, start("pc-1", "deep-work"));

        pc.push(&phone_shared.identity.id(), loopback(phone.address().port())).unwrap();
        phone.push(&pc_shared.identity.id(), loopback(pc.address().port())).unwrap();

        assert!(
            until(|| phone_shared.replay(NOW + 1) == pc_shared.replay(NOW + 1)),
            "the two devices did not end up believing the same thing"
        );
        assert_eq!(phone_shared.replay(NOW + 1).sessions.running.len(), 2);
    }
}
