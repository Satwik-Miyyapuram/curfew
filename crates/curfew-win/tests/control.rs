//! The control channel and the state file.
//!
//! Both are places where a lock could be lost by accident or taken away on purpose, so the tests
//! here are mostly about refusal: what the service will not do when asked nicely.

use curfew_core::{Config, Lock, Refusal, Session, SessionSource, Sessions};
use curfew_win::ipc::{encode, parse_request, Request, Response};
use curfew_win::procs::{Process, Processes};
use curfew_win::state::{load, save, Loaded, Persisted};
use curfew_win::Enforcer;
use std::collections::BTreeSet;
use std::path::PathBuf;

const CONFIG: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "domain", domain = "reddit.com" }
action = { kind = "block" }
"#;

const NOW: i64 = 1_788_510_600;

struct Empty;

impl Processes for Empty {
    fn list(&self) -> Vec<Process> {
        Vec::new()
    }

    fn terminate(&self, _pid: u32) -> bool {
        true
    }
}

fn dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("curfew-ctl-{}-{name}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn enforcer(name: &str) -> Enforcer {
    Enforcer::new(Config::from_toml(CONFIG).unwrap(), dir(name).join("hosts"))
}

// --- the control channel ------------------------------------------------------------------------

#[test]
fn a_manual_session_can_be_started_and_shows_up_in_status() {
    let mut e = enforcer("start");

    assert_eq!(
        e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] }),
        Response::Ok
    );
    e.tick(NOW, 0, &[], &Empty);

    let Response::Status(status) = e.handle(NOW, Request::Status) else { panic!("not a status") };
    assert_eq!(status.running.len(), 1);
    assert!(status.blocked_domains.contains("reddit.com"));
}

#[test]
fn starting_a_shorter_session_over_a_longer_one_does_not_shorten_it() {
    let mut e = enforcer("no-shorten");
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 7200, locks: vec![] });

    // The obvious attack on a timer: ask for ten minutes and hope it replaces the two hours.
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 600, locks: vec![] });

    let session = &e.sessions.running[0];
    assert_eq!(session.lock.ends_at, Some(NOW + 7200), "a running lock was shortened");
}

#[test]
fn starting_over_a_running_session_can_only_add_conditions() {
    let mut e = enforcer("merge");
    e.handle(
        NOW,
        Request::Start {
            profile: "deep-work".into(),
            seconds: 3600,
            locks: vec![Lock::DeviceCredential],
        },
    );
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] });

    assert!(
        e.sessions.running[0].lock.conditions.contains(&Lock::DeviceCredential),
        "a second start dropped the credential the first one asked for"
    );
}

#[test]
fn a_locked_session_is_not_ended_by_asking() {
    let mut e = enforcer("refuse");
    e.handle(
        NOW,
        Request::Start {
            profile: "deep-work".into(),
            seconds: 3600,
            locks: vec![Lock::DeviceCredential],
        },
    );
    let id = e.sessions.running[0].id.clone();

    // The caller claims nothing, which is the honest case, and also what a hostile caller sending
    // a hand-written line would look like.
    let response = e.handle(NOW, Request::End { id: id.clone(), satisfied: BTreeSet::new() });

    match response {
        Response::Refused { refusal: Refusal::Locked { missing, .. } } => {
            assert!(missing.contains(&Lock::DeviceCredential));
        }
        other => panic!("a credential lock was released by asking: {other:?}"),
    }
    assert_eq!(e.sessions.running.len(), 1);
}

#[test]
fn an_unlocked_session_ends_when_asked_and_gives_the_sites_back() {
    let mut e = enforcer("end");
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] });
    e.tick(NOW, 0, &[], &Empty);
    let id = e.sessions.running[0].id.clone();

    assert_eq!(e.handle(NOW, Request::End { id, satisfied: BTreeSet::new() }), Response::Ok);

    assert!(e.sessions.running.is_empty());
    let hosts = std::fs::read_to_string(&e.hosts_path).unwrap();
    assert!(!hosts.contains("reddit.com"), "the sites stayed blocked after the lock ended");
}

#[test]
fn the_delayed_release_lands_a_day_out_and_cannot_be_brought_forward() {
    let mut e = enforcer("release");
    e.handle(
        NOW,
        Request::Start {
            profile: "deep-work".into(),
            seconds: 999_999,
            locks: vec![Lock::DeviceCredential],
        },
    );
    let id = e.sessions.running[0].id.clone();

    let first = e.handle(NOW, Request::RequestRelease { id: id.clone() });
    // Asking again an hour later must not restart the clock, and must not move it in either
    // direction: the 24 hours are counted from the first request (GAPS D1).
    let again = e.handle(NOW + 3600, Request::RequestRelease { id });

    assert_eq!(first, Response::Release { at: NOW + 24 * 3600 });
    assert_eq!(again, first, "asking again moved the release");
}

#[test]
fn a_session_cannot_be_started_for_a_profile_that_does_not_exist() {
    let mut e = enforcer("no-profile");
    let response =
        e.handle(NOW, Request::Start { profile: "invented".into(), seconds: 60, locks: vec![] });
    assert!(matches!(response, Response::Error { .. }));
    assert!(e.sessions.running.is_empty());
}

#[test]
fn a_config_that_no_longer_parses_changes_nothing() {
    let mut e = enforcer("bad-reload");
    let path = dir("bad-reload").join("curfew.toml");
    std::fs::write(&path, "this is not toml = = =").unwrap();
    e.config_path = Some(path);
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] });

    let response = e.handle(NOW, Request::Reload);

    assert!(matches!(response, Response::Error { .. }));
    assert_eq!(e.sessions.running.len(), 1, "an unparseable config ended a session");
    assert_eq!(e.config.profiles.len(), 1, "an unparseable config replaced the good one");
}

#[test]
fn a_line_of_nonsense_on_the_pipe_is_an_error_not_a_panic() {
    assert!(parse_request("{oh no").is_err());
    assert!(parse_request("").is_err());
    assert!(parse_request(r#"{"request":"invented"}"#).is_err());
}

#[test]
fn requests_and_responses_round_trip_as_one_line_each() {
    let request =
        Request::End { id: "s1".into(), satisfied: BTreeSet::from([Lock::DeviceCredential]) };
    let line = serde_json::to_string(&request).unwrap();
    assert!(!line.contains('\n'), "a framed message must not contain the frame delimiter");
    assert_eq!(parse_request(&line).unwrap(), request);

    let encoded = encode(&Response::Ok);
    assert!(encoded.ends_with('\n'));
    assert_eq!(encoded.matches('\n').count(), 1);
}

// --- the state file -----------------------------------------------------------------------------

fn locked_state() -> Persisted {
    let mut sessions = Sessions::default();
    sessions.start(Session {
        id: "s1".into(),
        profile: "deep-work".into(),
        source: SessionSource::Manual,
        started_at: NOW,
        lock: curfew_core::LockSet::new([Lock::DeviceCredential], Some(NOW + 7200)),
    });
    Persisted { sessions, last_tick: Some(NOW), ..Default::default() }
}

#[test]
fn a_session_survives_being_written_and_read_back() {
    let path = dir("persist").join("state.json");
    let state = locked_state();

    save(&path, &state).unwrap();

    assert_eq!(load(&path), Loaded::Ok(state));
}

#[test]
fn a_first_run_is_told_apart_from_a_damaged_file() {
    assert_eq!(load(&dir("fresh").join("nothing-here.json")), Loaded::Fresh);
}

#[test]
fn a_corrupted_state_file_is_recovered_from_the_backup_rather_than_starting_empty() {
    let path = dir("corrupt").join("state.json");
    save(&path, &locked_state()).unwrap();
    // A second save is what puts the good copy in the backup; then the live file is destroyed, the
    // shape a half-written file or a hostile edit would leave.
    save(&path, &locked_state()).unwrap();
    std::fs::write(&path, "{ truncated").unwrap();

    match load(&path) {
        Loaded::Recovered { state, .. } => {
            assert_eq!(state.sessions.running.len(), 1, "a running lock was lost to corruption");
        }
        other => panic!("corruption emptied the state instead of recovering it: {other:?}"),
    }
}

#[test]
fn deleting_the_state_file_does_not_end_a_lock() {
    let path = dir("deleted").join("state.json");
    save(&path, &locked_state()).unwrap();
    save(&path, &locked_state()).unwrap();

    // The most obvious way out on a machine you administer.
    std::fs::remove_file(&path).unwrap();

    match load(&path) {
        Loaded::Recovered { state, .. } => assert_eq!(state.sessions.running.len(), 1),
        other => panic!("deleting the state file released a lock: {other:?}"),
    }
}

#[test]
fn losing_both_copies_is_reported_rather_than_passed_off_as_a_fresh_install() {
    let path = dir("lost").join("state.json");
    std::fs::write(&path, "{ truncated").unwrap();
    std::fs::write(path.with_extension("bak"), "also truncated").unwrap();

    match load(&path) {
        Loaded::Lost { detail } => assert!(!detail.is_empty()),
        other => panic!("both copies were unreadable and nothing was said: {other:?}"),
    }
}

#[test]
fn saving_leaves_no_temporary_file_behind() {
    let d = dir("no-temp");
    let path = d.join("state.json");
    save(&path, &locked_state()).unwrap();
    assert!(!path.with_extension("tmp").exists());
}

#[test]
fn a_wrong_password_does_not_open_a_credential_lock() {
    let mut e = enforcer("unlock");
    e.handle(
        NOW,
        Request::Start {
            profile: "deep-work".into(),
            seconds: 3600,
            locks: vec![Lock::DeviceCredential],
        },
    );
    let id = e.sessions.running[0].id.clone();

    let response = e.handle(
        NOW,
        Request::Unlock {
            id,
            username: "curfew-account-that-does-not-exist".into(),
            domain: String::new(),
            password: "not-the-password".into(),
        },
    );

    assert!(
        matches!(response, Response::Error { .. }),
        "a bad password was accepted: {response:?}"
    );
    assert_eq!(e.sessions.running.len(), 1);
}

#[test]
fn unlocking_proves_only_the_credential_and_not_the_other_conditions() {
    // The wire carries the password, and the service checks it — but a proven password says nothing
    // about a token, so a session locked with both must stay shut even on a correct one. Asserting
    // that here without a real password means asserting the shape: `Unlock` names exactly one
    // condition, and everything else in the message is inert.
    let request = Request::Unlock {
        id: "s1".into(),
        username: "someone".into(),
        domain: String::new(),
        password: "secret".into(),
    };
    let line = serde_json::to_string(&request).unwrap();
    assert!(!line.contains('\n'));
    assert_eq!(parse_request(&line).unwrap(), request);
    assert!(
        !format!("{:?}", curfew_win::credential::Secret::new("secret".into())).contains("secret"),
        "a password would be printed into a log line"
    );
}

// --- frozen mode (GAPS B4) ----------------------------------------------------------------------

/// A config whose profile takes the whole device, which is the one thing that can cost unsaved work.
const FROZEN: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "frozen"
name = "Frozen"

[[profiles.rules]]
target = { kind = "whole_device" }
action = { kind = "block" }
"#;

fn frozen(name: &str) -> Enforcer {
    Enforcer::new(Config::from_toml(FROZEN).unwrap(), dir(name).join("hosts"))
}

#[test]
fn asking_to_freeze_the_machine_announces_it_and_starts_nothing() {
    let mut e = frozen("freeze-announce");

    let response =
        e.handle(NOW, Request::Start { profile: "frozen".into(), seconds: 3600, locks: vec![] });

    match response {
        Response::Announced { countdown } => {
            assert!(countdown.fires_at >= NOW + 60, "a freeze was allowed to happen immediately");
        }
        other => panic!("freezing the whole device answered {other:?}"),
    }
    assert!(e.sessions.running.is_empty(), "the freeze started before its countdown ran");
}

#[test]
fn a_countdown_can_always_be_called_off_and_nothing_is_closed() {
    let mut e = frozen("freeze-cancel");
    e.handle(NOW, Request::Start { profile: "frozen".into(), seconds: 3600, locks: vec![] });

    // No lock is satisfied and none is needed: nothing has been started, so there is no promise.
    assert_eq!(e.handle(NOW + 10, Request::CancelFreeze), Response::Ok);

    e.tick(NOW + 3600, 1, &[], &Empty);
    assert!(e.sessions.running.is_empty(), "a cancelled freeze fired anyway");
    assert!(e.freeze.is_none());
}

#[test]
fn a_countdown_that_runs_out_becomes_a_real_session() {
    let mut e = frozen("freeze-fire");
    e.handle(NOW, Request::Start { profile: "frozen".into(), seconds: 3600, locks: vec![] });

    let early = e.tick(NOW + 30, 1, &[], &Empty);
    assert_eq!(early.froze, None, "it fired before the warning was over");

    let fired = e.tick(NOW + 60, 30, &[], &Empty);
    assert_eq!(fired.froze.as_deref(), Some("frozen"));
    assert_eq!(e.sessions.running.len(), 1);
    assert!(e.freeze.is_none(), "the countdown outlived the freeze it started");
}

#[test]
fn asking_twice_never_shortens_a_countdown_already_running() {
    let mut e = frozen("freeze-twice");
    e.handle(NOW, Request::Start { profile: "frozen".into(), seconds: 3600, locks: vec![] });
    let first = e.freeze.clone().unwrap().fires_at;

    // A second click 30 seconds in must not restart the clock in either direction: the user has
    // been told a time, and moving it earlier would take away warning they were promised.
    e.handle(NOW + 30, Request::Start { profile: "frozen".into(), seconds: 3600, locks: vec![] });

    assert_eq!(e.freeze.unwrap().fires_at, first);
}

#[test]
fn the_status_carries_the_countdown_so_no_ui_can_show_a_calm_machine() {
    let mut e = frozen("freeze-status");
    e.handle(NOW, Request::Start { profile: "frozen".into(), seconds: 3600, locks: vec![] });

    match e.handle(NOW + 5, Request::Status) {
        Response::Status(status) => assert!(status.freeze.is_some()),
        other => panic!("status answered {other:?}"),
    }
}

#[test]
fn a_schedule_cannot_freeze_the_machine_on_the_stroke_of_the_hour_either() {
    // The same warning applies however the session was asked for: a calendar event is not a reason
    // to close someone's unsaved work without notice.
    let config = format!(
        "{FROZEN}\n[[weekly]]\nid = \"w\"\nprofile = \"frozen\"\nstart_minute = 0\n\
         end_minute = 1439\n"
    );
    let mut e =
        Enforcer::new(Config::from_toml(&config).unwrap(), dir("freeze-sched").join("hosts"));

    let first = e.tick(NOW, 1, &[], &Empty);

    assert_eq!(first.started, Vec::<String>::new(), "a schedule froze the machine with no warning");
    assert!(e.freeze.is_some(), "the schedule was dropped instead of being announced");

    let later = e.tick(NOW + 60, 60, &[], &Empty);
    assert_eq!(later.froze.as_deref(), Some("frozen"));
}

#[test]
fn confirming_when_nothing_is_counting_down_is_an_error_rather_than_a_surprise() {
    let mut e = frozen("freeze-confirm-none");
    match e.handle(NOW, Request::ConfirmFreeze) {
        Response::Error { .. } => {}
        other => panic!("confirming nothing answered {other:?}"),
    }
}

#[test]
fn a_freeze_asked_for_by_a_peer_waits_for_someone_at_this_machine() {
    use curfew_core::frozen::{announce, due, Due};
    let mut e = frozen("freeze-peer");
    e.freeze = Some(announce(NOW, "frozen", 3600, curfew_core::Origin::Peer, 60));

    // Well past the moment a local countdown would have fired.
    let ignored = e.tick(NOW + 120, 1, &[], &Empty);
    assert_eq!(ignored.froze, None, "a peer froze this machine without anyone here agreeing");

    match e.handle(NOW + 130, Request::ConfirmFreeze) {
        Response::Announced { countdown } => assert!(countdown.confirmed),
        other => panic!("confirming answered {other:?}"),
    }
    assert_eq!(due(&e.freeze.clone().unwrap(), NOW + 130), Due::Fire);
    assert_eq!(e.tick(NOW + 130, 1, &[], &Empty).froze.as_deref(), Some("frozen"));
}

// --- what the browser extension may ask (GAPS G1) ------------------------------------------------

const URL_CONFIG: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "url", pattern = "*youtube.com/shorts*" }
action = { kind = "block" }
"#;

fn url_enforcer(name: &str) -> Enforcer {
    Enforcer::new(Config::from_toml(URL_CONFIG).unwrap(), dir(name).join("hosts"))
}

#[test]
fn the_service_answers_a_url_check_and_says_which_rule_did_it() {
    let mut e = url_enforcer("check");
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] });

    let answer = e.handle(
        NOW,
        Request::Check {
            browser: "chrome.exe".into(),
            url: "https://www.youtube.com/shorts/abc?x=1".into(),
        },
    );

    match answer {
        Response::Verdict { blocked, reason } => {
            assert!(blocked);
            assert!(reason.unwrap().contains("deep-work"));
        }
        other => panic!("expected a verdict, got {other:?}"),
    }
}

#[test]
fn a_url_no_rule_covers_is_allowed_rather_than_blocked_by_default() {
    // An extension that blocks whatever the service fails to answer for would turn a service
    // restart into a browser that shows nothing but block pages.
    let mut e = url_enforcer("allow");
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] });

    let answer = e.handle(
        NOW,
        Request::Check { browser: "chrome.exe".into(), url: "https://docs.rs/".into() },
    );

    assert_eq!(answer, Response::Verdict { blocked: false, reason: None });
}

#[test]
fn a_check_counts_as_a_heartbeat_because_a_browser_that_is_asking_is_plainly_alive() {
    let mut e = url_enforcer("check-beats");
    e.handle(NOW, Request::Check { browser: "chrome.exe".into(), url: "https://docs.rs/".into() });

    assert!(e.watch.trusted("chrome.exe", NOW + 10));
}

#[test]
fn a_heartbeat_is_recorded_and_changes_nothing_else() {
    let mut e = url_enforcer("beat");
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] });

    assert_eq!(
        e.handle(NOW, Request::Beat { browser: "chrome.exe".into(), url: None }),
        Response::Ok
    );

    assert!(e.watch.trusted("chrome.exe", NOW + 10));
    // The one thing a heartbeat must never be is a way out of a session.
    assert_eq!(e.sessions.running.len(), 1);
}

const WEB_BUDGET: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "url", pattern = "*youtube.com*" }
action = { kind = "budget", seconds = 600, refill = { kind = "daily", at_minute = 240 } }
"#;

#[test]
fn a_heartbeat_naming_a_page_spends_that_page_s_budget() {
    let mut e =
        Enforcer::new(Config::from_toml(WEB_BUDGET).unwrap(), dir("web-budget").join("hosts"));
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] });
    let url = "https://www.youtube.com/watch?v=x".to_string();

    // The first beat opens the clock; each later one charges the interval since the last.
    e.handle(NOW, Request::Beat { browser: "chrome.exe".into(), url: Some(url.clone()) });
    for i in 1..=30 {
        e.handle(
            NOW + i * 20,
            Request::Beat { browser: "chrome.exe".into(), url: Some(url.clone()) },
        );
    }

    let check =
        e.handle(NOW + 601, Request::Check { browser: "chrome.exe".into(), url: url.clone() });
    assert!(
        matches!(check, Response::Verdict { blocked: true, .. }),
        "ten minutes on the page left the ten-minute budget untouched: {check:?}"
    );
}

#[test]
fn a_heartbeat_with_no_focused_page_spends_nothing() {
    let mut e =
        Enforcer::new(Config::from_toml(WEB_BUDGET).unwrap(), dir("web-idle").join("hosts"));
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![] });
    for i in 0..=40 {
        e.handle(NOW + i * 20, Request::Beat { browser: "chrome.exe".into(), url: None });
    }

    assert!(
        e.usage.values().all(|c| c.rollups.is_empty()),
        "a browser behind another window was charged for a page nobody was looking at"
    );
}

#[test]
fn nothing_the_extension_can_say_ends_a_locked_session() {
    let mut e = url_enforcer("no-exit");
    e.handle(
        NOW,
        Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![Lock::Timer] },
    );
    let id = e.sessions.running[0].id.clone();

    e.handle(NOW, Request::Beat { browser: "chrome.exe".into(), url: None });
    e.handle(NOW, Request::Check { browser: "chrome.exe".into(), url: "https://x.test/".into() });

    assert!(matches!(
        e.handle(NOW + 1, Request::End { id, satisfied: BTreeSet::new() }),
        Response::Refused { .. }
    ));
}

// --- the escape hatch ---------------------------------------------------------------------------

const HATCH: &str = r#"
schema_version = 1
timezone = "Europe/London"

[emergency]
passes = 2
window_seconds = 604800
cooldown_seconds = 86400

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "domain", domain = "reddit.com" }
action = { kind = "block" }
"#;

fn hatched(name: &str) -> Enforcer {
    Enforcer::new(Config::from_toml(HATCH).unwrap(), dir(name).join("hosts"))
}

fn locked(e: &mut Enforcer) -> String {
    e.handle(
        NOW,
        Request::Start {
            profile: "deep-work".into(),
            seconds: 999_999,
            locks: vec![Lock::DeviceCredential],
        },
    );
    e.sessions.running[0].id.clone()
}

#[test]
fn an_emergency_pass_ends_a_session_no_password_was_typed_for() {
    let mut e = hatched("pass");
    let id = locked(&mut e);

    assert_eq!(e.handle(NOW, Request::Emergency { id }), Response::Ok);
    assert!(e.sessions.running.is_empty());
    // And the blocks it was holding are gone with it, not left behind by a release that only
    // touched the session list.
    let hosts = std::fs::read_to_string(&e.hosts_path).unwrap_or_default();
    assert!(!hosts.contains("reddit.com"));
}

#[test]
fn a_pass_is_refused_when_the_rules_never_offered_one() {
    let mut e = enforcer("no-hatch");
    let id = locked(&mut e);

    assert_eq!(
        e.handle(NOW, Request::Emergency { id }),
        Response::NoPass { refusal: curfew_core::PassRefusal::Disabled }
    );
    assert_eq!(e.sessions.running.len(), 1, "a refused pass ended a session anyway");
}

#[test]
fn a_second_pass_inside_the_cooldown_is_refused_and_says_when() {
    let mut e = hatched("cooldown");
    let first = locked(&mut e);
    e.handle(NOW, Request::Emergency { id: first });

    let second = locked(&mut e);
    assert_eq!(
        e.handle(NOW + 60, Request::Emergency { id: second }),
        Response::NoPass { refusal: curfew_core::PassRefusal::CoolingDown { until: NOW + 86400 } }
    );
    assert_eq!(e.sessions.running.len(), 1);
}

#[test]
fn a_pass_spent_on_a_session_that_has_already_ended_is_still_spent() {
    // The ration counts reaching for the hatch, not succeeding with it. Otherwise a stale id is a
    // free probe of how many passes are left.
    let mut e = hatched("stale");
    let id = locked(&mut e);
    e.handle(NOW, Request::Emergency { id: id.clone() });

    assert_eq!(
        e.handle(NOW + 1, Request::Emergency { id }),
        Response::NoPass { refusal: curfew_core::PassRefusal::CoolingDown { until: NOW + 86400 } }
    );
    assert_eq!(e.passes.used.len(), 1);
}

#[test]
fn the_status_says_how_many_passes_are_left_without_being_asked_twice() {
    let mut e = hatched("status-passes");
    let Response::Status(before) = e.handle(NOW, Request::Status) else { panic!("no status") };
    assert_eq!(before.passes_left, 2);
    assert_eq!(before.pass_refusal, None);

    let id = locked(&mut e);
    e.handle(NOW, Request::Emergency { id });

    let Response::Status(after) = e.handle(NOW + 60, Request::Status) else { panic!("no status") };
    assert_eq!(after.passes_left, 1);
    assert_eq!(
        after.pass_refusal,
        Some(curfew_core::PassRefusal::CoolingDown { until: NOW + 86400 }),
        "one left, but not right now — a status that said only the count would mislead"
    );
}

#[test]
fn passes_survive_a_restart_the_way_sessions_do() {
    let path = dir("pass-state").join("state.json");
    let mut e = hatched("pass-state");
    let id = locked(&mut e);
    e.handle(NOW, Request::Emergency { id });

    save(
        &path,
        &Persisted {
            sessions: e.sessions.clone(),
            usage: Default::default(),
            launches: Default::default(),
            passes: e.passes.clone(),
            boots: e.boots.clone(),
            boot_counter: e.boot_counter.clone(),
            clock: e.clock.clone(),
            releases: e.releases.clone(),
            history: Vec::new(),
            last_tick: Some(NOW),
        },
    )
    .unwrap();

    let Loaded::Ok(back) = load(&path) else { panic!("the state did not come back") };
    assert_eq!(back.passes, e.passes, "a restart handed back a fresh ration");
}

// --- the other three ways out -------------------------------------------------------------------
//
// A tag, a reboot and a paired device. Each is a condition the *service* checks: the tests below
// are as much about what a caller cannot claim as about what a user can prove.

const TAGGED: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "domain", domain = "reddit.com" }
action = { kind = "block" }

[[tokens]]
id = "fridge"
hash = "PLACEHOLDER"
"#;

const PAYLOAD: &str = "curfew-tag-abcdefghijkmnopqrstuvwx";

fn tagged(name: &str) -> Enforcer {
    let toml = TAGGED.replace("PLACEHOLDER", &curfew_core::fingerprint(PAYLOAD));
    Enforcer::new(Config::from_toml(&toml).unwrap(), dir(name).join("hosts"))
}

fn held(e: &mut Enforcer, locks: Vec<Lock>) -> String {
    e.handle(NOW, Request::Start { profile: "deep-work".into(), seconds: 999_999, locks });
    e.sessions.running[0].id.clone()
}

#[test]
fn the_right_tag_ends_a_session_and_the_wrong_one_does_not() {
    let mut e = tagged("tag-right");
    let id = held(&mut e, vec![Lock::Token { id: "fridge".into() }]);

    let wrong = e.handle(NOW, Request::Token { id: id.clone(), payload: "hello".into() });
    assert!(matches!(wrong, Response::Error { .. }), "an unknown tag was accepted");
    assert_eq!(e.sessions.running.len(), 1);

    assert_eq!(
        e.handle(NOW, Request::Token { id, payload: PAYLOAD.into() }),
        Response::Ok,
        "the tag the config names did not open its own lock"
    );
    assert!(e.sessions.running.is_empty());
}

#[test]
fn a_tag_that_exists_but_is_not_the_one_this_lock_asks_for_is_refused() {
    let mut e = tagged("tag-other");
    let id = held(&mut e, vec![Lock::Token { id: "desk".into() }]);

    let answer = e.handle(NOW, Request::Token { id, payload: PAYLOAD.into() });
    assert!(
        matches!(answer, Response::Refused { refusal: Refusal::Locked { .. } }),
        "the fridge tag opened a lock that asks for the desk one: {answer:?}"
    );
    assert_eq!(e.sessions.running.len(), 1);
}

#[test]
fn claiming_a_tag_over_the_pipe_proves_nothing() {
    // The whole point of the tag is the walk to it. A caller that could simply say "token
    // satisfied" would turn a physical lock into a JSON message.
    let mut e = tagged("tag-claim");
    let id = held(&mut e, vec![Lock::Token { id: "fridge".into() }]);

    let claimed = BTreeSet::from([Lock::Token { id: "fridge".into() }]);
    let answer = e.handle(NOW, Request::End { id, satisfied: claimed });
    assert!(
        matches!(answer, Response::Refused { .. }),
        "a claim was taken as evidence: {answer:?}"
    );
    assert_eq!(e.sessions.running.len(), 1);
}

#[test]
fn a_tag_and_a_password_can_be_presented_one_after_the_other() {
    // Two conditions, two actions, in either order and a minute apart. If each proof vanished as
    // soon as it was made, a lock asking for both could never be satisfied at all.
    let mut e = tagged("tag-and-pin");
    let id = held(&mut e, vec![Lock::Token { id: "fridge".into() }, Lock::DeviceCredential]);

    let first = e.handle(NOW, Request::Token { id: id.clone(), payload: PAYLOAD.into() });
    assert!(matches!(first, Response::Refused { .. }), "the tag alone ended a two-part lock");
    assert_eq!(e.sessions.running.len(), 1);

    // Standing in for the password check, which needs a real account to exercise. The proof is
    // recorded by the same call that verifies it, so this is the state the verification leaves.
    e.proofs.record(&id, Lock::DeviceCredential, NOW + 30);
    assert_eq!(e.handle(NOW + 30, Request::End { id, satisfied: BTreeSet::new() }), Response::Ok);
}

#[test]
fn a_proof_goes_stale_rather_than_standing_all_evening() {
    let mut e = tagged("tag-stale");
    let id = held(&mut e, vec![Lock::Token { id: "fridge".into() }, Lock::DeviceCredential]);

    e.handle(NOW, Request::Token { id: id.clone(), payload: PAYLOAD.into() });
    e.proofs.record(&id, Lock::DeviceCredential, NOW + curfew_core::PROOF_SECONDS);

    let answer =
        e.handle(NOW + curfew_core::PROOF_SECONDS, Request::End { id, satisfied: BTreeSet::new() });
    assert!(
        matches!(answer, Response::Refused { .. }),
        "a tag scanned two minutes ago was still counting: {answer:?}"
    );
}

#[test]
fn a_restart_lock_holds_until_the_machine_is_actually_restarted() {
    let mut e = enforcer("restart");
    e.observe_boot(4_000);
    let id = held(&mut e, vec![Lock::RestartRequired]);
    e.tick(NOW, 0, &[], &Empty);

    let refused = e.handle(NOW, Request::End { id: id.clone(), satisfied: BTreeSet::new() });
    assert!(matches!(refused, Response::Refused { .. }), "a restart lock opened without one");

    // Uptime back at nearly nothing: the machine went down and came up.
    e.observe_boot(12);
    e.tick(NOW + 3600, 0, &[], &Empty);
    assert_eq!(
        e.handle(NOW + 3600, Request::End { id, satisfied: BTreeSet::new() }),
        Response::Ok,
        "the reboot the lock asked for did not satisfy it"
    );
}

#[test]
fn a_clock_moved_forward_is_not_a_restart() {
    // The cheapest bypass there is, if boot time were taken to be "now minus uptime".
    let mut e = enforcer("restart-clock");
    e.observe_boot(4_000);
    let id = held(&mut e, vec![Lock::RestartRequired]);
    e.tick(NOW, 0, &[], &Empty);

    e.observe_boot(4_100);
    e.tick(NOW + 400_000, 0, &[], &Empty);
    let answer = e.handle(NOW + 400_000, Request::End { id, satisfied: BTreeSet::new() });
    assert!(matches!(answer, Response::Refused { .. }), "a clock change ended a restart lock");
}

#[test]
fn a_session_started_after_the_reboot_still_needs_its_own() {
    let mut e = enforcer("restart-after");
    e.observe_boot(4_000);
    e.tick(NOW, 0, &[], &Empty);
    e.observe_boot(9);
    let id = held(&mut e, vec![Lock::RestartRequired]);
    e.tick(NOW + 60, 0, &[], &Empty);

    let answer = e.handle(NOW + 60, Request::End { id, satisfied: BTreeSet::new() });
    assert!(
        matches!(answer, Response::Refused { .. }),
        "a reboot before the session began was counted for it: {answer:?}"
    );
}

#[test]
fn restart_evidence_survives_the_service_being_restarted() {
    // The evidence is a fact about the machine, so it must not be a fact about the process. A
    // service that forgot which boot it started a session in could never satisfy this lock.
    let path = dir("restart-state").join("state.json");
    let mut e = enforcer("restart-state");
    e.observe_boot(4_000);
    let id = held(&mut e, vec![Lock::RestartRequired]);
    e.tick(NOW, 0, &[], &Empty);

    save(
        &path,
        &Persisted {
            sessions: e.sessions.clone(),
            usage: Default::default(),
            launches: Default::default(),
            passes: Default::default(),
            boots: e.boots.clone(),
            boot_counter: e.boot_counter.clone(),
            clock: e.clock.clone(),
            releases: e.releases.clone(),
            history: Vec::new(),
            last_tick: Some(NOW),
        },
    )
    .unwrap();

    let Loaded::Ok(back) = load(&path) else { panic!("the state did not come back") };
    let mut after = enforcer("restart-state");
    after.sessions = back.sessions;
    after.boots = back.boots;
    after.boot_counter = back.boot_counter;
    after.observe_boot(11);
    after.tick(NOW + 3600, 0, &[], &Empty);

    assert_eq!(
        after.handle(NOW + 3600, Request::End { id, satisfied: BTreeSet::new() }),
        Response::Ok
    );
}

#[test]
fn a_peer_lock_naming_this_device_is_offered_and_then_given() {
    let mut e = enforcer("peer");
    e.device_id = Some("PHONE7".into());
    let id = held(&mut e, vec![Lock::PeerRelease { device_id: "PHONE7".into() }]);
    e.tick(NOW, 0, &[], &Empty);

    let Response::Status(before) = e.handle(NOW, Request::Status) else { panic!("no status") };
    assert_eq!(before.releasable, vec![id.clone()], "this device was not offered the release");
    assert!(before.released.is_empty());

    assert_eq!(e.handle(NOW, Request::Release { id: id.clone() }), Response::Ok);
    assert!(
        e.sessions.running.is_empty(),
        "the release this device gave did not open its own lock"
    );
    assert!(e.releases.contains(&id), "the release was not written down to be published");
}

#[test]
fn a_release_from_the_wrong_device_does_not_open_the_lock() {
    let mut e = enforcer("peer-wrong");
    e.device_id = Some("LAPTOP2".into());
    let id = held(&mut e, vec![Lock::PeerRelease { device_id: "PHONE7".into() }]);
    e.tick(NOW, 0, &[], &Empty);

    let Response::Status(status) = e.handle(NOW, Request::Status) else { panic!("no status") };
    assert!(status.releasable.is_empty(), "a device the lock does not name was offered the button");

    e.handle(NOW, Request::Release { id: id.clone() });
    assert_eq!(
        e.sessions.running.len(),
        1,
        "the wrong device released a lock it was not asked for"
    );

    // And a claim over the pipe is not a release either.
    let claimed = BTreeSet::from([Lock::PeerRelease { device_id: "PHONE7".into() }]);
    let answer = e.handle(NOW, Request::End { id, satisfied: claimed });
    assert!(matches!(answer, Response::Refused { .. }), "a claimed peer release was believed");
}

#[test]
fn a_release_heard_through_the_log_ends_the_session_here() {
    // What the sync loop does: the peer's entry becomes an entry in `released`, and from then on
    // the ordinary end works.
    let mut e = enforcer("peer-heard");
    let id = held(&mut e, vec![Lock::PeerRelease { device_id: "PHONE7".into() }]);
    e.released.insert(id.clone(), BTreeSet::from(["PHONE7".to_string()]));

    assert_eq!(e.handle(NOW, Request::End { id, satisfied: BTreeSet::new() }), Response::Ok);
}

#[test]
fn a_release_for_a_session_this_device_has_never_heard_of_is_still_recorded() {
    // The usual case: the lock is on the phone, and the PC is only being asked to say yes. Waiting
    // for the session to arrive here first would make the button work only sometimes.
    let mut e = enforcer("peer-unknown");
    e.device_id = Some("PC1".into());

    assert_eq!(e.handle(NOW, Request::Release { id: "elsewhere".into() }), Response::Ok);
    assert!(e.releases.contains("elsewhere"));
}
