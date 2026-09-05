//! What the blocking has added up to: days with a session on them, streaks, and the export.
//!
//! This lives in the core rather than in either app for the usual reason — a streak the phone and
//! the PC disagree about is worse than no streak at all — but also for a subtler one. A "day" is a
//! local-calendar day in the user's own timezone, which is the same awkward question the budget
//! refill windows answer, with the same two-days-a-year edge cases. Answering it twice would mean
//! answering it differently.
//!
//! What is *not* here is any notion of a goal, a score, or a comparison with other people. A
//! streak is a count of days the user did the thing they said they wanted to do; making it a thing
//! to lose would turn a self-control tool into a second compulsion, which is the failure mode this
//! whole project exists to avoid.

use crate::Timestamp;
use chrono::{Duration, NaiveDate, TimeZone};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};

/// One session that happened, reduced to what a statistic needs.
///
/// Deliberately not [`crate::Session`]: that carries locks, sources and release state, none of
/// which survives into a number, and a history table that had to store all of it would be a much
/// larger thing to keep for thirty days.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SessionRecord {
    pub profile: String,
    pub started_at: Timestamp,
    /// When it ended, or `None` for one still running — which is counted up to `now`, because a
    /// day you are in the middle of blocking is a day you blocked.
    #[serde(default)]
    pub ended_at: Option<Timestamp>,
}

/// One local day.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DayStat {
    /// `YYYY-MM-DD` in the user's timezone. A string because that is what both UIs display and
    /// what the export writes, and re-deriving it on each side would re-open the timezone question.
    pub day: String,
    /// Seconds of that day covered by a session. Overlapping sessions are counted once: two
    /// profiles blocking the same hour is one hour of the user's day, not two.
    pub blocked_seconds: u32,
    /// How many sessions touched the day. A session over midnight touches two.
    pub sessions: u32,
}

/// The summary both apps show.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Stats {
    /// Every day in the requested window, oldest first, including the empty ones — a chart with
    /// the empty days missing is a chart that lies about the shape of the week.
    pub days: Vec<DayStat>,
    /// Days in a row up to and including today. Today not having started yet does not break it:
    /// see [`summarize`].
    pub current_streak: u32,
    pub longest_streak: u32,
    pub total_blocked_seconds: u64,
    pub total_sessions: u32,
}

/// Summarize `records` over the `days` local days ending with the day containing `now`.
///
/// A day counts toward a streak when any session covered part of it. The current streak is allowed
/// to end yesterday rather than today, because at 9am you have not failed a day you have not
/// finished — breaking someone's streak at midnight for a day still in progress would be both
/// wrong and cruel.
pub fn summarize(records: &[SessionRecord], now: Timestamp, tz: Tz, days: u32) -> Stats {
    let today = local_date(now, tz);
    let span = days.max(1) as i64;
    let first = today - Duration::days(span - 1);

    let mut seconds = vec![0u32; span as usize];
    let mut sessions = vec![0u32; span as usize];

    // Every session as a half-open interval, clamped to something sane. A record whose end is
    // before its start is a clock that moved, not a negative session.
    let mut spans: Vec<(Timestamp, Timestamp)> = records
        .iter()
        .map(|r| (r.started_at, r.ended_at.unwrap_or(now).max(r.started_at)))
        .collect();

    // The session count is per record: two profiles blocking the same hour is two sessions the
    // user started, even though it is one hour of their day.
    for &(start, end) in &spans {
        for_each_local_day(start, end, first, today, tz, |index, _| {
            sessions[index] += 1;
        });
    }

    // The seconds are not. Merging first is what stops two overlapping sessions from reporting
    // ninety minutes of a sixty-minute hour.
    spans.sort_unstable();
    let mut merged: Vec<(Timestamp, Timestamp)> = Vec::with_capacity(spans.len());
    for span in spans {
        match merged.last_mut() {
            Some(last) if span.0 <= last.1 => last.1 = last.1.max(span.1),
            _ => merged.push(span),
        }
    }
    for (start, end) in merged {
        for_each_local_day(start, end, first, today, tz, |index, overlap| {
            seconds[index] = seconds[index].saturating_add(overlap);
        });
    }

    let buckets: Vec<(u32, u32)> = seconds.into_iter().zip(sessions).collect();

    let days_out: Vec<DayStat> = buckets
        .iter()
        .enumerate()
        .map(|(i, (seconds, sessions))| DayStat {
            day: (first + Duration::days(i as i64)).to_string(),
            blocked_seconds: *seconds,
            sessions: *sessions,
        })
        .collect();

    let hit: Vec<bool> = days_out.iter().map(|d| d.sessions > 0).collect();

    let mut longest = 0u32;
    let mut run = 0u32;
    for &h in &hit {
        run = if h { run + 1 } else { 0 };
        longest = longest.max(run);
    }

    // Count back from today, and if today is still empty, from yesterday.
    let mut current = 0u32;
    let mut i = hit.len();
    if i > 0 && !hit[i - 1] {
        i -= 1;
    }
    while i > 0 && hit[i - 1] {
        current += 1;
        i -= 1;
    }

    Stats {
        total_blocked_seconds: days_out.iter().map(|d| d.blocked_seconds as u64).sum(),
        total_sessions: days_out.iter().map(|d| d.sessions).sum(),
        current_streak: current,
        longest_streak: longest,
        days: days_out,
    }
}

impl Stats {
    /// The days as CSV, one row per day, with a header.
    ///
    /// CSV because it opens in the spreadsheet the user already has, and because an export nobody
    /// can read is not really an export. No quoting logic is needed and none is written: every
    /// field here is a date or a number, and if that ever stops being true this has to grow a
    /// proper writer rather than a hopeful `format!`.
    pub fn to_csv(&self) -> String {
        let mut out = String::from("day,blocked_seconds,sessions\n");
        for d in &self.days {
            out.push_str(&format!("{},{},{}\n", d.day, d.blocked_seconds, d.sessions));
        }
        out
    }
}

/// Call `f` once for each local day between `first` and `today` that `start..end` touches, with
/// the day's index and how many seconds of it are covered.
///
/// The days are walked rather than divided out of the length, because a local day is not always
/// 86400 seconds long and on the two days a year it is not, dividing gets the answer wrong.
fn for_each_local_day(
    start: Timestamp,
    end: Timestamp,
    first: NaiveDate,
    today: NaiveDate,
    tz: Tz,
    mut f: impl FnMut(usize, u32),
) {
    let mut date = local_date(start, tz).max(first);
    let last = local_date(end, tz).min(today);
    while date <= last {
        let (day_from, day_to) = (day_start(date, tz), day_start(date + Duration::days(1), tz));
        let overlap = end.min(day_to) - start.max(day_from);
        // A session that started this second has covered no time yet, and still happened: the day
        // it began on is a day the user blocked, and a streak that ignored it would break at the
        // moment somebody started a session.
        if overlap > 0 || (day_from <= start && start < day_to) {
            f((date - first).num_days() as usize, overlap.max(0) as u32);
        }
        date += Duration::days(1);
    }
}

fn local_date(at: Timestamp, tz: Tz) -> NaiveDate {
    tz.timestamp_opt(at, 0)
        .earliest()
        .map(|d| d.date_naive())
        .unwrap_or_else(|| NaiveDate::from_ymd_opt(1970, 1, 1).expect("epoch is a date"))
}

/// Midnight starting `date`, in `tz`. On a spring-forward day midnight may not exist locally; the
/// next instant that does is the honest answer, and it keeps the day contiguous with the last one.
fn day_start(date: NaiveDate, tz: Tz) -> Timestamp {
    let midnight = date.and_hms_opt(0, 0, 0).expect("midnight is a time");
    tz.from_local_datetime(&midnight)
        .earliest()
        .or_else(|| {
            (0..4).find_map(|h| {
                let t = date.and_hms_opt(h, 0, 0)?;
                tz.from_local_datetime(&t).earliest()
            })
        })
        .map(|d| d.timestamp())
        .unwrap_or(0)
}
