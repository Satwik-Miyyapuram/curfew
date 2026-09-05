//! Proof that the machine was actually restarted.
//!
//! [`Lock::RestartRequired`] is the condition that costs a reboot: everything you had open closes,
//! and the tab you were about to go back to is gone. It is the cheapest strong lock we have,
//! because the friction is real and nothing has to be typed.
//!
//! It cannot be satisfied by a claim. What makes it provable locally is the boot id already read
//! for the trusted clock (see [`crate::clock`]): a value that changes on every boot and that no
//! setting can restore. So this module writes down which boot a session was *first seen in* on this
//! device, and calls the condition satisfied once the current boot is a different one.
//!
//! Two things follow from "first seen here" rather than "started at":
//!
//! * A session adopted from a paired device counts from the moment this device heard about it, so
//!   a phone that was switched off while the PC started a lock does not arrive already released.
//! * A restart is a restart however it happened. A power cut satisfies this as surely as choosing
//!   Restart does, which is honest: the lock asked for the machine to go down, and it went down.
//!
//! [`Lock::RestartRequired`]: crate::lock::Lock::RestartRequired

use crate::lock::Lock;
use crate::session::Session;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

/// Which boot each running session was first seen in. Persisted with the rest of the state — a
/// record of reboots that did not survive reboots would answer its own question wrongly.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Boots {
    seen: BTreeMap<String, u64>,
}

impl Boots {
    /// Note the sessions running now, and forget the ones that are not.
    ///
    /// Forgetting matters: an id that came back — a session ended and restarted under the same name
    /// by a schedule — must not inherit the first sighting of the old one, or the new lock would
    /// begin already satisfied.
    pub fn observe(&mut self, boot_id: u64, running: &[Session]) {
        for session in running {
            self.seen.entry(session.id.clone()).or_insert(boot_id);
        }
        let live: BTreeSet<&str> = running.iter().map(|s| s.id.as_str()).collect();
        self.seen.retain(|id, _| live.contains(id.as_str()));
    }

    /// Whether the machine has been restarted since this session was first seen here.
    pub fn restarted(&self, id: &str, boot_id: u64) -> bool {
        self.seen.get(id).is_some_and(|first| *first != boot_id)
    }

    /// What a restart proves about this session, in the form [`crate::Sessions::end`] takes.
    pub fn evidence(&self, id: &str, boot_id: u64) -> BTreeSet<Lock> {
        if self.restarted(id, boot_id) {
            [Lock::RestartRequired].into()
        } else {
            BTreeSet::new()
        }
    }
}

/// A boot id for platforms that do not publish one.
///
/// Android hands out a value that changes on every boot; Windows does not, and the obvious
/// substitute -- "boot time is the wall clock minus uptime" -- is exactly the wrong thing here,
/// because moving the system clock would then look like a reboot and would open every
/// [`Lock::RestartRequired`] on the machine. So the id is derived from uptime alone: a monotonic
/// counter that no setting can move and that only ever restarts at zero when the machine does.
///
/// Uptime going backwards is the whole signal. It cannot happen within one boot, so seeing it is
/// proof of a restart, and the counter that follows is this device's own private numbering -- it is
/// never compared with another device's, so it needs no agreement with anybody.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct BootCounter {
    last_uptime: i64,
    boot_id: u64,
}

impl BootCounter {
    /// Fold in a reading of the machine's uptime and return the id of the boot it belongs to.
    pub fn observe(&mut self, uptime: i64) -> u64 {
        if self.boot_id == 0 || uptime < self.last_uptime {
            self.boot_id += 1;
        }
        self.last_uptime = uptime;
        self.boot_id
    }

    /// The current id without taking a reading. Zero before the first one.
    pub fn boot_id(&self) -> u64 {
        self.boot_id
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lock::LockSet;
    use crate::session::SessionSource;

    const NOW: crate::Timestamp = 1_788_510_600;

    fn session(id: &str) -> Session {
        Session {
            id: id.into(),
            profile: "deep-work".into(),
            source: SessionSource::Manual,
            started_at: NOW,
            lock: LockSet::new([Lock::RestartRequired], None),
        }
    }

    #[test]
    fn a_session_that_has_not_lived_through_a_reboot_proves_nothing() {
        let mut boots = Boots::default();
        boots.observe(7, &[session("a")]);
        boots.observe(7, &[session("a")]);
        assert!(!boots.restarted("a", 7));
        assert!(boots.evidence("a", 7).is_empty());
    }

    #[test]
    fn a_new_boot_id_is_the_proof() {
        let mut boots = Boots::default();
        boots.observe(7, &[session("a")]);
        boots.observe(8, &[session("a")]);
        assert!(boots.restarted("a", 8));
        assert_eq!(BTreeSet::from([Lock::RestartRequired]), boots.evidence("a", 8));
    }

    #[test]
    fn the_first_sighting_is_never_overwritten_by_a_later_one() {
        // Otherwise every pass would move the goalposts to the current boot and the condition
        // could never be met.
        let mut boots = Boots::default();
        boots.observe(7, &[session("a")]);
        boots.observe(8, &[session("a")]);
        boots.observe(8, &[session("a")]);
        assert!(boots.restarted("a", 8));
    }

    #[test]
    fn a_session_nobody_has_seen_here_yet_is_not_released_by_a_reboot() {
        let boots = Boots::default();
        assert!(!boots.restarted("a", 8));
    }

    #[test]
    fn an_id_that_ended_and_came_back_starts_again() {
        let mut boots = Boots::default();
        boots.observe(7, &[session("a")]);
        boots.observe(8, &[]);
        boots.observe(8, &[session("a")]);
        assert!(!boots.restarted("a", 8));
    }

    #[test]
    fn boot_ids_need_no_order_only_difference() {
        // Nothing promises the next boot id is larger; on some platforms it is a random value.
        let mut boots = Boots::default();
        boots.observe(9, &[session("a")]);
        boots.observe(2, &[session("a")]);
        assert!(boots.restarted("a", 2));
    }
    #[test]
    fn a_counter_numbers_the_first_boot_it_sees() {
        let mut counter = BootCounter::default();
        assert_eq!(1, counter.observe(30));
        assert_eq!(1, counter.observe(32));
    }

    #[test]
    fn uptime_going_backwards_is_a_reboot() {
        let mut counter = BootCounter::default();
        counter.observe(90_000);
        assert_eq!(2, counter.observe(12));
        assert_eq!(2, counter.observe(14));
    }

    #[test]
    fn a_wall_clock_moved_by_a_year_is_not_a_reboot() {
        // The reason the counter is built on uptime and not on "now minus uptime": a clock change
        // must never be a way to satisfy a restart lock.
        let mut counter = BootCounter::default();
        let first = counter.observe(500);
        assert_eq!(first, counter.observe(502));
    }

    #[test]
    fn a_counter_survives_being_written_down() {
        let mut counter = BootCounter::default();
        counter.observe(500);
        let json = serde_json::to_string(&counter).unwrap();
        let mut back: BootCounter = serde_json::from_str(&json).unwrap();
        assert_eq!(1, back.observe(600));
        assert_eq!(2, back.observe(3));
    }
}
