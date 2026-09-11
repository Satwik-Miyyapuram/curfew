//! The second process, and the only reason killing the first one is not a bypass.
//!
//! The service control manager already restarts the service after a failure, but it does so on the
//! manager's terms: three restarts, then it gives up, and a stop issued by an administrator is not
//! a failure at all. So Curfew runs a small second process whose only job is to notice that the
//! service is not running while a lock is held, and to start it again.
//!
//! Two things keep this honest rather than malicious. The watchdog reads the state file to decide,
//! so it restarts the service *only* while a lock is actually held — with nothing running it exits
//! and leaves the machine alone. And it never fights an uninstall: a service that no longer exists
//! means Curfew was removed the supported way, and the watchdog stops.

use curfew_win::state::{self, Loaded};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// How often the watchdog looks. Slower than the enforcement tick on purpose: this process exists
/// for the seconds after a kill, and polling the service control manager is not free.
pub const POLL: Duration = Duration::from_secs(3);

/// What the watchdog can see about the service.
///
/// Off Windows `sys::look` can only answer `Gone`, so in a build with the tests compiled out
/// nothing constructs the other two. They are still the shape of the decision — the tests
/// below walk every pair — so the variants stay and the lint is told why.
#[cfg_attr(not(windows), allow(dead_code))]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Seen {
    /// The service exists and is running. Nothing to do.
    Running,
    /// The service exists but is not running.
    Stopped,
    /// The service is not registered any more.
    Gone,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Wait,
    Start,
    /// Stop watching and exit.
    Retire,
}

/// The whole decision, with no clock, no registry and no processes in it, so that every branch can
/// be stated as a test.
pub fn decide(seen: Seen, locks_running: bool) -> Action {
    match (seen, locks_running) {
        // Removed through the supported path, which already refuses while a lock is held. Nothing
        // left to protect, and a watchdog that outlived its service would be a haunting.
        (Seen::Gone, _) => Action::Retire,
        (Seen::Running, _) => Action::Wait,
        // The case this process exists for.
        (Seen::Stopped, true) => Action::Start,
        // Stopped with nothing running is an ordinary stop. Curfew does not insist on running when
        // it has nothing to enforce; the service starts itself again at the next boot.
        (Seen::Stopped, false) => Action::Retire,
    }
}

/// Whether the last saved state still has a lock in it.
///
/// An unreadable state file counts as "yes". The alternative is that corrupting a file releases a
/// lock, and every uncertain case here is resolved in favour of the promise the user made.
pub fn locks_running(state_path: &Path) -> bool {
    match state::load(state_path) {
        Loaded::Ok(state) | Loaded::Recovered { state, .. } => !state.sessions.running.is_empty(),
        Loaded::Fresh => false,
        Loaded::Lost { .. } => true,
    }
}

#[cfg(windows)]
mod sys {
    use super::Seen;
    use windows_service::service::{ServiceAccess, ServiceState};
    use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};

    pub fn look(name: &str) -> Seen {
        let Ok(manager) =
            ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)
        else {
            // The manager itself is unreachable, which is not the service being gone. Waiting is the
            // conservative answer: it neither releases a lock nor starts a fight.
            return Seen::Running;
        };
        let access = ServiceAccess::QUERY_STATUS | ServiceAccess::START;
        let Ok(service) = manager.open_service(name, access) else {
            return Seen::Gone;
        };
        match service.query_status() {
            Ok(status) if status.current_state == ServiceState::Running => Seen::Running,
            Ok(_) => Seen::Stopped,
            Err(_) => Seen::Running,
        }
    }

    pub fn start(name: &str) -> Result<(), String> {
        let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)
            .map_err(|e| e.to_string())?;
        let service = manager
            .open_service(name, ServiceAccess::START | ServiceAccess::QUERY_STATUS)
            .map_err(|e| e.to_string())?;
        service.start::<&str>(&[]).map_err(|e| e.to_string())
    }
}

#[cfg(not(windows))]
mod sys {
    use super::Seen;

    pub fn look(_name: &str) -> Seen {
        Seen::Gone
    }

    pub fn start(_name: &str) -> Result<(), String> {
        Err("services are a Windows thing".to_string())
    }
}

/// Watch until there is nothing left to watch.
pub fn run(name: &str, state_path: &Path) {
    loop {
        match decide(sys::look(name), locks_running(state_path)) {
            Action::Wait => {}
            Action::Start => {
                if let Err(e) = sys::start(name) {
                    // Worth saying and not worth stopping for: the next pass tries again, and the
                    // usual cause is the manager being busy stopping the service we are starting.
                    eprintln!("curfew: watchdog could not start the service: {e}");
                }
            }
            Action::Retire => return,
        }
        std::thread::sleep(POLL);
    }
}

/// Where the watchdog runs from: its own copy, beside the state file rather than in the install
/// directory.
///
/// A running process holds its own image open, and the watchdog's image used to be the same
/// `curfew.exe` the service runs from. That made every upgrade a file the installer could not
/// replace: Windows decides whether a reboot is needed before it stops anything, sees a second
/// process holding the binary, and asks the user to restart their machine to install a program.
/// Restart Manager cannot help there either, because the watchdog is a plain process and not a
/// service, so there is nothing for it to stop and start again.
///
/// `%ProgramData%\Curfew` is administrator-owned, the same as the config and the state file beside
/// it, so running from here is no easier to tamper with than running from Program Files.
pub fn image_path() -> PathBuf {
    state::default_path().with_file_name("curfew-watchdog.exe")
}

/// Start the watchdog as a detached child of the service.
///
/// Failure is reported and not fatal. A Curfew with no watchdog still enforces everything it was
/// asked to; it is just easier to interrupt, and refusing to run at all would be a worse trade.
pub fn spawn() -> std::io::Result<std::process::Child> {
    let exe = std::env::current_exe()?;
    // The image lives beside the state file so the installer has one file to replace instead of
    // two. `refresh` decides whether it is genuinely this build; a `false` is not a failure to
    // report but a reason to run from `exe`, which is the only path whose contents are not in
    // question — so an image that cannot be trusted is never the thing that gets spawned.
    let image = image_path();
    let from = if refresh(&exe, &image) { image } else { exe };
    std::process::Command::new(from).arg("watchdog").spawn()
}

/// Make sure [`to`] holds a copy of this build, and say whether it is safe to run from.
///
/// **This decides whether a SYSTEM process executes a file, so it compares contents, not metadata.**
/// It used to compare length and modification time, which is an attacker-controlled pair: the
/// directory inherits `BUILTIN\Users: Write` from `C:\ProgramData`, `curfew-watchdog.exe` does not
/// exist until the service first runs, and a user who creates it first — padded to the length of
/// `curfew.exe` with a newer timestamp — makes the old check succeed, so the copy was skipped and
/// the service spawned their binary as SYSTEM. Reading both files and comparing bytes removes the
/// guesswork: a difference is a difference, whatever the metadata says.
///
/// `false` means "do not run from here". The caller falls back to this process's own executable,
/// which is the one file we know the contents of by definition. That is a deliberate trade: running
/// the watchdog from `curfew.exe` can make the installer want a reboot (the reason this image
/// exists at all), and a reboot request is a far better outcome than executing an unverified binary
/// as SYSTEM.
fn refresh(from: &Path, to: &Path) -> bool {
    if let Some(parent) = to.parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            eprintln!("curfew: watchdog image directory unavailable: {e}");
            return false;
        }
    }
    let current = match std::fs::read(from) {
        Ok(bytes) => bytes,
        Err(e) => {
            eprintln!("curfew: could not read this build to check the watchdog image: {e}");
            return false;
        }
    };
    // Identical bytes are the same file, so a running watchdog holding it open costs nothing.
    if std::fs::read(to).is_ok_and(|existing| existing == current) {
        return true;
    }
    match std::fs::write(to, &current) {
        Ok(()) => true,
        Err(e) => {
            // Most often a previous watchdog still running from this path. Reported rather than
            // fatal, but it does mean the image is not this build and must not be spawned.
            eprintln!("curfew: watchdog image could not be replaced ({e})");
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_service_killed_while_a_lock_is_held_is_started_again() {
        assert_eq!(decide(Seen::Stopped, true), Action::Start);
    }

    #[test]
    fn a_service_stopped_with_nothing_running_is_left_alone() {
        assert_eq!(decide(Seen::Stopped, false), Action::Retire);
    }

    #[test]
    fn an_uninstalled_service_is_never_started_again() {
        // Uninstalling already refuses while a lock is held, so reaching here with locks means the
        // user got out the supported way. Restarting a deleted service would make removal
        // impossible, which is the line between a commitment device and malware.
        assert_eq!(decide(Seen::Gone, true), Action::Retire);
        assert_eq!(decide(Seen::Gone, false), Action::Retire);
    }

    #[test]
    fn a_running_service_is_not_touched() {
        assert_eq!(decide(Seen::Running, true), Action::Wait);
        assert_eq!(decide(Seen::Running, false), Action::Wait);
    }

    #[test]
    fn an_unreadable_state_file_is_treated_as_a_lock_still_being_held() {
        let dir = std::env::temp_dir().join(format!("curfew-wd-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("state.json");
        std::fs::write(&path, "{ truncated").unwrap();
        std::fs::write(path.with_extension("bak"), "also truncated").unwrap();

        assert!(locks_running(&path), "damaging the state file switched the watchdog off");
    }

    #[test]
    fn a_first_run_has_nothing_to_watch() {
        let dir = std::env::temp_dir().join(format!("curfew-wd-fresh-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        assert!(!locks_running(&dir.join("nothing.json")));
    }

    /// A scratch directory per test, so parallel tests do not fight over one path.
    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("curfew-wd-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_planted_image_is_replaced_even_when_its_size_and_time_agree() {
        // The exploit this replaced a metadata check for. `%ProgramData%\Curfew` inherits
        // `BUILTIN\Users: Write`, and `curfew-watchdog.exe` does not exist until the service first
        // runs — so a user can create it first, padded to the same length as `curfew.exe` with a
        // newer timestamp. The old check compared exactly those two things, decided the copy was
        // unnecessary, and the service spawned the attacker's binary as SYSTEM.
        let dir = scratch("planted");
        let from = dir.join("curfew.exe");
        let to = dir.join("curfew-watchdog.exe");

        // Same length by construction, different content, and the name says which is which.
        std::fs::write(&from, b"REAL-BUILD-16byt").unwrap();
        std::fs::write(&to, b"EVIL-IMAGE-16byt").unwrap();
        assert_eq!(std::fs::metadata(&from).unwrap().len(), std::fs::metadata(&to).unwrap().len());

        // …and newer than the real build, which is the other half of the old check.
        let future = std::time::SystemTime::now() + std::time::Duration::from_secs(3600);
        std::fs::File::options().write(true).open(&to).unwrap().set_modified(future).unwrap();

        assert!(refresh(&from, &to), "a replaceable image was reported as unusable");
        assert_eq!(
            std::fs::read(&to).unwrap(),
            std::fs::read(&from).unwrap(),
            "the planted image survived: a SYSTEM process would have run it"
        );
    }

    #[test]
    fn an_identical_image_is_left_alone() {
        // The common case, and the reason content comparison is affordable: the bytes are read
        // either way, but nothing is written, so a watchdog currently running from this path does
        // not block the service from starting.
        let dir = scratch("identical");
        let from = dir.join("curfew.exe");
        let to = dir.join("curfew-watchdog.exe");
        std::fs::write(&from, b"same bytes").unwrap();
        std::fs::write(&to, b"same bytes").unwrap();

        assert!(refresh(&from, &to));
        assert_eq!(std::fs::read(&to).unwrap(), b"same bytes");
    }

    #[test]
    fn a_missing_image_is_created_from_this_build() {
        let dir = scratch("missing");
        let from = dir.join("curfew.exe");
        let to = dir.join("nested").join("curfew-watchdog.exe");
        std::fs::write(&from, b"the real build").unwrap();

        assert!(refresh(&from, &to), "a first run could not stage the watchdog");
        assert_eq!(std::fs::read(&to).unwrap(), b"the real build");
    }

    #[test]
    fn an_image_that_cannot_be_verified_is_not_vouched_for() {
        // What the caller does with `false` is fall back to its own executable. The point here is
        // that a build we cannot read is never reported as safe to run.
        let dir = scratch("unreadable");
        let from = dir.join("curfew.exe");
        let to = dir.join("curfew-watchdog.exe");

        assert!(!refresh(&from, &to), "an unreadable build was reported as safe to run");
    }
}
