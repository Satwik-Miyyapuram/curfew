//! What a year of using Curfew costs on disk, and what an unattended device costs when it crashes.
//!
//! The Phase 3 exit criteria put a number on the first — under 5 MB per device per year — because
//! an op-log that only ever grows is the usual way a sync design becomes unusable on a phone after
//! a few months. The second is here for the same reason: a device that is killed mid-sentence is
//! the ordinary case on a phone, not an exceptional one, so "both sides are still in a valid state
//! afterwards" has to be a test rather than an intention.

use curfew_core::session::{Session, SessionSource};
use curfew_core::{Lock, LockSet, Timestamp};
use curfew_sync::device::Identity;
use curfew_sync::oplog::{Log, Op};
use curfew_sync::pair::{Invite, Peers};
use curfew_sync::wire::{self, Message};
use std::collections::BTreeMap;

const NOW: Timestamp = 1_788_510_600;
const DAY: Timestamp = 24 * 60 * 60;
const MONTH: Timestamp = 30 * DAY;

fn paired() -> (Identity, Identity, Peers, Peers) {
    let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
    let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
    on_phone.accept(&phone, &Invite::new(&pc, NOW, [3; 16]), NOW).unwrap();
    on_pc.accept(&pc, &Invite::new(&phone, NOW, [3; 16]), NOW).unwrap();
    (phone, pc, on_phone, on_pc)
}

fn session(id: &str, at: Timestamp) -> Op {
    Op::Start {
        session: Box::new(Session {
            id: id.into(),
            profile: "deep-work".into(),
            source: SessionSource::Manual,
            started_at: at,
            lock: LockSet::new([Lock::DeviceCredential], None),
        }),
    }
}

/// One day of heavier-than-typical use: four blocks, and a target opened and counted against every
/// waking half hour. Real devices write less than this.
fn a_day(log: &mut Log, me: &Identity, day: Timestamp) {
    for block in 0..4 {
        let at = day + block * 3 * 60 * 60;
        log.append(me, at, session(&format!("s{day}-{block}"), at));
        log.append(me, at + 60 * 60, Op::End { session: format!("s{day}-{block}") });
    }
    for slot in 0..32 {
        let at = day + slot * 30 * 60;
        log.append(me, at, Op::Launched { key: "com.instagram.android".into(), at });
        log.append(me, at, Op::Used { key: "com.instagram.android".into(), at, seconds: 900 });
    }
}

#[test]
fn a_year_of_use_stays_well_under_the_five_megabyte_budget() {
    let (_, pc, _, _) = paired();
    let mut log = Log::default();
    let mut biggest = 0usize;

    for day in 0..365 {
        let at = NOW + day * DAY;
        a_day(&mut log, &pc, at);
        // Compaction is what keeps this bounded, and it is only worth measuring as the device would
        // actually run it: on a schedule, keeping the recent past whole.
        if day % 30 == 29 {
            log.compact(at - MONTH, at);
        }
        biggest = biggest.max(serde_json::to_vec(&log).unwrap().len());
    }

    let bytes = serde_json::to_vec(&log).unwrap().len();
    let budget = 5 * 1024 * 1024;
    assert!(
        biggest < budget,
        "a year of use peaked at {biggest} bytes, over the {budget}-byte budget"
    );
    assert!(bytes < budget, "a year of use ended at {bytes} bytes, over the {budget}-byte budget");
}

#[test]
fn compaction_never_drops_a_lock_that_is_still_running() {
    // The cheapest way to make the log small would be to forget old sessions. A lock is a promise,
    // so the summary has to carry a running one however old it is.
    let (_, pc, _, _) = paired();
    let mut log = Log::default();
    log.append(&pc, NOW, session("long", NOW));
    for day in 1..200 {
        a_day(&mut log, &pc, NOW + day * DAY);
    }
    log.compact(NOW + 199 * DAY, NOW + 200 * DAY);

    let believed = log.replay(NOW + 200 * DAY);
    assert_eq!(believed.sessions.running.len(), 1, "a running lock was compacted away");
    assert!(believed.sessions.running[0].lock.is_locked());
}

#[test]
fn a_device_killed_halfway_through_a_sync_leaves_both_sides_valid() {
    // The phone is put in a pocket mid-exchange. What has landed must be whole, what has not must
    // arrive on the next pass, and neither side may be left holding a history with a hole in it.
    let (phone, pc, on_phone, _) = paired();
    let mut pc_log = Log::default();
    for i in 0..20 {
        let at = NOW + i * 60;
        pc_log.append(&pc, at, session(&format!("s{i}"), at));
    }
    let batch = pc_log.since(&BTreeMap::new());

    for cut in 0..batch.len() {
        let mut phone_log = Log::default();
        // Everything up to the cut arrived; the rest was lost with the connection.
        wire::receive(&mut phone_log, &on_phone, &batch[..cut]);
        assert_eq!(phone_log.len(), cut, "a half-delivered batch left the log in a bad state");
        assert!(phone_log.replay(NOW + DAY).sessions.running.len() <= cut);

        // The next pass asks for exactly what is missing, and nothing is lost.
        let Message::Heads(heads) = wire::greet(&phone_log) else { unreachable!() };
        let Message::Entries(rest) = wire::answer(&pc_log, &heads) else { unreachable!() };
        wire::receive(&mut phone_log, &on_phone, &rest);
        assert_eq!(
            phone_log.replay(NOW + DAY),
            pc_log.replay(NOW + DAY),
            "the devices did not agree after resuming from a cut at {cut}"
        );
    }
    let _ = phone;
}

#[test]
fn a_batch_truncated_in_the_middle_of_a_frame_is_refused_rather_than_half_applied() {
    // A cut connection can also deliver a *damaged* entry rather than a missing one. Taking part of
    // one would mean a device acting on something nobody signed.
    let (_, pc, on_phone, _) = paired();
    let mut pc_log = Log::default();
    pc_log.append(&pc, NOW, session("s1", NOW));
    let mut batch = pc_log.since(&BTreeMap::new());
    batch[0].signature[0] ^= 1;

    let mut phone_log = Log::default();
    let received = wire::receive(&mut phone_log, &on_phone, &batch);

    assert_eq!(received.accepted, 0);
    assert_eq!(received.refused, 1);
    assert!(phone_log.is_empty(), "a damaged entry was taken in");
}
