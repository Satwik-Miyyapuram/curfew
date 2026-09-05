//! When a profile is supposed to be running: recurring weekly windows, and calendar events.
//!
//! Both answer the same question — "which profiles should be active at this instant, and how
//! strongly locked" — so they produce the same thing: [`Activation`]s. The platform supplies a
//! *snapshot* of calendar events it already read (`calendar.snapshot`, GAPS B3); nothing here
//! talks to a calendar provider, because nothing here talks to anything.
//!
//! Windows are half-open, `[start, end)`. An event that ends at 10:00 and one that starts at 10:00
//! do not overlap, and a session does not linger for a second past its end.

use crate::lock::{Lock, LockSet};
use crate::target::glob_match;
use crate::Timestamp;
use chrono::{Datelike, Duration, NaiveDate, TimeZone, Timelike};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};

/// A recurring weekly window: "weekday mornings, 09:00 to 12:00".
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WeeklySchedule {
    pub id: String,
    /// The profile this window runs.
    pub profile: String,
    /// Days it runs on, 0 = Monday. Empty means every day.
    #[serde(default)]
    pub days: Vec<u8>,
    /// Local minutes after midnight. `end_minute <= start_minute` means the window runs past
    /// midnight into the next day, which is the shape every "no phone after 11pm" rule has.
    pub start_minute: u32,
    pub end_minute: u32,
    /// Conditions guarding the session this window starts.
    #[serde(default)]
    pub locks: Vec<Lock>,
}

/// A calendar event as the platform read it. Times are absolute instants; an all-day event is
/// reported as the instants its local day starts and ends.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CalendarEvent {
    pub id: String,
    pub title: String,
    /// The calendar the event came from, as the provider names it.
    #[serde(default)]
    pub calendar: String,
    #[serde(default)]
    pub location: String,
    pub start: Timestamp,
    pub end: Timestamp,
    #[serde(default)]
    pub all_day: bool,
    /// Free/busy status. A "free" event is usually a placeholder, so matchers can skip them.
    #[serde(default)]
    pub busy: bool,
    /// Categories the event carries, where the provider has them. ICS files do; Android's calendar
    /// provider does not, so this stays empty there rather than being guessed at.
    #[serde(default)]
    pub categories: Vec<String>,
}

impl CalendarEvent {
    /// How long the event runs. Saturating, because an event whose end precedes its start is a
    /// provider bug and should read as "no time at all" rather than as an enormous block.
    pub fn duration_seconds(&self) -> u64 {
        self.end.saturating_sub(self.start).max(0) as u64
    }
}

/// Turns calendar events into sessions: "anything on my Work calendar titled *focus* runs the
/// deep-work profile, starting five minutes early".
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CalendarSchedule {
    pub id: String,
    pub profile: String,
    /// All of these must match. An empty matcher matches every event, which is a real thing to
    /// want ("everything on my Focus calendar") but only ever in combination with `calendar`.
    #[serde(default)]
    pub matcher: EventMatcher,
    /// Start this many seconds before the event, and keep running this many seconds after. Padding
    /// is what makes a calendar-driven block usable: the lock is already up when the meeting
    /// starts (GAPS B3).
    #[serde(default)]
    pub pad_before_seconds: u32,
    #[serde(default)]
    pub pad_after_seconds: u32,
    #[serde(default)]
    pub locks: Vec<Lock>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct EventMatcher {
    /// Glob over the event title.
    #[serde(default)]
    pub title: Option<String>,
    /// Exact (case-insensitive) calendar name.
    #[serde(default)]
    pub calendar: Option<String>,
    /// Glob over the location.
    #[serde(default)]
    pub location: Option<String>,
    /// Only match events marked busy.
    #[serde(default)]
    pub busy_only: bool,
    /// Only match all-day events, or only timed ones. `None` matches either.
    #[serde(default)]
    pub all_day: Option<bool>,
    /// One of these categories must be present, compared case-insensitively. An empty list matches
    /// every event, including one carrying no categories at all.
    #[serde(default)]
    pub categories: Vec<String>,
    /// Ignore anything shorter than this. The rule that wants it is "block during real meetings,
    /// not during the fifteen-minute reminders I scatter through the day".
    #[serde(default)]
    pub min_duration_seconds: Option<u64>,
    /// Ignore anything longer than this. The rule that wants it is "an all-day 'On leave' entry is
    /// not a focus block", which cannot be said with `all_day` alone: a provider may report a
    /// week-long event as an ordinary timed one.
    #[serde(default)]
    pub max_duration_seconds: Option<u64>,
}

impl EventMatcher {
    pub fn matches(&self, event: &CalendarEvent) -> bool {
        if self.busy_only && !event.busy {
            return false;
        }
        if self.all_day.is_some_and(|want| want != event.all_day) {
            return false;
        }
        if let Some(pattern) = &self.title {
            if !glob_match(pattern, &event.title) {
                return false;
            }
        }
        if let Some(pattern) = &self.location {
            if !glob_match(pattern, &event.location) {
                return false;
            }
        }
        if let Some(name) = &self.calendar {
            if !name.eq_ignore_ascii_case(&event.calendar) {
                return false;
            }
        }
        if !self.categories.is_empty() {
            let wanted = self
                .categories
                .iter()
                .any(|want| event.categories.iter().any(|have| have.eq_ignore_ascii_case(want)));
            if !wanted {
                return false;
            }
        }
        // Duration is measured on the event as the calendar has it, before padding. Padding is the
        // user asking for a wider block around a meeting, not a claim that the meeting is longer.
        let duration = event.duration_seconds();
        if self.min_duration_seconds.is_some_and(|least| duration < least) {
            return false;
        }
        if self.max_duration_seconds.is_some_and(|most| duration > most) {
            return false;
        }
        true
    }
}

/// One profile that should be running over one span, and the lock it comes with.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Activation {
    pub profile: String,
    /// Which schedule produced this, for the UI ("running because: Work calendar / Focus block").
    pub source: ActivationSource,
    pub start: Timestamp,
    pub end: Timestamp,
    pub locks: Vec<Lock>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ActivationSource {
    Weekly { schedule: String },
    Calendar { schedule: String, event: String },
}

impl Activation {
    /// The lock a session started from this activation carries: its conditions, ending when the
    /// activation does.
    pub fn lock(&self) -> LockSet {
        LockSet::new(self.locks.iter().cloned(), Some(self.end))
    }
}

impl WeeklySchedule {
    /// The activation covering `now`, if this window is open.
    ///
    /// Windows that cross midnight are handled by also testing yesterday's window, which is why
    /// this looks at two candidate days rather than one.
    pub fn active_at(&self, now: Timestamp, tz: Tz) -> Option<Activation> {
        let today = local_date(now, tz)?;
        for date in [today - Duration::days(1), today] {
            let (start, end) = self.span_on(date, tz)?;
            if self.runs_on(date) && (start..end).contains(&now) {
                return Some(Activation {
                    profile: self.profile.clone(),
                    source: ActivationSource::Weekly { schedule: self.id.clone() },
                    start,
                    end,
                    locks: self.locks.clone(),
                });
            }
        }
        None
    }

    fn runs_on(&self, date: NaiveDate) -> bool {
        let weekday = date.weekday().num_days_from_monday() as u8;
        self.days.is_empty() || self.days.contains(&weekday)
    }

    /// The absolute span of this window when it starts on `date`. A window whose end is at or
    /// before its start runs into the following day.
    fn span_on(&self, date: NaiveDate, tz: Tz) -> Option<(Timestamp, Timestamp)> {
        let start = local_instant(date, self.start_minute, tz)?;
        let end_date =
            if self.end_minute <= self.start_minute { date + Duration::days(1) } else { date };
        let end = local_instant(end_date, self.end_minute, tz)?;
        Some((start, end))
    }
}

impl CalendarSchedule {
    /// Every activation this schedule produces from a snapshot, whether or not it covers `now`.
    /// Used both for enforcement and for the preview timeline.
    pub fn activations(&self, events: &[CalendarEvent]) -> Vec<Activation> {
        events
            .iter()
            .filter(|e| self.matcher.matches(e))
            .map(|e| Activation {
                profile: self.profile.clone(),
                source: ActivationSource::Calendar {
                    schedule: self.id.clone(),
                    event: e.id.clone(),
                },
                start: e.start.saturating_sub(self.pad_before_seconds as i64),
                // A zero-length or inverted event still produces a window that contains its own
                // start, so a malformed calendar entry cannot silently do nothing.
                end: e.end.max(e.start).saturating_add(self.pad_after_seconds as i64 + 1),
                locks: self.locks.clone(),
            })
            .collect()
    }
}

/// Everything that should be running at `now`, from both kinds of schedule.
///
/// Overlapping activations of the same profile are *not* collapsed: each carries its own end time
/// and lock, and merging them here would quietly shorten one of them. Merging is the session
/// layer's job, where the lock lattice guarantees it can only ever add strictness.
pub fn active_at(
    now: Timestamp,
    tz: Tz,
    weekly: &[WeeklySchedule],
    calendars: &[CalendarSchedule],
    events: &[CalendarEvent],
) -> Vec<Activation> {
    let mut out: Vec<Activation> = weekly.iter().filter_map(|w| w.active_at(now, tz)).collect();
    for schedule in calendars {
        out.extend(
            schedule.activations(events).into_iter().filter(|a| (a.start..a.end).contains(&now)),
        );
    }
    out
}

/// The next instant at or after `now` when the set of activations could change, so the platform
/// can set one alarm instead of polling (Android doze, GAPS A5).
pub fn next_change_after(
    now: Timestamp,
    tz: Tz,
    weekly: &[WeeklySchedule],
    calendars: &[CalendarSchedule],
    events: &[CalendarEvent],
) -> Option<Timestamp> {
    let mut best: Option<Timestamp> = None;
    let mut consider = |t: Timestamp| {
        if t > now {
            best = Some(best.map_or(t, |b: Timestamp| b.min(t)));
        }
    };

    for schedule in calendars {
        for a in schedule.activations(events) {
            consider(a.start);
            consider(a.end);
        }
    }

    // A weekly window changes state at most twice a day, so looking a week ahead is exhaustive
    // and bounded -- no schedule can make this loop long.
    if let Some(today) = local_date(now, tz) {
        for w in weekly {
            for offset in -1..=7 {
                let date = today + Duration::days(offset);
                if !w.runs_on(date) {
                    continue;
                }
                if let Some((start, end)) = w.span_on(date, tz) {
                    consider(start);
                    consider(end);
                }
            }
        }
    }
    best
}

fn local_date(now: Timestamp, tz: Tz) -> Option<NaiveDate> {
    tz.timestamp_opt(now, 0).earliest().map(|d| d.date_naive())
}

/// The instant `minute` minutes after local midnight on `date`, resolving DST the same way budget
/// windows do: ambiguous takes the earlier, nonexistent steps forward to the first time that
/// exists. A schedule must never fail to start because the clock skipped its start time.
fn local_instant(date: NaiveDate, minute: u32, tz: Tz) -> Option<Timestamp> {
    let minute = minute.min(24 * 60);
    let (date, minute) =
        if minute == 24 * 60 { (date + Duration::days(1), 0) } else { (date, minute) };
    let naive = date.and_hms_opt(minute / 60, minute % 60, 0)?;
    if let Some(dt) = tz.from_local_datetime(&naive).earliest() {
        return Some(dt.timestamp());
    }
    for extra in 1..=180 {
        let candidate = naive + Duration::minutes(extra);
        if let Some(dt) = tz.from_local_datetime(&candidate).earliest() {
            return Some(dt.timestamp());
        }
    }
    Some(naive.and_utc().timestamp())
}

/// Local wall-clock minutes after midnight, for the UI and for tests.
pub fn local_minute_of_day(at: Timestamp, tz: Tz) -> Option<u32> {
    let dt = tz.timestamp_opt(at, 0).earliest()?;
    Some(dt.hour() * 60 + dt.minute())
}
