//! Matching: globs, domains, URLs and keywords, including the cases a hostile or careless rule
//! could produce. Matching runs on every foreground change, so "never panics, never stalls" is as
//! much a requirement here as "gets the right answer".

use curfew_core::{domain_matches, glob_match, Observation, Target, Url};
use proptest::prelude::*;

fn web(url: &str) -> Observation {
    Observation::Web { url: Url::parse(url) }
}

// --- globs ----------------------------------------------------------------------------------

#[test]
fn a_glob_without_wildcards_is_an_exact_case_insensitive_match() {
    assert!(glob_match("Steam.exe", "steam.EXE"));
    assert!(!glob_match("steam.exe", "steam.exe.bak"));
    assert!(!glob_match("steam.exe", "steam.ex"));
}

#[test]
fn a_star_matches_an_empty_run() {
    assert!(glob_match("*", ""));
    assert!(glob_match("a*b", "ab"));
    assert!(glob_match("**", "anything"));
}

#[test]
fn a_question_mark_matches_exactly_one_character() {
    assert!(glob_match("a?c", "abc"));
    assert!(!glob_match("a?c", "ac"), "? is not optional");
    assert!(!glob_match("a?c", "abbc"));
}

#[test]
fn an_empty_pattern_matches_only_an_empty_string() {
    assert!(glob_match("", ""));
    assert!(!glob_match("", "x"));
}

/// The shape that makes a backtracking regex engine hang. Here it must return, fast.
#[test]
fn a_pathological_pattern_terminates() {
    let pattern = "*a*a*a*a*a*a*a*a*a*a*a*a*a*b";
    let text = "a".repeat(2_000);
    let start = std::time::Instant::now();
    assert!(!glob_match(pattern, &text));
    assert!(start.elapsed().as_millis() < 500, "glob matching must not stall the enforcer");
}

#[test]
fn trailing_stars_after_the_text_runs_out_still_match() {
    assert!(glob_match("abc***", "abc"));
    assert!(!glob_match("abc*?", "abc"), "? still needs a character");
}

// --- domains --------------------------------------------------------------------------------

#[test]
fn a_domain_covers_itself_and_its_subdomains() {
    assert!(domain_matches("reddit.com", "reddit.com"));
    assert!(domain_matches("reddit.com", "old.reddit.com"));
    assert!(domain_matches("reddit.com", "a.b.c.reddit.com"));
}

/// The bug that would quietly let the whole point of a block through.
#[test]
fn a_domain_never_covers_a_lookalike() {
    assert!(!domain_matches("reddit.com", "notreddit.com"));
    assert!(!domain_matches("reddit.com", "reddit.com.evil.test"));
    assert!(!domain_matches("reddit.com", "myreddit.community"));
}

#[test]
fn domains_are_normalized_for_case_and_stray_dots() {
    assert!(domain_matches(".Reddit.COM.", "old.reddit.com"));
}

// --- URLs -----------------------------------------------------------------------------------

#[test]
fn parsing_splits_a_url_into_the_parts_rules_care_about() {
    let u = Url::parse("HTTPS://user:pw@WWW.Example.com:8443/a/b?x=1#frag");
    assert_eq!(u.host, "www.example.com");
    assert_eq!(u.path, "/a/b");
    assert_eq!(u.query, "x=1");
    assert_eq!(u.normalized(), "www.example.com/a/b?x=1", "the fragment never reaches a rule");
}

#[test]
fn a_url_without_a_scheme_or_path_still_parses() {
    let u = Url::parse("example.com");
    assert_eq!(u.host, "example.com");
    assert_eq!(u.path, "");
    assert_eq!(u.normalized(), "example.com");
}

#[test]
fn a_url_target_matches_the_normalized_form() {
    let t = Target::Url { pattern: "*youtube.com/shorts*".into() };
    assert!(t.matches(&web("https://www.youtube.com/shorts/abc")));
    assert!(t.matches(&web("http://user@www.youtube.com:8080/shorts/abc?a=1")));
    assert!(!t.matches(&web("https://www.youtube.com/watch?v=abc")));
}

#[test]
fn a_keyword_searches_the_url_the_title_and_the_path() {
    let t = Target::Keyword { text: "Black Friday".into() };
    assert!(t.matches(&Observation::Window {
        exe: "chrome.exe".into(),
        title: "black friday deals".into(),
    }));
    assert!(t.matches(&web("https://shop.test/black friday")));
    assert!(!t.matches(&Observation::Idle), "idle has no text to search");
}

// --- file paths -----------------------------------------------------------------------------

#[test]
fn a_file_path_rule_ignores_which_separator_was_written() {
    let t = Target::FilePath { pattern: r"c:\games\*".into() };
    assert!(t.matches(&Observation::FileOpen { path: "C:/Games/doom.exe".into() }));
    assert!(t.matches(&Observation::FileOpen { path: r"C:\Games\doom.exe".into() }));
    assert!(!t.matches(&Observation::FileOpen { path: r"D:\Games\doom.exe".into() }));
}

// --- keys -----------------------------------------------------------------------------------

/// Two rules naming the same thing must share one budget, however they were typed.
#[test]
fn keys_are_stable_under_case_and_distinct_across_kinds() {
    let a = Target::AppPackage { package: "COM.Example".into() }.key();
    let b = Target::AppPackage { package: "com.example".into() }.key();
    assert_eq!(a, b);
    assert_ne!(a, Target::Domain { domain: "com.example".into() }.key());
    assert_eq!(Target::WholeDevice.key(), "device");
}

// --- properties -----------------------------------------------------------------------------

proptest! {
    /// Matching must never panic, whatever the platform or the config hands it.
    #[test]
    fn glob_matching_never_panics(p in ".{0,40}", t in ".{0,40}") {
        let _ = glob_match(&p, &t);
    }

    /// A literal pattern, with every wildcard removed, matches itself.
    #[test]
    fn a_literal_pattern_matches_itself(s in "[a-z0-9./-]{0,30}") {
        prop_assert!(glob_match(&s, &s));
    }

    /// A single leading and trailing star turns any literal into a substring search.
    #[test]
    fn a_starred_literal_matches_any_string_containing_it(
        pre in "[a-z]{0,10}", mid in "[a-z]{1,10}", post in "[a-z]{0,10}"
    ) {
        let pattern = format!("*{mid}*");
        let text = format!("{pre}{mid}{post}");
        prop_assert!(glob_match(&pattern, &text));
    }

    /// Parsing never panics and never invents a host that was not there.
    #[test]
    fn url_parsing_never_panics(s in ".{0,60}") {
        let u = Url::parse(&s);
        prop_assert!(!u.host.contains('/'));
        prop_assert!(!u.host.contains('@'));
    }

    /// A domain never matches a string that merely ends with it.
    #[test]
    fn a_domain_never_matches_a_bare_suffix(rule in "[a-z]{3,8}\\.com", prefix in "[a-z]{1,6}") {
        let observed = format!("{prefix}{rule}");
        prop_assert!(!domain_matches(&rule, &observed));
    }
}
