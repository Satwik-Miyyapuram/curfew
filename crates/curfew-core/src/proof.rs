//! Evidence that has been checked, and how long it stands for.
//!
//! Some conditions are proved by an event rather than by a state: a PIN is typed once, a tag is
//! held against the phone once. A lock that asks for *both* would be impossible to satisfy if each
//! proof vanished the instant it was made — the user would type the PIN, be told the tag is still
//! missing, scan the tag, and be told the PIN is still missing, forever.
//!
//! So a checked proof is remembered, briefly. [`PROOF_SECONDS`] is short on purpose: long enough
//! to walk to the fridge and back, short enough that a PIN typed this morning is not still an open
//! door this evening. Nothing here is persisted, either — after a restart every condition is
//! unproven again, which is the safe direction.
//!
//! Only *verified* evidence belongs in here. A claim from an unprivileged caller ("I satisfied the
//! credential") is not evidence and must never be recorded; the point of the checks upstream is
//! lost the moment a claim can be laundered into a proof.

use crate::lock::Lock;
use crate::Timestamp;
use std::collections::{BTreeMap, BTreeSet};

/// How long a checked proof stands. Two minutes: the walk to the other room, and not the evening.
pub const PROOF_SECONDS: Timestamp = 120;

/// Proofs made recently, per session.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Proofs {
    held: BTreeMap<String, BTreeMap<Lock, Timestamp>>,
}

impl Proofs {
    /// Write down a proof that has actually been checked.
    ///
    /// A second proof of the same condition refreshes it rather than stacking, so a user who scans
    /// the tag twice while looking for their PIN has not shortened anything and has not extended
    /// anything either.
    pub fn record(&mut self, session: &str, lock: Lock, now: Timestamp) {
        self.held.entry(session.to_string()).or_default().insert(lock, now);
    }

    /// What is still provably true for this session at `now`.
    pub fn fresh(&self, session: &str, now: Timestamp) -> BTreeSet<Lock> {
        self.held
            .get(session)
            .into_iter()
            .flatten()
            .filter(|(_, at)| now >= **at && now - **at < PROOF_SECONDS)
            .map(|(lock, _)| lock.clone())
            .collect()
    }

    /// Drop what has gone stale, and everything belonging to sessions that are no longer running.
    pub fn prune(&mut self, now: Timestamp, running: &[String]) {
        let live: BTreeSet<&str> = running.iter().map(String::as_str).collect();
        self.held.retain(|session, proofs| {
            proofs.retain(|_, at| now >= *at && now - *at < PROOF_SECONDS);
            live.contains(session.as_str()) && !proofs.is_empty()
        });
    }

    /// Forget everything about one session, because it has ended.
    pub fn forget(&mut self, session: &str) {
        self.held.remove(session);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lock::ChallengeKind;

    const NOW: Timestamp = 1_788_510_600;

    #[test]
    fn a_proof_just_made_counts() {
        let mut proofs = Proofs::default();
        proofs.record("a", Lock::DeviceCredential, NOW);
        assert_eq!(BTreeSet::from([Lock::DeviceCredential]), proofs.fresh("a", NOW));
    }

    #[test]
    fn two_conditions_proved_a_minute_apart_are_both_still_true() {
        // The whole reason this type exists: a lock asking for a PIN *and* a tag has to be
        // satisfiable by a person with two hands and one room to walk to.
        let mut proofs = Proofs::default();
        proofs.record("a", Lock::DeviceCredential, NOW);
        proofs.record("a", Lock::Token { id: "fridge".into() }, NOW + 60);
        assert_eq!(
            BTreeSet::from([Lock::DeviceCredential, Lock::Token { id: "fridge".into() }]),
            proofs.fresh("a", NOW + 60)
        );
    }

    #[test]
    fn a_proof_goes_stale() {
        let mut proofs = Proofs::default();
        proofs.record("a", Lock::DeviceCredential, NOW);
        assert!(proofs.fresh("a", NOW + PROOF_SECONDS).is_empty());
    }

    #[test]
    fn a_proof_from_the_future_is_not_evidence_now() {
        // A clock that jumped would otherwise mint a proof that outlives its window.
        let mut proofs = Proofs::default();
        proofs.record("a", Lock::DeviceCredential, NOW + 600);
        assert!(proofs.fresh("a", NOW).is_empty());
    }

    #[test]
    fn one_session_proof_never_releases_another() {
        let mut proofs = Proofs::default();
        proofs.record("a", Lock::DeviceCredential, NOW);
        assert!(proofs.fresh("b", NOW).is_empty());
    }

    #[test]
    fn repeating_a_proof_refreshes_it_rather_than_stacking() {
        let mut proofs = Proofs::default();
        proofs.record("a", Lock::Challenge { challenge: ChallengeKind::Math }, NOW);
        proofs.record("a", Lock::Challenge { challenge: ChallengeKind::Math }, NOW + 60);
        assert_eq!(1, proofs.fresh("a", NOW + 60).len());
        assert!(!proofs.fresh("a", NOW + 179).is_empty());
        assert!(proofs.fresh("a", NOW + 180).is_empty());
    }

    #[test]
    fn pruning_forgets_stale_proofs_and_dead_sessions() {
        let mut proofs = Proofs::default();
        proofs.record("a", Lock::DeviceCredential, NOW);
        proofs.record("b", Lock::DeviceCredential, NOW);
        proofs.prune(NOW + 1, &["a".to_string()]);
        assert!(!proofs.fresh("a", NOW + 1).is_empty());
        assert!(proofs.fresh("b", NOW + 1).is_empty());
        proofs.prune(NOW + PROOF_SECONDS, &["a".to_string()]);
        assert!(proofs.fresh("a", NOW + PROOF_SECONDS).is_empty());
    }

    #[test]
    fn a_session_that_ended_leaves_nothing_behind() {
        let mut proofs = Proofs::default();
        proofs.record("a", Lock::DeviceCredential, NOW);
        proofs.forget("a");
        assert!(proofs.fresh("a", NOW).is_empty());
    }
}
