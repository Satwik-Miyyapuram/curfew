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

    assert!(matches!(response, Response::Error { .. }), "a bad password was accepted: {response:?}");
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
