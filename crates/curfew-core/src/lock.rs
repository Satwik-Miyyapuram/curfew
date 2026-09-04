//! The lock lattice.
//!
//! Design invariant 2 is "a lock is a promise": no code path may shorten an active lock. Locks are
//! only *partially* ordered — a password lock is not obviously stricter or weaker than a two-hour
//! timer — so "strictest wins" is not a definition on its own (GAPS C1).
//!
//! The definition we use instead: a merged lock is the **conjunction** of every lock merged into
//! it, with the end time being the **maximum** of their end times. Release requires satisfying
//! *all* of the conditions. Conjunction is total, associative, commutative, idempotent, and
//! monotone — merging can only ever add conditions or push the end time later, never the reverse.
//! That is exactly the invariant, so it holds by construction rather than by care.

use crate::Timestamp;
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

/// The universal last-resort exit (GAPS D1). Available on every lock at every strictness, visible
/// from the moment the lock starts, and never shortenable. A commitment device without one is a
/// trap.
pub const DELAYED_RELEASE_SECONDS: i64 = 24 * 60 * 60;

/// A single release condition.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Lock {
    /// Ends when its time is up and not before.
    Timer,
    /// A confirmation dialog. Friction, not a barrier.
    Confirm,
    /// Argon2id hash of the user's password. The hash never leaves the device.
    Password { hash: String },
    /// Retype a passage / solve a problem.
    Challenge { challenge: ChallengeKind },
    /// Only a specific paired device can release. Only possible because we sync.
    PeerRelease { device_id: String },
    /// Scan a physical NFC tag or QR code, deliberately left in another room.
    Token { id: String },
    /// The machine must be restarted before release.
    RestartRequired,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChallengeKind {
    Typing,
    Math,
}

/// The set of conditions guarding one session, plus when the timer component expires.
///
/// A `LockSet` with no conditions is an unlocked session: it ends when its time is up and the user
/// may end it early.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LockSet {
    /// Conditions that must *all* be satisfied to release early. `BTreeSet` gives deduplication
    /// and a canonical order for free, which makes merge idempotent and commutative.
    pub conditions: BTreeSet<Lock>,
    /// Wall-clock end of the locked period. `None` means "until released".
    pub ends_at: Option<Timestamp>,
    /// Set when the user starts the 24h delayed release; the lock lifts at this time no matter
    /// what. Once set it can never be moved later or cleared (see [`LockSet::request_release`]).
    pub delayed_release_at: Option<Timestamp>,
}

impl LockSet {
    pub fn unlocked() -> Self {
        Self::default()
    }

    pub fn new(conditions: impl IntoIterator<Item = Lock>, ends_at: Option<Timestamp>) -> Self {
        Self { conditions: conditions.into_iter().collect(), ends_at, delayed_release_at: None }
    }

    pub fn is_locked(&self) -> bool {
        !self.conditions.is_empty()
    }

    /// True when this set constrains nothing at all: no conditions, no end time, no pending
    /// release. This is the identity element of [`LockSet::merge`].
    pub fn is_empty(&self) -> bool {
        self.conditions.is_empty() && self.ends_at.is_none() && self.delayed_release_at.is_none()
    }

    /// The lattice join. Conjunction of conditions, latest of the end times.
    ///
    /// The empty set is the identity element: it carries no promise, so merging it in returns the
    /// other side untouched. Between two non-empty sets, `ends_at: None` means "no automatic end,
    /// release only by the conditions", which outlasts any concrete end time and so absorbs.
    pub fn merge(&self, other: &Self) -> Self {
        if self.is_empty() {
            return other.clone();
        }
        if other.is_empty() {
            return self.clone();
        }
        let ends_at = match (self.ends_at, other.ends_at) {
            (Some(a), Some(b)) => Some(a.max(b)),
            // "until released" outlasts any concrete end time.
            _ => None,
        };
        Self {
            conditions: self.conditions.union(&other.conditions).cloned().collect(),
            ends_at,
            // The earlier promised release wins: a delayed release already visible to the user is
            // a commitment we made, and merging must not push it back.
            delayed_release_at: min_opt(self.delayed_release_at, other.delayed_release_at),
        }
    }

    /// Start the 24-hour delayed release. Idempotent: calling it again never moves the time later,
    /// so spamming it cannot be used to reset anything, and it cannot be cancelled.
    pub fn request_release(&mut self, now: Timestamp) -> Timestamp {
        let candidate = now + DELAYED_RELEASE_SECONDS;
        let at = min_opt(self.delayed_release_at, Some(candidate)).expect("candidate is Some");
        self.delayed_release_at = Some(at);
        at
    }

    /// Whether the lock has expired on its own terms at `now`.
    pub fn is_expired(&self, now: Timestamp) -> bool {
        let by_timer = self.ends_at.is_some_and(|t| now >= t);
        let by_delay = self.delayed_release_at.is_some_and(|t| now >= t);
        by_timer || by_delay
    }

    /// Whether the presented evidence satisfies every condition.
    pub fn can_release(&self, now: Timestamp, satisfied: &BTreeSet<Lock>) -> bool {
        if self.is_expired(now) {
            return true;
        }
        self.conditions.is_subset(satisfied)
    }
}

fn min_opt(a: Option<Timestamp>, b: Option<Timestamp>) -> Option<Timestamp> {
    match (a, b) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (x, y) => x.or(y),
    }
}
