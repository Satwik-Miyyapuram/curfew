//! Calendar subscriptions, and what they do when the network is not there.
//!
//! The property under test throughout is the one that makes a calendar rule trustworthy: a fetch
//! that fails must never be the difference between blocked and not blocked. Everything else here —
//! refresh intervals, id namespacing, size limits — exists in service of that.

use curfew_core::{CalendarSource, Timestamp};
use curfew_win::calendar::{as_http, is_url, Feeds, LocalFiles, Outcome, MAX_BYTES};
use std::cell::RefCell;

const UTC: chrono_tz::Tz = chrono_tz::UTC;

/// 2026-09-04 09:00 UTC, the instant every document below is written around.
const NOW: Timestamp = 1_788_512_400;

fn ics(summary: &str, start: &str, end: &str) -> String {
    format!(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:{summary}\r\nSUMMARY:{summary}\r\n\
         DTSTART:{start}\r\nDTEND:{end}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    )
}

fn meeting() -> String {
    ics("Design review", "20260904T090000Z", "20260904T100000Z")
}

fn source(id: &str, location: &str, refresh: u32) -> CalendarSource {
    CalendarSource { id: id.to_string(), location: location.to_string(), refresh_seconds: refresh }
}

fn dir(tag: &str) -> std::path::PathBuf {
    let path = std::env::temp_dir().join(format!("curfew-feeds-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&path);
    std::fs::create_dir_all(&path).unwrap();
    path
}

/// A fetcher that hands back whatever the test tells it to, and counts how often it was asked.
struct Scripted {
    answers: RefCell<Vec<Result<String, String>>>,
    calls: RefCell<usize>,
}

impl Scripted {
    fn new(answers: Vec<Result<String, String>>) -> Self {
        Self { answers: RefCell::new(answers), calls: RefCell::new(0) }
    }

    fn always(answer: Result<String, String>) -> Self {
        Self::new(vec![answer; 1])
    }

    fn calls(&self) -> usize {
        *self.calls.borrow()
    }
}

impl curfew_win::calendar::Fetch for Scripted {
    fn fetch(&self, _location: &str) -> Result<String, String> {
        *self.calls.borrow_mut() += 1;
        let mut answers = self.answers.borrow_mut();
        if answers.len() > 1 {
            answers.remove(0)
        } else {
            answers[0].clone()
        }
    }
}

#[test]
fn a_subscription_that_parses_becomes_events() {
    let mut feeds = Feeds::new(dir("ok"));
    let fetcher = Scripted::always(Ok(meeting()));

    let (events, outcomes) =
        feeds.events(NOW, &[source("work", "https://cal/x.ics", 3600)], UTC, &fetcher);

    assert_eq!(1, events.len());
    assert_eq!("Design review", events[0].title);
    assert_eq!(vec![Outcome::Refreshed { id: "work".into(), events: 1 }], outcomes);
}

#[test]
fn an_event_carries_the_source_it_came_from_in_its_id() {
    // Two calendars can hold the same meeting. If both appearances shared an id, a schedule would
    // treat the second as the first still running, and the block would end at the wrong time.
    let mut feeds = Feeds::new(dir("ids"));
    let fetcher = Scripted::always(Ok(meeting()));

    let (events, _) = feeds.events(
        NOW,
        &[source("work", "https://a/x.ics", 3600), source("home", "https://b/x.ics", 3600)],
        UTC,
        &fetcher,
    );

    assert_eq!(2, events.len());
    assert_eq!(
        2,
        events.iter().map(|e| e.id.clone()).collect::<std::collections::BTreeSet<_>>().len()
    );
    assert!(events.iter().any(|e| e.id.starts_with("work/")));
    assert!(events.iter().any(|e| e.id.starts_with("home/")));
}

#[test]
fn a_fetch_that_fails_keeps_blocking_from_the_last_good_copy() {
    // The whole point. Otherwise "unplug the router" is a way out of a calendar-driven lock.
    let mut feeds = Feeds::new(dir("outage"));
    let good = Scripted::always(Ok(meeting()));
    let sources = [source("work", "https://cal/x.ics", 0)];
    feeds.events(NOW, &sources, UTC, &good);

    let broken = Scripted::always(Err("the network is unreachable".to_string()));
    let (events, outcomes) = feeds.events(NOW + 1, &sources, UTC, &broken);

    assert_eq!(1, events.len(), "an outage released a calendar block");
    assert_eq!(
        vec![Outcome::Failed {
            id: "work".into(),
            detail: "the network is unreachable".into(),
            still_serving: true,
        }],
        outcomes,
    );
}

#[test]
fn a_document_that_is_not_a_calendar_does_not_replace_the_one_that_was() {
    // A captive portal answering a subscription URL with a login page is the ordinary way this
    // happens, and it must not look like "your meetings were all cancelled".
    let mut feeds = Feeds::new(dir("junk"));
    let sources = [source("work", "https://cal/x.ics", 0)];
    feeds.events(NOW, &sources, UTC, &Scripted::always(Ok(meeting())));

    let portal = Scripted::always(Ok("<html>Sign in to the WiFi</html>".to_string()));
    let (events, outcomes) = feeds.events(NOW + 1, &sources, UTC, &portal);

    assert_eq!(1, events.len());
    assert!(matches!(outcomes[0], Outcome::Failed { still_serving: true, .. }));
    assert_eq!(meeting(), feeds.cached("work").unwrap());
}

#[test]
fn a_source_that_has_never_been_fetched_says_so_rather_than_pretending_to_be_empty() {
    let mut feeds = Feeds::new(dir("never"));
    let broken = Scripted::always(Err("no such file".to_string()));

    let (events, outcomes) =
        feeds.events(NOW, &[source("work", "C:/nope.ics", 3600)], UTC, &broken);

    assert!(events.is_empty());
    assert!(matches!(outcomes[0], Outcome::Failed { still_serving: false, .. }));
}

#[test]
fn nothing_is_fetched_again_before_its_refresh_interval() {
    let mut feeds = Feeds::new(dir("refresh"));
    let fetcher = Scripted::always(Ok(meeting()));
    let sources = [source("work", "https://cal/x.ics", 3600)];

    feeds.events(NOW, &sources, UTC, &fetcher);
    let (events, outcomes) = feeds.events(NOW + 60, &sources, UTC, &fetcher);

    assert_eq!(1, fetcher.calls(), "a subscription was re-fetched inside its own interval");
    assert_eq!(1, events.len(), "the cached copy stopped producing events");
    assert_eq!(vec![Outcome::Fresh { id: "work".into() }], outcomes);
}

#[test]
fn the_interval_does_expire() {
    let mut feeds = Feeds::new(dir("expire"));
    let fetcher = Scripted::always(Ok(meeting()));
    let sources = [source("work", "https://cal/x.ics", 3600)];

    feeds.events(NOW, &sources, UTC, &fetcher);
    feeds.events(NOW + 3600, &sources, UTC, &fetcher);

    assert_eq!(2, fetcher.calls());
}

#[test]
fn a_cached_copy_survives_a_restart() {
    // A service restarted during an outage must come back still enforcing what the calendar said.
    let where_it_lives = dir("restart");
    let sources = [source("work", "https://cal/x.ics", 3600)];
    let mut first = Feeds::new(&where_it_lives);
    first.events(NOW, &sources, UTC, &Scripted::always(Ok(meeting())));

    let mut second = Feeds::new(&where_it_lives);
    second.restore(&sources);
    let broken = Scripted::always(Err("still offline".to_string()));
    let (events, _) = second.events(NOW + 10, &sources, UTC, &broken);

    assert_eq!(1, events.len(), "a restart during an outage lost a calendar block");
}

#[test]
fn a_restored_copy_is_refreshed_at_the_first_opportunity() {
    // Its age is unknown, so it is treated as stale. Honouring an hour of freshness for a document
    // that might be a week old is the wrong way round.
    let where_it_lives = dir("stale");
    let sources = [source("work", "https://cal/x.ics", 3600)];
    let mut first = Feeds::new(&where_it_lives);
    first.events(NOW, &sources, UTC, &Scripted::always(Ok(meeting())));

    let mut second = Feeds::new(&where_it_lives);
    second.restore(&sources);
    let fetcher = Scripted::always(Ok(meeting()));
    second.events(NOW + 1, &sources, UTC, &fetcher);

    assert_eq!(1, fetcher.calls());
}

#[test]
fn a_source_id_cannot_reach_out_of_the_cache_directory() {
    // Source ids come out of the config file. A name is not a path.
    let where_it_lives = dir("traversal");
    let mut feeds = Feeds::new(&where_it_lives);
    feeds.events(
        NOW,
        &[source("../../escape", "https://cal/x.ics", 3600)],
        UTC,
        &Scripted::always(Ok(meeting())),
    );

    let written: Vec<_> = std::fs::read_dir(&where_it_lives)
        .unwrap()
        .filter_map(|e| e.ok().map(|e| e.file_name().to_string_lossy().to_string()))
        .collect();
    assert_eq!(vec!["______escape.ics".to_string()], written);
}

#[test]
fn events_come_back_in_time_order_across_sources() {
    let mut feeds = Feeds::new(dir("order"));
    let fetcher = Scripted::new(vec![
        Ok(ics("Later", "20260904T140000Z", "20260904T150000Z")),
        Ok(ics("Earlier", "20260904T080000Z", "20260904T090000Z")),
    ]);

    let (events, _) = feeds.events(
        NOW,
        &[source("a", "https://a/x.ics", 3600), source("b", "https://b/x.ics", 3600)],
        UTC,
        &fetcher,
    );

    assert_eq!(
        vec!["Earlier", "Later"],
        events.iter().map(|e| e.title.as_str()).collect::<Vec<_>>()
    );
}

#[test]
fn an_event_far_outside_the_window_is_not_expanded() {
    let mut feeds = Feeds::new(dir("window"));
    let fetcher = Scripted::always(Ok(ics("Next year", "20270904T090000Z", "20270904T100000Z")));

    let (events, outcomes) =
        feeds.events(NOW, &[source("work", "https://cal/x.ics", 3600)], UTC, &fetcher);

    assert!(events.is_empty());
    // Still a successful refresh: an empty window is not a broken subscription.
    assert_eq!(vec![Outcome::Refreshed { id: "work".into(), events: 0 }], outcomes);
}

// --- the local-file fetcher -----------------------------------------------------------------

#[test]
fn a_local_file_is_read_from_disk() {
    use curfew_win::calendar::Fetch;
    let where_it_lives = dir("localfile");
    let file = where_it_lives.join("work.ics");
    std::fs::write(&file, meeting()).unwrap();

    assert_eq!(meeting(), LocalFiles.fetch(file.to_str().unwrap()).unwrap());
}

#[test]
fn the_local_fetcher_says_plainly_that_it_cannot_do_urls() {
    use curfew_win::calendar::Fetch;
    let refusal = LocalFiles.fetch("https://cal/x.ics").unwrap_err();

    assert!(refusal.contains("subscription"), "{refusal}");
}

#[test]
fn a_file_too_large_to_be_a_calendar_is_refused_rather_than_read() {
    use curfew_win::calendar::Fetch;
    let where_it_lives = dir("huge");
    let file = where_it_lives.join("huge.ics");
    std::fs::write(&file, vec![b'x'; MAX_BYTES + 1]).unwrap();

    assert!(LocalFiles.fetch(file.to_str().unwrap()).is_err());
}

#[test]
fn webcal_is_a_subscription_and_is_fetched_over_https() {
    assert!(is_url("webcal://example.com/x.ics"));
    assert_eq!("https://example.com/x.ics", as_http("webcal://example.com/x.ics"));
    assert_eq!("https://example.com/x.ics", as_http("https://example.com/x.ics"));
    assert_eq!("C:/cal.ics", as_http("C:/cal.ics"));
    assert!(!is_url("C:/cal.ics"));
}

// --- how far ahead a caller can see ------------------------------------------------------------

#[test]
fn the_default_window_stops_where_enforcement_stops() {
    // Sixty hours out. The enforcement loop has no use for it and does not collect it.
    let mut feeds = Feeds::new(dir("horizon-default"));
    let far = ics("Sunday standup", "20260906T210000Z", "20260906T220000Z");
    let fetcher = Scripted::always(Ok(far));

    let (events, _) = feeds.events(NOW, &[source("work", "https://cal/x.ics", 3600)], UTC, &fetcher);

    assert!(events.is_empty(), "{events:?}");
}

#[test]
fn a_preview_asked_for_a_week_is_given_a_week() {
    // The bug this pins: `upcoming --hours 72` honoured the hours for weekly windows and silently
    // truncated calendar events at thirty-six, so a busy Sunday read as a free one.
    let mut feeds = Feeds::new(dir("horizon-wide"));
    let far = ics("Sunday standup", "20260906T210000Z", "20260906T220000Z");
    let fetcher = Scripted::always(Ok(far));

    let (events, _) = feeds.events_ahead(
        NOW,
        72 * 3_600,
        &[source("work", "https://cal/x.ics", 3600)],
        UTC,
        &fetcher,
    );

    assert_eq!(1, events.len(), "{events:?}");
    assert_eq!("Sunday standup", events[0].title);
}

#[test]
fn asking_for_less_than_enforcement_needs_does_not_narrow_the_window() {
    // `--hours 1` is a question about the next hour, not permission to collect less than the
    // enforcement loop relies on; the two share one cache, and a narrowed read would poison it.
    let mut feeds = Feeds::new(dir("horizon-narrow"));
    let fetcher = Scripted::always(Ok(meeting()));

    let (events, _) =
        feeds.events_ahead(NOW, 3_600, &[source("work", "https://cal/x.ics", 3600)], UTC, &fetcher);

    assert_eq!(1, events.len(), "{events:?}");
}
