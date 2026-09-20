//! A counted limit whose permit is released by `Drop`.
//!
//! Both sides of the control channel need "at most N of these at once": the service caps concurrent
//! connections, and the window caps concurrent calls to it. Both wrote the counter by hand, and both
//! wrote the release as a statement *after* the work — which is skipped when the work panics.
//!
//! That is not theoretical on either side. The service's handler takes a `Mutex` with `.expect(...)`,
//! which panics on a poisoned lock, and then runs a large `handle`. The window's calls into
//! `ipc::ask` and `answer`. After N panics the counter is stuck at the cap and the channel is closed
//! for good — the exact total-wedge the cap exists to prevent, arrived at through the cap itself.
//!
//! A `Drop` guard runs on the unwind path, so the permit cannot be lost. That is the whole point of
//! putting it in a type rather than in a comment claiming it is handled.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

/// A counter with a ceiling. Cloneable, because a permit holds one.
#[derive(Debug)]
pub struct Capacity {
    live: AtomicUsize,
    limit: usize,
}

impl Capacity {
    pub fn new(limit: usize) -> Arc<Self> {
        Arc::new(Self { live: AtomicUsize::new(0), limit })
    }

    /// Take a permit, or `None` when [`Capacity::limit`] are already out.
    ///
    /// The load-then-add is not atomic, and does not need to be: a caller racing another can at worst
    /// push the count one past the limit for an instant, and the alternative — a compare-exchange loop
    /// — buys a strictness nobody can observe for a check that runs on every keystroke. What matters is
    /// that the count comes back down, which the permit guarantees.
    pub fn take(self: &Arc<Self>) -> Option<Permit> {
        if self.live.load(Ordering::SeqCst) >= self.limit {
            return None;
        }
        self.live.fetch_add(1, Ordering::SeqCst);
        Some(Permit { owner: Arc::clone(self) })
    }

    pub fn limit(&self) -> usize {
        self.limit
    }

    /// How many are out. For tests and diagnostics.
    pub fn live(&self) -> usize {
        self.live.load(Ordering::SeqCst)
    }
}

/// One slot, given back when this is dropped — including while unwinding from a panic.
#[derive(Debug)]
pub struct Permit {
    owner: Arc<Capacity>,
}

impl Drop for Permit {
    fn drop(&mut self) {
        self.owner.live.fetch_sub(1, Ordering::SeqCst);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_permit_is_returned_when_it_is_dropped() {
        let capacity = Capacity::new(2);
        assert_eq!(capacity.live(), 0);
        {
            let _first = capacity.take().unwrap();
            let _second = capacity.take().unwrap();
            assert_eq!(capacity.live(), 2);
            assert!(capacity.take().is_none(), "the cap was not enforced");
        }
        assert_eq!(capacity.live(), 0, "the permits were not returned");
        assert!(capacity.take().is_some(), "the slots did not come back");
    }

    /// **The property the hand-written counter did not have.** A panic unwinds past a trailing
    /// `fetch_sub`, so the slot was leaked and the channel closed for good after `limit` panics.
    #[test]
    fn a_permit_is_returned_even_when_the_work_panics() {
        let capacity = Capacity::new(1);
        let c = Arc::clone(&capacity);

        let panicked = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
            let _permit = c.take().expect("a slot should be free");
            panic!("the handler blew up");
        }));
        assert!(panicked.is_err(), "the closure should have panicked");

        assert_eq!(capacity.live(), 0, "the slot leaked through a panic");
        assert!(capacity.take().is_some(), "the channel was wedged by its own cap");
    }

    #[test]
    fn the_limit_is_whatever_it_was_given() {
        let capacity = Capacity::new(3);
        assert_eq!(capacity.limit(), 3);
        let _a = capacity.take().unwrap();
        let _b = capacity.take().unwrap();
        let _c = capacity.take().unwrap();
        assert!(capacity.take().is_none());
    }
}
