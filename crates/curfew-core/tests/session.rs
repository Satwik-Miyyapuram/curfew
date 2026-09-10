//! Sessions and the promise a lock makes. Most of these tests are about ways *out* of a lock that
//! must not exist.

use curfew_core::emergency::{EmergencyPolicy, Passes};
use curfew_core::schedule::{Activation, ActivationSource};
use curfew_core::session::{reconcile, Refusal, Session, SessionSource, Sessions};
use curfew_core::{Lock, LockSet, Timestamp, DELAYED_RELEASE_SECONDS};
use std::collections::BTreeSet;

const NOW: Timestamp = 1_788_609_600;

fn session(id: &str, profile: &str, lock: LockSet) -> Session {
    Session {
        id: id.into(),
        profile: profile.into(),
        source: SessionSource::Manual,
        started_at: NOW,
        lock,
    }
}

fn evidence(locks: &[Lock]) -> BTreeSet<Lock> {
    locks.iter().cloned().collect()
}

// --- starting ------------------------------------------------------------------------------------

#[test]
fn a_started_session_is_running_and_names_its_profile() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([], Some(NOW + 3600))));
    assert_eq!(s.active_profiles(NOW), vec!["deep-work".to_string()]);
    assert!(s.get("a").is_some());
}

/// Retrying a start after a crash must not create a second session or weaken the first.
#[test]
fn starting_the_same_profile_twice_merges_instead_of_duplicating() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::Confirm], Some(NOW + 3600))));
    s.start(session("b", "deep-work", LockSet::new([Lock::DeviceCredential], Some(NOW + 60))));

    assert_eq!(s.running.len(), 1);
    let lock = &s.running[0].lock;
    assert!(
        lock.conditions.contains(&Lock::Confirm)
            && lock.conditions.contains(&Lock::DeviceCredential)
    );
    assert_eq!(lock.ends_at, Some(NOW + 3600), "the later end time wins, always");
}

#[test]
fn a_weaker_second_start_cannot_shorten_a_running_lock() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::DeviceCredential], Some(NOW + 7200))));
    s.start(session("a2", "deep-work", LockSet::unlocked()));
    assert_eq!(s.running[0].lock.ends_at, Some(NOW + 7200));
    assert!(s.running[0].lock.is_locked(), "an unlocked start must not unlock a locked session");
}

#[test]
fn separate_profiles_run_as_separate_sessions() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([], Some(NOW + 60))));
    s.start(session("b", "sleep", LockSet::new([], Some(NOW + 60))));
    assert_eq!(s.active_profiles(NOW), vec!["deep-work".to_string(), "sleep".to_string()]);
}

// --- ending --------------------------------------------------------------------------------------

#[test]
fn an_unlocked_session_ends_on_request() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([], Some(NOW + 3600))));
    assert!(s.end("a", NOW, &BTreeSet::new()).is_ok());
    assert!(s.running.is_empty());
}

#[test]
fn a_locked_session_refuses_and_says_exactly_what_is_missing() {
    let mut s = Sessions::default();
    s.start(session(
        "a",
        "deep-work",
        LockSet::new([Lock::DeviceCredential, Lock::Confirm], Some(NOW + 3600)),
    ));
    let err = s.end("a", NOW, &evidence(&[Lock::Confirm])).unwrap_err();
    match err {
        Refusal::Locked { missing, ends_at, .. } => {
            assert_eq!(missing, evidence(&[Lock::DeviceCredential]));
            assert_eq!(ends_at, Some(NOW + 3600));
        }
        other => panic!("expected a locked refusal, got {other:?}"),
    }
    assert_eq!(s.running.len(), 1, "a refused end leaves the session running");
}

#[test]
fn satisfying_every_condition_ends_the_session() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::DeviceCredential, Lock::Confirm], None)));
    let ended = s.end("a", NOW, &evidence(&[Lock::Confirm, Lock::DeviceCredential])).unwrap();
    assert_eq!(ended.profile, "deep-work");
}

#[test]
fn ending_a_session_that_is_not_running_is_refused_rather_than_silently_fine() {
    let mut s = Sessions::default();
    assert_eq!(s.end("nope", NOW, &BTreeSet::new()), Err(Refusal::NotRunning));
}

/// The clock going backwards is the oldest trick there is. A lock ending at a fixed instant simply
/// does not expire until that instant arrives, whatever `now` did in between.
#[test]
fn a_clock_moved_backwards_does_not_release_anything() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::DeviceCredential], Some(NOW + 3600))));
    assert!(s.end("a", NOW - 100_000, &BTreeSet::new()).is_err());
    assert_eq!(s.active_profiles(NOW - 100_000), vec!["deep-work".to_string()]);
}

/// And the clock going *forwards* past the end time is not a trick: the session was going to end
/// then anyway, and refusing would be the bug.
#[test]
fn a_session_past_its_end_time_is_over_and_ends_freely() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::DeviceCredential], Some(NOW + 60))));
    assert!(s.end("a", NOW + 61, &BTreeSet::new()).is_ok());
}

// --- delayed release -------------------------------------------------------------------------------

#[test]
fn the_delayed_release_lands_24_hours_out_and_then_frees_the_session() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::DeviceCredential], None)));
    let at = s.request_release("a", NOW).unwrap();
    assert_eq!(at, NOW + DELAYED_RELEASE_SECONDS);

    assert!(s.end("a", NOW + DELAYED_RELEASE_SECONDS - 1, &BTreeSet::new()).is_err());
    assert!(s.end("a", NOW + DELAYED_RELEASE_SECONDS, &BTreeSet::new()).is_ok());
}

#[test]
fn requesting_the_release_again_never_moves_it_later() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::DeviceCredential], None)));
    let first = s.request_release("a", NOW).unwrap();
    assert_eq!(s.request_release("a", NOW + 5_000).unwrap(), first);
}

#[test]
fn a_refusal_tells_the_user_when_the_delayed_release_lands() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::DeviceCredential], None)));
    s.request_release("a", NOW).unwrap();
    match s.end("a", NOW, &BTreeSet::new()).unwrap_err() {
        Refusal::Locked { delayed_release_at, .. } => {
            assert_eq!(delayed_release_at, Some(NOW + DELAYED_RELEASE_SECONDS));
        }
        other => panic!("{other:?}"),
    }
}

// --- reaping and merged state -----------------------------------------------------------------------

#[test]
fn reaping_removes_only_sessions_whose_time_is_up() {
    let mut s = Sessions::default();
    s.start(session("a", "short", LockSet::new([], Some(NOW + 10))));
    s.start(session("b", "long", LockSet::new([], Some(NOW + 10_000))));
    let reaped = s.reap(NOW + 100);
    assert_eq!(reaped.len(), 1);
    assert_eq!(reaped[0].profile, "short");
    assert_eq!(s.active_profiles(NOW + 100), vec!["long".to_string()]);
}

#[test]
fn the_merged_lock_is_the_conjunction_of_every_running_session() {
    let mut s = Sessions::default();
    s.start(session("a", "one", LockSet::new([Lock::Confirm], Some(NOW + 60))));
    s.start(session("b", "two", LockSet::new([Lock::DeviceCredential], Some(NOW + 600))));
    let merged = s.merged_lock(NOW);
    assert_eq!(merged.conditions, evidence(&[Lock::Confirm, Lock::DeviceCredential]));
    assert_eq!(merged.ends_at, Some(NOW + 600));
}

#[test]
fn an_expired_session_no_longer_contributes_to_the_merged_lock() {
    let mut s = Sessions::default();
    s.start(session("a", "one", LockSet::new([Lock::Confirm], Some(NOW + 10))));
    assert!(s.merged_lock(NOW + 100).conditions.is_empty());
}

// --- reconciliation with schedules --------------------------------------------------------------------

fn activation(profile: &str, start: Timestamp, end: Timestamp, locks: Vec<Lock>) -> Activation {
    Activation {
        profile: profile.into(),
        source: ActivationSource::Weekly { schedule: "w".into() },
        start,
        end,
        locks,
    }
}

#[test]
fn reconciling_starts_a_session_for_a_schedule_that_is_now_active() {
    let mut s = Sessions::default();
    let started = reconcile(
        NOW,
        &mut s,
        &[activation("deep-work", NOW, NOW + 3600, vec![Lock::Timer])],
        |a| format!("id-{}", a.profile),
    );
    assert_eq!(started, vec!["id-deep-work".to_string()]);
    assert_eq!(s.running[0].source, SessionSource::Weekly { schedule: "w".into() });
    assert_eq!(s.running[0].lock.ends_at, Some(NOW + 3600));
}

#[test]
fn reconciling_twice_does_not_start_a_second_session() {
    let mut s = Sessions::default();
    let a = [activation("deep-work", NOW, NOW + 3600, vec![Lock::Timer])];
    reconcile(NOW, &mut s, &a, |a| format!("id-{}", a.profile));
    let again = reconcile(NOW + 60, &mut s, &a, |a| format!("id2-{}", a.profile));
    assert!(again.is_empty());
    assert_eq!(s.running.len(), 1);
}

/// Deleting the calendar event is not a way out of the lock. This is the single most important
/// test in this file: without it, "block me during meetings" is defeated by deleting the meeting.
#[test]
fn a_schedule_that_stops_matching_does_not_end_the_session_it_started() {
    let mut s = Sessions::default();
    reconcile(
        NOW,
        &mut s,
        &[activation("deep-work", NOW, NOW + 3600, vec![Lock::DeviceCredential])],
        |a| format!("id-{}", a.profile),
    );
    reconcile(NOW + 60, &mut s, &[], |a| format!("id-{}", a.profile));
    assert_eq!(s.active_profiles(NOW + 60), vec!["deep-work".to_string()]);
    assert!(s.end("id-deep-work", NOW + 60, &BTreeSet::new()).is_err());
}

/// ...but the session still ends when the window it was created for was always going to end.
#[test]
fn the_session_a_schedule_started_still_ends_at_the_windows_end() {
    let mut s = Sessions::default();
    reconcile(
        NOW,
        &mut s,
        &[activation("deep-work", NOW, NOW + 3600, vec![Lock::DeviceCredential])],
        |a| format!("id-{}", a.profile),
    );
    assert!(s.active_profiles(NOW + 3600).is_empty());
    assert_eq!(s.reap(NOW + 3600).len(), 1);
}

/// A schedule that was edited to end later extends the running session; one edited to end sooner
/// does not shorten it.
#[test]
fn reconciling_can_extend_a_session_but_never_cut_it_short() {
    let mut s = Sessions::default();
    let mint = |a: &Activation| format!("id-{}", a.profile);
    reconcile(NOW, &mut s, &[activation("deep-work", NOW, NOW + 3600, vec![])], mint);
    reconcile(NOW, &mut s, &[activation("deep-work", NOW, NOW + 60, vec![])], mint);
    assert_eq!(s.running[0].lock.ends_at, Some(NOW + 3600));
    reconcile(NOW, &mut s, &[activation("deep-work", NOW, NOW + 9000, vec![])], mint);
    assert_eq!(s.running[0].lock.ends_at, Some(NOW + 9000));
}

#[test]
fn an_activation_that_has_already_ended_starts_nothing() {
    let mut s = Sessions::default();
    let started = reconcile(NOW, &mut s, &[activation("deep-work", NOW - 100, NOW, vec![])], |a| {
        a.profile.clone()
    });
    assert!(started.is_empty());
    assert!(s.running.is_empty());
}

// --- the escape hatch ----------------------------------------------------------------------------

/// Spending a pass is how a real emergency gets out. It is the only thing that opens a lock whose
/// conditions cannot be met, and the rationing that makes it safe lives in `emergency.rs`.
#[test]
fn an_emergency_pass_ends_a_session_no_evidence_could_have_ended() {
    let policy = EmergencyPolicy { passes: 1, window_seconds: 7 * 86_400, cooldown_seconds: 3600 };
    let mut passes = Passes::default();
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::Token { id: "t1".into() }], None)));

    // Without the token there is no way out at all.
    assert!(matches!(s.end("a", NOW, &evidence(&[])), Err(Refusal::Locked { .. })));

    let pass = passes.spend(NOW, &policy).expect("the configured pass is available");
    let ended = s.end_with_pass("a", NOW, pass).expect("a spent pass ends the session");
    assert_eq!(ended.profile, "deep-work");
    assert!(s.running.is_empty());
}

/// One pass, one session. A bad evening must not become a general amnesty.
#[test]
fn a_pass_ends_one_session_and_leaves_the_others_exactly_as_locked() {
    let policy = EmergencyPolicy { passes: 2, window_seconds: 7 * 86_400, cooldown_seconds: 0 };
    let mut passes = Passes::default();
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::Confirm], None)));
    s.start(session("b", "evenings", LockSet::new([Lock::Confirm], None)));

    let pass = passes.spend(NOW, &policy).expect("the first pass");
    s.end_with_pass("a", NOW, pass).expect("the first session ends");

    assert_eq!(s.running.len(), 1);
    assert_eq!(s.running[0].id, "b");
    assert!(s.running[0].lock.is_locked());
}

/// A pass is not a way to conjure a session out of nothing, and a wasted one is still spent —
/// which is why the quota is recorded before the release is attempted, not after.
#[test]
fn a_pass_spent_on_a_session_that_is_not_running_is_refused_and_still_gone() {
    let policy = EmergencyPolicy { passes: 1, window_seconds: 7 * 86_400, cooldown_seconds: 0 };
    let mut passes = Passes::default();
    let mut s = Sessions::default();

    let pass = passes.spend(NOW, &policy).expect("the only pass");
    assert_eq!(s.end_with_pass("nope", NOW, pass), Err(Refusal::NotRunning));
    assert_eq!(passes.remaining(NOW, &policy), 0);
}

/// Ending an unlocked scheduled session used to last one reconcile: the window still matched, so it
/// started straight back up. The end now sticks for the occurrence it ended.
#[test]
fn ending_a_scheduled_session_does_not_let_the_same_window_restart_it() {
    let mut s = Sessions::default();
    let a = activation("study", 100, 400, vec![]);
    reconcile(150, &mut s, std::slice::from_ref(&a), |_| "id".into());
    let id = s.running[0].id.clone();

    s.end(&id, 200, &BTreeSet::new()).expect("an unlocked session ends on request");
    assert!(s.running.is_empty());

    let started = reconcile(201, &mut s, std::slice::from_ref(&a), |_| "id2".into());
    assert!(started.is_empty(), "the occurrence the user ended must stay ended");
    assert!(s.running.is_empty());
}

/// Ending tonight's window is not editing the schedule: the next occurrence starts as it always did.
#[test]
fn ending_one_occurrence_leaves_the_next_one_alone() {
    let mut s = Sessions::default();
    let tonight = activation("study", 100, 400, vec![]);
    reconcile(150, &mut s, std::slice::from_ref(&tonight), |_| "id".into());
    let id = s.running[0].id.clone();
    s.end(&id, 200, &BTreeSet::new()).expect("an unlocked session ends on request");

    let tomorrow = activation("study", 1_000, 1_300, vec![]);
    let started = reconcile(1_050, &mut s, std::slice::from_ref(&tomorrow), |_| "id2".into());
    assert_eq!(started.len(), 1);
    assert_eq!(s.running.len(), 1);
}
