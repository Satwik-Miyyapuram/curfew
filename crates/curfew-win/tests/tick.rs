//! One enforcement pass, end to end.
//!
//! These are the tests that would catch the Windows service doing something the phone would not:
//! blocking when no session is running, letting a session evaporate because a schedule was edited,
//! or leaving the hosts file blocking a machine whose lock ended hours ago.

use curfew_core::{CalendarEvent, Config, Lock, Session, SessionSource};
use curfew_win::ipc::{Request, Response};
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
    enforcer_with(name, CONFIG)
}

/// The same, for a test that needs the schedule to carry a different lock.
fn enforcer_with(name: &str, toml: &str) -> (Enforcer, PathBuf) {
    let dir = std::env::temp_dir().join(format!("curfew-tick-{}-{name}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("hosts");
    std::fs::write(&path, "127.0.0.1 localhost\r\n").unwrap();
    let config = Config::from_toml(toml).expect("the test config must parse");
    (Enforcer::new(config, path.clone()), path)
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
fn a_service_that_cannot_see_the_desktop_charges_the_window_the_tray_reports() {
    let (mut enforcer, _) = enforcer("seen");
    // The service's own view: session 0, no foreground window at all.
    let table = Fake::new(vec![proc(1, "news.exe")]);
    enforcer.tick(NOW, 0, &[], &table);

    for i in 1..=10 {
        let at = NOW + i * 60;
        enforcer.handle(at, Request::Seen { exe: "news.exe".into(), title: "News".into() });
        enforcer.tick(at, 60, &[], &table);
    }
    let tick = enforcer.tick(NOW + 11 * 60, 60, &[], &table);

    assert_eq!(
        tick.processes.closed,
        BTreeSet::from(["news.exe".to_string()]),
        "the tray said the user was reading the news for ten minutes and none of it was charged"
    );
}

#[test]
fn a_report_that_has_gone_stale_stops_vouching_for_the_window() {
    let (mut enforcer, _) = enforcer("stale-seen");
    let table = Fake::new(vec![proc(1, "news.exe")]);
    enforcer.handle(NOW, Request::Seen { exe: "news.exe".into(), title: "News".into() });
    // A tray that has quit: nothing reported for twenty minutes.
    for i in 1..=20 {
        enforcer.tick(NOW + i * 60, 60, &[], &table);
    }

    assert!(
        enforcer.usage.values().all(|c| c.rollups.is_empty()),
        "a window the tray reported twenty minutes ago was still being charged"
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
        enabled: true,
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
            categories: Vec::new(),
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

#[test]
fn a_session_that_ends_is_kept_for_the_statistics() {
    let (mut enforcer, _) = enforcer("history");
    let table = Fake::new(vec![]);

    enforcer.tick(NOW, 0, &[], &table);
    assert!(enforcer.history.is_empty(), "a running session is not history yet");

    // The window has closed, so the pass reaps the session — and the record of it survives.
    enforcer.tick(AFTER, 0, &[], &table);

    assert_eq!(enforcer.history.len(), 1);
    let record = &enforcer.history[0];
    assert_eq!(record.profile, "deep-work");
    assert_eq!(record.started_at, NOW);
    assert_eq!(record.ended_at, Some(AFTER));
}

/// A session can end by being reaped, by a satisfied lock, by an emergency pass or by a peer. The
/// history is written by noticing what is no longer running, so every one of those is covered
/// without each having to remember to say so.
///
/// This uses a `confirm` lock rather than the schedule's `timer`, because a timer is not a condition
/// a caller may claim — see [`claiming_a_timer_does_not_release_a_timer_lock`].
#[test]
fn a_session_ended_by_hand_is_kept_too() {
    let (mut enforcer, _) = enforcer_with(
        "history-by-hand",
        &CONFIG.replace("locks = [{ kind = \"timer\" }]", "locks = [{ kind = \"confirm\" }]"),
    );
    let table = Fake::new(vec![]);

    enforcer.tick(NOW, 0, &[], &table);
    let id = enforcer.sessions.running[0].id.clone();

    let answer =
        enforcer.handle(NOW + 600, Request::End { id, satisfied: BTreeSet::from([Lock::Confirm]) });
    assert_eq!(
        answer,
        Response::Ok,
        "a confirmation the caller showed did not release the session"
    );

    enforcer.tick(NOW + 900, 0, &[], &table);

    assert_eq!(enforcer.history.len(), 1);
    assert_eq!(enforcer.history[0].ended_at, Some(NOW + 900));
}

/// The regression guard for the worst bug this review found.
///
/// `Lock::Timer` used to be in `claimable`, which meant a caller could assert that the timer had run
/// out and be believed. One line on the named pipe — no administrator, no UI, no tray — ended any
/// timer-locked session, and the flagship configuration in `tests/golden/example.toml` is a weekly
/// window locked exactly that way. A timer's condition is the machine fact `ends_at`; expiry already
/// grants it, and nothing legitimate was ever gained by letting a caller claim it.
#[test]
fn claiming_a_timer_does_not_release_a_timer_lock() {
    let (mut enforcer, _) = enforcer("timer-not-claimable");
    let table = Fake::new(vec![]);

    enforcer.tick(NOW, 0, &[], &table);
    let id = enforcer.sessions.running[0].id.clone();

    // The window runs 09:00–12:00 and NOW is 09:30, so the timer has two and a half hours left.
    let answer = enforcer.handle(
        NOW + 600,
        Request::End { id: id.clone(), satisfied: BTreeSet::from([Lock::Timer]) },
    );

    match answer {
        Response::Refused { refusal } => {
            assert!(
                matches!(refusal, curfew_core::Refusal::Locked { .. }),
                "a claimed timer was refused for the wrong reason: {refusal:?}"
            );
        }
        other => panic!("a timer was released by claiming it: {other:?}"),
    }
    assert_eq!(enforcer.sessions.running.len(), 1, "the session ended on a claimed timer");

    // …and the honest path still works: once the clock reaches `ends_at`, the pass reaps it.
    enforcer.tick(AFTER, 0, &[], &table);
    assert!(enforcer.sessions.running.is_empty(), "expiry no longer releases a timer lock");
}

#[test]
fn the_statistics_count_the_session_that_is_still_running() {
    let (mut enforcer, _) = enforcer("stats-running");
    let table = Fake::new(vec![]);

    enforcer.tick(NOW, 0, &[], &table);
    let stats = enforcer.stats(NOW + 3600, 7).expect("the config has a timezone");

    let today = stats.days.last().expect("today is in the window");
    assert_eq!(today.day, "2026-09-04");
    assert_eq!(today.blocked_seconds, 3600);
    assert_eq!(today.sessions, 1);
    assert_eq!(stats.current_streak, 1);
}

#[test]
fn history_older_than_thirty_days_is_dropped() {
    let (mut enforcer, _) = enforcer("history-pruned");
    let table = Fake::new(vec![]);

    enforcer.tick(NOW, 0, &[], &table);
    enforcer.tick(AFTER, 0, &[], &table);
    assert_eq!(enforcer.history.len(), 1);

    // A pass a fortnight later still has it; one two months later does not.
    enforcer.tick(AFTER + 14 * 24 * 3600, 0, &[], &table);
    assert_eq!(enforcer.history.len(), 1);
    enforcer.tick(AFTER + 60 * 24 * 3600, 0, &[], &table);
    assert!(enforcer.history.is_empty());
}
