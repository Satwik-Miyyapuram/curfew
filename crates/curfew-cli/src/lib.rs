//! `curfew` -- a thin CLI over the core. It exists so the rule engine can be inspected and tested
//! without a phone or a service running, and so the config file stays a first-class artifact.
//!
//! A library rather than a second binary. There is one `curfew` command and there has to be: two
//! crates producing an executable of the same name silently overwrite each other in the target
//! directory, so which `curfew` you got depended on which crate cargo happened to finish last.
//! `curfew-svc` owns the executable and calls [`run`] for the subcommands that need no service.

mod schedule;

use curfew_core::budget::{Consumption, Launches};
use curfew_core::config::Platform;
use curfew_core::engine::State;
use curfew_core::{decide, Config, Decision, LockSet, Observation, Target, Url};
use std::collections::BTreeMap;

const USAGE: &str = "\
curfew -- calendar-unified rules for every window

USAGE:
  curfew check <config.toml>                     validate a config and list its profiles
  curfew migrate <config.toml>                   print the config migrated to the current schema
  curfew decide <config.toml> <profile> <target> [options]

TARGET is one of:
  app:<package>        exe:<name>        title:<window title>
  domain:<host>        url:<url>         path:<file path>
  notif:<package>      device

OPTIONS for decide:
  --at <unix seconds>  evaluate at this instant instead of now
  --used <seconds>     pretend this much budget is already spent
  --opens <count>      pretend the target has been opened this many times
  --platform <android|windows|browser>
";

/// Whether `command` is one of the config subcommands this module answers.
///
/// The service's dispatcher asks before delegating, so an unknown word still reaches the service's
/// own usage text rather than this one — a user who mistypes `statsu` should be told about `status`.
pub fn handles(command: &str) -> bool {
    matches!(command, "check" | "migrate" | "decide") || schedule::handles(command)
}

/// Run one config subcommand. `args` starts at the subcommand itself.
/// Returns the process exit code: 0 for success, 1 for a bad config, 2 for a misuse.
pub fn run(args: &[&str]) -> i32 {
    // The schedule editor answers a family of commands with their own flags and their own usage
    // text, so it is asked first and matches on the whole argument list itself.
    if args.first().is_some_and(|c| schedule::handles(c)) {
        return schedule::run(args);
    }

    let result = match args {
        ["check", path] => check(path),
        ["migrate", path] => migrate(path),
        ["decide", path, profile, target, rest @ ..] => run_decide(path, profile, target, rest),
        _ => {
            eprint!("{USAGE}{}", schedule::USAGE);
            return 2;
        }
    };

    match result {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("error: {e}");
            1
        }
    }
}

fn load(path: &str) -> Result<Config, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{path}: {e}"))?;
    Config::from_toml(&text).map_err(|e| format!("{path}: {e}"))
}

fn check(path: &str) -> Result<(), String> {
    let cfg = load(path)?;
    println!(
        "ok: schema v{}, timezone {}, {} profile(s)",
        cfg.schema_version,
        cfg.timezone,
        cfg.profiles.len()
    );
    for p in &cfg.profiles {
        println!("  {} ({}) -- {} rule(s)", p.id, p.name, p.rules.len());
    }
    Ok(())
}

fn migrate(path: &str) -> Result<(), String> {
    let cfg = load(path)?;
    print!("{}", cfg.to_toml().map_err(|e| e.to_string())?);
    Ok(())
}

fn run_decide(path: &str, profile: &str, target: &str, rest: &[&str]) -> Result<(), String> {
    let cfg = load(path)?;
    if cfg.profile(profile).is_none() {
        return Err(format!("no profile named {profile:?} in {path}"));
    }

    let mut at = now();
    let mut used = 0u32;
    let mut opens = 0u32;
    let mut platform: Option<Platform> = None;
    let mut it = rest.iter();
    while let Some(flag) = it.next() {
        let mut value = || it.next().ok_or_else(|| format!("{flag} needs a value"));
        match *flag {
            "--at" => at = value()?.parse().map_err(|_| "--at wants unix seconds")?,
            "--used" => used = value()?.parse().map_err(|_| "--used wants seconds")?,
            "--opens" => opens = value()?.parse().map_err(|_| "--opens wants a count")?,
            "--platform" => {
                platform = Some(match *value()? {
                    "android" => Platform::Android,
                    "windows" => Platform::Windows,
                    "browser" => Platform::Browser,
                    other => return Err(format!("unknown platform {other:?}")),
                })
            }
            other => return Err(format!("unknown option {other:?}")),
        }
    }

    let (obs, default_platform, key_target) = parse_target(target)?;
    let platform = platform.unwrap_or(default_platform);

    let mut usage = BTreeMap::new();
    let mut launches = BTreeMap::new();
    if let Some(t) = key_target {
        let key = t.key();
        if used > 0 {
            let mut c = Consumption::default();
            c.record(at, used);
            usage.insert(key.clone(), c);
        }
        if opens > 0 {
            let mut l = Launches::default();
            for _ in 0..opens {
                l.record(at);
            }
            launches.insert(key, l);
        }
    }

    let state = State {
        active_profiles: vec![profile.to_string()],
        lock: LockSet::unlocked(),
        usage,
        launches,
        platform,
    };

    match decide(at, &state, &obs, &cfg) {
        Decision::Allow => println!("allow"),
        Decision::Mute => println!("mute"),
        Decision::Delay { seconds } => println!("delay {seconds}s"),
        Decision::Block { reason } => println!("block ({reason:?})"),
    }
    Ok(())
}

/// The target kinds `decide` understands, written the way an error message needs them.
const KINDS: &str =
    "Kinds: app, exe, title, domain (or site), url, path, notif — or `device` on its own for the whole phone.";

/// Parse a `kind:value` target into what the engine sees, the platform it implies, and the
/// [`Target`] whose key names its budget (so `--used` can be attributed to the right ledger).
fn parse_target(spec: &str) -> Result<(Observation, Platform, Option<Target>), String> {
    if spec == "device" {
        return Ok((
            Observation::App { package: "com.example.anything".into(), screen: None },
            Platform::Android,
            Some(Target::WholeDevice),
        ));
    }
    let (kind, value) = spec.split_once(':').ok_or_else(|| {
        // Naming one of seven kinds sent people away thinking `decide` only understood apps. A
        // bare word with a dot in it is almost always a site someone forgot to prefix, so say so
        // rather than making them read the whole list to find the one they wanted.
        if spec.contains('.') && !spec.contains(' ') {
            format!("target must say what {spec:?} is: domain:{spec} for a site, exe:{spec} for a program. {KINDS}")
        } else {
            format!("target must look like kind:value. {KINDS}")
        }
    })?;
    let out = match kind {
        "app" => (
            Observation::App { package: value.into(), screen: None },
            Platform::Android,
            Some(Target::AppPackage { package: value.into() }),
        ),
        "exe" => (
            Observation::Window { exe: value.into(), title: String::new() },
            Platform::Windows,
            Some(Target::WindowsExe { exe: value.into() }),
        ),
        "title" => (
            Observation::Window { exe: String::new(), title: value.into() },
            Platform::Windows,
            None,
        ),
        // `curfew block` spells this `--site`, so `decide` answers to both rather than making
        // someone learn that one command's word for a website is not the other's.
        "domain" | "site" => (
            Observation::Web { url: Url::parse(&format!("https://{value}/")) },
            Platform::Browser,
            Some(Target::Domain { domain: value.into() }),
        ),
        "url" => (
            Observation::Web { url: Url::parse(value) },
            Platform::Browser,
            Some(Target::Domain { domain: Url::parse(value).host }),
        ),
        "path" => (Observation::FileOpen { path: value.into() }, Platform::Windows, None),
        "notif" => (
            Observation::Notification { package: value.into(), title: String::new() },
            Platform::Android,
            Some(Target::NotificationSource { package: value.into() }),
        ),
        other => return Err(format!("unknown target kind {other:?}. {KINDS}")),
    };
    Ok(out)
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::parse_target;

    #[test]
    fn a_target_with_no_kind_is_told_which_kind_it_probably_meant() {
        // The old message named `app:` alone, which read as the only thing `decide` understood.
        let complaint = parse_target("reddit.com").unwrap_err();

        assert!(complaint.contains("domain:reddit.com"), "{complaint}");
        assert!(complaint.contains("exe:reddit.com"), "{complaint}");
    }

    #[test]
    fn a_target_that_is_not_a_forgotten_domain_still_gets_the_list() {
        let complaint = parse_target("some thing").unwrap_err();

        assert!(complaint.contains("kind:value"), "{complaint}");
        assert!(complaint.contains("notif"), "{complaint}");
    }

    #[test]
    fn an_unknown_kind_says_what_the_known_ones_are() {
        let complaint = parse_target("website:reddit.com").unwrap_err();

        assert!(complaint.contains("\"website\""), "{complaint}");
        assert!(complaint.contains("domain"), "{complaint}");
    }

    #[test]
    fn site_and_domain_are_the_same_target() {
        // `curfew block` spells it `--site`; nobody should have to learn two words for a website.
        let (_, _, site) = parse_target("site:reddit.com").unwrap();
        let (_, _, domain) = parse_target("domain:reddit.com").unwrap();

        assert_eq!(domain, site);
        assert!(site.is_some());
    }
}
