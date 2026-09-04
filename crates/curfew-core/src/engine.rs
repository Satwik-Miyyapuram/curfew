//! The rule engine: one pure function both platforms call (ARCHITECTURE.md §3).

use crate::budget::{Consumption, Launches};
use crate::config::{Action, Config, Platform, Rule};
use crate::lock::LockSet;
use crate::target::Observation;
use crate::Timestamp;
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// The live session state the caller materialized from storage and the op-log.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct State {
    /// Profiles whose sessions are currently running.
    pub active_profiles: Vec<String>,
    /// The merged lock guarding those sessions.
    pub lock: LockSet,
    /// Time spent, keyed by [`crate::target::Target::key`].
    pub usage: BTreeMap<String, Consumption>,
    /// Opens, keyed the same way.
    pub launches: BTreeMap<String, Launches>,
    pub platform: Platform,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "decision", rename_all = "snake_case")]
pub enum Decision {
    Allow,
    Block {
        reason: BlockReason,
    },
    /// Let the user through, but only after `seconds` of friction.
    Delay {
        seconds: u32,
    },
    /// Deliver nothing: the notification is suppressed. Only ever produced for notifications.
    Mute,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "reason", rename_all = "snake_case")]
pub enum BlockReason {
    /// A rule names this target directly.
    Blocked { profile: String },
    /// An allow-only profile is active and this target is not on the list.
    NotAllowlisted { profile: String },
    /// The shared budget for this target is spent.
    BudgetExhausted { profile: String, seconds: u32 },
    /// It has been opened as many times as the rule allows in this window.
    LaunchLimitReached { profile: String, count: u32 },
}

/// Decide what to do about `obs` at `now`.
///
/// Every active profile is evaluated in full and the results are then resolved strictest-first, so
/// the answer does not depend on the order profiles or rules happen to appear in — two devices
/// with the same config and the same op-log must decide identically, and rule order is exactly the
/// kind of thing that drifts between them.
///
/// `now` is a parameter rather than a clock read: that is what makes the engine testable, and what
/// lets Android and Windows agree about what a config means.
pub fn decide(now: Timestamp, state: &State, obs: &Observation, config: &Config) -> Decision {
    // An unparseable timezone was already rejected at load; fall back rather than panic here,
    // because the enforcer must always produce an answer.
    let tz: Tz = config.timezone.parse().unwrap_or(chrono_tz::UTC);

    // Nothing in the foreground is not something to block. Without this an allow-only profile
    // would "block" the lock screen and the launcher, and the overlay would fight the home button.
    if matches!(obs, Observation::Idle) {
        return Decision::Allow;
    }

    let mut blocked: Option<BlockReason> = None;
    let mut budget_spent: Option<BlockReason> = None;
    let mut launches_spent: Option<BlockReason> = None;
    let mut allow_only: Option<String> = None;
    let mut allowlisted = false;
    let mut delay: Option<u32> = None;
    let mut mute = false;

    for profile_id in &state.active_profiles {
        let Some(profile) = config.profile(profile_id) else { continue };
        for rule in &profile.rules {
            if !applies_to(rule, state.platform) {
                continue;
            }
            let hit = rule.target.matches(obs);
            let key = rule.target.key();

            match &rule.action {
                Action::Block if hit => {
                    blocked.get_or_insert(BlockReason::Blocked { profile: profile.id.clone() });
                }
                Action::AllowOnly => {
                    // An allow-only rule turns the whole profile into an allowlist, whether or not
                    // this particular observation matches it.
                    allow_only.get_or_insert_with(|| profile.id.clone());
                    allowlisted |= hit;
                }
                Action::Budget { seconds, refill } if hit => {
                    let from = refill.window_start(now, tz);
                    let used = state.usage.get(&key).map(|c| c.used_since(from)).unwrap_or(0);
                    if used >= *seconds {
                        budget_spent.get_or_insert(BlockReason::BudgetExhausted {
                            profile: profile.id.clone(),
                            seconds: *seconds,
                        });
                    }
                }
                Action::LaunchLimit { count, refill } if hit => {
                    let from = refill.window_start(now, tz);
                    let opens = state.launches.get(&key).map(|l| l.count_since(from)).unwrap_or(0);
                    if opens >= *count {
                        launches_spent.get_or_insert(BlockReason::LaunchLimitReached {
                            profile: profile.id.clone(),
                            count: *count,
                        });
                    }
                }
                Action::Delay { seconds } if hit => {
                    delay = Some(delay.map_or(*seconds, |d: u32| d.max(*seconds)));
                }
                Action::MuteNotifications if hit => mute = true,
                _ => {}
            }
        }
    }

    // Notifications are a different question: nothing about them can be "blocked" or "delayed",
    // only delivered or not. A target that is blocked outright has its notifications suppressed
    // too — being told about the thing you are avoiding is the distraction.
    if obs.is_notification() {
        return if mute || blocked.is_some() { Decision::Mute } else { Decision::Allow };
    }

    // Strictest first, and independent of rule order.
    if let Some(reason) = blocked.or(budget_spent).or(launches_spent) {
        return Decision::Block { reason };
    }
    if let Some(profile) = allow_only {
        if !allowlisted {
            return Decision::Block { reason: BlockReason::NotAllowlisted { profile } };
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
