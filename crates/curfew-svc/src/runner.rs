//! The loop the service runs, and the channel it answers on.
//!
//! Nothing here decides anything. The loop's whole job is to call the pass on a timer, persist what
//! the pass produced, and hand control messages to the enforcer — so that the interesting parts
//! stay in `curfew-win`, where they are tested without a machine.

use curfew_core::Config;
use curfew_win::ipc::{encode, parse_request, Response};
use curfew_win::state::{self, Loaded, Persisted};
use curfew_win::{hosts, procs::SystemProcesses, Enforcer};
use interprocess::local_socket::traits::ListenerExt as _;
use interprocess::local_socket::{GenericNamespaced, ListenerOptions, ToNsName};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// The control channel's name and the client that speaks on it, both owned by `curfew-win` so the
/// service and everything that talks to it cannot disagree about either.
pub use curfew_win::ipc::{ask, SOCKET};

/// How often a pass runs.
///
/// Two seconds is a compromise with a reason on each side: a blocked application that stays open
/// for a quarter of a minute has effectively not been blocked, and a pass that runs every 200ms
/// spends a laptop's battery enumerating processes nobody started.
pub const TICK: Duration = Duration::from_secs(2);

/// Where the machine's config lives. `%ProgramData%` rather than the user's profile: the config is
/// what the locks are made of, so a standard user must not be able to rewrite it.
pub fn config_path() -> PathBuf {
    let root = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".to_string());
    Path::new(&root).join("Curfew").join("curfew.toml")
}

/// Where the hosts file is, honouring an override.
///
/// The override exists so Curfew can be run end to end without administrator rights and without
/// touching the machine's real name resolution — which is how it is developed and how its
/// integration tests run. It is read from the *service's* environment, so on an installed service
/// it is under the same protection as the service itself.
pub fn hosts_path() -> PathBuf {
    match std::env::var("CURFEW_HOSTS") {
        Ok(path) if !path.is_empty() => PathBuf::from(path),
        _ => hosts::default_path(),
    }
}

pub fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Build the enforcer the service will run, restoring whatever the last run left behind.
///
/// A config that will not parse is fatal *only* on a fresh start with no sessions: if locks are
/// running, the service carries on enforcing the config it cannot re-read rather than releasing
/// them, because "break the config file" must not be a way out.
pub fn build(
    config_path: &Path,
    state_path: &Path,
    hosts_path: PathBuf,
) -> Result<Enforcer, String> {
    let config = std::fs::read_to_string(config_path)
        .map_err(|e| e.to_string())
        .and_then(|text| Config::from_toml(&text).map_err(|e| e.to_string()));

    let loaded = state::load(state_path);
    let (persisted, warning) = match loaded {
        Loaded::Ok(state) => (state, None),
        Loaded::Fresh => (Persisted::default(), None),
        Loaded::Recovered { state, detail } => (
            state,
            Some(format!("Curfew's saved state was unreadable and was recovered from its backup ({detail}). Running locks were kept.")),
        ),
        Loaded::Lost { detail } => (
            Persisted::default(),
            Some(format!("Curfew's saved state and its backup were both unreadable ({detail}). Any locks that were running have been lost.")),
        ),
    };

    let config = match config {
        Ok(config) => config,
        Err(detail) if persisted.sessions.running.is_empty() => return Err(detail),
        // Locks are running and the config is gone. Nothing here can be enforced by rule any more,
        // but the sessions still exist and still have to be honoured, so the service starts with an
        // empty config and says so rather than quietly releasing them.
        Err(detail) => {
            eprintln!("curfew: config unreadable while locks are running ({detail})");
            Config::default()
        }
    };

    let mut enforcer = Enforcer::new(config, hosts_path);
    enforcer.sessions = persisted.sessions;
    enforcer.usage = persisted.usage;
    enforcer.launches = persisted.launches;
    enforcer.config_path = Some(config_path.to_path_buf());
    enforcer.state_warning = warning;
    Ok(enforcer)
}

/// Where the machine's own DNS settings are recorded while Curfew is holding them.
pub fn dns_record() -> PathBuf {
    state::default_path().with_file_name("dns-before.json")
}

/// Start the local resolver if the config asks for one, and point the machine at it.
fn start_resolver(config: &Config) -> Option<curfew_win::dns::Proxy> {
    if !config.resolver.enabled {
        return None;
    }
    let upstream = match config.resolver.upstream.parse() {
        Ok(upstream) => upstream,
        Err(e) => {
            eprintln!("curfew: {} is not a resolver address ({e})", config.resolver.upstream);
            return None;
        }
    };
    match curfew_win::dns::Proxy::start(curfew_win::dns::LISTEN, upstream) {
        Ok(mut proxy) => {
            if let Err(e) = proxy.take_over(Some(dns_record())) {
                eprintln!("curfew: the resolver is running but nothing is asking it ({e})");
            }
            Some(proxy)
        }
        Err(e) => {
            eprintln!(
                "curfew: could not start the resolver ({e}). Something else is answering DNS on                  this machine. The hosts file is still blocking the exact names."
            );
            None
        }
    }
}

/// Where this machine keeps its sync identity, its peers and its log: under `%ProgramData%`
/// beside the config and the state, for the same reason those are there. A standard user must not
/// be able to hand themselves a new identity, delete the peer that holds their locks, or truncate
/// the log.
pub fn sync_root() -> PathBuf {
    match std::env::var("CURFEW_SYNC_DIR") {
        Ok(path) if !path.is_empty() => PathBuf::from(path),
        _ => {
            let data = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".into());
            Path::new(&data).join("Curfew").join("sync")
        }
    }
}

/// Bring sync up: identity, peers and log from disk, and a node listening on the LAN.
///
/// Every failure here is survivable and none of them may stop enforcement. A machine with no
/// network, a locked-down LAN, or a sync directory it cannot write is still a machine that has to
/// keep its own locks; sync is how the *other* device finds out, and a device that refused to block
/// because it could not gossip would have the priorities exactly backwards.
fn start_sync() -> Option<(curfew_sync::node::Node, PathBuf)> {
    let root = sync_root();
    let name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "this pc".into());
    let (shared, complaints) = match curfew_sync::store::open(&root, &name) {
        Ok(opened) => opened,
        Err(e) => {
            eprintln!("curfew: sync is off ({e}). This device still enforces its own locks.");
            return None;
        }
    };
    for complaint in complaints {
        eprintln!("curfew: {complaint}");
    }
    match curfew_sync::node::Node::start(shared) {
        Ok(node) => Some((node, root)),
        Err(e) => {
            eprintln!("curfew: sync is off ({e}). This device still enforces its own locks.");
            None
        }
    }
}

fn persist(enforcer: &Enforcer, state_path: &Path, last_tick: i64) {
    let snapshot = Persisted {
        sessions: enforcer.sessions.clone(),
        usage: enforcer.usage.clone(),
        launches: enforcer.launches.clone(),
        last_tick: Some(last_tick),
    };
    if let Err(e) = state::save(state_path, &snapshot) {
        eprintln!("curfew: could not save state: {e}");
    }
}

/// Serve control messages until the process ends. One connection, one request, one line back.
fn serve(enforcer: Arc<Mutex<Enforcer>>) -> std::io::Result<()> {
    let name = SOCKET.to_ns_name::<GenericNamespaced>()?;
    let listener = ListenerOptions::new().name(name).create_sync()?;
    for connection in listener.incoming() {
        let Ok(stream) = connection else { continue };
        let mut reader = BufReader::new(stream);
        let mut line = String::new();
        if reader.read_line(&mut line).is_err() {
            continue;
        }
        let response = match parse_request(&line) {
            Ok(request) => enforcer.lock().expect("enforcer").handle(now(), request),
            Err(detail) => Response::Error { detail },
        };
        let mut stream = reader.into_inner();
        let _ = stream.write_all(encode(&response).as_bytes());
        let _ = stream.flush();
    }
    Ok(())
}

/// Run the loop until `stop` says otherwise. `stop` is how the service control manager asks us to
/// end; it is checked between passes rather than mid-pass so the hosts file is never left half
/// written.
pub fn run(
    enforcer: Enforcer,
    state_path: PathBuf,
    stop: impl Fn() -> bool,
    last_tick: Option<i64>,
    guarded: bool,
) {
    let enforcer = Arc::new(Mutex::new(enforcer));
    {
        let served = Arc::clone(&enforcer);
        std::thread::spawn(move || {
            if let Err(e) = serve(served) {
                eprintln!("curfew: control channel unavailable: {e}");
            }
        });
    }

    // The watchdog is only worth having when there is a service for it to restart, so a console run
    // does without one.
    let mut watchdog = match guarded {
        true => crate::watchdog::spawn().map_err(|e| eprintln!("curfew: no watchdog: {e}")).ok(),
        false => None,
    };

    // The resolver, when the config asks for one. A failure to bind is reported and then lived
    // with: the hosts file is still enforcing the exact names, so this degrades the block rather
    // than ending it, and a service that refused to start over it would be worse than the gap.
    let resolver = {
        let guard = enforcer.lock().expect("enforcer");
        start_resolver(&guard.config)
    };

    // Sync, if this machine can have it. Held for the life of the loop: dropping the node stops
    // its threads, which is exactly what should happen when the service stops.
    let sync = start_sync();
    let mut mirror = curfew_sync::mirror::Mirror::default();

    // Calendar subscriptions. Cached beside the state file so an outage — or a restart during one —
    // does not release a block the calendar was driving.
    let subscriptions = crate::feeds::Subscriptions::default();
    let mut feeds = curfew_win::calendar::Feeds::new(
        state_path.parent().unwrap_or_else(|| Path::new(".")).join("calendars"),
    );
    {
        let guard = enforcer.lock().expect("enforcer");
        feeds.restore(&guard.config.calendar_sources);
    }

    let mut previous = last_tick;
    while !stop() {
        let now = now();
        // Elapsed is clamped to the tick interval. A gap larger than that is the machine having
        // been asleep or off, and time the machine was off is not time the user spent on anything:
        // charging it to a budget would empty an allowance overnight.
        let elapsed = match previous {
            Some(before) if now > before => (now - before).min(TICK.as_secs() as i64) as u32,
            _ => 0,
        };
        {
            let mut guard = enforcer.lock().expect("enforcer");
            let events = match guard.config.tz() {
                Ok(zone) => {
                    let sources = guard.config.calendar_sources.clone();
                    let (events, outcomes) = feeds.events(now, &sources, zone, &subscriptions);
                    for outcome in outcomes {
                        if let curfew_win::calendar::Outcome::Failed { id, detail, still_serving } =
                            outcome
                        {
                            // Reported every time rather than once: a subscription that has been
                            // failing for a week is worth being noisy about, and the alternative is
                            // a block quietly running on a stale calendar with nobody told.
                            eprintln!(
                                "curfew: calendar '{id}' could not be read ({detail}){}",
                                if still_serving {
                                    "; the last copy that worked is still in force"
                                } else {
                                    "; it has never been read, so it is blocking nothing"
                                }
                            );
                        }
                    }
                    events
                }
                Err(_) => Vec::new(),
            };
            let tick = guard.tick(now, elapsed, &events, &SystemProcesses::default());
            if let Some(resolver) = &resolver {
                resolver.set(tick.domains.clone());
            }
            // Sync runs after the pass, not before it: what the other device is told is what this
            // one has just decided, and what it hears back is enforced on the very next pass two
            // seconds later, which is what keeps the five-second promise.
            if let Some((node, root)) = &sync {
                let Enforcer { sessions, usage, launches, .. } = &mut *guard;
                let pass = mirror.pass(node.shared(), now, sessions, usage, launches);
                if pass.published > 0 {
                    node.push_all(now);
                    if let Err(e) = curfew_sync::store::save(root, node.shared()) {
                        eprintln!("curfew: could not save the sync log: {e}");
                    }
                }
            }
            persist(&guard, &state_path, now);
        }
        // The other half of the pair: killing the watchdog is as obvious an attack as killing the
        // service, so the service starts it again the moment it notices it has gone.
        if guarded {
            let dead = watchdog.as_mut().map(|c| c.try_wait().map(|s| s.is_some()).unwrap_or(true));
            if dead != Some(false) {
                watchdog = crate::watchdog::spawn().ok();
            }
        }

        previous = Some(now);
        std::thread::sleep(TICK);
    }

    // A clean stop gives the hosts file back only when nothing is running. A service stopped while
    // a lock is held is a bypass attempt, and leaving the block in place is the correct answer:
    // the watchdog will start us again.
    let guard = enforcer.lock().expect("enforcer");
    if guard.sessions.running.is_empty() {
        let _ = hosts::clear(&guard.hosts_path);
    }
}
