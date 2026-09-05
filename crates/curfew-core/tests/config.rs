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

/// The app picker's edits, which are the only writes to a config that do not come from a person
/// typing. The rules are: what the picker owns it may replace, and what it does not own it must
/// not touch — a hand-written budget is the user saying something more specific.
mod picker {
    use curfew_core::config::{Action, Config};
    use curfew_core::target::Target;

    const CONFIG: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "app_package", package = "com.instagram.android" }
action = { kind = "block" }

[[profiles.rules]]
target = { kind = "app_package", package = "com.reddit.frontpage" }
action = { kind = "budget", seconds = 600 }

[[profiles.rules]]
target = { kind = "domain", domain = "news.example" }
action = { kind = "block" }
"#;

    #[test]
    fn the_picker_sees_only_the_rules_it_owns() {
        let config = Config::from_toml(CONFIG).unwrap();
        assert_eq!(config.blocked_apps("deep-work"), vec!["com.instagram.android".to_string()]);
    }

    #[test]
    fn checking_a_box_adds_a_rule_and_unchecking_removes_it() {
        let mut config = Config::from_toml(CONFIG).unwrap();
        config.set_blocked_apps("deep-work", &["com.twitter.android".to_string()]).unwrap();

        assert_eq!(config.blocked_apps("deep-work"), vec!["com.twitter.android".to_string()]);
    }

    #[test]
    fn a_budget_on_an_app_survives_the_picker() {
        let mut config = Config::from_toml(CONFIG).unwrap();
        config.set_blocked_apps("deep-work", &[]).unwrap();

        let profile = config.profile("deep-work").unwrap();
        assert!(profile.rules.iter().any(|r| matches!(r.action, Action::Budget { .. })));
        assert!(profile.rules.iter().any(|r| matches!(&r.target, Target::Domain { .. })));
    }

    #[test]
    fn the_written_file_does_not_churn_when_the_same_set_comes_back_in_another_order() {
        let mut a = Config::from_toml(CONFIG).unwrap();
        let mut b = Config::from_toml(CONFIG).unwrap();
        a.set_blocked_apps("deep-work", &["b.app".into(), "a.app".into()]).unwrap();
        b.set_blocked_apps("deep-work", &["a.app".into(), "b.app".into(), "a.app".into()]).unwrap();
        assert_eq!(a.to_toml().unwrap(), b.to_toml().unwrap());
    }

    #[test]
    fn the_result_is_a_config_that_still_loads() {
        let mut config = Config::from_toml(CONFIG).unwrap();
        config.set_blocked_apps("deep-work", &["com.twitter.android".into()]).unwrap();
        let round_tripped = Config::from_toml(&config.to_toml().unwrap()).unwrap();
        assert_eq!(
            round_tripped.blocked_apps("deep-work"),
            vec!["com.twitter.android".to_string()]
        );
    }

    #[test]
    fn a_profile_that_does_not_exist_is_an_error_rather_than_a_silent_no_op() {
        let mut config = Config::from_toml(CONFIG).unwrap();
        assert!(config.set_blocked_apps("nope", &["a.app".into()]).is_err());
    }

    #[test]
    fn an_empty_package_is_refused() {
        let mut config = Config::from_toml(CONFIG).unwrap();
        assert!(config.set_blocked_apps("deep-work", &["  ".into()]).is_err());
    }
}

// --- physical tags ---
//
// The config carries fingerprints, never payloads: it is the document a user backs up, diffs and
// syncs, and a tag written into it in full would be a tag anyone who reads the file already has.

/// The commonest mistake is pasting the payload in, so the refusal has to be specific about it.
#[test]
fn a_token_hash_that_is_the_tag_itself_is_refused() {
    let toml = "schema_version = 1\n[[tokens]]\nid = \"fridge\"\nhash = \"curfew-tag-abcdefgh\"\n";
    let err = Config::from_toml(toml).unwrap_err();
    assert!(
        matches!(&err, ConfigError::Invalid(m) if m.contains("64-character hex")),
        "a payload was accepted where a fingerprint belongs: {err:?}"
    );
}

#[test]
fn a_token_with_no_id_is_refused_because_no_lock_could_ever_name_it() {
    let toml = format!("schema_version = 1\n[[tokens]]\nid = \"\"\nhash = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"\n");
    assert!(matches!(Config::from_toml(&toml), Err(ConfigError::Invalid(_))));
}

#[test]
fn two_tokens_with_one_id_are_refused_rather_than_one_winning_quietly() {
    let toml = format!(
        "schema_version = 1\n\
         [[tokens]]\nid = \"fridge\"\nhash = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"\n\
         [[tokens]]\nid = \"fridge\"\nhash = \"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"\n"
    );
    let err = Config::from_toml(&toml).unwrap_err();
    assert!(
        matches!(&err, ConfigError::Invalid(m) if m.contains("duplicate token id")),
        "one of two tags was dropped without a word: {err:?}"
    );
}

#[test]
fn a_fingerprint_typed_in_capitals_is_still_a_fingerprint() {
    let toml = format!(
        "schema_version = 1\n[[tokens]]\nid = \"fridge\"\nhash = \"{}\"\n",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".to_uppercase()
    );
    assert!(Config::from_toml(&toml).is_ok(), "a hash in capitals was refused");
}

/// Almost every config has no tags, and a token lock in one of them simply cannot be opened by a
/// scan. That is the safe direction to fail in, so it must not be an error to have none.
#[test]
fn a_config_with_no_tokens_is_ordinary() {
    assert!(Config::from_toml("schema_version = 1").unwrap().tokens.is_empty());
}

#[test]
fn tokens_survive_a_round_trip_through_the_document() {
    let toml = format!("schema_version = 1\n[[tokens]]\nid = \"fridge\"\nhash = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"\n");
    let config = Config::from_toml(&toml).unwrap();
    let back = Config::from_toml(&config.to_toml().unwrap()).unwrap();
    assert_eq!(back.tokens, config.tokens);
}
