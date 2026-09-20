//! What the rule engine costs as a config grows.
//!
//! **Measurement, not a gate.** Every test here is `#[ignore]`d, so `cargo test` stays fast and no
//! timing threshold sits in CI to go flaky on a loaded machine. Run them deliberately:
//!
//! ```text
//! cargo test -p curfew-core --test engine_cost -- --ignored --nocapture
//! ```
//!
//! They exist because two claims about this engine were made from reasoning alone and both were
//! wrong. `charged_keys` was described as O(n²) "worth a note as rule counts grow" — true, but
//! nobody had asked *at what n*, so the note could not be acted on. And `decide` was assumed to be
//! dominated by rule matching, when it was in fact allocating a key `String` for every rule of every
//! active profile including the ones that never read it.
//!
//! A number here is worth more than the argument that produced it.

use curfew_core::config::{Action, Profile, Rule};
use curfew_core::engine::{charged_keys, State};
use curfew_core::Refill;
use curfew_core::{Config, Observation, Platform, Target};
use std::collections::BTreeMap;
use std::time::Instant;

/// A config with `count` budget rules on domains, which are the rules that are both metered and
/// matched by a single observation — the worst case for `charged_keys`, because every one of them
/// hits and every one of them is deduplicated against the ones before it.
fn metered_config(count: usize) -> Config {
    let rules = (0..count)
        .map(|i| Rule {
            target: Target::Domain { domain: format!("site{i}.test") },
            action: Action::Budget { seconds: 600, refill: Refill::Never },
            platforms: vec![],
        })
        .collect();
    Config {
        profiles: vec![Profile {
            id: "deep-work".into(),
            name: "Deep work".into(),
            description: String::new(),
            rules,
        }],
        ..Config::default()
    }
}

/// The observation every one of those rules matches, so no rule is skipped as a non-hit.
fn wide_observation() -> Observation {
    Observation::Web { url: curfew_core::Url::parse("https://site1.test/") }
}

fn state() -> State {
    State {
        active_profiles: vec!["deep-work".into()],
        platform: Platform::Android,
        usage: BTreeMap::new(),
        launches: BTreeMap::new(),
        ..State::default()
    }
}

/// How `charged_keys` scales, which is the question the audit raised and did not answer.
#[test]
#[ignore = "measurement; run with --ignored --nocapture"]
fn charged_keys_cost_by_rule_count() {
    println!("\n  rules |    keys |      per call |  per rule");
    println!("  ------+---------+---------------+----------");
    for count in [10usize, 50, 100, 250, 500, 1000, 2000] {
        let config = metered_config(count);
        let state = state();
        let obs = wide_observation();

        // A match on one of these is a *suffix* match against the observation's host, so only the
        // rule whose domain the host actually ends with hits. That is deliberate: it is the shape a
        // real config has, and it means `keys` stays small while the scan stays long — the wall-clock
        // cost is the scan, which is the part that grows.
        let iterations = 1_000;
        let start = Instant::now();
        let mut keys = 0;
        for _ in 0..iterations {
            keys = charged_keys(&state, &obs, &config).len();
        }
        let each = start.elapsed() / iterations;
        let per_rule = each / count.max(1) as u32;
        println!("  {count:>5} | {keys:>7} | {:>10.2?} | {:>7.3?}", each, per_rule,);
    }
    println!();
}

/// The whole decision, which is what runs on every foreground change.
#[test]
#[ignore = "measurement; run with --ignored --nocapture"]
fn decide_cost_by_rule_count() {
    println!("\n  rules |      per decide");
    println!("  ------+----------------");
    for count in [10usize, 50, 100, 250, 500, 1000] {
        let config = metered_config(count);
        let state = state();
        let obs = wide_observation();

        let iterations = 1_000;
        let start = Instant::now();
        for _ in 0..iterations {
            let _ = curfew_core::decide(1_788_609_600, &state, &obs, &config);
        }
        println!("  {count:>5} | {:>14.2?}", start.elapsed() / iterations);
    }
    println!();
}

/// A real config: mostly plain blocks, which is what the reordered `charged_keys` exists for.
///
/// The all-metered config above is the case the reorder does *not* help, because there the target
/// matcher is asked for every rule either way. This is the shape a person actually writes.
fn mixed_config(total: usize, metered: usize) -> Config {
    let mut rules: Vec<Rule> = (0..total.saturating_sub(metered))
        .map(|i| Rule {
            target: Target::AppPackage { package: format!("com.example.app{i}") },
            action: Action::Block,
            platforms: vec![],
        })
        .collect();
    rules.extend((0..metered).map(|i| Rule {
        target: Target::Domain { domain: format!("site{i}.test") },
        action: Action::Budget { seconds: 600, refill: Refill::Never },
        platforms: vec![],
    }));
    Config {
        profiles: vec![Profile {
            id: "deep-work".into(),
            name: "Deep work".into(),
            description: String::new(),
            rules,
        }],
        ..Config::default()
    }
}

/// The cheap-question-first ordering, measured, against a config that is mostly blocks.
///
/// The observation is an app that every block rule matches, which is the worst case for the scan and
/// the best case for the reorder — so the number below is the most the ordering can buy, not a
/// typical figure.
///
/// Measured on the development machine after the reorder:
///
/// ```text
///   10 metered rules among N total |   per call
///   -------------------------------+-----------
///                              10 |  223.00ns
///                             100 |  597.00ns
///                             500 |    2.54µs
///                            2000 |   10.69µs
/// ```
///
/// Roughly **5 ns per block rule** once the action is asked about first, against the ~595 ns per rule
/// the all-metered table above still shows — because there the target matcher has to run for every
/// rule either way. Before the reorder this config cost what the all-metered one does: the matcher was
/// asked about every block rule and its answer thrown away.
#[test]
#[ignore = "measurement; run with --ignored --nocapture"]
fn charged_keys_cost_in_a_mostly_blocked_config() {
    println!("\n  10 metered rules among N total |   per call");
    println!("  -------------------------------+-----------");
    for total in [10usize, 50, 100, 250, 500, 1000, 2000] {
        let config = mixed_config(total, 10);
        let state = state();
        let obs = Observation::App { package: "com.example.app3".into(), screen: None };

        let iterations = 1_000;
        let start = Instant::now();
        for _ in 0..iterations {
            let _ = charged_keys(&state, &obs, &config);
        }
        println!("  {:>29} | {:>9.2?}", total, start.elapsed() / iterations);
    }
    println!();
}
