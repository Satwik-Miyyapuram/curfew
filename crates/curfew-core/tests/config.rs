//! The config document: parsing, validation, round-tripping and forward migration.

use curfew_core::budget::Refill;
use curfew_core::{Action, Config, ConfigError, Target, CONFIG_SCHEMA_VERSION};

const GOLDEN: &str = include_str!("golden/example.toml");
const GOLDEN_V0: &str = include_str!("golden/v0_example.toml");

#[test]
fn the_golden_config_parses() {
    let cfg = Config::from_toml(GOLDEN).expect("golden config must parse");
    assert_eq!(cfg.schema_version, CONFIG_SCHEMA_VERSION);
    assert_eq!(cfg.timezone, "Europe/London");
    assert_eq!(cfg.profiles.len(), 2);
    assert_eq!(cfg.profile("deep-work").unwrap().rules.len(), 10);
}

#[test]
fn the_golden_config_carries_its_schedules() {
    let cfg = Config::from_toml(GOLDEN).unwrap();
    assert_eq!(cfg.weekly.len(), 2);
    assert_eq!(cfg.calendars.len(), 1);
    // The overnight window is the crossing-midnight shape, which the schedule layer has to notice.
    let overnight = cfg.weekly.iter().find(|w| w.id == "overnight").expect("overnight window");
    assert!(overnight.end_minute < overnight.start_minute);
    assert_eq!(cfg.calendars[0].pad_before_seconds, 300);
    assert_eq!(cfg.calendars[0].matcher.calendar.as_deref(), Some("Work"));
}

#[test]
fn a_config_with_no_schedules_at_all_is_valid() {
    let cfg = Config::from_toml("schema_version = 1").unwrap();
    assert!(cfg.weekly.is_empty() && cfg.calendars.is_empty());
}

#[test]
fn the_golden_config_round_trips() {
    let cfg = Config::from_toml(GOLDEN).unwrap();
    let again = Config::from_toml(&cfg.to_toml().unwrap()).unwrap();
    assert_eq!(cfg, again, "serializing and re-reading must not change meaning");
}

/// A config from a newer Curfew is refused outright. Loading it partially would mean writing back a
/// file with the user's rules silently dropped -- the one data-loss bug that would destroy trust.
#[test]
fn a_config_from_the_future_is_refused() {
    let err = Config::from_toml("schema_version = 9999").unwrap_err();
    assert!(
        matches!(err, ConfigError::FromTheFuture { found: 9999, .. }),
        "expected a from-the-future error, got {err:?}"
    );
}

#[test]
fn a_v0_config_migrates_forward_without_changing_meaning() {
    let cfg = Config::from_toml(GOLDEN_V0).expect("v0 config must still load");
    assert_eq!(cfg.schema_version, CONFIG_SCHEMA_VERSION);

    let rules = &cfg.profile("deep-work").unwrap().rules;

    // A v0 substring title match is exactly the glob `*text*`.
    assert_eq!(
        rules[1].target,
        Target::WindowTitle { pattern: "*- YouTube*".into() },
        "the substring target must become the equivalent glob"
    );

    // v0 budgets had no window; they migrate to the default daily refill.
    assert_eq!(rules[2].action, Action::Budget { seconds: 1200, refill: Refill::default() });
}

#[test]
fn a_missing_schema_version_is_treated_as_the_oldest_one() {
    let cfg = Config::from_toml("[[profiles]]\nid = \"a\"\nname = \"A\"").unwrap();
    assert_eq!(cfg.schema_version, CONFIG_SCHEMA_VERSION);
    assert_eq!(cfg.timezone, "UTC", "an absent timezone defaults, it does not fail");
}

#[test]
fn an_unknown_timezone_is_refused_at_load() {
    let err = Config::from_toml("schema_version = 1\ntimezone = \"Mars/Olympus\"").unwrap_err();
    assert!(matches!(err, ConfigError::UnknownTimezone(_)), "got {err:?}");
}

#[test]
fn duplicate_profile_ids_are_refused() {
    let toml = r#"
        schema_version = 1
        [[profiles]]
        id = "x"
        name = "One"
        [[profiles]]
        id = "x"
        name = "Two"
    "#;
    assert!(matches!(Config::from_toml(toml), Err(ConfigError::Invalid(_))));
}

/// A zero budget behaves as a block but reads as an oversight, and the difference matters when the
/// user is looking at their own config trying to work out why something is unavailable.
#[test]
fn a_zero_budget_is_refused_because_it_is_a_block_in_disguise() {
    let toml = r#"
        schema_version = 1
        [[profiles]]
        id = "x"
        name = "X"
        [[profiles.rules]]
        target = { kind = "app_package", package = "com.a" }
        action = { kind = "budget", seconds = 0 }
    "#;
    let err = Config::from_toml(toml).unwrap_err();
    assert!(err.to_string().contains("zero-second budget"), "got {err}");
}

#[test]
fn a_zero_launch_limit_is_refused_too() {
    let toml = r#"
        schema_version = 1
        [[profiles]]
        id = "x"
        name = "X"
        [[profiles.rules]]
        target = { kind = "app_package", package = "com.a" }
        action = { kind = "launch_limit", count = 0 }
    "#;
    assert!(Config::from_toml(toml).unwrap_err().to_string().contains("zero launch limit"));
}

#[test]
fn an_empty_profile_id_is_refused() {
    let toml = "schema_version = 1\n[[profiles]]\nid = \"  \"\nname = \"X\"";
    assert!(matches!(Config::from_toml(toml), Err(ConfigError::Invalid(_))));
}

#[test]
fn malformed_toml_reports_a_parse_error_rather_than_panicking() {
    assert!(matches!(Config::from_toml("this is not toml ["), Err(ConfigError::Parse(_))));
}

#[test]
fn an_empty_config_is_valid_and_blocks_nothing() {
    let cfg = Config::from_toml("schema_version = 1").unwrap();
    assert!(cfg.profiles.is_empty());
}
