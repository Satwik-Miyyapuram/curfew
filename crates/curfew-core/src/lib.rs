//! Curfew core: the platform-independent half of the app.
//!
//! Everything here is pure. No I/O, no clock reads, no platform calls — the caller passes `now`
//! and the observed foreground target in, and gets a [`Decision`] out. That is what lets Android
//! and Windows agree on what a config file means (ARCHITECTURE.md §3).

pub mod config;
pub mod engine;
pub mod lock;

pub use config::{Action, Config, Profile, Rule, Target, CONFIG_SCHEMA_VERSION};
pub use engine::{decide, Decision, Foreground, State};
pub use lock::{Lock, LockSet};

/// Seconds since the Unix epoch. Wall-clock, signed so arithmetic never wraps.
pub type Timestamp = i64;
