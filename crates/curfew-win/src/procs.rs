//! Blocking an application on Windows.
//!
//! The decision is the core's; this module only supplies what is running and carries out the
//! answer. The split matters twice over: the same config must decide identically on both platforms
//! (ARCHITECTURE §3), and process enumeration is the one part of this that cannot be tested without
//! a live machine — so it sits behind [`Processes`], and everything above it is tested with a fake.

use crate::delay::{Gates, Step};
use curfew_core::{decide, Config, Decision, Observation, State, Timestamp};
use std::collections::{BTreeMap, BTreeSet};

/// One running process, in the terms a rule can talk about.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Process {
    pub pid: u32,
    /// The executable's file name, no path — what a `windows_exe` rule names.
    pub exe: String,
    /// The foreground window's title, when it has one.
    pub title: String,
}

impl Process {
    fn observation(&self) -> Observation {
        Observation::Window { exe: self.exe.clone(), title: self.title.clone() }
    }
}

/// What the enforcer decided to do about one process.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    Leave,
    /// Close it, and tell the user which rule did it.
    Close {
        reason: curfew_core::BlockReason,
    },
    /// Let it run, but show the friction screen for `seconds` first.
    Delay {
        seconds: u32,
    },
}

/// The source of truth about what is running, and the thing that can stop it.
///
/// A trait rather than a concrete type so the loop above can be tested exhaustively — including the
/// cases that are painful to produce for real, like a process that refuses to die.
pub trait Processes {
    fn list(&self) -> Vec<Process>;
    /// Ask a process to end. Returning `false` is not an error: a process Curfew is not allowed to
    /// touch (another user's, or a protected one) is a fact about the machine, not a bug, and the
    /// caller reports it rather than retrying forever.
    fn terminate(&self, pid: u32) -> bool;
    /// The window the user is actually looking at, when that is knowable.
    ///
    /// Defaulted rather than required: budgets are charged against it, and a platform that cannot
    /// answer should charge nothing rather than guess. Guessing here spends someone's allowance on
    /// an app they were not using.
    fn foreground(&self) -> Option<Process> {
        None
    }
}

/// Decide about every running process, without touching any of them.
///
/// Separated from [`enforce`] so that "what would this config do to my machine right now" is
/// answerable — by a test, by the CLI's dry run, and by the UI before a session starts.
pub fn verdicts(
    now: Timestamp,
    state: &State,
    config: &Config,
    processes: &[Process],
) -> Vec<(Process, Verdict)> {
    processes
        .iter()
        .map(|p| {
            let verdict = match decide(now, state, &p.observation(), config) {
                Decision::Block { reason } => Verdict::Close { reason },
                Decision::Delay { seconds } => Verdict::Delay { seconds },
                // A mute is about notifications and cannot be produced for a window; treating it as
                // "leave alone" is the only safe reading, and closing on a decision the engine did
                // not mean is exactly the bug that gets a blocker uninstalled.
                Decision::Allow | Decision::Mute => Verdict::Leave,
            };
            (p.clone(), verdict)
        })
        .collect()
}

/// What one enforcement pass actually did.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Outcome {
    /// Executables that were closed, deduplicated: one app is many processes and the user thinks of
    /// it as one thing.
    pub closed: BTreeSet<String>,
    /// Executables Curfew decided to close and could not. These are reported, never hidden: a
    /// blocker that silently fails to block is worse than one that admits it.
    pub failed: BTreeSet<String>,
    /// Executables being held behind a delay rule, and how many seconds of the wait are left.
    ///
    /// A held app is closed like a blocked one, and the difference is entirely in what happens next:
    /// the countdown runs down, the app is left alone afterwards, and the user is told both facts.
    pub delayed: BTreeMap<String, i64>,
}

/// Carry out the verdicts.
///
/// `gates` carries the delay countdowns between passes, and is the only state this function keeps:
/// everything else is decided from the config and the clock every time.
pub fn enforce(
    now: Timestamp,
    state: &State,
    config: &Config,
    processes: &impl Processes,
    gates: &mut Gates,
) -> Outcome {
    let mut outcome = Outcome::default();
    let listing = processes.list();
    // Before anything is decided: an app that is no longer running has spent its wait, so the next
    // launch costs the same pause as the first.
    gates.forget_absent(&listing.iter().map(|p| p.exe.to_lowercase()).collect());

    for (process, verdict) in verdicts(now, state, config, &listing) {
        match verdict {
            Verdict::Leave => {}
            Verdict::Delay { seconds } => {
                match gates.consider(&process.exe, now, seconds) {
                    Step::Pass => {}
                    Step::Hold { seconds_left } => {
                        outcome.delayed.insert(process.exe.to_lowercase(), seconds_left);
                        // Held, not blocked — but on Windows the only way to hold an app that a
                        // program cannot ignore is to close it, so the tray says why and says how
                        // long. Failing to close it is not reported as a failure to block: the wait
                        // still runs down and the app was going to be allowed anyway.
                        processes.terminate(process.pid);
                    }
                }
            }
            Verdict::Close { .. } => {
                if processes.terminate(process.pid) {
                    outcome.closed.insert(process.exe.to_lowercase());
                } else {
                    outcome.failed.insert(process.exe.to_lowercase());
                }
            }
        }
    }
    outcome
}

/// The real process table.
#[derive(Default)]
pub struct SystemProcesses {
    /// Titles to use instead of asking the desktop. Empty in production; the escape hatch exists
    /// so a machine without a desktop session (a service before anyone logs in) can be driven.
    pub titles: std::collections::BTreeMap<u32, String>,
}

impl Processes for SystemProcesses {
    fn list(&self) -> Vec<Process> {
        let mut titles = crate::windows::window_titles();
        titles.extend(self.titles.clone());
        let mut system = sysinfo::System::new();
        system.refresh_processes(sysinfo::ProcessesToUpdate::All, true);
        system
            .processes()
            .iter()
            .map(|(pid, process)| {
                let id = pid.as_u32();
                Process {
                    pid: id,
                    exe: process.name().to_string_lossy().to_string(),
                    title: titles.get(&id).cloned().unwrap_or_default(),
                }
            })
            .collect()
    }

    fn terminate(&self, pid: u32) -> bool {
        let mut system = sysinfo::System::new();
        let pid = sysinfo::Pid::from_u32(pid);
        system.refresh_processes(sysinfo::ProcessesToUpdate::Some(&[pid]), true);
        // `kill` is a hard terminate. Windows has no portable "please close" that a program cannot
        // simply ignore, and a blocked app that ignores the request is the whole problem — but the
        // cost is unsaved work, so the UI warns before a session starts and Frozen mode never fires
        // without a countdown (GAPS B4).
        system.process(pid).map(|p| p.kill()).unwrap_or(false)
    }

    fn foreground(&self) -> Option<Process> {
        crate::windows::foreground()
    }
}
