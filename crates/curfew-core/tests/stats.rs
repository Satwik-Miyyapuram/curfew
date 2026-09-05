//! What the numbers say a fortnight of blocking looked like.
//!
//! The awkward cases here are all the same case wearing different hats: a "day" is a local
//! calendar day, not 86400 seconds, and two sessions covering the same hour are one hour of the
//! user's life. Both are easy to get wrong in a way nobody notices until a streak is wrong.

use chrono::TimeZone;
use chrono_tz::{Tz, UTC};
use curfew_core::{summarize, SessionRecord, Timestamp};

const BERLIN: Tz = chrono_tz::Europe::Berlin;

/// A local wall-clock instant, as the user would have read it off their own phone.
fn at(tz: Tz, y: i32, m: u32, d: u32, h: u32, min: u32) -> Timestamp {
    tz.with_ymd_and_hms(y, m, d, h, min, 0)
        .single()
        .expect("a wall clock time that exists exactly once")
        .timestamp()
}

fn record(start: Timestamp, end: Option<Timestamp>) -> SessionRecord {
    SessionRecord { profile: "deep-work".into(), started_at: start, ended_at: end }
}

fn day(stats: &curfew_core::Stats, date: &str) -> curfew_core::DayStat {
    stats
        .days
        .iter()
        .find(|d| d.day == date)
        .unwrap_or_else(|| {
            panic!("{date} in {:?}", stats.days.iter().map(|d| &d.day).collect::<Vec<_>>())
        })
        .clone()
}

#[test]
fn no_history_is_a_full_window_of_zeroes() {
    // Not an empty list: a chart missing its empty days lies about the shape of the week.
    let stats = summarize(&[], at(UTC, 2026, 5, 20, 9, 0), UTC, 14);

    assert_eq!(stats.days.len(), 14);
    assert_eq!(stats.days.first().expect("a first day").day, "2026-05-07");
    assert_eq!(stats.days.last().expect("a last day").day, "2026-05-20");
    assert!(stats.days.iter().all(|d| d.blocked_seconds == 0 && d.sessions == 0));
    assert_eq!(stats.current_streak, 0);
    assert_eq!(stats.longest_streak, 0);
    assert_eq!(stats.total_blocked_seconds, 0);
    assert_eq!(stats.total_sessions, 0);
}

#[test]
fn one_session_lands_on_its_own_day() {
    let start = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats =
        summarize(&[record(start, Some(start + 3600))], at(BERLIN, 2026, 5, 20, 18, 0), BERLIN, 7);

    let today = day(&stats, "2026-05-20");
    assert_eq!(today.blocked_seconds, 3600);
    assert_eq!(today.sessions, 1);
    assert_eq!(stats.total_blocked_seconds, 3600);
    assert_eq!(stats.current_streak, 1);
}

#[test]
fn a_session_still_running_counts_up_to_now() {
    let start = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats = summarize(&[record(start, None)], start + 1800, BERLIN, 7);

    assert_eq!(day(&stats, "2026-05-20").blocked_seconds, 1800);
    assert_eq!(stats.current_streak, 1);
}

#[test]
fn a_session_that_started_this_second_still_counts_as_a_day() {
    // Otherwise the streak would break at the exact moment somebody started blocking.
    let start = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats = summarize(&[record(start, None)], start, BERLIN, 7);

    let today = day(&stats, "2026-05-20");
    assert_eq!(today.blocked_seconds, 0);
    assert_eq!(today.sessions, 1);
    assert_eq!(stats.current_streak, 1);
}

#[test]
fn overlapping_sessions_are_one_hour_but_two_sessions() {
    // Two profiles blocking 09:00-10:00 and 09:30-10:30 is ninety minutes of the user's day,
    // not two hours, and it is still two sessions they chose to start.
    let nine = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats = summarize(
        &[record(nine, Some(nine + 3600)), record(nine + 1800, Some(nine + 5400))],
        at(BERLIN, 2026, 5, 20, 18, 0),
        BERLIN,
        7,
    );

    let today = day(&stats, "2026-05-20");
    assert_eq!(today.blocked_seconds, 5400);
    assert_eq!(today.sessions, 2);
    assert_eq!(stats.total_blocked_seconds, 5400);
}

#[test]
fn one_session_inside_another_adds_nothing() {
    let nine = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats = summarize(
        &[record(nine, Some(nine + 7200)), record(nine + 600, Some(nine + 1200))],
        at(BERLIN, 2026, 5, 20, 18, 0),
        BERLIN,
        7,
    );

    assert_eq!(day(&stats, "2026-05-20").blocked_seconds, 7200);
    assert_eq!(day(&stats, "2026-05-20").sessions, 2);
}

#[test]
fn touching_sessions_do_not_double_count_the_boundary() {
    let nine = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats = summarize(
        &[record(nine, Some(nine + 3600)), record(nine + 3600, Some(nine + 7200))],
        at(BERLIN, 2026, 5, 20, 18, 0),
        BERLIN,
        7,
    );

    assert_eq!(day(&stats, "2026-05-20").blocked_seconds, 7200);
}

#[test]
fn a_session_over_midnight_touches_two_days() {
    let start = at(BERLIN, 2026, 5, 19, 23, 0);
    let stats = summarize(
        &[record(start, Some(at(BERLIN, 2026, 5, 20, 1, 0)))],
        at(BERLIN, 2026, 5, 20, 9, 0),
        BERLIN,
        7,
    );

    assert_eq!(day(&stats, "2026-05-19").blocked_seconds, 3600);
    assert_eq!(day(&stats, "2026-05-20").blocked_seconds, 3600);
    assert_eq!(day(&stats, "2026-05-19").sessions, 1);
    assert_eq!(day(&stats, "2026-05-20").sessions, 1);
    // The session is counted once per day it touched, so the totals are the days' totals.
    assert_eq!(stats.total_sessions, 2);
    assert_eq!(stats.current_streak, 2);
}

#[test]
fn a_spring_forward_day_is_twenty_three_hours() {
    // 2026-03-29, Berlin: 02:00 becomes 03:00, so the local day is 82800 seconds. A summary that
    // divided by 86400 would report the day as unfinished when it was fully blocked.
    let stats = summarize(
        &[record(at(BERLIN, 2026, 3, 28, 0, 0), Some(at(BERLIN, 2026, 3, 30, 0, 0)))],
        at(BERLIN, 2026, 3, 30, 9, 0),
        BERLIN,
        7,
    );

    assert_eq!(day(&stats, "2026-03-28").blocked_seconds, 86400);
    assert_eq!(day(&stats, "2026-03-29").blocked_seconds, 82800);
}

#[test]
fn an_autumn_back_day_is_twenty_five_hours() {
    // 2026-10-25, Berlin: 03:00 becomes 02:00.
    let stats = summarize(
        &[record(at(BERLIN, 2026, 10, 24, 0, 0), Some(at(BERLIN, 2026, 10, 26, 0, 0)))],
        at(BERLIN, 2026, 10, 26, 9, 0),
        BERLIN,
        7,
    );

    assert_eq!(day(&stats, "2026-10-25").blocked_seconds, 90000);
}

#[test]
fn the_same_moment_falls_on_different_days_in_different_zones() {
    // 00:30 on the 21st in Berlin is 22:30 on the 20th in UTC: the same half hour of blocking
    // belongs to a different day depending on where the user is standing.
    let start = at(BERLIN, 2026, 5, 21, 0, 30);
    let records = [record(start, Some(start + 1800))];

    let berlin = summarize(&records, start + 43_200, BERLIN, 7);
    assert_eq!(day(&berlin, "2026-05-21").blocked_seconds, 1800);
    assert_eq!(day(&berlin, "2026-05-20").blocked_seconds, 0);

    let utc = summarize(&records, start + 43_200, UTC, 7);
    assert_eq!(day(&utc, "2026-05-20").blocked_seconds, 1800);
    assert_eq!(day(&utc, "2026-05-21").blocked_seconds, 0);
}

#[test]
fn a_gap_breaks_the_streak_and_the_longest_survives_it() {
    let now = at(BERLIN, 2026, 5, 20, 9, 0);
    let d = |offset: i64| {
        let s = at(BERLIN, 2026, 5, 20, 12, 0) - offset * 86_400;
        record(s, Some(s + 3600))
    };
    // Days 6,5,4 blocked, day 3 missed, days 2,1,0 blocked.
    let records: Vec<_> = [6, 5, 4, 2, 1, 0].into_iter().map(d).collect();

    let stats = summarize(&records, now, BERLIN, 7);
    assert_eq!(stats.longest_streak, 3);
    assert_eq!(stats.current_streak, 3);
    assert_eq!(stats.total_sessions, 6);
}

#[test]
fn the_current_streak_may_end_yesterday() {
    // At 09:00 you have not failed a day you have not finished.
    let yesterday = at(BERLIN, 2026, 5, 19, 20, 0);
    let before = at(BERLIN, 2026, 5, 18, 20, 0);
    let stats = summarize(
        &[record(before, Some(before + 3600)), record(yesterday, Some(yesterday + 3600))],
        at(BERLIN, 2026, 5, 20, 9, 0),
        BERLIN,
        7,
    );

    assert_eq!(day(&stats, "2026-05-20").sessions, 0);
    assert_eq!(stats.current_streak, 2);
    assert_eq!(stats.longest_streak, 2);
}

#[test]
fn a_streak_that_ended_the_day_before_yesterday_is_over() {
    let then = at(BERLIN, 2026, 5, 18, 20, 0);
    let stats =
        summarize(&[record(then, Some(then + 3600))], at(BERLIN, 2026, 5, 20, 9, 0), BERLIN, 7);

    assert_eq!(stats.current_streak, 0);
    assert_eq!(stats.longest_streak, 1);
}

#[test]
fn history_older_than_the_window_is_left_out() {
    let old = at(BERLIN, 2026, 4, 1, 9, 0);
    let stats =
        summarize(&[record(old, Some(old + 3600))], at(BERLIN, 2026, 5, 20, 9, 0), BERLIN, 7);

    assert_eq!(stats.days.len(), 7);
    assert_eq!(stats.total_sessions, 0);
    assert_eq!(stats.total_blocked_seconds, 0);
}

#[test]
fn a_session_running_since_before_the_window_only_counts_inside_it() {
    let stats = summarize(
        &[record(at(BERLIN, 2026, 5, 1, 0, 0), None)],
        at(BERLIN, 2026, 5, 20, 12, 0),
        BERLIN,
        3,
    );

    assert_eq!(stats.days.len(), 3);
    assert_eq!(day(&stats, "2026-05-18").blocked_seconds, 86400);
    assert_eq!(day(&stats, "2026-05-20").blocked_seconds, 43200);
    assert_eq!(stats.current_streak, 3);
}

#[test]
fn a_clock_that_moved_backwards_is_not_a_negative_session() {
    let start = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats =
        summarize(&[record(start, Some(start - 7200))], at(BERLIN, 2026, 5, 20, 12, 0), BERLIN, 7);

    let today = day(&stats, "2026-05-20");
    assert_eq!(today.blocked_seconds, 0);
    assert_eq!(today.sessions, 1);
}

#[test]
fn a_zero_day_window_still_reports_today() {
    let stats = summarize(&[], at(BERLIN, 2026, 5, 20, 9, 0), BERLIN, 0);
    assert_eq!(stats.days.len(), 1);
    assert_eq!(stats.days[0].day, "2026-05-20");
}

#[test]
fn the_csv_has_a_header_and_a_row_per_day() {
    let start = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats =
        summarize(&[record(start, Some(start + 3600))], at(BERLIN, 2026, 5, 20, 12, 0), BERLIN, 3);

    assert_eq!(
        stats.to_csv(),
        "day,blocked_seconds,sessions\n\
         2026-05-18,0,0\n\
         2026-05-19,0,0\n\
         2026-05-20,3600,1\n"
    );
}

#[test]
fn the_summary_round_trips_through_json() {
    // Both apps read this over FFI, so a field that does not survive serde is a field the UI
    // silently shows as zero.
    let start = at(BERLIN, 2026, 5, 20, 9, 0);
    let stats =
        summarize(&[record(start, Some(start + 3600))], at(BERLIN, 2026, 5, 20, 12, 0), BERLIN, 3);

    let json = serde_json::to_string(&stats).expect("stats serialize");
    let back: curfew_core::Stats = serde_json::from_str(&json).expect("stats deserialize");
    assert_eq!(back, stats);
}
