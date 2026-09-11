//! Sessions and the promise a lock makes. Most of these tests are about ways *out* of a lock that
//! must not exist.

use curfew_core::emergency::{EmergencyPolicy, Passes};
use curfew_core::schedule::{Activation, ActivationSource};
use curfew_core::session::{reconcile, running_from, Refusal, Session, SessionSource, Sessions};
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

// --- restoring from storage ----------------------------------------------------------------------
//
// The restore path is the one way into session state that does not come from the lattice, and it used
// to be a whole-structure assignment: `restore_sessions` in `curfew-ffi` deserialized a `Sessions`
// and wrote it over the running one, with no lock check, no proof and no op-log entry. Anyone who
// could call it could end every lock by handing over `{"running":[]}`.
//
// These tests are the promise that the door is shut, from the side that matters: what a *caller* can
// achieve by sending arbitrary JSON. Every one of them fails against the old implementation.

#[test]
fn an_empty_restore_cannot_end_a_running_lock() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::Timer], Some(NOW + 3600))));

    // The bypass, in one line: the stored state says nothing is running.
    s.restore_without_weakening(Sessions::default());

    assert_eq!(s.running.len(), 1, "an empty restore ended a running lock");
    assert!(s.get("a").is_some());
    assert!(s.get("a").unwrap().lock.conditions.contains(&Lock::Timer));
}

#[test]
fn a_restore_cannot_shorten_a_lock_or_drop_its_conditions() {
    let mut s = Sessions::default();
    s.start(session(
        "a",
        "deep-work",
        LockSet::new([Lock::Confirm, Lock::DeviceCredential], Some(NOW + 3600)),
    ));

    // The same session, reported as far weaker than it is: no conditions, ending in a minute.
    let mut incoming = Sessions::default();
    incoming.start(session("a", "deep-work", LockSet::new([], Some(NOW + 60))));
    s.restore_without_weakening(incoming);

    let lock = &s.get("a").unwrap().lock;
    assert!(
        lock.conditions.contains(&Lock::Confirm)
            && lock.conditions.contains(&Lock::DeviceCredential),
        "a restore dropped a lock condition"
    );
    assert_eq!(lock.ends_at, Some(NOW + 3600), "a restore shortened the end time");
}

/// The legitimate use: everything that was running before a restart comes back.
#[test]
fn a_restore_starts_sessions_that_are_not_running_yet() {
    let mut s = Sessions::default();
    let mut incoming = Sessions::default();
    incoming.start(session("a", "deep-work", LockSet::new([Lock::Timer], Some(NOW + 3600))));
    incoming.start(session("b", "evening", LockSet::new([Lock::Confirm], Some(NOW + 60))));

    s.restore_without_weakening(incoming);

    assert_eq!(s.running.len(), 2, "a restore dropped a session it should have started");
    assert!(s.get("a").is_some() && s.get("b").is_some());
}

/// A restore may strengthen, which is the other half of "necessary and sufficient".
#[test]
fn a_restore_can_strengthen_a_running_session() {
    let mut s = Sessions::default();
    s.start(session("a", "deep-work", LockSet::new([Lock::Confirm], Some(NOW + 60))));

    let mut incoming = Sessions::default();
    incoming.start(session(
        "a",
        "deep-work",
        LockSet::new([Lock::DeviceCredential], Some(NOW + 3600)),
    ));
    s.restore_without_weakening(incoming);

    let lock = &s.get("a").unwrap().lock;
    assert!(
        lock.conditions.contains(&Lock::Confirm)
            && lock.conditions.contains(&Lock::DeviceCredential),
        "the stronger set should hold both conditions"
    );
    assert_eq!(lock.ends_at, Some(NOW + 3600));
}

/// A dismissal is a memory that a schedule occurrence was ended. A restore must not un-remember it,
/// because forgetting one lets tonight's window start again straight after being ended.
#[test]
fn a_restore_cannot_un_dismiss_an_occurrence() {
    let mut s = Sessions::default();
    s.dismissed.insert("weekly-night".into(), NOW);

    s.restore_without_weakening(Sessions::default());
    assert_eq!(s.dismissed.get("weekly-night").copied(), Some(NOW), "a restore forgot a dismissal");

    // And a later dismissal wins, whichever side it came from.
    let mut incoming = Sessions::default();
    incoming.dismissed.insert("weekly-night".into(), NOW + 10);
    s.restore_without_weakening(incoming);
    assert_eq!(s.dismissed.get("weekly-night").copied(), Some(NOW + 10));
}

/// Restoring the same thing twice is the ordinary case — a service restart loop, or a retry — and it
/// must be idempotent rather than accumulating duplicates.
#[test]
fn restoring_twice_changes_nothing_the_second_time() {
    let mut s = Sessions::default();
    let mut incoming = Sessions::default();
    incoming.start(session("a", "deep-work", LockSet::new([Lock::Timer], Some(NOW + 3600))));

    s.restore_without_weakening(incoming.clone());
    let after_first = s.running.len();
    s.restore_without_weakening(incoming);

    assert_eq!(s.running.len(), after_first, "a second restore duplicated a session");
    assert_eq!(after_first, 1);
}

// --- what a surface may offer (P1-6) -------------------------------------------------------------
//
// Three user interfaces each answered this question their own way. The tray routed `DeviceCredential`
// and `PeerRelease`; the Windows window routed two variants by comparing display *strings* and
// rendered no release at all for a credential lock; and neither routed `Challenge`, which exists in
// this crate and which Android implements. So the decision lives here now and every surface reads it.

/// **The finding.** A credential lock must still offer the exit of last resort, because that is what
/// the architecture promises and what the window did not do.
#[test]
fn a_credential_lock_offers_the_password_and_the_24_hour_release() {
    let lock = LockSet::new([Lock::DeviceCredential], Some(NOW + 3600));
    let offers = lock.offers(false, false);

    assert!(offers.credential, "the password route was not offered");
    assert!(!offers.ends_on_request, "a credential lock is not free to end");
    assert!(
        offers.delayed_release,
        "no 24-hour release: this is the gap that left a user with no way out from the window"
    );
    assert!(offers.elsewhere.is_empty(), "a credential is not an elsewhere condition");
}

/// And a lock whose only condition is a tag in another room. The tray only offered the 24-hour
/// release when `credential || others`; here `elsewhere` is non-empty, so both agree — but the
/// predicate is what decides, not the surface.
#[test]
fn a_lock_no_local_prompt_can_satisfy_offers_where_it_is_and_the_way_out() {
    let lock = LockSet::new([Lock::Token { id: "t1".into() }], Some(NOW + 3600));
    let offers = lock.offers(false, false);

    assert!(!offers.credential && !offers.confirm, "a tag is not a prompt this surface can show");
    assert_eq!(offers.elsewhere, vec![Lock::Token { id: "t1".into() }]);
    assert!(offers.delayed_release, "the last-resort exit was withheld");
}

/// A challenge is a route Android supports and Windows did not route at all. It must be named as
/// "elsewhere" rather than silently omitted, so a surface can say what is holding the lock.
#[test]
fn a_challenge_is_reported_as_an_elsewhere_condition() {
    use curfew_core::ChallengeKind;
    let lock = LockSet::new([Lock::Challenge { challenge: ChallengeKind::Math }], Some(NOW + 3600));
    let offers = lock.offers(false, false);

    assert_eq!(offers.elsewhere.len(), 1, "the challenge was dropped rather than reported");
    assert!(matches!(offers.elsewhere[0], Lock::Challenge { .. }));
    assert!(offers.delayed_release);
}

/// An unlocked session ends on request and needs no exit of last resort — there is nothing to exit.
#[test]
fn an_unlocked_session_ends_on_request_and_offers_no_last_resort() {
    let lock = LockSet::new([], None);
    let offers = lock.offers(false, false);

    assert!(offers.ends_on_request);
    assert!(
        !offers.delayed_release,
        "a 24-hour delay was offered for a session that can simply be ended"
    );
}

/// **The peer release.** A lock naming *this* device is offered here; naming another device it is not.
/// This is the one input a surface cannot work out for itself, which is why the service computes it.
#[test]
fn a_peer_release_is_offered_only_to_the_device_it_names() {
    let lock = LockSet::new([Lock::PeerRelease { device_id: "PHONE7".into() }], None);

    let ours = lock.offers(true, false);
    assert!(ours.peer_release, "the named device was not offered the release");
    assert!(!ours.peer_released);
    assert!(ours.elsewhere.is_empty(), "a release we can give is not an elsewhere condition");

    let theirs = lock.offers(false, false);
    assert!(!theirs.peer_release, "a device that was not named was offered the release");
    assert_eq!(theirs.elsewhere.len(), 1, "the peer lock should be reported as elsewhere");
}

/// Given already: reported, never offered again. The release cannot be withdrawn, so a button would
/// suggest it could be redone.
#[test]
fn a_peer_release_already_given_is_reported_rather_than_offered() {
    let lock = LockSet::new([Lock::PeerRelease { device_id: "PHONE7".into() }], None);
    let offers = lock.offers(true, true);

    assert!(!offers.peer_release, "a release already given was offered again");
    assert!(offers.peer_released, "a release already given was not reported");
    assert!(
        offers.elsewhere.is_empty(),
        "the device holding the release should not be told the lock is elsewhere"
    );
}

/// A release already counting down is never offered again, because asking twice cannot move it.
#[test]
fn a_delayed_release_already_running_is_reported_and_never_offered_twice() {
    let mut lock = LockSet::new([Lock::DeviceCredential], None);
    lock.delayed_release_at = Some(NOW + curfew_core::DELAYED_RELEASE_SECONDS);

    let offers = lock.offers(false, false);
    assert!(!offers.delayed_release, "a running release was offered again");
    assert_eq!(offers.delayed_release_at, Some(NOW + curfew_core::DELAYED_RELEASE_SECONDS));
}

/// **An expired timer is not "locked elsewhere".** It is a condition in the set but it is satisfied
/// by the clock, and the tray filters it out for exactly that reason. Leaving it in made an expired
/// timer render as unreachable from the tray — caught by the tray's own suite, and pinned here so the
/// shared predicate cannot regress on its own.
#[test]
fn a_timer_is_never_an_elsewhere_condition() {
    let expired = LockSet::new([Lock::Timer], Some(NOW - 1));
    let offers = expired.offers(false, false);

    assert!(offers.elsewhere.is_empty(), "an expired timer was reported as elsewhere");
    assert!(offers.delayed_release);

    // And a timer still running is the same: it is a time, not a place.
    let running = LockSet::new([Lock::Timer], Some(NOW + 3600));
    assert!(running.offers(false, false).elsewhere.is_empty());
}

/// A confirmation is friction, not a barrier, and is a route this surface can serve.
#[test]
fn a_confirmation_is_offered_rather_than_reported_as_elsewhere() {
    let offers = LockSet::new([Lock::Confirm], Some(NOW + 3600)).offers(false, false);

    assert!(offers.confirm);
    assert!(offers.elsewhere.is_empty());
    assert!(offers.delayed_release);
}

/// Every condition together, so a surface that renders all of them has something to render.
#[test]
fn every_condition_is_accounted_for() {
    use curfew_core::ChallengeKind;
    let lock = LockSet::new(
        [
            Lock::DeviceCredential,
            Lock::Confirm,
            Lock::Challenge { challenge: ChallengeKind::Typing },
            Lock::RestartRequired,
            Lock::Timer,
        ],
        Some(NOW + 60),
    );
    let offers = lock.offers(false, false);

    assert!(offers.credential && offers.confirm);
    // The challenge and the restart, and *not* the credential, the confirmation or the timer.
    assert_eq!(offers.elsewhere.len(), 2, "got {:?}", offers.elsewhere);
    assert!(offers.elsewhere.iter().any(|l| matches!(l, Lock::RestartRequired)));
    assert!(offers.elsewhere.iter().any(|l| matches!(l, Lock::Challenge { .. })));
    assert!(offers.delayed_release);
}

// --- which running session a schedule is holding (P2-11) -----------------------------------------
//
// `curfew remove <id>` deletes a window or calendar rule and nothing in the running service notices. The
// session keeps its own copy of what it blocks, so the lock is not weakened — but the user is not told,
// and the README says a lock is a promise that only its own conditions shorten. This is the predicate that
// lets a caller refuse instead, and refusing is right in exactly one direction: a schedule that is *not*
// enforcing anything must be deletable, or the plan becomes unmaintainable without ending a lock first.

fn a_session(id: &str, profile: &str, source: SessionSource) -> Session {
    Session {
        id: id.into(),
        profile: profile.into(),
        source,
        started_at: NOW,
        lock: LockSet::new([Lock::Timer], None),
    }
}

/// **The case the finding is about**: a weekly window is holding a session, so removing it is refused.
#[test]
fn a_running_weekly_window_is_reported_as_holding_a_session() {
    let running = vec![a_session(
        "s1",
        "deep-work",
        SessionSource::Weekly { schedule: "weekday-mornings".into() },
    )];

    let held = running_from(&running, "weekday-mornings");
    assert_eq!(held.len(), 1);
    assert_eq!(held[0].id, "s1");
}

/// A calendar rule the same, because it carries the schedule id too.
#[test]
fn a_running_calendar_rule_is_reported_as_holding_a_session() {
    let running = vec![a_session(
        "s1",
        "deep-work",
        SessionSource::Calendar { schedule: "work-focus".into(), event: "e1".into() },
    )];

    assert_eq!(running_from(&running, "work-focus").len(), 1);
}

/// **And the other direction, which is what keeps the plan editable.** A schedule that is not enforcing
/// anything can be removed freely: that is the ordinary edit, and refusing it would mean ending a lock
/// before every change to the plan.
#[test]
fn a_schedule_that_is_not_running_is_not_reported() {
    let running = vec![
        a_session("s1", "deep-work", SessionSource::Weekly { schedule: "weekday-mornings".into() }),
        // **A calendar session too, asked about a different id.** Both arms need this: without a calendar
        // case here, replacing the calendar arm's comparison with `true` changes no outcome in any test,
        // because every other calendar fixture asks about the id it actually carries.
        a_session(
            "s2",
            "deep-work",
            SessionSource::Calendar { schedule: "work-focus".into(), event: "e1".into() },
        ),
    ];

    assert!(running_from(&running, "some-other-window").is_empty());
    for other in ["weekday-mornings", "work-focus"] {
        let found = running_from(&running, other);
        // Neither is "not reported": asking about the id a session carries *does* report it, which is the
        // other half of this predicate. What matters is that it reports only its own.
        assert_eq!(found.len(), 1, "{other} reported the wrong number of sessions");
        assert_eq!(found[0].id, if other == "weekday-mornings" { "s1" } else { "s2" });
    }
    assert!(running_from(&[], "weekday-mornings").is_empty());
}

/// A session started by hand is not derived from any schedule, so no removal is refused on its account.
#[test]
fn a_manual_session_holds_no_schedule() {
    let running = vec![a_session("s1", "deep-work", SessionSource::Manual)];

    assert!(
        running_from(&running, "weekday-mornings").is_empty(),
        "a manual session was attributed to a schedule"
    );
}

/// Two sessions from the same schedule are both reported, because the refusal names the profiles.
#[test]
fn every_session_from_a_schedule_is_reported() {
    let running = vec![
        a_session("s1", "deep-work", SessionSource::Weekly { schedule: "w".into() }),
        a_session(
            "s2",
            "evenings",
            SessionSource::Calendar { schedule: "w".into(), event: "e".into() },
        ),
        a_session("s3", "other", SessionSource::Weekly { schedule: "elsewhere".into() }),
    ];

    let held = running_from(&running, "w");
    assert_eq!(held.len(), 2, "both sessions from a schedule should be reported");
    assert!(held.iter().all(|s| s.profile != "other"), "an unrelated session was attributed");
}
