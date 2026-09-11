//! The lock lattice.
//!
//! Design invariant 2 is "a lock is a promise": no code path may shorten an active lock. Locks are
//! only *partially* ordered — a credential lock is not obviously stricter or weaker than a two-hour
//! timer — so "strictest wins" is not a definition on its own (GAPS C1).
//!
//! The definition we use instead: a merged lock is the **conjunction** of every lock merged into
//! it, with the end time being the **maximum** of their end times. Release requires satisfying
//! *all* of the conditions. Conjunction is total, associative, commutative, idempotent, and
//! monotone — merging can only ever add conditions or push the end time later, never the reverse.
//! That is exactly the invariant, so it holds by construction rather than by care.
//!
//! Note what is *not* here. There is no password of our own: credential checks are delegated to the
//! operating system's screen lock (DECISIONS D7), so Curfew never stores, hashes or sees a secret.
//! And there is no device-wide biometric suppression: no third-party app can set that on Android
//! without device-owner provisioning, which is permanently out (DECISIONS D9). What we *can*
//! promise is that a release never accepts a fingerprint — [`Lock::DeviceCredential`] asks for the
//! PIN and only the PIN — so a policy we cannot enforce is not expressible here at all.

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
    /// The device's own screen-lock credential — the PIN, pattern or password the user already
    /// has. Verified by the OS (`BiometricPrompt` restricted to `DEVICE_CREDENTIAL` on Android,
    /// `LogonUser` on Windows), never by us, so there is no secret for Curfew to store or leak.
    ///
    /// Biometrics are deliberately not accepted: a fingerprint is a reflex, and typing a PIN is
    /// the friction we are buying (DECISIONS D7).
    DeviceCredential,
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

/// **What a user interface may offer for a session under a lock.** See [`LockSet::offers`].
///
/// Exists so three surfaces cannot answer this question three different ways (P1-6). Every field is a
/// separate *route*, because they are not interchangeable: one proves ownership with the operating
/// system's own prompt, one is friction, one needs a physical object, one needs a restart, and one is
/// the 24-hour exit of last resort. A surface that can serve only some of them offers only those, and
/// says which of the others is holding the lock.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Offers {
    /// Nothing to prove: an "End" button is all that is needed.
    pub ends_on_request: bool,
    /// Offer "end with your password/PIN" — proved by the OS prompt, never by us.
    pub credential: bool,
    /// Offer "end, confirming first" — friction rather than a barrier.
    pub confirm: bool,
    /// Conditions no local prompt can satisfy. Carried as values rather than a count so a surface can
    /// name them: a tag to fetch, a challenge to answer, a restart to do.
    pub elsewhere: Vec<Lock>,
    /// This device is the one the lock names, and has not given the release yet.
    pub peer_release: bool,
    /// This device is the one the lock names, and *has* given it. Reported rather than offered: the
    /// peer release is irrevocable, so offering it twice would suggest it could be redone.
    pub peer_released: bool,
    /// The 24-hour delayed release can be started. The last-resort exit, for every lock.
    pub delayed_release: bool,
    /// A delayed release already counting down, and when it lands. Never offered again, because asking
    /// twice cannot move it.
    pub delayed_release_at: Option<Timestamp>,
}

/// The set of conditions guarding one session, plus when it ends.
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

    /// **What a user interface may offer for a session under this lock** — the one place that decides.
    ///
    /// P1-6: this question was answered independently in three places, three different ways. The tray
    /// routed `DeviceCredential` and `PeerRelease`; the Windows window routed two variants *by
    /// comparing their display strings*; and neither routed `Challenge`, though it exists here and
    /// Android implements it. A user who locked a session with their Windows password and hid the tray
    /// — an option the tray itself presents as harmless — had no way to reach the last-resort exit from
    /// the product's primary surface.
    ///
    /// So the decision lives here, in the core both surfaces already depend on, and every surface reads
    /// [`Offers`] rather than re-deriving it.
    ///
    /// `releasable` is whether *this device* is the one a [`Lock::PeerRelease`] names — the service
    /// knows that and a UI cannot work it out. `released` is whether this device has already given that
    /// release, which is a different thing from being able to.
    pub fn offers(&self, releasable: bool, released: bool) -> Offers {
        let has = |want: fn(&Lock) -> bool| self.conditions.iter().any(want);
        let credential = has(|l| matches!(l, Lock::DeviceCredential));
        let confirm = has(|l| matches!(l, Lock::Confirm));
        // The conditions no local prompt can satisfy: a tag that is in another room, a challenge
        // answered in the app, a restart. Named rather than merely counted, so a surface can say
        // *which* one is holding the lock instead of "locked elsewhere".
        //
        // **`Timer` is excluded, and that is not a detail.** It is a condition in the set but it is
        // never something "elsewhere": it is satisfied by the clock, and every surface already shows
        // the end time beside it. The tray filtered it out before this predicate existed, and leaving
        // it in made an expired timer render as "locked elsewhere" with no way to end it — caught by
        // the tray's own `a_timer_that_has_run_out_can_be_ended_from_here`.
        let elsewhere: Vec<Lock> = self
            .conditions
            .iter()
            .filter(|l| {
                !matches!(l, Lock::DeviceCredential | Lock::Confirm | Lock::Timer)
                    // A peer release this device holds is not "elsewhere" either: it is offered right
                    // here, and calling it elsewhere is what made the window hide it.
                    && !(releasable && matches!(l, Lock::PeerRelease { .. }))
            })
            .cloned()
            .collect();

        Offers {
            // Zero conditions means there is nothing to prove, so the session ends on request.
            ends_on_request: self.conditions.is_empty(),
            credential,
            confirm,
            elsewhere,
            peer_release: releasable && !released,
            peer_released: releasable && released,
            // The last-resort exit. Offered whenever the lock has conditions and no release is already
            // counting down — which is every lock that is actually holding somebody, including the
            // ones no local prompt can satisfy. Deliberately *not* conditioned on `credential`: the
            // tray never required that either, and a user whose only condition is a tag kept in
            // another room still needs a way out that is not "wait 24 hours with no option shown".
            delayed_release: !self.conditions.is_empty() && self.delayed_release_at.is_none(),
            delayed_release_at: self.delayed_release_at,
        }
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

    /// **The stronger of the two locks, in every component** — never weaker than `self`.
    ///
    /// **`None` is deliberately not treated as the top element for either bound.** As a lattice that would
    /// be right — "until released" and "no automatic release" are both the longest-lived options — but it
    /// produced a lock nobody can open rather than a stronger one:
    ///
    ///  - an incoming `None` **cancelled** a running delayed release, which is the user's guaranteed way
    ///    out and which [`request_release`](Self::request_release) documents as cancellable by nothing; and
    ///  - an incoming `None` **removed** a running end time, which with a condition that cannot be claimed
    ///    (`Lock::Timer` is not claimable, since entry 1) leaves a lock with no exit at all.
    ///
    /// So this takes the later of two concrete values and otherwise keeps what is already running. A
    /// restore may make a lock last longer; it may not take a bound away.
    ///
    /// **Why this exists next to [`merge`], which looks similar.** `merge` combines two genuinely
    /// concurrent sessions' locks, where both sides are trusted; for `delayed_release_at` it deliberately
    /// takes the *earlier* one, because a release already shown to the user is a commitment we made and
    /// combining must not push it back. That is right for two promises and **wrong for a restore**, where
    /// the incoming side is caller-supplied and the running side is authoritative: `merge`'s
    /// `min_opt(None, Some(t)) == Some(t)` let a forged payload hand a running lock a release that had
    /// already passed, and `is_expired` then returned true with every condition bypassed.
    ///
    /// So the rule is: **use `merge` to combine promises, use `harden` to adopt untrusted state.**
    pub fn harden(&self, other: &LockSet) -> LockSet {
        // **`None` is not top here, deliberately — see the doc comment.** Adopting an unbounded value from
        // the untrusted side removes a bound, which is how a restore traps the user rather than releasing
        // them: a cancelled delayed release, or an end time replaced by "until released" on a lock whose
        // only condition is `Timer` (not claimable since entry 1, so nothing can open it).
        //
        // So: the later of two concrete values, and otherwise **keep what the running session already
        // has**. Nothing legitimate is lost, because an until-released session reaches a machine that is
        // not running one through `start()`, not through this function.
        let ends_at = match (self.ends_at, other.ends_at) {
            (Some(a), Some(b)) => Some(a.max(b)),
            (Some(a), None) => Some(a),
            (None, _) => None,
        };
        let delayed_release_at = match (self.delayed_release_at, other.delayed_release_at) {
            (Some(a), Some(b)) => Some(a.max(b)),
            (Some(a), None) => Some(a),
            (None, _) => None,
        };
        Self {
            conditions: self.conditions.union(&other.conditions).cloned().collect(),
            ends_at,
            delayed_release_at,
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
