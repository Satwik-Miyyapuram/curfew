//! What actually travels between two devices, and the rule for handling it.
//!
//! The protocol is deliberately smaller than the transports that carry it. A device says which
//! entries it already has; the other sends what is missing; both sides fold the result into their
//! log. There is no negotiation, no leader, no session state to lose, so an exchange that is cut in
//! half is not a broken state but simply a shorter exchange — which matters, because one supported
//! transport is a folder that syncs when it feels like it and another is a phone leaving a room.
//!
//! Every packet is sealed for exactly one peer (see [`crate::envelope`]) and every entry inside is
//! signed by its author, so a transport carries bytes it can neither read nor alter.

use crate::device::{DeviceId, Identity, PublicIdentity};
use crate::envelope::{self, Sealed};
use crate::oplog::{Log, Signed};
use crate::pair::Peers;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum Error {
    #[error("this packet was not for us, or it has been altered")]
    Sealed(#[from] envelope::Error),
    #[error("this packet is not a Curfew message")]
    Malformed,
    #[error("that device is not paired with this one")]
    Unpaired,
}

/// One message. Two shapes are enough: what I have, and what you were missing.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Message {
    /// The highest sequence number this device holds from each author it knows about.
    ///
    /// Sent rather than a full listing because it is bounded by the number of devices, not by the
    /// length of history: two devices with a year of entries still greet each other in a few bytes.
    Heads(BTreeMap<DeviceId, u64>),
    /// Entries the other side did not have, in the order they must be applied.
    Entries(Vec<Signed>),
}

/// A message with its sender named in the clear, because the receiver needs to know whose key to
/// try before it can open anything. The id is a claim and is treated as one: opening the envelope
/// is what proves it, since only the named device could have sealed it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Packet {
    pub from: DeviceId,
    pub sealed: Sealed,
}

/// Seal a message for one peer.
pub fn pack(me: &Identity, peer: &PublicIdentity, message: &Message) -> Packet {
    let plaintext = serde_json::to_vec(message).expect("a message is always serializable");
    Packet { from: me.id(), sealed: envelope::seal(me, peer, &plaintext) }
}

/// Open a packet, given the peers this device has paired with.
///
/// A packet from a device that was never paired, or that has been revoked, is refused before any
/// decryption is attempted — a removed device does not get to keep talking (GAPS C3).
pub fn unpack(me: &Identity, peers: &Peers, packet: &Packet) -> Result<Message, Error> {
    let peer = peers.active(&packet.from).map_err(|_| Error::Unpaired)?;
    let plaintext = envelope::open(me, &peer.identity, &packet.sealed)?;
    serde_json::from_slice(&plaintext).map_err(|_| Error::Malformed)
}

/// What this device wants to say first: everything it holds.
pub fn greet(log: &Log) -> Message {
    Message::Heads(log.heads())
}

/// What a peer is missing, given the heads it just announced.
pub fn answer(log: &Log, heads: &BTreeMap<DeviceId, u64>) -> Message {
    Message::Entries(log.since(heads))
}

/// What changed after taking in a batch.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Received {
    /// Entries that were new and are now held.
    pub accepted: usize,
    /// Entries that were already held. Normal, not an error: a shared folder redelivers everything
    /// it has every time it is scanned.
    pub known: usize,
    /// Entries that could not be taken: an unknown author, a broken signature, or a gap where the
    /// entry before it has not arrived yet. Held-back entries are simply re-offered next time,
    /// which is why a transport that reorders is not a problem to solve here.
    pub refused: usize,
}

/// Fold a batch into the log. Order within the batch does not matter and neither do repeats:
/// entries whose predecessor is missing are refused now and accepted on a later pass.
pub fn receive(log: &mut Log, peers: &Peers, entries: &[Signed]) -> Received {
    let mut out = Received::default();
    let mut pending: Vec<&Signed> = entries.iter().collect();
    loop {
        let before = pending.len();
        pending.retain(|signed| match log.accept(signed, peers) {
            Ok(true) => {
                out.accepted += 1;
                false
            }
            Ok(false) => {
                out.known += 1;
                false
            }
            Err(_) => true,
        });
        if pending.len() == before {
            out.refused = pending.len();
            return out;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::oplog::Op;
    use crate::pair::Invite;
    use curfew_core::session::{Session, SessionSource};
    use curfew_core::{Lock, LockSet, Timestamp};

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
        on_phone.accept(&phone, &Invite::new(&pc, NOW, [7; 16]), NOW).unwrap();
        on_pc.accept(&pc, &Invite::new(&phone, NOW, [7; 16]), NOW).unwrap();
        Pair { phone, pc, on_phone, on_pc }
    }

    fn start(id: &str) -> Op {
        Op::Start {
            session: Box::new(Session {
                id: id.into(),
                profile: "deep-work".into(),
                source: SessionSource::Manual,
                started_at: NOW,
                lock: LockSet::new([Lock::DeviceCredential], None),
            }),
        }
    }

    #[test]
    fn a_message_survives_the_round_trip_through_a_transport() {
        let p = paired();
        let message = Message::Heads(BTreeMap::from([(p.pc.id(), 4)]));

        let packet = pack(&p.pc, &p.phone.public(), &message);
        assert_eq!(unpack(&p.phone, &p.on_phone, &packet).unwrap(), message);
    }

    #[test]
    fn a_packet_from_a_stranger_is_refused_before_it_is_opened() {
        let p = paired();
        let stranger = Identity::generate("someone else");
        let packet = pack(&stranger, &p.phone.public(), &greet(&Log::default()));

        assert_eq!(unpack(&p.phone, &p.on_phone, &packet).unwrap_err(), Error::Unpaired);
    }

    #[test]
    fn a_revoked_device_stops_being_listened_to() {
        // The device is typically lost or stolen, so it will not cooperate in its own removal;
        // refusing its packets has to be a decision the remaining device can make alone.
        let mut p = paired();
        let packet = pack(&p.pc, &p.phone.public(), &greet(&Log::default()));
        p.on_phone.revoke(&p.pc.id(), NOW + 60).unwrap();

        assert_eq!(unpack(&p.phone, &p.on_phone, &packet).unwrap_err(), Error::Unpaired);
    }

    #[test]
    fn an_altered_packet_is_refused_rather_than_half_read() {
        let p = paired();
        let mut packet = pack(&p.pc, &p.phone.public(), &greet(&Log::default()));
        packet.sealed.ciphertext[0] ^= 1;

        assert_eq!(
            unpack(&p.phone, &p.on_phone, &packet).unwrap_err(),
            Error::Sealed(envelope::Error::NotForUs)
        );
    }

    #[test]
    fn rubbish_inside_a_valid_envelope_is_refused_rather_than_guessed_at() {
        let p = paired();
        let sealed = envelope::seal(&p.pc, &p.phone.public(), b"not a message at all");
        let packet = Packet { from: p.pc.id(), sealed };

        assert_eq!(unpack(&p.phone, &p.on_phone, &packet).unwrap_err(), Error::Malformed);
    }

    #[test]
    fn a_greeting_asks_for_exactly_what_is_missing_and_no_more() {
        let p = paired();
        let (mut phone_log, mut pc_log) = (Log::default(), Log::default());
        pc_log.append(&p.pc, NOW, start("s1"));
        pc_log.append(&p.pc, NOW + 5, start("s2"));

        // The phone already has the first entry.
        let first = pc_log.since(&BTreeMap::new())[0].clone();
        phone_log.accept(&first, &p.on_phone).unwrap();

        let Message::Heads(heads) = greet(&phone_log) else { panic!("a greeting is heads") };
        let Message::Entries(missing) = answer(&pc_log, &heads) else {
            panic!("an answer is entries")
        };

        assert_eq!(missing.len(), 1, "the PC resent an entry the phone already had");
        assert_eq!(missing[0].entry.seq, 2);
    }

    #[test]
    fn a_batch_delivered_backwards_still_lands_whole() {
        // A folder scan returns files in whatever order the filesystem felt like.
        let p = paired();
        let mut pc_log = Log::default();
        for i in 0..5 {
            pc_log.append(&p.pc, NOW + i, start(&format!("s{i}")));
        }
        let mut batch = pc_log.since(&BTreeMap::new());
        batch.reverse();

        let mut phone_log = Log::default();
        let received = receive(&mut phone_log, &p.on_phone, &batch);

        assert_eq!(received, Received { accepted: 5, known: 0, refused: 0 });
        assert_eq!(phone_log.replay(NOW + 10), pc_log.replay(NOW + 10));
    }

    #[test]
    fn redelivering_the_same_batch_changes_nothing_and_says_so() {
        let p = paired();
        let mut pc_log = Log::default();
        pc_log.append(&p.pc, NOW, start("s1"));
        let batch = pc_log.since(&BTreeMap::new());

        let mut phone_log = Log::default();
        let first = receive(&mut phone_log, &p.on_phone, &batch);
        let again = receive(&mut phone_log, &p.on_phone, &batch);

        assert_eq!(first.accepted, 1);
        assert_eq!(again, Received { accepted: 0, known: 1, refused: 0 });
    }

    #[test]
    fn an_entry_whose_predecessor_never_arrives_is_held_rather_than_applied() {
        // Applying it would mean accepting a history with a hole in it, and a hole is exactly what
        // a transport would produce if it were dropping the entries it did not like.
        let p = paired();
        let mut pc_log = Log::default();
        pc_log.append(&p.pc, NOW, start("s1"));
        pc_log.append(&p.pc, NOW + 5, start("s2"));
        let batch = pc_log.since(&BTreeMap::new());

        let mut phone_log = Log::default();
        let received = receive(&mut phone_log, &p.on_phone, &batch[1..]);

        assert_eq!(received, Received { accepted: 0, known: 0, refused: 1 });
        assert!(phone_log.is_empty(), "a gapped history was accepted");
    }

    #[test]
    fn a_forged_entry_inside_a_valid_packet_is_refused() {
        // Sealing proves who sent the packet. It says nothing about who wrote what is inside it,
        // which is why every entry carries its own signature.
        let p = paired();
        let mut theirs = Log::default();
        let liar = Identity::generate("liar");
        theirs.append(&liar, NOW, start("s1"));
        let batch = theirs.since(&BTreeMap::new());

        let mut phone_log = Log::default();
        assert_eq!(
            receive(&mut phone_log, &p.on_phone, &batch),
            Received { accepted: 0, known: 0, refused: 1 }
        );
    }

    #[test]
    fn two_devices_converge_by_greeting_and_answering_once_each() {
        let p = paired();
        let (mut phone_log, mut pc_log) = (Log::default(), Log::default());
        phone_log.append(&p.phone, NOW, start("phone-1"));
        pc_log.append(&p.pc, NOW + 1, start("pc-1"));

        let Message::Heads(from_phone) = greet(&phone_log) else { unreachable!() };
        let Message::Heads(from_pc) = greet(&pc_log) else { unreachable!() };
        let Message::Entries(for_phone) = answer(&pc_log, &from_phone) else { unreachable!() };
        let Message::Entries(for_pc) = answer(&phone_log, &from_pc) else { unreachable!() };
        receive(&mut phone_log, &p.on_phone, &for_phone);
        receive(&mut pc_log, &p.on_pc, &for_pc);

        assert_eq!(phone_log.replay(NOW + 10), pc_log.replay(NOW + 10));
    }
}
