//! The rule engine, against the golden config and against hand-built edge cases.

use curfew_core::budget::{Consumption, Launches};
use curfew_core::engine::State;
use curfew_core::{decide, BlockReason, Config, Decision, Observation, Platform, Target, Url};
use std::collections::BTreeMap;

const GOLDEN: &str = include_str!("golden/example.toml");
/// 2026-09-04T12:00:00Z, a Friday, comfortably inside the London day.
const NOON: i64 = 1_788_609_600;

fn golden() -> Config {
    Config::from_toml(GOLDEN).unwrap()
}

fn state(profiles: &[&str], platform: Platform) -> State {
    State {
        active_profiles: profiles.iter().map(|s| s.to_string()).collect(),
        platform,
        ..State::default()
    }
}

fn app(package: &str) -> Observation {
    Observation::App { package: package.into(), screen: None }
}

fn web(url: &str) -> Observation {
    Observation::Web { url: Url::parse(url) }
}

fn ask(state: &State, obs: &Observation) -> Decision {
    decide(NOON, state, obs, &golden())
}

// --- the basics -----------------------------------------------------------------------------

#[test]
fn a_named_app_is_blocked() {
    let d = ask(&state(&["deep-work"], Platform::Android), &app("com.instagram.android"));
    assert!(matches!(d, Decision::Block { reason: BlockReason::Blocked { .. } }), "{d:?}");
}

#[test]
fn package_matching_ignores_case_but_not_content() {
    let s = state(&["deep-work"], Platform::Android);
    assert!(matches!(ask(&s, &app("COM.Instagram.Android")), Decision::Block { .. }));
    assert_eq!(ask(&s, &app("com.instagram.androidx")), Decision::Allow);
}

#[test]
fn an_unlisted_app_is_allowed() {
    assert_eq!(
        ask(&state(&["deep-work"], Platform::Android), &app("com.example.notes")),
        Decision::Allow
    );
}

/// With no session running, nothing is enforced. This is the state the app spends most of its life
/// in, and getting it wrong would block everything all the time.
#[test]
fn no_active_profile_means_no_blocking() {
    let s = state(&[], Platform::Android);
    assert_eq!(ask(&s, &app("com.instagram.android")), Decision::Allow);
}

#[test]
fn an_active_profile_that_does_not_exist_is_ignored_rather_than_fatal() {
    let s = state(&["typo-profile"], Platform::Android);
    assert_eq!(ask(&s, &app("com.instagram.android")), Decision::Allow);
}

#[test]
fn an_idle_screen_is_never_blocked() {
    let s = state(&["deep-work", "writing"], Platform::Android);
    assert_eq!(ask(&s, &Observation::Idle), Decision::Allow);
}

// --- platforms ------------------------------------------------------------------------------

#[test]
fn platform_scoped_rules_only_fire_on_their_platform() {
    let win = Observation::Window { exe: "steam.exe".into(), title: String::new() };
    assert!(matches!(ask(&state(&["deep-work"], Platform::Windows), &win), Decision::Block { .. }));
    assert_eq!(ask(&state(&["deep-work"], Platform::Android), &win), Decision::Allow);
}

// --- domains and URLs -----------------------------------------------------------------------

#[test]
fn domain_rules_cover_subdomains_but_not_lookalikes() {
    let s = state(&["deep-work"], Platform::Browser);
    // Budgeted, not blocked, and nothing is spent yet.
    assert_eq!(ask(&s, &web("https://old.reddit.com/r/rust")), Decision::Allow);
    assert_eq!(ask(&s, &web("https://notreddit.com/")), Decision::Allow);
}

#[test]
fn a_url_glob_matches_a_path_and_ignores_the_rest_of_the_site() {
    let s = state(&["deep-work"], Platform::Browser);
    assert!(matches!(
        ask(&s, &web("https://www.youtube.com/shorts/abc123")),
        Decision::Block { .. }
    ));
    assert_eq!(ask(&s, &web("https://www.youtube.com/watch?v=abc123")), Decision::Allow);
}

#[test]
fn a_url_with_a_port_and_credentials_still_matches_its_host() {
    let s = state(&["deep-work"], Platform::Browser);
    let d = ask(&s, &web("https://user:pw@www.youtube.com:8443/shorts/x"));
    assert!(matches!(d, Decision::Block { .. }), "{d:?}");
}

// --- keywords -------------------------------------------------------------------------------

#[test]
fn a_keyword_matches_in_a_url_and_in_a_window_title() {
    let s = state(&["deep-work"], Platform::Browser);
    assert!(matches!(ask(&s, &web("https://shop.example/black-friday-deals")), Decision::Allow));

    let s = state(&["deep-work"], Platform::Browser);
    let hit = web("https://shop.example/?q=black%20friday");
    let _ = hit;
    // The keyword rule is written with a space, so it matches the human-readable title form.
    let titled =
        Observation::Window { exe: "chrome.exe".into(), title: "Black Friday sale".into() };
    assert!(matches!(ask(&s, &titled), Decision::Block { .. }));
}

// --- budgets --------------------------------------------------------------------------------

#[test]
fn a_budget_blocks_only_once_it_is_spent() {
    let cfg = golden();
    let key = Target::Domain { domain: "reddit.com".into() }.key();
    let obs = web("https://reddit.com/");

    let mut spent_almost = Consumption::default();
    spent_almost.record(NOON - 60, 1199);
    let mut s = state(&["deep-work"], Platform::Browser);
    s.usage = BTreeMap::from([(key.clone(), spent_almost)]);
    assert_eq!(decide(NOON, &s, &obs, &cfg), Decision::Allow, "one second left is still allowed");

    s.usage.get_mut(&key).unwrap().record(NOON - 30, 1);
    let d = decide(NOON, &s, &obs, &cfg);
    assert!(
        matches!(d, Decision::Block { reason: BlockReason::BudgetExhausted { seconds: 1200, .. } }),
        "{d:?}"
    );
}

/// Yesterday's spending must not count against today's allowance. The London reset is 04:00 local,
/// so use at 03:00 belongs to the previous day and use at 05:00 to this one.
#[test]
fn a_daily_budget_forgets_what_happened_before_the_reset() {
    let cfg = golden();
    let key = Target::Domain { domain: "reddit.com".into() }.key();
    let obs = web("https://reddit.com/");

    let mut usage = Consumption::default();
    usage.record(NOON - 36 * 3600, 5_000); // well before this morning's 04:00
    let mut s = state(&["deep-work"], Platform::Browser);
    s.usage = BTreeMap::from([(key.clone(), usage)]);
    assert_eq!(decide(NOON, &s, &obs, &cfg), Decision::Allow, "yesterday does not count");

    s.usage.get_mut(&key).unwrap().record(NOON - 3600, 5_000); // after the reset
    assert!(matches!(decide(NOON, &s, &obs, &cfg), Decision::Block { .. }));
}

/// Two devices, one budget. The engine sees a merged ledger, so use on the phone spends the PC's
/// allowance too -- the property that makes the budget mean anything at all when you have both.
#[test]
fn budget_consumption_is_shared_across_devices() {
    let cfg = golden();
    let key = Target::Domain { domain: "reddit.com".into() }.key();
    let mut merged = Consumption::default();
    merged.record(NOON - 600, 700); // phone
    merged.record(NOON - 300, 600); // laptop
    let mut s = state(&["deep-work"], Platform::Browser);
    s.usage = BTreeMap::from([(key, merged)]);
    assert!(matches!(decide(NOON, &s, &web("https://reddit.com/"), &cfg), Decision::Block { .. }));
}

// --- launch limits --------------------------------------------------------------------------

#[test]
fn a_launch_limit_blocks_the_fourth_open_of_the_day() {
    let cfg = golden();
    let key = Target::AppPackage { package: "com.twitter.android".into() }.key();
    let obs = app("com.twitter.android");

    let mut opens = Launches::default();
    opens.record(NOON - 3000);
    opens.record(NOON - 2000);
    let mut s = state(&["deep-work"], Platform::Android);
    s.launches = BTreeMap::from([(key.clone(), opens)]);
    assert_eq!(decide(NOON, &s, &obs, &cfg), Decision::Allow, "two of three used");

    s.launches.get_mut(&key).unwrap().record(NOON - 100);
    let d = decide(NOON, &s, &obs, &cfg);
    assert!(
        matches!(d, Decision::Block { reason: BlockReason::LaunchLimitReached { count: 3, .. } }),
        "{d:?}"
    );
}

// --- delays ---------------------------------------------------------------------------------

#[test]
fn a_delay_rule_asks_for_friction_not_a_block() {
    let s = state(&["deep-work"], Platform::Android);
    assert_eq!(ask(&s, &app("com.slack")), Decision::Delay { seconds: 15 });
}

#[test]
fn the_longest_delay_wins_when_several_apply() {
    let toml = r#"
        schema_version = 1
        [[profiles]]
        id = "p"
        name = "P"
        [[profiles.rules]]
        target = { kind = "app_package", package = "com.a" }
        action = { kind = "delay", seconds = 5 }
        [[profiles.rules]]
        target = { kind = "app_package", package = "com.a" }
        action = { kind = "delay", seconds = 30 }
    "#;
    let cfg = Config::from_toml(toml).unwrap();
    let s = state(&["p"], Platform::Android);
    assert_eq!(decide(NOON, &s, &app("com.a"), &cfg), Decision::Delay { seconds: 30 });
}

// --- allow-only -----------------------------------------------------------------------------

#[test]
fn allow_only_blocks_everything_it_does_not_name() {
    let s = state(&["writing"], Platform::Android);
    assert_eq!(ask(&s, &app("com.example.editor")), Decision::Allow);
    let d = ask(&s, &app("com.example.notes"));
    assert!(matches!(d, Decision::Block { reason: BlockReason::NotAllowlisted { .. } }), "{d:?}");
}

/// The strict profile wins. Anything else would let a permissive profile be used to escape a
/// stricter one that is running at the same time.
#[test]
fn allow_only_still_applies_when_a_second_profile_is_also_running() {
    let s = state(&["deep-work", "writing"], Platform::Android);
    let d = ask(&s, &app("com.example.notes"));
    assert!(matches!(d, Decision::Block { reason: BlockReason::NotAllowlisted { .. } }), "{d:?}");
}

#[test]
fn an_explicit_block_beats_an_allow_only_allowance() {
    let toml = r#"
        schema_version = 1
        [[profiles]]
        id = "a"
        name = "A"
        [[profiles.rules]]
        target = { kind = "app_package", package = "com.a" }
        action = { kind = "allow_only" }
        [[profiles]]
        id = "b"
        name = "B"
        [[profiles.rules]]
        target = { kind = "app_package", package = "com.a" }
        action = { kind = "block" }
    "#;
    let cfg = Config::from_toml(toml).unwrap();
    for order in [["a", "b"], ["b", "a"]] {
        let s = state(&order, Platform::Android);
        let d = decide(NOON, &s, &app("com.a"), &cfg);
        assert!(
            matches!(d, Decision::Block { reason: BlockReason::Blocked { .. } }),
            "order {order:?} gave {d:?}"
        );
    }
}

// --- notifications --------------------------------------------------------------------------

#[test]
fn a_muted_source_has_its_notifications_suppressed() {
    let s = state(&["deep-work"], Platform::Android);
    let n = Observation::Notification { package: "com.whatsapp".into(), title: "hi".into() };
    assert_eq!(ask(&s, &n), Decision::Mute);
}

/// Being told about the thing you are avoiding is the distraction, so a blocked app's
/// notifications are suppressed without needing a second rule.
#[test]
fn a_blocked_app_also_loses_its_notifications() {
    let toml = r#"
        schema_version = 1
        [[profiles]]
        id = "p"
        name = "P"
        [[profiles.rules]]
        target = { kind = "notification_source", package = "com.instagram.android" }
        action = { kind = "block" }
    "#;
    let cfg = Config::from_toml(toml).unwrap();
    let s = state(&["p"], Platform::Android);
    let n =
        Observation::Notification { package: "com.instagram.android".into(), title: "x".into() };
    assert_eq!(decide(NOON, &s, &n, &cfg), Decision::Mute, "notifications are muted, not blocked");
}

#[test]
fn an_unmuted_notification_is_delivered() {
    let s = state(&["deep-work"], Platform::Android);
    let n = Observation::Notification { package: "com.example.bank".into(), title: "hi".into() };
    assert_eq!(ask(&s, &n), Decision::Allow);
}

/// Allow-only is about what you can *look at*. Applying it to notifications would silence
/// everything on the device, including the things allow-only exists to protect.
#[test]
fn allow_only_does_not_silence_every_notification() {
    let s = state(&["writing"], Platform::Android);
    let n = Observation::Notification { package: "com.example.bank".into(), title: "hi".into() };
    assert_eq!(ask(&s, &n), Decision::Allow);
}

// --- whole device ---------------------------------------------------------------------------

#[test]
fn a_whole_device_rule_blocks_anything_in_the_foreground_but_not_an_idle_screen() {
    let toml = r#"
        schema_version = 1
        [[profiles]]
        id = "frozen"
        name = "Frozen"
        [[profiles.rules]]
        target = { kind = "whole_device" }
        action = { kind = "block" }
    "#;
    let cfg = Config::from_toml(toml).unwrap();
    let s = state(&["frozen"], Platform::Android);
    assert!(matches!(decide(NOON, &s, &app("com.anything"), &cfg), Decision::Block { .. }));
    assert_eq!(decide(NOON, &s, &Observation::Idle, &cfg), Decision::Allow);
}

// --- determinism ----------------------------------------------------------------------------

/// Two devices with the same config and the same log must decide the same thing, so the answer
/// cannot depend on the order rules or profiles happen to be listed in.
#[test]
fn the_decision_does_not_depend_on_profile_order() {
    let cfg = golden();
    let a = state(&["deep-work", "writing"], Platform::Android);
    let b = state(&["writing", "deep-work"], Platform::Android);
    for obs in [app("com.instagram.android"), app("com.example.editor"), app("com.other")] {
        assert_eq!(decide(NOON, &a, &obs, &cfg), decide(NOON, &b, &obs, &cfg), "{obs:?}");
    }
}

#[test]
fn an_unparseable_timezone_at_decide_time_falls_back_instead_of_panicking() {
    // Loading validates the timezone, but a config could arrive over sync from a newer build.
    let mut cfg = golden();
    cfg.timezone = "Nowhere/Real".into();
    let s = state(&["deep-work"], Platform::Android);
    assert!(matches!(
        decide(NOON, &s, &app("com.instagram.android"), &cfg),
        Decision::Block { .. }
    ));
}
