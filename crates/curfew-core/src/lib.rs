//! Curfew's policy core: pure, platform-free, and the single source of truth about what a config
//! means. Android, Windows and the CLI all call into this, which is what makes them agree.
//!
//! Nothing here reads a clock, a file or a device. `now` is always a parameter.

pub mod budget;
pub mod clock;
pub mod config;
pub mod engine;
pub mod lock;
pub mod schedule;
pub mod session;
pub mod target;

pub use clock::{ClockWitness, Reading, Verdict};
pub use budget::{Consumption, Launches, Refill, Rollup};
pub use config::{Action, Config, ConfigError, Platform, Profile, Rule, CONFIG_SCHEMA_VERSION};
pub use engine::{decide, BlockReason, Decision, State};
pub use lock::{ChallengeKind, Lock, LockSet, DELAYED_RELEASE_SECONDS};
pub use schedule::{
    active_at, next_change_after, Activation, ActivationSource, CalendarEvent, CalendarSchedule,
    EventMatcher, WeeklySchedule,
};
pub use session::{reconcile, Refusal, Session, SessionSource, Sessions};
pub use target::{domain_matches, glob_match, Observation, Target, Url};

/// Seconds since the Unix epoch. Wall-clock, signed so arithmetic never wraps.
pub type Timestamp = i64;
