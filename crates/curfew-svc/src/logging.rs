//! Where the service's diagnostics go, because until now they went nowhere.
//!
//! **P1-12.** An SCM-started service has null standard handles, and Rust's `std` documents `eprintln!`
//! against them as *silent success*: it writes nothing and reports no error. This crate emits
//! diagnostics on sixty-odd paths — state save failure, an unreadable config, a calendar fetch that
//! failed, hosts failures, sync failures, a watchdog that would not spawn — and every one of them was
//! discarded. That contradicts §10's "no silent failure" and the rule stated in `main.rs`: *"a blocker
//! that quietly fails to block is worse than one that admits it."*
//!
//! So there is one sink, installed once at startup and consulted by [`note!`] and friends. It writes
//! **both** to the file and to stderr, because the two callers want different things: the installed
//! service has only the file, and a developer running `curfew.exe` from a console wants the line in
//! front of them rather than in `%ProgramData%`.
//!
//! Deliberately hand-rolled rather than a logging crate. The requirement is "these lines must survive
//! on disk and must not grow without bound", which is a `Mutex<File>` and a size check; a framework
//! would add a dependency, a runtime and a configuration format to a job this size.

use std::fmt;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

/// Keep the log below this, then roll once. Small on purpose: this is a diagnostic tail, not an
/// audit trail, and the records that matter for auditing are in the sync log and the state file.
const MAX_BYTES: u64 = 2 * 1024 * 1024;

/// One rolled file is kept, so the worst case on disk is twice [`MAX_BYTES`].
fn rolled(path: &Path) -> PathBuf {
    path.with_extension("log.1")
}

struct Sink {
    path: PathBuf,
    file: File,
}

static SINK: OnceLock<Mutex<Sink>> = OnceLock::new();

/// Where the log lives by default — beside the state file, under the same ACL, so a standard user
/// cannot edit or delete the record of what the service did.
pub fn default_path() -> PathBuf {
    let root = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".to_string());
    Path::new(&root).join("Curfew").join("curfew.log")
}

/// Start logging to `path`. Idempotent: a second call is ignored, so a test or a restart cannot end up
/// with two sinks racing over one file.
///
/// A path that cannot be opened is **not** fatal. A service that refuses to start because it cannot
/// write a log is worse than one that starts without a log, and the failure is itself reported to
/// stderr, which is where the installer sees it.
pub fn install(path: PathBuf) {
    if SINK.get().is_some() {
        return;
    }
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    match OpenOptions::new().create(true).append(true).open(&path) {
        Ok(file) => {
            let _ = SINK.set(Mutex::new(Sink { path, file }));
        }
        Err(e) => {
            eprintln!(
                "curfew: cannot open the log file {} ({e}); diagnostics go to stderr only",
                path.display()
            );
        }
    }
}

/// Whether a sink is installed. Used by the tests, and by nothing in the service.
#[cfg(test)]
pub fn installed() -> bool {
    SINK.get().is_some()
}

/// Write one line. This is what the macros call; call them instead.
pub fn line(level: &str, args: fmt::Arguments<'_>) {
    // A timestamp the *machine* claims, which is the right thing for a log line and the wrong thing
    // for an enforcement decision. `runner::wall_now` carries the same warning for the same reason.
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let text = format!("{now} {level} {args}");

    // Always, so a console run is readable. For the installed service this is a no-op by design.
    eprintln!("curfew: {args}");

    let Some(sink) = SINK.get() else { return };
    // A poisoned lock means another thread panicked while logging. The log is not worth a second
    // panic, so the line is dropped and the service carries on.
    let Ok(mut sink) = sink.lock() else { return };

    // Roll before writing, not after: after would mean a single line can push the file past the cap
    // and be immediately lost by the roll that follows it.
    if sink.file.metadata().map(|m| m.len()).unwrap_or(0) >= MAX_BYTES {
        let _ = sink.file.flush();
        let _ = std::fs::rename(&sink.path, rolled(&sink.path));
        if let Ok(fresh) = OpenOptions::new().create(true).append(true).open(&sink.path) {
            sink.file = fresh;
        }
    }

    let _ = writeln!(sink.file, "{text}");
    // Flushed per line: a service that is killed — which is exactly the situation these lines exist
    // to explain — must not lose the last few in a buffer.
    let _ = sink.file.flush();
}

/// A diagnostic. Replaces `eprintln!` everywhere in this crate.
#[macro_export]
macro_rules! note {
    ($($arg:tt)*) => {
        $crate::logging::line("INFO", format_args!($($arg)*))
    };
}

/// Something that went wrong but did not stop anything.
#[macro_export]
macro_rules! warn {
    ($($arg:tt)*) => {
        $crate::logging::line("WARN", format_args!($($arg)*))
    };
}

/// Something that stopped working, or a promise that is not being kept.
#[macro_export]
macro_rules! error {
    ($($arg:tt)*) => {
        $crate::logging::line("ERROR", format_args!($($arg)*))
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The path is derived from `ProgramData`, which is where the service's other files live and what
    /// the installer ACLs. `state::default_path` does the same, and the two must stay in one folder.
    #[test]
    fn the_log_sits_beside_the_state_file() {
        let log = default_path();
        let state = curfew_win::state::default_path();
        assert_eq!(log.parent(), state.parent(), "the log escaped the service's own folder");
        assert_eq!(log.file_name().unwrap(), "curfew.log");
    }

    /// Rolling renames the file and starts a fresh one, so the cap is on the *current* file rather
    /// than on the total — which is why the worst case is twice the cap, not unbounded.
    #[test]
    fn a_full_log_is_rolled_and_not_truncated_in_place() {
        let dir = std::env::temp_dir().join(format!("curfew-log-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("curfew.log");

        // Fill over the cap directly, rather than by logging two megabytes.
        std::fs::write(&path, vec![b'x'; MAX_BYTES as usize + 1]).unwrap();
        let mut sink = Sink {
            path: path.clone(),
            file: OpenOptions::new().create(true).append(true).open(&path).unwrap(),
        };

        // The same roll the logger performs.
        let _ = sink.file.flush();
        std::fs::rename(&sink.path, rolled(&sink.path)).unwrap();
        sink.file = OpenOptions::new().create(true).append(true).open(&sink.path).unwrap();
        writeln!(sink.file, "after the roll").unwrap();

        assert!(rolled(&path).is_file(), "the old log was not kept");
        assert_eq!(std::fs::read_to_string(&path).unwrap().trim(), "after the roll");
        assert_eq!(
            std::fs::metadata(rolled(&path)).unwrap().len(),
            MAX_BYTES + 1,
            "the rolled file was altered"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// **The property the finding is about**: a line handed to the sink reaches the file.
    ///
    /// Not a test of `format!` or of `File::write` — those are std's. It is a test that the *wiring*
    /// exists, because the failure P1-12 describes is a service whose diagnostics went nowhere and
    /// said nothing about it.
    #[test]
    fn a_line_written_reaches_the_file() {
        // Its own process-wide sink, so this cannot race another test that installs one. The sink is a
        // `OnceLock`, so whichever installs first wins for the whole binary — which is why this asserts
        // through the *installed* path rather than assuming it owns the sink.
        let dir = std::env::temp_dir().join(format!("curfew-log-sink-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("curfew.log");
        install(path.clone());

        line("INFO", format_args!("a line from the test"));
        line("WARN", format_args!("and a warning"));

        // **Unconditional, and that matters.** An earlier version wrapped this in `if installed()`,
        // which made the whole test vacuous the moment installation failed — it would pass by not
        // checking anything, which is the same shape of error as the guards this branch has already
        // had to repair twice.
        assert!(installed(), "installing a sink in a writable temp directory failed");

        let text = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("nothing was written to {}: {e}", path.display()));
        assert!(text.contains("a line from the test"), "the line did not reach the file: {text:?}");
        assert!(text.contains("WARN"), "the level was not recorded: {text:?}");
        assert!(text.contains("INFO"), "the level was not recorded: {text:?}");

        let _ = std::fs::remove_dir_all(&dir);
    }
}
