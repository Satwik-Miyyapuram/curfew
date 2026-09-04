//! The rule engine: one pure function both platforms call (ARCHITECTURE.md §3).

use crate::config::{Action, BudgetLedger, Config, Platform, Rule, Target};
use crate::lock::LockSet;
use crate::Timestamp;

/// What the user is looking at right now, as reported by the platform enforcer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Foreground {
    /// Android package name.
    App { package: String },
    /// Windows process, plus its window title when the platform could read one.
    Window { exe: String, title: String },
    /// A page in a browser, already reduced to its host.
    Web { domain: String },
}

/// The live session state the caller materialized from storage and the op-log.
#[derive(Debug, Clone)]
pub struct State {
    /// Profiles whose sessions are currently running.
    pub active_profiles: Vec<String>,
    /// The merged lock guarding those sessions.
    pub lock: LockSet,
    /// Budget seconds already consumed, keyed by [`target_key`].
    pub budgets: BudgetLedger,
    pub platform: Platform,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    Allow,
    Block {
        reason: BlockReason,
    },
    /// Let the user through, but only after `seconds` of friction.
    Delay {
        seconds: u32,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BlockReason {
    /// A rule names this target directly.
    Blocked { profile: String },
    /// An allow-only profile is active and this target is not on the list.
    NotAllowlisted { profile: String },
    /// The shared budget for this target is spent.
    BudgetExhausted { profile: String, seconds: u32 },
}

/// Decide what to do about `fg` at `now`.
///
/// Order matters and is deliberately strictest-first: an explicit block beats an allow-only
/// allowance, an exhausted budget beats a delay, and anything unmatched is allowed. `now` is
/// threaded through for budget windows and locks rather than read from the system, which is what
/// keeps this testable and identical across platforms.
pub fn decide(now: Timestamp, state: &State, fg: &Foreground, config: &Config) -> Decision {
    let _ = now;
    let mut delay: Option<u32> = None;
    let mut allow_only_profile: Option<&str> = None;
    let mut allowlisted = false;

    for profile_id in &state.active_profiles {
        let Some(profile) = config.profile(profile_id) else {
            continue;
        };
        for rule in &profile.rules {
            if !applies_to(rule, state.platform) {
                continue;
            }
            let hit = matches(&rule.target, fg);
            match (&rule.action, hit) {
                (Action::Block, true) => {
                    return Decision::Block {
                        reason: BlockReason::Blocked { profile: profile.id.clone() },
                    }
                }
                (Action::AllowOnly, _) => {
                    allow_only_profile = Some(&profile.id);
                    allowlisted |= hit;
                }
                (Action::Budget { seconds }, true) => {
                    let used = state.budgets.get(&target_key(&rule.target)).copied().unwrap_or(0);
                    if used >= *seconds {
                        return Decision::Block {
                            reason: BlockReason::BudgetExhausted {
                                profile: profile.id.clone(),
                                seconds: *seconds,
                            },
                        };
                    }
                }
                (Action::Delay { seconds }, true) => {
                    delay = Some(delay.map_or(*seconds, |d: u32| d.max(*seconds)));
                }
                _ => {}
            }
        }
    }

    if let Some(profile) = allow_only_profile {
        if !allowlisted {
            return Decision::Block {
                reason: BlockReason::NotAllowlisted { profile: profile.to_string() },
            };
        }
    }

    match delay {
        Some(seconds) => Decision::Delay { seconds },
        None => Decision::Allow,
    }
}

fn applies_to(rule: &Rule, platform: Platform) -> bool {
    rule.platforms.is_empty() || rule.platforms.contains(&platform)
}

/// Stable identity of a target, used as the budget ledger key so the same rule accumulates one
/// shared budget across every device.
pub fn target_key(target: &Target) -> String {
    match target {
        Target::AppPackage { package } => format!("app:{}", package.to_lowercase()),
        Target::WindowsExe { exe } => format!("exe:{}", exe.to_lowercase()),
        Target::Domain { domain } => format!("domain:{}", domain.to_lowercase()),
        Target::WindowTitleContains { text } => format!("title:{}", text.to_lowercase()),
    }
}

fn matches(target: &Target, fg: &Foreground) -> bool {
    match (target, fg) {
        (Target::AppPackage { package }, Foreground::App { package: p }) => {
            package.eq_ignore_ascii_case(p)
        }
        (Target::WindowsExe { exe }, Foreground::Window { exe: e, .. }) => {
            exe.eq_ignore_ascii_case(e)
        }
        (Target::WindowTitleContains { text }, Foreground::Window { title, .. }) => {
            title.to_lowercase().contains(&text.to_lowercase())
        }
        (Target::Domain { domain }, Foreground::Web { domain: d }) => domain_matches(domain, d),
        _ => false,
    }
}

/// A domain rule covers the domain itself and every subdomain, but never a domain that merely ends
/// with the same characters (`notreddit.com` is not `reddit.com`).
fn domain_matches(rule: &str, observed: &str) -> bool {
    let rule = rule.trim_start_matches('.').to_lowercase();
    let observed = observed.to_lowercase();
    observed == rule || observed.ends_with(&format!(".{rule}"))
}
