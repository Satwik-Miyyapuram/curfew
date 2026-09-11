//! `curfew` — the Windows service and the command line that drives it.
//!
//! One binary plays three parts: the service itself, the client that talks to it, and the installer
//! that registers it. They share a binary because the service must be able to point the service
//! control manager at an executable that will still be there, and because an uninstaller that is a
//! separate program is an uninstaller that can be run when the service is not looking.

mod feeds;
mod host;
// Where diagnostics go. An SCM-started service has null standard handles, so before this every
// `eprintln!` in this crate was silently discarded (P1-12).
#[macro_use]
mod logging;
mod runner;
// The service control manager is Windows and nothing else, and every caller of `service` is
// already behind the same gate, so this costs no `cfg` at the call sites; without it a Linux
// build reaches for `windows-service`, which that build does not and cannot have. The watchdog
// is not in the same position: it carries its own non-Windows `sys` and is called from the
// enforcement loop, which is built everywhere.
#[cfg(windows)]
mod service;
mod watchdog;

use chrono::TimeZone as _;
use curfew_win::ipc::{Request, Response};
// `hosts` is given back only by the uninstaller, which exists on Windows alone.
#[cfg(windows)]
use curfew_win::hosts;
use curfew_win::state;
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
  curfew scan <id> <payload>       present a physical tag to end a session
  curfew peer-release <id>         release a session whose lock names this device
  curfew tag [payload]             print the config line for a tag (payload made up if omitted)
  curfew cancel                    call off a freeze that is counting down
  curfew confirm                   agree to a freeze another device asked for
  curfew reload                    re-read the config file
  curfew run                       run the loop in this console (for debugging)

  curfew check <config.toml>       validate a config and list its profiles
  curfew migrate <config.toml>     print it migrated to the current schema
  curfew decide <config.toml> <profile> <target> [options]
                                   ask the engine what it would do, with no service running

  curfew add-profile <config.toml> --id <id> [--name <text>] [--description <text>]
  curfew schedules <config.toml>   list profiles, windows, calendar rules and subscriptions
  curfew add-window <config.toml> --id <id> --profile <id> --from HH:MM --to HH:MM
  curfew add-calendar <config.toml> --id <id> --profile <id> [--title <glob>] ...
  curfew add-source <config.toml> --id <id> --from <file-or-url>
  curfew blocks <config.toml> [--profile <id>]
                                   list what each profile blocks
  curfew block <config.toml> --profile <id> --site <domain> | --exe <name> | --app <package>
                                   | --url <glob> | --title <glob> | --word <text> [--on <os>]
  curfew unblock <config.toml> --profile <id> <the same selector>
  curfew remove <config.toml> <id> remove a profile, window, rule or subscription
                                   (run any of these with no arguments for their flags)
  curfew upcoming <config.toml> [--hours <n>]
                                   what the hours ahead will block, and why (default 36)
  curfew stats <config.toml> [--days <n>] [--csv | --json]
                                   days blocked, streaks and totals

  curfew extension <browser> <id>  let a browser talk to Curfew (chrome, edge, brave,
                                   vivaldi, chromium, firefox, librewolf)
  curfew extension-host            spoken by the browser, not by people

  curfew install                   register the Windows service (needs an admin prompt)
  curfew uninstall                 remove it (refused while a lock is held)
";

/// The sentence refusing a removal, or `None` when there is nothing to refuse.
///
/// `None` covers both "no service is running" and "nothing is enforcing that schedule", and they are
/// deliberately not distinguished: neither is an error, and the caller does the same thing either way.
///
/// **Only a *running* session blocks a removal.** A schedule that is not currently enforcing anything can
/// be deleted freely — that is the ordinary edit, and refusing it would make the plan unmaintainable
/// without ending a lock first, which is exactly backwards.
fn removal_refusal(id: &str) -> Option<String> {
    let Response::Status(status) = curfew_win::ipc::ask(&Request::Status).ok()? else {
        return None;
    };
    let held = curfew_core::session::running_from(&status.running, id);
    let names: Vec<&str> = held.iter().map(|s| status.name_of(&s.profile)).collect();
    if names.is_empty() {
        return None;
    }
    Some(format!(
        "Not removed: {} is running under {id} right now, and a running session keeps its own copy of \
         what it blocks — so deleting the schedule would leave the lock in force and the plan no longer \
         explaining why.\n\n\
         End the session first (the lock's conditions decide how), or leave it: it stops on its own at \
         the end of its window, and the removal will go through then.\n\n\
         Nothing short of the 24-hour release shortens a lock that is already running.",
        names.join(", ")
    ))
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = args.first().map(String::as_str).unwrap_or("status");
    // The config subcommands need no service and no privileges, so they are answered here before
    // anything tries to open the pipe: `curfew check` has to work on a machine where Curfew was
    // never installed, and on Linux, where there is no service to talk to at all.
    if curfew_cli::handles(command) {
        let refs: Vec<&str> = args.iter().map(String::as_str).collect();
        // **A removal a running lock derives from is refused** — P2-11.
        //
        // The session keeps its own copy of what it blocks, so the lock is not weakened and this is not a
        // bypass: it is that the user is not told, and `README.md` says *"a lock is a promise — nothing
        // shortens it except the conditions you chose"*. Somebody who reads that and runs
        // `curfew remove <the window blocking me>` believes they have stopped it. The honest answer is the
        // one the uninstaller gives: not while it is holding you.
        //
        // Asked over the pipe, like the `Reload` below. A service that is not running makes this a no-op,
        // which is the config-first workflow the module doc describes — preparing a file to copy
        // elsewhere must not be blocked by a question nobody can answer.
        if let Some(id) = curfew_cli::removal_target(&refs) {
            if let Some(refusal) = removal_refusal(id) {
                eprintln!("{refusal}");
                std::process::exit(2);
            }
        }
        let code = curfew_cli::run(&refs);
        // A write the running service has not been told about is the worst failure this product has:
        // the file says the plan changed, the window reads the same file and shows the change, and
        // the service is still enforcing the old one. Nothing used to send `Reload` except a user
        // typing the verb by hand, so `curfew add-window …` printed "Added" and changed nothing that
        // was actually enforced.
        //
        // Best effort, and quiet about it: a machine with no service (a config being prepared to copy
        // elsewhere, or `curfew check` on a Linux box) is not an error case, and the edit stands
        // either way. The one case worth a sentence is a write to the file the service reads when
        // nothing answered — there the user is entitled to think it is already live.
        if code == 0 && curfew_cli::writes_config(command) {
            // Resolved before the attempt, because the sentence is only for a write to the file the
            // service actually reads — and comparing paths is the same work either way.
            let live = runner::config_path();
            let is_live =
                args.get(curfew_cli::CONFIG_ARG).is_some_and(|path| same_file(path, &live));
            if curfew_win::ipc::ask(&Request::Reload).is_err() && is_live {
                println!(
                    "Saved. Curfew is not running, so this applies when it next starts — \
                     `curfew install` starts it, and `curfew run` enforces it in this terminal."
                );
            }
        }
        std::process::exit(code);
    }

    let code = match command {
        "status" => status(),
        "cancel" => simple(Request::CancelFreeze),
        "confirm" => simple(Request::ConfirmFreeze),
        "start" => start(&args[1..]),
        "end" => end(&args[1..]),
        "release" => release(&args[1..]),
        "emergency" => emergency(&args[1..]),
        "scan" => scan(&args[1..]),
        "peer-release" => peer_release(&args[1..]),
        "tag" => tag(&args[1..]),
        "upcoming" => upcoming(&args[1..]),
        "stats" => stats(&args[1..]),
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
            crate::note!("no such command: {other}\n\n{USAGE}");
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
        crate::note!(
            "usage: curfew extension <browser> <extension-id>

             The id is shown on the browser's extensions page. Chromium browsers give a long 
             lowercase id; Firefox gives an address like curfew@curfew.dev."
        );
        return 2;
    };
    let Some(browser) = Browser::parse(name) else {
        crate::note!("I do not know how to register with {name}.");
        return 2;
    };

    let Ok(exe) = std::env::current_exe() else {
        crate::warn!("could not work out where this program lives.");
        return 1;
    };
    let directory = state::default_path().with_file_name("hosts");
    if let Err(e) = std::fs::create_dir_all(&directory) {
        crate::warn!("could not create {}: {e}", directory.display());
        return 1;
    }
    let path = directory.join(browser.manifest_file());
    if let Err(e) = std::fs::write(&path, manifest(browser.family(), &exe, id)) {
        crate::warn!("could not write {}: {e}", path.display());
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
            crate::warn!("reg add {key} failed ({status}).");
            1
        }
        Err(e) => {
            crate::warn!("could not run reg ({e}).");
            1
        }
    }
}

/// Print what the schedules will block over the next while, and why.
///
/// What the last fortnight of blocking added up to.
///
/// Read straight from the state file rather than asked of the service, so it answers on a machine
/// where the service is stopped — which is exactly the machine somebody is most likely to be
/// looking at their record on. The arithmetic is the core's, and the timezone is the config's, so
/// this prints the same days the phone shows.
fn stats(args: &[String]) -> i32 {
    let Some(path) = args.first() else {
        crate::note!("usage: curfew stats <config.toml> [--days <n>] [--csv | --json]");
        return 2;
    };
    let mut days: u32 = 14;
    let mut format = "text";
    let mut rest = args[1..].iter();
    while let Some(flag) = rest.next() {
        match flag.as_str() {
            "--days" => match rest.next().and_then(|v| v.parse::<u32>().ok()) {
                Some(n) if (1..=365).contains(&n) => days = n,
                _ => {
                    crate::note!("--days wants a number of days, up to 365.");
                    return 2;
                }
            },
            "--csv" => format = "csv",
            "--json" => format = "json",
            other => {
                crate::note!("{other} is not a flag this command takes.");
                return 2;
            }
        }
    }

    let config = match std::fs::read_to_string(path)
        .map_err(|e| format!("{path}: {e}"))
        .and_then(|text| curfew_core::Config::from_toml(&text).map_err(|e| format!("{path}: {e}")))
    {
        Ok(config) => config,
        Err(e) => {
            crate::note!("{e}");
            return 1;
        }
    };
    let zone = match config.tz() {
        Ok(zone) => zone,
        Err(e) => {
            crate::note!("{e}");
            return 1;
        }
    };

    // A missing or unreadable state file is an empty record, not an error: a fresh install has no
    // history, and a machine that has never run the service should say "nothing yet" rather than
    // fail.
    let persisted = match state::load(&state::default_path()) {
        state::Loaded::Ok(state) => state,
        state::Loaded::Recovered { state, detail } => {
            crate::note!("{detail}; these figures come from the backup copy.");
            state
        }
        state::Loaded::Fresh => Default::default(),
        state::Loaded::Lost { detail } => {
            crate::warn!("the state file could not be read ({detail}).");
            Default::default()
        }
    };

    let mut records = persisted.history;
    records.extend(persisted.sessions.running.iter().map(|s| curfew_core::stats::SessionRecord {
        profile: s.profile.clone(),
        started_at: s.started_at,
        ended_at: None,
    }));

    // A wall clock is right for a statistics report: the user asked what their week looked like,
    // and the answer is about the days they remember, not about what a lock should be judged
    // against. Enforcement never uses this.
    let now = runner::wall_now();
    let summary = curfew_core::stats::summarize(&records, now, zone, days);
    match format {
        "csv" => print!("{}", summary.to_csv()),
        "json" => match serde_json::to_string_pretty(&summary) {
            Ok(text) => println!("{text}"),
            Err(e) => {
                crate::note!("{e}");
                return 1;
            }
        },
        _ => {
            println!(
                "{} day{} in a row. Best so far: {}.",
                summary.current_streak,
                if summary.current_streak == 1 { "" } else { "s" },
                summary.longest_streak,
            );
            println!(
                "{} across {} session{} in the last {days} days.\n",
                span(summary.total_blocked_seconds),
                summary.total_sessions,
                if summary.total_sessions == 1 { "" } else { "s" },
            );
            for day in &summary.days {
                // A bar per day, in the terminal's own characters: readable over a remote session,
                // and no colour to be lost in a pipe.
                let hours = day.blocked_seconds as usize / 3600;
                println!(
                    "  {}  {:<24} {}",
                    day.day,
                    "#".repeat(hours.min(24)),
                    span(day.blocked_seconds as u64),
                );
            }
        }
    }
    0
}

/// A duration in the words a person would use for it.
fn span(seconds: u64) -> String {
    match (seconds / 3600, (seconds % 3600) / 60) {
        (0, 0) => "nothing".to_string(),
        (0, m) => format!("{m}m"),
        (h, 0) => format!("{h}h"),
        (h, m) => format!("{h}h {m}m"),
    }
}

/// It lives here rather than in `curfew-cli` because it is the only config command that needs the
/// calendars themselves: a preview that showed weekly windows and quietly left out every meeting
/// would be worse than no preview, since the meetings are the half a user cannot work out in their
/// head. Subscriptions are fetched exactly as the service fetches them, so a URL that will not
/// answer says so here rather than at the moment it was supposed to block something.
fn upcoming(args: &[String]) -> i32 {
    let Some(path) = args.first() else {
        crate::note!("usage: curfew upcoming <config.toml> [--hours <n>]");
        return 2;
    };
    let mut hours: i64 = 36;
    let mut rest = args[1..].iter();
    while let Some(flag) = rest.next() {
        match flag.as_str() {
            "--hours" => match rest.next().and_then(|v| v.parse::<i64>().ok()) {
                Some(n) if (1..=336).contains(&n) => hours = n,
                _ => {
                    crate::note!("--hours wants a number of hours, up to 336 (two weeks).");
                    return 2;
                }
            },
            other => {
                crate::note!("{other} is not a flag this command takes.");
                return 2;
            }
        }
    }

    let config = match std::fs::read_to_string(path)
        .map_err(|e| format!("{path}: {e}"))
        .and_then(|text| curfew_core::Config::from_toml(&text).map_err(|e| format!("{path}: {e}")))
    {
        Ok(config) => config,
        Err(e) => {
            crate::note!("{e}");
            return 1;
        }
    };
    let zone = match config.tz() {
        Ok(zone) => zone,
        Err(e) => {
            crate::note!("{e}");
            return 1;
        }
    };

    let now = runner::wall_now();
    // Fetched into the same cache directory the service uses, so previewing warms the cache the
    // service will read rather than making a second copy of everybody's calendar.
    let mut feeds =
        curfew_win::calendar::Feeds::new(state::default_path().with_file_name("calendars"));
    feeds.restore(&config.calendar_sources);
    // Asked for the same reach the preview is about to print. The enforcement loop keeps the
    // narrow window; a timeline that stopped at a day and a half while still listing weekly
    // windows beyond it would show a free afternoon that is not free.
    let (events, outcomes) = feeds.events_ahead(
        now,
        hours * 3_600,
        &config.calendar_sources,
        zone,
        &feeds::Subscriptions::default(),
    );
    for outcome in outcomes {
        if let curfew_win::calendar::Outcome::Failed { id, detail, still_serving } = outcome {
            crate::warn!(
                "calendar '{id}' could not be read ({detail}){}",
                if still_serving { "; showing the last copy that worked" } else { "" }
            );
        }
    }

    let horizon = now + hours * 3_600;
    let activations = curfew_core::schedule::upcoming(
        now,
        horizon,
        zone,
        &config.weekly,
        &config.calendars,
        &events,
    );
    if activations.is_empty() {
        println!("Nothing is scheduled in the next {hours} hours.");
        if config.weekly.is_empty() && config.calendars.is_empty() {
            println!("There are no schedules yet — `curfew add-window {path} --help` shows how.");
        }
        return 0;
    }

    // Printed in the config's own zone rather than the machine's. Schedules fire in that zone —
    // that is the whole point of the setting — so a window written as 21:00 has to read as 21:00
    // here, even on a laptop carried somewhere else.
    println!("Times are {zone}, the zone this config is written in.");
    // Grouped by day, because "what does tomorrow look like" is the question being asked and a flat
    // list of thirty timestamps does not answer it.
    let mut day = String::new();
    for a in &activations {
        let starts = zone.timestamp_opt(a.start, 0).single();
        let heading = starts
            .map(|t| t.format("%A %-d %B").to_string())
            .unwrap_or_else(|| "later".to_string());
        if heading != day {
            println!("\n{heading}");
            day = heading;
        }
        let because = match &a.source {
            curfew_core::schedule::ActivationSource::Weekly { schedule } => schedule.clone(),
            curfew_core::schedule::ActivationSource::Calendar { schedule, event } => {
                format!("{schedule}: {event}")
            }
        };
        println!(
            "  {}-{}  {}  ({because})",
            starts.map(|t| t.format("%H:%M").to_string()).unwrap_or_default(),
            zone.timestamp_opt(a.end, 0)
                .single()
                .map(|t| t.format("%H:%M").to_string())
                .unwrap_or_default(),
            a.profile,
        );
    }
    0
}

/// Ask the service, and turn "the service is not running" into the sentence that actually helps.
fn ask(request: Request) -> Result<Response, i32> {
    runner::ask(&request).map_err(|e| {
        crate::warn!(
            "could not reach the Curfew service ({e}).\n\
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
    match chrono::Local.timestamp_opt(ts, 0).single() {
        Some(local) => local.format("%a %-d %b, %H:%M").to_string(),
        None => ts.to_string(),
    }
}

fn report(response: Response) -> i32 {
    match response {
        Response::Ok => 0,
        // The figures, as the Time page gets them. Nothing on the command line asks for these —
        // `curfew stats` reads the state file directly so that it works with no service running —
        // but the arm is written rather than wildcarded so that adding a request later gets an
        // answer instead of falling into a catch-all.
        Response::Stats(stats) => {
            println!(
                "{} across {} session{} in the last {} days.",
                span(stats.total_blocked_seconds),
                stats.total_sessions,
                if stats.total_sessions == 1 { "" } else { "s" },
                stats.days.len(),
            );
            if stats.current_streak > 0 {
                println!("Current streak: {} day(s).", stats.current_streak);
            }
            0
        }
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
            crate::note!("{detail}");
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

/// Whether two paths name the same file.
///
/// `canonicalize` first, because `curfew add-window curfew.toml …` run from `%ProgramData%\Curfew`,
/// or a path with `..` in it, or a differently-cased spelling on Windows, all name the same file as
/// the service's own config and none of them compare equal as strings. Falling back to a literal
/// comparison matters too: the file may not exist yet, and canonicalize fails on a path that is not
/// there.
fn same_file(a: &str, b: &std::path::Path) -> bool {
    let a = std::path::Path::new(a);
    if a == b {
        return true;
    }
    match (a.canonicalize(), b.canonicalize()) {
        (Ok(a), Ok(b)) => a == b,
        // Neither could be resolved, and they were not literally equal, so there is nothing left to
        // compare. Saying "not the same" is the safe answer: the only consequence is a missing
        // sentence, where the other direction prints a warning about a file the service never reads.
        _ => false,
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
        crate::note!("usage: curfew start <profile> <minutes> [--credential]");
        return 2;
    };
    let Ok(minutes) = minutes.parse::<u32>() else {
        crate::note!("{minutes} is not a number of minutes");
        return 2;
    };
    let locks = if args.iter().any(|a| a == "--credential") {
        vec![curfew_core::Lock::DeviceCredential]
    } else {
        vec![]
    };
    // Read before the request, because the request takes the lock list by value and the confirmation
    // afterwards needs to know whether there was one.
    let locked = !locks.is_empty();
    // Said out loud before it starts, because after it starts is too late: this is the whole point
    // of a commitment device, and a user surprised by it is a user who was not warned properly.
    if locked {
        println!(
            "Starting a locked session for {minutes} minutes. It will need your Windows password \
             to end early, and if you cannot use it, the 24-hour release is the way out."
        );
    }
    match ask(Request::Start { profile: profile.clone(), seconds: minutes * 60, locks }) {
        // Confirmed out loud, because this is the one command whose entire purpose is to change
        // something and it used to print nothing at all.
        //
        // `report` is silent on `Ok` on purpose — "end" and "release" are asked for and their effect
        // is visible elsewhere, so a line saying "done" would be noise. `start` is different: nothing
        // about the machine looks different afterwards until an app you try to open disappears, which
        // may be minutes later. A user who is not told it worked concludes it did not, and either runs
        // it again or gives up on the feature.
        Ok(Response::Ok) => {
            print!("{}", started_message(profile, minutes, locked));
            0
        }
        Ok(response) => report(response),
        Err(code) => code,
    }
}

/// What `curfew start` says once a session has actually begun.
///
/// Split out from the command so the wording can be read and tested — the same reason the tray keeps
/// `unreachable_service` out of its Win32 layer. The property worth testing is not the wording but the
/// *branch*: what a user is told their way out is has to match the lock they chose, and getting it
/// wrong sends them to a command that will refuse them.
///
/// The locked case is the one that matters. `curfew end` claims nothing satisfied — deliberately, so
/// that a command line cannot assert a password was typed — which means a credential lock is refused
/// there. Telling somebody to run it would be exactly the failure this log keeps finding: the product
/// naming an exit that does not exist.
fn started_message(profile: &str, minutes: u32, locked: bool) -> String {
    let mut text = format!(
        "Started. {profile} is running for the next {minutes} minute{}.\n",
        if minutes == 1 { "" } else { "s" }
    );
    if locked {
        text.push_str(
            "To stop it early you need your Windows password — the Curfew icon can ask for it — or \
             you can start the 24-hour release.\n",
        );
    } else {
        text.push_str("To stop it early: `curfew status`, then `curfew end <id>`.\n");
    }
    text
}

fn end(args: &[String]) -> i32 {
    let Some(id) = args.first() else {
        crate::note!("usage: curfew end <id>");
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
        crate::note!("usage: curfew emergency <id>");
        return 2;
    };
    match ask(Request::Emergency { id: id.clone() }) {
        Ok(response) => report(response),
        Err(code) => code,
    }
}

/// Present a tag. The payload is whatever the sticker or the printed code says.
///
/// Passing it on the command line means it lands in the shell's history, which is a real
/// weakness — a payload read out of `%HISTFILE%` is a tag that can be produced without walking to
/// the fridge. It is accepted anyway because the alternative on a desktop with no NFC reader is no
/// token locks at all, and because the honest place to say so is here, in the tool's own help.
fn scan(args: &[String]) -> i32 {
    let (Some(id), Some(payload)) = (args.first(), args.get(1)) else {
        crate::note!("usage: curfew scan <id> <payload>");
        return 2;
    };
    match ask(Request::Token { id: id.clone(), payload: payload.clone() }) {
        Ok(response) => report(response),
        Err(code) => code,
    }
}

/// Give the release a peer lock on another device is waiting for.
///
/// There is no way to take one back, so this prints what it did rather than asking first: the
/// confirmation belongs in the UI that offers the button, and a CLI that asked twice would be
/// pretending the second answer could change something.
fn peer_release(args: &[String]) -> i32 {
    let Some(id) = args.first() else {
        crate::note!("usage: curfew peer-release <id>");
        return 2;
    };
    match ask(Request::Release { id: id.clone() }) {
        Ok(response) => {
            let code = report(response);
            if code == 0 {
                println!("Released. The other device will act on it within a few seconds.");
            }
            code
        }
        Err(code) => code,
    }
}

/// Mint a tag, or fingerprint one that already exists.
///
/// Prints the payload to write to the sticker or the QR code *and* the config line, because those
/// two have to be produced together and a user who is given only the hash has no way back to the
/// thing it is a hash of. Runs entirely locally and needs no service.
fn tag(args: &[String]) -> i32 {
    let payload = match args.first() {
        Some(given) => given.clone(),
        None => mint(),
    };
    println!(
        "Write this on the tag:
  {payload}
"
    );
    println!("Put this in curfew.toml:");
    println!("  [[tokens]]");
    println!("  id = \"fridge\"");
    println!("  hash = \"{}\"", curfew_core::fingerprint(&payload));
    println!(
        "
The payload is not stored anywhere. Lose the tag and the lock stays shut until"
    );
    println!("its time is up or the 24-hour release lands.");
    0
}

/// A payload with no meaning and enough randomness that guessing it is not a strategy.
fn mint() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    // Not a cryptographic generator, and it does not need to be one: the value never leaves this
    // machine, is written to a physical object, and is only ever compared for equality. What it
    // must not be is guessable from the config, and a hash never reveals it.
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default();
    let mut seed = (now.as_nanos() as u64) ^ (std::process::id() as u64).wrapping_mul(0x9e37_79b9);
    let mut out = String::from("curfew-tag-");
    for _ in 0..24 {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        let alphabet = b"abcdefghijkmnopqrstuvwxyz23456789";
        out.push(alphabet[(seed % alphabet.len() as u64) as usize] as char);
    }
    out
}

fn release(args: &[String]) -> i32 {
    let Some(id) = args.first() else {
        crate::note!("usage: curfew release <id>");
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
    // A machine with no config yet gets the starter one rather than an error naming a file the
    // user has never heard of.
    if let Err(e) = runner::ensure_config(&runner::config_path()) {
        crate::warn!("could not write a starting config ({e}).");
    }
    let enforcer =
        match runner::build(&runner::config_path(), &state::default_path(), runner::hosts_path()) {
            Ok(enforcer) => enforcer,
            Err(detail) => {
                crate::note!("{detail}");
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
            crate::note!("not started by the service control manager: {e}");
            1
        }
    }
}

#[cfg(not(windows))]
fn service_entry() -> i32 {
    crate::note!("services are a Windows thing; use `curfew run`");
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
            crate::warn!(
                "could not install the service ({e}).\n\
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
    //
    // It used to fail open on the one case that matters. The old shape was "refuse if the service
    // answered *and* listed a running session", and every other outcome fell through to
    // `service::uninstall()` — including `Err`, which is what asking a *stopped* service returns.
    // Since the installer stops the service before it runs this, and stopping was itself obeyed,
    // "stop Curfew, then uninstall" removed a running lock's enforcement entirely. So the answer is
    // now the other way round: uninstall needs a positive "nothing is running", from two witnesses.
    let running = match runner::ask(&Request::Status) {
        Ok(Response::Status(status)) => !status.running.is_empty(),
        // Unreachable: stopped, crashed, or wedged. Any of those is a reason to refuse, not a
        // reason to proceed, because the service is the only thing that knows what is running.
        Ok(_) | Err(_) => true,
    };
    // …and the state file directly, which is the witness the watchdog uses and the one thing left
    // when the service is not answering at all. An unreadable state counts as locked.
    let state_says_locked = crate::watchdog::locks_running(&curfew_win::state::default_path());

    if refused_uninstall(running, state_says_locked) {
        println!(
            "Curfew is not going to uninstall itself while a lock is running — that is what you \
             asked it for.\n\
             The session ends on its own, or `curfew release <id>` starts the 24-hour release, \
             after which this will work.\n\
             If Curfew has already been removed from your config, `curfew status` will say what it \
             can still see."
        );
        return 1;
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
            crate::warn!("could not remove the service ({e}). Try an administrator prompt.");
            1
        }
    }
}

/// Whether the uninstall guard should refuse, given its two witnesses.
///
/// Pulled out of [`uninstall`] so the decision can be tested without a service, a pipe or an
/// administrator prompt — because the decision is where the bug was, and the bug was a default.
/// The old code refused only on one specific answer and let *everything else* through, so
/// "unreachable" and "answered something unexpected" both meant "go ahead and uninstall".
///
/// Written as "refuse unless both witnesses positively agree there is nothing running" so that a
/// future reader adding a third witness cannot accidentally widen the hole again: every argument
/// added here defaults to refusing.
#[cfg(windows)]
fn refused_uninstall(service_says_running: bool, state_says_locked: bool) -> bool {
    service_says_running || state_says_locked
}

#[cfg(not(windows))]
fn install() -> i32 {
    crate::note!("services are a Windows thing");
    1
}

#[cfg(not(windows))]
fn uninstall() -> i32 {
    crate::note!("services are a Windows thing");
    1
}

#[cfg(all(test, windows))]
mod uninstall_guard_tests {
    use super::refused_uninstall;

    /// The regression guard. Before this, an unreachable service meant "nothing is running, go
    /// ahead" — and since the installer stops the service before it runs `curfew.exe uninstall`,
    /// that turned "stop Curfew, then uninstall" into a way out of a live lock.
    #[test]
    fn an_unreachable_service_refuses_rather_than_permits() {
        // The call site passes `true` for an unreachable service; this pins the shape so a future
        // refactor that reintroduces a default-allow arm has to delete a test that says why.
        assert!(refused_uninstall(true, false), "an unreachable service allowed an uninstall");
        assert!(refused_uninstall(true, true));
    }

    #[test]
    fn a_running_session_refuses() {
        assert!(refused_uninstall(true, false));
        assert!(refused_uninstall(false, true), "the state file was ignored");
        assert!(refused_uninstall(true, true));
    }

    #[test]
    fn both_witnesses_agreeing_on_nothing_running_is_the_only_way_through() {
        assert!(!refused_uninstall(false, false), "an ordinary uninstall was refused");
    }
}

#[cfg(test)]
mod same_file_tests {
    use super::same_file;
    use std::path::PathBuf;

    /// The case the check exists for: a config written by the same path the service reads.
    #[test]
    fn the_same_path_is_the_same_file() {
        assert!(same_file("/tmp/curfew.toml", &PathBuf::from("/tmp/curfew.toml")));
    }

    /// A different path is a different file, and this is the direction that decides whether a
    /// confusing sentence is printed. Saying "not the same" costs a missing line; saying "the same"
    /// wrongly would tell someone their edits are live when they are not.
    #[test]
    fn a_different_path_is_not() {
        assert!(!same_file("/tmp/other.toml", &PathBuf::from("/tmp/curfew.toml")));
    }

    /// A path reached by a different spelling is the same file, and this is the case that matters in
    /// practice: `curfew add-window curfew.toml …` run from `%ProgramData%\Curfew`, or a path with a
    /// `..` in it, both name the service's own config and neither compares equal as a string.
    #[test]
    fn a_path_reached_by_another_spelling_is_the_same_file() {
        let dir = std::env::temp_dir().join(format!("curfew-same-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("nested")).unwrap();
        let real = dir.join("curfew.toml");
        std::fs::write(&real, "schema_version = 1\n").unwrap();

        let via_parent = dir.join("nested").join("..").join("curfew.toml");
        assert!(
            same_file(via_parent.to_str().unwrap(), &real),
            "a path with `..` in it was treated as a different file"
        );

        // And a file that does not exist yet still compares by its literal spelling, because
        // canonicalize fails on a missing path and the fallback is the only thing left.
        let missing = dir.join("not-written-yet.toml");
        assert!(same_file(missing.to_str().unwrap(), &missing));

        let _ = std::fs::remove_dir_all(&dir);
    }
}

#[cfg(test)]
mod started_message_tests {
    use super::started_message;

    /// `curfew start` says something.
    ///
    /// It printed nothing on success at all, which is the remaining half of F-22: the one command
    /// whose entire purpose is to change something gave no sign that it had. Nothing looks different
    /// until an app you open disappears, which can be minutes later, so a user who is not told
    /// concludes it failed and either runs it again or gives up on the feature.
    #[test]
    fn a_started_session_is_confirmed() {
        let text = started_message("deep-work", 30, false);
        assert!(text.contains("Started"), "{text}");
        assert!(text.contains("deep-work"), "the confirmation does not name the profile: {text}");
        assert!(text.contains("30 minutes"), "the confirmation does not say how long: {text}");
    }

    /// One minute is a minute, not "1 minutes".
    #[test]
    fn a_single_minute_is_not_pluralised() {
        let text = started_message("deep-work", 1, false);
        assert!(text.contains("1 minute."), "{text}");
        assert!(!text.contains("1 minutes"), "{text}");
    }

    /// An unlocked session points at `curfew end`, which will actually work.
    #[test]
    fn an_unlocked_session_names_the_command_that_ends_it() {
        let text = started_message("deep-work", 30, false);
        assert!(text.contains("curfew end"), "{text}");
        assert!(text.contains("curfew status"), "the id has to come from somewhere: {text}");
    }

    /// A locked one does **not**, because `curfew end` claims nothing satisfied and would refuse.
    ///
    /// This is the assertion that earns its place. Sending somebody to a command that fails is exactly
    /// the defect this whole log is about — a product naming an exit that does not exist — and it
    /// would be introduced by a confident-sounding line of copy, not by a logic error.
    #[test]
    fn a_locked_session_never_points_at_a_command_that_would_refuse_it() {
        let text = started_message("deep-work", 30, true);
        assert!(
            !text.contains("curfew end"),
            "a credential-locked session was told to run a command that refuses it: {text}"
        );
        // …and it does say what does work.
        assert!(text.contains("Windows password"), "{text}");
        assert!(text.contains("24-hour release"), "{text}");
    }
}
