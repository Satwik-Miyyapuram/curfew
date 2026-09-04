//! The FFI boundary. The core's own behaviour is tested in `curfew-core`; what is tested here is
//! that nothing is lost, weakened or silently swallowed on the way across -- and, above all, that
//! the guarantees the core makes about locks survive being reachable from Kotlin.

use curfew_ffi::{check_config, Curfew, CurfewError, PlatformName};
use serde_json::{json, Value};

const NOW: i64 = 1_788_609_600;

fn config() -> String {
    include_str!("../../curfew-core/tests/golden/example.toml").to_string()
}

fn curfew() -> std::sync::Arc<Curfew> {
    Curfew::new(config()).expect("the golden config loads")
}

/// Start a manual session on `profile`, locked by `locks` until `ends_at`.
fn start(c: &Curfew, id: &str, profile: &str, locks: Value, ends_at: Option<i64>) {
    c.start_session(
        json!({
            "id": id,
            "profile": profile,
            "source": {"kind": "manual"},
            "started_at": NOW,
            "lock": {"conditions": locks, "ends_at": ends_at, "delayed_release_at": null},
        })
        .to_string(),
    )
    .expect("a well-formed session starts");
}

fn decide(c: &Curfew, obs: Value) -> Value {
    let out =
        c.decide(NOW, obs.to_string(), PlatformName::Android, String::new()).expect("decided");
    serde_json::from_str(&out).expect("the decision is JSON")
}

// --- loading ------------------------------------------------------------------------------------

#[test]
fn the_golden_config_crosses_the_boundary_intact() {
    let c = curfew();
    let round_tripped = c.config_toml().expect("serializes");
    let reloaded = Curfew::new(round_tripped).expect("and loads again");
    assert!(reloaded.config_toml().is_ok());
}

#[test]
fn a_broken_config_is_a_config_error_carrying_the_message_the_user_needs() {
    match Curfew::new("schema_version = ".into()).unwrap_err() {
        CurfewError::Config { detail } => assert!(!detail.is_empty()),
        other => panic!("expected a config error, got {other:?}"),
    }
}

#[test]
fn check_config_summarizes_without_loading() {
    let summary = check_config(config()).expect("valid");
    assert!(summary.contains("schema v1"), "{summary}");
    assert!(summary.contains("Europe/London"), "{summary}");
}

#[test]
fn check_config_refuses_a_config_from_the_future() {
    let from_the_future = config().replace("schema_version = 1", "schema_version = 99");
    assert!(check_config(from_the_future).is_err());
}

// --- deciding -----------------------------------------------------------------------------------

#[test]
fn a_blocked_app_is_blocked_while_its_profile_runs() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let d = decide(&c, json!({"kind": "app", "package": "com.instagram.android", "screen": null}));
    assert_eq!(d["decision"], "block");
    assert_eq!(d["reason"]["reason"], "blocked");
    assert_eq!(d["reason"]["profile"], "deep-work");
}

#[test]
fn nothing_is_blocked_when_no_session_is_running() {
    let c = curfew();
    let d = decide(&c, json!({"kind": "app", "package": "com.instagram.android", "screen": null}));
    assert_eq!(d["decision"], "allow");
}

#[test]
fn a_delay_rule_comes_back_with_its_seconds() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let d = decide(&c, json!({"kind": "app", "package": "com.slack", "screen": null}));
    assert_eq!(d["decision"], "delay");
    assert_eq!(d["seconds"], 15);
}

#[test]
fn a_url_rule_still_matches_once_the_url_has_been_through_json() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let d = decide(
        &c,
        json!({"kind": "web", "url": {
            "raw": "https://m.youtube.com:443/shorts/abc",
            "host": "m.youtube.com", "path": "/shorts/abc", "query": ""
        }}),
    );
    assert_eq!(d["decision"], "block", "{d}");
}

/// An empty usage payload is the common case on a fresh start and must mean "no usage yet", not a
/// parse failure that leaves the service unable to decide anything.
#[test]
fn an_empty_usage_payload_is_treated_as_no_usage() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    assert_eq!(decide(&c, json!({"kind": "idle"}))["decision"], "allow");
}

#[test]
fn usage_handed_in_from_storage_is_what_exhausts_a_budget() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let usage = json!({
        "usage": {"domain:reddit.com": {"rollups": [{"at": NOW - 60, "seconds": 1200}]}}
    });
    let out = c
        .decide(
            NOW,
            json!({"kind": "web", "url": {
                "raw": "https://reddit.com/", "host": "reddit.com", "path": "/", "query": ""
            }})
            .to_string(),
            PlatformName::Android,
            usage.to_string(),
        )
        .expect("decided");
    let d: Value = serde_json::from_str(&out).unwrap();
    assert_eq!(d["decision"], "block");
    assert_eq!(d["reason"]["reason"], "budget_exhausted");
}

/// The platform argument is not decoration: a Windows-only rule must not fire on the phone.
#[test]
fn platform_scoping_survives_the_boundary() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let obs = json!({"kind": "window", "exe": "steam.exe", "title": "Steam"});
    let android =
        c.decide(NOW, obs.to_string(), PlatformName::Android, String::new()).expect("decided");
    let windows =
        c.decide(NOW, obs.to_string(), PlatformName::Windows, String::new()).expect("decided");
    assert!(android.contains("allow"), "{android}");
    assert!(windows.contains("block"), "{windows}");
}

#[test]
fn a_malformed_observation_is_a_payload_error_and_not_a_panic() {
    let c = curfew();
    match c.decide(NOW, "{\"kind\":\"nonsense\"}".into(), PlatformName::Android, String::new()) {
        Err(CurfewError::Payload { .. }) => {}
        other => panic!("expected a payload error, got {other:?}"),
    }
}

// --- sessions -----------------------------------------------------------------------------------

#[test]
fn an_unlocked_session_ends_on_request() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    c.end_session("s1".into(), NOW, String::new()).expect("ends");
    assert!(c.active_profiles(NOW).is_empty());
}

/// The refusal has to reach Kotlin with enough detail to tell the user what to do, not just "no".
#[test]
fn a_locked_session_refuses_and_names_what_is_missing() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([{"kind": "device_credential"}]), Some(NOW + 3600));
    match c.end_session("s1".into(), NOW, String::new()).unwrap_err() {
        CurfewError::Refused { refusal } => {
            let r: Value = serde_json::from_str(&refusal).unwrap();
            assert_eq!(r["refusal"], "locked");
            assert_eq!(r["missing"][0]["kind"], "device_credential");
            assert_eq!(r["ends_at"], NOW + 3600);
        }
        other => panic!("expected a refusal, got {other:?}"),
    }
    assert_eq!(c.active_profiles(NOW), vec!["deep-work".to_string()]);
}

#[test]
fn the_evidence_the_platform_collected_is_what_releases_the_lock() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([{"kind": "device_credential"}]), None);
    let evidence = json!([{"kind": "device_credential"}]).to_string();
    c.end_session("s1".into(), NOW, evidence).expect("credential accepted");
    assert!(c.active_profiles(NOW).is_empty());
}

#[test]
fn ending_a_session_that_is_not_running_is_refused_rather_than_quietly_fine() {
    let c = curfew();
    match c.end_session("nope".into(), NOW, String::new()).unwrap_err() {
        CurfewError::Refused { refusal } => assert!(refusal.contains("not_running"), "{refusal}"),
        other => panic!("{other:?}"),
    }
}

#[test]
fn the_delayed_release_lands_24_hours_out_and_is_never_moved_later() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([{"kind": "device_credential"}]), None);
    let first = c.request_release("s1".into(), NOW).expect("requested");
    assert_eq!(first, NOW + 24 * 3600);
    assert_eq!(c.request_release("s1".into(), NOW + 5_000).expect("again"), first);
    assert!(c.end_session("s1".into(), first - 1, String::new()).is_err());
    c.end_session("s1".into(), first, String::new()).expect("free once it lands");
}

/// Replacing the config is a settings change, not an escape hatch. A lock taken under the old
/// config is still a lock.
#[test]
fn replacing_the_config_does_not_release_a_running_session() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([{"kind": "device_credential"}]), Some(NOW + 3600));
    // Rename the profile everywhere it is named, so the replacement config is itself valid: what
    // is under test is the running session, not the config checker.
    c.set_config(config().replace("\"deep-work\"", "\"renamed\"")).expect("loads");
    assert_eq!(c.active_profiles(NOW), vec!["deep-work".to_string()]);
    assert!(c.end_session("s1".into(), NOW, String::new()).is_err());
}

#[test]
fn a_rejected_config_leaves_the_old_one_in_place() {
    let c = curfew();
    assert!(c.set_config("schema_version = ".into()).is_err());
    assert!(check_config(c.config_toml().unwrap()).is_ok(), "the previous config is still loaded");
}

// --- persistence --------------------------------------------------------------------------------

/// A reboot, a force-stop and an app update all look like this: the process is new and the only
/// thing that knows about the lock is what was written to storage. If this round trip loses
/// anything, invariant 2 is broken by a power button.
#[test]
fn sessions_survive_a_process_restart_with_their_locks_intact() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([{"kind": "device_credential"}]), Some(NOW + 3600));
    c.request_release("s1".into(), NOW).expect("requested");
    let saved = c.sessions_json().expect("serializes");

    let restarted = curfew();
    restarted.restore_sessions(saved).expect("restores");
    assert_eq!(restarted.active_profiles(NOW), vec!["deep-work".to_string()]);
    match restarted.end_session("s1".into(), NOW, String::new()).unwrap_err() {
        CurfewError::Refused { refusal } => {
            let r: Value = serde_json::from_str(&refusal).unwrap();
            assert_eq!(r["delayed_release_at"], NOW + 24 * 3600, "the countdown does not restart");
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn the_merged_lock_is_reported_for_the_you_are_locked_screen() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([{"kind": "confirm"}]), Some(NOW + 60));
    start(&c, "s2", "writing", json!([{"kind": "device_credential"}]), Some(NOW + 600));
    let lock: Value = serde_json::from_str(&c.merged_lock_json(NOW).unwrap()).unwrap();
    assert_eq!(lock["ends_at"], NOW + 600, "the later end wins");
    assert_eq!(lock["conditions"].as_array().unwrap().len(), 2);
}

#[test]
fn reaping_removes_only_what_is_over_and_reports_it() {
    let c = curfew();
    start(&c, "s1", "short", json!([]), Some(NOW + 10));
    start(&c, "s2", "long", json!([]), Some(NOW + 10_000));
    let reaped: Value = serde_json::from_str(&c.reap(NOW + 100).unwrap()).unwrap();
    assert_eq!(reaped.as_array().unwrap().len(), 1);
    assert_eq!(reaped[0]["profile"], "short");
    assert_eq!(c.active_profiles(NOW + 100), vec!["long".to_string()]);
}

// --- schedules ----------------------------------------------------------------------------------

/// 2026-09-04 09:30 London is inside the golden config's `weekday-mornings` window.
const FRIDAY_0930: i64 = 1_788_510_600;

#[test]
fn reconciling_starts_the_session_a_weekly_window_calls_for() {
    let c = curfew();
    let started = c.reconcile(FRIDAY_0930, String::new(), "seed".into()).expect("reconciled");
    assert!(!started.is_empty(), "the weekday-mornings window is open");
    assert!(c.active_profiles(FRIDAY_0930).contains(&"deep-work".to_string()));
}

#[test]
fn reconciling_twice_does_not_start_a_second_session() {
    let c = curfew();
    c.reconcile(FRIDAY_0930, String::new(), "a".into()).expect("first");
    let again = c.reconcile(FRIDAY_0930 + 60, String::new(), "b".into()).expect("second");
    assert!(again.is_empty(), "{again:?}");
}

/// The single most important test at this boundary: deleting the calendar event must not be a way
/// out of the lock it started.
#[test]
fn a_calendar_event_that_disappears_does_not_end_the_session_it_started() {
    let c = curfew();
    let events = json!([{
        "id": "e1", "title": "Focus block", "calendar": "Work", "location": "",
        "start": FRIDAY_0930, "end": FRIDAY_0930 + 3600, "all_day": false, "busy": true
    }])
    .to_string();
    let started = c.reconcile(FRIDAY_0930, events, "seed".into()).expect("reconciled");
    let id = started.first().expect("a session started").clone();

    // The user deletes the meeting and reconciles again with an empty calendar.
    c.reconcile(FRIDAY_0930 + 60, "[]".into(), "seed2".into()).expect("reconciled");
    assert!(c.active_profiles(FRIDAY_0930 + 60).contains(&"deep-work".to_string()));
    assert!(c.end_session(id, FRIDAY_0930 + 60, String::new()).is_err());
}

#[test]
fn activations_are_reported_for_the_preview_timeline() {
    let c = curfew();
    let out: Value =
        serde_json::from_str(&c.activations_json(FRIDAY_0930, String::new()).unwrap()).unwrap();
    let a = out.as_array().expect("an array");
    assert!(a.iter().any(|x| x["profile"] == "deep-work"), "{out}");
}

#[test]
fn the_next_change_is_reported_so_the_service_can_set_one_alarm() {
    let c = curfew();
    let next = c.next_change_after(FRIDAY_0930, String::new()).expect("computed");
    assert!(next.is_some_and(|t| t > FRIDAY_0930), "{next:?}");
}

#[test]
fn a_malformed_calendar_snapshot_is_a_payload_error() {
    let c = curfew();
    assert!(matches!(
        c.reconcile(NOW, "not json".into(), "seed".into()),
        Err(CurfewError::Payload { .. })
    ));
}

// --- concurrency --------------------------------------------------------------------------------

/// Android calls `decide` from the accessibility thread while the UI thread reads sessions. The
/// object is shared as an `Arc` across the boundary, so it has to hold up under exactly that.
#[test]
fn the_object_is_safe_to_use_from_several_threads_at_once() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    std::thread::scope(|scope| {
        for _ in 0..8 {
            let c = std::sync::Arc::clone(&c);
            scope.spawn(move || {
                for i in 0..200 {
                    let obs =
                        json!({"kind": "app", "package": "com.instagram.android", "screen": null});
                    let out = c
                        .decide(NOW + i, obs.to_string(), PlatformName::Android, String::new())
                        .expect("decided");
                    assert!(out.contains("block"), "{out}");
                    let _ = c.sessions_json().expect("readable");
                }
            });
        }
    });
    assert_eq!(c.active_profiles(NOW), vec!["deep-work".to_string()]);
}

// --- what to charge -----------------------------------------------------------------------------

/// The platform records seconds; only the core knows which budget they belong to. A subdomain has
/// to land on the rule's key, or the budget quietly never fills.
#[test]
fn a_subdomain_is_charged_against_the_rule_that_covers_it() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let keys = c
        .charged_keys(
            NOW,
            json!({"kind": "web", "url": {
                "raw": "https://old.reddit.com/r/all",
                "host": "old.reddit.com",
                "path": "/r/all",
                "query": ""
            }})
            .to_string(),
            PlatformName::Android,
        )
        .expect("keys");
    assert_eq!(keys, vec!["domain:reddit.com".to_string()]);
}

#[test]
fn a_launch_limited_app_reports_its_key_too() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let keys = c
        .charged_keys(
            NOW,
            json!({"kind": "app", "package": "com.twitter.android"}).to_string(),
            PlatformName::Android,
        )
        .expect("keys");
    assert_eq!(keys, vec!["app:com.twitter.android".to_string()]);
}

/// A plain block meters nothing, so there is nothing to record against it.
#[test]
fn a_blocked_app_has_nothing_to_charge() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let keys = c
        .charged_keys(
            NOW,
            json!({"kind": "app", "package": "com.instagram.android"}).to_string(),
            PlatformName::Android,
        )
        .expect("keys");
    assert!(keys.is_empty(), "{keys:?}");
}

/// Nothing is metered when no profile is running: an idle device must not consume a budget.
#[test]
fn nothing_is_charged_outside_a_session() {
    let c = curfew();
    let keys = c
        .charged_keys(
            NOW,
            json!({"kind": "app", "package": "com.twitter.android"}).to_string(),
            PlatformName::Android,
        )
        .expect("keys");
    assert!(keys.is_empty(), "{keys:?}");
}

/// A Windows-only rule must not charge an Android observation, or the two platforms would disagree
/// about how much of a shared budget is left.
#[test]
fn platform_scoping_applies_to_charging_as_well_as_to_blocking() {
    let c = curfew();
    start(&c, "s1", "deep-work", json!([]), Some(NOW + 3600));
    let android = c
        .charged_keys(
            NOW,
            json!({"kind": "window", "exe": "steam.exe", "title": "Steam"}).to_string(),
            PlatformName::Android,
        )
        .expect("keys");
    assert!(android.is_empty(), "{android:?}");
}

// --- the app picker -----------------------------------------------------------------------------

#[test]
fn the_picker_edits_a_config_the_core_can_still_load() {
    let profiles: Value =
        serde_json::from_str(&curfew_ffi::profiles_json(config()).expect("profiles"))
            .expect("JSON");
    let first = profiles[0]["id"].as_str().expect("a profile id").to_string();

    let edited = curfew_ffi::set_blocked_apps(
        config(),
        first.clone(),
        vec!["com.example.one".into(), "com.example.two".into()],
    )
    .expect("the picker's edit is accepted");

    Curfew::new(edited.clone()).expect("and the result is a loadable config");
    assert_eq!(
        curfew_ffi::blocked_apps(edited, first).expect("read back"),
        vec!["com.example.one".to_string(), "com.example.two".to_string()],
    );
}

#[test]
fn the_picker_cannot_invent_a_profile() {
    let err = curfew_ffi::set_blocked_apps(config(), "not-a-profile".into(), vec![])
        .expect_err("a profile that does not exist is an error");
    assert!(matches!(err, CurfewError::Config { .. }));
}

// --- trusted time -------------------------------------------------------------------------------

fn observe(c: &Curfew, wall: i64, uptime: i64, boot_id: u64) -> Value {
    let out = c.observe_clock(wall, uptime, boot_id).expect("a reading is judged");
    serde_json::from_str(&out).expect("the verdict is JSON")
}

#[test]
fn the_first_reading_is_taken_at_face_value_and_becomes_the_baseline() {
    let c = curfew();
    let verdict = observe(&c, NOW, 1_000, 3);

    assert_eq!(verdict["now"], NOW);
    assert_eq!(verdict["tampered"], false);
    assert_eq!(c.trusted_now(), Some(NOW));
}

#[test]
fn there_is_no_trusted_time_before_anything_has_been_observed() {
    assert_eq!(curfew().trusted_now(), None);
}

#[test]
fn a_clock_wound_forward_cannot_end_a_locked_session_through_the_boundary() {
    // The attack, whole: a two-hour timer lock, and a user who sets the device clock four hours
    // forward a minute after it starts.
    let c = curfew();
    observe(&c, NOW, 1_000, 3);
    start(&c, "s1", "deep-work", json!([{"kind": "timer"}]), Some(NOW + 2 * 3_600));

    let verdict = observe(&c, NOW + 4 * 3_600, 1_060, 3);

    assert_eq!(verdict["now"], NOW + 60, "the boundary credited stolen time");
    assert_eq!(verdict["refused_forward"], 4 * 3_600 - 60);
    assert_eq!(verdict["tampered"], true);

    // And the session is still running at the time the verdict actually licenses.
    let reaped: Value =
        serde_json::from_str(&c.reap(verdict["now"].as_i64().unwrap()).expect("reap")).unwrap();
    assert_eq!(reaped.as_array().expect("an array").len(), 0, "the lock ended early");

    let sessions: Value = serde_json::from_str(&c.sessions_json().expect("sessions")).unwrap();
    assert_eq!(sessions["running"].as_array().expect("an array").len(), 1);
}

#[test]
fn honest_downtime_across_a_reboot_is_credited_and_reported_as_unverified() {
    let c = curfew();
    observe(&c, NOW, 1_000, 3);

    let verdict = observe(&c, NOW + 8 * 3_600, 40, 4);

    assert_eq!(verdict["now"], NOW + 8 * 3_600);
    assert_eq!(verdict["unverified"], 8 * 3_600);
    assert_eq!(verdict["tampered"], false, "being switched off is not tampering");
}

#[test]
fn the_witness_survives_the_process_dying() {
    let c = curfew();
    observe(&c, NOW, 1_000, 3);
    observe(&c, NOW + 600, 1_600, 3);
    let saved = c.clock_witness_json().expect("serializes").expect("there is a witness");

    // A new object is what a restart looks like from here.
    let restarted = curfew();
    assert_eq!(restarted.trusted_now(), None);
    restarted.restore_clock(saved).expect("the witness loads");
    assert_eq!(restarted.trusted_now(), Some(NOW + 600));

    // And it refuses what it would have refused had it never died.
    let verdict = observe(&restarted, NOW + 90_000, 1_660, 3);
    assert_eq!(verdict["now"], NOW + 660);
    assert_eq!(verdict["refused_forward"], 90_000 - 660);
}

#[test]
fn there_is_no_witness_to_save_before_the_first_reading() {
    assert_eq!(curfew().clock_witness_json().expect("no error"), None);
}

#[test]
fn a_corrupt_witness_is_a_payload_error_rather_than_a_reset_baseline() {
    // Silently starting over would be the bug worth having: an attacker who can corrupt the stored
    // witness would get a fresh baseline, which is the whole prize.
    match curfew().restore_clock("{\"trusted\": ".into()).unwrap_err() {
        CurfewError::Payload { detail } => assert!(!detail.is_empty()),
        other => panic!("expected a payload error, got {other:?}"),
    }
}
