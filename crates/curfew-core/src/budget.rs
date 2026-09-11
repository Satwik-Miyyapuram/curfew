//! Budgets, refills and launch limits.
//!
//! Consumption arrives as periodic rollups from the op-log (GAPS C4), never as one event per
//! second, so a year of use stays small enough to sit in a shared folder. A budget is spent when
//! the rollups inside the current refill window add up to the allowance.
//!
//! Refill windows are computed in a named timezone rather than in UTC, because "resets at 4am" has
//! to mean 4am where the person is, including on the two days a year when that is ambiguous or
//! impossible.

use crate::Timestamp;
use chrono::{Datelike, Duration, NaiveDate, TimeZone};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};

/// When a budget starts over.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields, tag = "kind", rename_all = "snake_case")]
pub enum Refill {
    /// One allowance, ever. Spend it and it is gone until the rule changes.
    Never,
    /// A sliding window: only the last `seconds` of use count.
    Rolling { seconds: u32 },
    /// Resets `at_minute` minutes after local midnight, every day.
    Daily { at_minute: u32 },
    /// Resets weekly, on `weekday` (0 = Monday) at `at_minute`.
    Weekly { weekday: u8, at_minute: u32 },
    /// Resets monthly, on `day` (1-31, clamped to the length of the month) at `at_minute`.
    Monthly { day: u8, at_minute: u32 },
}

impl Default for Refill {
    fn default() -> Self {
        Refill::Daily { at_minute: 4 * 60 }
    }
}

impl Refill {
    /// The instant the current window opened, or `None` for [`Refill::Never`] (which has no
    /// window: everything ever recorded counts).
    ///
    /// Always `<= now`. Where a local reset time does not exist because the clock jumped forward,
    /// the first instant that does exist is used; where it happens twice because the clock went
    /// back, the earlier one is used. Both choices err towards opening the window *sooner*, which
    /// can only give the user their allowance back marginally early -- never take it away
    /// unexpectedly, which is the failure that would feel like a bug.
    pub fn window_start(&self, now: Timestamp, tz: Tz) -> Option<Timestamp> {
        match *self {
            Refill::Never => None,
            Refill::Rolling { seconds } => Some(now - seconds as i64),
            Refill::Daily { at_minute } => {
                let today = local_date(now, tz);
                Some(walk_back(today, at_minute, tz, now, |d| d - Duration::days(1)))
            }
            Refill::Weekly { weekday, at_minute } => {
                let today = local_date(now, tz);
                let target = weekday.min(6) as i64;
                let current = today.weekday().num_days_from_monday() as i64;
                // The most recent occurrence of that weekday, today included.
                let back = (current - target).rem_euclid(7);
                let anchor = today - Duration::days(back);
                Some(walk_back(anchor, at_minute, tz, now, |d| d - Duration::days(7)))
            }
            Refill::Monthly { day, at_minute } => {
                let today = local_date(now, tz);
                let anchor = clamp_day(today.year(), today.month(), day);
                Some(walk_back(anchor, at_minute, tz, now, |d| {
                    let (y, m) =
                        if d.month() == 1 { (d.year() - 1, 12) } else { (d.year(), d.month() - 1) };
                    clamp_day(y, m, day)
                }))
            }
        }
    }
}

/// Step the anchor date back until its reset instant is at or before `now`.
fn walk_back(
    mut date: NaiveDate,
    at_minute: u32,
    tz: Tz,
    now: Timestamp,
    prev: impl Fn(NaiveDate) -> NaiveDate,
) -> Timestamp {
    // Bounded: each step moves at least a day back, and the first candidate is at most one period
    // in the future, so two iterations always suffice. The third is belt and braces.
    for _ in 0..3 {
        let at = local_instant(date, at_minute, tz);
        if at <= now {
            return at;
        }
        date = prev(date);
    }
    local_instant(date, at_minute, tz)
}

fn local_date(now: Timestamp, tz: Tz) -> NaiveDate {
    tz.timestamp_opt(now, 0).earliest().map(|d| d.date_naive()).unwrap_or_else(|| {
        // Only reachable for timestamps outside chrono's range; clamp rather than panic.
        NaiveDate::from_ymd_opt(1970, 1, 1).expect("epoch is a date")
    })
}

fn clamp_day(year: i32, month: u32, day: u8) -> NaiveDate {
    let day = day.clamp(1, 31) as u32;
    for d in (1..=day).rev() {
        if let Some(date) = NaiveDate::from_ymd_opt(year, month, d) {
            return date;
        }
    }
    NaiveDate::from_ymd_opt(year, month, 1).expect("every month has a first")
}

/// The UTC instant of `at_minute` minutes after local midnight on `date`, resolving DST folds.
fn local_instant(date: NaiveDate, at_minute: u32, tz: Tz) -> Timestamp {
    let at_minute = at_minute.min(24 * 60 - 1);
    let naive = date
        .and_hms_opt(at_minute / 60, at_minute % 60, 0)
        .expect("at_minute is clamped into a valid time");

    // Ambiguous (clock went back): take the earlier of the two. Nonexistent (clock jumped
    // forward): step forward a minute at a time until the wall clock exists again.
    if let Some(dt) = tz.from_local_datetime(&naive).earliest() {
        return dt.timestamp();
    }
    for extra in 1..=180 {
        let candidate = naive + Duration::minutes(extra);
        if let Some(dt) = tz.from_local_datetime(&candidate).earliest() {
            return dt.timestamp();
        }
    }
    // No timezone on earth skips three hours; fall back to treating it as UTC rather than panic.
    naive.and_utc().timestamp()
}

/// Recorded use of one target, as rollups from the op-log.
///
/// Each entry says "this many seconds were used, in the minute starting at this instant". A rollup
/// straddling a refill boundary is attributed to the window its *start* falls in, which can
/// mis-assign at most one rollup interval. That is deliberate: the alternative is splitting
/// rollups, which would make the same log merge differently on different devices.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Consumption {
    pub rollups: Vec<Rollup>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Rollup {
    pub at: Timestamp,
    pub seconds: u32,
}

impl Consumption {
    pub fn record(&mut self, at: Timestamp, seconds: u32) {
        self.rollups.push(Rollup { at, seconds });
    }

    /// Seconds used since `from` (inclusive), or ever if `from` is `None`. Saturating, so a
    /// corrupt or hostile log cannot overflow the total into a small number and hand back an
    /// allowance.
    pub fn used_since(&self, from: Option<Timestamp>) -> u32 {
        self.rollups
            .iter()
            .filter(|r| from.is_none_or(|f| r.at >= f))
            .fold(0u32, |acc, r| acc.saturating_add(r.seconds))
    }

    /// Drop rollups older than `before`; used by op-log compaction.
    pub fn prune(&mut self, before: Timestamp) {
        self.rollups.retain(|r| r.at >= before);
    }
}

/// Times a target was opened, for launch limits ("open Instagram at most 5 times a day").
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Launches {
    pub at: Vec<Timestamp>,
}

impl Launches {
    pub fn record(&mut self, at: Timestamp) {
        self.at.push(at);
    }

    pub fn count_since(&self, from: Option<Timestamp>) -> u32 {
        self.at.iter().filter(|t| from.is_none_or(|f| **t >= f)).count() as u32
    }

    pub fn prune(&mut self, before: Timestamp) {
        self.at.retain(|t| *t >= before);
    }
}
