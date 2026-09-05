//! Keeping this device's enforcement and the shared op-log saying the same thing.
//!
//! The service decides nothing about sync and the sync crate knows nothing about enforcement; this
//! is the seam between them, and it is deliberately a *diff* rather than a set of hooks sprinkled
//! through the enforcer. Every way a session can begin — a control message, a schedule, later a
//! calendar event — would otherwise need its own call into the log, and the one that got forgotten
//! would be a lock that never left the machine.
//!
//! Order matters and is the whole design:
//!
//! 1. Publish what happened here since the last pass. After this the log holds everything this
//!    device knows, its own doings included.
//! 2. Adopt the log back. Because step 1 already put local history in, the replay is a superset of
//!    what this device had, so usage and launches can be taken wholesale instead of merged by
//!    hand — which is what stops a device counting its own minutes twice.
//!
//! Adoption can only ever add. A session a peer started is started here; a session a peer ended is
//! ended here only if the *local* lock allows it, which is [`Sessions::end`] refusing, not this
//! module deciding. An `End` from a peer is a request, never an instruction.

use crate::node::Shared;
use crate::oplog::Op;
use curfew_core::budget::{Consumption, Launches};
use curfew_core::session::Sessions;
use curfew_core::Timestamp;
use std::collections::{BTreeMap, BTreeSet};

/// What one pass of the mirror did, for logging and for the status a UI shows.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Pass {
    /// Entries this device wrote to the log.
    pub published: usize,
    /// Sessions that started here because another device started them.
    pub adopted: Vec<String>,
    /// Sessions another device ended that are still locked here (GAPS C6). Not a conflict to
    /// resolve — the lock is doing its job — but the UI is owed the explanation.
    pub still_locked: Vec<String>,
}

/// How much of the local state has already been written to the log.
///
/// Kept as counts and ids rather than by re-reading the log, because "what have I already said" is
/// a question about this device's own history and must not become answerable by a peer.
#[derive(Debug, Default, Clone)]
pub struct Mirror {
    sessions: BTreeMap<String, Published>,
    usage: BTreeMap<String, usize>,
    launches: BTreeMap<String, usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Published {
    profile: String,
    /// The release time last announced, so a release is published once rather than every pass.
    release: Option<Timestamp>,
}

impl Mirror {
    /// Write everything that has happened on this device since the last pass into the log.
    pub fn publish(
        &mut self,
        shared: &Shared,
        now: Timestamp,
        sessions: &Sessions,
        usage: &BTreeMap<String, Consumption>,
        launches: &BTreeMap<String, Launches>,
    ) -> usize {
        let mut wrote = 0;

        for session in &sessions.running {
            let known = self.sessions.get(&session.id);
            // A session is republished when its lock changes as well as when it appears: the lock
            // is what the other device has to honour, and strengthening one here has to travel.
            let fresh = match known {
                None => true,
                Some(published) => published.profile != session.profile,
            };
            if fresh {
                shared.record(now, Op::Start { session: Box::new(session.clone()) });
                wrote += 1;
            }
            let release = session.lock.delayed_release_at;
            if let Some(at) = release.filter(|_| known.and_then(|p| p.release) != release) {
                shared.record(now, Op::ReleaseRequested { session: session.id.clone(), at });
                wrote += 1;
            }
            self.sessions.insert(
                session.id.clone(),
                Published { profile: session.profile.clone(), release },
            );
        }

        // Gone from `running` and not because time ran out: the user ended it and satisfied the
        // lock, so the other devices are told. A session that simply expired needs no entry —
        // both devices hold the same end time and reach it on their own.
        let running: BTreeSet<&String> = sessions.running.iter().map(|s| &s.id).collect();
        let ended: Vec<String> =
            self.sessions.keys().filter(|id| !running.contains(id)).cloned().collect();
        for id in ended {
            shared.record(now, Op::End { session: id.clone() });
            wrote += 1;
            self.sessions.remove(&id);
        }

        for (key, consumption) in usage {
            let said = self.usage.get(key).copied().unwrap_or(0);
            for rollup in consumption.rollups.iter().skip(said) {
                shared.record(
                    now,
                    Op::Used { key: key.clone(), at: rollup.at, seconds: rollup.seconds },
                );
                wrote += 1;
            }
            self.usage.insert(key.clone(), consumption.rollups.len());
        }

        for (key, opened) in launches {
            let said = self.launches.get(key).copied().unwrap_or(0);
            for at in opened.at.iter().skip(said) {
                shared.record(now, Op::Launched { key: key.clone(), at: *at });
                wrote += 1;
            }
            self.launches.insert(key.clone(), opened.at.len());
        }

        wrote
    }

    /// Take the log's view back into local enforcement.
    ///
    /// `usage` and `launches` are replaced rather than merged: after [`Mirror::publish`] the log
    /// contains this device's own rollups too, so the replay is the complete picture and merging
    /// would count local time twice.
    pub fn adopt(
        &mut self,
        shared: &Shared,
        now: Timestamp,
        sessions: &mut Sessions,
        usage: &mut BTreeMap<String, Consumption>,
        launches: &mut BTreeMap<String, Launches>,
    ) -> Pass {
        let believed = shared.replay(now);
        let mut adopted = Vec::new();

        for session in &believed.sessions.running {
            let before = sessions.for_profile(&session.profile).map(|s| s.lock.clone());
            sessions.start(session.clone());
            if before.is_none() {
                adopted.push(session.id.clone());
                // Adopted, so already in the log: republishing it would be this device claiming
                // authorship of another device's decision.
                self.sessions.insert(
                    session.id.clone(),
                    Published {
                        profile: session.profile.clone(),
                        release: session.lock.delayed_release_at,
                    },
                );
            }
        }

        // A session the log says is over, and that the local lock agrees is over, ends here too.
        // `Sessions::end` is the only exit, and it refuses while the lock still holds — which is
        // exactly the case reported as `still_locked`.
        let gone: Vec<String> = sessions
            .running
            .iter()
            .filter(|s| !believed.sessions.running.iter().any(|b| b.id == s.id))
            .map(|s| s.id.clone())
            .collect();
        let satisfied = BTreeSet::new();
        for id in gone {
            if sessions.end(&id, now, &satisfied).is_ok() {
                self.sessions.remove(&id);
            }
        }

        *usage = believed.usage;
        *launches = believed.launches;
        self.usage = usage.iter().map(|(k, v)| (k.clone(), v.rollups.len())).collect();
        self.launches = launches.iter().map(|(k, v)| (k.clone(), v.at.len())).collect();

        Pass { published: 0, adopted, still_locked: believed.still_locked }
    }

    /// One full pass: say what happened here, then take back what everyone knows.
    pub fn pass(
        &mut self,
        shared: &Shared,
        now: Timestamp,
        sessions: &mut Sessions,
        usage: &mut BTreeMap<String, Consumption>,
        launches: &mut BTreeMap<String, Launches>,
    ) -> Pass {
        let published = self.publish(shared, now, sessions, usage, launches);
        let mut pass = self.adopt(shared, now, sessions, usage, launches);
        pass.published = published;
        pass
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::device::Identity;
    use crate::oplog::Log;
    use crate::pair::{Invite, Peers};
    use curfew_core::session::{Session, SessionSource};
    use curfew_core::{Lock, LockSet};

    const NOW: Timestamp = 1_788_510_600;
    const HOUR: Timestamp = 60 * 60;

    struct Device {
        shared: Shared,
        mirror: Mirror,
        sessions: Sessions,
        usage: BTreeMap<String, Consumption>,
        launches: BTreeMap<String, Launches>,
    }

    impl Device {
        fn pass(&mut self, now: Timestamp) -> Pass {
            self.mirror.pass(
                &self.shared,
                now,
                &mut self.sessions,
                &mut self.usage,
                &mut self.launches,
            )
        }
    }

    /// Two devices paired with each other, each with its own mirror over its own log.
    fn two() -> (Device, Device) {
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
        on_phone.accept(&phone, &Invite::new(&pc, NOW, [9; 16]), NOW).unwrap();
        on_pc.accept(&pc, &Invite::new(&phone, NOW, [9; 16]), NOW).unwrap();
        let device = |identity, peers| Device {
            shared: Shared::new(identity, peers, Log::default()),
            mirror: Mirror::default(),
            sessions: Sessions::default(),
            usage: BTreeMap::new(),
            launches: BTreeMap::new(),
        };
        (device(phone, on_phone), device(pc, on_pc))
    }

    /// Hand everything one device holds to the other, the way a transport would.
    fn carry(from: &Device, to: &Device) {
        let entries = {
            let log = from.shared.log.lock().unwrap();
            log.since(&to.shared.log.lock().unwrap().heads())
        };
        let peers = to.shared.peers.lock().unwrap();
        let mut log = to.shared.log.lock().unwrap();
        crate::wire::receive(&mut log, &peers, &entries);
    }

    fn session(id: &str, profile: &str, lock: LockSet) -> Session {
        Session {
            id: id.into(),
            profile: profile.into(),
            source: SessionSource::Manual,
            started_at: NOW,
            lock,
        }
    }

    fn locked(until: Timestamp) -> LockSet {
        LockSet::new([Lock::DeviceCredential], Some(until))
    }

    #[test]
    fn a_session_started_here_reaches_the_other_device() {
        let (mut phone, mut pc) = two();
        pc.sessions.start(session("pc-1", "deep-work", locked(NOW + HOUR)));

        assert_eq!(pc.pass(NOW).published, 1);
        carry(&pc, &phone);
        let landed = phone.pass(NOW + 1);

        assert_eq!(landed.adopted, vec!["pc-1".to_string()]);
        assert_eq!(phone.sessions.running.len(), 1);
        assert!(phone.sessions.running[0].lock.is_locked());
    }

    #[test]
    fn nothing_is_said_twice() {
        // The pass runs every two seconds for as long as the machine is on. A mirror that
        // republished the running session each time would fill the log in an afternoon.
        let (_, mut pc) = two();
        pc.sessions.start(session("pc-1", "deep-work", locked(NOW + HOUR)));
        pc.usage.entry("chrome".into()).or_default().record(NOW, 30);

        assert_eq!(pc.pass(NOW).published, 2);
        for tick in 1..10 {
            assert_eq!(pc.pass(NOW + tick).published, 0, "the mirror repeated itself");
        }
        assert_eq!(pc.shared.log.lock().unwrap().len(), 2);
    }

    #[test]
    fn time_spent_on_one_device_is_spent_on_both() {
        // The point of sharing budgets: half an hour on the phone is half an hour gone from the
        // PC's allowance too, not a fresh one.
        let (mut phone, mut pc) = two();
        phone.usage.entry("com.instagram.android".into()).or_default().record(NOW, 1800);
        phone.pass(NOW);
        carry(&phone, &pc);
        pc.pass(NOW + 1);

        assert_eq!(pc.usage["com.instagram.android"].used_since(None), 1800);
    }

    #[test]
    fn a_devices_own_minutes_are_not_counted_twice() {
        let (_, mut pc) = two();
        pc.usage.entry("chrome".into()).or_default().record(NOW, 60);
        pc.pass(NOW);
        pc.usage.entry("chrome".into()).or_default().record(NOW + 2, 60);
        pc.pass(NOW + 2);

        assert_eq!(pc.usage["chrome"].used_since(None), 120, "local time was double counted");
    }

    #[test]
    fn a_peer_ending_a_locked_session_does_not_end_it_here() {
        // Invariant 2 across devices. Ending it on the phone must not be a way out of the PC's
        // lock; the PC says so instead of doing it.
        let (mut phone, mut pc) = two();
        pc.sessions.start(session("pc-1", "deep-work", locked(NOW + HOUR)));
        pc.pass(NOW);
        carry(&pc, &phone);
        phone.pass(NOW + 1);

        // The phone ends it locally — its own lock is whatever the phone's user could satisfy —
        // and says so.
        phone.shared.record(NOW + 2, Op::End { session: "pc-1".into() });
        carry(&phone, &pc);
        let pass = pc.pass(NOW + 3);

        assert_eq!(pc.sessions.running.len(), 1, "a peer ended a locked session");
        assert_eq!(pass.still_locked, vec!["pc-1".to_string()]);
    }

    #[test]
    fn a_session_ended_here_when_the_lock_allowed_it_ends_everywhere() {
        let (mut phone, mut pc) = two();
        pc.sessions.start(session("pc-1", "deep-work", LockSet::new([], Some(NOW + HOUR))));
        pc.pass(NOW);
        carry(&pc, &phone);
        phone.pass(NOW + 1);
        assert_eq!(phone.sessions.running.len(), 1);

        pc.sessions.end("pc-1", NOW + 2, &BTreeSet::new()).unwrap();
        pc.pass(NOW + 2);
        carry(&pc, &phone);
        phone.pass(NOW + 3);

        assert!(phone.sessions.running.is_empty(), "the other device kept a released session");
    }

    #[test]
    fn a_release_started_here_is_announced_once_and_honoured_there() {
        let (mut phone, mut pc) = two();
        pc.sessions.start(session("pc-1", "deep-work", locked(NOW + 30 * 24 * HOUR)));
        pc.pass(NOW);
        carry(&pc, &phone);
        phone.pass(NOW + 1);

        let at = pc.sessions.request_release("pc-1", NOW + 2).unwrap();
        assert_eq!(pc.pass(NOW + 2).published, 1);
        assert_eq!(pc.pass(NOW + 4).published, 0, "the release was announced again");
        carry(&pc, &phone);
        phone.pass(NOW + 5);

        assert_eq!(phone.sessions.running[0].lock.delayed_release_at, Some(at));
    }

    #[test]
    fn an_adopted_session_is_not_claimed_as_this_devices_own() {
        // If adoption fed the publisher, the two devices would take turns re-announcing the same
        // session forever and the log would grow without anything happening.
        let (mut phone, mut pc) = two();
        pc.sessions.start(session("pc-1", "deep-work", locked(NOW + HOUR)));
        pc.pass(NOW);
        carry(&pc, &phone);

        phone.pass(NOW + 1);
        let after = phone.shared.log.lock().unwrap().len();
        phone.pass(NOW + 3);

        assert_eq!(phone.shared.log.lock().unwrap().len(), after, "the phone echoed the PC");
    }

    #[test]
    fn two_devices_starting_at_once_end_up_holding_both_locks() {
        let (mut phone, mut pc) = two();
        phone.sessions.start(session("phone-1", "evenings", locked(NOW + HOUR)));
        pc.sessions.start(session("pc-1", "deep-work", locked(NOW + 2 * HOUR)));
        phone.pass(NOW);
        pc.pass(NOW);
        carry(&phone, &pc);
        carry(&pc, &phone);
        phone.pass(NOW + 1);
        pc.pass(NOW + 1);

        assert_eq!(phone.sessions.running.len(), 2);
        assert_eq!(pc.sessions.running.len(), 2);
        assert!(phone.sessions.merged_lock(NOW + 1).is_locked());
    }

    #[test]
    fn a_session_that_simply_ran_out_of_time_costs_no_entry() {
        // Both devices hold the same end time and reach it on their own; writing an End for it
        // would be a log entry per session per device for no information at all.
        let (_, mut pc) = two();
        pc.sessions.start(session("pc-1", "deep-work", LockSet::new([], Some(NOW + 60))));
        pc.pass(NOW);
        let before = pc.shared.log.lock().unwrap().len();

        pc.sessions.reap(NOW + 61);
        pc.pass(NOW + 61);

        assert_eq!(
            pc.shared.log.lock().unwrap().len(),
            before + 1,
            "an expiry wrote more than one"
        );
        assert!(pc.sessions.running.is_empty());
    }
}
