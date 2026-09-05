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
    /// Start the 24-hour delayed release (GAPS D1). Returns when it lands.
    RequestRelease { id: String },
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
    /// Why website blocking is not working, if it is not.
    pub hosts_error: Option<String>,
    /// Set when the state file could not be read on startup. The user is owed this: it means locks
    /// may have been lost.
    pub state_warning: Option<String>,
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
