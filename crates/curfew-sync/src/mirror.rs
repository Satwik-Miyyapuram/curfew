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
use curfew_core::emergency::Passes;
use curfew_core::session::Sessions;
use curfew_core::{CalendarEvent, Timestamp};
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
    /// Calendar events other devices published, for this device's schedules to act on. This
    /// device's own events are not in here: it already has them, and taking its own snapshot back
    /// would make an event's id depend on whether sync happened to be running.
    pub calendar: Vec<CalendarEvent>,
    /// Which devices have released which session, so a `Lock::PeerRelease` can be checked without
    /// asking the network anything at the moment someone presses end. Keyed by session id, values
    /// are device ids as `Lock::PeerRelease { device_id }` spells them.
    pub released: BTreeMap<String, BTreeSet<String>>,
    /// Every emergency pass spent on any device, this one included. The caller adopts it whole:
    /// the quota is one ration shared between devices, not one each.
    pub passes: Passes,
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
    /// The calendar snapshot last written to the log, and when. Kept so an unchanged calendar is
    /// published once rather than every two seconds.
    calendar: Option<(Vec<CalendarEvent>, Timestamp)>,
    /// How many pass-uses have already been written to the log, so one is announced once.
    passes: usize,
    /// Sessions this device has already announced a release for. A release is said once; saying it
    /// again would be harmless (the log unions them) but would grow the log for nothing.
    released: BTreeSet<String>,
}

/// How long a published calendar snapshot stands before it is written again unchanged.
///
/// A snapshot is republished on change immediately; this is only the floor under an *unchanged*
/// one, which exists so that a device joining later is not left waiting on a calendar that never
/// changes.
pub const CALENDAR_SECONDS: Timestamp = 15 * 60;

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

    /// Publish emergency passes spent here that the log has not been told about yet.
    ///
    /// Separate from [`Mirror::publish`] because a pass is spent by a person pressing a button and
    /// not by the enforcement loop: it has to reach the other devices on the next pass whether or
    /// not anything else changed, and it must be published before the session it released is,
    /// so that a peer never sees the release without the use that paid for it.
    pub fn publish_passes(&mut self, shared: &Shared, now: Timestamp, passes: &Passes) -> usize {
        let mut wrote = 0;
        for at in passes.used.iter().skip(self.passes) {
            shared.record(now, Op::EmergencyUsed { at: *at });
            wrote += 1;
        }
        self.passes = passes.used.len();
        wrote
    }

    /// Announce the releases this device has agreed to give, for locks that name it.
    ///
    /// Only new ones are written. There is deliberately no way to un-announce: `Lock::PeerRelease`
    /// is satisfied the moment the named device says so, and a release that could be withdrawn
    /// would let one device re-lock another after the user had already been told they were free.
    pub fn publish_releases(
        &mut self,
        shared: &Shared,
        now: Timestamp,
        releases: &BTreeSet<String>,
    ) -> usize {
        let mut wrote = 0;
        for session in releases.difference(&self.released.clone()) {
            shared.record(now, Op::Released { session: session.clone() });
            self.released.insert(session.clone());
            wrote += 1;
        }
        wrote
    }

    /// Publish this device's calendar, if it has changed or has gone unsaid for long enough.
    ///
    /// `events` should be only what a rule on *this* device would act on. The log is encrypted and
    /// goes nowhere but the user's own devices, but a lock does not need to know the name of every
    /// meeting in someone's week to do its job, and the smallest thing that works is the thing to
    /// send.
    pub fn publish_calendar(
        &mut self,
        shared: &Shared,
        now: Timestamp,
        events: &[CalendarEvent],
    ) -> usize {
        let due = match &self.calendar {
            None => true,
            Some((said, at)) => said != events || now.saturating_sub(*at) >= CALENDAR_SECONDS,
        };
        if !due {
            return 0;
        }
        shared.record(now, Op::Calendar { events: events.to_vec() });
        self.calendar = Some((events.to_vec(), now));
        1
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

        // Another device's events, tagged with the device they came from. Without the tag, two
        // phones with the same meeting in the same calendar would produce one event id, and ending
        // one would look like ending both.
        let mine = shared.identity.id();
        let mut calendar: Vec<CalendarEvent> = believed
            .calendars
            .iter()
            .filter(|(author, _)| **author != mine)
            .flat_map(|(author, events)| {
                events.iter().cloned().map(move |mut event| {
                    event.id = format!("{author}/{}", event.id);
                    event
                })
            })
            .collect();
        calendar.sort_by(|a, b| a.start.cmp(&b.start).then_with(|| a.id.cmp(&b.id)));

        // Taken whole for the same reason usage is: after publishing, the log holds this device's
        // own uses too, so the replay is the complete ration rather than half of it.
        self.passes = believed.passes.used.len();

        Pass {
            published: 0,
            adopted,
            still_locked: believed.still_locked,
            calendar,
            passes: believed.passes,
            released: believed
                .released
                .into_iter()
                .map(|(session, devices)| {
                    (session, devices.iter().map(|d| d.as_str().to_string()).collect())
                })
                .collect(),
        }
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
    use curfew_core::emergency::{EmergencyPolicy, PassRefusal};
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
        passes: Passes,
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

        /// Spend a pass here and let the mirror carry it, the way the service does.
        fn spend(&mut self, now: Timestamp, policy: &EmergencyPolicy) -> Result<(), PassRefusal> {
            let spent = self.passes.spend(now, policy)?;
            assert_eq!(spent.at(), now);
            self.mirror.publish_passes(&self.shared, now, &self.passes);
            Ok(())
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
            passes: Passes::default(),
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

    fn event(id: &str, title: &str, start: Timestamp) -> CalendarEvent {
        CalendarEvent {
            id: id.into(),
            title: title.into(),
            calendar: "Work".into(),
            location: String::new(),
            start,
            end: start + HOUR,
            all_day: false,
            busy: true,
            categories: Vec::new(),
        }
    }

    #[test]
    fn a_calendar_seen_on_one_device_reaches_the_other() {
        // The point of the feature: a phone that was never given calendar permission still goes
        // quiet during a meeting, because the PC can see the meeting and says so.
        let (mut phone, mut pc) = two();
        pc.mirror.publish_calendar(&pc.shared, NOW, &[event("e1", "Design review", NOW + 600)]);
        carry(&pc, &phone);

        let pass = phone.pass(NOW + 1);

        assert_eq!(1, pass.calendar.len());
        assert_eq!("Design review", pass.calendar[0].title);
    }

    #[test]
    fn an_event_says_which_device_saw_it() {
        let (mut phone, mut pc) = two();
        pc.mirror.publish_calendar(&pc.shared, NOW, &[event("e1", "Standup", NOW + 600)]);
        carry(&pc, &phone);

        let pass = phone.pass(NOW + 1);

        assert_eq!(format!("{}/e1", pc.shared.identity.id()), pass.calendar[0].id);
    }

    #[test]
    fn a_device_does_not_adopt_its_own_calendar_back() {
        // It already holds these events untagged. Taking them back through sync would make an
        // event's id depend on whether sync happened to be running.
        let (mut phone, _pc) = two();
        phone.mirror.publish_calendar(&phone.shared, NOW, &[event("e1", "Standup", NOW + 600)]);

        assert!(phone.pass(NOW + 1).calendar.is_empty());
    }

    #[test]
    fn a_deleted_meeting_disappears_rather_than_lingering() {
        // A snapshot replaces its predecessor wholesale, which is why a cancelled meeting needs no
        // tombstone of its own.
        let (mut phone, mut pc) = two();
        let both = [event("e1", "Standup", NOW + 600), event("e2", "Review", NOW + 7200)];
        pc.mirror.publish_calendar(&pc.shared, NOW, &both);
        carry(&pc, &phone);
        phone.pass(NOW + 1);

        pc.mirror.publish_calendar(&pc.shared, NOW + 2, &both[1..]);
        carry(&pc, &phone);
        let pass = phone.pass(NOW + 3);

        assert_eq!(
            vec!["Review".to_string()],
            pass.calendar.iter().map(|e| e.title.clone()).collect::<Vec<_>>()
        );
    }

    #[test]
    fn one_device_clearing_its_calendar_leaves_anothers_alone() {
        let (mut phone, mut pc) = two();
        let identity = Identity::generate("laptop");
        let mut on_laptop = Peers::default();
        on_laptop
            .accept(&identity, &Invite::new(&phone.shared.identity, NOW, [9; 16]), NOW)
            .unwrap();
        phone
            .shared
            .peers
            .lock()
            .unwrap()
            .accept(&phone.shared.identity, &Invite::new(&identity, NOW, [9; 16]), NOW)
            .unwrap();
        let mut laptop = Device {
            shared: Shared::new(identity, on_laptop, Log::default()),
            mirror: Mirror::default(),
            sessions: Sessions::default(),
            usage: BTreeMap::new(),
            launches: BTreeMap::new(),
            passes: Passes::default(),
        };

        pc.mirror.publish_calendar(&pc.shared, NOW, &[event("e1", "PC meeting", NOW + 600)]);
        laptop.mirror.publish_calendar(
            &laptop.shared,
            NOW,
            &[event("e2", "Laptop meeting", NOW + 600)],
        );
        carry(&pc, &phone);
        carry(&laptop, &phone);
        phone.pass(NOW + 1);

        laptop.mirror.publish_calendar(&laptop.shared, NOW + 2, &[]);
        carry(&laptop, &phone);
        let pass = phone.pass(NOW + 3);

        assert_eq!(
            vec!["PC meeting".to_string()],
            pass.calendar.iter().map(|e| e.title.clone()).collect::<Vec<_>>(),
        );
    }

    #[test]
    fn an_unchanged_calendar_is_not_republished_every_pass() {
        // A pass runs every two seconds. Writing the week's meetings each time would fill the log
        // in an afternoon.
        let (_phone, mut pc) = two();
        let events = [event("e1", "Standup", NOW + 600)];

        assert_eq!(1, pc.mirror.publish_calendar(&pc.shared, NOW, &events));
        assert_eq!(0, pc.mirror.publish_calendar(&pc.shared, NOW + 2, &events));
        assert_eq!(0, pc.mirror.publish_calendar(&pc.shared, NOW + CALENDAR_SECONDS - 1, &events));
    }

    #[test]
    fn a_changed_calendar_is_published_at_once() {
        let (_phone, mut pc) = two();
        pc.mirror.publish_calendar(&pc.shared, NOW, &[event("e1", "Standup", NOW + 600)]);

        assert_eq!(
            1,
            pc.mirror.publish_calendar(&pc.shared, NOW + 2, &[event("e1", "Standup", NOW + 900)]),
        );
    }

    #[test]
    fn an_unchanged_calendar_is_said_again_eventually() {
        // So a device that pairs later is not left waiting on a calendar that never changes.
        let (_phone, mut pc) = two();
        let events = [event("e1", "Standup", NOW + 600)];
        pc.mirror.publish_calendar(&pc.shared, NOW, &events);

        assert_eq!(1, pc.mirror.publish_calendar(&pc.shared, NOW + CALENDAR_SECONDS, &events));
    }

    #[test]
    fn a_stranger_s_calendar_is_never_absorbed() {
        // Anyone can shout a snapshot at a device. Only paired devices are listened to, and a
        // calendar is an instruction to block, so an unpaired one must not reach enforcement.
        let (mut phone, _pc) = two();
        let stranger = Device {
            shared: Shared::new(Identity::generate("stranger"), Peers::default(), Log::default()),
            mirror: Mirror::default(),
            sessions: Sessions::default(),
            usage: BTreeMap::new(),
            launches: BTreeMap::new(),
            passes: Passes::default(),
        };
        let mut stranger = stranger;
        stranger.mirror.publish_calendar(&stranger.shared, NOW, &[event("e1", "Lunch", NOW + 600)]);
        carry(&stranger, &phone);

        assert!(phone.pass(NOW + 1).calendar.is_empty());
    }

    // --- the shared ration ---------------------------------------------------------------------

    fn rationed(passes: u32, cooldown: u32) -> EmergencyPolicy {
        EmergencyPolicy { passes, window_seconds: 7 * 24 * HOUR as u32, cooldown_seconds: cooldown }
    }

    /// The point of syncing passes at all: the quota is one ration for the person, not one per
    /// device they happen to own.
    #[test]
    fn a_pass_spent_on_one_device_is_spent_on_the_other() {
        let policy = rationed(1, 0);
        let (mut phone, mut pc) = two();

        phone.spend(NOW, &policy).expect("the phone has the only pass");
        carry(&phone, &pc);
        let seen = pc.pass(NOW + 1);

        pc.passes = seen.passes;
        assert_eq!(pc.passes.remaining(NOW + 1, &policy), 0);
        assert!(matches!(pc.passes.check(NOW + 1, &policy), Err(PassRefusal::QuotaSpent { .. })));
    }

    /// Buying a second phone must not buy a second allowance, even when the two are spent at the
    /// same instant on devices that have not spoken yet.
    #[test]
    fn two_devices_spending_at_once_do_not_produce_two_rations() {
        let policy = rationed(2, 0);
        let (mut phone, mut pc) = two();
        phone.spend(NOW, &policy).expect("phone");
        pc.spend(NOW + 30, &policy).expect("pc");

        carry(&phone, &pc);
        carry(&pc, &phone);
        let here = phone.pass(NOW + 60);
        let there = pc.pass(NOW + 60);

        assert_eq!(here.passes, there.passes);
        assert_eq!(here.passes.used.len(), 2);
        assert_eq!(here.passes.remaining(NOW + 60, &policy), 0);
    }

    #[test]
    fn a_pass_is_announced_once_rather_than_every_pass() {
        let policy = rationed(2, 0);
        let (mut phone, mut pc) = two();
        phone.spend(NOW, &policy).expect("the pass");

        // Nothing else has happened here, so a second pass of the mirror writes nothing at all.
        assert_eq!(phone.pass(NOW + 2).published, 0);
        assert_eq!(phone.pass(NOW + 4).published, 0);

        carry(&phone, &pc);
        assert_eq!(pc.pass(NOW + 6).passes.used, vec![NOW]);
    }

    /// Going quiet is not a way to earn passes back.
    #[test]
    fn a_device_that_was_offline_adopts_the_ration_it_missed() {
        let policy = rationed(1, 0);
        let (mut phone, mut pc) = two();
        phone.spend(NOW, &policy).expect("spent while the pc was away");

        // The pc believes it still has its pass, right up until it hears otherwise.
        assert!(pc.passes.check(NOW + HOUR, &policy).is_ok());
        carry(&phone, &pc);
        pc.passes = pc.pass(NOW + HOUR).passes;
        assert!(pc.passes.check(NOW + HOUR, &policy).is_err());
    }

    #[test]
    fn a_stranger_s_pass_use_is_never_absorbed() {
        // Anyone can shout an entry at a device. A pass-use is a claim that someone's ration is
        // gone, so an unpaired device saying it must change nothing here.
        let policy = rationed(1, 0);
        let (mut phone, _pc) = two();
        let stranger = Device {
            shared: Shared::new(Identity::generate("stranger"), Peers::default(), Log::default()),
            mirror: Mirror::default(),
            sessions: Sessions::default(),
            usage: BTreeMap::new(),
            launches: BTreeMap::new(),
            passes: Passes::default(),
        };
        stranger.shared.record(NOW, Op::EmergencyUsed { at: NOW });

        carry(&stranger, &phone);
        let seen = phone.pass(NOW + 1);
        assert_eq!(seen.passes, Passes::default());
        assert!(seen.passes.check(NOW + 1, &policy).is_ok());
    }

    #[test]
    fn a_release_given_here_reaches_the_device_holding_the_lock() {
        let (mut phone, mut pc) = two();
        let holder = pc.shared.identity.id().as_str().to_string();
        phone.sessions.running.push(session(
            "s1",
            "deep-work",
            LockSet::new([Lock::PeerRelease { device_id: holder.clone() }], None),
        ));
        phone.mirror.publish(&phone.shared, NOW, &phone.sessions, &phone.usage, &phone.launches);
        carry(&phone, &pc);
        pc.pass(NOW + 1);

        let mut releases = BTreeSet::new();
        releases.insert("s1".to_string());
        assert_eq!(pc.mirror.publish_releases(&pc.shared, NOW + 2, &releases), 1);
        carry(&pc, &phone);

        let pass = phone.pass(NOW + 3);
        assert_eq!(
            pass.released.get("s1").map(|d| d.contains(&holder)),
            Some(true),
            "the phone did not hear the release the PC gave"
        );
    }

    #[test]
    fn the_same_release_is_only_ever_written_once() {
        let (_phone, mut pc) = two();
        let mut releases = BTreeSet::new();
        releases.insert("s1".to_string());

        assert_eq!(pc.mirror.publish_releases(&pc.shared, NOW, &releases), 1);
        assert_eq!(pc.mirror.publish_releases(&pc.shared, NOW + 60, &releases), 0);

        releases.insert("s2".to_string());
        assert_eq!(pc.mirror.publish_releases(&pc.shared, NOW + 120, &releases), 1);
    }

    #[test]
    fn a_release_outlives_the_session_it_opened() {
        // The session ends, its entry is reaped, and the release stays in the log. A device that
        // syncs a week later must not adopt the session back without also hearing it was released.
        let (mut phone, mut pc) = two();
        let mut releases = BTreeSet::new();
        releases.insert("s1".to_string());
        pc.mirror.publish_releases(&pc.shared, NOW, &releases);
        carry(&pc, &phone);

        let pass = phone.pass(NOW + HOUR * 24 * 7);
        assert!(pass.released.contains_key("s1"));
    }
}
