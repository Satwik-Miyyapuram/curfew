//! Windows enforcement for Curfew.
//!
//! The policy lives in `curfew-core` and is shared with Android; what is here is the Windows half of
//! carrying it out: which processes are running, which of them a lock says must not be, and which
//! names must not resolve. Everything that can be decided without touching the machine is a pure
//! function, so the parts that need a machine stay small enough to read.

pub mod acl;
pub mod blocked;
pub mod calendar;
pub mod capacity;
pub mod credential;
pub mod delay;
pub mod dns;
pub mod downtime;
pub mod extension;
pub mod hosts;
pub mod ipc;
pub mod pairing;
pub mod procs;
pub mod prompt;
pub mod state;
pub mod tick;
pub mod windows;

pub use blocked::blocked_domains;
pub use delay::{Gates, Step};
pub use procs::{enforce, verdicts, Outcome, Process, Processes, Verdict};
pub use tick::{Enforcer, Tick};
