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
use curfew_core::schedule::{active_at, next_change_after, upcoming, CalendarEvent};
use curfew_core::session::{reconcile, Session, Sessions};
use curfew_core::target::Observation;
use curfew_core::{decide, ClockWitness, Config, Lock, Reading, Timestamp, Verdict};
use std::collections::BTreeSet;
use std::sync::{Arc, RwLock};

pub mod sync;

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
    /// No emergency pass could be spent. `refusal` is the serialized `PassRefusal`, which carries
    /// the time the next one becomes available -- the only part of the answer a user can act on.
    #[error("no pass: {refusal}")]
    NoPass { refusal: String },
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
    /// `None` until the first reading. Held here rather than on the platform side for the same
    /// reason sessions are: the guarantee that a clock change cannot shorten a lock is only worth
    /// anything if there is exactly one place that decides what time it is.
    clock: RwLock<Option<ClockWitness>>,
    /// Emergency passes already spent, here and on every paired device. Kept beside the sessions
    /// because the two are read together on every attempt to end a lock early, and because the
    /// ration must be as hard for Kotlin to edit as the sessions are.
    passes: RwLock<curfew_core::Passes>,
    /// Which boot each running session was first seen in, so a restart lock is answered by a fact
    /// about the device rather than by Kotlin saying it rebooted. Persisted with the sessions.
    boots: RwLock<curfew_core::Boots>,
    /// A boot number derived from uptime going backwards, never from the wall clock: a clock moved
    /// forward a year must not read as a restart, or every restart lock would open with a setting.
    boot_counter: RwLock<curfew_core::BootCounter>,
    /// Conditions this device has actually checked in the last couple of minutes -- a credential
    /// the platform prompt accepted, a tag that matched. Deliberately not persisted: after a
    /// restart nothing is proven again.
    proofs: RwLock<curfew_core::Proofs>,
    /// Releases this device has given for locks that name it, to be published to the op-log, and
    /// the releases every device has given, as read back from it.
    releases: RwLock<BTreeSet<String>>,
    released: RwLock<std::collections::BTreeMap<String, BTreeSet<String>>>,
    /// This device's own id in the op-log, once sync knows it.
    device_id: RwLock<Option<String>>,
}

/// Whether a condition is one the caller is the only witness to.
///
/// A timer, a confirmation and a challenge are satisfied inside the UI and nowhere else, so the UI
/// is allowed to say it did them. Everything else is checked here against something the caller
/// cannot fake, because `end_session` is reachable from any code in the app process -- and on a
/// rooted device, from outside it.
fn claimable(lock: &Lock) -> bool {
    matches!(lock, Lock::Timer | Lock::Confirm | Lock::Challenge { .. })
}

impl Curfew {
    /// Everything this device can prove about a session's lock without being told.
    ///
    /// A restart is proven by the boot id; a peer release by an entry in the signed op-log; a
    /// credential or a tag by the check made when it was presented, for as long as that proof is
    /// fresh. Nothing a caller says reaches this function.
    fn proven(&self, id: &str, now: Timestamp) -> BTreeSet<Lock> {
        let boot = self.boot_counter.read().expect("boot lock").boot_id();
        let mut evidence = self.boots.read().expect("boots lock").evidence(id, boot);
        evidence.extend(self.proofs.read().expect("proofs lock").fresh(id, now));
        for device in self.released.read().expect("released lock").get(id).into_iter().flatten() {
            evidence.insert(Lock::PeerRelease { device_id: device.clone() });
        }
        evidence
    }

    /// End a session with evidence already gathered, forgetting the proofs it used up.
    fn finish(&self, id: &str, now: Timestamp, satisfied: &BTreeSet<Lock>) -> Result<(), CurfewError> {
        match self.sessions.write().expect("sessions lock").end(id, now, satisfied) {
            Ok(_) => {
                self.proofs.write().expect("proofs lock").forget(id);
                Ok(())
            }
            Err(refusal) => Err(CurfewError::Refused {
                refusal: serde_json::to_string(&refusal).unwrap_or_else(|_| "{}".into()),
            }),
        }
    }
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
            clock: RwLock::new(None),
            passes: RwLock::new(curfew_core::Passes::default()),
            boots: RwLock::new(curfew_core::Boots::default()),
            boot_counter: RwLock::new(curfew_core::BootCounter::default()),
            proofs: RwLock::new(curfew_core::Proofs::default()),
            releases: RwLock::new(BTreeSet::new()),
            released: RwLock::new(std::collections::BTreeMap::new()),
            device_id: RwLock::new(None),
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

    /// End a session. `satisfied_json` is what the caller witnessed -- for example
    /// `[{"kind":"confirm"}]` after the user confirmed in a dialog.
    ///
    /// Only the conditions the caller can be the only witness to are taken from it. A credential,
    /// a tag, a restart and a peer release are added by [`Self::proven`] from what this object
    /// checked itself, so a caller that simply listed them all would end nothing.
    pub fn end_session(
        &self,
        id: String,
        now: Timestamp,
        satisfied_json: String,
    ) -> Result<(), CurfewError> {
        let claimed: BTreeSet<Lock> =
            serde_json::from_str(if satisfied_json.is_empty() { "[]" } else { &satisfied_json })
                .map_err(payload)?;
        let mut satisfied: BTreeSet<Lock> = claimed.into_iter().filter(claimable).collect();
        satisfied.extend(self.proven(&id, now));
        self.finish(&id, now, &satisfied)
    }

    // --- evidence ---------------------------------------------------------------------------

    /// Note that the device credential prompt has just succeeded for this session.
    ///
    /// Called by the platform *after* `BiometricPrompt` reports success, and separated from ending
    /// so a lock that asks for a credential and a tag can be satisfied by doing both, a minute
    /// apart, rather than being impossible to satisfy at all.
    pub fn record_credential(&self, id: String, now: Timestamp) {
        self.proofs.write().expect("proofs lock").record(&id, Lock::DeviceCredential, now);
    }

    /// Present a physical tag. The payload is fingerprinted and thrown away; only the id of the
    /// tag it matched is kept, and only for as long as the proof is fresh.
    ///
    /// A scan of something unknown and a scan of a real tag this lock does not name are answered
    /// the same way -- the session stays locked -- so scanning cannot be used to enumerate the
    /// tags a config knows about.
    pub fn scan_token(&self, id: String, payload: String, now: Timestamp) -> Result<(), CurfewError> {
        let tag = {
            let config = self.config.read().expect("config lock");
            curfew_core::identify(&config.tokens, &payload)
        };
        // An unknown tag records nothing and then takes the ordinary path, so it is answered by
        // the same refusal a real tag for another lock gets: the session is still locked, and the
        // reply says nothing about which tags exist.
        if let Some(tag) = tag {
            self.proofs.write().expect("proofs lock").record(&id, Lock::Token { id: tag }, now);
        }
        let satisfied = self.proven(&id, now);
        self.finish(&id, now, &satisfied)
    }

    /// Give the release a peer's lock is waiting on this device for.
    ///
    /// Recorded even when this device has never heard of the session: the lock it opens is usually
    /// on the *other* device. There is no way to take one back -- a release that could be withdrawn
    /// would let one device re-shut a lock the user had already been told they were out of.
    pub fn release_peer(&self, id: String, now: Timestamp) {
        self.releases.write().expect("releases lock").insert(id.clone());
        let Some(mine) = self.device_id.read().expect("device lock").clone() else { return };
        self.released.write().expect("released lock").entry(id.clone()).or_default().insert(mine);
        let satisfied = self.proven(&id, now);
        let _ = self.finish(&id, now, &satisfied);
    }

    /// Sessions whose lock asks *this* device for the release, for the button that gives it.
    pub fn releasable(&self) -> Vec<String> {
        let Some(mine) = self.device_id.read().expect("device lock").clone() else {
            return Vec::new();
        };
        self.sessions
            .read()
            .expect("sessions lock")
            .running
            .iter()
            .filter(|s| {
                s.lock.conditions.iter().any(
                    |lock| matches!(lock, Lock::PeerRelease { device_id } if *device_id == mine),
                )
            })
            .map(|s| s.id.clone())
            .collect()
    }

    /// The releases this device has given, for the sync layer to publish.
    pub fn releases_json(&self) -> Result<String, CurfewError> {
        serde_json::to_string(&*self.releases.read().expect("releases lock")).map_err(payload)
    }

    /// Adopt the releases every device has given, as read back from the op-log, and the id this
    /// device is known by there.
    pub fn observe_releases(
        &self,
        device_id: String,
        released_json: String,
    ) -> Result<(), CurfewError> {
        let released: std::collections::BTreeMap<String, BTreeSet<String>> =
            serde_json::from_str(if released_json.is_empty() { "{}" } else { &released_json })
                .map_err(payload)?;
        *self.device_id.write().expect("device lock") = Some(device_id);
        *self.released.write().expect("released lock") = released;
        Ok(())
    }

    pub fn restore_releases(&self, releases_json: String) -> Result<(), CurfewError> {
        let stored: BTreeSet<String> = serde_json::from_str(&releases_json).map_err(payload)?;
        self.releases.write().expect("releases lock").extend(stored);
        Ok(())
    }

    /// Take a reading of how long the device has been up, in seconds.
    ///
    /// Called on every pass and after every boot-completed broadcast. Uptime rather than the clock
    /// on purpose: uptime can only go backwards by rebooting.
    pub fn observe_boot(&self, uptime_seconds: i64) {
        let boot = self.boot_counter.write().expect("boot lock").observe(uptime_seconds);
        let sessions = self.sessions.read().expect("sessions lock");
        self.boots.write().expect("boots lock").observe(boot, &sessions.running);
    }

    pub fn boots_json(&self) -> Result<String, CurfewError> {
        serde_json::to_string(&(
            &*self.boots.read().expect("boots lock"),
            &*self.boot_counter.read().expect("boot lock"),
        ))
        .map_err(payload)
    }

    pub fn restore_boots(&self, boots_json: String) -> Result<(), CurfewError> {
        let (boots, counter): (curfew_core::Boots, curfew_core::BootCounter) =
            serde_json::from_str(&boots_json).map_err(payload)?;
        *self.boots.write().expect("boots lock") = boots;
        *self.boot_counter.write().expect("boot lock") = counter;
        Ok(())
    }

    /// Which conditions of a session's lock this device can prove right now, as JSON, so the UI
    /// can stop offering a button for something that is already satisfied.
    pub fn proven_json(&self, id: String, now: Timestamp) -> Result<String, CurfewError> {
        serde_json::to_string(&self.proven(&id, now)).map_err(payload)
    }

    // --- the escape hatch -------------------------------------------------------------------

    /// End a session by spending an emergency pass, when the config allows one and the ration
    /// permits it now.
    ///
    /// The pass is taken before the session is looked at, so a pass spent on a session that has
    /// already ended is still spent: the ration counts reaching for the hatch, not succeeding, or
    /// a stale id would be a free way to probe how many are left.
    ///
    /// A refused pass comes back as [`CurfewError::NoPass`] with the reason as JSON, so the UI can
    /// say *when* rather than only *no*.
    pub fn spend_pass(&self, id: String, now: Timestamp) -> Result<(), CurfewError> {
        let pass = {
            let config = self.config.read().expect("config lock");
            self.passes.write().expect("passes lock").spend(now, &config.emergency).map_err(
                |refusal| CurfewError::NoPass {
                    refusal: serde_json::to_string(&refusal).unwrap_or_else(|_| "{}".into()),
                },
            )?
        };
        self.sessions
            .write()
            .expect("sessions lock")
            .end_with_pass(&id, now, pass)
            .map(|_| ())
            .map_err(|refusal| CurfewError::Refused {
                refusal: serde_json::to_string(&refusal).unwrap_or_else(|_| "{}".into()),
            })
    }

    /// How many passes could be spent inside the rolling window. Zero both when the hatch is off
    /// and when it is empty; [`Self::pass_refusal_json`] tells the two apart.
    pub fn passes_remaining(&self, now: Timestamp) -> u32 {
        let config = self.config.read().expect("config lock");
        self.passes.read().expect("passes lock").remaining(now, &config.emergency)
    }

    /// Why a pass cannot be spent right now, as JSON, or `None` when one can.
    pub fn pass_refusal_json(&self, now: Timestamp) -> Result<Option<String>, CurfewError> {
        let config = self.config.read().expect("config lock");
        match self.passes.read().expect("passes lock").check(now, &config.emergency) {
            Ok(()) => Ok(None),
            Err(refusal) => serde_json::to_string(&refusal).map(Some).map_err(payload),
        }
    }

    pub fn passes_json(&self) -> Result<String, CurfewError> {
        serde_json::to_string(&*self.passes.read().expect("passes lock")).map_err(payload)
    }

    /// Restore the spent ration after a restart. Merged rather than replaced: a device that has
    /// heard about a peer's pass since the file was written must not forget it by reading an older
    /// copy of its own.
    pub fn restore_passes(&self, passes_json: String) -> Result<(), CurfewError> {
        let stored: curfew_core::Passes = serde_json::from_str(&passes_json).map_err(payload)?;
        self.passes.write().expect("passes lock").merge(&stored);
        Ok(())
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

    // --- trusted time -----------------------------------------------------------------------

    /// Take a reading of the device's wall clock and its monotonic uptime, and return the
    /// [`curfew_core::Verdict`] as JSON: the time locks should be judged against, plus how much
    /// the reading tried to claim and was refused.
    ///
    /// The platform must pass the two clocks read at the same moment, and a `boot_id` that changes
    /// on every restart. Everything the caller does with time afterwards should use the `now` this
    /// returns, not the wall clock it passed in.
    pub fn observe_clock(
        &self,
        wall: Timestamp,
        uptime: i64,
        boot_id: u64,
    ) -> Result<String, CurfewError> {
        let reading = Reading { wall, uptime, boot_id };
        let mut guard = self.clock.write().expect("clock lock");
        let verdict = match guard.as_mut() {
            Some(witness) => witness.observe(reading),
            None => {
                // Nothing to check a first reading against, so it is taken at face value and
                // recorded as the baseline every later reading is measured from.
                let witness = ClockWitness::new(reading);
                let now = witness.now();
                *guard = Some(witness);
                Verdict { now, refused_forward: 0, refused_backward: 0, unverified: 0 }
            }
        };
        serde_json::to_string(&serde_json::json!({
            "now": verdict.now,
            "refused_forward": verdict.refused_forward,
            "refused_backward": verdict.refused_backward,
            "unverified": verdict.unverified,
            "tampered": verdict.tampered(),
        }))
        .map_err(payload)
    }

    /// The current trusted time, without taking a reading. `None` before the first observation.
    pub fn trusted_now(&self) -> Option<Timestamp> {
        self.clock.read().expect("clock lock").as_ref().map(ClockWitness::now)
    }

    /// The witness as JSON, for the platform to write down. Without persistence a restart would
    /// reset the baseline, and resetting the baseline is exactly what an attacker wants.
    pub fn clock_witness_json(&self) -> Result<Option<String>, CurfewError> {
        match self.clock.read().expect("clock lock").as_ref() {
            Some(witness) => serde_json::to_string(witness).map(Some).map_err(payload),
            None => Ok(None),
        }
    }

    /// Restore a witness written by [`Curfew::clock_witness_json`].
    pub fn restore_clock(&self, witness_json: String) -> Result<(), CurfewError> {
        let witness: ClockWitness = serde_json::from_str(&witness_json).map_err(payload)?;
        *self.clock.write().expect("clock lock") = Some(witness);
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

    /// Everything that will be running between `from` and `to`: the preview timeline.
    ///
    /// Activations come back whole rather than clipped to the window, so a block that started
    /// before the view is shown starting when it really did.
    pub fn upcoming_json(
        &self,
        from: Timestamp,
        to: Timestamp,
        events_json: String,
    ) -> Result<String, CurfewError> {
        let events: Vec<CalendarEvent> =
            serde_json::from_str(if events_json.is_empty() { "[]" } else { &events_json })
                .map_err(payload)?;
        let config = self.config.read().expect("config lock");
        let tz = config.tz().map_err(|e| CurfewError::Config { detail: e.to_string() })?;
        serde_json::to_string(&upcoming(from, to, tz, &config.weekly, &config.calendars, &events))
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
    let named: Vec<_> =
        config.profiles.iter().map(|p| serde_json::json!({ "id": p.id, "name": p.name })).collect();
    serde_json::to_string(&named).map_err(|e| CurfewError::Config { detail: e.to_string() })
}
