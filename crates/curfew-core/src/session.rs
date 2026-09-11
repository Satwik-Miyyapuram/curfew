//! Sessions: a profile that is actually running, and the lock guarding it.
//!
//! This is where design invariant 2 ("a lock is a promise") is enforced in practice. Every path
//! that could end a session early goes through [`Sessions::end`], and there is exactly one of it.
//! The lock lattice does the hard part — merging can only add strictness — so the job here is to
//! make sure nothing sneaks around the lattice: not a schedule that stops matching, not a config
//! that deletes the profile, not a clock that jumps backwards.

use crate::lock::{Lock, LockSet};
use crate::schedule::{Activation, ActivationSource};
use crate::Timestamp;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::collections::BTreeSet;

/// Why a session is running. Kept so the UI can say "running because: Work calendar", and so a
/// schedule-started session can be recognised when the schedule that started it goes away.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum SessionSource {
    /// The user pressed start.
    Manual,
    Weekly {
        schedule: String,
    },
    Calendar {
        schedule: String,
        event: String,
    },
}

impl From<&ActivationSource> for SessionSource {
    fn from(src: &ActivationSource) -> Self {
        match src {
            ActivationSource::Weekly { schedule } => {
                SessionSource::Weekly { schedule: schedule.clone() }
            }
            ActivationSource::Calendar { schedule, event } => {
                SessionSource::Calendar { schedule: schedule.clone(), event: event.clone() }
            }
        }
    }
}

/// One running profile.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Session {
    /// Stable id, minted by the caller (a ULID in practice) so the op-log can refer to it.
    pub id: String,
    pub profile: String,
    pub source: SessionSource,
    pub started_at: Timestamp,
    pub lock: LockSet,
}

impl Session {
    pub fn is_over(&self, now: Timestamp) -> bool {
        self.lock.is_expired(now)
    }
}

/// Why a request to end a session was refused. Every variant is something the UI must be able to
/// explain, because "no" without a reason is what makes people uninstall a blocker.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "refusal", rename_all = "snake_case")]
pub enum Refusal {
    /// No session by that id is running.
    NotRunning,
    /// The lock's conditions are not all satisfied.
    Locked {
        /// What is still missing, so the UI can ask for exactly that.
        missing: BTreeSet<Lock>,
        /// When it ends on its own, if it does.
        ends_at: Option<Timestamp>,
        /// When the delayed release lands, if one has been requested.
        delayed_release_at: Option<Timestamp>,
    },
}

/// The running sessions. Ordered by id so two devices materializing the same op-log get the same
/// structure, byte for byte.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Sessions {
    pub running: Vec<Session>,
    /// For each profile, when its current occurrence was ended by hand.
    ///
    /// Ending a scheduled session used to last about a second: the session went away, and the very
    /// next reconcile saw the same window still matching and started it straight back up. That is
    /// not a lock keeping its promise, it is a bug wearing a lock's clothes — the promise is that a
    /// session runs until its lock says otherwise, and the user had just satisfied that lock.
    ///
    /// So an end is remembered against the occurrence it ended, and reconcile skips exactly that
    /// occurrence. The *next* one starts normally: this ends tonight's window, never the schedule.
    #[serde(default)]
    pub dismissed: BTreeMap<String, Timestamp>,
}

impl Sessions {
    /// Start a session, or strengthen the one already running for that profile.
    ///
    /// Starting the same profile twice never creates a second session and never weakens the first:
    /// the locks merge, which by the lattice can only add conditions and push the end time later.
    /// This is what makes "start deep work" idempotent and safe to retry after a crash.
    pub fn start(&mut self, session: Session) {
        if let Some(existing) = self.running.iter_mut().find(|s| s.profile == session.profile) {
            existing.lock = existing.lock.merge(&session.lock);
            existing.started_at = existing.started_at.min(session.started_at);
            return;
        }
        self.running.push(session);
        self.running.sort_by(|a, b| a.id.cmp(&b.id));
    }

    pub fn get(&self, id: &str) -> Option<&Session> {
        self.running.iter().find(|s| s.id == id)
    }

    pub fn for_profile(&self, profile: &str) -> Option<&Session> {
        self.running.iter().find(|s| s.profile == profile)
    }

    /// Profiles currently running, for [`crate::engine::State`].
    pub fn active_profiles(&self, now: Timestamp) -> Vec<String> {
        let mut out: Vec<String> =
            self.running.iter().filter(|s| !s.is_over(now)).map(|s| s.profile.clone()).collect();
        out.sort();
        out.dedup();
        out
    }

    /// The merged lock across every running session: what the user is actually held by.
    pub fn merged_lock(&self, now: Timestamp) -> LockSet {
        self.running
            .iter()
            .filter(|s| !s.is_over(now))
            .fold(LockSet::unlocked(), |acc, s| acc.merge(&s.lock))
    }

    /// End a session early. The *only* way a session ends before its time.
    pub fn end(
        &mut self,
        id: &str,
        now: Timestamp,
        satisfied: &BTreeSet<Lock>,
    ) -> Result<Session, Refusal> {
        let Some(index) = self.running.iter().position(|s| s.id == id) else {
            return Err(Refusal::NotRunning);
        };
        let session = &self.running[index];
        if !session.lock.can_release(now, satisfied) {
            return Err(Refusal::Locked {
                missing: session.lock.conditions.difference(satisfied).cloned().collect(),
                ends_at: session.lock.ends_at,
                delayed_release_at: session.lock.delayed_release_at,
            });
        }
        let ended = self.running.remove(index);
        // Remembered against the profile, so the occurrence that is matching right now does not
        // restart on the next reconcile a second from now.
        self.dismissed.insert(ended.profile.clone(), now);
        Ok(ended)
    }

    /// End a session with a spent emergency pass, whatever its lock says.
    ///
    /// The pass is taken by value and dropped here, so one pass ends one session: the other
    /// sessions running at the time stay exactly as locked as they were. This is still
    /// [`Sessions::end`] underneath — the pass is presented as evidence for that session's own
    /// conditions rather than as a way around the check — so invariant 2 survives the escape
    /// hatch, and a caller cannot use this to end something that is not running.
    ///
    /// Spending the pass is the caller's job, and must happen before this: [`Passes::spend`] is
    /// what enforces the quota, and it is deliberately not called from in here so the use is
    /// recorded in the op-log whether or not the release that followed it succeeded.
    ///
    /// **The pass is a receipt, and the type now says so.** `Pass` has a private field, so the only
    /// way to obtain one is [`Passes::spend`]; the `let _ = pass` below is therefore not "we trust
    /// the caller" but "the caller could not have this unless the ration allowed it". Before that
    /// field was private, any code in the process could write `Pass { at: 0 }` — and through the
    /// FFI, any code in the Android app, on a rooted device from outside it — and release every
    /// lock the hatch was supposed to be rationed against.
    ///
    /// [`Passes::spend`]: crate::emergency::Passes::spend
    pub fn end_with_pass(
        &mut self,
        id: &str,
        now: Timestamp,
        pass: crate::emergency::Pass,
    ) -> Result<Session, Refusal> {
        let _ = pass;
        let Some(session) = self.running.iter().find(|s| s.id == id) else {
            return Err(Refusal::NotRunning);
        };
        let satisfied = session.lock.conditions.clone();
        self.end(id, now, &satisfied)
    }

    /// Start the 24-hour delayed release on a session (GAPS D1). Idempotent, and never movable
    /// later — the guarantee lives in [`LockSet::request_release`].
    pub fn request_release(&mut self, id: &str, now: Timestamp) -> Result<Timestamp, Refusal> {
        let session = self.running.iter_mut().find(|s| s.id == id).ok_or(Refusal::NotRunning)?;
        Ok(session.lock.request_release(now))
    }

    /// Drop sessions whose time is up. Returns what it removed, so the caller can log and notify.
    pub fn reap(&mut self, now: Timestamp) -> Vec<Session> {
        let (over, still_running): (Vec<_>, Vec<_>) =
            std::mem::take(&mut self.running).into_iter().partition(|s| s.is_over(now));
        self.running = still_running;
        over
    }
}

/// Bring running sessions into line with what the schedules say should be running at `now`.
///
/// The asymmetry is deliberate and is the heart of invariant 2:
///
/// - A schedule that *starts* matching starts (or strengthens) a session immediately.
/// - A schedule that *stops* matching does not end anything. Deleting the calendar event, editing
///   the schedule, or changing the device clock must not be a way out of a lock. The session ends
///   when its own lock says so, which for a scheduled session is the activation's end time — a
///   time that was already fixed when the session started.
///
/// Returns the ids of sessions it started, for logging.
pub fn reconcile(
    now: Timestamp,
    sessions: &mut Sessions,
    activations: &[Activation],
    mut mint_id: impl FnMut(&Activation) -> String,
) -> Vec<String> {
    let mut started = Vec::new();
    for activation in activations {
        if activation.end <= now {
            continue;
        }
        // This exact occurrence was ended by hand: leave it ended. Anything starting later is a
        // new occurrence and is unaffected.
        if sessions
            .dismissed
            .get(&activation.profile)
            .is_some_and(|at| *at >= activation.start && *at < activation.end)
        {
            continue;
        }
        let existing_lock = sessions.for_profile(&activation.profile).map(|s| s.lock.clone());
        let session = Session {
            id: sessions
                .for_profile(&activation.profile)
                .map(|s| s.id.clone())
                .unwrap_or_else(|| mint_id(activation)),
            profile: activation.profile.clone(),
            source: SessionSource::from(&activation.source),
            started_at: now,
            lock: activation.lock(),
        };
        let is_new = existing_lock.is_none();
        let id = session.id.clone();
        sessions.start(session);
        if is_new {
            started.push(id);
        }
    }
    started
}
