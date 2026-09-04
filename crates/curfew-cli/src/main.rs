//! `curfew` -- a thin CLI over the core. It exists so the rule engine can be inspected and tested
//! without a phone or a service running, and so the config file stays a first-class artifact.

use curfew_core::config::{BudgetLedger, Platform};
use curfew_core::engine::{Foreground, State};
use curfew_core::{decide, Config, Decision, LockSet};
use std::process::ExitCode;

const USAGE: &str = "\
curfew -- calendar-unified rules for every window

USAGE:
  curfew check <config.toml>                     validate a config and list its profiles
  curfew decide <config.toml> <profile> <target> ask the rule engine about one target

TARGET is app:<package>, exe:<name>, title:<text> or domain:<host>.
";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["check", path] => check(path),
        ["decide", path, profile, target] => run_decide(path, profile, target),
        _ => {
            eprint!("{USAGE}");
            return ExitCode::from(2);
        }
    };

    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

fn load(path: &str) -> Result<Config, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
    Config::from_toml(&text).map_err(|e| format!("{path}: {e}"))
}

fn check(path: &str) -> Result<(), String> {
    let cfg = load(path)?;
    println!("ok: schema v{}, {} profile(s)", cfg.schema_version, cfg.profiles.len());
    for p in &cfg.profiles {
        println!("  {} ({}) -- {} rule(s)", p.id, p.name, p.rules.len());
    }
    Ok(())
}

fn run_decide(path: &str, profile: &str, target: &str) -> Result<(), String> {
    let cfg = load(path)?;
    if cfg.profile(profile).is_none() {
        return Err(format!("no profile named {profile:?} in {path}"));
    }
    let (kind, value) = target.split_once(':').ok_or("target must look like app:com.example")?;
    let (fg, platform) = match kind {
        "app" => (Foreground::App { package: value.into() }, Platform::Android),
        "exe" => {
            (Foreground::Window { exe: value.into(), title: String::new() }, Platform::Windows)
        }
        "title" => {
            (Foreground::Window { exe: String::new(), title: value.into() }, Platform::Windows)
        }
        "domain" => (Foreground::Web { domain: value.into() }, Platform::Browser),
        other => return Err(format!("unknown target kind {other:?}")),
    };

    let state = State {
        active_profiles: vec![profile.to_string()],
        lock: LockSet::unlocked(),
        budgets: BudgetLedger::new(),
        platform,
    };

    match decide(now(), &state, &fg, &cfg) {
        Decision::Allow => println!("allow"),
        Decision::Delay { seconds } => println!("delay {seconds}s"),
        Decision::Block { reason } => println!("block ({reason:?})"),
    }
    Ok(())
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
