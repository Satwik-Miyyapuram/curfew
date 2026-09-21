//! "If I end a block it stays ended" — across devices, not only on one.
//!
//! The core keeps its own promise in `curfew-core`: `reconcile` skips the occurrence a hand-end
//! dismissed. Sync is the other door into the running set, and it is a door this crate opens:
//! [`Mirror::adopt`] starts sessions the log says other devices are holding. These tests are about
//! that door — that adopting a peer's session can never put back a block this device's user ended.

use curfew_core::budget::{Consumption, Launches};
use curfew_core::schedule::{Activation, ActivationSource};
use curfew_core::session::{Session, SessionSource, Sessions};
use curfew_core::{LockSet, Timestamp};
use curfew_sync::device::Identity;
use curfew_sync::mirror::Mirror;
use curfew_sync::node::Shared;
use curfew_sync::oplog::Log;
use curfew_sync::pair::{Invite, Peers};
use std::collections::BTreeMap;

const NOW: Timestamp = 1_788_510_600;
const HOUR: Timestamp = 3600;

/// The window both devices are running: 09:00 to 17:00, in seconds from `NOW`.
const WINDOW_START: Timestamp = NOW;
const WINDOW_END: Timestamp = NOW + 8 * HOUR;
/// And the same profile's window the next day, which must still start normally.
const TOMORROW_END: Timestamp = NOW + 24 * HOUR + 8 * HOUR;

struct Device {
    shared: Shared,
    mirror: Mirror,
    sessions: Sessions,
    usage: BTreeMap<String, Consumption>,
    launches: BTreeMap<String, Launches>,
}

impl Device {
    fn pass(&mut self, now: Timestamp, activations: &[Activation]) -> curfew_sync::mirror::Pass {
        self.mirror.pass(
            &self.shared,
            now,
            &mut self.sessions,
            &mut self.usage,
            &mut self.launches,
            activations,
        )
    }
}

/// Two devices paired with each other, each with its own mirror over its own log.
fn two() -> (Device, Device) {
    let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
    let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
    on_phone.accept(&phone, &Invite::new(&pc, NOW, [9; 16]), NOW).unwrap();
    on_pc.accept(&pc, &Invite::new(&phone, NOW, [9; 16]), NOW).unwrap();
    let device = |identity, peers| Device {
        shared: Shared::new(identity, peers, Log::default()),
        mirror: Mirror::default(),
        sessions: Sessions::default(),
        usage: BTreeMap::new(),
        launches: BTreeMap::new(),
    };
    (device(phone, on_phone), device(pc, on_pc))
}

/// Hand everything one device holds to the other, the way a transport would.
fn carry(from: &Device, to: &Device) {
    let entries = {
        let log = from.shared.log.lock().unwrap();
        log.since(&to.shared.log.lock().unwrap().heads())
    };
    let peers = to.shared.peers.lock().unwrap();
    let mut log = to.shared.log.lock().unwrap();
    curfew_sync::wire::receive(&mut log, &peers, &entries);
}

/// The window a schedule is running, as `schedule::active_at` would produce it.
fn window(profile: &str, start: Timestamp, end: Timestamp) -> Activation {
    Activation {
        profile: profile.into(),
        source: ActivationSource::Weekly { schedule: "evenings".into() },
        start,
        end,
        locks: Vec::new(),
    }
}

/// A session a peer's own reconcile would have started for that window.
fn scheduled(id: &str, profile: &str, started_at: Timestamp, ends_at: Timestamp) -> Session {
    Session {
        id: id.into(),
        profile: profile.into(),
        source: SessionSource::Weekly { schedule: "evenings".into() },
        // `Activation::lock` pins a started session's end to the activation's end, which is what
        // makes an occurrence identifiable across devices.
        lock: LockSet::new([], Some(ends_at)),
        started_at,
    }
}

/// **Hole 4.** A peer that starts the same window *after* this device's user ended it must not put
/// the block back here.
///
/// The peer's session is the same occurrence — its own schedule started the same window — but it was
/// materialized later, so its `started_at` is after the end here. Before the fix, adoption compared
/// the dismissal against that `started_at` alone, read the peer's session as a *different*
/// occurrence, and adopted it: the block the user had just ended was running again a second later,
/// with nothing on this device having asked for it.
#[test]
fn a_peer_starting_the_same_window_later_does_not_restart_a_block_ended_here() {
    let (mut phone, mut pc) = two();

    // The phone's user satisfied the lock at 09:30 and ended the block.
    phone.sessions.dismissed.insert("deep-work".into(), NOW + 1800);

    // The PC was asleep at 09:00 and only wakes at 10:00, still inside the same window.
    pc.sessions.start(scheduled("pc-1", "deep-work", NOW + 3600, WINDOW_END));
    pc.pass(NOW + 3600, &[window("deep-work", WINDOW_START, WINDOW_END)]);
    carry(&pc, &phone);

    let pass = phone.pass(NOW + 3601, &[window("deep-work", WINDOW_START, WINDOW_END)]);

    assert!(pass.adopted.is_empty(), "a peer restarted the occurrence ended here: {pass:?}");
    assert!(
        phone.sessions.running.is_empty(),
        "the block came back after being ended: {:?}",
        phone.sessions.running
    );
    assert_eq!(
        phone.sessions.dismissed.get("deep-work").copied(),
        Some(NOW + 1800),
        "adoption overwrote the dismissal"
    );
}

/// **Hole 6, on the synced path.** Ending tonight's window must not suppress tomorrow's: the next
/// occurrence is a different one, and a peer starting it is adopted exactly as before.
///
/// This is the test that keeps the fix above from being "never adopt anything for a dismissed
/// profile", which would satisfy it while quietly breaking the schedule.
#[test]
fn the_next_occurrence_from_a_peer_is_still_adopted_after_an_end_here() {
    let (mut phone, mut pc) = two();
    phone.sessions.dismissed.insert("deep-work".into(), NOW + 1800);

    // The peer is holding tomorrow's window, which starts after tonight's ended.
    pc.sessions.start(scheduled("pc-2", "deep-work", NOW + 24 * HOUR + HOUR, TOMORROW_END));
    pc.pass(NOW + 24 * HOUR + HOUR, &[window("deep-work", NOW + 24 * HOUR + HOUR, TOMORROW_END)]);
    carry(&pc, &phone);

    let pass = phone.pass(
        NOW + 24 * HOUR + HOUR + 1,
        &[window("deep-work", NOW + 24 * HOUR + HOUR, TOMORROW_END)],
    );

    assert_eq!(pass.adopted, vec!["pc-2".to_string()], "tomorrow's window did not reach the phone");
    assert_eq!(phone.sessions.running.len(), 1);
}

/// The occurrence identity is the window's end *and* the profile. Two profiles whose windows happen
/// to end at the same instant are still two profiles, and ending one must not silence the other.
#[test]
fn an_unrelated_profile_ending_at_the_same_instant_is_not_suppressed() {
    let (mut phone, mut pc) = two();
    phone.sessions.dismissed.insert("deep-work".into(), NOW + 1800);

    // A different profile, in a window that ends at the same instant as the dismissed one.
    let mut reading = scheduled("pc-3", "reading", NOW + 3600, WINDOW_END);
    reading.source = SessionSource::Weekly { schedule: "reading".into() };
    pc.sessions.start(reading);
    pc.pass(NOW + 3600, &[window("deep-work", WINDOW_START, WINDOW_END)]);
    carry(&pc, &phone);

    let pass = phone.pass(NOW + 3601, &[window("deep-work", WINDOW_START, WINDOW_END)]);

    assert_eq!(pass.adopted, vec!["pc-3".to_string()], "an unrelated profile was suppressed");
    assert_eq!(phone.sessions.active_profiles(NOW + 3601), vec!["reading".to_string()]);
}

/// The ordinary case, and the one the guard was written for: the peer is holding the very session
/// this device ended (both devices started the same window, one user ended it here). It must not be
/// adopted back even when the caller supplies no activations at all.
#[test]
fn the_session_ended_here_is_not_adopted_back_from_a_peer() {
    let (mut phone, mut pc) = two();

    // Both devices are holding the window; the phone's copy is ended at 09:30.
    pc.sessions.start(scheduled("pc-1", "deep-work", WINDOW_START, WINDOW_END));
    pc.pass(WINDOW_START, &[]);
    carry(&pc, &phone);
    phone.pass(WINDOW_START + 1, &[]);
    assert_eq!(phone.sessions.running.len(), 1, "the fixture needs the phone to hold it first");

    let id = phone.sessions.running[0].id.clone();
    phone.sessions.end(&id, NOW + 1800, &Default::default()).unwrap();
    assert!(phone.sessions.running.is_empty());

    // The peer still believes it is running (its own lock is untouched), and says so again.
    pc.pass(NOW + 1800, &[]);
    carry(&pc, &phone);
    let pass = phone.pass(NOW + 1801, &[]);

    assert!(pass.adopted.is_empty(), "the ended session was adopted straight back: {pass:?}");
    assert!(phone.sessions.running.is_empty(), "the block came back after being ended");
}
