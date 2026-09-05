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
use std::path::Path;
use std::time::Duration;

/// How often the watchdog looks. Slower than the enforcement tick on purpose: this process exists
/// for the seconds after a kill, and polling the service control manager is not free.
pub const POLL: Duration = Duration::from_secs(3);

/// What the watchdog can see about the service.
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

/// Start the watchdog as a detached child of the service.
///
/// Failure is reported and not fatal. A Curfew with no watchdog still enforces everything it was
/// asked to; it is just easier to interrupt, and refusing to run at all would be a worse trade.
pub fn spawn() -> std::io::Result<std::process::Child> {
    let exe = std::env::current_exe()?;
    std::process::Command::new(exe).arg("watchdog").spawn()
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
}
