//! One pass of enforcement, and the only place the Windows side keeps mutable state.
//!
//! Everything below this module is either pure or a thin wrapper over the machine. This is where
//! they are joined: schedules are reconciled into sessions, sessions become the [`State`] the core
//! decides against, and that decision reaches the machine as processes closed and names blocked.
//!
//! The tick is deliberately dumb and repeatable. It is driven by a timer the service owns, it takes
//! `now` as an argument like everything else in this project, and running it twice for the same
//! instant changes nothing — because a service that crashes mid-pass has to be able to just run the
//! pass again.

use crate::blocked::blocked_domains;
use crate::hosts;
use crate::ipc::{Request, Response, Status};
use crate::procs::{enforce, Outcome, Process, Processes};
use curfew_core::engine::charged_keys;
use curfew_core::stats::{summarize, SessionRecord, Stats};
use curfew_core::{
    active_at, Config, Consumption, Launches, Platform, Session, Sessions, State, Timestamp,
};
use curfew_core::{CalendarEvent, Observation};
use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;

/// What one pass did, for the tray, the log and the tests.
/// How long a finished session stays on record. The same thirty days the phone keeps.
pub const HISTORY_SECONDS: Timestamp = 30 * 24 * 60 * 60;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Tick {
    /// Sessions a schedule started this pass.
    pub started: Vec<String>,
    /// Sessions whose lock expired and which are therefore over.
    pub ended: Vec<Session>,
    /// The profile a countdown froze this pass, if one fired.
    pub froze: Option<String>,
    pub processes: Outcome,
    /// The names now written to the hosts file.
    pub domains: BTreeSet<String>,
    /// Why the hosts file could not be written, if it could not.
    ///
    /// Not an error the pass fails on: losing the hosts file (a virus scanner holding it open, a
    /// permission change) must not also stop Curfew closing applications. It is surfaced so the UI
    /// can say website blocking is not working, which is the honest thing to do.
    pub hosts_error: Option<String>,
}

/// Conditions a caller is allowed to assert for itself.
///
/// A confirmation dialog and a retyped passage happen entirely in the UI: there is no machine fact
/// underneath them, so the process that showed them is the only possible witness and refusing its
/// word would make those locks unusable rather than stronger. Everything else -- a password, a tag,
/// a reboot, a peer -- is checked by the service, and is therefore never taken on a caller's say-so.
fn claimable(lock: &curfew_core::Lock) -> bool {
    use curfew_core::Lock;
    matches!(lock, Lock::Timer | Lock::Confirm | Lock::Challenge { .. })
}

/// The service's state between passes.
pub struct Enforcer {
    pub config: Config,
    pub sessions: Sessions,
    /// Time spent, keyed by [`curfew_core::Target::key`]. Persisted by the caller.
    pub usage: BTreeMap<String, Consumption>,
    pub launches: BTreeMap<String, Launches>,
    /// Where the hosts file is. A field rather than a constant so tests never touch the real one.
    pub hosts_path: PathBuf,
    /// Where the config was read from, so a reload knows what to re-read. `None` when it was
    /// handed in directly, as the tests do.
    pub config_path: Option<PathBuf>,
    /// Sessions that have finished, kept for thirty days so `curfew stats` can say what the
    /// fortnight looked like. Persisted, and pruned on every pass.
    pub history: Vec<SessionRecord>,
    /// The running sessions as of the last pass, keyed by id. A pass that finds one of these gone
    /// writes it into [`Enforcer::history`], which is how ends get recorded no matter which of the
    /// several ways a session can end took it: a reap, a satisfied lock, a pass, a peer.
    watching: BTreeMap<String, (String, Timestamp)>,
    /// What the last pass did, so a status request is answered from fact rather than by running
    /// enforcement again on the caller's schedule.
    pub last: Tick,
    /// Set when the state file could not be read at startup: locks may have been lost, and the
    /// user is owed that fact.
    pub state_warning: Option<String>,
    /// Emergency passes spent so far, on this device and on every device it has heard from. The
    /// quota is one quota, so this set is merged rather than owned.
    pub passes: curfew_core::Passes,
    /// Which boot each running session was first seen in, so `Lock::RestartRequired` is answered
    /// by a fact about the machine rather than by a caller saying it rebooted. Persisted.
    pub boots: curfew_core::Boots,
    /// This boot's id, and the counter that derives it from uptime. Both persisted: a machine
    /// that forgot which boot it was in would answer a restart lock by guessing.
    pub boot_id: u64,
    pub boot_counter: curfew_core::BootCounter,
    /// Conditions this device has actually checked recently -- a password Windows accepted, a tag
    /// that matched. Deliberately not persisted: after a restart nothing is proven again.
    pub proofs: curfew_core::Proofs,
    /// Releases this device has given, for locks that name it. Published to the op-log by the
    /// caller and persisted, so a release survives the service being restarted.
    pub releases: BTreeSet<String>,
    /// Releases every device has given, from the op-log. The evidence for `Lock::PeerRelease`.
    pub released: BTreeMap<String, BTreeSet<String>>,
    /// This device's own id in the op-log, once sync knows it. `None` on a machine that has never
    /// paired, where no peer lock can name it anyway.
    pub device_id: Option<String>,
    /// A whole-device freeze that has been announced and not yet happened (GAPS B4). At most one:
    /// two countdowns racing each other would leave nobody able to say what is about to occur.
    pub freeze: Option<curfew_core::Countdown>,
    /// Delay countdowns in flight, one per held executable. Deliberately not persisted: a wait is
    /// seconds long, and a service restart that reset one would be indistinguishable from the app
    /// having been closed and reopened.
    pub gates: crate::delay::Gates,
    /// Which browsers have an extension answering for them. Not persisted either: after a restart
    /// every browser is unproven, and each gets its startup grace to say so again.
    pub watch: crate::extension::Watch,
    /// Distinguishes ids minted in the same second. Sessions outlive the process, so ids must not
    /// collide across a restart either — hence the timestamp in the id as well.
    counter: usize,
}

impl Enforcer {
    pub fn new(config: Config, hosts_path: PathBuf) -> Self {
        Self {
            config,
            sessions: Sessions::default(),
            usage: BTreeMap::new(),
            launches: BTreeMap::new(),
            hosts_path,
            config_path: None,
            history: Vec::new(),
            watching: BTreeMap::new(),
            last: Tick::default(),
            state_warning: None,
            passes: Default::default(),
            boots: Default::default(),
            boot_id: 0,
            boot_counter: Default::default(),
            proofs: Default::default(),
            releases: BTreeSet::new(),
            released: BTreeMap::new(),
            device_id: None,
            freeze: None,
            gates: Default::default(),
            watch: Default::default(),
            counter: 0,
        }
    }

    /// Take a reading of the machine's uptime, which is what says whether it has been restarted.
    ///
    /// Called by the caller rather than from inside [`Enforcer::tick`] so that the one thing here
    /// that depends on the real machine stays at the edge, where a test can hand it a number and a
    /// reboot is two lines rather than a reboot.
    pub fn observe_boot(&mut self, uptime: i64) {
        self.boot_id = self.boot_counter.observe(uptime);
    }

    /// Everything this device can prove about a session's lock without being told.
    ///
    /// This is what separates a condition that is *checked* from one that is merely claimed. A
    /// reboot is proven by the boot id; a peer release by a signed entry in the log; a password or
    /// a tag by the check that was made when it was presented, for as long as that proof is fresh.
    /// Nothing a caller says reaches this function.
    pub fn proven(&self, id: &str, now: Timestamp) -> BTreeSet<curfew_core::Lock> {
        let mut evidence = self.boots.evidence(id, self.boot_id);
        evidence.extend(self.proofs.fresh(id, now));
        for device in self.released.get(id).into_iter().flatten() {
            evidence.insert(curfew_core::Lock::PeerRelease { device_id: device.clone() });
        }
        evidence
    }

    /// Sessions whose lock asks *this* device for the release.
    pub fn releasable(&self) -> Vec<String> {
        let Some(mine) = &self.device_id else { return Vec::new() };
        self.sessions
            .running
            .iter()
            .filter(|s| {
                s.lock.conditions.iter().any(|lock| {
                    matches!(lock, curfew_core::Lock::PeerRelease { device_id } if device_id == mine)
                })
            })
            .map(|s| s.id.clone())
            .collect()
    }

    /// Note that the sessions running now are the sessions running now.
    ///
    /// Called after restoring state, so a session that ends while the service is stopped is still
    /// recorded when the next pass notices it is gone.
    pub fn watch_sessions(&mut self) {
        self.watching = self
            .sessions
            .running
            .iter()
            .map(|s| (s.id.clone(), (s.profile.clone(), s.started_at)))
            .collect();
    }

    /// Move sessions that are no longer running into the history.
    ///
    /// The end time is this pass's `now` rather than the exact instant the session ended, which
    /// can be up to one pass earlier. A statistic measured in days can afford that; threading an
    /// exact end through every one of the ways a session can finish could not be afforded, and
    /// each of those paths is one more place to forget.
    fn remember(&mut self, now: Timestamp) {
        let running: BTreeMap<String, (String, Timestamp)> = self
            .sessions
            .running
            .iter()
            .map(|s| (s.id.clone(), (s.profile.clone(), s.started_at)))
            .collect();
        for (id, (profile, started_at)) in &self.watching {
            if !running.contains_key(id) {
                self.history.push(SessionRecord {
                    profile: profile.clone(),
                    started_at: *started_at,
                    ended_at: Some(now.max(*started_at)),
                });
            }
        }
        self.watching = running;
        // The same thirty days the phone keeps, for the same reason: old slices answer no question
        // anybody asks, and are the only part of this that is sensitive.
        let cutoff = now - HISTORY_SECONDS;
        self.history.retain(|record| record.ended_at.unwrap_or(now) >= cutoff);
    }

    /// What the last `days` days of blocking added up to.
    ///
    /// Running sessions are included, counted up to `now`: a day you are in the middle of blocking
    /// is a day you blocked.
    pub fn stats(&self, now: Timestamp, days: u32) -> Result<Stats, String> {
        let tz = self.config.tz().map_err(|e| e.to_string())?;
        Ok(summarize(&self.session_records(), now, tz, days))
    }

    /// Everything the statistics are computed from: what has finished, and what is still going.
    pub fn session_records(&self) -> Vec<SessionRecord> {
        let mut records = self.history.clone();
        records.extend(self.sessions.running.iter().map(|s| SessionRecord {
            profile: s.profile.clone(),
            started_at: s.started_at,
            ended_at: None,
        }));
        records
    }

    /// The world as the core sees it at `now`.
    pub fn state(&self, now: Timestamp) -> State {
        State {
            active_profiles: self.sessions.active_profiles(now),
            lock: self.sessions.merged_lock(now),
            usage: self.usage.clone(),
            launches: self.launches.clone(),
            platform: Platform::Windows,
        }
    }

    /// Charge `seconds` against whatever budgets the foreground window falls under.
    ///
    /// Only the foreground gets charged: a budgeted app minimized behind the editor is not being
    /// used, and a blocker that eats an hour's allowance while its window sits unseen would be
    /// wrong in the direction that makes people uninstall it.
    fn accrue(&mut self, now: Timestamp, seconds: u32, foreground: Option<Process>) {
        let Some(process) = foreground else { return };
        if seconds == 0 {
            return;
        }
        let obs = Observation::Window { exe: process.exe, title: process.title };
        let state = self.state(now);
        for key in charged_keys(&state, &obs, &self.config) {
            self.usage.entry(key).or_default().record(now, seconds);
        }
    }

    /// Run one pass.
    ///
    /// `elapsed` is the seconds since the previous pass, passed in rather than measured here for
    /// the same reason `now` is: a module that reads the clock cannot be tested against a clock
    /// that lies, and lying clocks are a threat this project takes seriously.
    pub fn tick(
        &mut self,
        now: Timestamp,
        elapsed: u32,
        events: &[CalendarEvent],
        processes: &impl Processes,
    ) -> Tick {
        self.accrue(now, elapsed, processes.foreground());

        let mut tick = Tick { froze: self.settle_freeze(now), ..Default::default() };
        if let Ok(tz) = self.config.tz() {
            let mut activations =
                active_at(now, tz, &self.config.weekly, &self.config.calendars, events);
            // A schedule may not freeze the machine on the stroke of the hour either. The first
            // pass that would start a whole-device session announces it instead and holds the
            // activation back; once the countdown has fired the session is running, and from then
            // on reconcile sees it as any other and keeps it alive to the end of the window.
            activations.retain(|activation| {
                let freezing = curfew_core::frozen::freezes(&self.config, &activation.profile);
                let running = self.sessions.for_profile(&activation.profile).is_some();
                if !freezing || running {
                    return true;
                }
                if self.freeze.is_none() {
                    self.freeze = Some(curfew_core::frozen::announce(
                        now,
                        &activation.profile,
                        (activation.end - now).max(0) as u32,
                        curfew_core::Origin::Local,
                        curfew_core::frozen::MINIMUM_WARNING_SECONDS,
                    ));
                }
                false
            });
            let counter = &mut self.counter;
            tick.started =
                curfew_core::reconcile(now, &mut self.sessions, &activations, |activation| {
                    *counter += 1;
                    format!("win-{now}-{}-{counter}", activation.profile)
                });
        }
        // Reaping after reconcile, never before: a session whose window has been extended must be
        // strengthened before anything asks whether it is over (design invariant 2).
        tick.ended = self.sessions.reap(now);

        // Which boot each surviving session belongs to, and which proofs are still fresh. Done
        // after the reap so a session that has just ended takes its bookkeeping with it.
        self.boots.observe(self.boot_id, &self.sessions.running);
        let running: Vec<String> = self.sessions.running.iter().map(|s| s.id.clone()).collect();
        self.proofs.prune(now, &running);

        self.remember(now);

        let state = self.state(now);
        tick.processes =
            enforce(now, &state, &self.config, processes, &mut self.gates, &mut self.watch);
        tick.domains = blocked_domains(now, &state, &self.config);

        if let Err(e) = hosts::apply(&self.hosts_path, &tick.domains) {
            tick.hosts_error = Some(e.to_string());
        }
        self.last = tick.clone();
        tick
    }

    /// Answer one control message.
    ///
    /// This is a privilege boundary: the service runs as SYSTEM and the caller does not. Every
    /// answer here therefore goes through the core rather than around it — in particular
    /// [`Request::End`] re-checks the lock, because a caller saying it satisfied a condition is not
    /// evidence that it did.
    pub fn handle(&mut self, now: Timestamp, request: Request) -> Response {
        match request {
            Request::Status => Response::Status(Status {
                now,
                running: self.sessions.running.clone(),
                blocked_domains: self.last.domains.clone(),
                failing: self.last.processes.failed.clone(),
                closed: self.last.processes.closed.clone(),
                unwatched: self.last.processes.unwatched.clone(),
                delayed: self.last.processes.delayed.clone(),
                freeze: self.freeze.clone(),
                hosts_error: self.last.hosts_error.clone(),
                state_warning: self.state_warning.clone(),
                passes_left: self.passes.remaining(now, &self.config.emergency),
                pass_refusal: self.passes.check(now, &self.config.emergency).err(),
                releasable: self.releasable(),
                released: self.releases.iter().cloned().collect(),
            }),

            Request::Start { profile, seconds, locks } => {
                if !self.config.profiles.iter().any(|p| p.id == profile) {
                    return Response::Error { detail: format!("no profile named {profile}") };
                }
                // A profile that takes the whole device away is announced, never started on the
                // spot: the one thing Curfew cannot give back is unsaved work (GAPS B4). The
                // countdown replaces any earlier one for the same profile rather than stacking,
                // and never shortens a countdown already ticking.
                if curfew_core::frozen::freezes(&self.config, &profile) {
                    let countdown = curfew_core::frozen::announce(
                        now,
                        &profile,
                        seconds,
                        curfew_core::Origin::Local,
                        curfew_core::frozen::MINIMUM_WARNING_SECONDS,
                    );
                    let countdown = match self.freeze.take() {
                        Some(existing) if existing.profile == countdown.profile => existing,
                        _ => countdown,
                    };
                    self.freeze = Some(countdown.clone());
                    return Response::Announced { countdown };
                }
                self.counter += 1;
                let counter = self.counter;
                // Merged rather than replaced: starting a session on a profile that already has one
                // may only ever add conditions or push the end time out (invariant 2). Asking for a
                // ten-minute block while a two-hour one is running is not a way to get ten minutes.
                let requested = curfew_core::LockSet::new(locks, Some(now + i64::from(seconds)));
                let lock = match self.sessions.for_profile(&profile) {
                    Some(existing) => existing.lock.merge(&requested),
                    None => requested,
                };
                let id = self
                    .sessions
                    .for_profile(&profile)
                    .map(|s| s.id.clone())
                    .unwrap_or_else(|| format!("win-{now}-{profile}-{counter}"));
                self.sessions.start(Session {
                    id,
                    profile,
                    source: curfew_core::SessionSource::Manual,
                    started_at: now,
                    lock,
                });
                Response::Ok
            }

            // A caller may claim the conditions it is the only witness to -- a confirmation it
            // showed, a passage it made the user retype -- and nothing else. Everything stronger is
            // supplied by `proven`, which asks the machine rather than the caller. Without this
            // filter, an `end` message listing `restart_required` as satisfied, typed into the pipe
            // by hand, would be every lock's way out.
            Request::End { id, satisfied } => {
                let mut satisfied: BTreeSet<curfew_core::Lock> =
                    satisfied.into_iter().filter(claimable).collect();
                satisfied.extend(self.proven(&id, now));
                match self.sessions.end(&id, now, &satisfied) {
                    Ok(_) => {
                        // Give the machine back in the same breath rather than waiting for the next
                        // pass: a lock that has ended but whose sites still fail to resolve reads as
                        // a broken machine.
                        self.proofs.forget(&id);
                        let _ = hosts::apply(&self.hosts_path, &self.last_domains(now));
                        Response::Ok
                    }
                    Err(refusal) => Response::Refused { refusal },
                }
            }

            Request::Unlock { id, username, domain, password } => {
                let secret = crate::credential::Secret::new(password);
                if !crate::credential::verify(&username, &domain, &secret) {
                    // Deliberately the same sentence whether the account exists or not, and no
                    // count of attempts: this is a commitment device, not a login screen, and the
                    // only thing worth saying is that the lock is still shut.
                    return Response::Error {
                        detail:
                            "Windows did not accept that password. The session is still locked."
                                .into(),
                    };
                }
                // Only this condition is proved by a password. A timer, a tag or a peer still has
                // to be satisfied its own way -- but the proof is written down for a couple of
                // minutes, so a lock asking for two things can be satisfied by doing two things
                // rather than being impossible to satisfy at all.
                self.proofs.record(&id, curfew_core::Lock::DeviceCredential, now);
                let satisfied = self.proven(&id, now);
                match self.sessions.end(&id, now, &satisfied) {
                    Ok(_) => {
                        let _ = hosts::apply(&self.hosts_path, &self.last_domains(now));
                        Response::Ok
                    }
                    Err(refusal) => Response::Refused { refusal },
                }
            }

            // The tag is checked here, against fingerprints, and the payload is not kept. A scan
            // of something else is not told apart from a scan of a tag this lock does not name:
            // both leave the lock shut, and neither is a way to enumerate the tags on the fridge.
            Request::Token { id, payload } => {
                match curfew_core::identify(&self.config.tokens, &payload) {
                    None => Response::Error {
                        detail:
                            "That is not a tag this machine knows. The session is still locked."
                                .into(),
                    },
                    Some(tag) => {
                        self.proofs.record(&id, curfew_core::Lock::Token { id: tag }, now);
                        let satisfied = self.proven(&id, now);
                        match self.sessions.end(&id, now, &satisfied) {
                            Ok(_) => {
                                self.proofs.forget(&id);
                                let _ = hosts::apply(&self.hosts_path, &self.last_domains(now));
                                Response::Ok
                            }
                            Err(refusal) => Response::Refused { refusal },
                        }
                    }
                }
            }

            // Recorded, never rescinded, and deliberately allowed for a session that is not running
            // here: the lock this releases is usually on the *other* device, and asking this one to
            // wait until it has heard of the session would make the button work only sometimes.
            Request::Release { id } => {
                self.releases.insert(id.clone());
                // A peer lock naming this device is satisfied by this device saying so, so try the
                // local end too: if the session is here and this was the last condition, it goes.
                if let Some(mine) = self.device_id.clone() {
                    self.released.entry(id.clone()).or_default().insert(mine);
                    let satisfied = self.proven(&id, now);
                    if self.sessions.end(&id, now, &satisfied).is_ok() {
                        self.proofs.forget(&id);
                        let _ = hosts::apply(&self.hosts_path, &self.last_domains(now));
                    }
                }
                Response::Ok
            }

            // Never refused, and never checked against a lock: nothing has been started, so there is
            // no promise to keep. A countdown that could not be called off would make the warning a
            // taunt rather than a courtesy.
            Request::CancelFreeze => {
                self.freeze = None;
                Response::Ok
            }

            Request::ConfirmFreeze => match self.freeze.as_mut() {
                None => Response::Error { detail: "nothing is counting down".into() },
                Some(countdown) => {
                    countdown.confirmed = true;
                    Response::Announced { countdown: countdown.clone() }
                }
            },

            // Spend first, then end. A pass that was taken and then refused because the session
            // had already finished is still a pass gone: the ration counts attempts to use the
            // hatch, not successes, or a user could probe it for free.
            Request::Emergency { id } => {
                let pass = match self.passes.spend(now, &self.config.emergency) {
                    Ok(pass) => pass,
                    Err(refusal) => return Response::NoPass { refusal },
                };
                match self.sessions.end_with_pass(&id, now, pass) {
                    Ok(_) => {
                        // Same as an ordinary end: the machine comes back in the same breath.
                        let _ = hosts::apply(&self.hosts_path, &self.last_domains(now));
                        Response::Ok
                    }
                    Err(refusal) => Response::Refused { refusal },
                }
            }

            Request::RequestRelease { id } => match self.sessions.request_release(&id, now) {
                Ok(at) => Response::Release { at },
                Err(refusal) => Response::Refused { refusal },
            },

            // Recorded and nothing else. A heartbeat is not a request to change anything, which is
            // why it is safe for it to be unauthenticated on a local pipe.
            Request::Beat { browser } => {
                self.watch.beat(&browser, now);
                Response::Ok
            }

            // Decided here rather than in the browser. The extension reports the URL and shows what
            // it is told; an extension someone has edited can lie about the URL, but it cannot mint
            // an allow, and lying about the URL is only worth doing to block yourself harder.
            Request::Check { browser, url } => {
                self.watch.beat(&browser, now);
                let state = self.state(now);
                let obs = Observation::Web { url: curfew_core::Url::parse(&url) };
                match curfew_core::decide(now, &state, &obs, &self.config) {
                    curfew_core::Decision::Block { reason } => Response::Verdict {
                        blocked: true,
                        reason: Some(crate::extension::explain(&reason)),
                    },
                    // A delay or a mute has no meaning for a page load, and blocking on a decision
                    // the engine did not make is the bug that gets a blocker uninstalled.
                    _ => Response::Verdict { blocked: false, reason: None },
                }
            }

            Request::Reload => match &self.config_path {
                None => Response::Error { detail: "no config path is configured".into() },
                Some(path) => match std::fs::read_to_string(path)
                    .map_err(|e| e.to_string())
                    .and_then(|text| Config::from_toml(&text).map_err(|e| e.to_string()))
                {
                    // A config that no longer parses changes nothing. The running locks stay as
                    // they are: an unparseable file must not be a way out either.
                    Err(detail) => Response::Error { detail },
                    Ok(config) => {
                        self.config = config;
                        // A delay the user has just rewritten should not be governed by a countdown
                        // started under the old rule.
                        self.gates.clear();
                        // Likewise a browser judged against rules that no longer exist.
                        self.watch.clear();
                        Response::Ok
                    }
                },
            },
        }
    }

    /// Fire, drop or leave alone the announced freeze. Returns the profile a freeze started.
    ///
    /// Run at the top of every pass so a countdown that has run out becomes a real session before
    /// anything else looks at what is running — a pass that enforced first would let the frozen
    /// minute through.
    fn settle_freeze(&mut self, now: Timestamp) -> Option<String> {
        use curfew_core::frozen::Due;
        let countdown = self.freeze.clone()?;
        match curfew_core::frozen::due(&countdown, now) {
            Due::Waiting | Due::AwaitingConfirmation => None,
            Due::Expired => {
                self.freeze = None;
                None
            }
            Due::Fire => {
                self.freeze = None;
                self.counter += 1;
                let counter = self.counter;
                self.sessions.start(Session {
                    id: format!("win-{now}-{}-{counter}", countdown.profile),
                    profile: countdown.profile.clone(),
                    source: curfew_core::SessionSource::Manual,
                    started_at: now,
                    lock: curfew_core::LockSet::new([], Some(now + i64::from(countdown.seconds))),
                });
                Some(countdown.profile)
            }
        }
    }

    /// The domains blocked by whatever is running right now, without running a whole pass.
    fn last_domains(&self, now: Timestamp) -> BTreeSet<String> {
        blocked_domains(now, &self.state(now), &self.config)
    }

    /// Give the machine back: no hosts entries, nothing enforced. Called when the service stops
    /// cleanly and by the uninstaller, both of which are only reachable when no lock is held.
    pub fn release(&self) -> std::io::Result<()> {
        hosts::clear(&self.hosts_path)
    }
}
