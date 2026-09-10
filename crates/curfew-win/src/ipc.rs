//! What the tray and the CLI are allowed to ask the service.
//!
//! The service runs as SYSTEM and the things talking to it do not, so this boundary is a privilege
//! boundary: everything expressible here is something an unprivileged user on this machine may do.
//! That is why there is no "stop enforcing" message and no "delete session" message. The only exits
//! are [`Request::End`], which the core refuses unless the lock's conditions are satisfied, and
//! [`Request::RequestRelease`], which starts the 24-hour wait and can never be shortened.
//!
//! Framing is one JSON object per line over a named pipe. Boring on purpose: the wire format of a
//! local control channel is not a place to be clever, and a line-delimited protocol can be driven
//! by hand when something has gone wrong.

use curfew_core::{Lock, Refusal, Session, Timestamp};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

/// The pipe the service listens on. `\\.\pipe\` is machine-local; it is never reachable over the
/// network, which is what makes an unauthenticated local protocol acceptable at all.
pub const PIPE_NAME: &str = r"\\.\pipe\curfew";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "request", rename_all = "snake_case")]
pub enum Request {
    /// What is running, what is blocked, and what is wrong.
    Status,
    /// Start a session by hand.
    Start {
        profile: String,
        seconds: u32,
        #[serde(default)]
        locks: Vec<Lock>,
    },
    /// End one, having satisfied its conditions. The service re-checks; a caller claiming to have
    /// satisfied a lock is not evidence that it did.
    End {
        id: String,
        #[serde(default)]
        satisfied: BTreeSet<Lock>,
    },
    /// End a session by proving ownership of the machine.
    ///
    /// The password is checked by the service with `LogonUser`, never by the caller: the point of
    /// [`Request::End`] refusing an unproven claim is lost if some other message accepts one. The
    /// pipe is machine-local and the process at the other end is SYSTEM, which is the same trust
    /// boundary the operating system's own credential prompt sits on.
    Unlock {
        id: String,
        username: String,
        #[serde(default)]
        domain: String,
        password: String,
    },
    /// End a session by presenting a physical tag: the payload of an NFC sticker or a QR code,
    /// typed or pasted here.
    ///
    /// The payload is checked against the config's fingerprints by the service, for the same
    /// reason [`Request::Unlock`] checks the password itself. A tag that is not one of the
    /// configured ones is not an error message worth being precise about: it says the lock is
    /// still shut, and nothing about which tags exist.
    Token { id: String, payload: String },
    /// "This device releases that session", for a `Lock::PeerRelease` naming this device.
    ///
    /// The release is announced through the op-log, where the signature makes the claim of
    /// authorship real. It cannot be withdrawn: a release a peer could take back would let one
    /// device shut a lock the user has already been told they are out of.
    Release { id: String },
    /// Start the 24-hour delayed release (GAPS D1). Returns when it lands.
    RequestRelease { id: String },
    /// Spend an emergency pass on one session, ending it whatever its lock says.
    ///
    /// The rationing is the service's to enforce, not the caller's: a tray that decided for itself
    /// whether a pass was available would be a tray that could decide there were more of them.
    /// Refused with [`Response::NoPass`] when there is none to spend.
    Emergency { id: String },
    /// Call off an announced freeze.
    ///
    /// This one message needs no lock satisfied and can never be refused on policy grounds, because
    /// a countdown has started nothing yet — the promise Curfew keeps is about sessions it is
    /// already running (GAPS B4).
    CancelFreeze,
    /// Agree, at this machine, to a freeze a paired device asked for.
    ConfirmFreeze,
    /// Re-read the config from disk.
    Reload,
    /// "The extension in this browser is alive." Sent by the native-messaging host on a timer.
    ///
    /// Unauthenticated, like everything else on this pipe, and that is a known limit rather than an
    /// oversight: anyone who can write here could forge a heartbeat and get their browser back
    /// without path rules being enforced. It buys them nothing below that layer — domains, apps and
    /// the lock itself are enforced by the service and are not reachable from here — which is
    /// exactly why the extension is a granularity layer and never the floor (GAPS G1).
    ///
    /// `url` is the page in the browser's focused tab, or nothing when no window of that browser
    /// is focused. It is what web budgets are charged against: the service cannot see a tab
    /// from where it runs, so time on a page is counted from the extension saying so.
    Beat {
        browser: String,
        #[serde(default)]
        url: Option<String>,
    },
    /// "This is the window the user is looking at." Sent by the tray every couple of seconds.
    ///
    /// The service runs in session 0, where `GetForegroundWindow` sees no desktop at all, so the
    /// only process that can answer the question is one in the user's session. Unauthenticated,
    /// like the heartbeat, and with the same limit: a program that lies here can under-charge an
    /// app budget. It cannot touch a block, a schedule or a lock.
    Seen { exe: String, title: String },
    /// "The user is opening this URL. May they?" The service decides; the extension only reports
    /// and obeys, so a tampered extension cannot invent an allow the core did not give.
    Check { browser: String, url: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "response", rename_all = "snake_case")]
pub enum Response {
    /// Boxed: a status carries the whole visible world — every running session, every schedule
    /// due, every warning — and it is one of a dozen variants the rest of which are a word and a
    /// timestamp. Without the box every response everywhere would be as large as the largest.
    Status(Box<Status>),
    Ok,
    /// Release lands at this instant, and not before.
    Release {
        at: Timestamp,
    },
    /// A freeze has been announced and will happen unless it is cancelled.
    Announced {
        countdown: curfew_core::Countdown,
    },
    /// The core said no. Carried through verbatim so the UI can explain exactly which condition is
    /// unmet, rather than saying "denied" and leaving the user guessing.
    Refused {
        refusal: Refusal,
    },
    /// No emergency pass could be spent, and why — a disabled hatch, a spent quota, or a cooldown
    /// with a time on it. Separate from [`Response::Refused`] because nothing about the session's
    /// own lock was the problem.
    NoPass {
        refusal: curfew_core::PassRefusal,
    },
    /// The answer to a [`Request::Check`].
    Verdict {
        blocked: bool,
        /// Said out loud on the block page. A page that says only "blocked" invites the reader to
        /// suspect a bug and go looking for the way round it.
        reason: Option<String>,
    },
    /// Something went wrong that is not a policy decision: a config that no longer parses, a
    /// profile that does not exist.
    Error {
        detail: String,
    },
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Status {
    pub now: Timestamp,
    pub running: Vec<Session>,
    pub blocked_domains: BTreeSet<String>,
    /// Executables the last pass wanted to close and could not. Shown, never hidden.
    pub failing: BTreeSet<String>,
    /// Executables the last pass closed. The tray watches this to know when to explain itself: a
    /// window that vanishes with no reason given is indistinguishable from a crash.
    pub closed: BTreeSet<String>,
    /// Browsers closed for having no extension answering for them while a path-level rule was in
    /// force. The tray says how to fix it, because this one is fixable.
    #[serde(default)]
    pub unwatched: BTreeSet<String>,
    /// Executables held behind a delay rule, and the seconds left of each wait.
    pub delayed: std::collections::BTreeMap<String, i64>,
    /// A freeze that has been announced and has not happened yet. Carried on every status so no UI
    /// can be showing a stale "nothing is about to happen" while the machine counts down.
    #[serde(default)]
    pub freeze: Option<curfew_core::Countdown>,
    /// Why website blocking is not working, if it is not.
    pub hosts_error: Option<String>,
    /// Set when the state file could not be read on startup. The user is owed this: it means locks
    /// may have been lost.
    pub state_warning: Option<String>,
    /// Emergency passes that could be spent right now, and why not when the answer is none. Both
    /// on every status, so a UI never has to ask a second question to know whether to offer the
    /// hatch or to explain its absence.
    #[serde(default)]
    pub passes_left: u32,
    #[serde(default)]
    pub pass_refusal: Option<curfew_core::PassRefusal>,
    /// Sessions whose lock names *this* device as the one that may release it, so the tray can
    /// offer the button rather than the user having to know which device was named.
    #[serde(default)]
    pub releasable: Vec<String>,
    /// Sessions this device has already released. Kept so the button becomes a sentence — the
    /// release is given once and there is nothing further to press.
    #[serde(default)]
    pub released: Vec<String>,
}

/// The control channel's name. Namespaced, so on Windows this is a named pipe under
/// [`PIPE_NAME`]'s directory, which is machine-local and never reachable over the network.
pub const SOCKET: &str = "curfew.sock";

/// Send one request to a running service and read the answer.
///
/// This lives beside the message types rather than in the service, because everything that talks to
/// the service — the command line, the tray, the overlay — needs it, and a second hand-written copy
/// of the framing is a second place for it to drift.
pub fn ask(request: &Request) -> std::io::Result<Response> {
    use interprocess::local_socket::traits::Stream as _;
    use interprocess::local_socket::{GenericNamespaced, Stream, ToNsName};
    use std::io::{BufRead, BufReader, Write};

    let name = SOCKET.to_ns_name::<GenericNamespaced>()?;
    let stream = Stream::connect(name)?;
    let mut reader = BufReader::new(stream);
    let line = format!("{}\n", serde_json::to_string(request)?);
    reader.get_mut().write_all(line.as_bytes())?;
    reader.get_mut().flush()?;
    let mut answer = String::new();
    reader.read_line(&mut answer)?;
    serde_json::from_str(answer.trim()).map_err(std::io::Error::other)
}

/// Parse one line from the wire.
pub fn parse_request(line: &str) -> Result<Request, String> {
    serde_json::from_str(line.trim()).map_err(|e| e.to_string())
}

/// Render a response as the single line that answers it.
pub fn encode(response: &Response) -> String {
    // A response that cannot be serialized would leave the caller hanging on a read forever, so
    // there is a last-resort line rather than a panic.
    let body = serde_json::to_string(response)
        .unwrap_or_else(|e| format!(r#"{{"response":"error","detail":"{e}"}}"#));
    format!("{body}\n")
}
