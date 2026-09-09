//! Schedules: recurring windows and calendar events becoming activations.

use chrono::TimeZone;
use chrono_tz::Tz;
use curfew_core::schedule::{
    active_at, next_change_after, upcoming, ActivationSource, CalendarEvent, CalendarSchedule,
    EventMatcher, WeeklySchedule,
};
use curfew_core::{Lock, Timestamp};

const LONDON: Tz = chrono_tz::Europe::London;

fn local(y: i32, m: u32, d: u32, h: u32, min: u32) -> Timestamp {
    LONDON.with_ymd_and_hms(y, m, d, h, min, 0).earliest().expect("test instants exist").timestamp()
}

fn weekday_mornings() -> WeeklySchedule {
    WeeklySchedule {
        id: "mornings".into(),
        profile: "deep-work".into(),
        days: vec![0, 1, 2, 3, 4],
        start_minute: 9 * 60,
        end_minute: 12 * 60,
        locks: vec![Lock::Timer],
        enabled: true,
    }
}

fn overnight() -> WeeklySchedule {
    WeeklySchedule {
        id: "overnight".into(),
        profile: "sleep".into(),
        days: vec![],
        start_minute: 23 * 60,
        end_minute: 7 * 60,
        locks: vec![Lock::DeviceCredential],
        enabled: true,
    }
}

fn event(id: &str, title: &str, start: Timestamp, end: Timestamp) -> CalendarEvent {
    CalendarEvent {
        id: id.into(),
        title: title.into(),
        calendar: "Work".into(),
        location: String::new(),
        start,
        end,
        all_day: false,
        busy: true,
        categories: Vec::new(),
    }
}

// --- weekly windows ----------------------------------------------------------------------------

#[test]
fn a_weekly_window_is_open_between_its_ends_and_closed_outside() {
    let w = weekday_mornings();
    // 2026-09-04 is a Friday.
    assert!(w.active_at(local(2026, 9, 4, 10, 0), LONDON).is_some());
    assert!(w.active_at(local(2026, 9, 4, 8, 59), LONDON).is_none());
    assert!(w.active_at(local(2026, 9, 4, 12, 1), LONDON).is_none());
}

/// Half-open: the start instant is inside the window, the end instant is not. Otherwise two
/// adjacent windows would both be open for one second, and a session would outlive its schedule.
#[test]
fn the_window_includes_its_start_and_excludes_its_end() {
    let w = weekday_mornings();
    assert!(w.active_at(local(2026, 9, 4, 9, 0), LONDON).is_some());
    assert!(w.active_at(local(2026, 9, 4, 12, 0), LONDON).is_none());
}

#[test]
fn a_weekly_window_does_not_run_on_a_day_it_is_not_listed_for() {
    let w = weekday_mornings();
    assert!(w.active_at(local(2026, 9, 5, 10, 0), LONDON).is_none(), "Saturday");
    assert!(w.active_at(local(2026, 9, 6, 10, 0), LONDON).is_none(), "Sunday");
}

#[test]
fn an_empty_day_list_means_every_day() {
    let w = overnight();
    assert!(w.active_at(local(2026, 9, 5, 23, 30), LONDON).is_some(), "Saturday night");
}

/// The shape every "no phone after 11pm" rule has: the window ends on the following day.
#[test]
fn a_window_that_crosses_midnight_stays_open_into_the_next_morning() {
    let w = overnight();
    assert!(w.active_at(local(2026, 9, 4, 23, 30), LONDON).is_some());
    assert!(w.active_at(local(2026, 9, 5, 2, 0), LONDON).is_some(), "still the same window");
    assert!(w.active_at(local(2026, 9, 5, 7, 0), LONDON).is_none(), "closes at 07:00");
    assert!(w.active_at(local(2026, 9, 5, 12, 0), LONDON).is_none());
}

#[test]
fn an_activation_carries_its_schedules_locks_and_ends_when_the_window_does() {
    let a = weekday_mornings().active_at(local(2026, 9, 4, 10, 0), LONDON).unwrap();
    assert_eq!(a.profile, "deep-work");
    assert_eq!(a.source, ActivationSource::Weekly { schedule: "mornings".into() });
    assert_eq!(a.end, local(2026, 9, 4, 12, 0));
    let lock = a.lock();
    assert!(lock.conditions.contains(&Lock::Timer));
    assert_eq!(lock.ends_at, Some(local(2026, 9, 4, 12, 0)));
}

/// A schedule must not fail to start because the clock skipped its start time. London's
/// spring-forward on 2026-03-29 removes 01:00-02:00 entirely.
#[test]
fn a_window_starting_in_the_lost_hour_still_opens() {
    let w = WeeklySchedule {
        id: "lost".into(),
        profile: "sleep".into(),
        days: vec![],
        start_minute: 90, // 01:30, which does not exist that day
        end_minute: 4 * 60,
        locks: vec![],
        enabled: true,
    };
    assert!(w.active_at(local(2026, 3, 29, 3, 0), LONDON).is_some());
}

// --- calendar ----------------------------------------------------------------------------------

fn focus_schedule() -> CalendarSchedule {
    CalendarSchedule {
        id: "focus".into(),
        profile: "deep-work".into(),
        matcher: EventMatcher {
            title: Some("*focus*".into()),
            calendar: Some("Work".into()),
            location: None,
            busy_only: true,
            all_day: None,
            ..EventMatcher::default()
        },
        pad_before_seconds: 300,
        pad_after_seconds: 0,
        locks: vec![Lock::Confirm],
        enabled: true,
    }
}

#[test]
fn a_matching_event_becomes_an_activation_with_its_padding_applied() {
    let start = local(2026, 9, 4, 14, 0);
    let events = [event("e1", "Deep Focus block", start, start + 3600)];
    let a = &focus_schedule().activations(&events)[0];
    assert_eq!(a.start, start - 300, "padding starts the lock before the meeting does");
    assert_eq!(a.profile, "deep-work");
    assert_eq!(
        a.source,
        ActivationSource::Calendar { schedule: "focus".into(), event: "e1".into() }
    );
}

#[test]
fn a_non_matching_event_produces_nothing() {
    let start = local(2026, 9, 4, 14, 0);
    let mut wrong_title = event("e1", "Team lunch", start, start + 3600);
    wrong_title.title = "Team lunch".into();
    let mut wrong_calendar = event("e2", "Focus", start, start + 3600);
    wrong_calendar.calendar = "Personal".into();
    let mut not_busy = event("e3", "Focus", start, start + 3600);
    not_busy.busy = false;
    let events = [wrong_title, wrong_calendar, not_busy];
    assert!(focus_schedule().activations(&events).is_empty());
}

#[test]
fn matching_is_case_insensitive_on_both_the_title_and_the_calendar_name() {
    let start = local(2026, 9, 4, 14, 0);
    let mut e = event("e1", "FOCUS TIME", start, start + 60);
    e.calendar = "work".into();
    assert_eq!(focus_schedule().activations(&[e]).len(), 1);
}

#[test]
fn an_empty_matcher_matches_every_event() {
    let start = local(2026, 9, 4, 14, 0);
    let schedule = CalendarSchedule {
        id: "all".into(),
        profile: "any".into(),
        matcher: EventMatcher::default(),
        pad_before_seconds: 0,
        pad_after_seconds: 0,
        locks: vec![],
        enabled: true,
    };
    let mut free = event("e1", "anything", start, start + 60);
    free.busy = false;
    assert_eq!(schedule.activations(&[free]).len(), 1);
}

#[test]
fn an_all_day_filter_separates_all_day_events_from_timed_ones() {
    let start = local(2026, 9, 4, 0, 0);
    let mut all_day = event("e1", "Holiday", start, start + 86_400);
    all_day.all_day = true;
    let timed = event("e2", "Holiday", start, start + 3600);
    let mut schedule = focus_schedule();
    schedule.matcher = EventMatcher { all_day: Some(true), ..EventMatcher::default() };
    let out = schedule.activations(&[all_day, timed]);
    assert_eq!(out.len(), 1);
    assert_eq!(
        out[0].source,
        ActivationSource::Calendar { schedule: "focus".into(), event: "e1".into() }
    );
}

/// A calendar with a zero-length or inverted entry must still produce something enforceable rather
/// than a window that silently contains no instant at all.
#[test]
fn a_zero_length_event_still_produces_a_window_containing_its_start() {
    let at = local(2026, 9, 4, 14, 0);
    let mut schedule = focus_schedule();
    schedule.pad_before_seconds = 0;
    let a = &schedule.activations(&[event("e1", "Focus", at, at)])[0];
    assert!((a.start..a.end).contains(&at));
}

#[test]
fn an_event_with_its_end_before_its_start_does_not_produce_a_backwards_window() {
    let at = local(2026, 9, 4, 14, 0);
    let a = &focus_schedule().activations(&[event("e1", "Focus", at, at - 3600)])[0];
    assert!(a.end > a.start, "{a:?}");
}

// --- everything together -------------------------------------------------------------------------

#[test]
fn active_at_collects_from_both_kinds_of_schedule() {
    let now = local(2026, 9, 4, 10, 0);
    let events = [event("e1", "Focus", now - 60, now + 3600)];
    let out = active_at(now, LONDON, &[weekday_mornings()], &[focus_schedule()], &events);
    assert_eq!(out.len(), 2, "the weekly window and the calendar event both apply: {out:?}");
}

#[test]
fn an_event_that_has_already_ended_is_not_active() {
    let now = local(2026, 9, 4, 14, 0);
    let events = [event("e1", "Focus", now - 7200, now - 3600)];
    assert!(active_at(now, LONDON, &[], &[focus_schedule()], &events).is_empty());
}

// --- the next alarm ------------------------------------------------------------------------------

#[test]
fn the_next_change_is_the_soonest_edge_strictly_after_now() {
    let now = local(2026, 9, 4, 10, 0);
    let events = [event("e1", "Focus", local(2026, 9, 4, 11, 0), local(2026, 9, 4, 11, 30))];
    let next = next_change_after(now, LONDON, &[weekday_mornings()], &[focus_schedule()], &events);
    assert_eq!(next, Some(local(2026, 9, 4, 11, 0) - 300), "the padded start of the meeting");
}

#[test]
fn the_next_change_looks_ahead_to_tomorrows_window_when_todays_is_over() {
    let now = local(2026, 9, 4, 13, 0); // Friday afternoon, window closed
    let next = next_change_after(now, LONDON, &[weekday_mornings()], &[], &[]);
    assert_eq!(next, Some(local(2026, 9, 7, 9, 0)), "Monday morning, skipping the weekend");
}

#[test]
fn there_is_no_next_change_when_nothing_is_scheduled() {
    assert_eq!(next_change_after(local(2026, 9, 4, 10, 0), LONDON, &[], &[], &[]), None);
}

// --- category and duration matchers --------------------------------------------------------------

#[test]
fn a_category_matcher_needs_one_of_the_categories_and_ignores_case() {
    let mut tagged = event("e1", "Sprint review", 0, 3600);
    tagged.categories = vec!["Focus".into(), "Team".into()];
    let matcher = EventMatcher { categories: vec!["focus".into()], ..EventMatcher::default() };

    assert!(matcher.matches(&tagged));
    assert!(!matcher.matches(&event("e2", "Sprint review", 0, 3600)));
}

#[test]
fn an_empty_category_list_is_not_a_filter() {
    // Otherwise every rule written before categories existed would quietly stop matching anything
    // that came from a provider with no categories at all, which is most of them.
    let matcher = EventMatcher::default();

    assert!(matcher.matches(&event("e1", "Anything", 0, 3600)));
}

#[test]
fn a_duration_matcher_keeps_the_meetings_and_drops_the_reminders() {
    let matcher = EventMatcher {
        min_duration_seconds: Some(20 * 60),
        max_duration_seconds: Some(4 * 60 * 60),
        ..EventMatcher::default()
    };

    assert!(!matcher.matches(&event("short", "Take pills", 0, 10 * 60)));
    assert!(matcher.matches(&event("real", "Design review", 0, 60 * 60)));
    assert!(!matcher.matches(&event("leave", "On leave", 0, 24 * 60 * 60)));
}

#[test]
fn duration_is_measured_before_padding_not_after() {
    // A rule that says "meetings of at least an hour" is about the meeting. Padding is the user
    // asking for a wider block around it, not a claim that the meeting itself is longer.
    let schedule = CalendarSchedule {
        id: "long-meetings".into(),
        profile: "deep-work".into(),
        matcher: EventMatcher { min_duration_seconds: Some(60 * 60), ..EventMatcher::default() },
        pad_before_seconds: 30 * 60,
        pad_after_seconds: 30 * 60,
        locks: vec![],
        enabled: true,
    };
    let half_hour = event("e1", "Standup", 1_788_510_600, 1_788_510_600 + 30 * 60);

    assert!(schedule.activations(&[half_hour]).is_empty());
}

#[test]
fn an_event_whose_end_precedes_its_start_lasts_no_time_at_all() {
    // A provider bug should read as an event of zero length, not as one long enough to trip every
    // "at least this long" rule ever written.
    let backwards = event("broken", "Corrupt", 1_000, 100);

    assert_eq!(0, backwards.duration_seconds());
}

// --- the preview timeline ----------------------------------------------------------------------

/// A schedule that acts on every event, for previews where the matcher is not what is being tested.
fn everything() -> CalendarSchedule {
    CalendarSchedule {
        id: "all".into(),
        profile: "meetings".into(),
        matcher: EventMatcher::default(),
        pad_before_seconds: 0,
        pad_after_seconds: 0,
        locks: vec![Lock::Timer],
        enabled: true,
    }
}

#[test]
fn tomorrow_is_shown_before_it_happens() {
    // The whole point of a preview: a block that has not started yet is still listed, so a user can
    // see what a rule is about to do to their morning before it does it. 2026-09-04 is a Friday, so
    // four days from here is Friday and Monday and neither of the weekend days.
    let friday_morning = local(2026, 9, 4, 8, 0);
    let ahead = upcoming(
        friday_morning,
        friday_morning + 4 * 24 * 3600,
        LONDON,
        &[weekday_mornings()],
        &[],
        &[],
    );

    assert_eq!(
        vec![local(2026, 9, 4, 9, 0), local(2026, 9, 7, 9, 0)],
        ahead.iter().map(|a| a.start).collect::<Vec<_>>(),
    );
}

#[test]
fn nothing_outside_the_window_is_previewed() {
    let friday_night = local(2026, 9, 4, 20, 0);
    let ahead =
        upcoming(friday_night, friday_night + 3600, LONDON, &[weekday_mornings()], &[], &[]);

    assert!(ahead.is_empty());
}

#[test]
fn a_block_already_running_is_previewed_whole_rather_than_clipped() {
    // Showing it as starting at the edge of the view would tell the user it starts later than it
    // does, which for a lock is the one direction that matters.
    let mid_window = local(2026, 9, 4, 10, 0);
    let ahead = upcoming(mid_window, mid_window + 3600, LONDON, &[weekday_mornings()], &[], &[]);

    assert_eq!(1, ahead.len());
    assert_eq!(local(2026, 9, 4, 9, 0), ahead[0].start);
    assert_eq!(local(2026, 9, 4, 12, 0), ahead[0].end);
}

#[test]
fn an_overnight_window_that_began_yesterday_is_still_in_the_preview() {
    let after_midnight = local(2026, 9, 5, 1, 0);
    let ahead = upcoming(after_midnight, after_midnight + 3600, LONDON, &[overnight()], &[], &[]);

    assert_eq!(1, ahead.len());
    assert_eq!(local(2026, 9, 4, 23, 0), ahead[0].start);
}

#[test]
fn meetings_and_weekly_windows_are_previewed_together_in_time_order() {
    let friday = local(2026, 9, 4, 6, 0);
    let ahead = upcoming(
        friday,
        friday + 24 * 3600,
        LONDON,
        &[weekday_mornings()],
        &[everything()],
        &[event("e1", "Design review", local(2026, 9, 4, 14, 0), local(2026, 9, 4, 15, 0))],
    );

    assert_eq!(
        vec!["deep-work".to_string(), "meetings".to_string()],
        ahead.iter().map(|a| a.profile.clone()).collect::<Vec<_>>(),
    );
    assert!(matches!(ahead[1].source, ActivationSource::Calendar { .. }));
}

#[test]
fn a_preview_carries_the_lock_the_block_will_come_with() {
    // A preview that showed the times but not the lock would be the least useful half: the thing a
    // user needs to know before tomorrow is whether they will be able to get out of it.
    let friday = local(2026, 9, 4, 6, 0);
    let ahead = upcoming(friday, friday + 12 * 3600, LONDON, &[overnight()], &[], &[]);

    assert_eq!(vec![Lock::DeviceCredential], ahead[0].locks);
}

#[test]
fn a_window_that_spans_the_clock_change_is_previewed_at_its_real_length() {
    // The UK leaves summer time at 02:00 on 2026-10-25, inside the overnight window.
    let saturday = local(2026, 10, 24, 20, 0);
    let ahead = upcoming(saturday, saturday + 24 * 3600, LONDON, &[overnight()], &[], &[]);

    let night = &ahead[0];
    assert_eq!(9 * 3600, night.end - night.start, "the extra hour was not previewed");
}

#[test]
fn a_preview_of_a_day_with_no_rules_is_empty_rather_than_an_error() {
    let friday = local(2026, 9, 4, 6, 0);

    assert!(upcoming(friday, friday + 24 * 3600, LONDON, &[], &[], &[]).is_empty());
}

#[test]
fn a_backwards_window_previews_nothing() {
    // A UI can ask for one by arithmetic on a date the user changed; it must not answer with the
    // whole week.
    let friday = local(2026, 9, 4, 12, 0);

    assert!(upcoming(friday, friday - 3600, LONDON, &[weekday_mornings()], &[], &[]).is_empty());
}

#[test]
fn a_paused_window_does_nothing_at_all() {
    let mut w = weekday_mornings();
    w.enabled = false;
    let monday = local(2024, 6, 3, 10, 0);
    assert!(active_at(monday, LONDON, &[w.clone()], &[], &[]).is_empty());
    assert!(upcoming(monday, monday + 7 * 86_400, LONDON, &[w.clone()], &[], &[]).is_empty());
    // Nor may it wake the platform: an alarm for a window that will not run is a wakeup spent on
    // nothing, and on Android that is a real cost.
    assert_eq!(next_change_after(monday, LONDON, &[w], &[], &[]), None);
}

#[test]
fn a_paused_calendar_rule_matches_nothing() {
    let mut schedule = focus_schedule();
    schedule.enabled = false;
    let start = local(2024, 6, 3, 10, 0);
    let events = vec![event("e1", "Focus block", start, start + 3_600)];
    assert!(schedule.activations(&events).is_empty());
}

#[test]
fn a_schedule_written_before_pausing_existed_still_runs() {
    // The flag defaults to true on the way in, so upgrading the app cannot silently switch every
    // existing window off.
    let w: WeeklySchedule = serde_json::from_str(
        r#"{"id":"w","profile":"p","days":[0],"start_minute":540,"end_minute":720}"#,
    )
    .expect("a window without the field is still a window");
    assert!(w.enabled);
}
