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
        tick
    }

    /// Give the machine back: no hosts entries, nothing enforced. Called when the service stops
    /// cleanly and by the uninstaller, both of which are only reachable when no lock is held.
    pub fn release(&self) -> std::io::Result<()> {
        hosts::clear(&self.hosts_path)
    }
}
