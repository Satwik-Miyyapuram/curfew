//! One enforcement pass, end to end.
//!
//! These are the tests that would catch the Windows service doing something the phone would not:
//! blocking when no session is running, letting a session evaporate because a schedule was edited,
//! or leaving the hosts file blocking a machine whose lock ended hours ago.

use curfew_core::{CalendarEvent, Config, Lock, Session, SessionSource};
use curfew_win::procs::{Process, Processes};
use curfew_win::Enforcer;
use std::cell::RefCell;
use std::collections::BTreeSet;
use std::path::PathBuf;

/// A window 09:00–12:00 on weekdays, guarding a profile that blocks Steam and reddit.com, plus a
/// ten-minute daily budget for news.example so accrual has something to bite on.
const CONFIG: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "windows_exe", exe = "steam.exe" }
action = { kind = "block" }

[[profiles.rules]]
target = { kind = "domain", domain = "reddit.com" }
action = { kind = "block" }

[[profiles.rules]]
target = { kind = "windows_exe", exe = "news.exe" }
action = { kind = "budget", seconds = 600, refill = { kind = "daily", at_minute = 240 } }

[[weekly]]
id = "mornings"
profile = "deep-work"
days = [0, 1, 2, 3, 4]
start_minute = 540
end_minute = 720
locks = [{ kind = "timer" }]
"#;

/// 2026-09-04 09:30 Europe/London, a Friday, half an hour into the window.
const NOW: i64 = 1_788_510_600;
/// 2026-09-04 13:00 Europe/London: an hour after the window closed.
const AFTER: i64 = NOW + 3 * 3600 + 1800;

fn config() -> Config {
    Config::from_toml(CONFIG).expect("the test config must parse")
}

struct Fake {
    running: Vec<Process>,
    foreground: Option<Process>,
    killed: RefCell<Vec<u32>>,
}

impl Fake {
    fn new(running: Vec<Process>) -> Self {
        Self { running, foreground: None, killed: RefCell::new(Vec::new()) }
    }

    fn looking_at(mut self, process: Process) -> Self {
        self.foreground = Some(process);
        self
    }
}

impl Processes for Fake {
    fn list(&self) -> Vec<Process> {
        self.running.clone()
    }

    fn terminate(&self, pid: u32) -> bool {
        self.killed.borrow_mut().push(pid);
        true
    }

    fn foreground(&self) -> Option<Process> {
        self.foreground.clone()
    }
}

fn proc(pid: u32, exe: &str) -> Process {
    Process { pid, exe: exe.to_string(), title: exe.to_string() }
}

/// An `Enforcer` writing to a hosts file of its own, named after the test so parallel tests do not
/// fight over one path.
fn enforcer(name: &str) -> (Enforcer, PathBuf) {
    let dir = std::env::temp_dir().join(format!("curfew-tick-{}-{name}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("hosts");
    std::fs::write(&path, "127.0.0.1 localhost\r\n").unwrap();
    (Enforcer::new(config(), path.clone()), path)
}

fn hosts(path: &PathBuf) -> String {
    std::fs::read_to_string(path).unwrap()
}

#[test]
fn a_schedule_that_has_come_round_starts_a_session_and_enforces_it() {
    let (mut enforcer, path) = enforcer("starts");
    let table = Fake::new(vec![proc(1, "steam.exe"), proc(2, "code.exe")]);

    let tick = enforcer.tick(NOW, 0, &[], &table);

    assert_eq!(tick.started.len(), 1, "the window was open and nothing started");
    assert_eq!(tick.processes.closed, BTreeSet::from(["steam.exe".to_string()]));
    assert!(tick.domains.contains("reddit.com"));
    assert!(hosts(&path).contains("0.0.0.0 reddit.com"));
    assert!(hosts(&path).contains("127.0.0.1 localhost"), "the machine's own entry was eaten");
    assert_eq!(tick.hosts_error, None);
}

#[test]
fn running_the_same_pass_twice_changes_nothing() {
    let (mut enforcer, path) = enforcer("idempotent");
    let table = Fake::new(vec![proc(1, "steam.exe")]);

    let first = enforcer.tick(NOW, 0, &[], &table);
    let after_first = hosts(&path);
    let second = enforcer.tick(NOW, 0, &[], &table);

    // A service that crashes mid-pass has to be able to just run the pass again, so a repeat must
    // not start a second session or stack a second hosts block.
    assert!(second.started.is_empty(), "a second session was started for the same window");
    assert_eq!(hosts(&path), after_first);
    assert_eq!(first.domains, second.domains);
}

#[test]
fn outside_the_window_nothing_is_blocked_and_the_hosts_file_is_given_back() {
    let (mut enforcer, path) = enforcer("released");
    let table = Fake::new(vec![proc(1, "steam.exe")]);

    enforcer.tick(NOW, 0, &[], &table);
    table.killed.borrow_mut().clear();
    let tick = enforcer.tick(AFTER, 0, &[], &table);

    assert_eq!(tick.ended.len(), 1, "the session outlived its own window");
    assert!(tick.domains.is_empty());
    assert!(table.killed.borrow().is_empty(), "a lock that is over closed something");
    assert_eq!(hosts(&path), "127.0.0.1 localhost\r\n", "the file was not restored exactly");
}

#[test]
fn deleting_the_schedule_does_not_end_a_running_session() {
    let (mut enforcer, _) = enforcer("no-escape");
    let table = Fake::new(vec![proc(1, "steam.exe")]);
    enforcer.tick(NOW, 0, &[], &table);

    // The way out that a blocker must not have: edit the rule that started the lock.
    enforcer.config.weekly.clear();
    let tick = enforcer.tick(NOW + 60, 0, &[], &table);

    assert!(tick.ended.is_empty());
    assert_eq!(
        tick.processes.closed,
        BTreeSet::from(["steam.exe".to_string()]),
        "removing the schedule let the session go: a lock is a promise (invariant 2)"
    );
}

#[test]
fn a_credential_locked_session_is_not_ended_by_the_pass() {
    let (mut enforcer, _) = enforcer("credential");
    let table = Fake::new(vec![proc(1, "steam.exe")]);
    enforcer.sessions.start(Session {
        id: "manual".to_string(),
        profile: "deep-work".to_string(),
        source: SessionSource::Manual,
        started_at: NOW,
        lock: curfew_core::LockSet {
            conditions: BTreeSet::from([Lock::DeviceCredential]),
            ends_at: Some(AFTER + 3600),
            delayed_release_at: None,
        },
    });

    let tick = enforcer.tick(AFTER, 0, &[], &table);

    assert!(tick.ended.is_empty(), "a lock with time left was reaped");
    assert_eq!(tick.processes.closed, BTreeSet::from(["steam.exe".to_string()]));
}

#[test]
fn only_the_foreground_window_spends_its_budget() {
    let (mut enforcer, _) = enforcer("budget");
    let table = Fake::new(vec![proc(1, "news.exe")]).looking_at(proc(1, "news.exe"));
    enforcer.tick(NOW, 0, &[], &table);

    // Ten minutes of it, in one-minute passes: the last one exhausts the budget.
    for i in 1..=10 {
        enforcer.tick(NOW + i * 60, 60, &[], &table);
    }
    let tick = enforcer.tick(NOW + 11 * 60, 60, &[], &table);

    assert_eq!(
        tick.processes.closed,
        BTreeSet::from(["news.exe".to_string()]),
        "the budget ran out and the app was left running"
    );
}

#[test]
fn a_budgeted_app_left_in_the_background_is_not_charged() {
    let (mut enforcer, _) = enforcer("background");
    // Running, but the user is in the editor. Nothing should be spent.
    let table = Fake::new(vec![proc(1, "news.exe")]).looking_at(proc(2, "code.exe"));
    enforcer.tick(NOW, 0, &[], &table);
    for i in 1..=20 {
        enforcer.tick(NOW + i * 60, 60, &[], &table);
    }

    assert!(
        enforcer.usage.values().all(|c| c.rollups.is_empty()),
        "an allowance was spent on an app nobody was looking at"
    );
}

#[test]
fn a_hosts_file_that_cannot_be_written_does_not_stop_processes_being_closed() {
    let mut enforcer = Enforcer::new(
        config(),
        // A directory that does not exist: the same shape as a permission failure, and reachable
        // without needing to break a real file.
        std::env::temp_dir().join("curfew-no-such-dir-xyz").join("hosts"),
    );
    let table = Fake::new(vec![proc(1, "steam.exe")]);

    let tick = enforcer.tick(NOW, 0, &[], &table);

    assert!(tick.hosts_error.is_some(), "a failed write was reported as success");
    assert_eq!(
        tick.processes.closed,
        BTreeSet::from(["steam.exe".to_string()]),
        "losing website blocking must not also lose application blocking"
    );
}

#[test]
fn a_calendar_event_starts_a_session_the_same_way_a_weekly_window_does() {
    let mut config = config();
    config.weekly.clear();
    config.calendars = vec![curfew_core::CalendarSchedule {
        id: "focus".to_string(),
        profile: "deep-work".to_string(),
        matcher: curfew_core::EventMatcher {
            title: Some("*Focus*".to_string()),
            ..Default::default()
        },
        locks: vec![Lock::Timer],
        pad_before_seconds: 0,
        pad_after_seconds: 0,
    }];
    let mut enforcer = Enforcer::new(config, std::env::temp_dir().join("curfew-tick-cal-hosts"));
    let table = Fake::new(vec![proc(1, "steam.exe")]);

    let tick = enforcer.tick(
        NOW,
        0,
        &[CalendarEvent {
            id: "e1".to_string(),
            title: "Focus block".to_string(),
            calendar: "work".to_string(),
            location: String::new(),
            start: NOW - 600,
            end: NOW + 3600,
            all_day: false,
            busy: true,
        }],
        &table,
    );

    assert_eq!(tick.started.len(), 1);
    assert_eq!(tick.processes.closed, BTreeSet::from(["steam.exe".to_string()]));
}

#[test]
fn a_release_gives_the_machine_back() {
    let (mut enforcer, path) = enforcer("release");
    enforcer.tick(NOW, 0, &[], &Fake::new(vec![]));
    assert!(hosts(&path).contains("reddit.com"));

    enforcer.release().unwrap();

    assert_eq!(hosts(&path), "127.0.0.1 localhost\r\n");
}
