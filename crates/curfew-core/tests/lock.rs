//! Property tests for the lock lattice.
//!
//! These exist *before* any sync or merge code (ROADMAP Phase 3, GAPS C1). Design invariant 2 says
//! a lock can never be shortened; these tests are what turns that sentence into something CI
//! enforces.

use curfew_core::lock::{ChallengeKind, DELAYED_RELEASE_SECONDS};
use curfew_core::{Lock, LockSet};
use proptest::prelude::*;
use std::collections::BTreeSet;

fn any_lock() -> impl Strategy<Value = Lock> {
    prop_oneof![
        Just(Lock::Timer),
        Just(Lock::Confirm),
        Just(Lock::RestartRequired),
        Just(Lock::Challenge { challenge: ChallengeKind::Typing }),
        Just(Lock::Challenge { challenge: ChallengeKind::Math }),
        "[a-f0-9]{4}".prop_map(|h| Lock::Password { hash: h }),
        "[a-z]{1,4}".prop_map(|d| Lock::PeerRelease { device_id: d }),
        "[a-z]{1,4}".prop_map(|i| Lock::Token { id: i }),
    ]
}

fn any_lockset() -> impl Strategy<Value = LockSet> {
    (
        prop::collection::vec(any_lock(), 0..4),
        prop::option::of(0i64..1_000_000),
        prop::option::of(0i64..1_000_000),
    )
        .prop_map(|(conds, ends_at, delayed)| {
            let conditions: BTreeSet<Lock> = conds.into_iter().collect();
            LockSet {
                // A delayed release only means anything while something is locked; an unlocked
                // set can be ended at will, so the generator never invents that state.
                delayed_release_at: delayed.filter(|_| !conditions.is_empty()),
                conditions,
                ends_at,
            }
        })
}

/// The core property: whatever is merged in, the result still demands everything the original
/// demanded, and does not end sooner.
fn is_at_least_as_strict(merged: &LockSet, original: &LockSet) -> bool {
    // An unlocked set promised nothing, so nothing about it can be broken.
    if !original.is_locked() {
        return true;
    }
    let keeps_conditions = original.conditions.is_subset(&merged.conditions);
    let ends_no_sooner = match (merged.ends_at, original.ends_at) {
        // "until released" is longer than any concrete end time.
        (None, _) => true,
        (Some(_), None) => false,
        (Some(m), Some(o)) => m >= o,
    };
    keeps_conditions && ends_no_sooner
}

proptest! {
    #[test]
    fn merge_never_weakens_either_side(a in any_lockset(), b in any_lockset()) {
        let m = a.merge(&b);
        prop_assert!(is_at_least_as_strict(&m, &a));
        prop_assert!(is_at_least_as_strict(&m, &b));
    }

    #[test]
    fn merge_is_commutative(a in any_lockset(), b in any_lockset()) {
        prop_assert_eq!(a.merge(&b), b.merge(&a));
    }

    #[test]
    fn merge_is_associative(a in any_lockset(), b in any_lockset(), c in any_lockset()) {
        prop_assert_eq!(a.merge(&b).merge(&c), a.merge(&b.merge(&c)));
    }

    #[test]
    fn merge_is_idempotent(a in any_lockset()) {
        prop_assert_eq!(a.merge(&a), a.clone());
    }

    /// No sequence of merges -- however long, in whatever order, however many peers -- can produce
    /// a lock weaker than any one that went into it. Design invariant 2, as a test.
    #[test]
    fn no_sequence_of_merges_can_shorten_a_lock(
        start in any_lockset(),
        rest in prop::collection::vec(any_lockset(), 1..12),
    ) {
        let mut acc = start.clone();
        for next in &rest {
            let merged = acc.merge(next);
            prop_assert!(is_at_least_as_strict(&merged, &acc));
            prop_assert!(is_at_least_as_strict(&merged, &start));
            acc = merged;
        }
    }

    /// The last-resort exit is a promise in the other direction: once shown to the user it cannot
    /// be pushed further away, by repetition or by merging.
    #[test]
    fn delayed_release_never_moves_later(
        now in 0i64..1_000_000,
        later in 1i64..100_000,
        other in any_lockset(),
    ) {
        let mut lock = LockSet::new([Lock::Timer], Some(now + 10_000));
        let first = lock.request_release(now);
        prop_assert_eq!(first, now + DELAYED_RELEASE_SECONDS);

        // Asking again later must not reset the clock.
        let second = lock.request_release(now + later);
        prop_assert_eq!(second, first);

        // Nor may a merge from a peer.
        let merged = lock.merge(&other);
        prop_assert!(merged.delayed_release_at.unwrap() <= first);
    }
}

#[test]
fn an_unlocked_session_releases_freely() {
    let lock = LockSet::unlocked();
    assert!(!lock.is_locked());
    assert!(lock.can_release(0, &BTreeSet::new()));
}

#[test]
fn release_requires_every_merged_condition() {
    let a = LockSet::new([Lock::Password { hash: "x".into() }], Some(1_000));
    let b = LockSet::new([Lock::RestartRequired], Some(2_000));
    let merged = a.merge(&b);

    assert_eq!(merged.ends_at, Some(2_000), "merge takes the later end time");

    let only_password: BTreeSet<_> = [Lock::Password { hash: "x".into() }].into();
    assert!(!merged.can_release(0, &only_password), "one of two conditions is not enough");

    let both: BTreeSet<_> = [Lock::Password { hash: "x".into() }, Lock::RestartRequired].into();
    assert!(merged.can_release(0, &both));
    assert!(merged.can_release(2_000, &BTreeSet::new()), "expiry releases on its own");
}
