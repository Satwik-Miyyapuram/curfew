//! Registering with the service control manager, and answering it.
//!
//! Two things here are load-bearing rather than boilerplate. The service is configured to restart
//! itself after a failure, so `taskkill` buys a few seconds rather than an evening. And it refuses
//! to stop while a lock is held — the control manager is told the stop failed, which is true, and
//! the enforcement loop carries on.
//!
//! That second one used to be a comment describing behaviour the code did not have: the handler set
//! the loop's stop flag and returned `NoError` for *both* `Stop` and `Shutdown`, whatever was
//! running. `NoError` is acceptance, so the service went down on any stop request — and because the
//! installer stops the service before replacing its files, that also made the install-time stop
//! succeed, which in turn let the deferred `curfew.exe uninstall` run against a stopped service and
//! fail open. Stopping the service was therefore a complete way out of a lock, and it needed no
//! administrator: Task Manager's "End task" and the Services console both reach it.

use curfew_win::state;
use std::ffi::OsString;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use windows_service::service::{
    ServiceAccess, ServiceAction, ServiceActionType, ServiceControl, ServiceControlAccept,
    ServiceErrorControl, ServiceExitCode, ServiceFailureActions, ServiceFailureResetPeriod,
    ServiceInfo, ServiceStartType, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};

pub const NAME: &str = "Curfew";

/// `ERROR_SERVICE_CANNOT_ACCEPT_CTRL` (winerror.h, 1061).
///
/// What a control handler returns to *deny* a control rather than acknowledge it. Spelled out
/// because the `windows-sys` feature set this crate enables does not export it, and a wrong number
/// here would be reported by the SCM as some unrelated failure.
const ERROR_SERVICE_CANNOT_ACCEPT_CTRL: u32 = 1061;

windows_service::define_windows_service!(ffi_service_main, service_main);

pub fn start_dispatch() -> windows_service::Result<()> {
    windows_service::service_dispatcher::start(NAME, ffi_service_main)
}

fn service_main(_arguments: Vec<OsString>) {
    if let Err(e) = run() {
        eprintln!("curfew: service failed: {e}");
    }
}

fn run() -> windows_service::Result<()> {
    let stop = Arc::new(AtomicBool::new(false));
    let asked_to_stop = Arc::clone(&stop);
    // The control handler runs on a thread the SCM owns, so it cannot lock the enforcer or ask the
    // service anything through the pipe. It reads the same witness the watchdog reads — the state
    // file — which is the only channel available to it and the same one `curfew uninstall` uses.
    let state_path = state::default_path();

    let status_handle = windows_service::service_control_handler::register(NAME, move |control| {
        use windows_service::service_control_handler::ServiceControlHandlerResult;
        match control {
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            // A shutdown is the machine going away. It is always obeyed whatever is running: the
            // lock is carried across the reboot by the state file, and refusing would only mean the
            // machine hung on its way down. `Lock::RestartRequired` is satisfied by the reboot, and
            // every timer simply resumes against a clock that kept moving.
            ServiceControl::Shutdown => {
                asked_to_stop.store(true, Ordering::SeqCst);
                ServiceControlHandlerResult::NoError
            }
            // A stop is a request, and while a lock is held it is refused. `ERROR_SERVICE_CANNOT_
            // ACCEPT_CTRL` is what tells the SCM and the caller the request was denied, which is
            // what makes an installer's `StopServices` fail and roll the uninstall back rather than
            // replacing the binaries of a service that was holding a lock.
            //
            // An unreadable state file counts as locked — `locks_running` answers `true` for
            // `Loaded::Lost` — because "the state is gone" is exactly what someone deleting their
            // way out of a lock would arrange, and a refusal here costs an honest user one reboot.
            ServiceControl::Stop => {
                if crate::watchdog::locks_running(&state_path) {
                    ServiceControlHandlerResult::Other(ERROR_SERVICE_CANNOT_ACCEPT_CTRL)
                } else {
                    asked_to_stop.store(true, Ordering::SeqCst);
                    ServiceControlHandlerResult::NoError
                }
            }
            _ => ServiceControlHandlerResult::NotImplemented,
        }
    })?;

    let running = |state: ServiceState, accept: ServiceControlAccept| ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: state,
        controls_accepted: accept,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    };

    status_handle.set_service_status(running(
        ServiceState::Running,
        ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
    ))?;

    let state_path = state::default_path();
    match crate::runner::build(
        &crate::runner::config_path(),
        &state_path,
        crate::runner::hosts_path(),
    ) {
        Ok(enforcer) => {
            crate::runner::run(enforcer, state_path, || stop.load(Ordering::SeqCst), None, true);
        }
        Err(detail) => eprintln!("curfew: {detail}"),
    }

    status_handle
        .set_service_status(running(ServiceState::Stopped, ServiceControlAccept::empty()))?;
    Ok(())
}

fn manager(access: ServiceManagerAccess) -> windows_service::Result<ServiceManager> {
    ServiceManager::local_computer(None::<&str>, access)
}

pub fn install() -> windows_service::Result<()> {
    // Written here, while the install still has the administrator rights that %ProgramData% wants,
    // so the service's first start finds a config rather than stopping on a missing file.
    if let Err(e) = crate::runner::ensure_config(&crate::runner::config_path()) {
        eprintln!("curfew: could not write a starting config ({e}).");
    }
    let manager = manager(ServiceManagerAccess::CONNECT | ServiceManagerAccess::CREATE_SERVICE)?;
    let executable = std::env::current_exe().map_err(windows_service::Error::Winapi)?;

    let info = ServiceInfo {
        name: OsString::from(NAME),
        display_name: OsString::from("Curfew"),
        service_type: ServiceType::OWN_PROCESS,
        // Automatic, because a blocker that only runs when someone remembers to start it is not a
        // blocker. It runs as LocalSystem (`account_name: None`) because writing the hosts file and
        // closing another user's processes both need more than the logged-in user has.
        start_type: ServiceStartType::AutoStart,
        error_control: ServiceErrorControl::Normal,
        executable_path: executable,
        launch_arguments: vec![OsString::from("service")],
        dependencies: vec![],
        account_name: None,
        account_password: None,
    };

    let access = ServiceAccess::CHANGE_CONFIG | ServiceAccess::START | ServiceAccess::QUERY_STATUS;
    // Installing over an existing registration is the normal case, not the odd one: the installer
    // runs this on every upgrade, and plenty of machines registered the service by hand first.
    // Creating fails there, so the existing one is pointed at this binary instead. Anything else
    // would mean an upgrade that either refuses or leaves the service running the old exe.
    let service = match manager.create_service(&info, access) {
        Ok(service) => service,
        Err(e) if is_already_installed(&e) => {
            let service = manager.open_service(NAME, access | ServiceAccess::CHANGE_CONFIG)?;
            service.change_config(&info)?;
            service
        }
        Err(e) => return Err(e),
    };
    service.set_description(
        "Enforces the blocks you asked Curfew for, including across restarts. Curfew is \
         open source and sends nothing anywhere.",
    )?;
    // Killing the process is the first thing anyone tries. Restarting after one second, three
    // times, turns that into a few seconds of access rather than an evening of it — and the state
    // file means the restarted service picks up exactly the locks that were running.
    service.update_failure_actions(ServiceFailureActions {
        reset_period: ServiceFailureResetPeriod::After(Duration::from_secs(86400)),
        reboot_msg: None,
        command: None,
        actions: Some(vec![
            ServiceAction {
                action_type: ServiceActionType::Restart,
                delay: Duration::from_secs(1),
            },
            ServiceAction {
                action_type: ServiceActionType::Restart,
                delay: Duration::from_secs(1),
            },
            ServiceAction {
                action_type: ServiceActionType::Restart,
                delay: Duration::from_secs(5),
            },
        ]),
    })?;
    // Already running is the success case here, not a failure: a reinstall over a service that
    // never stopped has nothing left to do.
    match service.start::<&str>(&[]) {
        Ok(()) => {}
        Err(e) if is_already_running(&e) => {}
        Err(e) => return Err(e),
    }
    allow_through_firewall();
    Ok(())
}

/// ERROR_SERVICE_EXISTS.
fn is_already_installed(e: &windows_service::Error) -> bool {
    raw_error(e) == Some(1073)
}

/// ERROR_SERVICE_ALREADY_RUNNING.
fn is_already_running(e: &windows_service::Error) -> bool {
    raw_error(e) == Some(1056)
}

/// The Win32 code behind a service error, where there is one.
fn raw_error(e: &windows_service::Error) -> Option<i32> {
    match e {
        windows_service::Error::Winapi(io) => io.raw_os_error(),
        _ => None,
    }
}

/// The name both firewall rules carry, so uninstalling can find them again.
const FIREWALL_RULE: &str = "Curfew (device sync)";

/// Ask Windows Firewall for the sync port once, here, instead of once per boot forever.
///
/// Sync listens for the other devices on this machine's own network, and Windows answers a fresh
/// listening socket with a prompt — one that needs an administrator and appears again whenever the
/// service restarts, which for a service configured to restart itself is often. Installing already
/// holds the administrator token this needs, so the question is asked once, at the moment the user
/// has already said yes to installing a blocker.
///
/// Private and domain networks only. A blocker has no business accepting connections on the café
/// wifi, and the devices this talks to are the user's own.
///
/// Failure is not fatal and not silent: without the rule sync still works on the loopback and the
/// user gets the prompt they would have got anyway, which is worse but not broken.
fn allow_through_firewall() {
    let Ok(exe) = std::env::current_exe() else {
        return;
    };
    // Removed first so re-installing from a new folder does not leave a rule pointing at the old
    // one, which would be a rule allowing a program that is no longer there.
    revoke_firewall_rules();
    for protocol in ["tcp", "udp"] {
        let status = std::process::Command::new("netsh")
            .args([
                "advfirewall",
                "firewall",
                "add",
                "rule",
                &format!("name={FIREWALL_RULE}"),
                "dir=in",
                "action=allow",
                &format!("program={}", exe.display()),
                &format!("protocol={protocol}"),
                "profile=private,domain",
                "enable=yes",
            ])
            .status();
        if !matches!(status, Ok(s) if s.success()) {
            eprintln!(
                "curfew: could not add the firewall rule for {protocol}. Syncing with your other                  devices will still work, but Windows will ask about it each time."
            );
        }
    }
}

/// Take the rules back out. Called on uninstall, and before adding them, so they never accumulate.
fn revoke_firewall_rules() {
    let _ = std::process::Command::new("netsh")
        .args(["advfirewall", "firewall", "delete", "rule", &format!("name={FIREWALL_RULE}")])
        .status();
}

pub fn uninstall() -> windows_service::Result<()> {
    let manager = manager(ServiceManagerAccess::CONNECT)?;
    let service = manager.open_service(
        NAME,
        ServiceAccess::STOP | ServiceAccess::DELETE | ServiceAccess::QUERY_STATUS,
    )?;
    if service.query_status()?.current_state != ServiceState::Stopped {
        service.stop()?;
    }
    // Before the delete, because after it there is nothing left to be sure of: an uninstall that
    // leaves a firewall rule behind has left a hole named after a program that is gone.
    revoke_firewall_rules();
    service.delete()?;
    // The watchdog's own copy of this program. It retires by itself once the service is gone, and
    // it is holding this file open until it does, so the delete is attempted and its failure
    // ignored: the copy left behind runs nothing and the next install overwrites it.
    let _ = std::fs::remove_file(crate::watchdog::image_path());
    Ok(())
}
