//! Properties the sync layer has to hold for *any* history, not just the ones we thought of.
//!
//! The unit tests in `oplog.rs` pin specific scenarios. These generate them: random operations,
//! authored by random devices, delivered in random orders and in random-sized batches, with each
//! run checking the two things a serverless blocker cannot get wrong — that both devices end up
//! believing the same thing, and that no amount of syncing ever weakens a lock (GAPS C1, C4).

use curfew_core::session::{Session, SessionSource};
use curfew_core::{Lock, LockSet, Timestamp};
use curfew_sync::device::Identity;
use curfew_sync::oplog::{Log, Op, Replay};
use curfew_sync::pair::{Invite, Peers};
use proptest::prelude::*;
use std::collections::BTreeMap;

const NOW: Timestamp = 1_788_510_600;

fn any_lock() -> impl Strategy<Value = Lock> {
    prop_oneof![
        Just(Lock::Timer),
        Just(Lock::Confirm),
        Just(Lock::DeviceCredential),
        Just(Lock::RestartRequired),
    ]
}

fn any_op() -> impl Strategy<Value = Op> {
    prop_oneof![
        (0usize..3, prop::collection::vec(any_lock(), 0..3), prop::option::of(0i64..10_000),)
            .prop_map(|(profile, locks, ends)| Op::Start {
                session: Box::new(Session {
                    id: format!("s{profile}"),
                    profile: format!("p{profile}"),
                    source: SessionSource::Manual,
                    started_at: NOW,
                    lock: LockSet::new(locks, ends.map(|e| NOW + e)),
                }),
            }),
        (0usize..3).prop_map(|profile| Op::End { session: format!("s{profile}") }),
        (0usize..3, 0i64..10_000).prop_map(|(profile, at)| Op::ReleaseRequested {
            session: format!("s{profile}"),
            at: NOW + at,
        }),
        (0usize..3, 0i64..1000, 1u32..600).prop_map(|(key, at, seconds)| Op::Used {
            key: format!("app:{key}"),
            at: NOW + at,
            seconds,
        }),
        (0usize..3, 0i64..1000)
            .prop_map(|(key, at)| Op::Launched { key: format!("app:{key}"), at: NOW + at }),
    ]
}

/// A history as (which device authored it, at what time, what happened).
fn any_history() -> impl Strategy<Value = Vec<(bool, i64, Op)>> {
    prop::collection::vec((any::<bool>(), 0i64..2000, any_op()), 0..25)
}

struct Devices {
    phone: Identity,
    pc: Identity,
    on_phone: Peers,
    on_pc: Peers,
}

fn devices() -> Devices {
    let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
    let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
    on_phone.accept(&phone, &Invite::new(&pc, NOW, [1; 16]), NOW).unwrap();
    on_pc.accept(&pc, &Invite::new(&phone, NOW, [1; 16]), NOW).unwrap();
    Devices { phone, pc, on_phone, on_pc }
}

/// Write a history onto two devices, each authoring its own share.
fn author(d: &Devices, history: &[(bool, i64, Op)]) -> (Log, Log) {
    let (mut phone_log, mut pc_log) = (Log::default(), Log::default());
    for (on_phone, offset, op) in history {
        // A session is one *run*: the same id started again after being ended is a new run with its
        // own promises, so stamp each start with the moment it happened. Without this, comparing by
        // id alone would read "ended, then started again stricter" as a promise being weakened.
        let op = &match op.clone() {
            Op::Start { mut session } => {
                session.started_at = NOW + offset;
                Op::Start { session }
            }
            other => other,
        };
        if *on_phone {
            phone_log.append(&d.phone, NOW + offset, op.clone());
        } else {
            pc_log.append(&d.pc, NOW + offset, op.clone());
        }
    }
    (phone_log, pc_log)
}

/// Deliver everything each side is missing, retrying held-back entries until nothing moves. A
/// transport that reorders is normal; one that loses an entry forever is not, so retrying is what
/// a real one does.
fn sync(a: (&mut Log, &Peers), b: (&mut Log, &Peers)) {
    let (log_a, peers_a) = a;
    let (log_b, peers_b) = b;
    // Until nothing moves, not a fixed number of rounds: an entry held back because the one before
    // it has not arrived is delivered on a later pass, and a bounded loop would leave logs
    // half-delivered and make this helper, rather than the code, the thing under test.
    loop {
        let to_a = log_b.since(&log_a.heads());
        let to_b = log_a.since(&log_b.heads());
        if to_a.is_empty() && to_b.is_empty() {
            break;
        }
        for signed in to_a {
            let _ = log_a.accept(&signed, peers_a);
        }
        for signed in to_b {
            let _ = log_b.accept(&signed, peers_b);
        }
    }
}

/// Feed entries in the given order, retrying the ones held back, until the log stops growing.
fn absorb(entries: &[curfew_sync::oplog::Signed], peers: &Peers) -> Log {
    let mut log = Log::default();
    let mut pending: Vec<_> = entries.to_vec();
    loop {
        let before = pending.len();
        pending.retain(|signed| log.accept(signed, peers).is_err());
        if pending.len() == before {
            return log;
        }
    }
}

/// Is `after` at least as strong a promise as `before`, for every session they share?
fn never_weaker(before: &Replay, after: &Replay, now: Timestamp) -> Result<(), String> {
    for session in &before.sessions.running {
        // A different run under the same id is a different promise, not a weakened one: real ids
        // are unique per run, and the generator reuses a small pool precisely so that `End` ops
        // land on something.
        if let Some(other) = after.sessions.running.iter().find(|s| s.id == session.id) {
            if other.started_at != session.started_at {
                continue;
            }
        }
        let Some(now) = after
            .sessions
            .running
            .iter()
            .find(|s| s.id == session.id && s.started_at == session.started_at)
        else {
            // Disappearing is allowed for a session that was holding nobody, and for one whose
            // own terms have run out — a timer that expired, or a release that landed. What is
            // never allowed is a live lock ending because two devices talked to each other.
            if session.lock.is_locked() && !session.lock.is_expired(now) {
                return Err(format!("locked session {} vanished", session.id));
            }
            continue;
        };
        for condition in &session.lock.conditions {
            if !now.lock.conditions.contains(condition) {
                return Err(format!("{} lost the condition {condition:?}", session.id));
            }
        }
        match (session.lock.ends_at, now.lock.ends_at) {
            (Some(was), Some(is)) if is < was => {
                return Err(format!("{} now ends earlier than it promised", session.id))
            }
            // A session that holds nobody — no conditions, no end — is not a promise, so giving
            // it an end time takes nothing away. For a locked one it would: "until released"
            // outlasts every concrete time, and quietly acquiring one would be an exit.
            (None, Some(_)) if session.lock.is_locked() => {
                return Err(format!("{} gained an end time it did not have", session.id))
            }
            _ => {}
        }
        // Only for a session that is holding someone. An unlocked one can legitimately be ended
        // and started again under the same id, and the new run gets its own release timeline; a
        // locked one cannot, because `end` refuses it, so a later release there would be a real
        // extension of a promise already made.
        if let (Some(was), Some(is)) =
            (session.lock.delayed_release_at, now.lock.delayed_release_at)
        {
            if is > was && session.lock.is_locked() {
                return Err(format!("{}'s release was pushed further away", session.id));
            }
        }
    }
    Ok(())
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(200))]

    /// Both devices agree, whatever happened and in whatever order it reached them.
    #[test]
    fn two_devices_that_have_seen_everything_believe_the_same_thing(
        history in any_history(),
        at in 0i64..3000,
    ) {
        let d = devices();
        let (mut phone_log, mut pc_log) = author(&d, &history);

        sync((&mut phone_log, &d.on_phone), (&mut pc_log, &d.on_pc));

        prop_assert_eq!(phone_log.replay(NOW + at), pc_log.replay(NOW + at));
    }

    /// Replay depends on the *set* of entries, never on the order they arrived.
    #[test]
    fn arrival_order_cannot_change_the_answer(history in any_history(), at in 0i64..3000) {
        let d = devices();
        let (phone_log, pc_log) = author(&d, &history);

        let mut everything: Vec<_> =
            phone_log.since(&BTreeMap::new()).into_iter().chain(pc_log.since(&BTreeMap::new())).collect();

        let forwards = absorb(&everything, &d.on_phone);
        everything.reverse();
        let backwards = absorb(&everything, &d.on_phone);

        prop_assert_eq!(forwards.replay(NOW + at), backwards.replay(NOW + at));
    }

    /// Syncing can add promises. It can never take one away.
    #[test]
    fn syncing_never_weakens_a_lock(history in any_history(), at in 0i64..3000) {
        let d = devices();
        let (mut phone_log, mut pc_log) = author(&d, &history);
        let before = phone_log.replay(NOW + at);

        sync((&mut phone_log, &d.on_phone), (&mut pc_log, &d.on_pc));
        let after = phone_log.replay(NOW + at);

        let verdict = never_weaker(&before, &after, NOW + at);
        prop_assert!(verdict.is_ok(), "{verdict:?}");
    }

    /// Syncing twice is syncing once. A transport that redelivers everything — a shared folder
    /// being rescanned, a peer reconnecting — must not change anything.
    #[test]
    fn syncing_again_changes_nothing(history in any_history(), at in 0i64..3000) {
        let d = devices();
        let (mut phone_log, mut pc_log) = author(&d, &history);

        sync((&mut phone_log, &d.on_phone), (&mut pc_log, &d.on_pc));
        let once = phone_log.replay(NOW + at);
        sync((&mut phone_log, &d.on_phone), (&mut pc_log, &d.on_pc));

        prop_assert_eq!(phone_log.replay(NOW + at), once);
    }

    /// Compaction is about size and nothing else: the state it leaves behind is the state it found,
    /// and no lock is lighter for having been summarized.
    #[test]
    fn compaction_does_not_change_what_is_believed(history in any_history(), through in 0i64..2000) {
        let d = devices();
        let (mut phone_log, mut pc_log) = author(&d, &history);
        sync((&mut phone_log, &d.on_phone), (&mut pc_log, &d.on_pc));

        let at = NOW + 3000;
        let before = phone_log.replay(at);
        phone_log.compact(NOW + through, at);
        let after = phone_log.replay(at);

        prop_assert_eq!(&after.sessions, &before.sessions);
        prop_assert!(never_weaker(&before, &after, at).is_ok());
    }

    /// A device that has compacted still exchanges correctly with one that has not.
    #[test]
    fn a_compacted_device_and_a_fresh_one_still_agree(
        history in any_history(),
        through in 0i64..2000,
    ) {
        let d = devices();
        let (mut phone_log, mut pc_log) = author(&d, &history);
        sync((&mut phone_log, &d.on_phone), (&mut pc_log, &d.on_pc));

        let at = NOW + 3000;
        phone_log.compact(NOW + through, at);
        sync((&mut phone_log, &d.on_phone), (&mut pc_log, &d.on_pc));

        prop_assert_eq!(phone_log.replay(at).sessions, pc_log.replay(at).sessions);
    }
}
