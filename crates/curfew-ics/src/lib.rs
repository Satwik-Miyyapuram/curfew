//! Reading iCalendar files into the events the policy core already understands.
//!
//! This is the PC side of the calendar feature. Android has a calendar provider that expands
//! recurrence for us; Windows has no such thing, so a subscribed `.ics` URL, a CalDAV export, or a
//! file on disk all arrive here as text and have to be turned into concrete occurrences.
//!
//! Nothing in this crate does any I/O. It takes a string and gives back
//! [`curfew_core::schedule::CalendarEvent`]s over a window, which keeps the fetching — and the
//! decision about what Curfew is allowed to reach out to — in the service where it can be seen.
//!
//! What is deliberately *not* implemented is as important as what is:
//!
//!  - No `VTODO`, `VJOURNAL`, `VFREEBUSY`, or alarms. Curfew blocks during events; the rest is
//!    other people's data that it has no reason to read.
//!  - No attendee lists, organizers, descriptions, or URLs. A blocker does not need to know who
//!    else is in the meeting, and parsing a field is the first step to storing it.
//!  - Unsupported recurrence (`BYSETPOS`, `BYWEEKNO`, `BYYEARDAY`) makes the event non-recurring
//!    rather than approximately recurring. A calendar rule that fires at the wrong time is worse
//!    than one that does not fire, because the user stops trusting the ones that do.

use chrono::{DateTime, Datelike, Duration, NaiveDate, NaiveDateTime, TimeZone, Timelike, Utc};
use chrono_tz::Tz;
use curfew_core::schedule::CalendarEvent;
use curfew_core::Timestamp;
use std::collections::{BTreeMap, BTreeSet};

/// How many occurrences of one rule will ever be produced, whatever the window asks for.
///
/// A malformed or hostile `.ics` can say "every second, forever". The window is normally a day or
/// two, so this only ever bites on input that was going to be nonsense anyway, and it bounds the
/// work a subscribed URL can make the service do.
const MAX_OCCURRENCES: usize = 2_000;

/// Guard against a rule whose interval never advances the clock.
const MAX_ITERATIONS: usize = 20_000;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum Error {
    #[error("this does not look like an iCalendar file")]
    NotCalendar,
}

/// Every occurrence overlapping `[from, to)`, in start order.
///
/// `default_tz` is the timezone to read floating times in — ones written with no zone and no
/// trailing `Z`. The spec says those mean "local time wherever the file is read", so the caller
/// passes the timezone the policy is configured with rather than the machine's, which is what
/// makes a calendar rule behave the same on a laptop that travels.
/// How many `VEVENT`s the document contains, or `Err` if it is not a calendar at all.
///
/// **P2-9.** [`events_between`] answers "is this a calendar?", which it decides from a single
/// `BEGIN:VCALENDAR`. That is enough to know the text parses, and not enough to know it is
/// *authoritative*: a provider's auth-expiry placeholder and a truncated export both carry the header
/// and hold no events. A caller that caches whatever parses therefore replaces a good copy with a
/// placeholder and releases every block that copy was driving, which is fail-open on exactly the
/// threat this module exists to close.
///
/// Counted rather than parsed into events on purpose: the question is about the *document*, not about
/// a window of it, so it must not depend on a date range or a timezone.
pub fn event_count(text: &str) -> Result<usize, Error> {
    if !text.contains("BEGIN:VCALENDAR") {
        return Err(Error::NotCalendar);
    }
    Ok(components(text).len())
}

pub fn events_between(
    text: &str,
    from: Timestamp,
    to: Timestamp,
    default_tz: Tz,
) -> Result<Vec<CalendarEvent>, Error> {
    if !text.contains("BEGIN:VCALENDAR") {
        return Err(Error::NotCalendar);
    }
    let calendar_name = calendar_name(text);
    let mut out = Vec::new();
    // Overrides first: a recurring series can have one occurrence moved or cancelled, and the
    // series cannot be expanded correctly without knowing which.
    let components = components(text);
    let mut overrides: BTreeMap<String, BTreeMap<Timestamp, Component>> = BTreeMap::new();
    for component in &components {
        if let (Some(uid), Some(recurrence_id)) =
            (component.text("UID"), component.time("RECURRENCE-ID", default_tz))
        {
            overrides.entry(uid).or_default().insert(recurrence_id, component.clone());
        }
    }

    for component in &components {
        if component.property("RECURRENCE-ID").is_some() {
            continue;
        }
        let uid = component.text("UID").unwrap_or_default();
        let empty = BTreeMap::new();
        let changed = overrides.get(&uid).unwrap_or(&empty);
        out.extend(component.occurrences(from, to, default_tz, &calendar_name, changed));
    }

    // Overrides that were moved into the window from a series occurrence outside it still belong
    // in the answer: the user moved the meeting into today, and today is what is being blocked.
    for (uid, moved) in &overrides {
        for (recurrence_id, component) in moved {
            if component.is_cancelled() {
                continue;
            }
            let Some((start, end)) = component.span(default_tz) else { continue };
            if start >= to || end <= from {
                continue;
            }
            if out.iter().any(|e| e.id == occurrence_id(uid, *recurrence_id)) {
                continue;
            }
            out.push(component.to_event(
                occurrence_id(uid, *recurrence_id),
                start,
                end,
                &calendar_name,
            ));
        }
    }

    out.sort_by_key(|a| (a.start, a.id.clone()));
    out.dedup_by(|a, b| a.id == b.id);
    Ok(out)
}

/// The id an occurrence gets.
///
/// The instant is part of it, because a weekly meeting is many events to a schedule: giving every
/// occurrence one id would make next Tuesday's block look like last Tuesday's still running.
fn occurrence_id(uid: &str, start: Timestamp) -> String {
    format!("{uid}:{start}")
}

/// Whether a `DATE`-typed value is the eight-digit form, `YYYYMMDD`.
///
/// Shared by [`Component::is_all_day`], so the parameter and the value form are decided in one place —
/// `DTEND` needs the same answer and would otherwise be a second copy of this test.
///
/// Deliberately not "is it eight characters": `parse_time` requires all eight to be digits as well, and a
/// predicate that disagreed with the parser about which strings are dates would reintroduce the mismatch
/// this fixes.
fn is_date_form(value: &str) -> bool {
    let value = value.trim();
    value.len() == 8 && value.bytes().all(|b| b.is_ascii_digit())
}

fn calendar_name(text: &str) -> String {
    for line in unfolded(text) {
        let Some((name, value)) = split_property(&line) else { continue };
        // X-WR-CALNAME is not in the spec but is what every provider actually writes.
        if name == "X-WR-CALNAME" {
            return unescape(&value);
        }
    }
    String::new()
}

/// One `VEVENT`, as a bag of properties.
#[derive(Debug, Clone, Default)]
struct Component {
    properties: Vec<(String, BTreeMap<String, String>, String)>,
}

impl Component {
    fn property(&self, name: &str) -> Option<&(String, BTreeMap<String, String>, String)> {
        self.properties.iter().find(|(n, _, _)| n == name)
    }

    fn text(&self, name: &str) -> Option<String> {
        self.property(name).map(|(_, _, value)| unescape(value))
    }

    fn is_cancelled(&self) -> bool {
        self.text("STATUS").is_some_and(|s| s.eq_ignore_ascii_case("CANCELLED"))
    }

    /// Whether `DTSTART` names a whole day rather than an instant.
    ///
    /// **Two spellings, and only one was recognised** — P2-8. RFC 5545 permits an all-day start either
    /// as `DTSTART;VALUE=DATE:20260904` or as the bare value form `DTSTART:20260904`, and providers write
    /// both. Checking only the parameter meant the value form was treated as a *timed* event with no
    /// `DTEND`, so `span` produced a zero-length interval, the overlap test rejected it, and an ordinary
    /// all-day "Holiday" or "Leave" entry was **dropped entirely** — a calendar rule silently stopping on
    /// exactly the days it was set up to cover.
    ///
    /// `parse_time` already accepted both forms; this is the half that decides how long the event is.
    fn is_all_day(&self) -> bool {
        let Some((_, params, value)) = self.property("DTSTART") else { return false };
        params.get("VALUE").is_some_and(|v| v == "DATE") || is_date_form(value)
    }

    /// Busy unless the calendar says otherwise. `TRANSP:TRANSPARENT` is how a provider marks the
    /// placeholder entries — "out of office", "birthday" — that a focus rule should skip.
    fn is_busy(&self) -> bool {
        !self.text("TRANSP").is_some_and(|t| t.eq_ignore_ascii_case("TRANSPARENT"))
    }

    fn categories(&self) -> Vec<String> {
        self.properties
            .iter()
            .filter(|(name, _, _)| name == "CATEGORIES")
            .flat_map(|(_, _, value)| {
                unescape(value).split(',').map(str::to_string).collect::<Vec<_>>()
            })
            .map(|c| c.trim().to_string())
            .filter(|c| !c.is_empty())
            .collect()
    }

    fn time(&self, name: &str, default_tz: Tz) -> Option<Timestamp> {
        let (_, params, value) = self.property(name)?;
        parse_time(value, params, default_tz)
    }

    /// The first occurrence's span. `DTEND` wins over `DURATION`; a `DTSTART` with neither is an
    /// instant for a timed event and a whole day for a dated one, which is what the spec says.
    fn span(&self, default_tz: Tz) -> Option<(Timestamp, Timestamp)> {
        let start = self.time("DTSTART", default_tz)?;
        if let Some(end) = self.time("DTEND", default_tz) {
            return Some((start, end.max(start)));
        }
        if let Some(duration) = self.text("DURATION").and_then(|d| parse_duration(&d)) {
            return Some((start, start + duration));
        }
        let length = if self.is_all_day() { 24 * 60 * 60 } else { 0 };
        Some((start, start + length))
    }

    fn to_event(
        &self,
        id: String,
        start: Timestamp,
        end: Timestamp,
        calendar: &str,
    ) -> CalendarEvent {
        CalendarEvent {
            id,
            title: self.text("SUMMARY").unwrap_or_default(),
            calendar: calendar.to_string(),
            location: self.text("LOCATION").unwrap_or_default(),
            start,
            end,
            all_day: self.is_all_day(),
            busy: self.is_busy(),
            categories: self.categories(),
        }
    }

    fn occurrences(
        &self,
        from: Timestamp,
        to: Timestamp,
        default_tz: Tz,
        calendar: &str,
        changed: &BTreeMap<Timestamp, Component>,
    ) -> Vec<CalendarEvent> {
        let Some(uid) = self.text("UID") else { return Vec::new() };
        let Some((first_start, first_end)) = self.span(default_tz) else { return Vec::new() };
        let length = first_end - first_start;
        let excluded = self.exceptions(default_tz);

        let mut out = Vec::new();
        for start in self.starts(first_start, from, to, default_tz) {
            if excluded.contains(&start) {
                continue;
            }
            // A moved or cancelled occurrence is described by its own component, and that
            // description replaces this one entirely.
            if let Some(replacement) = changed.get(&start) {
                if replacement.is_cancelled() {
                    continue;
                }
                let Some((moved_start, moved_end)) = replacement.span(default_tz) else { continue };
                if moved_start < to && moved_end > from {
                    out.push(replacement.to_event(
                        occurrence_id(&uid, start),
                        moved_start,
                        moved_end,
                        calendar,
                    ));
                }
                continue;
            }
            let end = start + length;
            if start < to && end > from {
                out.push(self.to_event(occurrence_id(&uid, start), start, end, calendar));
            }
        }
        out
    }

    fn exceptions(&self, default_tz: Tz) -> BTreeSet<Timestamp> {
        self.properties
            .iter()
            .filter(|(name, _, _)| name == "EXDATE")
            .flat_map(|(_, params, value)| {
                value
                    .split(',')
                    .filter_map(|one| parse_time(one, params, default_tz))
                    .collect::<Vec<_>>()
            })
            .collect()
    }

    /// Every start instant this component produces that could overlap the window, plus the ones
    /// `RDATE` adds outright.
    fn starts(
        &self,
        first: Timestamp,
        from: Timestamp,
        to: Timestamp,
        default_tz: Tz,
    ) -> Vec<Timestamp> {
        let mut starts: BTreeSet<Timestamp> = BTreeSet::new();
        starts.insert(first);
        for (name, params, value) in &self.properties {
            if name == "RDATE" {
                for one in value.split(',') {
                    if let Some(at) = parse_time(one, params, default_tz) {
                        starts.insert(at);
                    }
                }
            }
        }
        if let Some((_, _, rule)) = self.property("RRULE") {
            let zone = self.zone(default_tz);
            starts.extend(expand(rule, first, to, zone));
        }
        // A window starts before `from` when the event is long enough to still be running, so the
        // filter here is deliberately generous; `occurrences` does the exact overlap test.
        starts.into_iter().filter(|&at| at < to || at >= from).collect()
    }

    /// The zone recurrence is counted in.
    ///
    /// This matters and is the usual source of "my 9am block starts at 8am in winter": a weekly
    /// rule repeats at the same *local* time, so the arithmetic has to happen in the event's own
    /// zone and only then be turned back into an instant.
    fn zone(&self, default_tz: Tz) -> Tz {
        self.property("DTSTART")
            .and_then(|(_, params, _)| params.get("TZID"))
            .and_then(|name| name.parse::<Tz>().ok())
            .unwrap_or(default_tz)
    }
}

/// Split the file into `VEVENT` components. Nested components other than events are skipped, which
/// is what drops `VTIMEZONE` and `VALARM` blocks along with everything Curfew does not read.
fn components(text: &str) -> Vec<Component> {
    let mut out = Vec::new();
    let mut current: Option<Component> = None;
    let mut depth_inside_event = 0usize;
    for line in unfolded(text) {
        let Some((name, value)) = split_property(&line) else { continue };
        let bare = name.split(';').next().unwrap_or(&name).to_string();
        match (bare.as_str(), value.as_str()) {
            ("BEGIN", "VEVENT") => current = Some(Component::default()),
            ("END", "VEVENT") => {
                if let Some(component) = current.take() {
                    out.push(component);
                }
                depth_inside_event = 0;
            }
            ("BEGIN", _) if current.is_some() => depth_inside_event += 1,
            ("END", _) if current.is_some() => {
                depth_inside_event = depth_inside_event.saturating_sub(1)
            }
            _ => {
                if depth_inside_event > 0 {
                    continue;
                }
                if let Some(component) = current.as_mut() {
                    let (property, params) = split_params(&name);
                    component.properties.push((property, params, value));
                }
            }
        }
    }
    out
}

/// Undo RFC 5545 line folding: a continuation line starts with a space or tab and belongs to the
/// line before it. Providers fold in the middle of words, so this cannot be skipped.
fn unfolded(text: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for raw in text.split('\n') {
        let line = raw.strip_suffix('\r').unwrap_or(raw);
        if line.starts_with(' ') || line.starts_with('\t') {
            if let Some(last) = out.last_mut() {
                last.push_str(&line[1..]);
                continue;
            }
        }
        out.push(line.to_string());
    }
    out
}

/// `NAME;PARAM=x:value` into its name-with-params and its value. The colon inside a quoted
/// parameter is not a separator, which is how `ALTREP="http://..."` avoids truncating the line.
fn split_property(line: &str) -> Option<(String, String)> {
    let mut quoted = false;
    for (i, c) in line.char_indices() {
        match c {
            '"' => quoted = !quoted,
            ':' if !quoted => {
                return Some((line[..i].to_ascii_uppercase(), line[i + 1..].to_string()))
            }
            _ => {}
        }
    }
    None
}

fn split_params(name_with_params: &str) -> (String, BTreeMap<String, String>) {
    let mut parts = name_with_params.split(';');
    let name = parts.next().unwrap_or_default().to_string();
    let mut params = BTreeMap::new();
    for part in parts {
        if let Some((key, value)) = part.split_once('=') {
            params.insert(key.to_ascii_uppercase(), value.trim_matches('"').to_string());
        }
    }
    (name, params)
}

fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n') | Some('N') => out.push('\n'),
            Some(other) => out.push(other),
            None => break,
        }
    }
    out
}

/// `20260904T093000Z`, `20260904T093000` with a `TZID`, or `20260904` for a dated value.
fn parse_time(value: &str, params: &BTreeMap<String, String>, default_tz: Tz) -> Option<Timestamp> {
    let value = value.trim();
    if value.len() == 8 {
        let date = NaiveDate::parse_from_str(value, "%Y%m%d").ok()?;
        let zone = params.get("TZID").and_then(|n| n.parse::<Tz>().ok()).unwrap_or(default_tz);
        // An all-day event starts when that day starts where the user is, not at UTC midnight.
        // Getting this wrong shifts every all-day block by the offset, which in London is an hour
        // in summer and none in winter — the classic way a calendar feature looks haunted.
        return Some(local_midnight(date, zone));
    }
    if let Some(stripped) = value.strip_suffix('Z') {
        let naive = NaiveDateTime::parse_from_str(stripped, "%Y%m%dT%H%M%S").ok()?;
        return Some(Utc.from_utc_datetime(&naive).timestamp());
    }
    let naive = NaiveDateTime::parse_from_str(value, "%Y%m%dT%H%M%S").ok()?;
    let zone = params.get("TZID").and_then(|n| n.parse::<Tz>().ok()).unwrap_or(default_tz);
    Some(in_zone(naive, zone))
}

/// A local wall-clock time as an instant.
///
/// The two awkward cases are the whole reason this is a function: the hour that does not exist in
/// spring takes the instant the clock jumps to, and the hour that happens twice in autumn takes the
/// first. Both are choices, and both are better than refusing to schedule anything that day.
fn in_zone(naive: NaiveDateTime, zone: Tz) -> Timestamp {
    match zone.from_local_datetime(&naive) {
        chrono::LocalResult::Single(at) => at.timestamp(),
        chrono::LocalResult::Ambiguous(first, _) => first.timestamp(),
        chrono::LocalResult::None => {
            let mut probe = naive;
            for _ in 0..4 {
                probe += Duration::hours(1);
                if let chrono::LocalResult::Single(at) = zone.from_local_datetime(&probe) {
                    return at.timestamp();
                }
            }
            Utc.from_utc_datetime(&naive).timestamp()
        }
    }
}

fn local_midnight(date: NaiveDate, zone: Tz) -> Timestamp {
    in_zone(date.and_hms_opt(0, 0, 0).expect("midnight is a valid time"), zone)
}

/// `P1DT2H30M` and friends, in seconds. Weeks and days only; months in a DURATION are not legal.
fn parse_duration(text: &str) -> Option<Timestamp> {
    let text = text.trim();
    let (sign, rest) = match text.strip_prefix('-') {
        Some(rest) => (-1, rest),
        None => (1, text.strip_prefix('+').unwrap_or(text)),
    };
    let rest = rest.strip_prefix('P')?;
    let mut seconds: Timestamp = 0;
    let mut number = String::new();
    for c in rest.chars() {
        match c {
            'T' => continue,
            '0'..='9' => number.push(c),
            unit => {
                let value: Timestamp = number.parse().ok()?;
                number.clear();
                seconds += match unit {
                    'W' => value * 7 * 24 * 60 * 60,
                    'D' => value * 24 * 60 * 60,
                    'H' => value * 60 * 60,
                    'M' => value * 60,
                    'S' => value,
                    _ => return None,
                };
            }
        }
    }
    Some(sign * seconds)
}

/// Every comma-separated token, or `None` if any of them cannot be understood.
///
/// `None` means "refuse the rule", and it is deliberately not an empty `Vec`: an empty *constraint* list
/// means "unconstrained" to the caller, which is the widening this exists to prevent. Two distinct
/// meanings, so two distinct return shapes.
fn collect<T>(raw: Option<&String>, parse: fn(&str) -> Option<T>) -> Option<Vec<T>> {
    let Some(raw) = raw else { return Some(Vec::new()) };
    raw.split(',').map(parse).collect()
}

/// The same for a list of integers, which is `BYMONTHDAY` and `BYMONTH`.
///
/// **Signed, because `BYMONTHDAY` is** — P2-8. `-1` is the last day of the month and is ordinary in real
/// calendars. Parsing as `u32` meant it failed, was dropped, and left the rule unconstrained.
fn collect_ints(raw: Option<&String>) -> Option<Vec<i32>> {
    collect(raw, |token| token.trim().parse().ok())
}

/// Whether `day` satisfies a `BYMONTHDAY` list, resolving negatives against the month it is in.
///
/// RFC 5545 counts `-1` from the end of the month, so the answer depends on the month — which is why this
/// cannot be a `contains` on a precomputed list set. February is also why it cannot be "31 minus the
/// ordinal".
fn month_day_matches(tokens: &[i32], cursor: chrono::DateTime<Tz>) -> bool {
    if tokens.is_empty() {
        return true;
    }
    let length = days_in_month(cursor.year(), cursor.month());
    tokens.iter().any(|token| {
        let day = if *token > 0 { *token } else { length + 1 + *token };
        day == cursor.day() as i32
    })
}

/// The number of days in a month, so a negative `BYMONTHDAY` can be resolved against it.
fn days_in_month(year: i32, month: u32) -> i32 {
    let (next_year, next_month) = if month == 12 { (year + 1, 1) } else { (year, month + 1) };
    let first = chrono::NaiveDate::from_ymd_opt(year, month, 1);
    let next = chrono::NaiveDate::from_ymd_opt(next_year, next_month, 1);
    match (first, next) {
        // `signed_duration_since` rather than a table, so leap years need no special case.
        (Some(first), Some(next)) => (next - first).num_days() as i32,
        // Unreachable for a month that came from a real date, and a wrong answer here would widen a
        // constraint — so it refuses instead, by matching nothing.
        _ => 0,
    }
}

/// Expand an `RRULE` into start instants, up to `until`.
///
/// Recurrence is counted in local time and converted back, so a weekly 09:00 meeting stays at
/// 09:00 across a DST change rather than drifting to 08:00 or 10:00.
fn expand(rule: &str, first: Timestamp, until: Timestamp, zone: Tz) -> Vec<Timestamp> {
    let mut parts = BTreeMap::new();
    for piece in rule.split(';') {
        if let Some((key, value)) = piece.split_once('=') {
            parts.insert(key.to_ascii_uppercase(), value.to_ascii_uppercase());
        }
    }
    let frequency = parts.get("FREQ").map(String::as_str).unwrap_or("");
    let interval: i64 = parts.get("INTERVAL").and_then(|i| i.parse().ok()).unwrap_or(1).max(1);
    let count: Option<usize> = parts.get("COUNT").and_then(|c| c.parse().ok());
    let end_by: Option<Timestamp> =
        parts.get("UNTIL").and_then(|u| parse_time(u, &BTreeMap::new(), zone));
    // Rules Curfew cannot expand exactly are not expanded at all: a block that fires on the wrong
    // day teaches the user to distrust the ones that fire on the right day.
    if parts.keys().any(|k| {
        matches!(
            k.as_str(),
            "BYSETPOS" | "BYWEEKNO" | "BYYEARDAY" | "BYHOUR" | "BYMINUTE" | "BYSECOND"
        )
    }) {
        return Vec::new();
    }
    // **A token that cannot be understood refuses the whole rule** — P2-8. `filter_map` used to drop it
    // and leave the list empty, and an empty list means *no constraint*, so the rule widened: `BYDAY=2FR`
    // fired every week instead of on the second Friday, and `BYMONTHDAY=-1` fired every day instead of on
    // the last. Widening is the fail-open direction, and this parser feeds a lock.
    //
    // The module doc already states the rule — "unsupported recurrence should make an event
    // non-recurring". This is the code being made to match it.
    let Some(by_day) = collect(parts.get("BYDAY"), weekday) else { return Vec::new() };
    let Some(by_month_day) = collect_ints(parts.get("BYMONTHDAY")) else { return Vec::new() };
    let Some(by_month) = collect_ints(parts.get("BYMONTH")) else { return Vec::new() };

    let start: DateTime<Tz> = match zone.timestamp_opt(first, 0) {
        chrono::LocalResult::Single(at) => at,
        _ => return Vec::new(),
    };
    let horizon = end_by.map(|by| by.min(until)).unwrap_or(until);

    let mut out = Vec::new();
    let mut produced = 0usize;
    let mut cursor = start;
    let mut steps = 0i64;
    for _ in 0..MAX_ITERATIONS {
        if cursor.timestamp() > horizon || out.len() >= MAX_OCCURRENCES {
            break;
        }
        if count.is_some_and(|c| produced >= c) {
            break;
        }
        let keep = (by_month.is_empty() || by_month.contains(&(cursor.month() as i32)))
            && month_day_matches(&by_month_day, cursor)
            && (by_day.is_empty() || by_day.contains(&cursor.weekday()));
        if keep {
            out.push(cursor.timestamp());
            produced += 1;
        }
        steps += 1;
        cursor = match advance(cursor, start, steps, frequency, interval, &by_day, zone) {
            Some(next) => next,
            None => break,
        };
    }
    out
}

/// Step the cursor forward one unit.
///
/// `BYDAY` on a weekly rule is handled by walking a day at a time and keeping the days that match,
/// which is simpler than generating each week's set and cannot produce an out-of-order result.
fn advance(
    cursor: DateTime<Tz>,
    start: DateTime<Tz>,
    steps: i64,
    frequency: &str,
    interval: i64,
    by_day: &[chrono::Weekday],
    zone: Tz,
) -> Option<DateTime<Tz>> {
    let naive = cursor.naive_local();
    let next = match frequency {
        "SECONDLY" => naive + Duration::seconds(interval),
        "MINUTELY" => naive + Duration::minutes(interval),
        "HOURLY" => naive + Duration::hours(interval),
        "DAILY" => naive + Duration::days(interval),
        "WEEKLY" if by_day.is_empty() => naive + Duration::weeks(interval),
        // With BYDAY, a week is walked a day at a time; the interval is then only meaningful for
        // whole weeks, and INTERVAL>1 with BYDAY is rare enough to be worth refusing rather than
        // approximating.
        "WEEKLY" if interval == 1 => naive + Duration::days(1),
        // Counted from the original start rather than from the previous occurrence: clamping the
        // 31st to the 28th of February must not then hold the rule at the 28th for every month
        // after it. RFC 5545 recurs on the day the series began.
        "MONTHLY" => add_months(start.naive_local(), interval * steps)?,
        "YEARLY" => add_months(start.naive_local(), interval * 12 * steps)?,
        _ => return None,
    };
    match zone.from_local_datetime(&next) {
        chrono::LocalResult::Single(at) => Some(at),
        chrono::LocalResult::Ambiguous(first, _) => Some(first),
        // A recurrence that lands in the hour a clock skips is moved forward rather than dropped;
        // the meeting still happens, and a missing block is the worse failure.
        chrono::LocalResult::None => {
            zone.from_local_datetime(&(next + Duration::hours(1))).single()
        }
    }
}

/// Add whole months, clamping to the end of a short month.
///
/// The 31st in a 30-day month becomes the 30th rather than spilling into the next one, because a
/// block that lands on the 1st is a block on the wrong day.
fn add_months(naive: NaiveDateTime, months: i64) -> Option<NaiveDateTime> {
    let total = naive.year() as i64 * 12 + (naive.month0() as i64) + months;
    let year = (total.div_euclid(12)) as i32;
    let month = (total.rem_euclid(12) + 1) as u32;
    let mut day = naive.day();
    loop {
        if let Some(date) = NaiveDate::from_ymd_opt(year, month, day) {
            return date.and_hms_opt(naive.hour(), naive.minute(), naive.second());
        }
        day -= 1;
        if day == 0 {
            return None;
        }
    }
}

fn weekday(code: &str) -> Option<chrono::Weekday> {
    // An ordinal prefix ("2FR" — the second Friday) is not supported, and **the rule is now refused**
    // rather than dropped. It used to be dropped, which left the day list empty — and an empty list means
    // "every day", so the rule became "every Friday" and then, through the weekly branch, every week. The
    // comment here claimed refusal at the time; it was describing behaviour the code did not have, which
    // is the same false-guarantee defect this branch has now corrected fourteen times.
    match code.trim() {
        "MO" => Some(chrono::Weekday::Mon),
        "TU" => Some(chrono::Weekday::Tue),
        "WE" => Some(chrono::Weekday::Wed),
        "TH" => Some(chrono::Weekday::Thu),
        "FR" => Some(chrono::Weekday::Fri),
        "SA" => Some(chrono::Weekday::Sat),
        "SU" => Some(chrono::Weekday::Sun),
        _ => None,
    }
}

#[cfg(test)]
mod date_form_tests {
    use super::*;

    /// **`is_date_form` and `parse_time` must agree about which strings are dates.**
    ///
    /// They are two halves of one decision: `parse_time` says what instant a `DTSTART` names, and
    /// `is_all_day` says how long the event is. When they disagreed — `parse_time` accepting the bare
    /// eight-digit form while `is_all_day` only recognised the `VALUE=DATE` parameter — an all-day entry
    /// with no `DTEND` became a zero-length event and was dropped entirely (P2-8).
    ///
    /// **Why this is a unit test rather than a behaviour test.** The digit check in `is_date_form` cannot
    /// be reached through the public API: eight non-digit characters fail `parse_time` first, so no event
    /// is produced either way and no mutation of the check can fail a behaviour test. That does not make it
    /// dead code — it makes it a guard against the two functions drifting apart, which is exactly what this
    /// asserts.
    #[test]
    fn the_two_date_parsers_agree() {
        let params = BTreeMap::new();
        for value in [
            // The value form, which is the case the whole finding is about.
            "20260904",
            // **Exactly eight characters, and not a date.** These are the cases that exercise the digit
            // check, and the first version of this list got them wrong: `"20260904T0"` is ten characters
            // and `"2026-09-0"` is nine, so neither reached the check at all and relaxing it to
            // `len() == 8` survived the mutation run.
            "abcdefgh",
            "2026-090",
            "2026090a",
            // Length boundaries, which the check also decides.
            "2026090",
            "202609040",
            // Timed forms: not dates, whatever they land on.
            "20260904T090000Z",
            "20260904T090000",
            "not a date at all",
            "",
        ] {
            let parses_as_date = parse_time(value, &params, chrono_tz::UTC)
                // `parse_time` returns a timestamp for both forms, so "is it a date" is asked by
                // comparing what it produced against that day's midnight — which is what a DATE value
                // means. A bare equality on the instant would be true for a timed event at 00:00:00Z too.
                .is_some_and(|at| at % 86_400 == 0);
            let looks_like_a_date = is_date_form(value);

            // The property is one-directional and that is deliberate: anything `is_date_form` accepts must
            // be a date `parse_time` can read. The converse is not required — a *timed* value that happens
            // to land on midnight is not a date, and treating it as one would make a 00:00 meeting last a
            // whole day.
            if looks_like_a_date {
                assert!(
                    parses_as_date,
                    "`is_date_form` called {value:?} a date and `parse_time` did not read it as one, \
                     which is the mismatch that drops all-day events"
                );
            }
        }

        // And the two forms RFC 5545 permits are both recognised, since that is the substance.
        assert!(is_date_form("20260904"));
        assert!(!is_date_form("20260904T090000Z"));
        assert!(!is_date_form("2026-09-04"));
    }
}
