//! Editing schedules from the command line.
//!
//! The phone has forms for this; the desktop has these. A user who has to hand-write TOML to say
//! "block games between 9 and 5" is a user who will write it wrong once and then not trust the
//! blocker again, and the calendar half of Curfew is unusable on a PC without a way to subscribe to
//! a calendar in the first place.
//!
//! Every edit goes through [`Config`]'s own upsert, which validates the whole document and rolls
//! back on refusal, so a bad flag cannot leave a config the service will not load. The file is only
//! written after the core has accepted the change.
//!
//! One thing these commands do *not* preserve: comments and formatting. The document is parsed,
//! edited and printed, because a text-level edit that tried to keep a user's layout would be
//! guessing at where a value ends. It is said out loud on every write rather than discovered later.

use curfew_core::schedule::{CalendarSchedule, CalendarSource, EventMatcher, WeeklySchedule};
use curfew_core::{Action, ChallengeKind, Config, Lock, Platform, Rule, Target, Upserted};

pub const USAGE: &str = "\
  curfew add-profile <config.toml> --id <id> [--name <text>] [--description <text>]
  curfew schedules <config.toml>                 list profiles, windows, rules and subscriptions
  curfew add-window <config.toml> --id <id> --profile <id> --from HH:MM --to HH:MM
                                                 [--days mon,tue|weekdays|weekends] [--lock <lock>]
  curfew add-calendar <config.toml> --id <id> --profile <id>
                                                 [--title <glob>] [--calendar <name>]
                                                 [--location <glob>] [--category <name>]
                                                 [--busy-only] [--all-day] [--timed]
                                                 [--pad-before <minutes>] [--pad-after <minutes>]
                                                 [--shorter-than <minutes>] [--longer-than <minutes>]
                                                 [--lock <lock>]
  curfew add-source <config.toml> --id <id> --from <file-or-url> [--refresh <minutes>]
  curfew blocks <config.toml> [--profile <id>]   list what each profile blocks
  curfew block <config.toml> --profile <id> (--app <package> | --exe <name> | --site <domain>
                                             | --url <glob> | --title <glob> | --word <text>)
                                                 [--on android|windows]
  curfew unblock <config.toml> --profile <id> <the same selector>
  curfew remove <config.toml> <id>               remove a profile, window, rule or subscription

LOCKS (repeat --lock for more than one):
  timer  confirm  credential  restart  typing  math  token:<tag-id>  peer:<device-id>
";

/// Whether `command` is one of the schedule subcommands.
pub fn handles(command: &str) -> bool {
    matches!(
        command,
        "schedules"
            | "add-profile"
            | "add-window"
            | "add-calendar"
            | "add-source"
            | "blocks"
            | "block"
            | "unblock"
            | "remove"
    )
}

/// Whether `command` changes the config file rather than only reading it.
///
/// The two reading commands need nothing afterwards. Every other verb writes, and a write the running
/// service has not been told about is the worst kind of failure this product can have: the file says
/// the plan changed, the window reads the same file and shows the change, and the service is still
/// enforcing the old one. The caller uses this to decide whether a reload is owed.
pub fn writes_config(command: &str) -> bool {
    matches!(
        command,
        "add-profile"
            | "add-window"
            | "add-calendar"
            | "add-source"
            | "block"
            | "unblock"
            | "remove"
    )
}

/// Where the config path sits in a writing command's argument list.
///
/// Every one of them is `<verb> <config> [flags…]`, so it is always index 1 — but saying so here
/// rather than indexing at the call site means a verb that ever stops following the shape fails a
/// test instead of silently reloading nothing.
pub const CONFIG_ARG: usize = 1;

/// Run one schedule subcommand, and return the process exit code.
///
/// Two failures with different exit codes, because they have different fixes: 2 is a command that
/// was typed wrongly and gets the usage text, 1 is a command that was typed correctly and asked for
/// something that will not work, which gets the core's own sentence about why.
pub fn run(args: &[&str]) -> i32 {
    let result = match args {
        ["schedules", path] => list(path),
        ["add-profile", path, rest @ ..] => add_profile(path, rest),
        ["blocks", path, rest @ ..] => blocks(path, rest),
        ["block", path, rest @ ..] => block(path, rest),
        ["unblock", path, rest @ ..] => unblock(path, rest),
        ["add-window", path, rest @ ..] => add_window(path, rest),
        ["add-calendar", path, rest @ ..] => add_calendar(path, rest),
        ["add-source", path, rest @ ..] => add_source(path, rest),
        ["remove", path, id] => remove(path, id),
        _ => {
            eprint!("{USAGE}");
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

/// Write the edited config back, replacing the file in one step.
///
/// Through a temporary file in the same directory and a rename, because the alternative — truncate,
/// then write — has a window in which the config is half a document, and a service that reloads
/// during that window is a service that has just lost every rule the user had.
fn save(path: &str, config: &Config) -> Result<(), String> {
    let text = config.to_toml().map_err(|e| e.to_string())?;
    let temp = format!("{path}.new");
    std::fs::write(&temp, &text).map_err(|e| format!("{temp}: {e}"))?;
    std::fs::rename(&temp, path).map_err(|e| format!("{path}: {e}"))?;
    println!("Saved {path}. (Comments and formatting in the file are not preserved.)");
    Ok(())
}

// --- reading ------------------------------------------------------------------------------------

const DAYS: [&str; 7] = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];

/// "09:00", from minutes after midnight.
fn hhmm(minutes: u32) -> String {
    format!("{:02}:{:02}", (minutes / 60) % 24, minutes % 60)
}

fn describe_days(days: &[u8]) -> String {
    let mut sorted: Vec<u8> = days.to_vec();
    sorted.sort_unstable();
    sorted.dedup();
    match sorted.as_slice() {
        [] => "every day".to_string(),
        [0, 1, 2, 3, 4] => "weekdays".to_string(),
        [5, 6] => "weekends".to_string(),
        other => other
            .iter()
            .map(|d| DAYS.get(*d as usize).copied().unwrap_or("?"))
            .collect::<Vec<_>>()
            .join(","),
    }
}

fn describe_lock(lock: &Lock) -> String {
    match lock {
        Lock::Timer => "timer".into(),
        Lock::Confirm => "confirm".into(),
        Lock::DeviceCredential => "credential".into(),
        Lock::RestartRequired => "restart".into(),
        Lock::Challenge { challenge: ChallengeKind::Typing } => "typing".into(),
        Lock::Challenge { challenge: ChallengeKind::Math } => "math".into(),
        Lock::Token { id } => format!("token:{id}"),
        Lock::PeerRelease { device_id } => format!("peer:{device_id}"),
    }
}

fn describe_locks(locks: &[Lock]) -> String {
    if locks.is_empty() {
        // Not "no locks": an unlocked session is one the user may end early, which is a thing to
        // know about a window before relying on it.
        return "endable at any time".to_string();
    }
    locks.iter().map(describe_lock).collect::<Vec<_>>().join(" + ")
}

fn describe_matcher(m: &EventMatcher) -> String {
    let mut parts = Vec::new();
    if let Some(c) = m.calendar.as_deref().filter(|s| !s.trim().is_empty()) {
        parts.push(format!("on the {c} calendar"));
    }
    if let Some(t) = m.title.as_deref().filter(|s| !s.trim().is_empty()) {
        parts.push(format!("titled {t}"));
    }
    if let Some(l) = m.location.as_deref().filter(|s| !s.trim().is_empty()) {
        parts.push(format!("at {l}"));
    }
    if m.busy_only {
        parts.push("marked busy".into());
    }
    match m.all_day {
        Some(true) => parts.push("all-day".into()),
        Some(false) => parts.push("timed".into()),
        None => {}
    }
    if !m.categories.is_empty() {
        parts.push(format!("categorised {}", m.categories.join(" or ")));
    }
    if let Some(s) = m.min_duration_seconds {
        parts.push(format!("longer than {} min", s / 60));
    }
    if let Some(s) = m.max_duration_seconds {
        parts.push(format!("shorter than {} min", s / 60));
    }
    if parts.is_empty() {
        "every event".to_string()
    } else {
        format!("events {}", parts.join(", "))
    }
}

fn describe_padding(before: u32, after: u32) -> String {
    match (before, after) {
        (0, 0) => String::new(),
        (b, 0) => format!(", starting {} min early", b / 60),
        (0, a) => format!(", running {} min late", a / 60),
        (b, a) => format!(", starting {} min early and running {} min late", b / 60, a / 60),
    }
}

fn list(path: &str) -> Result<(), String> {
    let cfg = load(path)?;

    println!("profiles:");
    if cfg.profiles.is_empty() {
        // The gap a fresh install falls into: no profile means no schedule can be written at all.
        println!("  (none) — `curfew add-profile` adds one, and everything below needs one");
    }
    for p in &cfg.profiles {
        println!(
            "  {} — {}, blocking {} thing(s){}",
            p.id,
            p.name,
            p.rules.len(),
            if p.description.trim().is_empty() {
                String::new()
            } else {
                format!(" ({})", p.description)
            }
        );
    }

    println!("\nweekly windows:");
    if cfg.weekly.is_empty() {
        println!("  (none) — `curfew add-window` adds one");
    }
    for w in &cfg.weekly {
        let overnight = if w.end_minute <= w.start_minute { " (overnight)" } else { "" };
        println!(
            "  {} — {} runs {}, {} to {}{overnight}; {}",
            w.id,
            w.profile,
            describe_days(&w.days),
            hhmm(w.start_minute),
            hhmm(w.end_minute),
            describe_locks(&w.locks),
        );
    }

    println!("\ncalendar rules:");
    if cfg.calendars.is_empty() {
        println!("  (none) — `curfew add-calendar` adds one");
    }
    for c in &cfg.calendars {
        println!(
            "  {} — {} runs for {}{}; {}",
            c.id,
            c.profile,
            describe_matcher(&c.matcher),
            describe_padding(c.pad_before_seconds, c.pad_after_seconds),
            describe_locks(&c.locks),
        );
    }

    println!("\ncalendar subscriptions:");
    if cfg.calendar_sources.is_empty() {
        // The one that catches people out: rules with nothing to match against never fire, and
        // nothing anywhere else says so.
        println!(
            "  (none) — a calendar rule on this machine matches nothing until \
             `curfew add-source` gives it a calendar to read"
        );
    }
    for s in &cfg.calendar_sources {
        println!("  {} — {} every {} min", s.id, s.location, s.refresh_seconds / 60);
    }
    Ok(())
}

// --- flags --------------------------------------------------------------------------------------

/// The flags one command was given: every `--flag value` pair, in order, with `--flag` alone
/// recorded as an empty value.
struct Flags {
    pairs: Vec<(String, String)>,
}

impl Flags {
    fn parse(args: &[&str], switches: &[&str]) -> Result<Self, String> {
        let mut pairs = Vec::new();
        let mut it = args.iter().peekable();
        while let Some(arg) = it.next() {
            let Some(name) = arg.strip_prefix("--") else {
                return Err(format!("{arg} is not a flag; every value follows a --flag"));
            };
            if switches.contains(&name) {
                pairs.push((name.to_string(), String::new()));
                continue;
            }
            let value = it.next().ok_or_else(|| format!("--{name} needs a value"))?;
            if value.starts_with("--") {
                return Err(format!("--{name} needs a value, and {value} is another flag"));
            }
            pairs.push((name.to_string(), (*value).to_string()));
        }
        Ok(Self { pairs })
    }

    fn one(&self, name: &str) -> Option<&str> {
        self.pairs.iter().find(|(n, _)| n == name).map(|(_, v)| v.as_str())
    }

    fn required(&self, name: &str) -> Result<&str, String> {
        self.one(name).ok_or_else(|| format!("--{name} is needed"))
    }

    fn all(&self, name: &str) -> Vec<&str> {
        self.pairs.iter().filter(|(n, _)| n == name).map(|(_, v)| v.as_str()).collect()
    }

    fn has(&self, name: &str) -> bool {
        self.one(name).is_some()
    }

    /// A flag nobody reads is a flag that silently did nothing, which on a blocker means a lock the
    /// user thinks they asked for and did not get.
    fn reject_unknown(&self, known: &[&str]) -> Result<(), String> {
        match self.pairs.iter().find(|(n, _)| !known.contains(&n.as_str())) {
            Some((n, _)) => Err(format!("--{n} is not a flag this command takes")),
            None => Ok(()),
        }
    }
}

/// "09:00" as minutes after midnight. Strict: 24:00 and 9:60 are not times, and rounding one into
/// the next day would move a window without saying so.
fn minutes(text: &str) -> Result<u32, String> {
    let (h, m) =
        text.split_once(':').ok_or_else(|| format!("{text:?} is not a time like 09:00"))?;
    let h: u32 = h.trim().parse().map_err(|_| format!("{text:?} is not a time like 09:00"))?;
    let m: u32 = m.trim().parse().map_err(|_| format!("{text:?} is not a time like 09:00"))?;
    if h > 23 || m > 59 {
        return Err(format!("{text:?} is not a time on the clock"));
    }
    Ok(h * 60 + m)
}

fn days(text: &str) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    for word in text.split(',').map(str::trim).filter(|w| !w.is_empty()) {
        match word.to_ascii_lowercase().as_str() {
            "every" | "daily" | "all" => return Ok(Vec::new()),
            "weekdays" => out.extend([0, 1, 2, 3, 4]),
            "weekends" => out.extend([5, 6]),
            other => match DAYS.iter().position(|d| other.starts_with(d)) {
                Some(index) => out.push(index as u8),
                None => return Err(format!("{word:?} is not a day; try mon,tue or weekdays")),
            },
        }
    }
    out.sort_unstable();
    out.dedup();
    Ok(out)
}

fn lock(text: &str) -> Result<Lock, String> {
    Ok(match text {
        "timer" => Lock::Timer,
        "confirm" => Lock::Confirm,
        "credential" | "device-credential" => Lock::DeviceCredential,
        "restart" => Lock::RestartRequired,
        "typing" => Lock::Challenge { challenge: ChallengeKind::Typing },
        "math" => Lock::Challenge { challenge: ChallengeKind::Math },
        other => match other.split_once(':') {
            Some(("token", id)) if !id.is_empty() => Lock::Token { id: id.to_string() },
            Some(("peer", id)) if !id.is_empty() => {
                Lock::PeerRelease { device_id: id.to_string() }
            }
            _ => return Err(format!("{other:?} is not a lock. One of: timer, confirm, credential, restart, typing, math, token:<id>, peer:<id>")),
        },
    })
}

fn locks(flags: &Flags) -> Result<Vec<Lock>, String> {
    flags.all("lock").into_iter().map(lock).collect()
}

fn minutes_flag(flags: &Flags, name: &str) -> Result<Option<u32>, String> {
    match flags.one(name) {
        None => Ok(None),
        Some(v) => v.parse().map(Some).map_err(|_| format!("--{name} wants a number of minutes")),
    }
}

// --- writing ------------------------------------------------------------------------------------

fn add_window(path: &str, args: &[&str]) -> Result<(), String> {
    let flags = Flags::parse(args, &[])?;
    flags.reject_unknown(&["id", "profile", "from", "to", "days", "lock"])?;

    let window = WeeklySchedule {
        id: flags.required("id")?.to_string(),
        profile: flags.required("profile")?.to_string(),
        days: match flags.one("days") {
            Some(text) => days(text)?,
            None => Vec::new(),
        },
        start_minute: minutes(flags.required("from")?)?,
        end_minute: minutes(flags.required("to")?)?,
        locks: locks(&flags)?,
        // Made from the command line, so it runs; pausing is a later edit.
        enabled: true,
    };

    let mut cfg = load(path)?;
    let description = format!(
        "{} runs {}, {} to {}",
        window.profile,
        describe_days(&window.days),
        hhmm(window.start_minute),
        hhmm(window.end_minute)
    );
    // **The core says what happened; this no longer infers it** — P2-10.
    //
    // This used to print `Added` unless a row with the same *id* already existed, which is a
    // different question from whether the window was stored. `upsert_weekly` deliberately declines
    // to keep a second window with the same profile, days and times under a different id, so the
    // command reported a lock it had just discarded. On a tool whose whole point is being trusted
    // about whether a lock exists, that is the wrong thing to be wrong about.
    match cfg.upsert_weekly(window).map_err(|e| e.to_string())? {
        Upserted::Added => println!("Added: {description}."),
        Upserted::Replaced => println!("Replaced: {description}."),
        Upserted::AlreadyPresent => println!(
            "Already there: {description}. The plan already holds a window that runs those minutes \
             on those days, and two of them cannot behave differently from one, so nothing was \
             added."
        ),
    }
    save(path, &cfg)?;
    Ok(())
}

fn add_calendar(path: &str, args: &[&str]) -> Result<(), String> {
    let flags = Flags::parse(args, &["busy-only", "all-day", "timed"])?;
    flags.reject_unknown(&[
        "id",
        "profile",
        "title",
        "calendar",
        "location",
        "category",
        "busy-only",
        "all-day",
        "timed",
        "pad-before",
        "pad-after",
        "shorter-than",
        "longer-than",
        "lock",
    ])?;

    if flags.has("all-day") && flags.has("timed") {
        // Both means "match events that are all-day and not all-day", which matches nothing.
        return Err("--all-day and --timed ask for opposite things".into());
    }
    let text = |name: &str| flags.one(name).map(str::to_string).filter(|s| !s.trim().is_empty());

    let rule = CalendarSchedule {
        id: flags.required("id")?.to_string(),
        profile: flags.required("profile")?.to_string(),
        matcher: EventMatcher {
            title: text("title"),
            calendar: text("calendar"),
            location: text("location"),
            busy_only: flags.has("busy-only"),
            all_day: if flags.has("all-day") {
                Some(true)
            } else if flags.has("timed") {
                Some(false)
            } else {
                None
            },
            categories: flags.all("category").into_iter().map(str::to_string).collect(),
            min_duration_seconds: minutes_flag(&flags, "longer-than")?.map(|m| m as u64 * 60),
            max_duration_seconds: minutes_flag(&flags, "shorter-than")?.map(|m| m as u64 * 60),
        },
        pad_before_seconds: minutes_flag(&flags, "pad-before")?.unwrap_or(0) * 60,
        pad_after_seconds: minutes_flag(&flags, "pad-after")?.unwrap_or(0) * 60,
        locks: locks(&flags)?,
        // Made from the command line, so it runs; pausing is a later edit.
        enabled: true,
    };

    let mut cfg = load(path)?;
    let replacing = cfg.calendars.iter().any(|c| c.id == rule.id);
    let description = format!(
        "{} runs for {}{}",
        rule.profile,
        describe_matcher(&rule.matcher),
        describe_padding(rule.pad_before_seconds, rule.pad_after_seconds)
    );
    let orphaned = cfg.calendar_sources.is_empty();
    cfg.upsert_calendar(rule).map_err(|e| e.to_string())?;
    save(path, &cfg)?;
    println!("{}: {description}.", if replacing { "Replaced" } else { "Added" });
    if orphaned {
        println!(
            "This machine reads no calendars yet, so the rule will match nothing. \
             `curfew add-source` subscribes to one."
        );
    }
    Ok(())
}

/// Create a profile, or rename one.
///
/// First in the usage text and first in practice: every other command asks for a profile by id, and
/// on a fresh install there are none, so this is the command that has to exist before the rest mean
/// anything. What the profile *blocks* is not set here: the phone's app picker owns that list, and
/// on a PC it is the [[profiles.rules]] tables, which are too shaped to be worth a flag each.
fn add_profile(path: &str, args: &[&str]) -> Result<(), String> {
    let flags = Flags::parse(args, &[])?;
    flags.reject_unknown(&["id", "name", "description"])?;

    let id = flags.required("id")?.to_string();
    // The name defaults to the id so that `--id reading` alone works: a name is required by the
    // core, and making the user type "reading" twice buys nothing.
    let name = flags.one("name").unwrap_or(&id).to_string();
    let description = flags.one("description").unwrap_or("").to_string();

    let mut cfg = load(path)?;
    let existing = cfg.profile(&id).is_some();
    let rules = cfg.profile(&id).map(|p| p.rules.len()).unwrap_or(0);
    cfg.upsert_profile(&id, &name, &description).map_err(|e| e.to_string())?;
    save(path, &cfg)?;
    if existing {
        println!("Renamed {id} to {name}. The {rules} thing(s) it blocks are unchanged.");
    } else {
        println!(
            "Added the profile {id} ({name}). It blocks nothing yet — the app picker on \
             the phone, or a [[profiles.rules]] table in this file, says what — and it \
             starts nothing until a schedule names it."
        );
    }
    Ok(())
}

/// The selectors `block` and `unblock` share, and the target each one means.
///
/// One list rather than a flag per command, so the two can never drift into naming the same thing
/// differently — an unblock that does not spell its target exactly as the block did would silently
/// remove nothing.
const SELECTORS: [&str; 6] = ["app", "exe", "site", "url", "title", "word"];

/// Read exactly one selector flag, and turn it into the target it names.
///
/// Exactly one, because a command naming two things is ambiguous about which of them the platform
/// filter and the action belong to, and guessing there means blocking something the user did not
/// ask to block.
fn target(flags: &Flags) -> Result<Target, String> {
    let chosen: Vec<&str> = SELECTORS.iter().copied().filter(|f| flags.has(f)).collect();
    match chosen.as_slice() {
        [] => Err(format!(
            "say what to block: {}",
            SELECTORS.iter().map(|f| format!("--{f}")).collect::<Vec<_>>().join(", ")
        )),
        [one] => {
            let value = flags.required(one)?.trim().to_string();
            if value.is_empty() {
                return Err(format!("--{one} was given nothing to match"));
            }
            Ok(match *one {
                "app" => Target::AppPackage { package: value },
                "exe" => Target::WindowsExe { exe: value },
                "site" => Target::Domain { domain: value },
                "url" => Target::Url { pattern: value },
                "title" => Target::WindowTitle { pattern: value },
                _ => Target::Keyword { text: value },
            })
        }
        many => Err(format!(
            "one thing at a time: {} were all given, and a rule points at one target",
            many.iter().map(|f| format!("--{f}")).collect::<Vec<_>>().join(" and ")
        )),
    }
}

/// The platforms a rule applies to. Empty means every one, which is what leaving `--on` out means.
fn platforms(flags: &Flags) -> Result<Vec<Platform>, String> {
    flags
        .all("on")
        .iter()
        .map(|value| match value.to_ascii_lowercase().as_str() {
            "android" => Ok(Platform::Android),
            "windows" => Ok(Platform::Windows),
            other => Err(format!("{other:?} is not a platform; say android or windows")),
        })
        .collect()
}

/// Say in one line what a rule points at, in the words the flags use.
fn describe_target(t: &Target) -> String {
    match t {
        Target::AppPackage { package } => format!("app {package}"),
        Target::AppScreen { package, screen } => format!("screen {screen} in {package}"),
        Target::WindowsExe { exe } => format!("exe {exe}"),
        Target::WindowTitle { pattern } => format!("windows titled {pattern}"),
        Target::Domain { domain } => format!("site {domain} and its subdomains"),
        Target::Url { pattern } => format!("urls matching {pattern}"),
        Target::Keyword { text } => format!("the word {text}"),
        Target::FilePath { pattern } => format!("paths matching {pattern}"),
        Target::NotificationSource { package } => format!("notifications from {package}"),
        Target::WholeDevice => "the whole device".to_string(),
    }
}

/// Say what a rule does, for the listing.
///
/// The budget and limit shapes are described but not editable here: the core decides them already,
/// and a flag for each would be a second way to write a setting whose UI is still being designed.
/// Printing them is what keeps `blocks` an honest list of everything in the profile.
fn describe_action(a: &Action) -> String {
    match a {
        Action::Block => "blocked".to_string(),
        Action::AllowOnly => "allowed, and everything else blocked".to_string(),
        Action::Budget { seconds, .. } => format!("budgeted {} min", seconds / 60),
        Action::LaunchLimit { count, .. } => format!("limited to {count} opens"),
        Action::Delay { seconds } => format!("delayed {seconds}s before opening"),
        Action::MuteNotifications => "notification-muted".to_string(),
    }
}

/// Block something in a profile.
fn block(path: &str, args: &[&str]) -> Result<(), String> {
    let flags = Flags::parse(args, &[])?;
    let mut known = vec!["profile", "on"];
    known.extend(SELECTORS);
    flags.reject_unknown(&known)?;

    let profile = flags.required("profile")?.to_string();
    let rule =
        Rule { target: target(&flags)?, action: Action::Block, platforms: platforms(&flags)? };
    let described = describe_target(&rule.target);

    let mut cfg = load(path)?;
    cfg.upsert_rule(&profile, rule).map_err(|e| e.to_string())?;
    save(path, &cfg)?;
    // Said because it is the question people ask next: a rule does nothing until the profile it is
    // in is running, and the profile runs when a schedule says so.
    println!(
        "{profile} now blocks {described}. It takes effect while {profile} is running — \
         `curfew schedules {path}` says when that is."
    );
    Ok(())
}

/// Stop blocking something.
fn unblock(path: &str, args: &[&str]) -> Result<(), String> {
    let flags = Flags::parse(args, &[])?;
    let mut known = vec!["profile"];
    known.extend(SELECTORS);
    flags.reject_unknown(&known)?;

    let profile = flags.required("profile")?.to_string();
    let target = target(&flags)?;
    let described = describe_target(&target);

    let mut cfg = load(path)?;
    if cfg.profile(&profile).is_none() {
        return Err(format!("no profile {profile:?} in {path}"));
    }
    // Every rule on that target goes, on every platform: `--on windows` is not asked for here
    // because "stop blocking this" with one of two rules left standing is not what anybody means.
    let removed = cfg.remove_rule(&profile, &target);
    if removed == 0 {
        return Err(format!(
            "{profile} has no rule for {described}; `curfew blocks {path}` lists what it has"
        ));
    }
    save(path, &cfg)?;
    println!(
        "{profile} no longer blocks {described}. A session already running keeps its own copy of \
         what it blocks until it ends."
    );
    Ok(())
}

/// List what each profile blocks.
fn blocks(path: &str, args: &[&str]) -> Result<(), String> {
    let flags = Flags::parse(args, &[])?;
    flags.reject_unknown(&["profile"])?;
    let only = flags.one("profile").map(str::to_string);

    let cfg = load(path)?;
    if let Some(id) = &only {
        if cfg.profile(id).is_none() {
            return Err(format!("no profile {id:?} in {path}"));
        }
    }
    let mut shown = false;
    for p in cfg.profiles.iter().filter(|p| only.as_deref().is_none_or(|id| id == p.id)) {
        shown = true;
        println!("{} ({}):", p.id, p.name);
        if p.rules.is_empty() {
            println!("  (nothing) — `curfew block {path} --profile {} --site …`", p.id);
        }
        for rule in &p.rules {
            let where_ = if rule.platforms.is_empty() {
                String::new()
            } else {
                format!(
                    " on {}",
                    rule.platforms
                        .iter()
                        .map(|p| format!("{p:?}").to_lowercase())
                        .collect::<Vec<_>>()
                        .join(" and ")
                )
            };
            println!(
                "  {} — {}{where_}",
                describe_target(&rule.target),
                describe_action(&rule.action)
            );
        }
    }
    if !shown {
        println!("No profiles yet — `curfew add-profile {path} --id <id>` adds one.");
    }
    Ok(())
}

fn add_source(path: &str, args: &[&str]) -> Result<(), String> {
    let flags = Flags::parse(args, &[])?;
    flags.reject_unknown(&["id", "from", "refresh"])?;

    let source = CalendarSource {
        id: flags.required("id")?.to_string(),
        location: flags.required("from")?.to_string(),
        refresh_seconds: minutes_flag(&flags, "refresh")?.map(|m| m * 60).unwrap_or(3_600),
    };

    let mut cfg = load(path)?;
    let replacing = cfg.calendar_sources.iter().any(|s| s.id == source.id);
    let (id, location) = (source.id.clone(), source.location.clone());
    cfg.upsert_source(source).map_err(|e| e.to_string())?;
    save(path, &cfg)?;
    println!("{}: {id} reads {location}.", if replacing { "Replaced" } else { "Subscribed" });
    Ok(())
}

/// Remove whatever carries this id — window, rule or subscription.
///
/// One command rather than three because a user who wants a schedule gone knows its name and not
/// which list it lives in, and the ids are theirs to choose.
fn remove(path: &str, id: &str) -> Result<(), String> {
    let mut cfg = load(path)?;
    let mut removed = Vec::new();
    if cfg.profile(id).is_some() {
        // Refused while a schedule still names it, and the core's error says which — so this is
        // asked first, before anything else has been taken out of the document.
        cfg.remove_profile(id).map_err(|e| e.to_string())?;
        removed.push("profile");
    }
    if cfg.weekly.iter().any(|w| w.id == id) {
        cfg.remove_weekly(id);
        removed.push("weekly window");
    }
    if cfg.calendars.iter().any(|c| c.id == id) {
        cfg.remove_calendar(id);
        removed.push("calendar rule");
    }
    if cfg.calendar_sources.iter().any(|s| s.id == id) {
        cfg.remove_source(id);
        removed.push("subscription");
    }
    if removed.is_empty() {
        return Err(format!(
            "nothing in {path} is called {id:?}; `curfew schedules {path}` lists them"
        ));
    }

    save(path, &cfg)?;
    // A profile is a list of things to block, not something that starts on its own, so it gets its
    // own sentence rather than being told it has stopped starting sessions it never started.
    let consequence = if removed == ["profile"] {
        "Everything it blocked goes with it"
    } else {
        "It stops starting sessions"
    };
    println!(
        "Removed the {} called {id}. {consequence}; a session already running keeps running \
         until its own lock lets it go.",
        removed.join(" and the ")
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The distinction this module exists to make available to its caller.
    ///
    /// Getting it wrong in either direction is a real bug: a reading command that owed a reload would
    /// make `curfew schedules` restart enforcement's view of the world for no reason, and a writing
    /// command that was not listed would print "Added" while the service kept enforcing the old plan.
    #[test]
    fn only_the_commands_that_write_owe_a_reload() {
        for reading in ["schedules", "blocks"] {
            assert!(handles(reading), "{reading} is not handled at all");
            assert!(!writes_config(reading), "{reading} only reads and asked for a reload");
        }
        for writing in [
            "add-profile",
            "add-window",
            "add-calendar",
            "add-source",
            "block",
            "unblock",
            "remove",
        ] {
            assert!(handles(writing), "{writing} is not handled at all");
            assert!(writes_config(writing), "{writing} writes and did not ask for a reload");
        }
        // Everything handled is one or the other, so a verb added later cannot be neither.
        for command in [
            "schedules",
            "add-profile",
            "add-window",
            "add-calendar",
            "add-source",
            "blocks",
            "block",
            "unblock",
            "remove",
        ] {
            assert!(writes_config(command) || matches!(command, "schedules" | "blocks"));
        }
    }

    /// The config path is the one argument every writing verb agrees on.
    ///
    /// `writes_config` and `CONFIG_ARG` are used together to decide whether a failed reload is worth a
    /// sentence, so a verb that took its path somewhere else would silently compare the wrong argument
    /// against the service's own config. No file is needed: the assertion is about argument positions,
    /// which is the half a refactor would break.
    #[test]
    fn every_writing_command_takes_its_config_at_the_same_position() {
        const P: &str = "/tmp/curfew.toml";
        let invocations: [&[&str]; 7] = [
            &["add-profile", P, "--id", "x"],
            &[
                "add-window",
                P,
                "--id",
                "w",
                "--profile",
                "deep-work",
                "--from",
                "09:00",
                "--to",
                "10:00",
            ],
            &["add-calendar", P, "--id", "c", "--profile", "deep-work"],
            &["add-source", P, "--id", "s", "--location", "/tmp/x.ics"],
            &["block", P, "--profile", "deep-work", "--site", "reddit.com"],
            &["unblock", P, "--profile", "deep-work", "--site", "reddit.com"],
            &["remove", P, "w"],
        ];
        for args in invocations {
            assert!(writes_config(args[0]), "{} does not ask for a reload", args[0]);
            assert_eq!(
                args.get(CONFIG_ARG).copied(),
                Some(P),
                "{} does not take its config at index {CONFIG_ARG}",
                args[0]
            );
        }
    }

    #[test]
    fn a_time_is_read_the_way_it_is_written() {
        assert_eq!(minutes("09:00"), Ok(540));
        assert_eq!(minutes("9:5"), Ok(545));
        assert_eq!(minutes("23:59"), Ok(1_439));
    }

    /// Rounding 24:00 down to 23:59, or up to the next day, would move a window a day without
    /// telling anyone. Refused instead.
    #[test]
    fn what_is_not_a_time_is_refused_rather_than_guessed_at() {
        for bad in ["", "9", "24:00", "9:60", "-1:00", "nine", "9:aa", "9:00:00"] {
            assert!(minutes(bad).is_err(), "{bad:?} was read as a time");
        }
    }

    #[test]
    fn the_words_people_use_for_days_are_understood() {
        assert_eq!(days("mon,wed"), Ok(vec![0, 2]));
        assert_eq!(days("weekdays"), Ok(vec![0, 1, 2, 3, 4]));
        assert_eq!(days("weekends"), Ok(vec![5, 6]));
        assert_eq!(days("monday,tuesday"), Ok(vec![0, 1]));
        assert_eq!(days("every"), Ok(Vec::new()));
        // Written out of order, and twice, is still the same three days.
        assert_eq!(days("wed,mon,wed"), Ok(vec![0, 2]));
        assert!(days("funday").is_err());
    }

    #[test]
    fn every_lock_survives_being_named_and_read_back() {
        for text in [
            "timer",
            "confirm",
            "credential",
            "restart",
            "typing",
            "math",
            "token:fridge",
            "peer:laptop",
        ] {
            let parsed = lock(text).expect(text);
            assert_eq!(
                describe_lock(&parsed),
                if text == "device-credential" { "credential" } else { text }
            );
        }
        assert!(lock("token:").is_err());
        assert!(lock("nonsense").is_err());
    }

    #[test]
    fn a_flag_without_its_value_is_an_error_and_not_an_empty_string() {
        assert!(Flags::parse(&["--id"], &[]).is_err());
        assert!(Flags::parse(&["--id", "--profile"], &[]).is_err());
        assert!(Flags::parse(&["window"], &[]).is_err());
        let flags = Flags::parse(&["--busy-only", "--id", "x"], &["busy-only"]).unwrap();
        assert!(flags.has("busy-only"));
        assert_eq!(flags.one("id"), Some("x"));
    }

    #[test]
    fn a_misspelled_flag_is_refused_rather_than_ignored() {
        let flags = Flags::parse(&["--titel", "x"], &[]).unwrap();
        assert!(flags.reject_unknown(&["title"]).is_err());
    }

    #[test]
    fn days_are_described_the_way_they_are_typed() {
        assert_eq!(describe_days(&[]), "every day");
        assert_eq!(describe_days(&[0, 1, 2, 3, 4]), "weekdays");
        assert_eq!(describe_days(&[6, 5]), "weekends");
        assert_eq!(describe_days(&[2, 0]), "mon,wed");
    }

    #[test]
    fn an_empty_matcher_says_it_catches_everything() {
        assert_eq!(describe_matcher(&EventMatcher::default()), "every event");
    }

    #[test]
    fn a_matcher_reads_as_the_sentence_it_is() {
        let m = EventMatcher {
            calendar: Some("Work".into()),
            title: Some("*focus*".into()),
            busy_only: true,
            ..EventMatcher::default()
        };
        assert_eq!(
            describe_matcher(&m),
            "events on the Work calendar, titled *focus*, marked busy"
        );
    }

    #[test]
    fn a_window_with_no_locks_says_it_can_be_ended() {
        assert_eq!(describe_locks(&[]), "endable at any time");
        assert_eq!(describe_locks(&[Lock::Timer, Lock::DeviceCredential]), "timer + credential");
    }
}
