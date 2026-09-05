//! The op-log: everything the devices have to agree about, as facts that can be replayed.
//!
//! Devices are offline half the time and see each other's news out of order, so nothing here is a
//! command and nothing is a state transfer. Every device appends signed *operations* to its own
//! chain, exchanges them however it can, and folds the union of everybody's chains into state. Two
//! devices holding the same set of entries always compute the same state, whatever order the
//! entries arrived in (GAPS C4).
//!
//! Three rules make that safe rather than merely convergent:
//!
//! * **Replay is a fold in a fixed order** — by time, then author, then sequence number — so it is
//!   a pure function of the *set* of entries. Arrival order cannot change what a device believes.
//! * **An end is a request, not an instruction.** A peer saying "I ended this" cannot end a
//!   session that is still locked here. Otherwise sync would be the way out of every lock, and a
//!   stolen phone would be a master key (invariant 2).
//! * **Each author's entries form a hash chain.** A device that quietly drops or rewrites its own
//!   history breaks the chain, and the break is visible to everyone rather than being merged in.

use crate::device::{DeviceId, Identity};
use crate::pair::{Error as PairError, Peers};
use curfew_core::budget::{Consumption, Launches};
use curfew_core::emergency::Passes;
use curfew_core::session::{Session, Sessions};
use curfew_core::{CalendarEvent, Timestamp};
use serde::{Deserialize, Serialize};
use sha2::{Digest as _, Sha256};
use std::collections::{BTreeMap, BTreeSet};

/// The hash that starts every author's chain.
pub const GENESIS: [u8; 32] = [0; 32];

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum Error {
    #[error(transparent)]
    Peer(#[from] PairError),
    #[error("entry {seq} from {author} does not follow the one before it")]
    Forked { author: DeviceId, seq: u64 },
    #[error("entry {seq} from {author} contradicts an entry already held with that number")]
    Rewritten { author: DeviceId, seq: u64 },
}

/// One thing that happened, worth telling the other devices about.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum Op {
    /// A session began. Replayed through [`Sessions::start`], so seeing it twice, or seeing it
    /// alongside another device's start for the same profile, merges the locks rather than
    /// duplicating or weakening anything.
    Start { session: Box<Session> },
    /// A session ended *on the device that sent this*. Whether it ends here as well is decided by
    /// the local lock, not by the sender.
    End { session: String },
    /// The 24-hour release was started. Idempotent, and can only ever move the release earlier.
    ReleaseRequested { session: String, at: Timestamp },
    /// Time spent against a target, so a budget is shared across devices instead of being spent
    /// twice (once on each).
    Used { key: String, at: Timestamp, seconds: u32 },
    /// A target was opened, for launch limits.
    Launched { key: String, at: Timestamp },
    /// A device was removed. Carried in the log so the other devices stop listening to it too,
    /// without the user having to remove it separately on each one.
    Revoked { device: DeviceId },
    /// What this device's calendars say is coming, so a device that cannot see a calendar can still
    /// be blocked by one — a desktop with no subscription, or a phone whose calendar permission was
    /// never granted.
    ///
    /// A snapshot rather than a stream of changes: a calendar is small, it is entirely derived
    /// state, and replacing it wholesale means a deleted meeting actually disappears instead of
    /// needing its own tombstone. Only the events some rule on the sending device would act on are
    /// ever put in here, so the log carries the meetings that matter to a block and not a
    /// transcript of someone's week.
    Calendar { events: Vec<CalendarEvent> },
    /// "I, the device that signed this, release that session."
    ///
    /// The satisfying evidence for `Lock::PeerRelease { device_id }`, and the reason the entry says
    /// only which session: *who* released it is the entry's author, which is signed, so a device
    /// cannot release on another's behalf by writing a different name in the message. Grow-only,
    /// like every other permission in the log — a release once given is not taken back, because a
    /// peer who could withdraw one could hold a lock shut that the user was already promised out
    /// of.
    Released { session: String },
    /// An emergency pass was spent. Carried so the quota is one quota across every device rather
    /// than one per device, and so a phone kept offline for a week does not come back with a fresh
    /// allowance. Merging is union of timestamps, which cannot give a pass back.
    EmergencyUsed { at: Timestamp },
}

/// An operation, with everything needed to place it in its author's chain.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Entry {
    pub author: DeviceId,
    /// The author's own counter, from 1. Gaps are not tolerated: a missing entry is a hole in the
    /// chain, not a hint to carry on.
    pub seq: u64,
    /// The hash of the author's previous entry, or [`GENESIS`].
    pub prev: [u8; 32],
    pub at: Timestamp,
    pub op: Op,
}

impl Entry {
    /// The bytes that are signed and chained. Serialized through the canonical form rather than
    /// hashing a struct in memory, so two devices on different platforms hash the same bytes.
    pub fn canonical(&self) -> Vec<u8> {
        serde_json::to_vec(self).expect("an entry is always serializable")
    }

    pub fn hash(&self) -> [u8; 32] {
        let mut hasher = Sha256::new();
        hasher.update(b"curfew-entry-v1");
        hasher.update(self.canonical());
        hasher.finalize().into()
    }
}

/// An entry as it travels: signed by its author, verifiable by anyone holding their public key.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Signed {
    pub entry: Entry,
    #[serde(with = "signature_bytes")]
    pub signature: [u8; 64],
}

pub(crate) mod signature_bytes {
    use serde::{Deserialize as _, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(bytes: &[u8; 64], s: S) -> Result<S::Ok, S::Error> {
        s.collect_seq(bytes.iter())
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[u8; 64], D::Error> {
        let bytes = Vec::<u8>::deserialize(d)?;
        bytes.try_into().map_err(|_| serde::de::Error::custom("a signature is 64 bytes"))
    }
}

impl Signed {
    pub fn verify(&self, peers: &Peers) -> Result<(), Error> {
        Ok(peers.verify(&self.entry.author, &self.entry.canonical(), &self.signature)?)
    }
}

/// What replaying the log produces: the state every device should agree on.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Replay {
    pub sessions: Sessions,
    pub usage: BTreeMap<String, Consumption>,
    pub launches: BTreeMap<String, Launches>,
    /// Devices the log itself says are gone.
    pub revoked: Vec<DeviceId>,
    /// Each device's latest calendar snapshot, keyed by the device that sent it. Kept per author
    /// rather than merged so that a device going quiet does not silently lose its events, and so a
    /// later snapshot from one device cannot delete another's.
    pub calendars: BTreeMap<DeviceId, Vec<CalendarEvent>>,
    /// Sessions a peer ended that are still locked here. Not an error and not a conflict to
    /// resolve — the lock is doing its job — but the UI is owed an explanation for why the phone
    /// says one thing and the PC another.
    pub still_locked: Vec<String>,
    /// Which devices have released which session, from `Op::Released`. Keyed by session, so the
    /// evidence for a `Lock::PeerRelease` is a lookup rather than a scan of the whole log.
    #[serde(default)]
    pub released: BTreeMap<String, BTreeSet<DeviceId>>,
    /// Every emergency pass spent anywhere, so the ration is global. Defaulted rather than
    /// required, so a checkpoint written by an older build still loads.
    #[serde(default)]
    pub passes: Passes,
}

/// Every entry this device holds, its own included.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Log {
    /// Keyed by author and sequence, which makes insertion idempotent: the same entry arriving
    /// from two different peers is stored once. Written down as a plain list, because a map keyed
    /// by a pair is not expressible in JSON and the key is derivable from the entry anyway.
    #[serde(with = "entries_as_list")]
    entries: BTreeMap<(DeviceId, u64), Signed>,
    /// State folded in from entries that have since been compacted away.
    #[serde(default)]
    checkpoint: Option<Checkpoint>,
}

mod entries_as_list {
    use super::{DeviceId, Signed};
    use serde::{Deserialize as _, Deserializer, Serializer};
    use std::collections::BTreeMap;

    type Entries = BTreeMap<(DeviceId, u64), Signed>;

    pub fn serialize<S: Serializer>(entries: &Entries, s: S) -> Result<S::Ok, S::Error> {
        s.collect_seq(entries.values())
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Entries, D::Error> {
        Ok(Vec::<Signed>::deserialize(d)?
            .into_iter()
            .map(|signed| ((signed.entry.author.clone(), signed.entry.seq), signed))
            .collect())
    }
}

/// A summary standing in for entries that have been dropped.
///
/// The log grows forever otherwise. Everything before `through` is folded into `state` once, and
/// the entries themselves are discarded; the heads keep the chains verifiable so a later entry can
/// still be checked against the one it claims to follow.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Checkpoint {
    pub through: Timestamp,
    pub state: Replay,
    pub heads: BTreeMap<DeviceId, (u64, [u8; 32])>,
}

impl Log {
    /// Append an operation of this device's own, signing it and chaining it to the last one.
    pub fn append(&mut self, me: &Identity, at: Timestamp, op: Op) -> Signed {
        let (seq, prev) = self.head(&me.id());
        let entry = Entry { author: me.id(), seq: seq + 1, prev, at, op };
        let signature = me.sign(&entry.canonical());
        let signed = Signed { entry, signature };
        self.entries.insert((me.id(), seq + 1), signed.clone());
        signed
    }

    /// The last sequence number and hash for one author.
    pub fn head(&self, author: &DeviceId) -> (u64, [u8; 32]) {
        if let Some((_, signed)) =
            self.entries.range((author.clone(), 0)..=(author.clone(), u64::MAX)).next_back()
        {
            return (signed.entry.seq, signed.entry.hash());
        }
        match self.checkpoint.as_ref().and_then(|c| c.heads.get(author)) {
            Some((seq, hash)) => (*seq, *hash),
            None => (0, GENESIS),
        }
    }

    /// Take in an entry from somewhere else.
    ///
    /// Refuses anything it cannot attribute to a device it currently listens to, anything that does
    /// not follow that author's chain, and any attempt to replace an entry it already holds. A
    /// transport is not trusted, so this is where trust is established and nowhere later.
    pub fn accept(&mut self, signed: &Signed, peers: &Peers) -> Result<bool, Error> {
        signed.verify(peers)?;
        let key = (signed.entry.author.clone(), signed.entry.seq);
        if let Some(existing) = self.entries.get(&key) {
            // Same entry twice is ordinary — two transports, or a peer resending. A *different*
            // entry with the same number is an author rewriting its own past.
            return if existing == signed {
                Ok(false)
            } else {
                Err(Error::Rewritten { author: signed.entry.author.clone(), seq: signed.entry.seq })
            };
        }
        let (seq, prev) = self.head(&signed.entry.author);
        if signed.entry.seq != seq + 1 || signed.entry.prev != prev {
            return Err(Error::Forked {
                author: signed.entry.author.clone(),
                seq: signed.entry.seq,
            });
        }
        self.entries.insert(key, signed.clone());
        Ok(true)
    }

    /// Everything this device holds that the other side has not, given the heads it reported.
    pub fn since(&self, heads: &BTreeMap<DeviceId, u64>) -> Vec<Signed> {
        self.entries
            .iter()
            .filter(|((author, seq), _)| *seq > heads.get(author).copied().unwrap_or(0))
            .map(|(_, signed)| signed.clone())
            .collect()
    }

    /// What to send a peer so it can work out what to send back.
    pub fn heads(&self) -> BTreeMap<DeviceId, u64> {
        let mut heads: BTreeMap<DeviceId, u64> = BTreeMap::new();
        if let Some(checkpoint) = &self.checkpoint {
            for (author, (seq, _)) in &checkpoint.heads {
                heads.insert(author.clone(), *seq);
            }
        }
        for (author, seq) in self.entries.keys() {
            let entry = heads.entry(author.clone()).or_default();
            *entry = (*entry).max(*seq);
        }
        heads
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty() && self.checkpoint.is_none()
    }

    /// Fold the log into state.
    ///
    /// Deterministic in the *set* of entries: sorted by time, then author, then sequence, so two
    /// devices that hold the same entries agree even if they received them in opposite orders and
    /// even if two entries share a timestamp.
    pub fn replay(&self, now: Timestamp) -> Replay {
        let mut state = self.checkpoint.as_ref().map(|c| c.state.clone()).unwrap_or_default();
        let mut ordered: Vec<&Entry> = self.entries.values().map(|s| &s.entry).collect();
        ordered.sort_by(|a, b| {
            a.at.cmp(&b.at).then_with(|| a.author.cmp(&b.author)).then_with(|| a.seq.cmp(&b.seq))
        });
        for entry in ordered {
            apply(&mut state, entry, now);
        }
        state.still_locked.sort();
        state.still_locked.dedup();
        state.revoked.sort();
        state.revoked.dedup();
        state
    }

    /// Replace everything up to `through` with a summary of it.
    ///
    /// Only entries strictly older than `through` go: an entry exactly at the boundary is kept, so
    /// two devices compacting at the same instant keep the same thing. Sessions and locks are
    /// carried whole into the summary — compaction is about size, and must not be a way to forget
    /// a promise.
    pub fn compact(&mut self, through: Timestamp, now: Timestamp) {
        let (old, kept): (Vec<_>, Vec<_>) = std::mem::take(&mut self.entries)
            .into_iter()
            .partition(|((_, _), signed)| signed.entry.at < through);
        self.entries = kept.into_iter().collect();

        let mut heads = self.checkpoint.as_ref().map(|c| c.heads.clone()).unwrap_or_default();
        let mut summarized =
            Log { entries: old.into_iter().collect(), checkpoint: self.checkpoint.take() };
        for (author, (seq, hash)) in summarized.heads_with_hashes() {
            let slot = heads.entry(author).or_insert((0, GENESIS));
            if seq >= slot.0 {
                *slot = (seq, hash);
            }
        }
        let mut state = summarized.replay(now);
        // Rollups and launches that no rule can still be counting are the bulk of the log; the
        // window is generous so that a monthly budget is never truncated by compaction.
        let horizon = through - 60 * 24 * 60 * 60;
        for consumption in state.usage.values_mut() {
            consumption.prune(horizon);
        }
        for launches in state.launches.values_mut() {
            launches.prune(horizon);
        }
        summarized.checkpoint = None;
        self.checkpoint = Some(Checkpoint { through, state, heads });
    }

    fn heads_with_hashes(&self) -> BTreeMap<DeviceId, (u64, [u8; 32])> {
        let mut heads: BTreeMap<DeviceId, (u64, [u8; 32])> = BTreeMap::new();
        if let Some(checkpoint) = &self.checkpoint {
            heads.extend(checkpoint.heads.clone());
        }
        for ((author, seq), signed) in &self.entries {
            let slot = heads.entry(author.clone()).or_insert((0, GENESIS));
            if *seq >= slot.0 {
                *slot = (*seq, signed.entry.hash());
            }
        }
        heads
    }
}

/// Fold one entry into the state. Every rule that protects a lock lives here.
fn apply(state: &mut Replay, entry: &Entry, now: Timestamp) {
    match &entry.op {
        Op::Start { session } => state.sessions.start((**session).clone()),
        Op::End { session } => {
            // The sender's lock was satisfied on the sender's device. Ours may be stricter — locks
            // merge across devices — so the local lock decides, exactly as it would for a button
            // pressed here. A peer can never be a way out.
            let locked = state
                .sessions
                .get(session)
                .is_some_and(|s| s.lock.is_locked() && !s.lock.is_expired(now));
            if locked {
                state.still_locked.push(session.clone());
            } else if state.sessions.end(session, now, &Default::default()).is_err() {
                // Already gone, or refused for a reason the lock owns. Either way there is nothing
                // to do and nothing to report: the session simply is not running here.
            }
        }
        Op::ReleaseRequested { session, at } => {
            // A peer says when its release lands, and a peer can say anything: a modified build, a
            // device with a wrong clock, or someone who worked out that the fastest way out of a
            // lock is to sync a release that has already arrived. So the claimed time is a
            // *lower* bound only — never earlier than a full 24 hours after the entry that asked
            // for it, which is the same wait the asking device had to serve.
            let earliest = entry.at + curfew_core::lock::DELAYED_RELEASE_SECONDS;
            let at = &(*at).max(earliest);
            // Only a session that is actually holding someone has a release to schedule. Recording
            // one against an unlocked session would turn a set that promises nothing into one that
            // does — and a set whose `ends_at: None` then reads as "until released" rather than as
            // "nothing here at all", which is how a merge could end up *removing* an end time.
            if let Some(running) =
                state.sessions.running.iter_mut().find(|s| &s.id == session && s.lock.is_locked())
            {
                // Through the lattice rather than by assignment, so a peer cannot push a release
                // that is already visible to the user further away.
                let mut proposed = running.lock.clone();
                proposed.delayed_release_at = Some(*at);
                running.lock = running.lock.merge(&proposed);
            }
        }
        Op::Used { key, at, seconds } => {
            state.usage.entry(key.clone()).or_default().record(*at, *seconds)
        }
        Op::Launched { key, at } => state.launches.entry(key.clone()).or_default().record(*at),
        Op::Revoked { device } => state.revoked.push(device.clone()),
        // Last snapshot wins, and only over its own author's previous one. Entries are applied in
        // time order, so this is the newest thing that device said about its calendar.
        Op::Calendar { events } => {
            state.calendars.insert(entry.author.clone(), events.clone());
        }
        // Kept even when no session by that id is running here yet: a release can legitimately
        // arrive before the start it refers to, and dropping it would make the order two devices
        // happened to sync in decide whether a lock opens.
        Op::Released { session } => {
            state.released.entry(session.clone()).or_default().insert(entry.author.clone());
        }
        // Grow-only, and never trusted to be in the past: a device claiming a use far in the
        // future only ever spends more of its own quota, never less.
        Op::EmergencyUsed { at } => state.passes.record(*at),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pair::Invite;
    use curfew_core::{Lock, LockSet, SessionSource};

    const NOW: Timestamp = 1_788_510_600;
    const HOUR: Timestamp = 3600;

    struct Pair {
        phone: Identity,
        pc: Identity,
        on_phone: Peers,
        on_pc: Peers,
    }

    fn paired() -> Pair {
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
        on_phone.accept(&phone, &Invite::new(&pc, NOW, [1; 16]), NOW).unwrap();
        on_pc.accept(&pc, &Invite::new(&phone, NOW, [1; 16]), NOW).unwrap();
        Pair { phone, pc, on_phone, on_pc }
    }

    fn session(
        id: &str,
        locks: impl IntoIterator<Item = Lock>,
        ends_at: Option<Timestamp>,
    ) -> Session {
        Session {
            id: id.into(),
            profile: "deep-work".into(),
            source: SessionSource::Manual,
            started_at: NOW,
            lock: LockSet::new(locks, ends_at),
        }
    }

    fn start(id: &str, locks: impl IntoIterator<Item = Lock>, ends_at: Option<Timestamp>) -> Op {
        Op::Start { session: Box::new(session(id, locks, ends_at)) }
    }

    /// Move every entry either side is missing, both ways, the way a real sync round does.
    fn exchange(a: (&mut Log, &Peers), b: (&mut Log, &Peers)) {
        let (log_a, peers_a) = a;
        let (log_b, peers_b) = b;
        for signed in log_b.since(&log_a.heads()) {
            log_a.accept(&signed, peers_a).unwrap();
        }
        for signed in log_a.since(&log_b.heads()) {
            log_b.accept(&signed, peers_b).unwrap();
        }
    }

    #[test]
    fn a_release_names_the_device_that_gave_it_without_saying_so() {
        // The op carries no author field on purpose: the entry is signed, so a device that wrote
        // one cannot put another device's name on it.
        let p = paired();
        let (mut on_pc, mut on_phone) = (Log::default(), Log::default());

        on_pc.append(
            &p.pc,
            NOW,
            start("s1", [Lock::PeerRelease { device_id: p.pc.id().as_str().into() }], None),
        );
        on_pc.append(&p.pc, NOW + 60, Op::Released { session: "s1".into() });
        exchange((&mut on_phone, &p.on_phone), (&mut on_pc, &p.on_pc));

        let believed = on_phone.replay(NOW + HOUR);
        assert_eq!(
            believed.released.get("s1").map(|d| d.len()),
            Some(1),
            "the release did not arrive at the device holding the lock"
        );
        assert!(believed.released["s1"].contains(&p.pc.id()));
    }

    #[test]
    fn a_release_for_a_session_nobody_here_has_heard_of_is_still_kept() {
        // The lock the release opens is usually on the other device, so the entry routinely
        // arrives before -- or instead of -- the session it names.
        let p = paired();
        let (mut on_pc, mut on_phone) = (Log::default(), Log::default());

        on_pc.append(&p.pc, NOW, Op::Released { session: "never-seen".into() });
        exchange((&mut on_phone, &p.on_phone), (&mut on_pc, &p.on_pc));

        assert!(on_phone.replay(NOW + HOUR).released.contains_key("never-seen"));
    }

    #[test]
    fn a_release_cannot_be_taken_back_by_saying_anything_afterwards() {
        // There is no un-release op, and an End is only ever a request. Once a user has been told
        // they are free, no later entry may shut the door again.
        let p = paired();
        let mut log = Log::default();

        log.append(
            &p.pc,
            NOW,
            start("s1", [Lock::PeerRelease { device_id: p.pc.id().as_str().into() }], None),
        );
        log.append(&p.pc, NOW + 60, Op::Released { session: "s1".into() });
        log.append(&p.pc, NOW + 120, Op::ReleaseRequested { session: "s1".into(), at: NOW + 120 });
        log.append(&p.pc, NOW + 180, start("s1", [Lock::Confirm], None));

        assert!(log.replay(NOW + HOUR).released["s1"].contains(&p.pc.id()));
    }

    #[test]
    fn two_devices_releasing_the_same_session_both_count() {
        // A lock may name more than one device. The set is a union, so the order the entries
        // arrive in cannot decide which release survives.
        let p = paired();
        let (mut on_pc, mut on_phone) = (Log::default(), Log::default());

        on_pc.append(&p.pc, NOW, Op::Released { session: "s1".into() });
        on_phone.append(&p.phone, NOW, Op::Released { session: "s1".into() });
        exchange((&mut on_phone, &p.on_phone), (&mut on_pc, &p.on_pc));

        let here = on_pc.replay(NOW + HOUR).released["s1"].clone();
        let there = on_phone.replay(NOW + HOUR).released["s1"].clone();
        assert_eq!(here.len(), 2);
        assert_eq!(here, there, "the two devices disagreed about who had released the session");
    }

    #[test]
    fn a_session_started_on_one_device_runs_on_the_other() {
        let p = paired();
        let (mut on_pc, mut on_phone) = (Log::default(), Log::default());

        on_pc.append(&p.pc, NOW, start("s1", [Lock::Timer], Some(NOW + HOUR)));
        exchange((&mut on_phone, &p.on_phone), (&mut on_pc, &p.on_pc));

        let replayed = on_phone.replay(NOW);
        assert_eq!(replayed.sessions.active_profiles(NOW), vec!["deep-work"]);
        assert_eq!(replayed, on_pc.replay(NOW), "the two devices disagreed about what is running");
    }

    #[test]
    fn the_order_entries_arrive_in_cannot_change_what_a_device_believes() {
        // The whole point of an op-log: a phone that was in a tunnel gets the same answer as the
        // PC that was online the entire time.
        let p = paired();
        let mut source = Log::default();
        source.append(&p.pc, NOW, start("s1", [Lock::Timer], Some(NOW + HOUR)));
        source.append(&p.pc, NOW + 10, Op::Used { key: "app:x".into(), at: NOW + 10, seconds: 60 });
        source.append(&p.pc, NOW + 20, Op::Launched { key: "app:x".into(), at: NOW + 20 });

        let forwards = source.since(&BTreeMap::new());
        let mut backwards = forwards.clone();
        backwards.reverse();

        let mut in_order = Log::default();
        for signed in &forwards {
            in_order.accept(signed, &p.on_phone).unwrap();
        }
        // Out of order, an entry can arrive before the one it follows; a real transport retries,
        // so the test does too rather than pretending gaps never happen.
        let mut out_of_order = Log::default();
        let mut pending = backwards;
        while !pending.is_empty() {
            let before = pending.len();
            pending.retain(|signed| out_of_order.accept(signed, &p.on_phone).is_err());
            assert!(pending.len() < before, "no progress: the chain cannot be reassembled");
        }

        assert_eq!(in_order.replay(NOW + 30), out_of_order.replay(NOW + 30));
    }

    #[test]
    fn the_same_entry_arriving_twice_changes_nothing() {
        let p = paired();
        let mut source = Log::default();
        let signed = source.append(&p.pc, NOW, start("s1", [], Some(NOW + HOUR)));

        let mut log = Log::default();
        assert!(log.accept(&signed, &p.on_phone).unwrap(), "the first copy was not new");
        assert!(!log.accept(&signed, &p.on_phone).unwrap(), "the second copy was treated as new");
        assert_eq!(log.len(), 1);
    }

    #[test]
    fn two_devices_starting_the_same_profile_end_up_with_the_stricter_lock() {
        // Not a conflict to resolve: the lattice already says what the answer is, and it is the
        // conjunction. Neither device's start may weaken the other's.
        let p = paired();
        let (mut on_pc, mut on_phone) = (Log::default(), Log::default());
        on_pc.append(&p.pc, NOW, start("s1", [Lock::Timer], Some(NOW + HOUR)));
        on_phone.append(&p.phone, NOW, start("s2", [Lock::DeviceCredential], Some(NOW + 4 * HOUR)));

        exchange((&mut on_phone, &p.on_phone), (&mut on_pc, &p.on_pc));

        let state = on_phone.replay(NOW);
        assert_eq!(state, on_pc.replay(NOW));
        assert_eq!(state.sessions.running.len(), 1, "the same profile ran twice");
        let lock = &state.sessions.running[0].lock;
        assert!(lock.conditions.contains(&Lock::DeviceCredential));
        assert!(lock.conditions.contains(&Lock::Timer));
        assert_eq!(lock.ends_at, Some(NOW + 4 * HOUR), "the later end time did not win");
    }

    #[test]
    fn a_peer_cannot_end_a_session_that_is_still_locked_here() {
        // The attack this closes: end the session on the device with the weakest lock, let sync
        // carry the ending everywhere, and every device is free. Sync must never be the exit.
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [Lock::DeviceCredential], Some(NOW + HOUR)));
        log.append(&p.pc, NOW + 60, Op::End { session: "s1".into() });

        let state = log.replay(NOW + 60);

        assert_eq!(state.sessions.running.len(), 1, "sync unlocked a locked session");
        assert_eq!(state.still_locked, vec!["s1".to_string()], "nothing explained the difference");
    }

    #[test]
    fn a_peer_ending_an_unlocked_session_does_end_it_here() {
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [], Some(NOW + HOUR)));
        log.append(&p.pc, NOW + 60, Op::End { session: "s1".into() });

        let state = log.replay(NOW + 60);
        assert!(state.sessions.running.is_empty(), "an unlocked session outlived its ending");
        assert!(state.still_locked.is_empty());
    }

    #[test]
    fn an_end_that_arrives_before_the_start_still_ends_nothing_it_should_not() {
        // Timestamps order the fold, so an end sent at an earlier instant than the start it refers
        // to must not be able to cancel it.
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW + 60, Op::End { session: "s1".into() });
        log.append(&p.pc, NOW, start("s1", [Lock::Timer], Some(NOW + HOUR)));

        assert_eq!(log.replay(NOW + 120).sessions.running.len(), 1);
    }

    #[test]
    fn a_release_a_peer_started_is_honoured_and_never_pushed_back() {
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [Lock::DeviceCredential], None));
        log.append(
            &p.pc,
            NOW + 10,
            Op::ReleaseRequested { session: "s1".into(), at: NOW + 24 * HOUR },
        );
        // A second request, later — from a device whose clock is behind, or simply a second press.
        log.append(
            &p.pc,
            NOW + 20,
            Op::ReleaseRequested { session: "s1".into(), at: NOW + 48 * HOUR },
        );

        let state = log.replay(NOW + 20);
        assert_eq!(
            state.sessions.running[0].lock.delayed_release_at,
            // Measured from the entry that asked, not from the time the asking device claimed:
            // the first press was at NOW + 10, so the wait ends a day after that.
            Some(NOW + 10 + 24 * HOUR),
            "a release the user could already see was moved further away"
        );
    }

    #[test]
    fn a_peer_cannot_claim_a_release_that_has_already_landed() {
        // The shortest path out of a lock, if this were trusted: run a modified build on the phone,
        // announce a release timed for a second from now, and let sync carry it to the PC.
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [Lock::DeviceCredential], None));
        log.append(&p.pc, NOW + 10, Op::ReleaseRequested { session: "s1".into(), at: NOW + 11 });

        let state = log.replay(NOW + 12);

        assert_eq!(
            state.sessions.running[0].lock.delayed_release_at,
            Some(NOW + 10 + 24 * HOUR),
            "a peer shortened the 24-hour release"
        );
        assert_eq!(state.sessions.running.len(), 1);
    }

    #[test]
    fn a_budget_is_spent_once_across_both_devices_rather_than_twice() {
        let p = paired();
        let (mut on_pc, mut on_phone) = (Log::default(), Log::default());
        on_pc.append(&p.pc, NOW, Op::Used { key: "app:x".into(), at: NOW, seconds: 600 });
        on_phone.append(&p.phone, NOW, Op::Used { key: "app:x".into(), at: NOW, seconds: 300 });

        exchange((&mut on_phone, &p.on_phone), (&mut on_pc, &p.on_pc));

        let used = on_phone.replay(NOW).usage["app:x"].used_since(None);
        assert_eq!(used, 900, "an allowance was available in full on each device");
        assert_eq!(used, on_pc.replay(NOW).usage["app:x"].used_since(None));
    }

    #[test]
    fn an_entry_from_a_device_that_was_never_paired_is_refused() {
        let p = paired();
        let stranger = Identity::generate("stranger");
        let mut theirs = Log::default();
        let signed = theirs.append(&stranger, NOW, start("s1", [], Some(NOW + HOUR)));

        let mut mine = Log::default();
        assert_eq!(mine.accept(&signed, &p.on_phone), Err(Error::Peer(PairError::Unknown)));
        let _ = stranger;
        assert!(mine.is_empty(), "an unsigned-for entry got in anyway");
    }

    #[test]
    fn an_entry_from_a_revoked_device_is_refused_from_the_moment_it_is_revoked() {
        let mut p = paired();
        let mut theirs = Log::default();
        let signed = theirs.append(&p.pc, NOW, start("s1", [], Some(NOW + HOUR)));

        p.on_phone.revoke(&p.pc.id(), NOW).unwrap();

        let mut mine = Log::default();
        assert!(matches!(
            mine.accept(&signed, &p.on_phone),
            Err(Error::Peer(PairError::Revoked { .. }))
        ));
    }

    #[test]
    fn a_tampered_entry_does_not_verify() {
        let p = paired();
        let mut log = Log::default();
        let mut signed = log.append(&p.pc, NOW, start("s1", [Lock::DeviceCredential], None));
        // Exactly the change a hostile transport would make: keep the signature, weaken the lock.
        signed.entry.op = start("s1", [], Some(NOW + 1));

        let mut mine = Log::default();
        assert!(mine.accept(&signed, &p.on_phone).is_err(), "a rewritten entry was accepted");
    }

    #[test]
    fn an_author_cannot_rewrite_its_own_past() {
        let p = paired();
        let mut theirs = Log::default();
        let first = theirs.append(&p.pc, NOW, start("s1", [Lock::DeviceCredential], None));

        let mut mine = Log::default();
        mine.accept(&first, &p.on_phone).unwrap();

        // The same sequence number, signed properly, saying something else.
        let mut rewritten = Log::default();
        let second = rewritten.append(&p.pc, NOW, start("s1", [], Some(NOW + 1)));
        assert_eq!(
            mine.accept(&second, &p.on_phone),
            Err(Error::Rewritten { author: p.pc.id(), seq: 1 })
        );
    }

    #[test]
    fn an_entry_that_skips_the_one_before_it_is_held_rather_than_merged() {
        let p = paired();
        let mut theirs = Log::default();
        theirs.append(&p.pc, NOW, start("s1", [], Some(NOW + HOUR)));
        let second = theirs.append(&p.pc, NOW + 10, Op::End { session: "s1".into() });

        let mut mine = Log::default();
        assert_eq!(
            mine.accept(&second, &p.on_phone),
            Err(Error::Forked { author: p.pc.id(), seq: 2 }),
            "a hole in an author's chain was papered over"
        );
    }

    #[test]
    fn only_what_a_peer_is_missing_is_sent() {
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [], Some(NOW + HOUR)));
        log.append(&p.pc, NOW + 10, Op::Launched { key: "app:x".into(), at: NOW + 10 });

        let mut heads = BTreeMap::new();
        heads.insert(p.pc.id(), 1);
        let missing = log.since(&heads);

        assert_eq!(missing.len(), 1);
        assert_eq!(missing[0].entry.seq, 2);
        assert_eq!(log.since(&log.heads()).len(), 0, "a peer in step was sent entries anyway");
    }

    #[test]
    fn compaction_shrinks_the_log_without_forgetting_a_promise() {
        // The exit criterion is a log that stays small forever. What it must not buy is a lock
        // that quietly disappears with the entries that started it.
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [Lock::DeviceCredential], None));
        for minute in 1..200 {
            log.append(
                &p.pc,
                NOW + minute * 60,
                Op::Used { key: "app:x".into(), at: NOW + minute * 60, seconds: 60 },
            );
        }
        let before = log.replay(NOW + 200 * 60);

        log.compact(NOW + 150 * 60, NOW + 200 * 60);

        assert!(log.len() < 60, "compaction kept {} entries", log.len());
        let after = log.replay(NOW + 200 * 60);
        assert_eq!(after.sessions, before.sessions, "a session was lost in compaction");
        assert_eq!(
            after.usage["app:x"].used_since(None),
            before.usage["app:x"].used_since(None),
            "spent budget came back"
        );
    }

    #[test]
    fn a_compacted_device_still_accepts_what_comes_next() {
        // Compaction throws entries away, so the chain has to stay verifiable from the summary
        // alone — otherwise the first entry after a compaction looks like a fork.
        let p = paired();
        let mut theirs = Log::default();
        theirs.append(&p.pc, NOW, start("s1", [], Some(NOW + HOUR)));
        theirs.append(&p.pc, NOW + 60, Op::Launched { key: "app:x".into(), at: NOW + 60 });

        let mut mine = Log::default();
        for signed in theirs.since(&BTreeMap::new()) {
            mine.accept(&signed, &p.on_phone).unwrap();
        }
        mine.compact(NOW + 120, NOW + 120);
        assert_eq!(mine.len(), 0, "nothing was actually compacted");

        let next =
            theirs.append(&p.pc, NOW + 180, Op::Launched { key: "app:x".into(), at: NOW + 180 });
        assert!(
            mine.accept(&next, &p.on_phone).unwrap(),
            "a compacted log rejected the next entry"
        );
        assert_eq!(mine.heads()[&p.pc.id()], 3);
    }

    #[test]
    fn a_compacted_log_never_asks_for_entries_it_has_already_folded_in() {
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [], Some(NOW + HOUR)));
        log.append(&p.pc, NOW + 60, Op::Launched { key: "app:x".into(), at: NOW + 60 });
        log.compact(NOW + 120, NOW + 120);

        assert_eq!(log.heads().get(&p.pc.id()), Some(&2));
    }

    #[test]
    fn a_removal_travels_so_it_does_not_have_to_be_repeated_on_every_device() {
        let p = paired();
        let lost = Identity::generate("lost phone");
        let mut log = Log::default();
        log.append(&p.pc, NOW, Op::Revoked { device: lost.id() });

        assert_eq!(log.replay(NOW).revoked, vec![lost.id()]);
    }

    #[test]
    fn entries_survive_the_wire_unchanged() {
        let p = paired();
        let mut log = Log::default();
        let signed =
            log.append(&p.pc, NOW, start("s1", [Lock::DeviceCredential], Some(NOW + HOUR)));

        let text = serde_json::to_string(&signed).unwrap();
        let restored: Signed = serde_json::from_str(&text).unwrap();

        assert_eq!(restored, signed);
        restored.verify(&p.on_phone).expect("a round trip broke the signature");
    }

    #[test]
    fn a_whole_log_survives_being_written_down_and_read_back() {
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [Lock::DeviceCredential], None));
        log.append(&p.pc, NOW + 60, Op::Used { key: "app:x".into(), at: NOW + 60, seconds: 60 });
        log.compact(NOW + 30, NOW + 60);

        let text = serde_json::to_string(&log).unwrap();
        let restored: Log = serde_json::from_str(&text).unwrap();

        assert_eq!(restored, log);
        assert_eq!(restored.replay(NOW + 60), log.replay(NOW + 60));
    }

    #[test]
    fn a_release_against_a_session_that_holds_nobody_invents_no_promise() {
        // A release only means something for a lock. Recorded against an unlocked session it would
        // build a lock set that says "until released", and merging that with a real one could then
        // take away an end time the user had been shown — syncing making a promise weaker, which
        // is the one thing this layer may never do.
        let p = paired();
        let mut log = Log::default();
        log.append(&p.pc, NOW, start("s1", [], None));
        log.append(&p.pc, NOW, Op::ReleaseRequested { session: "s1".into(), at: NOW });

        let session = log.replay(NOW).sessions.get("s1").cloned().expect("s1 is running");

        assert!(session.lock.is_empty(), "a release invented a lock: {:?}", session.lock);
    }
}
