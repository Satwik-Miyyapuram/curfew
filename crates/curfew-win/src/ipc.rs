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
    /// Start the 24-hour delayed release (GAPS D1). Returns when it lands.
    RequestRelease { id: String },
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
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "response", rename_all = "snake_case")]
pub enum Response {
    Status(Status),
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
    /// Executables that owe the user a friction screen before they continue.
    pub delayed: BTreeSet<String>,
    /// A freeze that has been announced and has not happened yet. Carried on every status so no UI
    /// can be showing a stale "nothing is about to happen" while the machine counts down.
    #[serde(default)]
    pub freeze: Option<curfew_core::Countdown>,
    /// Why website blocking is not working, if it is not.
    pub hosts_error: Option<String>,
    /// Set when the state file could not be read on startup. The user is owed this: it means locks
    /// may have been lost.
    pub state_warning: Option<String>,
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
