//! What the service must remember across a restart.
//!
//! Sessions outlive the process on purpose: a lock that a reboot, a crash or `taskkill` could end
//! is not a lock (design invariant 2). So this file is the one thing on the Windows side whose
//! corruption would be an escape hatch, and it is written and read accordingly — atomically, with
//! the previous good copy kept alongside, and never silently replaced by an empty one.

use curfew_core::{Consumption, Launches, Passes, Sessions, Timestamp};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};

/// Everything the service carries between passes.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Persisted {
    #[serde(default)]
    pub sessions: Sessions,
    #[serde(default)]
    pub usage: BTreeMap<String, Consumption>,
    #[serde(default)]
    pub launches: BTreeMap<String, Launches>,
    /// Emergency passes already spent. Persisted for the same reason sessions are: a ration a
    /// restart could forget would be no ration at all.
    #[serde(default)]
    pub passes: Passes,
    /// Which boot each running session was first seen in, and this machine's own numbering of
    /// its boots. Both persisted, because a restart-required lock whose evidence a restart erased
    /// would be a lock that could never be satisfied.
    #[serde(default)]
    pub boots: curfew_core::Boots,
    #[serde(default)]
    pub boot_counter: curfew_core::BootCounter,
    /// The trusted clock's baseline: the last wall clock and uptime seen together, and the time
    /// every later reading is measured from.
    ///
    /// Persisted for the same reason the boot counter is, and it matters more. The witness is what
    /// refuses a wall clock that has been moved, and a baseline that a restart reset would make
    /// "stop the service, set the clock, start the service" a way to end a timer lock early.
    #[serde(default)]
    pub clock: Option<curfew_core::ClockWitness>,
    /// Releases this device has given for peer locks. Kept because a release is a promise made to
    /// the other device, and a service restart must not take it back.
    #[serde(default)]
    pub releases: std::collections::BTreeSet<String>,
    /// Sessions that have finished, for the statistics. Kept for thirty days and pruned by the
    /// enforcer, not here: this file records, it does not decide.
    #[serde(default)]
    pub history: Vec<curfew_core::stats::SessionRecord>,
    /// When the last pass ran. Used to charge elapsed time honestly across a restart, and to notice
    /// that the machine was off — a gap is not usage.
    #[serde(default)]
    pub last_tick: Option<Timestamp>,
}

/// How a load went. Never a bare `Result`: "the file is gone" and "the file is nonsense" have to be
/// told apart by the caller, because one of them is a fresh install and the other is either
/// corruption or somebody trying to delete their way out of a lock.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Loaded {
    /// Read cleanly.
    Ok(Persisted),
    /// Nothing was there. A first run.
    Fresh,
    /// The main file could not be read, and this is what the backup held. The service enforces
    /// from it and says so, rather than starting empty: an unreadable file must not be a way out.
    Recovered { state: Persisted, detail: String },
    /// Neither copy could be read. The caller starts empty because there is nothing else it can
    /// do, but it must say so loudly — every running lock has just been lost.
    Lost { detail: String },
}

/// `%ProgramData%\Curfew\state.json` — outside the user's profile, so a standard user cannot edit
/// it, and the service's own ACL is what keeps it that way.
pub fn default_path() -> PathBuf {
    let root = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".to_string());
    Path::new(&root).join("Curfew").join("state.json")
}

fn backup_of(path: &Path) -> PathBuf {
    path.with_extension("bak")
}

pub fn load(path: &Path) -> Loaded {
    match std::fs::read_to_string(path) {
        Ok(text) => match serde_json::from_str(&text) {
            Ok(state) => Loaded::Ok(state),
            Err(e) => recover(path, e.to_string()),
        },
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            match std::fs::read_to_string(backup_of(path)) {
                // The main file is missing but a backup is not: this is either a crash between the two
                // writes or a deliberate deletion, and both are answered the same way.
                Ok(text) => match serde_json::from_str(&text) {
                    Ok(state) => {
                        Loaded::Recovered { state, detail: "state file was missing".into() }
                    }
                    Err(e) => Loaded::Lost {
                        detail: format!("state file missing, backup unreadable: {e}"),
                    },
                },
                Err(_) => Loaded::Fresh,
            }
        }
        Err(e) => recover(path, e.to_string()),
    }
}

fn recover(path: &Path, detail: String) -> Loaded {
    match std::fs::read_to_string(backup_of(path)) {
        Ok(text) => match serde_json::from_str(&text) {
            Ok(state) => Loaded::Recovered { state, detail },
            Err(e) => Loaded::Lost { detail: format!("{detail}; backup also unreadable: {e}") },
        },
        Err(_) => Loaded::Lost { detail },
    }
}

/// Write the state, keeping the previous copy as the backup.
///
/// Order matters: the current file is copied to `.bak` first, then the new one is written to a
/// temporary file and renamed over the top. At every instant at least one complete file exists.
pub fn save(path: &Path, state: &Persisted) -> io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    if path.exists() {
        std::fs::copy(path, backup_of(path))?;
    }
    let temp = path.with_extension("tmp");
    std::fs::write(&temp, serde_json::to_vec_pretty(state)?)?;
    std::fs::rename(&temp, path)
}
