//! Editing schedules from the command line, end to end: arguments in, config file out.
//!
//! These run the real entry point against a real file, because everything worth getting wrong here
//! lives between the two — a flag that parses and is then ignored, an edit the core refuses that
//! leaves half a document behind, an exit code that says a command worked when it did not.

use curfew_core::Config;
use std::path::{Path, PathBuf};

const BASE: &str = "\
schema_version = 1
timezone = \"Europe/London\"

[[profiles]]
id = \"deep-work\"
name = \"Deep work\"
rules = []
";

/// A config file of its own for one test, in the process's temp directory.
struct Sandbox {
    path: PathBuf,
}

impl Sandbox {
    fn new(name: &str) -> Self {
        let path =
            std::env::temp_dir().join(format!("curfew-cli-{name}-{}.toml", std::process::id()));
        std::fs::write(&path, BASE).unwrap();
        Self { path }
    }

    fn as_str(&self) -> &str {
        self.path.to_str().unwrap()
    }

    fn text(&self) -> String {
        std::fs::read_to_string(&self.path).unwrap()
    }

    fn config(&self) -> Config {
        Config::from_toml(&self.text()).expect("the file the CLI wrote must still parse")
    }
}

impl Drop for Sandbox {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
        let _ = std::fs::remove_file(self.path.with_extension("toml.new"));
    }
}

fn run(args: &[&str]) -> i32 {
    curfew_cli::run(args)
}

#[test]
fn a_window_added_from_the_command_line_is_in_the_file() {
    let box_ = Sandbox::new("add-window");
    assert_eq!(
        0,
        run(&[
            "add-window",
            box_.as_str(),
            "--id",
            "evenings",
            "--profile",
            "deep-work",
            "--from",
            "21:00",
            "--to",
            "23:30",
            "--days",
            "weekdays",
            "--lock",
            "credential",
        ])
    );

    let window = box_.config().weekly.into_iter().next().expect("a window");
    assert_eq!(window.id, "evenings");
    assert_eq!(window.profile, "deep-work");
    assert_eq!((window.start_minute, window.end_minute), (21 * 60, 23 * 60 + 30));
    assert_eq!(window.days, vec![0, 1, 2, 3, 4]);
    assert_eq!(window.locks, vec![curfew_core::Lock::DeviceCredential]);
}

/// The overnight shape, which is the one most people actually want and the one a naive editor
/// rejects as an empty window.
#[test]
fn a_window_crossing_midnight_is_accepted() {
    let box_ = Sandbox::new("overnight");
    let args = [
        "add-window",
        box_.as_str(),
        "--id",
        "night",
        "--profile",
        "deep-work",
        "--from",
        "23:00",
        "--to",
        "07:00",
    ];
    assert_eq!(0, run(&args));
    let window = box_.config().weekly.into_iter().next().unwrap();
    assert!(window.end_minute < window.start_minute);
}

#[test]
fn adding_a_window_twice_edits_it_rather_than_duplicating_it() {
    let box_ = Sandbox::new("upsert");
    let mut args = vec![
        "add-window",
        box_.as_str(),
        "--id",
        "w",
        "--profile",
        "deep-work",
        "--from",
        "09:00",
        "--to",
        "10:00",
    ];
    assert_eq!(0, run(&args));
    args[9] = "12:00";
    assert_eq!(0, run(&args));

    let weekly = box_.config().weekly;
    assert_eq!(1, weekly.len());
    assert_eq!(12 * 60, weekly[0].end_minute);
}

/// A refused edit must leave the file exactly as it was. Half a config is a config the service
/// will not load, and a blocker that will not load is a blocker that is not blocking.
#[test]
fn a_window_naming_a_profile_that_does_not_exist_changes_nothing() {
    let box_ = Sandbox::new("bad-profile");
    let before = box_.text();
    assert_eq!(
        1,
        run(&[
            "add-window",
            box_.as_str(),
            "--id",
            "w",
            "--profile",
            "nope",
            "--from",
            "09:00",
            "--to",
            "10:00"
        ])
    );
    assert_eq!(before, box_.text());
}

#[test]
fn a_time_that_is_not_a_time_is_refused_before_anything_is_written() {
    let box_ = Sandbox::new("bad-time");
    let before = box_.text();
    assert_eq!(
        1,
        run(&[
            "add-window",
            box_.as_str(),
            "--id",
            "w",
            "--profile",
            "deep-work",
            "--from",
            "25:00",
            "--to",
            "10:00"
        ])
    );
    assert_eq!(before, box_.text());
}

#[test]
fn a_window_that_starts_and_ends_at_the_same_minute_is_refused() {
    let box_ = Sandbox::new("same-minute");
    assert_eq!(
        1,
        run(&[
            "add-window",
            box_.as_str(),
            "--id",
            "w",
            "--profile",
            "deep-work",
            "--from",
            "09:00",
            "--to",
            "09:00"
        ])
    );
    assert!(box_.config().weekly.is_empty());
}

/// Misuse and a bad config are different problems with different fixes, and the exit codes say so:
/// 2 is "you typed it wrong", 1 is "what you typed will not work".
#[test]
fn a_missing_flag_exits_two_rather_than_one() {
    let box_ = Sandbox::new("missing-flag");
    assert_eq!(1, run(&["add-window", box_.as_str(), "--id", "w"]));
    assert_eq!(2, run(&["add-window"]));
}

#[test]
fn a_calendar_rule_keeps_every_part_of_its_matcher() {
    let box_ = Sandbox::new("add-calendar");
    assert_eq!(
        0,
        run(&[
            "add-calendar",
            box_.as_str(),
            "--id",
            "meetings",
            "--profile",
            "deep-work",
            "--title",
            "*standup*",
            "--calendar",
            "Work",
            "--busy-only",
            "--timed",
            "--category",
            "focus",
            "--pad-before",
            "5",
            "--pad-after",
            "10",
            "--longer-than",
            "15",
            "--lock",
            "timer",
        ])
    );

    let rule = box_.config().calendars.into_iter().next().expect("a rule");
    assert_eq!(rule.matcher.title.as_deref(), Some("*standup*"));
    assert_eq!(rule.matcher.calendar.as_deref(), Some("Work"));
    assert!(rule.matcher.busy_only);
    assert_eq!(rule.matcher.all_day, Some(false));
    assert_eq!(rule.matcher.categories, vec!["focus".to_string()]);
    assert_eq!(rule.matcher.min_duration_seconds, Some(900));
    // Padding is entered in minutes and stored in seconds; getting this backwards would turn five
    // minutes of lead-in into five seconds.
    assert_eq!((rule.pad_before_seconds, rule.pad_after_seconds), (300, 600));
    assert_eq!(rule.locks, vec![curfew_core::Lock::Timer]);
}

#[test]
fn a_rule_that_is_all_day_and_timed_at_once_is_refused() {
    let box_ = Sandbox::new("contradiction");
    assert_eq!(
        1,
        run(&[
            "add-calendar",
            box_.as_str(),
            "--id",
            "r",
            "--profile",
            "deep-work",
            "--all-day",
            "--timed"
        ])
    );
    assert!(box_.config().calendars.is_empty());
}

#[test]
fn a_subscription_is_written_with_its_refresh_in_seconds() {
    let box_ = Sandbox::new("add-source");
    assert_eq!(
        0,
        run(&[
            "add-source",
            box_.as_str(),
            "--id",
            "work",
            "--from",
            "https://example.invalid/w.ics",
            "--refresh",
            "30"
        ])
    );
    let source = box_.config().calendar_sources.into_iter().next().expect("a subscription");
    assert_eq!(source.location, "https://example.invalid/w.ics");
    assert_eq!(source.refresh_seconds, 1_800);
}

/// Nothing is fetched while editing: a subscription is added on the machine that will use it, and
/// a network that happens to be down is not a reason to refuse to write a line in a file.
#[test]
fn a_subscription_is_not_fetched_when_it_is_added() {
    let box_ = Sandbox::new("no-fetch");
    assert_eq!(
        0,
        run(&[
            "add-source",
            box_.as_str(),
            "--id",
            "w",
            "--from",
            "https://not-a-real-host.invalid/x.ics"
        ])
    );
}

#[test]
fn removing_takes_the_schedule_out_of_the_file() {
    let box_ = Sandbox::new("remove");
    run(&[
        "add-window",
        box_.as_str(),
        "--id",
        "w",
        "--profile",
        "deep-work",
        "--from",
        "09:00",
        "--to",
        "10:00",
    ]);
    run(&["add-source", box_.as_str(), "--id", "s", "--from", "cal.ics"]);

    assert_eq!(0, run(&["remove", box_.as_str(), "w"]));
    assert_eq!(0, run(&["remove", box_.as_str(), "s"]));
    let cfg = box_.config();
    assert!(cfg.weekly.is_empty() && cfg.calendar_sources.is_empty());
}

/// Removing something that was never there is a mistake worth naming: silence would read as
/// success, and the user would go on believing a window they can still see is gone.
#[test]
fn removing_a_name_nothing_has_says_so() {
    let box_ = Sandbox::new("remove-missing");
    let before = box_.text();
    assert_eq!(1, run(&["remove", box_.as_str(), "ghost"]));
    assert_eq!(before, box_.text());
}

#[test]
fn listing_a_config_reads_it_rather_than_changing_it() {
    let box_ = Sandbox::new("list");
    run(&[
        "add-window",
        box_.as_str(),
        "--id",
        "w",
        "--profile",
        "deep-work",
        "--from",
        "09:00",
        "--to",
        "10:00",
    ]);
    let before = box_.text();
    assert_eq!(0, run(&["schedules", box_.as_str()]));
    assert_eq!(before, box_.text());
}

/// The whole point of going through the core: whatever these commands write loads again.
#[test]
fn everything_written_here_is_a_config_the_service_can_load() {
    let box_ = Sandbox::new("round-trip");
    run(&[
        "add-window",
        box_.as_str(),
        "--id",
        "w",
        "--profile",
        "deep-work",
        "--from",
        "23:00",
        "--to",
        "07:00",
        "--lock",
        "restart",
    ]);
    run(&[
        "add-calendar",
        box_.as_str(),
        "--id",
        "c",
        "--profile",
        "deep-work",
        "--calendar",
        "Work",
    ]);
    run(&["add-source", box_.as_str(), "--id", "s", "--from", "cal.ics"]);

    let cfg = box_.config();
    assert!(cfg.validate().is_ok());
    assert_eq!(0, run(&["check", box_.as_str()]));
    // And the temporary file the write went through is not left lying beside it.
    assert!(!Path::new(&format!("{}.new", box_.as_str())).exists());
}
