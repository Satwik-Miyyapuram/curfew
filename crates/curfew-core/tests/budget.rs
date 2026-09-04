//! Refill windows and ledgers. The interesting cases are all calendar ones: "resets at 4am" has to
//! mean 4am where the person is, on the two days a year when 4am is ambiguous or does not happen,
//! and in the months that have no 31st.

use chrono::TimeZone;
use chrono_tz::Tz;
use curfew_core::budget::{Consumption, Launches, Refill};
use curfew_core::Timestamp;

const LONDON: Tz = chrono_tz::Europe::London;
const NY: Tz = chrono_tz::America::New_York;

/// A UTC instant from a local wall clock in `tz`, so the expectations below read as wall times.
fn local(tz: Tz, y: i32, m: u32, d: u32, h: u32, min: u32) -> Timestamp {
    tz.with_ymd_and_hms(y, m, d, h, min, 0).earliest().expect("test instants exist").timestamp()
}

// --- daily ------------------------------------------------------------------------------------

#[test]
fn a_daily_window_opens_at_the_local_reset_time_earlier_the_same_day() {
    let refill = Refill::Daily { at_minute: 4 * 60 };
    let now = local(LONDON, 2026, 9, 4, 12, 0);
    assert_eq!(refill.window_start(now, LONDON), Some(local(LONDON, 2026, 9, 4, 4, 0)));
}

#[test]
fn before_the_reset_the_window_is_still_yesterdays() {
    let refill = Refill::Daily { at_minute: 4 * 60 };
    let now = local(LONDON, 2026, 9, 4, 3, 0);
    assert_eq!(refill.window_start(now, LONDON), Some(local(LONDON, 2026, 9, 3, 4, 0)));
}

#[test]
fn the_reset_instant_itself_belongs_to_the_new_window() {
    let refill = Refill::Daily { at_minute: 4 * 60 };
    let reset = local(LONDON, 2026, 9, 4, 4, 0);
    assert_eq!(refill.window_start(reset, LONDON), Some(reset));
}

/// Timezone, not UTC offset: the same instant gives different windows in different places.
#[test]
fn the_window_follows_the_configured_timezone() {
    let refill = Refill::Daily { at_minute: 4 * 60 };
    let now = local(LONDON, 2026, 9, 4, 12, 0);
    assert_ne!(refill.window_start(now, LONDON), refill.window_start(now, NY));
}

#[test]
fn a_midnight_reset_is_expressible() {
    let refill = Refill::Daily { at_minute: 0 };
    let now = local(LONDON, 2026, 9, 4, 0, 30);
    assert_eq!(refill.window_start(now, LONDON), Some(local(LONDON, 2026, 9, 4, 0, 0)));
}

// --- DST --------------------------------------------------------------------------------------

/// 2026-03-29, London jumps 01:00 -> 02:00. A 01:30 reset never happens on that date; the window
/// must open at the first instant that does exist rather than panicking or skipping the day.
#[test]
fn a_reset_time_that_does_not_exist_opens_at_the_next_instant_that_does() {
    let refill = Refill::Daily { at_minute: 90 };
    let now = local(LONDON, 2026, 3, 29, 12, 0);
    let start = refill.window_start(now, LONDON).expect("daily always has a window");
    assert_eq!(start, local(LONDON, 2026, 3, 29, 2, 0), "the spring-forward hour is skipped");
    assert!(start <= now);
}

/// 2026-10-25, London falls back 02:00 -> 01:00, so 01:30 happens twice. Taking the earlier one
/// gives the allowance back marginally early, which is the safe direction to err.
#[test]
fn an_ambiguous_reset_time_takes_the_earlier_occurrence() {
    let refill = Refill::Daily { at_minute: 90 };
    let now = local(LONDON, 2026, 10, 25, 12, 0);
    let start = refill.window_start(now, LONDON).expect("daily always has a window");
    let later = LONDON.with_ymd_and_hms(2026, 10, 25, 1, 30, 0).latest().unwrap().timestamp();
    assert!(start < later, "the first 01:30, not the second");
    assert!(start <= now);
}

/// The property that matters more than either specific date: a window never opens in the future.
#[test]
fn a_window_never_opens_after_now_across_a_whole_dst_year() {
    let refills = [
        Refill::Daily { at_minute: 90 },
        Refill::Weekly { weekday: 0, at_minute: 90 },
        Refill::Monthly { day: 31, at_minute: 90 },
        Refill::Rolling { seconds: 3600 },
    ];
    let mut now = local(LONDON, 2026, 1, 1, 0, 0);
    let end = local(LONDON, 2027, 1, 1, 0, 0);
    while now < end {
        for refill in &refills {
            if let Some(start) = refill.window_start(now, LONDON) {
                assert!(start <= now, "{refill:?} opened in the future at {now}");
            }
        }
        now += 7 * 3600 + 137; // a stride that walks every hour of the clock
    }
}

// --- weekly and monthly -------------------------------------------------------------------------

#[test]
fn a_weekly_window_opens_on_the_named_weekday() {
    // 2026-09-04 is a Friday; weekday 0 is Monday.
    let refill = Refill::Weekly { weekday: 0, at_minute: 4 * 60 };
    let now = local(LONDON, 2026, 9, 4, 12, 0);
    assert_eq!(refill.window_start(now, LONDON), Some(local(LONDON, 2026, 8, 31, 4, 0)));
}

#[test]
fn a_weekly_reset_later_today_falls_back_a_full_week() {
    let refill = Refill::Weekly { weekday: 4, at_minute: 20 * 60 }; // Friday 20:00
    let now = local(LONDON, 2026, 9, 4, 12, 0); // Friday noon
    assert_eq!(refill.window_start(now, LONDON), Some(local(LONDON, 2026, 8, 28, 20, 0)));
}

#[test]
fn a_monthly_reset_on_the_31st_clamps_to_the_end_of_a_short_month() {
    let refill = Refill::Monthly { day: 31, at_minute: 4 * 60 };
    let now = local(LONDON, 2026, 9, 30, 12, 0); // September has 30 days
    assert_eq!(refill.window_start(now, LONDON), Some(local(LONDON, 2026, 9, 30, 4, 0)));
}

#[test]
fn a_monthly_reset_walks_back_into_the_previous_year() {
    let refill = Refill::Monthly { day: 15, at_minute: 4 * 60 };
    let now = local(LONDON, 2026, 1, 3, 12, 0);
    assert_eq!(refill.window_start(now, LONDON), Some(local(LONDON, 2025, 12, 15, 4, 0)));
}

#[test]
fn february_29_is_reachable_in_a_leap_year_and_clamped_otherwise() {
    let refill = Refill::Monthly { day: 31, at_minute: 0 };
    let leap = local(LONDON, 2028, 2, 29, 12, 0);
    assert_eq!(refill.window_start(leap, LONDON), Some(local(LONDON, 2028, 2, 29, 0, 0)));
    let common = local(LONDON, 2026, 2, 28, 12, 0);
    assert_eq!(refill.window_start(common, LONDON), Some(local(LONDON, 2026, 2, 28, 0, 0)));
}

// --- rolling and never --------------------------------------------------------------------------

#[test]
fn a_rolling_window_is_exactly_that_long() {
    let refill = Refill::Rolling { seconds: 3600 };
    assert_eq!(refill.window_start(10_000, LONDON), Some(6_400));
}

#[test]
fn never_has_no_window_so_everything_ever_recorded_counts() {
    assert_eq!(Refill::Never.window_start(10_000, LONDON), None);
    let mut c = Consumption::default();
    c.record(0, 60);
    c.record(9_000, 60);
    assert_eq!(c.used_since(Refill::Never.window_start(10_000, LONDON)), 120);
}

#[test]
fn the_default_refill_is_a_daily_four_am_reset() {
    assert_eq!(Refill::default(), Refill::Daily { at_minute: 4 * 60 });
}

// --- ledgers ------------------------------------------------------------------------------------

#[test]
fn consumption_counts_only_rollups_inside_the_window() {
    let mut c = Consumption::default();
    c.record(100, 30);
    c.record(200, 40);
    assert_eq!(c.used_since(Some(150)), 40);
    assert_eq!(c.used_since(Some(200)), 40, "the boundary instant is inside the window");
    assert_eq!(c.used_since(Some(201)), 0);
    assert_eq!(c.used_since(None), 70);
}

/// A corrupt or hostile op-log must not wrap the total around into a small number and hand back an
/// allowance that was already spent.
#[test]
fn a_ledger_that_overflows_saturates_rather_than_wrapping() {
    let mut c = Consumption::default();
    c.record(0, u32::MAX);
    c.record(1, u32::MAX);
    assert_eq!(c.used_since(None), u32::MAX);
}

#[test]
fn pruning_drops_only_what_is_older_than_the_cutoff() {
    let mut c = Consumption::default();
    c.record(100, 10);
    c.record(300, 10);
    c.prune(200);
    assert_eq!(c.rollups.len(), 1);
    assert_eq!(c.used_since(None), 10);
}

#[test]
fn launches_count_and_prune_the_same_way() {
    let mut l = Launches::default();
    l.record(100);
    l.record(200);
    l.record(300);
    assert_eq!(l.count_since(Some(200)), 2);
    assert_eq!(l.count_since(None), 3);
    l.prune(250);
    assert_eq!(l.count_since(None), 1);
}

#[test]
fn an_empty_ledger_has_spent_nothing() {
    assert_eq!(Consumption::default().used_since(Some(0)), 0);
    assert_eq!(Launches::default().count_since(None), 0);
}
