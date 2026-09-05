//! The escape hatch, and the rationing that keeps it honest.
//!
//! Every blocker eventually meets a real emergency: a locked phone and a hospital, a locked
//! machine and a deadline that moved. A tool with no way out gets uninstalled the first time that
//! happens, and an uninstalled blocker blocks nothing. So there is a way out — but it is scarce,
//! it is slow to come back, and it is written down.
//!
//! Three properties do the work:
//!
//! - **Off by default.** `passes = 0` is the default, so a fresh install is at its strongest and
//!   the hatch is something the user deliberately builds for themselves.
//! - **Rationed against a rolling window, not a calendar period.** Two passes "per week" means two
//!   in any seven days, so a user cannot spend Sunday's and Monday's within an hour of each other
//!   by waiting for a boundary.
//! - **Global, monotone and shared.** Uses are timestamps that only ever accumulate, unioned across
//!   devices through the op-log. Spending a pass on the phone spends it on the PC, and a device
//!   kept offline does not come back with a fresh allowance.
//!
//! What a pass is *not*: it is not a lock condition, and it does not weaken [`LockSet`]. Spending
//! one produces the whole condition set of exactly one session, which that session's `end` then
//! accepts. Invariant 2 holds — `Sessions::end` remains the only exit — and every other session
//! stays locked.
//!
//! [`LockSet`]: crate::LockSet

use crate::Timestamp;
use serde::{Deserialize, Serialize};

/// How many passes there are, how fast they come back, and how close together they may be spent.
///
/// The default is no passes at all. Somebody who wants an escape hatch says so in their config,
/// which means the decision is made while calm rather than at the moment of wanting out.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EmergencyPolicy {
    /// Passes available in any [`window_seconds`](Self::window_seconds)-long stretch. Zero — the
    /// default — disables the hatch entirely.
    #[serde(default)]
    pub passes: u32,
    /// The rolling window the quota is counted over. A week by default: long enough that spending
    /// one is a real decision, short enough that a genuine emergency next month is covered.
    #[serde(default = "default_window")]
    pub window_seconds: u32,
    /// The minimum gap between two uses, on top of the quota. This is the part that stops a
    /// bad evening from consuming a month's allowance in ten minutes.
    #[serde(default = "default_cooldown")]
    pub cooldown_seconds: u32,
}

fn default_window() -> u32 {
    7 * 24 * 3600
}

fn default_cooldown() -> u32 {
    24 * 3600
}

impl Default for EmergencyPolicy {
    fn default() -> Self {
        Self { passes: 0, window_seconds: default_window(), cooldown_seconds: default_cooldown() }
    }
}

impl EmergencyPolicy {
    /// Whether the user has asked for an escape hatch at all.
    pub fn enabled(&self) -> bool {
        self.passes > 0
    }
}

/// Why a pass could not be spent. Every variant carries the one fact the user needs, because a
/// refusal at this moment is the most frustrating message Curfew ever shows and vagueness here is
/// what makes someone reach for the uninstall button instead.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "refusal", rename_all = "snake_case")]
pub enum PassRefusal {
    /// No passes are configured. Nothing is pending; the user has to change their config, and
    /// that change cannot end a running session either.
    Disabled,
    /// The quota for the window is spent. `next_at` is when the oldest use falls out of it.
    QuotaSpent { next_at: Timestamp },
    /// A pass was used recently. `until` is when the cooldown ends.
    CoolingDown { until: Timestamp },
}

/// A spent pass: proof for exactly one release, and the timestamp to record in the log.
///
/// Deliberately not `Clone`. It is consumed by the call that ends a session, so one pass cannot be
/// presented twice — the type system carries the rationing that the accounting below promises.
#[derive(Debug, PartialEq, Eq)]
#[must_use = "a spent pass must be recorded, or the quota it consumed is lost"]
pub struct Pass {
    /// When it was spent. The caller writes this to the op-log so the other devices see it.
    pub at: Timestamp,
}

/// Every pass ever spent, on any device.
///
/// A set of timestamps and nothing else. Merging is union, which makes it a grow-only set: it is
/// commutative, idempotent, and cannot be made to forget. That is what lets two devices that have
/// not spoken in a week still agree that only one pass is left, and it is why the merge needs no
/// tie-breaking, no clocks, and no last-writer-wins.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Passes {
    /// Ascending, deduplicated. Two devices replaying the same log build the same vector.
    pub used: Vec<Timestamp>,
}

impl Passes {
    /// Record a use — from this device or from a peer's op-log. Idempotent, so replaying the log
    /// twice does not consume a second pass.
    pub fn record(&mut self, at: Timestamp) {
        if let Err(index) = self.used.binary_search(&at) {
            self.used.insert(index, at);
        }
    }

    /// Uses inside the rolling window ending at `now`.
    ///
    /// A use timestamped in the future is counted too. It can only come from a device whose clock
    /// ran ahead, and the safe reading of "we are not sure when this happened" is that the pass
    /// was spent, not that it is still available.
    fn recent(&self, now: Timestamp, policy: &EmergencyPolicy) -> Vec<Timestamp> {
        let since = now - i64::from(policy.window_seconds);
        self.used.iter().copied().filter(|at| *at > since).collect()
    }

    /// How many passes could be spent right now, ignoring the cooldown.
    pub fn remaining(&self, now: Timestamp, policy: &EmergencyPolicy) -> u32 {
        let spent = u32::try_from(self.recent(now, policy).len()).unwrap_or(u32::MAX);
        policy.passes.saturating_sub(spent)
    }

    /// Whether a pass can be spent at `now`, and if not, why — without spending one.
    ///
    /// The UI calls this to decide whether to offer the button at all, so that "there is a way out"
    /// and "you cannot take it yet" are answered in the same breath rather than one tap apart.
    pub fn check(&self, now: Timestamp, policy: &EmergencyPolicy) -> Result<(), PassRefusal> {
        if !policy.enabled() {
            return Err(PassRefusal::Disabled);
        }
        let recent = self.recent(now, policy);
        // The cooldown is checked first because it is the shorter wait and the more useful answer:
        // being told to come back in an hour beats being told to come back in six days when both
        // are true.
        if let Some(last) = recent.iter().max() {
            let until = last + i64::from(policy.cooldown_seconds);
            if now < until {
                return Err(PassRefusal::CoolingDown { until });
            }
        }
        if u32::try_from(recent.len()).unwrap_or(u32::MAX) >= policy.passes {
            // The window is rolling, so the quota returns when the *oldest* use inside it ages out.
            let oldest = recent.iter().min().copied().unwrap_or(now);
            return Err(PassRefusal::QuotaSpent {
                next_at: oldest + i64::from(policy.window_seconds),
            });
        }
        Ok(())
    }

    /// Spend one. Records the use here; the caller is responsible for putting it in the op-log so
    /// the other devices see it too.
    pub fn spend(&mut self, now: Timestamp, policy: &EmergencyPolicy) -> Result<Pass, PassRefusal> {
        self.check(now, policy)?;
        self.record(now);
        Ok(Pass { at: now })
    }

    /// Fold in what another device has spent.
    pub fn merge(&mut self, other: &Self) {
        for at in &other.used {
            self.record(*at);
        }
    }

    /// Forget uses that no window ending at or after `now` could still count, so the state a device
    /// carries does not grow without bound. Never called with a `now` in the past, and never
    /// removes anything that could still deny a pass.
    pub fn compact(&mut self, now: Timestamp, policy: &EmergencyPolicy) {
        let horizon = now - i64::from(policy.window_seconds);
        self.used.retain(|at| *at > horizon);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2026-09-04 09:30 UTC. Fixed, because every assertion here is about elapsed time.
    const NOW: Timestamp = 1_788_514_200;
    const DAY: Timestamp = 86_400;

    fn policy(passes: u32, cooldown: u32) -> EmergencyPolicy {
        EmergencyPolicy { passes, window_seconds: 7 * 24 * 3600, cooldown_seconds: cooldown }
    }

    #[test]
    fn a_fresh_install_has_no_escape_hatch_at_all() {
        let policy = EmergencyPolicy::default();
        assert!(!policy.enabled());
        assert_eq!(Passes::default().check(NOW, &policy), Err(PassRefusal::Disabled));
    }

    #[test]
    fn a_configured_pass_can_be_spent_once() {
        let policy = policy(1, 3600);
        let mut passes = Passes::default();
        assert_eq!(passes.remaining(NOW, &policy), 1);
        assert_eq!(passes.spend(NOW, &policy), Ok(Pass { at: NOW }));
        assert_eq!(passes.remaining(NOW, &policy), 0);
    }

    #[test]
    fn a_second_pass_within_the_cooldown_is_refused_even_when_the_quota_allows_it() {
        let policy = policy(5, 3600);
        let mut passes = Passes::default();
        let _ = passes.spend(NOW, &policy).expect("the first pass is available");
        assert_eq!(
            passes.check(NOW + 60, &policy),
            Err(PassRefusal::CoolingDown { until: NOW + 3600 }),
        );
        assert!(passes.check(NOW + 3600, &policy).is_ok());
    }

    #[test]
    fn the_quota_is_counted_over_a_rolling_window_rather_than_a_calendar_week() {
        let policy = policy(2, 0);
        let mut passes = Passes::default();
        let _ = passes.spend(NOW, &policy).expect("one");
        let _ = passes.spend(NOW + DAY, &policy).expect("two");

        // Six days in, both uses are still inside the window.
        assert_eq!(
            passes.check(NOW + 6 * DAY, &policy),
            Err(PassRefusal::QuotaSpent { next_at: NOW + 7 * DAY }),
        );
        // A week after the *first* use, only that one has aged out — so exactly one is back.
        assert!(passes.check(NOW + 7 * DAY, &policy).is_ok());
        assert_eq!(passes.remaining(NOW + 7 * DAY, &policy), 1);
    }

    #[test]
    fn a_refusal_says_when_rather_than_only_no() {
        let policy = policy(1, 2 * 3600);
        let mut passes = Passes::default();
        let _ = passes.spend(NOW, &policy).expect("the only pass");
        match passes.check(NOW + 60, &policy) {
            Err(PassRefusal::CoolingDown { until }) => assert_eq!(until, NOW + 2 * 3600),
            other => panic!("expected a cooldown with a time, got {other:?}"),
        }
        match passes.check(NOW + 3 * 3600, &policy) {
            Err(PassRefusal::QuotaSpent { next_at }) => assert_eq!(next_at, NOW + 7 * DAY),
            other => panic!("expected a quota refusal with a time, got {other:?}"),
        }
    }

    #[test]
    fn a_pass_spent_on_another_device_is_spent_here_too() {
        let policy = policy(1, 0);
        let mut phone = Passes::default();
        let mut pc = Passes::default();
        let _ = phone.spend(NOW, &policy).expect("the phone spends the only pass");

        pc.merge(&phone);
        assert_eq!(pc.remaining(NOW, &policy), 0);
        assert_eq!(pc.check(NOW, &policy), Err(PassRefusal::QuotaSpent { next_at: NOW + 7 * DAY }),);
    }

    /// The whole point of the grow-only set: staying offline is not a way to earn passes.
    #[test]
    fn merging_is_commutative_idempotent_and_never_gives_a_pass_back() {
        let policy = policy(3, 0);
        let mut phone = Passes::default();
        let mut pc = Passes::default();
        let _ = phone.spend(NOW, &policy).expect("phone");
        let _ = pc.spend(NOW + 60, &policy).expect("pc");

        let mut one = phone.clone();
        one.merge(&pc);
        let mut other = pc.clone();
        other.merge(&phone);
        assert_eq!(one, other);

        let before = one.clone();
        one.merge(&other);
        one.merge(&other);
        assert_eq!(one, before, "merging twice consumes nothing extra");
        assert_eq!(one.remaining(NOW + 120, &policy), 1);
    }

    #[test]
    fn replaying_the_same_use_twice_does_not_consume_two_passes() {
        let policy = policy(2, 0);
        let mut passes = Passes::default();
        passes.record(NOW);
        passes.record(NOW);
        assert_eq!(passes.used, vec![NOW]);
        assert_eq!(passes.remaining(NOW, &policy), 1);
    }

    /// A device whose clock ran ahead must not hand its user a free pass on the way back.
    #[test]
    fn a_use_stamped_in_the_future_still_counts_against_the_quota() {
        let policy = policy(1, 0);
        let mut passes = Passes::default();
        passes.record(NOW + 30 * DAY);
        assert_eq!(passes.remaining(NOW, &policy), 0);
    }

    #[test]
    fn compaction_drops_only_what_can_no_longer_deny_a_pass() {
        let policy = policy(1, 0);
        let mut passes = Passes::default();
        passes.record(NOW - 30 * DAY);
        passes.record(NOW - DAY);

        passes.compact(NOW, &policy);
        assert_eq!(passes.used, vec![NOW - DAY]);
        // And the surviving use is still doing its job.
        assert_eq!(passes.remaining(NOW, &policy), 0);
    }

    #[test]
    fn the_recorded_order_does_not_depend_on_the_order_the_log_arrived_in() {
        let mut forwards = Passes::default();
        let mut backwards = Passes::default();
        for at in [NOW, NOW + 60, NOW + 120] {
            forwards.record(at);
        }
        for at in [NOW + 120, NOW, NOW + 60] {
            backwards.record(at);
        }
        assert_eq!(forwards, backwards);
        assert_eq!(forwards.used, vec![NOW, NOW + 60, NOW + 120]);
    }
}
