use curfew_core::config::{BudgetLedger, Platform};
use curfew_core::engine::{target_key, BlockReason, Foreground, State};
use curfew_core::{decide, Config, Decision, LockSet, Target};

const GOLDEN: &str = include_str!("golden/example.toml");

fn state(platform: Platform, budgets: BudgetLedger) -> State {
    State {
        active_profiles: vec!["deep-work".into()],
        lock: LockSet::unlocked(),
        budgets,
        platform,
    }
}

fn cfg() -> Config {
    Config::from_toml(GOLDEN).unwrap()
}

#[test]
fn blocks_a_named_app() {
    let fg = Foreground::App { package: "com.instagram.android".into() };
    assert!(matches!(
        decide(0, &state(Platform::Android, BudgetLedger::new()), &fg, &cfg()),
        Decision::Block { reason: BlockReason::Blocked { .. } }
    ));
}

#[test]
fn allows_an_unlisted_app() {
    let fg = Foreground::App { package: "com.example.notes".into() };
    assert_eq!(
        decide(0, &state(Platform::Android, BudgetLedger::new()), &fg, &cfg()),
        Decision::Allow
    );
}

#[test]
fn no_active_profile_means_no_blocking() {
    let mut st = state(Platform::Android, BudgetLedger::new());
    st.active_profiles.clear();
    let fg = Foreground::App { package: "com.instagram.android".into() };
    assert_eq!(decide(0, &st, &fg, &cfg()), Decision::Allow);
}

#[test]
fn budget_blocks_only_once_spent() {
    let key = target_key(&Target::Domain { domain: "reddit.com".into() });
    let fg = Foreground::Web { domain: "old.reddit.com".into() };

    let mut used = BudgetLedger::new();
    used.insert(key.clone(), 1199);
    assert_eq!(decide(0, &state(Platform::Android, used), &fg, &cfg()), Decision::Allow);

    let mut spent = BudgetLedger::new();
    spent.insert(key, 1200);
    assert!(matches!(
        decide(0, &state(Platform::Android, spent), &fg, &cfg()),
        Decision::Block { reason: BlockReason::BudgetExhausted { .. } }
    ));
}

#[test]
fn domain_rules_cover_subdomains_but_not_lookalikes() {
    let c = cfg();
    let st = state(Platform::Android, BudgetLedger::new());
    let mut spent = st.budgets.clone();
    spent.insert(target_key(&Target::Domain { domain: "reddit.com".into() }), 9999);
    let st = State { budgets: spent, ..st };

    for d in ["reddit.com", "www.reddit.com", "old.reddit.com"] {
        let fg = Foreground::Web { domain: d.into() };
        assert!(matches!(decide(0, &st, &fg, &c), Decision::Block { .. }), "{d} should be blocked");
    }
    let fg = Foreground::Web { domain: "notreddit.com".into() };
    assert_eq!(decide(0, &st, &fg, &c), Decision::Allow, "lookalike domain must not match");
}

#[test]
fn platform_scoped_rules_only_fire_on_their_platform() {
    let fg = Foreground::Window { exe: "steam.exe".into(), title: "Steam".into() };
    let c = cfg();
    assert!(matches!(
        decide(0, &state(Platform::Windows, BudgetLedger::new()), &fg, &c),
        Decision::Block { .. }
    ));
    assert_eq!(decide(0, &state(Platform::Android, BudgetLedger::new()), &fg, &c), Decision::Allow);
}

#[test]
fn delay_rules_produce_friction_not_a_block() {
    let fg = Foreground::App { package: "com.slack".into() };
    assert_eq!(
        decide(0, &state(Platform::Android, BudgetLedger::new()), &fg, &cfg()),
        Decision::Delay { seconds: 15 }
    );
}
