//! The Android binding: one UniFFI-exported object wrapping the pure core.
//!
//! Structured values cross this boundary as JSON rather than as generated record types. That is a
//! deliberate trade. The alternative is mirroring every enum in the core into UniFFI's type system,
//! which means every change to a rule kind is a change in four places and a chance for Kotlin and
//! Rust to disagree about what a config means -- the exact divergence the shared core exists to
//! prevent. Serde is already the source of truth for the config file, so reusing it here keeps one
//! definition of every shape. The cost is a JSON encode per call, on a path that runs once per
//! foreground change (tens of times a minute at worst), not per frame.
//!
//! Nothing here reads a clock. `now` comes from the caller, exactly as it does in the core.

use curfew_core::config::Platform;
use curfew_core::engine::{charged_keys, State};
use curfew_core::schedule::{active_at, next_change_after, CalendarEvent};
use curfew_core::session::{reconcile, Session, Sessions};
use curfew_core::target::Observation;
use curfew_core::{decide, Config, Lock, Timestamp};
use std::collections::BTreeSet;
use std::sync::{Arc, RwLock};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CurfewError {
    /// The config file could not be loaded. The message is the one the core produced, which is
    /// written for the person who wrote the file.
    #[error("{detail}")]
    Config { detail: String },
    /// A JSON payload from the platform side was malformed. Always a bug in the app, never
    /// something a user can cause, so it is loud rather than silent.
    #[error("bad payload: {detail}")]
    Payload { detail: String },
    /// A session could not be ended. `refusal` is the serialized `Refusal` from the core, so the
    /// UI can say exactly which conditions are still missing.
    #[error("refused: {refusal}")]
    Refused { refusal: String },
}

fn payload<E: std::fmt::Display>(e: E) -> CurfewError {
    CurfewError::Payload { detail: e.to_string() }
}

/// The whole policy surface, as one object the Android service holds for its lifetime.
///
/// The config is parsed once. Sessions live here too, because every path that can end a session
/// early has to go through the core's single `end`, and putting them behind this lock is what
/// stops the Kotlin side from ever holding a `Sessions` it could edit directly.
#[derive(Debug, uniffi::Object)]
pub struct Curfew {
    config: RwLock<Config>,
    sessions: RwLock<Sessions>,
}

#[uniffi::export]
impl Curfew {
    /// Load a config. Fails rather than half-loading: a config we cannot fully understand is
    /// refused so it can never be written back with the user's rules missing.
    #[uniffi::constructor]
    pub fn new(config_toml: String) -> Result<Arc<Self>, CurfewError> {
        let config = Config::from_toml(&config_toml)
            .map_err(|e| CurfewError::Config { detail: e.to_string() })?;
        Ok(Arc::new(Self {
            config: RwLock::new(config),
            sessions: RwLock::new(Sessions::default()),
        }))
    }

    /// Replace the config. Running sessions are untouched: a config edit is not a way out of a
    /// lock, and the session already holds its own copy of what it promised.
    pub fn set_config(&self, config_toml: String) -> Result<(), CurfewError> {
        let config = Config::from_toml(&config_toml)
            .map_err(|e| CurfewError::Config { detail: e.to_string() })?;
        *self.config.write().expect("config lock") = config;
        Ok(())
    }

    pub fn config_toml(&self) -> Result<String, CurfewError> {
        self.config
            .read()
            .expect("config lock")
            .to_toml()
            .map_err(|e| CurfewError::Config { detail: e.to_string() })
    }

    /// The decision for one observation. `observation_json` is a serialized `Observation`; the
    /// answer is a serialized `Decision`.
    ///
    /// This is the hot path -- it runs on every foreground change -- so it takes read locks only
    /// and allocates nothing but the two JSON strings.
    pub fn decide(
        &self,
        now: Timestamp,
        observation_json: String,
        platform: PlatformName,
        usage_json: String,
    ) -> Result<String, CurfewError> {
        let obs: Observation = serde_json::from_str(&observation_json).map_err(payload)?;
        let mut state: State =
            serde_json::from_str(if usage_json.is_empty() { "{}" } else { &usage_json })
                .map_err(payload)?;
        let sessions = self.sessions.read().expect("sessions lock");
        state.active_profiles = sessions.active_profiles(now);
        state.lock = sessions.merged_lock(now);
        state.platform = platform.into();

        let decision = decide(now, &state, &obs, &self.config.read().expect("config lock"));
        serde_json::to_string(&decision).map_err(payload)
    }

    /// The usage keys this observation should be charged against, given the profiles running now.
    ///
    /// The platform records time and launches; only the core knows which rule target a given
    /// observation falls under, so it says so here rather than having Kotlin guess (which is how a
    /// budget silently stops counting).
    pub fn charged_keys(
        &self,
        now: Timestamp,
        observation_json: String,
        platform: PlatformName,
    ) -> Result<Vec<String>, CurfewError> {
        let obs: Observation = serde_json::from_str(&observation_json).map_err(payload)?;
        let mut state = State::default();
        let sessions = self.sessions.read().expect("sessions lock");
        state.active_profiles = sessions.active_profiles(now);
        state.platform = platform.into();
        Ok(charged_keys(&state, &obs, &self.config.read().expect("config lock")))
    }

    /// Start a session by hand. Idempotent per profile: starting one that is already running
    /// merges the locks, which can only ever add strictness.
    pub fn start_session(&self, session_json: String) -> Result<(), CurfewError> {
        let session: Session = serde_json::from_str(&session_json).map_err(payload)?;
        self.sessions.write().expect("sessions lock").start(session);
        Ok(())
    }

    /// End a session. `satisfied_json` is the evidence the platform collected -- for example
    /// `[{"kind":"device_credential"}]` after `BiometricPrompt` returned success.
    pub fn end_session(
        &self,
        id: String,
        now: Timestamp,
        satisfied_json: String,
    ) -> Result<(), CurfewError> {
        let satisfied: BTreeSet<Lock> =
            serde_json::from_str(if satisfied_json.is_empty() { "[]" } else { &satisfied_json })
                .map_err(payload)?;
        self.sessions.write().expect("sessions lock").end(&id, now, &satisfied).map(|_| ()).map_err(
            |refusal| CurfewError::Refused {
                refusal: serde_json::to_string(&refusal).unwrap_or_else(|_| "{}".into()),
            },
        )
    }

    /// Start the 24-hour delayed release, returning when it lands. Never movable later.
    pub fn request_release(&self, id: String, now: Timestamp) -> Result<Timestamp, CurfewError> {
        self.sessions.write().expect("sessions lock").request_release(&id, now).map_err(|refusal| {
            CurfewError::Refused {
                refusal: serde_json::to_string(&refusal).unwrap_or_else(|_| "{}".into()),
            }
        })
    }

    /// Bring sessions into line with the schedules, given the calendar snapshot the platform read.
    /// Returns the ids of sessions this started, for the notification and the log.
    pub fn reconcile(
        &self,
        now: Timestamp,
        events_json: String,
        id_seed: String,
    ) -> Result<Vec<String>, CurfewError> {
        let events: Vec<CalendarEvent> =
            serde_json::from_str(if events_json.is_empty() { "[]" } else { &events_json })
                .map_err(payload)?;
        let config = self.config.read().expect("config lock");
        let tz = config.tz().map_err(|e| CurfewError::Config { detail: e.to_string() })?;
        let activations = active_at(now, tz, &config.weekly, &config.calendars, &events);

        let mut counter = 0usize;
        let mut sessions = self.sessions.write().expect("sessions lock");
        Ok(reconcile(now, &mut sessions, &activations, |a| {
            counter += 1;
            format!("{id_seed}-{}-{counter}", a.profile)
        }))
    }

    /// Sessions whose time is up, removed and handed back so the caller can notify.
    pub fn reap(&self, now: Timestamp) -> Result<String, CurfewError> {
        let reaped = self.sessions.write().expect("sessions lock").reap(now);
        serde_json::to_string(&reaped).map_err(payload)
    }

    pub fn sessions_json(&self) -> Result<String, CurfewError> {
        serde_json::to_string(&*self.sessions.read().expect("sessions lock")).map_err(payload)
    }

    /// Restore sessions materialized from storage after a restart. The lock a session was under
    /// survives a reboot, a force-stop and an app update -- that is the entire point of persisting
    /// them (design invariant 2).
    pub fn restore_sessions(&self, sessions_json: String) -> Result<(), CurfewError> {
        let sessions: Sessions = serde_json::from_str(&sessions_json).map_err(payload)?;
        *self.sessions.write().expect("sessions lock") = sessions;
        Ok(())
    }

    pub fn active_profiles(&self, now: Timestamp) -> Vec<String> {
        self.sessions.read().expect("sessions lock").active_profiles(now)
    }

    /// The merged lock, as JSON, for the "you are locked until..." screen.
    pub fn merged_lock_json(&self, now: Timestamp) -> Result<String, CurfewError> {
        serde_json::to_string(&self.sessions.read().expect("sessions lock").merged_lock(now))
            .map_err(payload)
    }

    /// Everything the schedules say should be running at `now`, for the preview timeline.
    pub fn activations_json(
        &self,
        now: Timestamp,
        events_json: String,
    ) -> Result<String, CurfewError> {
        let events: Vec<CalendarEvent> =
            serde_json::from_str(if events_json.is_empty() { "[]" } else { &events_json })
                .map_err(payload)?;
        let config = self.config.read().expect("config lock");
        let tz = config.tz().map_err(|e| CurfewError::Config { detail: e.to_string() })?;
        serde_json::to_string(&active_at(now, tz, &config.weekly, &config.calendars, &events))
            .map_err(payload)
    }

    /// When the set of active schedules could next change, so the service can set one alarm
    /// instead of waking up to poll (which is what doze punishes).
    pub fn next_change_after(
        &self,
        now: Timestamp,
        events_json: String,
    ) -> Result<Option<Timestamp>, CurfewError> {
        let events: Vec<CalendarEvent> =
            serde_json::from_str(if events_json.is_empty() { "[]" } else { &events_json })
                .map_err(payload)?;
        let config = self.config.read().expect("config lock");
        let tz = config.tz().map_err(|e| CurfewError::Config { detail: e.to_string() })?;
        Ok(next_change_after(now, tz, &config.weekly, &config.calendars, &events))
    }
}

/// Which enforcer is asking. Mirrored rather than JSON-encoded because it appears in every
/// `decide` call and is the one type that genuinely benefits from being an enum in Kotlin.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum PlatformName {
    Android,
    Windows,
    Browser,
}

impl From<PlatformName> for Platform {
    fn from(p: PlatformName) -> Self {
        match p {
            PlatformName::Android => Platform::Android,
            PlatformName::Windows => Platform::Windows,
            PlatformName::Browser => Platform::Browser,
        }
    }
}

/// Validate a config without loading it, for the import screen. Returns a human-readable summary
/// on success and the core's own error message on failure.
#[uniffi::export]
pub fn check_config(config_toml: String) -> Result<String, CurfewError> {
    let config = Config::from_toml(&config_toml)
        .map_err(|e| CurfewError::Config { detail: e.to_string() })?;
    Ok(format!(
        "schema v{}, timezone {}, {} profile(s), {} weekly window(s), {} calendar rule(s)",
        config.schema_version,
        config.timezone,
        config.profiles.len(),
        config.weekly.len(),
        config.calendars.len()
    ))
}

/// Set a profile's blocked apps from a picker, returning the rewritten config.
///
/// The editing lives in the core rather than in each platform's UI: a config is the user's own
/// document, and two implementations of "add a rule" would eventually disagree about what a rule
/// looks like. The caller writes the result back through the usual `set_config` path, so the same
/// validation applies as to a hand-edited file.
#[uniffi::export]
pub fn set_blocked_apps(
    config_toml: String,
    profile: String,
    packages: Vec<String>,
) -> Result<String, CurfewError> {
    let mut config = Config::from_toml(&config_toml)
        .map_err(|e| CurfewError::Config { detail: e.to_string() })?;
    config
        .set_blocked_apps(&profile, &packages)
        .map_err(|e| CurfewError::Config { detail: e.to_string() })?;
    config.to_toml().map_err(|e| CurfewError::Config { detail: e.to_string() })
}

/// The packages `set_blocked_apps` owns, so a picker can open with the right boxes ticked.
#[uniffi::export]
pub fn blocked_apps(config_toml: String, profile: String) -> Result<Vec<String>, CurfewError> {
    let config = Config::from_toml(&config_toml)
        .map_err(|e| CurfewError::Config { detail: e.to_string() })?;
    Ok(config.blocked_apps(&profile))
}

/// The profiles a config defines, in file order, as `(id, name)` pairs flattened to a JSON array.
///
/// JSON rather than a UniFFI record because every other structured value crosses this boundary as
/// JSON, and one exception is how a boundary starts growing two conventions.
#[uniffi::export]
pub fn profiles_json(config_toml: String) -> Result<String, CurfewError> {
    let config = Config::from_toml(&config_toml)
        .map_err(|e| CurfewError::Config { detail: e.to_string() })?;
    let named: Vec<_> = config
        .profiles
        .iter()
        .map(|p| serde_json::json!({ "id": p.id, "name": p.name }))
        .collect();
    serde_json::to_string(&named).map_err(|e| CurfewError::Config { detail: e.to_string() })
}
