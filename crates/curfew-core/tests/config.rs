//! The config document: parsing, validation, round-tripping and forward migration.

use curfew_core::budget::Refill;
use curfew_core::{Action, Config, ConfigError, Rule, Target, Upserted, CONFIG_SCHEMA_VERSION};

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

/// A zero-length rolling window is the same defect as a zero budget, one level down: the rule is
/// present and looks enforced, and can never fire.
#[test]
fn a_zero_length_rolling_window_is_refused() {
    let toml = r#"
        schema_version = 1
        [[profiles]]
        id = "x"
        name = "X"
        [[profiles.rules]]
        target = { kind = "app_package", package = "com.a" }
        action = { kind = "budget", seconds = 1200, refill = { kind = "rolling", seconds = 0 } }
    "#;
    let err = Config::from_toml(toml).unwrap_err();
    assert!(err.to_string().contains("zero-second rolling window"), "got {err}");
}

/// The escape hatch is a safety rail, and an unvalidated rail is not one. `validate` never looked at
/// `[emergency]` at all, so a zero-length window made the ration unlimited: `recent` computes
/// `since = now - 0`, finds no spent pass, and reports the full quota for ever.
#[test]
fn a_zero_length_emergency_window_is_refused() {
    let toml = r#"
        schema_version = 1
        [emergency]
        passes = 3
        window_seconds = 0
    "#;
    let err = Config::from_toml(toml).unwrap_err();
    assert!(err.to_string().contains("zero-second window"), "got {err}");
}

/// The cooldown is what stops one bad evening consuming a month's allowance, so a zero one is a
/// rail removed — but only when there is a ration for it to protect.
#[test]
fn a_zero_cooldown_is_refused_when_there_are_passes_to_protect() {
    let toml = r#"
        schema_version = 1
        [emergency]
        passes = 3
        window_seconds = 604800
        cooldown_seconds = 0
    "#;
    let err = Config::from_toml(toml).unwrap_err();
    assert!(err.to_string().contains("zero-second cooldown"), "got {err}");
}

/// …and accepted when the hatch is switched off, because then there is nothing to ration and the
/// number means nothing. Turning the hatch on later is when it starts to matter.
#[test]
fn a_zero_cooldown_is_fine_while_the_hatch_is_off() {
    let toml = r#"
        schema_version = 1
        [emergency]
        passes = 0
        window_seconds = 604800
        cooldown_seconds = 0
    "#;
    assert!(
        Config::from_toml(toml).is_ok(),
        "a disabled hatch was refused for an irrelevant number"
    );
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
/// typing. The picker owns every app rule in the profile, blocked outright or rationed by a
/// budget: both are shown ticked, so both must survive a save that leaves the tick alone, and both
/// go when the tick does. Rules against anything but an app — a site, a URL — it must not touch.
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
    fn the_picker_sees_the_app_rules_and_not_the_site_ones() {
        let config = Config::from_toml(CONFIG).unwrap();
        assert_eq!(
            config.blocked_apps("deep-work"),
            vec!["com.instagram.android".to_string(), "com.reddit.frontpage".to_string()]
        );
    }

    #[test]
    fn checking_a_box_adds_a_rule_and_unchecking_removes_it() {
        let mut config = Config::from_toml(CONFIG).unwrap();
        config.set_blocked_apps("deep-work", &["com.twitter.android".to_string()]).unwrap();

        assert_eq!(config.blocked_apps("deep-work"), vec!["com.twitter.android".to_string()]);
    }

    #[test]
    /// The budget belongs to the tick. Saving the same set back — which the app does on every
    /// visit to the picker — used to rewrite the rationed app as a plain block, so a budget set
    /// on Monday was gone by Tuesday without anyone touching it.
    fn a_budget_survives_the_app_staying_ticked() {
        let mut config = Config::from_toml(CONFIG).unwrap();
        config
            .set_blocked_apps(
                "deep-work",
                &["com.instagram.android".into(), "com.reddit.frontpage".into()],
            )
            .unwrap();

        let profile = config.profile("deep-work").unwrap();
        assert!(profile.rules.iter().any(|r| matches!(
            (&r.target, &r.action),
            (Target::AppPackage { package }, Action::Budget { seconds: 600, .. })
                if package == "com.reddit.frontpage"
        )));
    }

    #[test]
    /// Unticking is the user saying the app is not blocked at all, and a ration is a way of being
    /// blocked. What it must not reach is the rest of the profile.
    fn unticking_an_app_takes_its_budget_with_it_and_leaves_the_sites_alone() {
        let mut config = Config::from_toml(CONFIG).unwrap();
        config.set_blocked_apps("deep-work", &[]).unwrap();

        let profile = config.profile("deep-work").unwrap();
        assert!(!profile.rules.iter().any(|r| matches!(r.action, Action::Budget { .. })));
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
    let toml = "schema_version = 1\n[[tokens]]\nid = \"\"\nhash = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"\n".to_string();
    assert!(matches!(Config::from_toml(&toml), Err(ConfigError::Invalid(_))));
}

#[test]
fn two_tokens_with_one_id_are_refused_rather_than_one_winning_quietly() {
    let toml = "schema_version = 1\n\
         [[tokens]]\nid = \"fridge\"\nhash = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"\n\
         [[tokens]]\nid = \"fridge\"\nhash = \"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"\n".to_string();
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
    let toml = "schema_version = 1\n[[tokens]]\nid = \"fridge\"\nhash = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"\n".to_string();
    let config = Config::from_toml(&toml).unwrap();
    let back = Config::from_toml(&config.to_toml().unwrap()).unwrap();
    assert_eq!(back.tokens, config.tokens);
}

// --- editing schedules from a settings screen ---------------------------------------------------

use curfew_core::schedule::{CalendarSchedule, CalendarSource, EventMatcher, WeeklySchedule};

fn window(id: &str, profile: &str) -> WeeklySchedule {
    WeeklySchedule {
        id: id.into(),
        profile: profile.into(),
        days: vec![0, 1, 2],
        start_minute: 9 * 60,
        end_minute: 17 * 60,
        locks: Vec::new(),
        enabled: true,
    }
}

fn rule(id: &str, profile: &str) -> CalendarSchedule {
    CalendarSchedule {
        id: id.into(),
        profile: profile.into(),
        matcher: EventMatcher { calendar: Some("Work".into()), ..EventMatcher::default() },
        pad_before_seconds: 60,
        pad_after_seconds: 0,
        locks: Vec::new(),
        enabled: true,
    }
}

#[test]
fn a_window_saved_twice_is_edited_rather_than_duplicated() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.weekly.len();
    cfg.upsert_weekly(window("evenings", "deep-work")).unwrap();
    assert_eq!(cfg.weekly.len(), before + 1);

    let mut edited = window("evenings", "deep-work");
    edited.start_minute = 20 * 60;
    cfg.upsert_weekly(edited).unwrap();
    assert_eq!(cfg.weekly.len(), before + 1, "editing a window added a second one");
    let saved = cfg.weekly.iter().find(|w| w.id == "evenings").unwrap();
    assert_eq!(saved.start_minute, 20 * 60);
}

/// Eight of these turned up on a real phone: a screen saved a fresh window on every pass, each with
/// a new id, and the plan filled with rows that said the same thing and had to be deleted one by
/// one. Two windows over the same profile, days and minutes cannot behave differently from one.
#[test]
fn a_second_window_identical_to_one_already_there_is_not_added() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_weekly(window("evenings", "deep-work")).unwrap();
    let after_first = cfg.weekly.len();

    let mut again = window("evenings", "deep-work");
    again.id = "evenings-2".into();
    cfg.upsert_weekly(again).unwrap();
    assert_eq!(cfg.weekly.len(), after_first, "an identical window was added again");

    // Same times, a different profile: a real second window, and it stays.
    let mut elsewhere = window("evenings-3", "deep-work");
    elsewhere.profile =
        cfg.profiles.iter().map(|p| p.id.clone()).find(|id| id != "deep-work").unwrap();
    cfg.upsert_weekly(elsewhere).unwrap();
    assert_eq!(cfg.weekly.len(), after_first + 1);
}

/// The refusal is only half of it. A form that reports an error and leaves the bad value in the
/// config means the next thing the user saves is refused too, for a reason they cannot see.
#[test]
fn a_window_naming_a_profile_that_does_not_exist_is_refused_and_leaves_nothing_behind() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.weekly.clone();
    let err = cfg.upsert_weekly(window("evenings", "no-such-profile")).unwrap_err();
    assert!(matches!(&err, ConfigError::Invalid(m) if m.contains("no-such-profile")), "{err:?}");
    assert_eq!(cfg.weekly, before);
    assert!(cfg.validate().is_ok(), "the config was left unsaveable");
}

/// The same, for an edit to a window that was already there: the old one has to come back.
#[test]
fn a_bad_edit_puts_the_window_back_as_it_was() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_weekly(window("evenings", "deep-work")).unwrap();
    let good = cfg.weekly.clone();

    let mut broken = window("evenings", "deep-work");
    broken.start_minute = 9_999;
    assert!(cfg.upsert_weekly(broken).is_err());
    assert_eq!(cfg.weekly, good);
}

#[test]
fn a_window_that_starts_and_ends_at_the_same_minute_is_refused() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let mut w = window("evenings", "deep-work");
    w.end_minute = w.start_minute;
    assert!(cfg.upsert_weekly(w).is_err(), "an empty-or-whole-day window was accepted");
}

#[test]
fn a_window_naming_an_eighth_day_is_refused() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let mut w = window("evenings", "deep-work");
    w.days = vec![7];
    assert!(cfg.upsert_weekly(w).is_err());
}

#[test]
fn removing_a_window_that_is_not_there_is_not_an_error() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.weekly.clone();
    cfg.remove_weekly("never-existed");
    assert_eq!(cfg.weekly, before);
}

#[test]
fn a_removed_window_is_gone() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_weekly(window("evenings", "deep-work")).unwrap();
    cfg.remove_weekly("evenings");
    assert!(cfg.weekly.iter().all(|w| w.id != "evenings"));
    assert!(cfg.validate().is_ok());
}

#[test]
fn a_calendar_rule_saved_twice_is_edited_rather_than_duplicated() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.calendars.len();
    cfg.upsert_calendar(rule("standups", "deep-work")).unwrap();
    let mut edited = rule("standups", "deep-work");
    edited.pad_after_seconds = 900;
    cfg.upsert_calendar(edited).unwrap();
    assert_eq!(cfg.calendars.len(), before + 1);
    assert_eq!(cfg.calendars.iter().find(|c| c.id == "standups").unwrap().pad_after_seconds, 900);
}

#[test]
fn a_calendar_rule_naming_a_profile_that_does_not_exist_is_refused_and_leaves_nothing_behind() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.calendars.clone();
    assert!(cfg.upsert_calendar(rule("standups", "no-such-profile")).is_err());
    assert_eq!(cfg.calendars, before);
}

#[test]
fn a_removed_calendar_rule_is_gone() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_calendar(rule("standups", "deep-work")).unwrap();
    cfg.remove_calendar("standups");
    assert!(cfg.calendars.iter().all(|c| c.id != "standups"));
}

/// Whatever the editor writes has to survive being saved to disk and read back, or the schedule
/// works until the app is next opened.
#[test]
fn edited_schedules_survive_a_round_trip_through_the_document() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_weekly(window("evenings", "deep-work")).unwrap();
    cfg.upsert_calendar(rule("standups", "deep-work")).unwrap();
    let back = Config::from_toml(&cfg.to_toml().unwrap()).unwrap();
    assert_eq!(back.weekly, cfg.weekly);
    assert_eq!(back.calendars, cfg.calendars);
}

/// Two windows with one id would make every edit to either land on whichever parsed first.
#[test]
fn two_windows_with_one_id_are_refused_at_load() {
    let toml = "schema_version = 1\n\
        [[profiles]]\nid = \"deep-work\"\nname = \"Deep work\"\n\
        [[weekly]]\nid = \"w\"\nprofile = \"deep-work\"\nstart_minute = 60\nend_minute = 120\n\
        [[weekly]]\nid = \"w\"\nprofile = \"deep-work\"\nstart_minute = 180\nend_minute = 240\n";
    let err = Config::from_toml(toml).unwrap_err();
    assert!(matches!(&err, ConfigError::Invalid(m) if m.contains("duplicate")), "{err:?}");
}

// --- calendar subscriptions ---------------------------------------------------------------------
//
// The desktop has no system calendar to read, so a subscription is the whole of the calendar
// feature there: a rule with nothing behind it never fires, and nothing else says so.

fn source(id: &str, location: &str) -> CalendarSource {
    CalendarSource { id: id.into(), location: location.into(), refresh_seconds: 3_600 }
}

#[test]
fn a_subscription_can_be_added_and_replaced_by_its_id() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_source(source("work", "https://example.invalid/a.ics")).unwrap();
    cfg.upsert_source(source("work", "https://example.invalid/b.ics")).unwrap();

    let mine: Vec<_> = cfg.calendar_sources.iter().filter(|s| s.id == "work").collect();
    assert_eq!(1, mine.len());
    assert_eq!("https://example.invalid/b.ics", mine[0].location);
}

#[test]
fn a_subscription_with_nothing_to_read_is_refused() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.calendar_sources.clone();
    assert!(cfg.upsert_source(source("work", "  ")).is_err());
    assert_eq!(before, cfg.calendar_sources);
    assert!(cfg.validate().is_ok());
}

/// Zero seconds means re-fetching someone's calendar server on every pass, which is every two
/// seconds. Refused rather than clamped, because a value that quietly becomes another value is a
/// setting the user cannot reason about.
#[test]
fn a_subscription_that_refreshes_constantly_is_refused() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let mut s = source("work", "cal.ics");
    s.refresh_seconds = 0;
    assert!(cfg.upsert_source(s).is_err());
}

/// Two subscriptions sharing an id share a cached copy, so an outage on one would be served the
/// other's meetings.
#[test]
fn two_subscriptions_with_one_id_are_refused_at_load() {
    let toml = "schema_version = 1\n\
        [[calendar_sources]]\nid = \"work\"\nlocation = \"a.ics\"\n\
        [[calendar_sources]]\nid = \"work\"\nlocation = \"b.ics\"\n";
    let err = Config::from_toml(toml).unwrap_err();
    assert!(matches!(&err, ConfigError::Invalid(m) if m.contains("duplicate")), "{err:?}");
}

#[test]
fn unsubscribing_leaves_the_rest_of_the_config_alone() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_source(source("work", "cal.ics")).unwrap();
    let calendars = cfg.calendars.clone();

    cfg.remove_source("work");
    cfg.remove_source("work");
    assert!(cfg.calendar_sources.iter().all(|s| s.id != "work"));
    // The rules that were reading it stay: they simply match nothing until a calendar comes back.
    assert_eq!(calendars, cfg.calendars);
    assert!(cfg.validate().is_ok());
}

// --- profiles -----------------------------------------------------------------------------------

/// A fresh install has no profiles, and every screen that lists them says "add one first". Creating
/// one has to be possible from the app, or the only way in is a hand-written file.
#[test]
fn a_profile_can_be_created_and_renamed_without_losing_its_rules() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let rules = cfg.profile("deep-work").unwrap().rules.clone();

    cfg.upsert_profile("deep-work", "Focus", "heads down").unwrap();
    let p = cfg.profile("deep-work").unwrap();
    assert_eq!(p.name, "Focus");
    assert_eq!(p.description, "heads down");
    // Renaming must not empty the app list: the picker owns the rules, not the name field.
    assert_eq!(p.rules, rules);

    cfg.upsert_profile("reading", "Reading", "").unwrap();
    assert!(cfg.profile("reading").unwrap().rules.is_empty());
    assert!(cfg.validate().is_ok());
}

/// A chip with nothing written on it is unusable in every UI that lists profiles.
#[test]
fn a_profile_with_no_name_is_refused_and_changes_nothing() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.profiles.clone();
    assert!(cfg.upsert_profile("reading", "   ", "").is_err());
    assert_eq!(before, cfg.profiles);
    assert!(cfg.validate().is_ok());
}

/// Deleting a profile a window still names would write a config that will not load, and the next
/// launch would discover that with nothing blocking.
#[test]
fn a_profile_a_schedule_still_names_cannot_be_deleted() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let used_by = cfg.weekly.iter().find(|w| w.profile == "deep-work").map(|w| w.id.clone());
    let used_by = used_by.expect("the golden config points a window at deep-work");

    let err = cfg.remove_profile("deep-work").unwrap_err();
    let ConfigError::Invalid(message) = &err else { panic!("{err:?}") };
    // The schedules are named, because "remove it first" is useless without saying which.
    assert!(message.contains(&used_by), "{message}");
    assert!(cfg.profile("deep-work").is_some());
}

#[test]
fn a_profile_nothing_points_at_can_be_deleted() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_profile("reading", "Reading", "").unwrap();
    cfg.remove_profile("reading").unwrap();
    assert!(cfg.profile("reading").is_none());
    // And deleting one that was never there is not an error worth raising.
    cfg.remove_profile("reading").unwrap();
    assert!(cfg.validate().is_ok());
}

/// Two rules on one target would mean two budgets and two entries in every list, so the second
/// statement about a target replaces the first.
#[test]
fn a_rule_replaces_the_one_pointing_at_the_same_thing() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.profile("deep-work").unwrap().rules.len();
    let rule = |action| Rule {
        target: Target::Domain { domain: "example.invalid".into() },
        action,
        platforms: Vec::new(),
    };

    cfg.upsert_rule("deep-work", rule(Action::Block)).unwrap();
    let after_first = cfg.profile("deep-work").unwrap().rules.len();
    cfg.upsert_rule("deep-work", rule(Action::Delay { seconds: 30 })).unwrap();

    let rules = &cfg.profile("deep-work").unwrap().rules;
    assert_eq!(after_first, rules.len(), "the second statement replaces the first");
    assert!(after_first > before);
    let stored = rules.iter().find(|r| r.target.key() == "domain:example.invalid").unwrap();
    assert_eq!(Action::Delay { seconds: 30 }, stored.action);
}

/// A rule for Windows only and a rule for everywhere are different statements about one target.
#[test]
fn a_rule_for_one_platform_does_not_replace_the_one_for_all_of_them() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let target = Target::WindowsExe { exe: "steam.exe".into() };
    cfg.upsert_rule(
        "deep-work",
        Rule { target: target.clone(), action: Action::Block, platforms: Vec::new() },
    )
    .unwrap();
    cfg.upsert_rule(
        "deep-work",
        Rule {
            target: target.clone(),
            action: Action::Block,
            platforms: vec![curfew_core::Platform::Windows],
        },
    )
    .unwrap();

    let matching = cfg
        .profile("deep-work")
        .unwrap()
        .rules
        .iter()
        .filter(|r| r.target.key() == target.key())
        .count();
    assert_eq!(2, matching);

    // And removing takes both: "stop blocking this" means all of it.
    assert_eq!(2, cfg.remove_rule("deep-work", &target));
    assert!(cfg.profile("deep-work").unwrap().rules.iter().all(|r| r.target.key() != target.key()));
}

#[test]
fn a_rule_for_a_profile_that_does_not_exist_is_refused() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let before = cfg.profiles.clone();
    assert!(cfg
        .upsert_rule(
            "nope",
            Rule {
                target: Target::Domain { domain: "x.com".into() },
                action: Action::Block,
                platforms: Vec::new(),
            },
        )
        .is_err());
    assert_eq!(before, cfg.profiles);
    assert_eq!(0, cfg.remove_rule("nope", &Target::Domain { domain: "x.com".into() }));
}

// --- what an upsert actually did (P2-10) ---------------------------------------------------------
//
// `upsert_weekly` deliberately declines to store a duplicate, and before this a caller could not tell
// that apart from a real insert — so `curfew add-window` printed "Added" for a window the core had
// just discarded, inferring the outcome from whether the *id* was new. These pin the three cases so
// the CLI's message has something true to report.

#[test]
fn an_upsert_says_when_it_added_something() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    assert_eq!(cfg.upsert_weekly(window("evenings", "deep-work")).unwrap(), Upserted::Added);
}

#[test]
fn an_upsert_says_when_it_replaced_a_row() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_weekly(window("evenings", "deep-work")).unwrap();

    let mut edited = window("evenings", "deep-work");
    edited.end_minute = 22 * 60;
    assert_eq!(cfg.upsert_weekly(edited).unwrap(), Upserted::Replaced);
}

/// The case the CLI got wrong: a different id, the same minutes, and nothing stored.
#[test]
fn an_upsert_says_when_the_plan_already_covers_it() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    cfg.upsert_weekly(window("evenings", "deep-work")).unwrap();
    let before = cfg.weekly.len();

    let mut twin = window("evenings", "deep-work");
    twin.id = "evenings-by-another-name".into();
    assert_eq!(cfg.upsert_weekly(twin).unwrap(), Upserted::AlreadyPresent);
    assert_eq!(cfg.weekly.len(), before, "an identical window was stored after all");
}

/// A rejected window reports the error rather than an outcome, so nothing can print a success for it.
#[test]
fn a_refused_upsert_reports_no_outcome() {
    let mut cfg = Config::from_toml(GOLDEN).unwrap();
    let mut broken = window("evenings", "no-such-profile");
    broken.profile = "no-such-profile".into();
    assert!(cfg.upsert_weekly(broken).is_err());
}
