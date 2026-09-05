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

    /// A config with no profiles at all: what a fresh install has, and the state in which every
    /// other command has nothing to point at.
    fn empty(name: &str) -> Self {
        let path =
            std::env::temp_dir().join(format!("curfew-cli-{name}-{}.toml", std::process::id()));
        std::fs::write(&path, "schema_version = 1\ntimezone = \"Europe/London\"\n").unwrap();
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

// --- profiles -----------------------------------------------------------------------------------

/// The command a fresh install starts with: every other one asks for a profile that has to exist.
#[test]
fn a_profile_can_be_created_from_nothing_and_then_scheduled() {
    let box_ = Sandbox::empty("new-profile");
    assert_eq!(0, run(&["add-profile", box_.as_str(), "--id", "reading", "--name", "Reading"]));

    let profile = box_.config().profiles.into_iter().next().expect("a profile");
    assert_eq!(profile.id, "reading");
    assert_eq!(profile.name, "Reading");
    assert!(profile.rules.is_empty(), "a new profile blocks nothing until the picker is used");

    // And the window that could not be written before now can be.
    assert_eq!(
        0,
        run(&[
            "add-window",
            box_.as_str(),
            "--id",
            "w",
            "--profile",
            "reading",
            "--from",
            "21:00",
            "--to",
            "22:00"
        ])
    );
}

/// Typing the id twice buys nothing, so the name follows the id when it is left out.
#[test]
fn a_profile_with_no_name_given_is_named_after_its_id() {
    let box_ = Sandbox::empty("default-name");
    assert_eq!(0, run(&["add-profile", box_.as_str(), "--id", "reading"]));
    assert_eq!("reading", box_.config().profiles[0].name);
}

/// Renaming is the same command, and it must not empty the list of apps the profile blocks.
#[test]
fn renaming_a_profile_keeps_what_it_blocks() {
    let box_ = Sandbox::new("rename");
    let before = box_.config().profiles[0].rules.clone();
    assert_eq!(0, run(&["add-profile", box_.as_str(), "--id", "deep-work", "--name", "Focus"]));
    let profiles = box_.config().profiles;
    assert_eq!(1, profiles.len());
    assert_eq!("Focus", profiles[0].name);
    assert_eq!(before, profiles[0].rules);
}

/// Deleting a profile a window still names would write a file the service refuses to load, so it is
/// refused here instead — while the config on disk is still the one that works.
#[test]
fn a_profile_a_window_still_names_cannot_be_removed() {
    let box_ = Sandbox::new("in-use");
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
        "22:00",
    ]);
    let before = box_.text();

    assert_eq!(1, run(&["remove", box_.as_str(), "deep-work"]));
    assert_eq!(before, box_.text());

    // Removing the window first is what makes it possible.
    assert_eq!(0, run(&["remove", box_.as_str(), "evenings"]));
    assert_eq!(0, run(&["remove", box_.as_str(), "deep-work"]));
    assert!(box_.config().profiles.is_empty());
}

#[test]
fn a_profile_flag_that_is_not_a_flag_is_refused() {
    let box_ = Sandbox::empty("bad-flag");
    let before = box_.text();
    assert_eq!(1, run(&["add-profile", box_.as_str(), "--id", "r", "--colour", "red"]));
    assert_eq!(before, box_.text());
}

// --- what a profile blocks ----------------------------------------------------------------------

/// The other half of a setup that needed a text editor: a profile that blocks nothing is a
/// schedule that does nothing, and picking apps by hand was a phone-only screen.
#[test]
fn a_site_can_be_blocked_and_unblocked_from_the_command_line() {
    let box_ = Sandbox::new("block-site");
    assert_eq!(0, run(&["block", box_.as_str(), "--profile", "deep-work", "--site", "reddit.com"]));

    let rules = box_.config().profiles[0].rules.clone();
    assert_eq!(1, rules.len());
    assert_eq!(curfew_core::Target::Domain { domain: "reddit.com".into() }, rules[0].target);
    assert_eq!(curfew_core::Action::Block, rules[0].action);
    assert!(rules[0].platforms.is_empty(), "no --on means every platform");

    assert_eq!(
        0,
        run(&["unblock", box_.as_str(), "--profile", "deep-work", "--site", "reddit.com"])
    );
    assert!(box_.config().profiles[0].rules.is_empty());
}

/// Blocking the same thing twice is one rule, not two: the second is the user restating the first,
/// and two rules on one target would mean two budgets and two entries in every listing.
#[test]
fn blocking_the_same_thing_twice_leaves_one_rule() {
    let box_ = Sandbox::new("block-twice");
    let args = ["block", box_.as_str(), "--profile", "deep-work", "--exe", "steam.exe"];
    assert_eq!(0, run(&args));
    assert_eq!(0, run(&args));
    assert_eq!(1, box_.config().profiles[0].rules.len());
}

/// A rule for one platform and a rule for every platform are different statements, and a user who
/// wrote both meant both.
#[test]
fn a_platform_specific_rule_sits_beside_the_one_for_everywhere() {
    let box_ = Sandbox::new("block-platform");
    run(&["block", box_.as_str(), "--profile", "deep-work", "--site", "x.com"]);
    assert_eq!(
        0,
        run(&[
            "block",
            box_.as_str(),
            "--profile",
            "deep-work",
            "--site",
            "x.com",
            "--on",
            "windows"
        ])
    );
    let rules = box_.config().profiles[0].rules.clone();
    assert_eq!(2, rules.len());
    assert_eq!(vec![curfew_core::Platform::Windows], rules[1].platforms);

    // And unblocking takes both, because "stop blocking this" with one left standing is not what
    // anybody means.
    assert_eq!(0, run(&["unblock", box_.as_str(), "--profile", "deep-work", "--site", "x.com"]));
    assert!(box_.config().profiles[0].rules.is_empty());
}

#[test]
fn a_block_naming_two_things_at_once_is_refused() {
    let box_ = Sandbox::new("two-targets");
    let before = box_.text();
    assert_eq!(
        1,
        run(&[
            "block",
            box_.as_str(),
            "--profile",
            "deep-work",
            "--site",
            "x.com",
            "--exe",
            "steam.exe"
        ])
    );
    assert_eq!(before, box_.text());
}

#[test]
fn a_block_naming_no_thing_at_all_is_refused() {
    let box_ = Sandbox::new("no-target");
    assert_eq!(1, run(&["block", box_.as_str(), "--profile", "deep-work"]));
    assert!(box_.config().profiles[0].rules.is_empty());
}

#[test]
fn blocking_into_a_profile_that_does_not_exist_changes_nothing() {
    let box_ = Sandbox::new("block-no-profile");
    let before = box_.text();
    assert_eq!(1, run(&["block", box_.as_str(), "--profile", "nope", "--site", "x.com"]));
    assert_eq!(before, box_.text());
}

/// Unblocking something that was never blocked is worth saying out loud: silence reads as success,
/// and the user walks away believing a site they can still open is blocked.
#[test]
fn unblocking_something_that_was_never_blocked_says_so() {
    let box_ = Sandbox::new("unblock-missing");
    let before = box_.text();
    assert_eq!(1, run(&["unblock", box_.as_str(), "--profile", "deep-work", "--site", "x.com"]));
    assert_eq!(before, box_.text());
}

#[test]
fn an_unknown_platform_is_refused_before_anything_is_written() {
    let box_ = Sandbox::new("bad-platform");
    let before = box_.text();
    assert_eq!(
        1,
        run(&[
            "block",
            box_.as_str(),
            "--profile",
            "deep-work",
            "--site",
            "x.com",
            "--on",
            "linux"
        ])
    );
    assert_eq!(before, box_.text());
}

#[test]
fn listing_what_a_profile_blocks_reads_rather_than_writes() {
    let box_ = Sandbox::new("blocks-list");
    run(&["block", box_.as_str(), "--profile", "deep-work", "--word", "gambling"]);
    let before = box_.text();
    assert_eq!(0, run(&["blocks", box_.as_str()]));
    assert_eq!(0, run(&["blocks", box_.as_str(), "--profile", "deep-work"]));
    assert_eq!(1, run(&["blocks", box_.as_str(), "--profile", "nope"]));
    assert_eq!(before, box_.text());
}
