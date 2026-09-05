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
use curfew_core::{
    active_at, Config, Consumption, Launches, Platform, Session, Sessions, State, Timestamp,
};
use curfew_core::{CalendarEvent, Observation};
use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;

/// What one pass did, for the tray, the log and the tests.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Tick {
    /// Sessions a schedule started this pass.
    pub started: Vec<String>,
    /// Sessions whose lock expired and which are therefore over.
    pub ended: Vec<Session>,
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
    /// What the last pass did, so a status request is answered from fact rather than by running
    /// enforcement again on the caller's schedule.
    pub last: Tick,
    /// Set when the state file could not be read at startup: locks may have been lost, and the
    /// user is owed that fact.
    pub state_warning: Option<String>,
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
            last: Tick::default(),
            state_warning: None,
            counter: 0,
        }
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

        let mut tick = Tick::default();
        if let Ok(tz) = self.config.tz() {
            let activations =
                active_at(now, tz, &self.config.weekly, &self.config.calendars, events);
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

        let state = self.state(now);
        tick.processes = enforce(now, &state, &self.config, processes);
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
                hosts_error: self.last.hosts_error.clone(),
                state_warning: self.state_warning.clone(),
            }),

            Request::Start { profile, seconds, locks } => {
                if !self.config.profiles.iter().any(|p| p.id == profile) {
                    return Response::Error { detail: format!("no profile named {profile}") };
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

            Request::End { id, satisfied } => {
                match self.sessions.end(&id, now, &satisfied) {
                    Ok(_) => {
                        // Give the machine back in the same breath rather than waiting for the next
                        // pass: a lock that has ended but whose sites still fail to resolve reads as
                        // a broken machine.
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
                        Response::Ok
                    }
                },
            },
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
