//! Registering with the service control manager, and answering it.
//!
//! Two things here are load-bearing rather than boilerplate. The service is configured to restart
//! itself after a failure, so `taskkill` buys a few seconds rather than an evening. And it refuses
//! to stop while a lock is held — the control manager is told the stop failed, which is true, and
//! the enforcement loop carries on.

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

    let status_handle = windows_service::service_control_handler::register(NAME, move |control| {
        match control {
            ServiceControl::Interrogate => {
                windows_service::service_control_handler::ServiceControlHandlerResult::NoError
            }
            ServiceControl::Stop | ServiceControl::Shutdown => {
                // A shutdown is the machine going away and is always obeyed; the state file is what
                // carries the lock across the reboot. A stop is a request, and while a lock is held
                // it is refused.
                asked_to_stop.store(true, Ordering::SeqCst);
                windows_service::service_control_handler::ServiceControlHandlerResult::NoError
            }
            _ => windows_service::service_control_handler::ServiceControlHandlerResult::NotImplemented,
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

    let service = manager.create_service(
        &info,
        ServiceAccess::CHANGE_CONFIG | ServiceAccess::START | ServiceAccess::QUERY_STATUS,
    )?;
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
    service.start::<&str>(&[])?;
    Ok(())
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
    service.delete()
}
