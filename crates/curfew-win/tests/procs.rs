//! What the Windows enforcer does to running programs.
//!
//! The process table is faked so that the cases that matter can actually be produced: a process
//! that refuses to die, an app running as five processes, a rule that only bites once a budget is
//! spent. What is not faked is the decision — that is the real core, because a Windows enforcer
//! that decides differently from the Android one is the bug this whole architecture exists to
//! prevent.

use curfew_core::{Config, Consumption, State};
use curfew_win::blocked_domains;
use curfew_win::extension::Watch;
use curfew_win::procs::{enforce, verdicts, Process, Processes, Verdict};
use curfew_win::Gates;
use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};

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
target = { kind = "window_title", pattern = "*- YouTube*" }
action = { kind = "block" }

[[profiles.rules]]
target = { kind = "windows_exe", exe = "slack.exe" }
action = { kind = "delay", seconds = 15 }

[[profiles.rules]]
target = { kind = "domain", domain = "reddit.com" }
action = { kind = "block" }

[[profiles.rules]]
target = { kind = "domain", domain = "news.example" }
action = { kind = "budget", seconds = 600, refill = { kind = "daily", at_minute = 240 } }
"#;

/// 2026-09-04 09:30 Europe/London, the same instant the Android tests use.
const NOW: i64 = 1_788_510_600;

fn config() -> Config {
    Config::from_toml(CONFIG).expect("the test config must parse")
}

fn active() -> State {
    State { active_profiles: vec!["deep-work".to_string()], ..State::default() }
}

fn proc(pid: u32, exe: &str, title: &str) -> Process {
    Process { pid, exe: exe.to_string(), title: title.to_string() }
}

/// A process table that records what it was asked to kill, and can refuse.
struct Fake {
    running: Vec<Process>,
    unkillable: BTreeSet<u32>,
    killed: RefCell<Vec<u32>>,
}

impl Fake {
    fn new(running: Vec<Process>) -> Self {
        Self { running, unkillable: BTreeSet::new(), killed: RefCell::new(Vec::new()) }
    }

    fn refusing(mut self, pid: u32) -> Self {
        self.unkillable.insert(pid);
        self
    }
}

impl Processes for Fake {
    fn list(&self) -> Vec<Process> {
        self.running.clone()
    }

    fn terminate(&self, pid: u32) -> bool {
        self.killed.borrow_mut().push(pid);
        !self.unkillable.contains(&pid)
    }
}

#[test]
fn a_blocked_exe_is_closed_and_nothing_else_is() {
    let table =
        Fake::new(vec![proc(1, "steam.exe", "Steam"), proc(2, "code.exe", "main.rs — curfew")]);

    let outcome =
        enforce(NOW, &active(), &config(), &table, &mut Gates::default(), &mut Watch::default());

    assert_eq!(outcome.closed, BTreeSet::from(["steam.exe".to_string()]));
    assert!(outcome.failed.is_empty());
    assert_eq!(*table.killed.borrow(), vec![1], "an unrelated editor was touched");
}

#[test]
fn nothing_is_closed_when_no_profile_is_active() {
    let table = Fake::new(vec![proc(1, "steam.exe", "Steam")]);

    let outcome = enforce(
        NOW,
        &State::default(),
        &config(),
        &table,
        &mut Gates::default(),
        &mut Watch::default(),
    );

    assert!(outcome.closed.is_empty());
    assert!(table.killed.borrow().is_empty(), "a lock that is not running closed something");
}

#[test]
fn every_process_of_one_app_is_closed_but_reported_once() {
    let table = Fake::new(vec![
        proc(10, "steam.exe", "Steam"),
        proc(11, "steam.exe", "Steam Web Helper"),
        proc(12, "Steam.exe", "Friends"),
    ]);

    let outcome =
        enforce(NOW, &active(), &config(), &table, &mut Gates::default(), &mut Watch::default());

    assert_eq!(*table.killed.borrow(), vec![10, 11, 12], "a surviving process is a bypass");
    assert_eq!(outcome.closed, BTreeSet::from(["steam.exe".to_string()]));
}

#[test]
fn a_process_that_cannot_be_killed_is_reported_rather_than_hidden() {
    let table = Fake::new(vec![proc(1, "steam.exe", "Steam")]).refusing(1);

    let outcome =
        enforce(NOW, &active(), &config(), &table, &mut Gates::default(), &mut Watch::default());

    assert!(outcome.closed.is_empty());
    assert_eq!(outcome.failed, BTreeSet::from(["steam.exe".to_string()]));
}

#[test]
fn a_window_title_rule_matches_the_window_not_the_program() {
    let table = Fake::new(vec![
        proc(1, "chrome.exe", "Rust in 100 seconds - YouTube - Google Chrome"),
        proc(2, "chrome.exe", "docs.rs - Google Chrome"),
    ]);

    let outcome =
        enforce(NOW, &active(), &config(), &table, &mut Gates::default(), &mut Watch::default());

    assert_eq!(*table.killed.borrow(), vec![1], "the whole browser was closed over one tab");
    assert_eq!(outcome.closed, BTreeSet::from(["chrome.exe".to_string()]));
}

#[test]
fn a_delay_holds_an_app_for_its_seconds_and_then_lets_it_run() {
    let table = Fake::new(vec![proc(1, "slack.exe", "Slack")]);
    let mut gates = Gates::default();

    let held = enforce(NOW, &active(), &config(), &table, &mut gates, &mut Watch::default());

    assert_eq!(held.delayed, BTreeMap::from([("slack.exe".to_string(), 15)]));
    assert_eq!(
        *table.killed.borrow(),
        vec![1],
        "the wait is only a wait if the app is not running"
    );
    // A delay is friction, not a block: nothing is reported as blocked or as having failed to be.
    assert!(held.closed.is_empty() && held.failed.is_empty());

    let after = enforce(NOW + 15, &active(), &config(), &table, &mut gates, &mut Watch::default());

    assert!(after.delayed.is_empty(), "the wait was served and the app is owed nothing more");
    assert_eq!(*table.killed.borrow(), vec![1], "an app was closed after it had served its wait");
}

#[test]
fn a_delayed_app_that_cannot_be_closed_is_not_reported_as_a_failure_to_block() {
    // It was always going to be allowed. Saying "could not close slack.exe" would send the user
    // chasing a permissions problem that changes nothing about what they asked for.
    let table = Fake::new(vec![proc(1, "slack.exe", "Slack")]).refusing(1);

    let outcome =
        enforce(NOW, &active(), &config(), &table, &mut Gates::default(), &mut Watch::default());

    assert!(outcome.failed.is_empty());
    assert_eq!(outcome.delayed, BTreeMap::from([("slack.exe".to_string(), 15)]));
}

#[test]
fn closing_a_delayed_app_makes_the_next_launch_wait_again() {
    let table = Fake::new(vec![proc(1, "slack.exe", "Slack")]);
    let mut gates = Gates::default();
    enforce(NOW, &active(), &config(), &table, &mut gates, &mut Watch::default());
    enforce(NOW + 15, &active(), &config(), &table, &mut gates, &mut Watch::default());

    let gone = Fake::new(vec![]);
    enforce(NOW + 20, &active(), &config(), &gone, &mut gates, &mut Watch::default());

    let again = enforce(NOW + 30, &active(), &config(), &table, &mut gates, &mut Watch::default());

    assert_eq!(again.delayed, BTreeMap::from([("slack.exe".to_string(), 15)]));
}

#[test]
fn verdicts_answer_without_touching_anything() {
    let running = vec![proc(1, "steam.exe", "Steam"), proc(2, "slack.exe", "Slack")];

    let answers = verdicts(NOW, &active(), &config(), &running);

    assert!(matches!(answers[0].1, Verdict::Close { .. }));
    assert_eq!(answers[1].1, Verdict::Delay { seconds: 15 });
}

#[test]
fn an_empty_machine_is_not_an_error() {
    let table = Fake::new(vec![]);
    assert_eq!(
        enforce(NOW, &active(), &config(), &table, &mut Gates::default(), &mut Watch::default()),
        Default::default()
    );
}

// --- the hosts list ---------------------------------------------------------------------------

#[test]
fn only_outright_blocked_domains_reach_the_hosts_file() {
    let blocked = blocked_domains(NOW, &active(), &config());

    assert!(blocked.contains("reddit.com"));
    assert!(
        !blocked.contains("news.example"),
        "a site with budget left must still resolve, or the resolver is lying to the user"
    );
}

#[test]
fn a_spent_budget_does_reach_the_hosts_file() {
    let mut state = active();
    let mut spent = Consumption::default();
    spent.record(NOW - 60, 600);
    state.usage.insert("domain:news.example".to_string(), spent);

    let blocked = blocked_domains(NOW, &state, &config());

    assert!(blocked.contains("news.example"));
}

#[test]
fn no_active_profile_blocks_no_domains() {
    assert!(blocked_domains(NOW, &State::default(), &config()).is_empty());
}

// --- the browser extension as a granularity layer (GAPS G1) -----------------------------------

const WITH_PATH_RULE: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "url", pattern = "*youtube.com/shorts*" }
action = { kind = "block" }
"#;

fn path_rule_config() -> Config {
    Config::from_toml(WITH_PATH_RULE).expect("the test config must parse")
}

#[test]
fn a_browser_with_no_extension_answering_for_it_is_closed_while_a_path_rule_runs() {
    // The service cannot see which tab is open, so the honest options are to close the browser or
    // to pretend the rule is being enforced. Pretending is the one that loses people their evening.
    let table = Fake::new(vec![proc(1, "chrome.exe", "New Tab")]);
    let mut watch = Watch::default();

    let start =
        enforce(NOW, &active(), &path_rule_config(), &table, &mut Gates::default(), &mut watch);
    assert!(start.unwatched.is_empty(), "a browser is given time to load its extension");

    let later = NOW + curfew_win::extension::GRACE_SECONDS + 60;
    let outcome =
        enforce(later, &active(), &path_rule_config(), &table, &mut Gates::default(), &mut watch);

    assert_eq!(outcome.unwatched, BTreeSet::from(["chrome.exe".to_string()]));
    assert_eq!(*table.killed.borrow(), vec![1]);
    assert!(outcome.closed.is_empty(), "this is not a blocked app and must not be reported as one");
}

#[test]
fn a_browser_whose_extension_is_reporting_is_left_alone() {
    let table = Fake::new(vec![proc(1, "chrome.exe", "New Tab")]);
    let mut watch = Watch::default();
    enforce(NOW, &active(), &path_rule_config(), &table, &mut Gates::default(), &mut watch);

    let later = NOW + curfew_win::extension::GRACE_SECONDS + 60;
    watch.beat("chrome.exe", later - 5);
    let outcome =
        enforce(later, &active(), &path_rule_config(), &table, &mut Gates::default(), &mut watch);

    assert!(outcome.unwatched.is_empty());
    assert!(table.killed.borrow().is_empty());
}

#[test]
fn a_profile_with_no_path_rules_costs_nobody_their_browser() {
    let table = Fake::new(vec![proc(1, "chrome.exe", "New Tab")]);
    let mut watch = Watch::default();
    let later = NOW + curfew_win::extension::GRACE_SECONDS + 60;

    let outcome = enforce(later, &active(), &config(), &table, &mut Gates::default(), &mut watch);

    assert!(outcome.unwatched.is_empty());
    assert!(table.killed.borrow().is_empty());
}

#[test]
fn a_browser_that_is_blocked_outright_is_reported_as_blocked_and_not_as_unwatched() {
    // Both are true of it, but only one is the reason: telling someone to install an extension
    // when the rule closes the browser regardless would be advice that changes nothing.
    let text = WITH_PATH_RULE.to_string()
        + "\n[[profiles.rules]]\ntarget = { kind = \"windows_exe\", exe = \"chrome.exe\" }\naction = { kind = \"block\" }\n";
    let config = Config::from_toml(&text).unwrap();
    let table = Fake::new(vec![proc(1, "chrome.exe", "New Tab")]);
    let later = NOW + curfew_win::extension::GRACE_SECONDS + 60;

    let outcome =
        enforce(later, &active(), &config, &table, &mut Gates::default(), &mut Watch::default());

    assert_eq!(outcome.closed, BTreeSet::from(["chrome.exe".to_string()]));
    assert!(outcome.unwatched.is_empty());
}

#[test]
fn a_browser_that_cannot_be_closed_is_reported_rather_than_quietly_left() {
    let table = Fake::new(vec![proc(1, "chrome.exe", "New Tab")]).refusing(1);
    let mut watch = Watch::default();
    enforce(NOW, &active(), &path_rule_config(), &table, &mut Gates::default(), &mut watch);
    let later = NOW + curfew_win::extension::GRACE_SECONDS + 60;

    let outcome =
        enforce(later, &active(), &path_rule_config(), &table, &mut Gates::default(), &mut watch);

    assert_eq!(outcome.failed, BTreeSet::from(["chrome.exe".to_string()]));
    assert!(outcome.unwatched.is_empty(), "nothing was actually closed");
}
