//! What a real `.ics` file does to a calendar rule.
//!
//! The dates here are deliberately concrete rather than relative to "now": the whole class of bug
//! this crate exists to avoid is a block that lands an hour out twice a year, and that can only be
//! caught by naming the days the clocks change and asserting on the instants either side.
//!
//! Europe/London is used throughout because it makes the failures visible: it is UTC in winter and
//! UTC+1 in summer, so a naive implementation passes half the year.

use chrono::TimeZone;
use chrono_tz::Europe::London;
use chrono_tz::Tz;
use curfew_core::Timestamp;
use curfew_ics::{events_between, Error};

const LONDON: Tz = London;

fn at(year: i32, month: u32, day: u32, hour: u32, minute: u32) -> Timestamp {
    LONDON.with_ymd_and_hms(year, month, day, hour, minute, 0).unwrap().timestamp()
}

fn utc(year: i32, month: u32, day: u32, hour: u32, minute: u32) -> Timestamp {
    chrono::Utc.with_ymd_and_hms(year, month, day, hour, minute, 0).unwrap().timestamp()
}

fn calendar(body: &str) -> String {
    format!("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Work\r\n{body}END:VCALENDAR\r\n")
}

fn event(properties: &str) -> String {
    calendar(&format!("BEGIN:VEVENT\r\n{properties}END:VEVENT\r\n"))
}

#[test]
fn something_that_is_not_a_calendar_is_refused_rather_than_read_as_an_empty_one() {
    // An empty answer and a failed fetch must not look alike: one means "nothing is scheduled",
    // the other means "the rule is not running", and only the second is worth telling the user.
    let result = events_between("<html>404</html>", 0, 1, LONDON);

    assert_eq!(Err(Error::NotCalendar), result);
}

#[test]
fn a_single_timed_event_arrives_with_its_title_calendar_and_span() {
    let text = event(
        "UID:e1\r\nSUMMARY:Design review\r\nLOCATION:Room 2\r\n\
         DTSTART:20260904T090000Z\r\nDTEND:20260904T100000Z\r\n",
    );

    let events =
        events_between(&text, utc(2026, 9, 4, 0, 0), utc(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!(1, events.len());
    assert_eq!("Design review", events[0].title);
    assert_eq!("Work", events[0].calendar);
    assert_eq!("Room 2", events[0].location);
    assert_eq!(utc(2026, 9, 4, 9, 0), events[0].start);
    assert_eq!(utc(2026, 9, 4, 10, 0), events[0].end);
    assert!(events[0].busy);
    assert!(!events[0].all_day);
}

#[test]
fn an_all_day_event_starts_when_the_day_starts_where_the_user_is() {
    // In British Summer Time that is 23:00 UTC the night before. Reading it as UTC midnight is the
    // single most common calendar bug there is, and it shifts every all-day block by an hour.
    let text = event("UID:e1\r\nSUMMARY:Conference\r\nDTSTART;VALUE=DATE:20260904\r\nDTEND;VALUE=DATE:20260905\r\n");

    let events = events_between(&text, at(2026, 9, 3, 0, 0), at(2026, 9, 6, 0, 0), LONDON).unwrap();

    assert_eq!(1, events.len());
    assert!(events[0].all_day);
    assert_eq!(at(2026, 9, 4, 0, 0), events[0].start);
    assert_eq!(at(2026, 9, 5, 0, 0), events[0].end);
    assert_eq!(utc(2026, 9, 3, 23, 0), events[0].start);
}

#[test]
fn a_dated_event_with_no_end_lasts_the_whole_day() {
    let text = event("UID:e1\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20260904\r\n");

    let events = events_between(&text, at(2026, 9, 3, 0, 0), at(2026, 9, 6, 0, 0), LONDON).unwrap();

    assert_eq!(at(2026, 9, 4, 0, 0), events[0].start);
    assert_eq!(at(2026, 9, 5, 0, 0), events[0].end);
}

#[test]
fn a_duration_stands_in_for_a_missing_end() {
    let text = event("UID:e1\r\nSUMMARY:Standup\r\nDTSTART:20260904T090000Z\r\nDURATION:PT15M\r\n");

    let events =
        events_between(&text, utc(2026, 9, 4, 0, 0), utc(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!(utc(2026, 9, 4, 9, 15), events[0].end);
}

#[test]
fn a_transparent_event_is_not_busy() {
    let text = event(
        "UID:e1\r\nSUMMARY:Birthday\r\nTRANSP:TRANSPARENT\r\n\
         DTSTART:20260904T090000Z\r\nDTEND:20260904T100000Z\r\n",
    );

    let events =
        events_between(&text, utc(2026, 9, 4, 0, 0), utc(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert!(!events[0].busy);
}

#[test]
fn categories_come_through_split_and_trimmed() {
    let text = event(
        "UID:e1\r\nSUMMARY:Sprint\r\nCATEGORIES:Focus, Team\r\n\
         DTSTART:20260904T090000Z\r\nDTEND:20260904T100000Z\r\n",
    );

    let events =
        events_between(&text, utc(2026, 9, 4, 0, 0), utc(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!(vec!["Focus".to_string(), "Team".to_string()], events[0].categories);
}

#[test]
fn a_folded_line_is_put_back_together_before_it_is_read() {
    // Providers fold mid-word at 75 octets. A parser that does not unfold silently truncates every
    // long title, and a title glob is how most calendar rules are written.
    let text = calendar(
        "BEGIN:VEVENT\r\nUID:e1\r\nSUMMARY:Quarterly planning and roadmap\r\n review\r\n\
         DTSTART:20260904T090000Z\r\nDTEND:20260904T100000Z\r\nEND:VEVENT\r\n",
    );

    let events =
        events_between(&text, utc(2026, 9, 4, 0, 0), utc(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!("Quarterly planning and roadmapreview", events[0].title);
}

#[test]
fn escaped_text_is_unescaped() {
    let text = event(
        "UID:e1\r\nSUMMARY:Review\\, then lunch\r\nDTSTART:20260904T090000Z\r\nDTEND:20260904T100000Z\r\n",
    );

    let events =
        events_between(&text, utc(2026, 9, 4, 0, 0), utc(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!("Review, then lunch", events[0].title);
}

// --- recurrence ----------------------------------------------------------------------------------

#[test]
fn a_daily_rule_produces_one_occurrence_per_day_in_the_window() {
    let text = event(
        "UID:e1\r\nSUMMARY:Standup\r\nDTSTART;TZID=Europe/London:20260901T090000\r\n\
         DTEND;TZID=Europe/London:20260901T091500\r\nRRULE:FREQ=DAILY\r\n",
    );

    let events = events_between(&text, at(2026, 9, 3, 0, 0), at(2026, 9, 6, 0, 0), LONDON).unwrap();

    assert_eq!(3, events.len());
    assert_eq!(at(2026, 9, 3, 9, 0), events[0].start);
    assert_eq!(at(2026, 9, 5, 9, 0), events[2].start);
}

#[test]
fn every_occurrence_gets_its_own_id() {
    // Otherwise the second occurrence of a weekly meeting looks to a schedule like the first one
    // still running, and the block never lifts.
    let text = event(
        "UID:e1\r\nSUMMARY:Standup\r\nDTSTART;TZID=Europe/London:20260901T090000\r\n\
         DTEND;TZID=Europe/London:20260901T091500\r\nRRULE:FREQ=DAILY\r\n",
    );

    let events = events_between(&text, at(2026, 9, 3, 0, 0), at(2026, 9, 6, 0, 0), LONDON).unwrap();

    let ids: std::collections::BTreeSet<_> = events.iter().map(|e| e.id.clone()).collect();
    assert_eq!(3, ids.len());
}

#[test]
fn a_weekly_meeting_keeps_its_local_time_across_the_autumn_clock_change() {
    // The UK moves off summer time on 2026-10-25. A 09:00 meeting is 08:00 UTC before and 09:00
    // UTC after; a rule expanded by adding 604800 seconds would put it an hour early forever.
    let text = event(
        "UID:e1\r\nSUMMARY:Weekly\r\nDTSTART;TZID=Europe/London:20261021T090000\r\n\
         DTEND;TZID=Europe/London:20261021T100000\r\nRRULE:FREQ=WEEKLY\r\n",
    );

    let events =
        events_between(&text, at(2026, 10, 20, 0, 0), at(2026, 11, 5, 0, 0), LONDON).unwrap();

    assert_eq!(3, events.len());
    assert_eq!(utc(2026, 10, 21, 8, 0), events[0].start);
    assert_eq!(utc(2026, 10, 28, 9, 0), events[1].start);
    assert_eq!(at(2026, 10, 28, 9, 0), events[1].start);
    assert_eq!(at(2026, 11, 4, 9, 0), events[2].start);
}

#[test]
fn a_daily_meeting_keeps_its_local_time_across_the_spring_clock_change() {
    // 2026-03-29 is when the UK springs forward.
    let text = event(
        "UID:e1\r\nSUMMARY:Standup\r\nDTSTART;TZID=Europe/London:20260327T090000\r\n\
         DTEND;TZID=Europe/London:20260327T091500\r\nRRULE:FREQ=DAILY\r\n",
    );

    let events =
        events_between(&text, at(2026, 3, 27, 0, 0), at(2026, 3, 31, 0, 0), LONDON).unwrap();

    assert_eq!(utc(2026, 3, 27, 9, 0), events[0].start);
    assert_eq!(utc(2026, 3, 30, 8, 0), events.last().unwrap().start);
    assert_eq!(at(2026, 3, 30, 9, 0), events.last().unwrap().start);
}

#[test]
fn a_meeting_in_the_hour_that_does_not_exist_is_moved_forward_rather_than_dropped() {
    // 01:30 on 2026-03-29 never happens in London. The meeting still happens, so a missing block
    // is the worse answer of the two.
    let text = event(
        "UID:e1\r\nSUMMARY:Night shift\r\nDTSTART;TZID=Europe/London:20260328T013000\r\n\
         DTEND;TZID=Europe/London:20260328T023000\r\nRRULE:FREQ=DAILY\r\n",
    );

    let events =
        events_between(&text, at(2026, 3, 28, 0, 0), at(2026, 3, 31, 0, 0), LONDON).unwrap();

    assert_eq!(3, events.len());
    assert!(events.iter().all(|e| e.end > e.start));
}

#[test]
fn a_weekly_rule_with_byday_fires_on_the_days_it_names() {
    let text = event(
        "UID:e1\r\nSUMMARY:Gym\r\nDTSTART;TZID=Europe/London:20260907T070000\r\n\
         DTEND;TZID=Europe/London:20260907T080000\r\nRRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR\r\n",
    );

    let events =
        events_between(&text, at(2026, 9, 7, 0, 0), at(2026, 9, 14, 0, 0), LONDON).unwrap();

    assert_eq!(3, events.len());
    assert_eq!(at(2026, 9, 7, 7, 0), events[0].start);
    assert_eq!(at(2026, 9, 9, 7, 0), events[1].start);
    assert_eq!(at(2026, 9, 11, 7, 0), events[2].start);
}

#[test]
fn count_and_until_both_stop_a_rule() {
    let counted = event(
        "UID:e1\r\nSUMMARY:Course\r\nDTSTART;TZID=Europe/London:20260901T090000\r\n\
         DTEND;TZID=Europe/London:20260901T100000\r\nRRULE:FREQ=DAILY;COUNT=2\r\n",
    );
    let dated = event(
        "UID:e2\r\nSUMMARY:Course\r\nDTSTART;TZID=Europe/London:20260901T090000\r\n\
         DTEND;TZID=Europe/London:20260901T100000\r\nRRULE:FREQ=DAILY;UNTIL=20260902T235959Z\r\n",
    );
    let window = (at(2026, 9, 1, 0, 0), at(2026, 9, 10, 0, 0));

    assert_eq!(2, events_between(&counted, window.0, window.1, LONDON).unwrap().len());
    assert_eq!(2, events_between(&dated, window.0, window.1, LONDON).unwrap().len());
}

#[test]
fn an_interval_skips_the_weeks_between() {
    let text = event(
        "UID:e1\r\nSUMMARY:Fortnightly\r\nDTSTART;TZID=Europe/London:20260901T090000\r\n\
         DTEND;TZID=Europe/London:20260901T100000\r\nRRULE:FREQ=WEEKLY;INTERVAL=2\r\n",
    );

    let events =
        events_between(&text, at(2026, 9, 1, 0, 0), at(2026, 10, 1, 0, 0), LONDON).unwrap();

    assert_eq!(3, events.len());
    assert_eq!(at(2026, 9, 15, 9, 0), events[1].start);
}

#[test]
fn a_monthly_rule_on_the_thirty_first_clamps_rather_than_spilling_into_the_next_month() {
    let text = event(
        "UID:e1\r\nSUMMARY:Invoices\r\nDTSTART;TZID=Europe/London:20260131T090000\r\n\
         DTEND;TZID=Europe/London:20260131T100000\r\nRRULE:FREQ=MONTHLY\r\n",
    );

    let events = events_between(&text, at(2026, 1, 1, 0, 0), at(2026, 4, 1, 0, 0), LONDON).unwrap();

    let days: Vec<u32> = events
        .iter()
        .map(|e| {
            use chrono::Datelike;
            LONDON.timestamp_opt(e.start, 0).unwrap().day()
        })
        .collect();
    assert_eq!(vec![31, 28, 31], days);
}

#[test]
fn an_excluded_date_takes_that_occurrence_out() {
    let text = event(
        "UID:e1\r\nSUMMARY:Standup\r\nDTSTART;TZID=Europe/London:20260901T090000\r\n\
         DTEND;TZID=Europe/London:20260901T091500\r\nRRULE:FREQ=DAILY\r\n\
         EXDATE;TZID=Europe/London:20260903T090000\r\n",
    );

    let events = events_between(&text, at(2026, 9, 1, 0, 0), at(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!(3, events.len());
    assert!(events.iter().all(|e| e.start != at(2026, 9, 3, 9, 0)));
}

#[test]
fn a_cancelled_occurrence_releases_its_block() {
    // The exit criterion for this phase in as many words: a deleted event stops blocking.
    let text = calendar(
        "BEGIN:VEVENT\r\nUID:e1\r\nSUMMARY:Standup\r\n\
         DTSTART;TZID=Europe/London:20260901T090000\r\nDTEND;TZID=Europe/London:20260901T091500\r\n\
         RRULE:FREQ=DAILY\r\nEND:VEVENT\r\n\
         BEGIN:VEVENT\r\nUID:e1\r\nRECURRENCE-ID;TZID=Europe/London:20260903T090000\r\n\
         STATUS:CANCELLED\r\nDTSTART;TZID=Europe/London:20260903T090000\r\n\
         DTEND;TZID=Europe/London:20260903T091500\r\nEND:VEVENT\r\n",
    );

    let events = events_between(&text, at(2026, 9, 1, 0, 0), at(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!(3, events.len());
    assert!(events.iter().all(|e| e.start != at(2026, 9, 3, 9, 0)));
}

#[test]
fn a_moved_occurrence_blocks_at_its_new_time_and_not_its_old_one() {
    let text = calendar(
        "BEGIN:VEVENT\r\nUID:e1\r\nSUMMARY:Standup\r\n\
         DTSTART;TZID=Europe/London:20260901T090000\r\nDTEND;TZID=Europe/London:20260901T091500\r\n\
         RRULE:FREQ=DAILY\r\nEND:VEVENT\r\n\
         BEGIN:VEVENT\r\nUID:e1\r\nSUMMARY:Standup (moved)\r\n\
         RECURRENCE-ID;TZID=Europe/London:20260903T090000\r\n\
         DTSTART;TZID=Europe/London:20260903T140000\r\nDTEND;TZID=Europe/London:20260903T141500\r\n\
         END:VEVENT\r\n",
    );

    let events = events_between(&text, at(2026, 9, 3, 0, 0), at(2026, 9, 4, 0, 0), LONDON).unwrap();

    assert_eq!(1, events.len());
    assert_eq!(at(2026, 9, 3, 14, 0), events[0].start);
    assert_eq!("Standup (moved)", events[0].title);
}

#[test]
fn an_rdate_adds_an_occurrence_the_rule_would_not_have_produced() {
    let text = event(
        "UID:e1\r\nSUMMARY:Review\r\nDTSTART;TZID=Europe/London:20260901T090000\r\n\
         DTEND;TZID=Europe/London:20260901T100000\r\nRDATE;TZID=Europe/London:20260903T150000\r\n",
    );

    let events = events_between(&text, at(2026, 9, 1, 0, 0), at(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!(2, events.len());
    assert_eq!(at(2026, 9, 3, 15, 0), events[1].start);
}

#[test]
fn a_recurrence_curfew_cannot_expand_exactly_produces_nothing_rather_than_something_wrong() {
    // BYSETPOS ("the last Friday of the month") is not implemented. A block on the wrong Friday
    // would teach the user to stop trusting the blocks on the right one.
    let text = event(
        "UID:e1\r\nSUMMARY:Retro\r\nDTSTART;TZID=Europe/London:20260925T090000\r\n\
         DTEND;TZID=Europe/London:20260925T100000\r\nRRULE:FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-1\r\n",
    );

    let events =
        events_between(&text, at(2026, 10, 1, 0, 0), at(2026, 12, 1, 0, 0), LONDON).unwrap();

    assert!(events.is_empty());
}

#[test]
fn a_rule_that_repeats_forever_is_bounded_by_the_window_not_by_the_rule() {
    let text = event(
        "UID:e1\r\nSUMMARY:Tick\r\nDTSTART;TZID=Europe/London:20260901T090000\r\n\
         DTEND;TZID=Europe/London:20260901T090100\r\nRRULE:FREQ=MINUTELY\r\n",
    );

    let events =
        events_between(&text, at(2026, 9, 1, 9, 0), at(2026, 9, 1, 10, 0), LONDON).unwrap();

    assert!(events.len() <= 61, "an unbounded rule produced {} occurrences", events.len());
}

#[test]
fn an_event_that_started_before_the_window_and_is_still_running_is_reported() {
    // The window is "what is happening now", not "what starts now". An overnight event that began
    // yesterday is still blocking.
    let text = event(
        "UID:e1\r\nSUMMARY:Night shift\r\nDTSTART;TZID=Europe/London:20260903T220000\r\n\
         DTEND;TZID=Europe/London:20260904T060000\r\n",
    );

    let events = events_between(&text, at(2026, 9, 4, 0, 0), at(2026, 9, 4, 1, 0), LONDON).unwrap();

    assert_eq!(1, events.len());
}

#[test]
fn nothing_outside_the_window_comes_back() {
    let text = event(
        "UID:e1\r\nSUMMARY:Last month\r\nDTSTART;TZID=Europe/London:20260801T090000\r\n\
         DTEND;TZID=Europe/London:20260801T100000\r\n",
    );

    let events = events_between(&text, at(2026, 9, 1, 0, 0), at(2026, 9, 2, 0, 0), LONDON).unwrap();

    assert!(events.is_empty());
}

#[test]
fn alarms_and_todos_are_not_read() {
    // A VALARM sits inside a VEVENT and has its own TRIGGER; a VTODO has a DTSTART. Neither is an
    // event, and reading either would produce blocks nobody scheduled.
    let text = calendar(
        "BEGIN:VTODO\r\nUID:t1\r\nSUMMARY:Buy milk\r\nDTSTART:20260904T090000Z\r\nEND:VTODO\r\n\
         BEGIN:VEVENT\r\nUID:e1\r\nSUMMARY:Real meeting\r\nDTSTART:20260904T100000Z\r\n\
         DTEND:20260904T110000Z\r\nBEGIN:VALARM\r\nTRIGGER:-PT15M\r\nSUMMARY:Reminder\r\n\
         END:VALARM\r\nEND:VEVENT\r\n",
    );

    let events =
        events_between(&text, utc(2026, 9, 4, 0, 0), utc(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!(1, events.len());
    assert_eq!("Real meeting", events[0].title);
}

#[test]
fn a_floating_time_is_read_in_the_timezone_the_policy_is_configured_with() {
    // Not the machine's timezone: a laptop that travels should keep blocking at the hours the user
    // set up, rather than following whatever the operating system decided the clock is now.
    let text =
        event("UID:e1\r\nSUMMARY:Floating\r\nDTSTART:20260904T090000\r\nDTEND:20260904T100000\r\n");

    let events = events_between(&text, at(2026, 9, 4, 0, 0), at(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert_eq!(at(2026, 9, 4, 9, 0), events[0].start);
}

#[test]
fn an_event_with_no_end_before_its_start_never_lasts_negative_time() {
    let text = event(
        "UID:e1\r\nSUMMARY:Corrupt\r\nDTSTART:20260904T100000Z\r\nDTEND:20260904T090000Z\r\n",
    );

    let events =
        events_between(&text, utc(2026, 9, 4, 0, 0), utc(2026, 9, 5, 0, 0), LONDON).unwrap();

    assert!(events.iter().all(|e| e.end >= e.start));
}
