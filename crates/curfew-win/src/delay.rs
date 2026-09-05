//! The friction screen: a rule that delays an app rather than blocking it.
//!
//! `Action::Delay` is the softest thing a profile can ask for, and the easiest to get wrong. It does
//! not mean "block for a while" and it does not mean "nag": it means the app opens *after* the user
//! has sat with the decision for a few seconds. Freedom and Cold Turkey both do this, and both do it
//! the same way — the app does not run during the wait, and once the wait is served it runs
//! normally until it is closed again.
//!
//! Windows offers no way to hold a process at launch that a program cannot simply ignore, so the
//! wait is served by closing the app while the countdown runs and leaving it alone afterwards. That
//! is the honest shape of it, and it is why this module exists as pure state rather than as a branch
//! inside the enforcement loop: what a gate does to an app is the part worth testing exhaustively.

use curfew_core::Timestamp;
use std::collections::{BTreeMap, BTreeSet};

/// Where one executable stands with its delay.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Gate {
    /// The countdown is running and lands at this time.
    Waiting { until: Timestamp },
    /// The wait was served. The app runs until it is closed, and only then waits again.
    Served,
}

/// What the enforcer should do about one delayed app right now.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Step {
    /// Close it, and tell the user how many seconds are left of the wait.
    Hold { seconds_left: i64 },
    /// The wait is over. Leave it alone.
    Pass,
}

/// Every delay in flight, keyed by lower-cased executable name.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Gates {
    entries: BTreeMap<String, Gate>,
}

impl Gates {
    /// Ask what to do about `exe`, starting its countdown if it has none.
    pub fn consider(&mut self, exe: &str, now: Timestamp, seconds: u32) -> Step {
        let key = exe.to_lowercase();
        let seconds = seconds as i64;
        let entry = self.entries.entry(key).or_insert(Gate::Waiting { until: now + seconds });

        match *entry {
            Gate::Served => Step::Pass,
            Gate::Waiting { until } => {
                // A clock that moved forward must not extend a wait, and one that moved backwards
                // must not either: the wait a user was promised is the one they get, so a landing
                // time further away than the rule allows is brought back to what the rule says.
                let until = until.min(now + seconds);
                self.entries.insert(exe.to_lowercase(), Gate::Waiting { until });
                if now >= until {
                    self.entries.insert(exe.to_lowercase(), Gate::Served);
                    Step::Pass
                } else {
                    Step::Hold { seconds_left: until - now }
                }
            }
        }
    }

    /// Forget every app that is no longer running.
    ///
    /// This is what makes a delay a delay rather than a one-time toll: closing the app spends the
    /// wait, so opening it again costs the same few seconds of thought as the first time.
    pub fn forget_absent(&mut self, running: &BTreeSet<String>) {
        self.entries.retain(|exe, _| running.contains(exe));
    }

    /// Drop everything. Used when the config is reloaded, since a rule the user has just edited
    /// should not be governed by a countdown started under the old one.
    pub fn clear(&mut self) {
        self.entries.clear();
    }

    #[cfg(test)]
    fn served(&self, exe: &str) -> bool {
        matches!(self.entries.get(exe), Some(Gate::Served))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: Timestamp = 1_788_510_600;

    #[test]
    fn a_first_sight_starts_the_wait_and_the_app_does_not_run_during_it() {
        let mut gates = Gates::default();
        assert_eq!(gates.consider("Slack.exe", NOW, 15), Step::Hold { seconds_left: 15 });
    }

    #[test]
    fn the_wait_counts_down_rather_than_restarting_on_every_pass() {
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 15);
        assert_eq!(gates.consider("slack.exe", NOW + 5, 15), Step::Hold { seconds_left: 10 });
        assert_eq!(gates.consider("slack.exe", NOW + 14, 15), Step::Hold { seconds_left: 1 });
    }

    #[test]
    fn once_served_the_app_runs_and_is_not_asked_again() {
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 15);
        assert_eq!(gates.consider("slack.exe", NOW + 15, 15), Step::Pass);
        assert_eq!(gates.consider("slack.exe", NOW + 3600, 15), Step::Pass);
        assert!(gates.served("slack.exe"));
    }

    #[test]
    fn closing_the_app_spends_the_wait_so_reopening_costs_the_same_pause() {
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 15);
        gates.consider("slack.exe", NOW + 15, 15);

        gates.forget_absent(&BTreeSet::new());

        assert_eq!(gates.consider("slack.exe", NOW + 20, 15), Step::Hold { seconds_left: 15 });
    }

    #[test]
    fn an_app_that_is_still_running_keeps_what_it_served() {
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 15);
        gates.consider("slack.exe", NOW + 15, 15);

        gates.forget_absent(&BTreeSet::from(["slack.exe".to_string()]));

        assert_eq!(gates.consider("slack.exe", NOW + 20, 15), Step::Pass);
    }

    #[test]
    fn a_clock_pushed_backwards_cannot_stretch_a_wait_past_what_the_rule_asked_for() {
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 15);
        // The machine's clock jumps back an hour. Left alone, the landing time would now be an hour
        // away, which is a delay rule turning into a block nobody asked for.
        assert_eq!(gates.consider("slack.exe", NOW - 3600, 15), Step::Hold { seconds_left: 15 });
    }

    #[test]
    fn a_clock_pushed_forwards_serves_the_wait_rather_than_refusing_to() {
        // The other direction is not a bypass worth fighting: skipping fifteen seconds of thought
        // by changing the system clock is more work than waiting, and a delay is not a lock.
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 15);
        assert_eq!(gates.consider("slack.exe", NOW + 86_400, 15), Step::Pass);
    }

    #[test]
    fn a_shortened_rule_shortens_the_wait_in_flight() {
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 60);
        assert_eq!(gates.consider("slack.exe", NOW, 5), Step::Hold { seconds_left: 5 });
    }

    #[test]
    fn a_zero_second_delay_is_no_delay_at_all() {
        let mut gates = Gates::default();
        assert_eq!(gates.consider("slack.exe", NOW, 0), Step::Pass);
    }

    #[test]
    fn each_app_waits_on_its_own_account() {
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 15);
        assert_eq!(gates.consider("steam.exe", NOW + 15, 30), Step::Hold { seconds_left: 30 });
        assert_eq!(gates.consider("slack.exe", NOW + 15, 15), Step::Pass);
    }

    #[test]
    fn a_reload_starts_every_wait_again_rather_than_honouring_an_old_rule() {
        let mut gates = Gates::default();
        gates.consider("slack.exe", NOW, 15);
        gates.consider("slack.exe", NOW + 15, 15);
        gates.clear();
        assert_eq!(gates.consider("slack.exe", NOW + 16, 15), Step::Hold { seconds_left: 15 });
    }
}
