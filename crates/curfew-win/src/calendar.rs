//! Where a desktop's calendar events come from.
//!
//! Windows has no system calendar a service can read the way Android's provider can be read, so
//! Curfew subscribes to the same `.ics` documents the user's calendar app does: a file on disk that
//! something else keeps in step, or a subscription URL.
//!
//! Three rules shape everything here, and all three exist because a calendar rule is a *lock*:
//!
//!  - **A failed fetch never releases a block.** The last copy that parsed is kept on disk and goes
//!    on producing events. Someone who wants out of a calendar-driven block must not be able to get
//!    it by pulling the network cable.
//!  - **Nothing is fetched more often than the source says.** A pass runs every two seconds; a
//!    subscription re-fetched on every pass would be indistinguishable from an attack on whoever
//!    hosts it.
//!  - **Fetching is a trait.** The loop is tested against a fetcher that never touches a network,
//!    which is the only way to test what happens when a network fails.

use curfew_core::{CalendarEvent, CalendarSource, Timestamp};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

/// How far either side of `now` events are expanded. Wide enough for any padding a schedule can
/// carry and for the preview timeline, and no wider: expanding a year of someone's meetings to
/// answer a question about this afternoon is over-collection whether or not it ever leaves the
/// machine.
pub const WINDOW_SECONDS: i64 = 36 * 60 * 60;

/// How large a document Curfew will read. A subscription URL is a thing a user pastes, and a
/// service that will read an unbounded response from one is a service that can be made to exhaust
/// the machine's memory by whatever is on the other end.
pub const MAX_BYTES: usize = 8 * 1024 * 1024;

/// Retrieval of one source's text, separated from the policy around it so the policy can be tested.
pub trait Fetch {
    /// The document at `location`, or a human-readable reason it could not be had.
    fn fetch(&self, location: &str) -> Result<String, String>;
}

/// Reads local paths, and refuses everything else.
///
/// The default on a machine with no HTTP client compiled in. A URL is reported as unsupported
/// rather than silently producing no events, because "no events" and "this rule is not running" are
/// different things and only the second is worth telling the user about.
#[derive(Debug, Default, Clone, Copy)]
pub struct LocalFiles;

impl Fetch for LocalFiles {
    fn fetch(&self, location: &str) -> Result<String, String> {
        if is_url(location) {
            return Err("this build of Curfew cannot fetch calendar subscriptions".to_string());
        }
        let data = std::fs::read(location).map_err(|e| e.to_string())?;
        if data.len() > MAX_BYTES {
            return Err(format!("calendar file is larger than {MAX_BYTES} bytes"));
        }
        String::from_utf8(data).map_err(|_| "calendar file is not text".to_string())
    }
}

pub fn is_url(location: &str) -> bool {
    let lower = location.to_ascii_lowercase();
    lower.starts_with("http://") || lower.starts_with("https://") || lower.starts_with("webcal://")
}

/// `webcal://` is a calendar subscription wearing a scheme browsers do not know; it is fetched over
/// https like everything else.
pub fn as_http(location: &str) -> String {
    match location.strip_prefix("webcal://").or_else(|| location.strip_prefix("WEBCAL://")) {
        Some(rest) => format!("https://{rest}"),
        None => location.to_string(),
    }
}

/// What happened to one source on one refresh, for the audit log and the UI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// Fetched and parsed. Carries how many events the window produced.
    Refreshed { id: String, events: usize },
    /// Not due yet. Nothing was fetched and the cached copy is still in use.
    Fresh { id: String },
    /// Could not be fetched or would not parse. Carries whether a usable cached copy remains.
    Failed { id: String, detail: String, still_serving: bool },
}

/// One source's last good document, and when it was taken.
#[derive(Debug, Clone)]
struct Cached {
    text: String,
    at: Timestamp,
}

/// The calendar sources a machine is subscribed to.
///
/// Holds the last good copy of each in memory and on disk, so a service restart during an outage
/// does not lose the blocks a subscription was driving.
pub struct Feeds {
    dir: PathBuf,
    cache: BTreeMap<String, Cached>,
}

impl Feeds {
    /// `dir` is where cached copies are written. It sits beside the service's state, which on an
    /// installed service means a directory a standard user cannot write — otherwise editing the
    /// cache would be a way to delete tomorrow's block.
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into(), cache: BTreeMap::new() }
    }

    fn path(&self, id: &str) -> PathBuf {
        // Source ids come from the config, which the user writes; a name is not a path.
        let safe: String = id
            .chars()
            .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
            .collect();
        self.dir.join(format!("{safe}.ics"))
    }

    /// Load whatever the last run cached, so an outage that spans a restart still blocks.
    ///
    /// A cached copy is loaded with an unknown age (`at = 0`), which makes every source due on the
    /// first pass after a restart — the right way round, since the alternative is honouring an hour
    /// of freshness for a document that may be a week old.
    pub fn restore(&mut self, sources: &[CalendarSource]) {
        for source in sources {
            if let Ok(text) = std::fs::read_to_string(self.path(&source.id)) {
                self.cache.insert(source.id.clone(), Cached { text, at: 0 });
            }
        }
    }

    /// Refresh whatever is due and return every event in the window around `now`.
    ///
    /// The events come back merged across sources and sorted by start, which is what a schedule
    /// wants and what makes a preview timeline readable.
    pub fn events(
        &mut self,
        now: Timestamp,
        sources: &[CalendarSource],
        zone: chrono_tz::Tz,
        fetcher: &impl Fetch,
    ) -> (Vec<CalendarEvent>, Vec<Outcome>) {
        let mut events = Vec::new();
        let mut outcomes = Vec::new();

        for source in sources {
            let due = match self.cache.get(&source.id) {
                Some(cached) => now.saturating_sub(cached.at) >= source.refresh_seconds as i64,
                None => true,
            };

            if due {
                match fetcher.fetch(&as_http(&source.location)) {
                    Ok(text) => match curfew_ics::events_between(&text, 0, 0, zone) {
                        // Parsed as a calendar, so it is safe to keep. Whether this particular
                        // window has any events in it says nothing about the document's validity.
                        Ok(_) => {
                            let _ = std::fs::create_dir_all(&self.dir);
                            let _ = std::fs::write(self.path(&source.id), &text);
                            self.cache.insert(source.id.clone(), Cached { text, at: now });
                        }
                        Err(e) => outcomes.push(Outcome::Failed {
                            id: source.id.clone(),
                            detail: e.to_string(),
                            still_serving: self.cache.contains_key(&source.id),
                        }),
                    },
                    Err(detail) => outcomes.push(Outcome::Failed {
                        id: source.id.clone(),
                        detail,
                        still_serving: self.cache.contains_key(&source.id),
                    }),
                }
            }

            let Some(cached) = self.cache.get(&source.id) else { continue };
            let found = curfew_ics::events_between(
                &cached.text,
                now - WINDOW_SECONDS,
                now + WINDOW_SECONDS,
                zone,
            )
            .unwrap_or_default();

            if !outcomes.iter().any(|o| matches!(o, Outcome::Failed { id, .. } if id == &source.id))
            {
                outcomes.push(if due {
                    Outcome::Refreshed { id: source.id.clone(), events: found.len() }
                } else {
                    Outcome::Fresh { id: source.id.clone() }
                });
            }

            // Two sources can carry the same event — a work calendar exported twice, a shared
            // meeting on both accounts. The source id joins the event id so one appearance cannot
            // silently stand in for the other, and so ending one does not look like ending both.
            events.extend(found.into_iter().map(|mut event| {
                event.id = format!("{}/{}", source.id, event.id);
                event
            }));
        }

        events.sort_by_key(|e| (e.start, e.id.clone()));
        (events, outcomes)
    }

    /// The cached document for a source, if there is one. For tests and for the UI's "last fetched"
    /// line.
    pub fn cached(&self, id: &str) -> Option<&str> {
        self.cache.get(id).map(|c| c.text.as_str())
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }
}
