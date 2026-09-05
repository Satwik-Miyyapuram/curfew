//! `curfew` — the Windows service and the command line that drives it.
//!
//! One binary plays three parts: the service itself, the client that talks to it, and the installer
//! that registers it. They share a binary because the service must be able to point the service
//! control manager at an executable that will still be there, and because an uninstaller that is a
//! separate program is an uninstaller that can be run when the service is not looking.

mod feeds;
mod host;
mod runner;
mod service;
#[cfg(windows)]
mod watchdog;

use curfew_win::ipc::{Request, Response};
use curfew_win::{hosts, state};
use std::collections::BTreeSet;

/// The service the watchdog looks after. Named here rather than imported from `service`, which is
/// Windows-only, so the watchdog subcommand still compiles everywhere.
const WATCHED: &str = "Curfew";

const USAGE: &str = "\
curfew — distraction blocking that keeps its promises

  curfew status                    what is running, what is blocked
  curfew start <profile> <minutes> [--credential]
  curfew end <id>                  end a session (refused if it is locked)
  curfew release <id>              start the 24-hour delayed release
  curfew emergency <id>            spend an emergency pass, if the ration allows one
  curfew cancel                    call off a freeze that is counting down
  curfew confirm                   agree to a freeze another device asked for
  curfew reload                    re-read the config file
  curfew run                       run the loop in this console (for debugging)

  curfew check <config.toml>       validate a config and list its profiles
  curfew migrate <config.toml>     print it migrated to the current schema
  curfew decide <config.toml> <profile> <target> [options]
                                   ask the engine what it would do, with no service running

  curfew extension <browser> <id>  let a browser talk to Curfew (chrome, edge, brave,
                                   vivaldi, chromium, firefox, librewolf)
  curfew extension-host            spoken by the browser, not by people

  curfew install                   register the Windows service (needs an admin prompt)
  curfew uninstall                 remove it (refused while a lock is held)
";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = args.first().map(String::as_str).unwrap_or("status");
    // The config subcommands need no service and no privileges, so they are answered here before
    // anything tries to open the pipe: `curfew check` has to work on a machine where Curfew was
    // never installed, and on Linux, where there is no service to talk to at all.
    if curfew_cli::handles(command) {
        let refs: Vec<&str> = args.iter().map(String::as_str).collect();
        std::process::exit(curfew_cli::run(&refs));
    }

    let code = match command {
        "status" => status(),
        "cancel" => simple(Request::CancelFreeze),
        "confirm" => simple(Request::ConfirmFreeze),
        "start" => start(&args[1..]),
        "end" => end(&args[1..]),
        "release" => release(&args[1..]),
        "emergency" => emergency(&args[1..]),
        "reload" => simple(Request::Reload),
        "run" => run_in_console(),
        "watchdog" => {
            watchdog::run(WATCHED, &state::default_path());
            0
        }
        "service" => service_entry(),
        "extension-host" => host::run(),
        "extension" => extension(&args[1..]),
        "install" => install(),
        "uninstall" => uninstall(),
        "help" | "--help" | "-h" => {
            println!("{USAGE}");
            0
        }
        other => {
            eprintln!("curfew: no such command: {other}\n\n{USAGE}");
            2
        }
    };
    std::process::exit(code);
}

/// Register the native-messaging host so one browser's extension can reach the service.
///
/// Per-user, and no elevation: this writes under `HKCU` and into the user's own profile, because a
/// machine-wide registration would reach into every account on a shared PC. Enforcement is not what
/// is being installed here — the service already does that — so nothing here needs to be privileged.
fn extension(args: &[String]) -> i32 {
    use curfew_win::extension::{manifest, Browser};

    let (Some(name), Some(id)) = (args.first(), args.get(1)) else {
        eprintln!(
            "usage: curfew extension <browser> <extension-id>

             The id is shown on the browser's extensions page. Chromium browsers give a long 
             lowercase id; Firefox gives an address like curfew@curfew.dev."
        );
        return 2;
    };
    let Some(browser) = Browser::parse(name) else {
        eprintln!("curfew: I do not know how to register with {name}.");
        return 2;
    };

    let Ok(exe) = std::env::current_exe() else {
        eprintln!("curfew: could not work out where this program lives.");
        return 1;
    };
    let directory = state::default_path().with_file_name("hosts");
    if let Err(e) = std::fs::create_dir_all(&directory) {
        eprintln!("curfew: could not create {}: {e}", directory.display());
        return 1;
    }
    let path = directory.join(browser.manifest_file());
    if let Err(e) = std::fs::write(&path, manifest(browser.family(), &exe, id)) {
        eprintln!("curfew: could not write {}: {e}", path.display());
        return 1;
    }

    // `reg add` rather than a registry crate: one process, one obvious command, and a failure the
    // user can reproduce by hand from the message.
    let key = browser.registry_key();
    let status = std::process::Command::new("reg")
        .args(["add", &key, "/ve", "/t", "REG_SZ", "/d", &path.display().to_string(), "/f"])
        .status();
    match status {
        Ok(status) if status.success() => {
            println!(
                "{name} can now talk to Curfew.
                 Load the extension from the `extension/` folder, then restart the browser.
                 While a URL rule is running, a browser with no extension answering for it is 
                 closed — so install it in every browser you use."
            );
            0
        }
        Ok(status) => {
            eprintln!("curfew: reg add {key} failed ({status}).");
            1
        }
        Err(e) => {
            eprintln!("curfew: could not run reg ({e}).");
            1
        }
    }
}

/// Ask the service, and turn "the service is not running" into the sentence that actually helps.
fn ask(request: Request) -> Result<Response, i32> {
    runner::ask(&request).map_err(|e| {
        eprintln!(
            "curfew: could not reach the Curfew service ({e}).\n\
             It may not be installed yet — `curfew install` registers it — or it may have been \
             stopped, in which case starting it again is the fix: `sc start Curfew`."
        );
        1
    })
}

/// A timestamp as a person reads it, in the machine's own time zone.
///
/// Epoch seconds are what the wire carries and what the tests assert on, but a release time is
/// something a user has to plan around, so it is never printed as a number.
fn when(ts: curfew_core::Timestamp) -> String {
    use chrono::TimeZone as _;
    match chrono::Local.timestamp_opt(ts, 0).single() {
        Some(local) => local.format("%a %-d %b, %H:%M").to_string(),
        None => ts.to_string(),
    }
}

fn report(response: Response) -> i32 {
    match response {
        Response::Ok => 0,
        Response::Release { at } => {
            println!("Release starts now and lands at {}. It cannot be brought forward.", when(at));
            0
        }
        Response::Announced { countdown } => {
            // Not "started": the difference matters, because the caller has a minute in which to
            // change its mind and the exit code says the command succeeded either way.
            println!(
                "{} freezes this whole device at {}. Save your work. `curfew cancel` calls it off.",
                countdown.profile,
                when(countdown.fires_at)
            );
            0
        }
        // Only the extension asks for one of these, and it never comes through this path.
        Response::Verdict { blocked, reason } => {
            println!(
                "{}",
                if blocked {
                    reason.unwrap_or_else(|| "Blocked.".into())
                } else {
                    "Allowed.".into()
                }
            );
            0
        }
        Response::Refused { refusal } => {
            println!("{}", describe(&refusal));
            1
        }
        Response::Error { detail } => {
            eprintln!("curfew: {detail}");
            1
        }
        Response::NoPass { refusal } => {
            println!("{}", describe_pass(&refusal));
            1
        }
        Response::Status(_) => 0,
    }
}

/// Why no emergency pass was available, said as the reason rather than the enum.
///
/// The wording matters more here than anywhere else in the CLI: this is the message someone reads
/// at the exact moment they most want a way out, so it says plainly that there is one, when, and
/// that waiting is the whole mechanism rather than a bug.
fn describe_pass(refusal: &curfew_core::PassRefusal) -> String {
    use curfew_core::PassRefusal;
    match refusal {
        PassRefusal::Disabled => "Emergency passes are switched off. Turn them on in curfew.toml              under [emergency], and they will be available from then on — not retroactively."
            .into(),
        PassRefusal::QuotaSpent { next_at } => format!(
            "No emergency passes left. The next one becomes available at {}.",
            when(*next_at)
        ),
        PassRefusal::CoolingDown { until } => format!(
            "A pass was used recently. The next one can be spent at {}.",
            when(*until)
        ),
    }
}

/// A refusal in the words of the thing that was refused, not in the words of the enum.
fn describe(refusal: &curfew_core::Refusal) -> String {
    use curfew_core::Refusal;
    match refusal {
        Refusal::NotRunning => "There is no session with that id.".to_string(),
        Refusal::Locked { missing, ends_at, delayed_release_at } => {
            let mut text = if missing.is_empty() {
                "This session is locked until its time is up.".to_string()
            } else {
                format!(
                    "This session is locked. Still to satisfy: {}.",
                    missing.iter().map(name_of).collect::<Vec<_>>().join(", ")
                )
            };
            if let Some(ends) = ends_at {
                text.push_str(&format!(" It ends on its own at {}.", when(*ends)));
            }
            match delayed_release_at {
                Some(at) => text.push_str(&format!(
                    " A delayed release is already running and lands at {}.",
                    when(*at)
                )),
                None => text.push_str(" `curfew release <id>` starts the 24-hour release."),
            }
            text
        }
    }
}

fn name_of(lock: &curfew_core::Lock) -> String {
    use curfew_core::Lock;
    match lock {
        Lock::Timer => "the timer".into(),
        Lock::Confirm => "a confirmation".into(),
        Lock::DeviceCredential => "your Windows password".into(),
        Lock::Challenge { .. } => "the challenge".into(),
        Lock::PeerRelease { device_id } => format!("a release from {device_id}"),
        Lock::Token { id } => format!("the token {id}"),
        Lock::RestartRequired => "a restart".into(),
    }
}

fn status() -> i32 {
    let Ok(response) = ask(Request::Status) else { return 1 };
    let Response::Status(status) = response else { return report(response) };

    if status.running.is_empty() {
        println!("Nothing is running. Curfew is watching.");
    }
    for session in &status.running {
        let remaining = session.lock.ends_at.map(|e| (e - status.now).max(0) / 60);
        match remaining {
            Some(minutes) => println!("{}  {}  {minutes} min left", session.id, session.profile),
            None => println!("{}  {}  until released", session.id, session.profile),
        }
        if let Some(at) = session.lock.delayed_release_at {
            println!("    delayed release lands at {}", when(at));
        }
    }
    if !status.blocked_domains.is_empty() {
        println!(
            "\nBlocked: {}",
            status.blocked_domains.iter().cloned().collect::<Vec<_>>().join(", ")
        );
    }
    // Failures are printed after the good news, never instead of it, and never suppressed: a
    // blocker that quietly fails to block is worse than one that admits it.
    for exe in &status.failing {
        println!("\nCould not close {exe}. It is running with privileges Curfew does not have.");
    }
    if let Some(e) = &status.hosts_error {
        println!("\nWebsite blocking is not working: {e}");
    }
    if let Some(warning) = &status.state_warning {
        println!("\n{warning}");
    }
    0
}

fn start(args: &[String]) -> i32 {
    let (Some(profile), Some(minutes)) = (args.first(), args.get(1)) else {
        eprintln!("curfew: usage: curfew start <profile> <minutes> [--credential]");
        return 2;
    };
    let Ok(minutes) = minutes.parse::<u32>() else {
        eprintln!("curfew: {minutes} is not a number of minutes");
        return 2;
    };
    let locks = if args.iter().any(|a| a == "--credential") {
        vec![curfew_core::Lock::DeviceCredential]
    } else {
        vec![]
    };
    // Said out loud before it starts, because after it starts is too late: this is the whole point
    // of a commitment device, and a user surprised by it is a user who was not warned properly.
    if !locks.is_empty() {
        println!(
            "Starting a locked session for {minutes} minutes. It will need your Windows password \
             to end early, and if you cannot use it, the 24-hour release is the way out."
        );
    }
    match ask(Request::Start { profile: profile.clone(), seconds: minutes * 60, locks }) {
        Ok(response) => report(response),
        Err(code) => code,
    }
}

fn end(args: &[String]) -> i32 {
    let Some(id) = args.first() else {
        eprintln!("curfew: usage: curfew end <id>");
        return 2;
    };
    // Nothing is claimed as satisfied here. Credential checks belong to the tray, which can show
    // the operating system's own prompt; a command line that could assert "the password was typed"
    // would be a hole, so it does not get to.
    match ask(Request::End { id: id.clone(), satisfied: BTreeSet::new() }) {
        Ok(response) => report(response),
        Err(code) => code,
    }
}

/// Spend an emergency pass on one session.
///
/// Deliberately not called `end --force`: it is scarce, it is recorded, and it is shared with every
/// paired device. Naming it after what it costs is the honest way to offer it.
fn emergency(args: &[String]) -> i32 {
    let Some(id) = args.first() else {
        eprintln!("curfew: usage: curfew emergency <id>");
        return 2;
    };
    match ask(Request::Emergency { id: id.clone() }) {
        Ok(response) => report(response),
        Err(code) => code,
    }
}

fn release(args: &[String]) -> i32 {
    let Some(id) = args.first() else {
        eprintln!("curfew: usage: curfew release <id>");
        return 2;
    };
    match ask(Request::RequestRelease { id: id.clone() }) {
        Ok(response) => report(response),
        Err(code) => code,
    }
}

fn simple(request: Request) -> i32 {
    match ask(request) {
        Ok(response) => report(response),
        Err(code) => code,
    }
}

/// Run the loop in the foreground. Useful for debugging, and the only way to run Curfew at all on a
/// machine where the service cannot be installed.
fn run_in_console() -> i32 {
    let enforcer =
        match runner::build(&runner::config_path(), &state::default_path(), runner::hosts_path()) {
            Ok(enforcer) => enforcer,
            Err(detail) => {
                eprintln!("curfew: {detail}");
                return 1;
            }
        };
    println!("Curfew is enforcing. Ctrl-C to stop.");
    runner::run(enforcer, state::default_path(), || false, None, false);
    0
}

#[cfg(windows)]
fn service_entry() -> i32 {
    match service::start_dispatch() {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("curfew: not started by the service control manager: {e}");
            1
        }
    }
}

#[cfg(not(windows))]
fn service_entry() -> i32 {
    eprintln!("curfew: services are a Windows thing; use `curfew run`");
    1
}

#[cfg(windows)]
fn install() -> i32 {
    match service::install() {
        Ok(()) => {
            println!("Curfew is installed and running. `curfew status` will now answer.");
            0
        }
        Err(e) => {
            eprintln!(
                "curfew: could not install the service ({e}).\n\
                 This needs an administrator prompt: run the same command from a terminal opened \
                 with \"Run as administrator\"."
            );
            1
        }
    }
}

#[cfg(windows)]
fn uninstall() -> i32 {
    // The refusal that makes uninstalling not be a bypass. It is checked against the service's own
    // state rather than a flag on disk, and the delayed release still works while it stands — so
    // this is friction with an exit, not a trap (GAPS D1).
    match runner::ask(&Request::Status) {
        Ok(Response::Status(status)) if !status.running.is_empty() => {
            println!(
                "Curfew is not going to uninstall itself while a lock is running — that is what \
                 you asked it for.\n\
                 The session ends on its own, or `curfew release <id>` starts the 24-hour release, \
                 after which this will work."
            );
            return 1;
        }
        _ => {}
    }
    match service::uninstall() {
        Ok(()) => {
            let _ = hosts::clear(&runner::hosts_path());
            // And the resolvers, from whatever the service left behind. An uninstall that leaves a
            // machine pointing at a proxy that is gone has broken the internet on its way out.
            curfew_win::dns::give_back_remembered(&runner::dns_record());
            println!("Curfew is uninstalled and the hosts file has been given back.");
            0
        }
        Err(e) => {
            eprintln!("curfew: could not remove the service ({e}). Try an administrator prompt.");
            1
        }
    }
}

#[cfg(not(windows))]
fn install() -> i32 {
    eprintln!("curfew: services are a Windows thing");
    1
}

#[cfg(not(windows))]
fn uninstall() -> i32 {
    eprintln!("curfew: services are a Windows thing");
    1
}
