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

/// How many ticks pass before an unpaired device looks again for a pairing — see [`start_sync`].
const SYNC_RETRY_TICKS: u32 = 15;

/// Where the machine's config lives. `%ProgramData%` rather than the user's profile: the config is
/// what the locks are made of, so a standard user must not be able to rewrite it.
pub fn config_path() -> PathBuf {
    let root = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".to_string());
    Path::new(&root).join("Curfew").join("curfew.toml")
}

/// The config a machine starts life with, written the first time one is needed.
///
/// A fresh install used to have no config at all: the service started, found nothing at
/// `config_path()`, and stopped with "The system cannot find the file specified" — which tells a
/// person who has just installed a blocker precisely nothing about what to do next. There is also
/// nowhere else for a first profile to come from, since every editing command refuses to write a
/// config that is not already there.
///
/// So the file is created once, with one profile that blocks the handful of sites people actually
/// name when asked what eats their evening, and no schedule and no session: it enforces nothing
/// until someone asks it to, and it gives every later command something to edit.
pub const STARTER_CONFIG: &str = r#"# Curfew's config. Everything the locks are made of lives here.
# Edit it with `curfew` commands, or by hand — `curfew check` will tell you if it is wrong.
schema_version = 1

[[profiles]]
id = "distractions"
name = "Distractions"
description = "The usual suspects. Add or remove whatever you like."

[[profiles.rules]]
target = { kind = "domain", domain = "youtube.com" }
action = { kind = "block" }

[[profiles.rules]]
target = { kind = "domain", domain = "reddit.com" }
action = { kind = "block" }

[[profiles.rules]]
target = { kind = "domain", domain = "x.com" }
action = { kind = "block" }
"#;

/// Write [`STARTER_CONFIG`] to `path` if nothing is there yet. An existing config is never touched.
pub fn ensure_config(path: &Path) -> std::io::Result<bool> {
    if path.exists() {
        return Ok(false);
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, STARTER_CONFIG)?;
    Ok(true)
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

/// The machine's own wall clock, exactly as it reports it. **Untrusted.**
///
/// This is what a person would read off the taskbar, and it is user-settable, so it must never be
/// the time a lock is judged against. Enforcement takes its instant from
/// [`curfew_win::Enforcer::observe_clock`], which refuses any part of this that the monotonic uptime
/// does not support. Kept for the two honest uses — reporting to a human, and stamping a log line —
/// and named so that reading it in an enforcement path is a visible mistake rather than an
/// invisible one.
pub fn wall_now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Where the last config that parsed is kept, beside the state file.
///
/// Same idea as `state.json.locked` and the calendar cache: a companion file that exists so a failure
/// of the real one does not become a failure of enforcement.
pub fn config_backup_path(state_path: &Path) -> PathBuf {
    state_path.with_file_name("curfew.toml.good")
}

/// Build the enforcer the service will run, restoring whatever the last run left behind.
///
/// A config that will not parse is fatal *only* on a fresh start with no sessions: if locks are
/// running, the service carries on enforcing rather than releasing them, because "break the config
/// file" must not be a way out.
///
/// **And "carries on enforcing" used to mean an empty config — P1-10.** The old code fell back to
/// `Config::default()`, which keeps the sessions and honours their locks while enforcing *none of
/// their rules*: every domain, app, path and budget behind them stops, and the only signal is a line
/// on stderr of a service nobody is reading. That is fail-open on the config file, and the review is
/// right that it is the same class as the other ways out this branch has closed.
///
/// The fallback is now [`config_backup_path`] — the last config that *did* parse, written on every
/// successful load. An empty config remains the last resort, because a machine whose config and
/// config-backup are both unreadable still has to start and still has to honour the sessions it is
/// holding; but it is no longer the *first* answer, and it comes with a warning that says which rules
/// are not being enforced rather than one that only says the file was unreadable.
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
        Ok(config) => {
            // Remember it while it is good. Best-effort: a machine that cannot write this still runs,
            // it just has no fallback if the config later breaks.
            if let Err(e) =
                std::fs::write(config_backup_path(state_path), config.to_toml().unwrap_or_default())
            {
                crate::warn!("could not keep a copy of the config ({e})");
            }
            config
        }
        Err(detail) if persisted.sessions.running.is_empty() => return Err(detail),
        Err(detail) => {
            let backup = config_backup_path(state_path);
            match std::fs::read_to_string(&backup)
                .map_err(|e| e.to_string())
                .and_then(|text| Config::from_toml(&text).map_err(|e| e.to_string()))
            {
                Ok(config) => {
                    // The good case: locks keep running *and* keep being enforced by the rules they
                    // were started under. Said out loud, because the user's edit is not in force and
                    // nothing else would tell them.
                    crate::warn!(
                        "config unreadable while locks are running ({detail}); enforcing the \
                         last config that parsed ({})",
                        backup.display()
                    );
                    config
                }
                Err(also) => {
                    crate::warn!(
                        "config unreadable while locks are running ({detail}), and the last \
                         good copy is unusable too ({also}). The sessions are still held, but no \
                         rule is being enforced."
                    );
                    Config::default()
                }
            }
        }
    };

    let mut enforcer = Enforcer::new(config, hosts_path);
    enforcer.sessions = persisted.sessions;
    enforcer.usage = persisted.usage;
    enforcer.launches = persisted.launches;
    enforcer.passes = persisted.passes;
    enforcer.boots = persisted.boots;
    enforcer.boot_counter = persisted.boot_counter;
    // The clock's baseline comes back with it. Without this, stopping the service, moving the clock
    // and starting it again would be a way out of every timer lock: the witness would begin life
    // trusting whatever the machine then claimed.
    enforcer.clock = persisted.clock;
    enforcer.releases = persisted.releases;
    enforcer.history = persisted.history;
    // A session that ended while the service was stopped has to be noticed by the first pass, so
    // the pass needs to know what was running when the service went away.
    enforcer.watch_sessions();
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
            crate::note!("{} is not a resolver address ({e})", config.resolver.upstream);
            return None;
        }
    };
    match curfew_win::dns::Proxy::start(curfew_win::dns::LISTEN, upstream) {
        Ok(mut proxy) => {
            if let Err(e) = proxy.take_over(Some(dns_record())) {
                crate::note!("the resolver is running but nothing is asking it ({e})");
            }
            Some(proxy)
        }
        Err(e) => {
            crate::warn!("could not start the resolver ({e}). Something else is answering DNS on                  this machine. The hosts file is still blocking the exact names."
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
///
/// **Nothing is bound until this device is paired with something.** The node listens on a TCP port
/// and a UDP beacon on every interface, and the first time it does, Windows Defender Firewall puts
/// an administrator prompt on screen — on a machine that has no peer to talk to, about traffic that
/// would go nowhere. Someone without an administrator password cannot answer it and someone who
/// can is being asked to allow a listener for a feature they have not switched on. So an unpaired
/// device binds nothing at all, and [`resume_sync`] brings the node up on the pass after the first
/// pairing lands.
/// The outcome of trying to bring sync up: a node, or the reason there is none.
///
/// **An `Option` could not tell two opposite things apart.** `start_sync` returned `None` both for
/// "nothing is paired, which is normal" and for "the sync directory is not writable, which is not",
/// and those want opposite things from the user: the first needs no action, the second does, and only
/// one of them belongs on a screen. Carrying the reason is what lets [`curfew_win::ipc::Status`] say
/// which — and until this existed the Windows window said nothing about sync at all.
enum SyncStart {
    /// Listening, with this many peers on disk and this root to save against.
    Up(curfew_sync::node::Node, PathBuf, usize),
    /// Nothing paired. Ordinary: not an error, not worth a warning, nothing to do.
    Unpaired,
    /// It did not start, and this is why — a sentence fit to show a user.
    Failed(String),
}

impl SyncStart {
    /// What to publish on every status.
    ///
    /// `nearby` is passed in because only the caller can ask the node, and asking it every pass is the
    /// only way the answer stays true: devices come and go from a LAN without telling anyone.
    fn state(&self, nearby: usize) -> curfew_win::ipc::SyncState {
        use curfew_win::ipc::SyncState;
        match self {
            SyncStart::Up(_, _, paired) => {
                SyncState { running: true, paired: *paired, nearby, why_off: None }
            }
            SyncStart::Unpaired => {
                SyncState { running: false, paired: 0, nearby: 0, why_off: None }
            }
            // `paired: 0` rather than a guess: `store::open` failed, so the peers on disk are exactly
            // what could not be read. Reporting a count here would be inventing one.
            SyncStart::Failed(why) => {
                SyncState { running: false, paired: 0, nearby: 0, why_off: Some(why.clone()) }
            }
        }
    }
}

fn start_sync() -> SyncStart {
    let root = sync_root();
    let name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "this pc".into());
    let (shared, complaints) = match curfew_sync::store::open(&root, &name) {
        Ok(opened) => opened,
        Err(e) => {
            crate::note!("sync is off ({e}). This device still enforces its own locks.");
            return SyncStart::Failed(e.to_string());
        }
    };
    for complaint in complaints {
        crate::note!("{complaint}");
    }
    let paired = shared.peers.lock().map(|peers| peers.active_ids().count()).unwrap_or(0);
    if paired == 0 {
        // Not an error and not worth a warning: this is simply a device that has not been paired.
        return SyncStart::Unpaired;
    }
    match curfew_sync::node::Node::start(shared) {
        Ok(node) => SyncStart::Up(node, root, paired),
        Err(e) => {
            crate::note!("sync is off ({e}). This device still enforces its own locks.");
            SyncStart::Failed(e.to_string())
        }
    }
}

fn persist(enforcer: &Enforcer, state_path: &Path, last_tick: i64) {
    let snapshot = Persisted {
        sessions: enforcer.sessions.clone(),
        usage: enforcer.usage.clone(),
        launches: enforcer.launches.clone(),
        passes: enforcer.passes.clone(),
        boots: enforcer.boots.clone(),
        boot_counter: enforcer.boot_counter.clone(),
        clock: enforcer.clock.clone(),
        releases: enforcer.releases.clone(),
        history: enforcer.history.clone(),
        last_tick: Some(last_tick),
    };
    if let Err(e) = state::save(state_path, &snapshot) {
        crate::warn!("could not save state: {e}");
    }
}

/// Who may talk to the control pipe.
///
/// The service runs as SYSTEM, and a pipe SYSTEM creates with the default descriptor is one only
/// SYSTEM and administrators can open. That is wrong for this pipe: the tray, the overlay and the
/// command line all run as the ordinary logged-in user, and without this they are told "Windows
/// refused this program access to the Curfew service" while enforcement carries on without them.
///
/// The grant is deliberate rather than lax. Everything expressible on this channel is something an
/// unprivileged user of this machine may already do — there is no "stop enforcing" message, and
/// ending a session goes through the core's refusal — so handing the interactive user read and
/// write is not handing them a way out of a lock.
///
/// Two narrowings that matter:
///
/// - **`IU`, not `AU` or `WD`.** Interactive excludes network logons, and a named pipe is
///   reachable over the network through `IPC$` by an authenticated remote user. Nobody signed in
///   from another machine has business ending a block on this one.
/// - **`0x12019B`, not `GA`.** That is file generic read plus generic write with
///   `FILE_CREATE_PIPE_INSTANCE` (`0x4`) masked off. With that bit a user could create a second
///   instance of `\.\pipe\curfew.sock` and answer for the service — a fake "no session is
///   running" is exactly what someone trying to get out of a block would want to say.
#[cfg(windows)]
const PIPE_SDDL: &str = "D:(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;IU)";

/// The listener, with the descriptor above where the platform has one.
#[cfg(windows)]
fn control_listener() -> std::io::Result<interprocess::local_socket::Listener> {
    use interprocess::os::windows::local_socket::ListenerOptionsExt as _;
    use interprocess::os::windows::security_descriptor::SecurityDescriptor;

    let sddl = widestring::U16CString::from_str(PIPE_SDDL).map_err(std::io::Error::other)?;
    let descriptor = SecurityDescriptor::deserialize(&sddl)?;
    let name = SOCKET.to_ns_name::<GenericNamespaced>()?;
    ListenerOptions::new().name(name).security_descriptor(descriptor).create_sync()
}

#[cfg(not(windows))]
fn control_listener() -> std::io::Result<interprocess::local_socket::Listener> {
    let name = SOCKET.to_ns_name::<GenericNamespaced>()?;
    ListenerOptions::new().name(name).create_sync()
}

/// The largest request the service will read, in bytes.
///
/// A request is one line of JSON naming a session id and a handful of fields; a generous page is
/// plenty. The cap exists because `read_line` into an unbounded `String` is a SYSTEM process
/// allocating whatever a client tells it to, and the client does not have to be privileged.
pub const MAX_REQUEST: u64 = 64 * 1024;

/// How many control connections may be in flight at once.
///
/// The legitimate clients are the tray, the window, the command line and one native-messaging host
/// per browser, so this is far above anything real and far below anything that would matter. Its job
/// is to bound how many threads a hostile client can make the service hold.
const MAX_CONNECTIONS: usize = 32;

/// Read one request line, stopping at [`MAX_REQUEST`].
///
/// Split out from the socket so it can be tested on a byte slice: the property that matters — an
/// oversized request is *truncated and refused*, never allocated — is about this function and not
/// about the transport.
///
/// Written as a `fill_buf`/`consume` loop rather than `read_line`, because `read_line` into a `String`
/// grows without limit and this runs as SYSTEM. Nothing here allocates more than the cap in total,
/// and a line that reaches the cap is returned truncated — which fails to parse, which is answered
/// with an error, which is the right outcome and needs no separate length check.
fn read_request(reader: &mut impl BufRead) -> std::io::Result<String> {
    const CAP: usize = MAX_REQUEST as usize;
    let mut bytes: Vec<u8> = Vec::new();
    loop {
        let room = CAP - bytes.len();
        if room == 0 {
            break;
        }
        // The borrow of the buffer has to end before `consume`, so the useful parts are copied out
        // first. One bounded copy per buffer refill, against an unbounded allocation.
        let (chunk, consumed, done) = {
            let available = reader.fill_buf()?;
            if available.is_empty() {
                break; // the client closed without finishing a line
            }
            let window = &available[..available.len().min(room)];
            match window.iter().position(|b| *b == b'\n') {
                Some(at) => (window[..=at].to_vec(), at + 1, true),
                None => (window.to_vec(), window.len(), window.len() == room),
            }
        };
        bytes.extend_from_slice(&chunk);
        reader.consume(consumed);
        if done {
            break;
        }
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

/// Serve control messages until the process ends. One connection, one request, one line back.
///
/// **One thread per connection**, and that is a correctness fix rather than a convenience. This used
/// to be a single-threaded loop, so the channel had one slot for every client on the machine and a
/// client that connected and never sent a newline held it for ever: the tray, the window, the command
/// line and every browser host all went unanswered until that connection went away, and nothing made
/// it go away. The last-resort exit — `curfew release`, the 24-hour delayed release §11 of the
/// architecture calls the thing that separates a commitment device from a trap — is issued over this
/// same channel, so wedging it locked the user out of their own way out.
///
/// **What this does not fix, and why.** A wedged client still occupies its own thread, because a
/// read deadline cannot be set on these streams: `interprocess`'s Windows named-pipe stream returns
/// `Unsupported` for `set_read_timeout`. Bounding the *count* is what removes the total-wedge
/// property — the accept loop and every other slot keep working — and a supervisor that closed the
/// stream from another thread would need `CancelIoEx` on a handle this crate does not expose.
/// Recorded here rather than discovered later.
fn serve(enforcer: Arc<Mutex<Enforcer>>) -> std::io::Result<()> {
    let listener = control_listener()?;
    // **The same counted limit the window uses for its calls to this service** (`curfew_win::capacity`).
    // It was a hand-written `AtomicUsize` here and a second one there, which is two places for the
    // release to be written and the branch's recurring lesson is that one of them will be wrong.
    let live = curfew_win::capacity::Capacity::new(MAX_CONNECTIONS);
    for connection in listener.incoming() {
        let Ok(stream) = connection else { continue };
        // Dropping the stream closes it. An answer would be kinder, but a client that has been refused
        // for queueing too many connections is not one this service owes a sentence to.
        let Some(permit) = live.take() else { continue };
        let served = Arc::clone(&enforcer);
        std::thread::spawn(move || {
            // **Held by a `Drop` guard, not released by a statement.** This read
            // `held.fetch_sub(1, ...)` *after* `answer_one(...)`, under a comment saying "released
            // whatever happened, so a panic in the handler cannot leak a slot for ever" — and a panic
            // unwinds straight past that line, so the slot *was* leaked. `answer_one` takes a `Mutex`
            // with `.expect(...)`, which panics on a poisoned lock, and then runs a large `handle`.
            // After `MAX_CONNECTIONS` such panics the service accepted no connections at all — the
            // total wedge the slot was introduced to prevent, arrived at through the slot itself.
            let _permit = permit;
            answer_one(stream, &served);
        });
    }
    Ok(())
}

/// Read one request from one connection and write one reply. Never panics out: the caller's slot
/// count depends on this returning.
fn answer_one(stream: interprocess::local_socket::Stream, enforcer: &Mutex<Enforcer>) {
    let mut reader = BufReader::new(stream);
    let Ok(line) = read_request(&mut reader) else { return };
    let response = match parse_request(&line) {
        Ok(request) => {
            let mut guard = enforcer.lock().expect("enforcer");
            // Trusted time, not the wall clock. `can_release` grants a release as soon as
            // `is_expired(now)` holds, so answering `End` with a wall clock the user has wound
            // forward would end a timer lock just as surely as claiming the timer had run out — the
            // same bypass through a different door.
            let now = guard.observe_clock(wall_now(), curfew_win::windows::uptime_seconds());
            guard.handle(now, request)
        }
        Err(detail) => Response::Error { detail },
    };
    let mut stream = reader.into_inner();
    let _ = stream.write_all(encode(&response).as_bytes());
    let _ = stream.flush();
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
                crate::note!("control channel unavailable: {e}");
            }
        });
    }

    // The watchdog is only worth having when there is a service for it to restart, so a console run
    // does without one.
    let mut watchdog = match guarded {
        true => crate::watchdog::spawn().map_err(|e| crate::warn!("no watchdog: {e}")).ok(),
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
    let mut sync = start_sync();
    // When the node did not start because nothing is paired yet, look again now and then rather
    // than making the user restart the service after pairing. Half a minute is far below anything
    // a person would notice and far above anything this costs.
    let mut sync_retry = 0u32;
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

    // What the other devices' calendars said on the previous pass. Held across passes because sync
    // runs after enforcement: a meeting the phone can see reaches this machine's rules two seconds
    // later, which is the same latency everything else in the mirror has.
    let mut peer_events: Vec<curfew_core::CalendarEvent> = Vec::new();
    let mut previous = last_tick;
    while !stop() {
        // Trusted time, and the only time this loop may judge anything against.
        //
        // The wall clock is read here and handed to the witness, which refuses whatever the
        // monotonic uptime does not support. Reading `SystemTime` directly was the cheapest bypass
        // in the product: setting the clock forward ended every timer lock, gave every blocked name
        // back, and emptied the resolver — and it left no trace, because the ended session was
        // written to history as one that had genuinely run out. Its own short lock, so the rest of
        // the pass is unchanged by it.
        let now = {
            let mut guard = enforcer.lock().expect("enforcer");
            let now = guard.observe_clock(wall_now(), curfew_win::windows::uptime_seconds());
            // **The window enforcement was down, recorded on the first pass** — P1-8. Here rather than
            // at startup because `now` has to be the *trusted* instant: measuring the gap against a
            // wall clock somebody may have moved would make the reported window fiction. `note_start`
            // is a no-op after the first call, so the loop costs one branch per pass.
            let gap = guard.note_start(previous, now, curfew_win::windows::uptime_seconds());
            // And said out loud, once, into the log the service now keeps (P1-12). The notice on
            // `Status` is for whoever is looking at the app; this is for whoever is looking at the
            // machine a week later, and the architecture's promise is about the record as much as the
            // banner.
            if let Some(downtime) = gap {
                crate::warn!("{}", downtime.describe());
            }
            now
        };
        // Elapsed is clamped to the tick interval. A gap larger than that is the machine having
        // been asleep or off, and time the machine was off is not time the user spent on anything:
        // charging it to a budget would empty an allowance overnight.
        let elapsed = match previous {
            Some(before) if now > before => (now - before).min(TICK.as_secs() as i64) as u32,
            _ => 0,
        };
        // **The calendar fetch happens here, outside the enforcer lock** — P1-11.
        //
        // `feeds.events` performs HTTP with a twenty-second timeout (`feeds::TIMEOUT`), against a
        // two-second tick, and it used to be called while holding the mutex that `serve()` needs to
        // answer anything at all. So one slow subscription stalled the whole control channel —
        // `Status`, and the 24-hour `release` with it — for up to twenty seconds, and any local user
        // could arrange that by pointing a subscription at a host that black-holes packets.
        //
        // The lock is taken twice instead: once briefly for the two values the fetch needs, and then
        // the real pass below. `sources` and `zone` are both `Clone`/`Copy` and neither can change
        // under us, because the only writer of the config is this loop.
        let events = {
            let (sources, zone) = {
                let guard = enforcer.lock().expect("enforcer");
                (guard.config.calendar_sources.clone(), guard.config.tz())
            };
            match zone {
                Ok(zone) => {
                    let (events, outcomes) = feeds.events(now, &sources, zone, &subscriptions);
                    for outcome in outcomes {
                        if let curfew_win::calendar::Outcome::Failed { id, detail, still_serving } =
                            outcome
                        {
                            // Reported every time rather than once: a subscription that has been
                            // failing for a week is worth being noisy about, and the alternative is
                            // a block quietly running on a stale calendar with nobody told.
                            crate::warn!(
                                "calendar '{id}' could not be read ({detail}){}",
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
            }
        };

        {
            let mut guard = enforcer.lock().expect("enforcer");
            // Only what this machine saw is ever published; the peers' events are merged in for
            // enforcement only, so a calendar cannot be echoed back and forth between devices.
            let mine = events.clone();
            let mut events = events;
            events.extend(peer_events.iter().cloned());
            events.sort_by(|a, b| a.start.cmp(&b.start).then_with(|| a.id.cmp(&b.id)));
            // No `observe_boot` here: `observe_clock` above already folded this pass's uptime in,
            // and it has to, because the witness compares its reading against the boot id. Doing it
            // twice would be harmless but would suggest the ordering does not matter, and it does.
            let tick = guard.tick(now, elapsed, &events, &SystemProcesses::default());
            if let Some(resolver) = &resolver {
                resolver.set(tick.domains.clone());
            }
            // Sync runs after the pass, not before it: what the other device is told is what this
            // one has just decided, and what it hears back is enforced on the very next pass two
            // seconds later, which is what keeps the five-second promise.
            if let SyncStart::Up(node, root, _) = &sync {
                let calendars = guard.config.calendars.clone();
                // Passes go out before the merge comes back, so a pass spent on this device in
                // the last two seconds is in the log the other device reads, and the ration this
                // device then adopts already counts it.
                let said_passes = mirror.publish_passes(node.shared(), now, &guard.passes);
                // A release goes out before the merge comes back for the same reason a pass does:
                // the device whose lock it satisfies should hear about it on its next pass, not on
                // the one after.
                let said_releases =
                    mirror.publish_releases(node.shared(), now, &guard.releases.clone());
                let Enforcer { sessions, usage, launches, .. } = &mut *guard;
                let pass = mirror.pass(node.shared(), now, sessions, usage, launches);
                guard.passes = pass.passes.clone();
                guard.released = pass.released.clone();
                guard.device_id = node.shared().identity.id().as_str().to_string().into();
                peer_events = pass.calendar.clone();
                // Only the events some rule here would act on. A lock does not need to know the
                // name of every meeting in someone's week to do its job, and the log is smaller and
                // duller for it.
                let matched: Vec<curfew_core::CalendarEvent> = mine
                    .iter()
                    .filter(|event| {
                        calendars.iter().any(|schedule| schedule.matcher.matches(event))
                    })
                    .cloned()
                    .collect();
                let said = mirror.publish_calendar(node.shared(), now, &matched);
                if pass.published + said + said_passes + said_releases > 0 {
                    node.push_all(now);
                    if let Err(e) = curfew_sync::store::save(root, node.shared()) {
                        crate::warn!("could not save the sync log: {e}");
                    }
                }
            }
            // Publish what sync is doing, whether or not it is up. `nearby` is asked of the node
            // rather than remembered, because devices leave a network without ever saying so.
            let nearby = match &sync {
                SyncStart::Up(node, _, _) => node.nearby(now).len(),
                _ => 0,
            };
            guard.sync = sync.state(nearby);
            persist(&guard, &state_path, now);
        }

        if !matches!(sync, SyncStart::Up(..)) {
            sync_retry += 1;
            if sync_retry >= SYNC_RETRY_TICKS {
                sync_retry = 0;
                sync = start_sync();
            }
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

#[cfg(test)]
mod read_request_tests {
    use super::{read_request, MAX_REQUEST};

    /// The ordinary case, and the frame delimiter is part of the line the parser gets.
    #[test]
    fn one_line_is_read_whole() {
        let mut input = &b"{\"request\":\"status\"}\n"[..];
        assert_eq!(read_request(&mut input).unwrap(), "{\"request\":\"status\"}\n");
    }

    /// A client that closes mid-line is answered with what it sent. It will fail to parse, and that
    /// is the correct outcome — the alternative is reading for ever.
    #[test]
    fn a_truncated_line_is_returned_as_what_arrived() {
        let mut input = &b"{\"request\":\"sta"[..];
        assert_eq!(read_request(&mut input).unwrap(), "{\"request\":\"sta");
    }

    /// The property the cap exists for: a request larger than the cap is **bounded**, not allocated.
    ///
    /// This is the DoS the unwritten `read_line` allowed — a client with no privileges making a
    /// SYSTEM process allocate whatever it was told to. The read stops at the cap, so what comes back
    /// is a truncated line that cannot parse, and the service answers an error instead of the machine
    /// running out of memory.
    #[test]
    fn an_oversized_request_is_truncated_rather_than_allocated() {
        let huge = format!("{{\"request\":\"{}\"}}\n", "a".repeat(MAX_REQUEST as usize * 2));
        let mut input = huge.as_bytes();
        let read = read_request(&mut input).unwrap();
        assert_eq!(
            read.len() as u64,
            MAX_REQUEST,
            "the read was not bounded by the cap, so the service allocated whatever it was sent"
        );
        // And a truncated request is not a request, which is what makes the cap safe rather than
        // merely smaller.
        assert!(
            curfew_win::ipc::parse_request(&read).is_err(),
            "an oversized request still parsed, so the cap does not refuse anything"
        );
    }

    /// A line that arrives split across several buffers is reassembled, because a socket has no
    /// obligation to deliver a request in one piece.
    #[test]
    fn a_line_split_across_reads_is_reassembled() {
        // A `BufRead` that hands out one byte at a time, which is the worst a socket may do.
        struct Dribble<'a>(&'a [u8]);
        impl std::io::Read for Dribble<'_> {
            fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
                if self.0.is_empty() || buf.is_empty() {
                    return Ok(0);
                }
                buf[0] = self.0[0];
                self.0 = &self.0[1..];
                Ok(1)
            }
        }
        impl std::io::BufRead for Dribble<'_> {
            fn fill_buf(&mut self) -> std::io::Result<&[u8]> {
                Ok(self.0)
            }
            fn consume(&mut self, amt: usize) {
                self.0 = &self.0[amt.min(self.0.len())..];
            }
        }

        let mut reader = Dribble(b"{\"request\":\"status\"}\n{\"request\":\"status\"}\n");
        assert_eq!(read_request(&mut reader).unwrap(), "{\"request\":\"status\"}\n");
        // The *second* request is still there and still whole: a bounded read must not eat the rest
        // of the stream, or a pipelined client would lose every message after the first.
        assert_eq!(read_request(&mut reader).unwrap(), "{\"request\":\"status\"}\n");
    }
}

#[cfg(all(test, windows))]
mod pipe_acl_tests {
    /// The descriptor is a string literal, and a typo in it would not fail the build — it would
    /// fail at the moment the service starts listening, on a machine, in a service, with the error
    /// going wherever service errors go. So it is parsed here instead.
    #[test]
    fn the_control_pipe_descriptor_parses() {
        use interprocess::os::windows::security_descriptor::SecurityDescriptor;
        let sddl = widestring::U16CString::from_str(super::PIPE_SDDL).unwrap();
        SecurityDescriptor::deserialize(&sddl).expect("the pipe's SDDL is not valid");
    }
}

/// P1-10: an unparseable config while locks are running must not stop enforcing the rules.
///
/// The old fallback was `Config::default()`, which keeps the sessions and honours their locks while
/// enforcing none of their rules — every domain, app, path and budget behind them stops, and the only
/// signal is a line on stderr of a service nobody reads. That is fail-open on the config file.
///
/// The fallback is now the last config that parsed, kept beside the state. These tests drive `build`
/// directly, which is why its three paths are parameters rather than the globals the service uses.
#[cfg(test)]
mod config_fallback_tests {
    use super::{build, config_backup_path};
    use curfew_core::Config;
    use curfew_win::state::{self, Persisted};
    use std::path::PathBuf;

    const CONFIG: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "domain", domain = "reddit.com" }
action = { kind = "block" }
"#;

    /// A scratch directory of its own, because these share `std::env::temp_dir()` with every other
    /// test in the binary and a leftover `curfew.toml.good` would make the "no fallback" case pass
    /// for the wrong reason.
    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("curfew-build-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// A locked machine on disk, so `build` takes the "locks are running" branch.
    fn write_locked_state(state_path: &std::path::Path) {
        let mut sessions = curfew_core::Sessions::default();
        sessions.start(curfew_core::Session {
            id: "s1".into(),
            profile: "deep-work".into(),
            source: curfew_core::session::SessionSource::Manual,
            started_at: 1_788_510_600,
            lock: curfew_core::LockSet::new([curfew_core::Lock::Timer], Some(1_788_513_600)),
        });
        state::save(state_path, &Persisted { sessions, ..Default::default() }).unwrap();
    }

    #[test]
    fn a_good_config_is_kept_for_later() {
        let dir = scratch("keeps-good");
        let config_path = dir.join("curfew.toml");
        let state_path = dir.join("state.json");
        std::fs::write(&config_path, CONFIG).unwrap();

        build(&config_path, &state_path, dir.join("hosts")).unwrap();

        let kept = config_backup_path(&state_path);
        assert!(kept.is_file(), "the last good config was not kept");
        assert!(Config::from_toml(&std::fs::read_to_string(&kept).unwrap()).is_ok());
    }

    /// **The finding.** Break the config while a lock runs and the rules must survive.
    #[test]
    fn a_broken_config_while_locked_still_enforces_the_last_good_one() {
        let dir = scratch("broken-while-locked");
        let config_path = dir.join("curfew.toml");
        let state_path = dir.join("state.json");
        std::fs::write(&config_path, CONFIG).unwrap();

        // One good start, which is what keeps the copy.
        build(&config_path, &state_path, dir.join("hosts")).unwrap();
        write_locked_state(&state_path);

        // Now the file is nonsense — and the old code started with `Config::default()`.
        std::fs::write(&config_path, "this is not toml = = =").unwrap();
        let enforcer = build(&config_path, &state_path, dir.join("hosts"))
            .expect("a broken config with locks running must not stop the service");

        assert_eq!(
            enforcer.config.profiles.len(),
            1,
            "the profile was lost, so nothing behind the lock is being enforced"
        );
        assert_eq!(
            enforcer.config.profiles[0].rules.len(),
            1,
            "the rule was lost, so the block is not running"
        );
        assert_eq!(enforcer.sessions.running.len(), 1, "the lock itself was dropped");
    }

    /// With nothing running, a broken config is still fatal: there is no promise to keep, and the
    /// user needs to know rather than have the service start enforcing nothing.
    #[test]
    fn a_broken_config_with_nothing_running_is_still_fatal() {
        let dir = scratch("broken-idle");
        let config_path = dir.join("curfew.toml");
        let state_path = dir.join("state.json");
        std::fs::write(&config_path, "not toml = = =").unwrap();

        assert!(build(&config_path, &state_path, dir.join("hosts")).is_err());
    }

    /// And when the backup is unusable too, the service still starts — a machine holding a lock must
    /// not refuse to run — but it is the last resort rather than the first answer.
    #[test]
    fn a_broken_config_with_no_usable_backup_still_starts() {
        let dir = scratch("no-fallback");
        let config_path = dir.join("curfew.toml");
        let state_path = dir.join("state.json");
        std::fs::write(&config_path, CONFIG).unwrap();
        build(&config_path, &state_path, dir.join("hosts")).unwrap();
        write_locked_state(&state_path);

        // Both the config and the kept copy are unusable.
        std::fs::write(&config_path, "not toml = = =").unwrap();
        std::fs::write(config_backup_path(&state_path), "also not toml").unwrap();

        let enforcer = build(&config_path, &state_path, dir.join("hosts"))
            .expect("a machine holding a lock must still start");
        assert!(enforcer.config.profiles.is_empty(), "there was nothing usable to enforce");
        assert_eq!(enforcer.sessions.running.len(), 1, "the lock itself was dropped");
    }
}
