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
///
/// **Verify once, then one ascending pass per author** — P2-18.
///
/// The previous shape was a retry loop that called `Log::accept` on every pending entry on every pass,
/// and `accept` verifies the signature before it looks at anything else. A batch delivered in reverse
/// order — which is what a shared folder produces, since a sync client does not promise an order — is
/// then accepted one entry per pass, and each pass re-verifies everything it already rejected. For a
/// batch of n that is `n + (n-1) + … = O(n²)` Ed25519 verifications, and `MAX_FRAME` permits 8 MiB of
/// JSON, so a single paired peer could pin a thread for minutes with only `MAX_CONVERSATIONS` threads
/// available to it.
///
/// Both halves are fixed here:
///
///  - **The cryptography happens once per entry**, up front. An entry that fails is refused
///    immediately: it can never be accepted on a later pass within this batch, so retrying it was
///    pure cost.
///  - **The ordering is one pass per author**, not a fixed-point loop. A chain's order is total and
///    the predecessor check is per-author (`head(author)` reads only that author's entries), so
///    sorting each author's entries by sequence and walking them once reaches exactly the state the
///    retry loop converged to — the contiguous run above the current head is accepted, and anything
///    past a gap is refused.
///
/// `MAX_FRAME` still bounds the work; this bounds it linearly rather than quadratically.
pub fn receive(log: &mut Log, peers: &Peers, entries: &[Signed]) -> Received {
    let mut out = Received::default();

    // Verify once. A bad signature, or an author this device does not listen to, is a permanent
    // refusal within this batch — no ordering of the batch makes it acceptable.
    let mut verified: Vec<&Signed> = Vec::with_capacity(entries.len());
    for signed in entries {
        match signed.verify(peers) {
            Ok(()) => verified.push(signed),
            Err(_) => out.refused += 1,
        }
    }

    // An author's entries, in the only order that matters. `BTreeMap` gives a stable grouping without
    // needing the authors to be ordered among themselves, since chains do not interact.
    let mut by_author: BTreeMap<&DeviceId, Vec<&Signed>> = BTreeMap::new();
    for signed in verified {
        by_author.entry(&signed.entry.author).or_default().push(signed);
    }

    for chain in by_author.values_mut() {
        chain.sort_by_key(|signed| signed.entry.seq);
        for signed in chain.iter() {
            match log.apply(signed) {
                Ok(true) => out.accepted += 1,
                // Held already. Normal rather than an error: a shared folder redelivers everything it
                // has on every scan.
                Ok(false) => out.known += 1,
                // A gap in this chain, a rewrite of an entry already held, or a fork. Refused now and
                // re-offered by the peer next time, which is why a transport that reorders is not a
                // problem to solve here.
                Err(_) => out.refused += 1,
            }
        }
    }

    out
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

    // --- what a batch costs (P2-18) ---------------------------------------------------------------
    //
    // The finding is a claim about *how many Ed25519 verifications* a batch costs, so these count them.
    // Nothing else distinguishes a linear `receive` from a quadratic one: both end with every entry
    // accepted and nothing refused. The count is the observable.

    /// A batch of `n` entries from the PC, in the form the wire carries it.
    fn batch(p: &Pair, n: usize) -> Vec<Signed> {
        let mut pc_log = Log::default();
        for i in 0..n {
            pc_log.append(&p.pc, NOW + i as i64, start(&format!("s{i}")));
        }
        pc_log.since(&BTreeMap::new())
    }

    /// **A batch delivered in reverse order costs one verification per entry.**
    ///
    /// This is the shape a shared folder produces, because a sync client does not promise an order. The
    /// old retry loop called `accept` on every pending entry every pass, and `accept` verifies before it
    /// looks at anything else — so n entries arriving backwards cost `n + (n-1) + … = O(n²)` checks. At
    /// n = 24 that is 300 where there should be 24, and `MAX_FRAME` permits far more than 24.
    #[test]
    fn a_reverse_order_batch_verifies_each_entry_once() {
        let p = paired();
        let mut backwards = batch(&p, 24);
        backwards.reverse();

        let mut phone_log = Log::default();
        crate::pair::verify_count::reset();
        let received = receive(&mut phone_log, &p.on_phone, &backwards);
        let checks = crate::pair::verify_count::taken();

        assert_eq!(received, Received { accepted: 24, known: 0, refused: 0 });
        assert_eq!(
            checks, 24,
            "each entry should be verified once; {checks} checks for 24 entries is the O(n²) retry loop"
        );
    }

    /// A second delivery of the same batch is all repeats, and still one check per entry — a shared
    /// folder redelivers everything on every scan, so this is the common case rather than the odd one.
    #[test]
    fn a_redelivered_batch_verifies_each_entry_once() {
        let p = paired();
        let entries = batch(&p, 12);
        let mut phone_log = Log::default();
        receive(&mut phone_log, &p.on_phone, &entries);

        crate::pair::verify_count::reset();
        let again = receive(&mut phone_log, &p.on_phone, &entries);

        assert_eq!(again, Received { accepted: 0, known: 12, refused: 0 });
        assert_eq!(
            crate::pair::verify_count::taken(),
            12,
            "a redelivery should be one check per entry"
        );
    }

    /// **An entry past a gap is refused, and the ones below it are still taken.**
    ///
    /// This is the property the retry loop existed for, and the one a single ascending pass has to
    /// preserve: order within the batch does not matter, and a missing predecessor holds back only what
    /// depends on it.
    #[test]
    fn a_gap_refuses_only_what_depends_on_it() {
        let p = paired();
        let entries = batch(&p, 5);

        // Everything except entry 2, shuffled: 5, 1, 4, 3.
        let delivered: Vec<Signed> =
            vec![entries[4].clone(), entries[0].clone(), entries[3].clone(), entries[2].clone()];
        let mut phone_log = Log::default();
        let got = receive(&mut phone_log, &p.on_phone, &delivered);

        assert_eq!(got.accepted, 1, "only entry 1 is reachable without entry 2: {got:?}");
        assert_eq!(got.refused, 3, "entries 3, 4 and 5 all depend on the gap: {got:?}");

        // And closing the gap takes the rest, in one more delivery.
        let filling = receive(&mut phone_log, &p.on_phone, &entries);
        assert_eq!(filling.accepted, 4, "the gap did not clear: {filling:?}");
        assert_eq!(filling.refused, 0);
    }

    /// **A broken signature is refused once, not retried.** Under the old loop it stayed pending and was
    /// re-verified on every pass, so a peer could make the receiver re-check a bad signature as often as
    /// it liked by padding a batch with them.
    #[test]
    fn a_bad_signature_is_verified_once_and_refused() {
        let p = paired();
        let mut entries = batch(&p, 4);
        entries[3].signature = [0u8; 64];

        let mut phone_log = Log::default();
        crate::pair::verify_count::reset();
        let got = receive(&mut phone_log, &p.on_phone, &entries);
        let checks = crate::pair::verify_count::taken();

        assert_eq!(got.accepted, 3, "the good entries should still be taken: {got:?}");
        assert_eq!(got.refused, 1, "the broken signature should be refused once: {got:?}");
        assert_eq!(
            checks, 4,
            "a bad signature was re-verified; {checks} checks for 4 entries means it was retried"
        );
    }

    /// And an entry signed by a device this one has never paired with is refused once, not retried.
    ///
    /// `DeviceId` is a hash of a public key, so a stranger cannot simply be named — the fixture is a real
    /// identity that the receiving peer table has never accepted, with an entry genuinely signed by it.
    /// That is the shape a peer that has been revoked, or one that was never invited, actually presents.
    #[test]
    fn an_unknown_author_is_verified_once_and_refused() {
        let p = paired();
        let stranger = Identity::generate("stranger");
        let mut stranger_log = Log::default();
        stranger_log.append(&stranger, NOW, start("s1"));
        let mut entries = batch(&p, 3);
        // A fourth entry from a device `on_phone` does not know. Its own chain is valid — it starts at
        // sequence 1 and is signed properly — so the *only* reason to refuse it is the author.
        entries.extend(stranger_log.since(&BTreeMap::new()));

        let mut phone_log = Log::default();
        crate::pair::verify_count::reset();
        let got = receive(&mut phone_log, &p.on_phone, &entries);

        assert_eq!(got.accepted, 3, "the paired device's entries should still be taken: {got:?}");
        assert_eq!(got.refused, 1, "an unknown author should be refused: {got:?}");
        assert_eq!(
            crate::pair::verify_count::taken(),
            4,
            "the unknown author was re-verified rather than refused once"
        );
    }
}
