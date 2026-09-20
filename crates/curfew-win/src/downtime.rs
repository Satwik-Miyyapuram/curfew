//! **The window enforcement was down, reported rather than forgotten.**
//!
//! `ARCHITECTURE.md` §10 promises it: *"if enforcement was down (reboot, force-stop, revoked permission,
//! OEM killer), the next launch reports the exact window it was down. No silent failure."* Android
//! implements it (`Downtime.kt`, `detectDowntime`, a dismissable banner on the Now screen) and Windows
//! had nothing — P1-8.
//!
//! It matters more here than a health indicator usually does, because on a commitment device a gap is
//! the shape of an escape. Killing the service during a timer lock does not end the lock, but it does
//! stop everything behind it being enforced, and if nothing says so then the machine looks like it was
//! working the whole time. The product's whole claim is that it does not lie about what it did.
//!
//! **The interesting gap is the one with no reboot in it.** A machine that was switched off overnight
//! is ordinary, and the clock layer already reports the time it credited across the shutdown
//! (`ClockWitness::unverified`). A *service* that was killed and restarted inside one boot is the case
//! nothing caught: the uptime never went backwards, so the clock sees nothing wrong, and
//! `Persisted::last_tick` was only ever used to charge elapsed time — clamped to one tick, so the two
//! hours the machine spent unenforced were silently discarded. [`Downtime::detect`] is what reads that
//! gap as a fact about enforcement rather than as a charging detail.

use curfew_core::Timestamp;
use serde::{Deserialize, Serialize};

/// A gap shorter than this is an ordinary restart, not downtime worth reporting.
///
/// The service restarts on upgrade, on a config change, after a crash the manager recovers from in
/// seconds. Five minutes is Android's threshold (`DOWNTIME_SECONDS`) and the same reasoning applies: a
/// gap that short cannot have let anybody do much, and reporting every restart trains people to dismiss
/// the banner without reading it — which is exactly what this must not become, because the banner that
/// matters is the one that says enforcement was off for two hours.
pub const DOWNTIME_SECONDS: i64 = 5 * 60;

/// A period enforcement was not running.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Downtime {
    /// The last trusted instant the service was known to be running.
    pub from: Timestamp,
    /// When it noticed it was running again.
    pub to: Timestamp,
    /// Whether the machine restarted in between, as opposed to only the service.
    ///
    /// The two deserve different words and they are not equally interesting. A machine that was off is
    /// nobody's doing; a service that was killed while the machine stayed up is either a crash or a
    /// deliberate stop, and it is the second one this exists to make visible.
    pub rebooted: bool,
    /// How many sessions were running when enforcement stopped, if the state file survived.
    ///
    /// The number that makes the notice mean something: "two hours unenforced" is a statistic, and "two
    /// hours unenforced with a lock running" is the thing the user needs to know.
    pub sessions: usize,
}

impl Downtime {
    /// The gap between `last_tick` and `now`, if it is worth reporting.
    ///
    /// `last_tick` is `Persisted::last_tick` — the **trusted** instant of the previous pass, so this
    /// measures against the clock the locks are judged by rather than against the wall clock somebody
    /// may have moved. Returns `None` for a first run, for a gap under [`DOWNTIME_SECONDS`], and for a
    /// `last_tick` in the future (which is the clock layer's business, and it already reports it).
    pub fn detect(
        last_tick: Option<Timestamp>,
        now: Timestamp,
        rebooted: bool,
        sessions: usize,
    ) -> Option<Self> {
        let from = last_tick?;
        let gap = now.checked_sub(from)?;
        if gap < DOWNTIME_SECONDS {
            return None;
        }
        Some(Self { from, to: now, rebooted, sessions })
    }

    pub fn seconds(&self) -> i64 {
        // Clamped at zero: a gap can never be negative here, because `detect` refuses one, and a
        // negative duration in a sentence would be worse than a wrong one.
        (self.to - self.from).max(0)
    }

    /// The sentence a surface shows.
    ///
    /// Says the window, what it cost, and which of the two causes it was — because "Curfew was not
    /// running" and "this machine was restarted" lead a user to different conclusions, and only one of
    /// them is worth being suspicious about.
    pub fn describe(&self) -> String {
        let when = if self.rebooted {
            "This machine was restarted, and Curfew did not run for"
        } else {
            "Curfew was not running for"
        };
        let cost = match self.sessions {
            0 => "nothing was locked at the time".to_string(),
            1 => "one lock was running and was not enforced".to_string(),
            n => format!("{n} locks were running and were not enforced"),
        };
        format!(
            "{when} {}. From {} to {}. {cost}, so treat that stretch as time this machine was not \
             held to.",
            human(self.seconds()),
            self.from,
            self.to,
        )
    }
}

/// Whole minutes, or hours and minutes, or days. Rounded down, because the notice should not claim
/// more than it can prove.
fn human(seconds: i64) -> String {
    if seconds < 3600 {
        format!("{} minutes", (seconds / 60).max(1))
    } else if seconds < 86_400 {
        format!("{} hours {} minutes", seconds / 3600, (seconds % 3600) / 60)
    } else {
        format!("{} days {} hours", seconds / 86_400, (seconds % 86_400) / 3600)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: Timestamp = 1_788_512_400;

    #[test]
    fn a_first_run_is_not_a_gap() {
        assert_eq!(Downtime::detect(None, NOW, false, 0), None);
    }

    #[test]
    fn an_ordinary_restart_is_not_reported() {
        // Four minutes: an upgrade, a config reload, a crash the manager recovered from.
        assert_eq!(Downtime::detect(Some(NOW - 240), NOW, true, 1), None);
        // And exactly at the threshold is still not a gap — the comparison is `>=` on the constant.
        assert_eq!(Downtime::detect(Some(NOW - DOWNTIME_SECONDS + 1), NOW, false, 1), None);
        assert!(Downtime::detect(Some(NOW - DOWNTIME_SECONDS), NOW, false, 1).is_some());
    }

    /// **The case nothing caught before.** No reboot, so the clock layer sees nothing wrong: the
    /// service was killed and restarted inside one boot.
    #[test]
    fn a_service_absent_within_one_boot_is_reported_as_such() {
        let gap = Downtime::detect(Some(NOW - 7200), NOW, false, 1).expect("a two-hour gap");
        assert_eq!(gap.seconds(), 7200);
        assert_eq!(gap.sessions, 1);
        assert!(!gap.rebooted);
        let text = gap.describe();
        assert!(text.contains("Curfew was not running"), "{text}");
        assert!(!text.contains("restarted"), "a service stop was described as a restart: {text}");
        assert!(text.contains("2 hours"), "{text}");
        assert!(text.contains("one lock was running"), "{text}");
    }

    #[test]
    fn a_reboot_is_described_as_a_reboot() {
        let gap = Downtime::detect(Some(NOW - 90_000), NOW, true, 0).expect("a long gap");
        let text = gap.describe();
        assert!(text.contains("restarted"), "{text}");
        assert!(text.contains("nothing was locked"), "{text}");
    }

    /// A `last_tick` in the future is the clock layer's business, and it reports it. Reporting a
    /// negative gap here as well would be two surfaces disagreeing about one fact.
    #[test]
    fn a_future_heartbeat_is_left_to_the_clock_layer() {
        assert_eq!(Downtime::detect(Some(NOW + 7200), NOW, false, 1), None);
    }

    #[test]
    fn the_sentence_counts_minutes_hours_and_days() {
        assert_eq!(human(600), "10 minutes");
        assert_eq!(human(7200), "2 hours 0 minutes");
        assert_eq!(human(90_000), "1 days 1 hours");
        // A gap just over the threshold still reads as a minute rather than as "0 minutes".
        assert_eq!(human(DOWNTIME_SECONDS), "5 minutes");
    }
}
