//! Trusted time.
//!
//! Design invariant 2 says no code path may shorten an active lock, and the device's own clock is
//! a code path. A `Lock::Timer` ends at a wall-clock instant, so a user who moves the system clock
//! forward would otherwise buy the rest of a lock for free — the cheapest bypass there is, and one
//! that needs no root, no admin and no uninstall.
//!
//! What is provable without a server: while the device stays up, monotonic uptime advances at the
//! rate real time does and cannot be set. So a wall clock that runs ahead of uptime, within a
//! single boot, is *demonstrably* wrong, by exactly the difference. That difference is refused —
//! never credited to a lock — and the lock is pushed back by it, so the tamper leaves the user
//! exactly where they started rather than ahead.
//!
//! What is not provable: a jump across a reboot. Uptime restarts at zero, so an hour of genuine
//! power-off and an hour stolen by changing the clock in the firmware are the same observation, and
//! no amount of local reasoning separates them. Refusing that time would punish every honest user
//! who turns their device off overnight, so it is credited, recorded as unverified, and surfaced.
//! The residual is that a bypass costs a reboot — real friction, plainly logged — rather than a
//! setting. See GAPS C6.

use crate::Timestamp;
use serde::{Deserialize, Serialize};

/// Wall clocks drift and NTP corrects them. Anything smaller than this is normal life, not tamper.
pub const TOLERANCE_SECONDS: i64 = 60;

/// One reading of the device's two clocks, taken at the same moment.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Reading {
    /// Seconds since the Unix epoch, as the device believes them. User-settable.
    pub wall: Timestamp,
    /// Seconds since boot, from a monotonic source that cannot be set
    /// (`SystemClock.elapsedRealtime` on Android, `QueryUnbiasedInterruptTime` on Windows).
    pub uptime: i64,
    /// Changes on every boot, so a restart is never mistaken for uptime running backwards.
    pub boot_id: u64,
}

/// What a reading turned out to mean.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Verdict {
    /// The time locks are judged against. Never ahead of the trusted elapsed time, and never
    /// behind the previous verdict: it only ever moves forward.
    pub now: Timestamp,
    /// Seconds the wall clock claimed within one boot that uptime did not support. Always
    /// tampering; refused rather than credited.
    pub refused_forward: i64,
    /// Seconds the wall clock lost within one boot. Also tampering, and equally refused — the
    /// trusted clock simply does not go back.
    pub refused_backward: i64,
    /// Seconds credited across a reboot without proof. Honest downtime looks exactly like this,
    /// so it is allowed, but it is worth showing to the user and worth writing down.
    pub unverified: i64,
}

impl Verdict {
    /// Whether this reading is evidence of the clock being moved while the device was running.
    pub fn tampered(&self) -> bool {
        self.refused_forward > 0 || self.refused_backward > 0
    }
}

/// The running memory a trusted clock needs: the last reading, and the time it produced.
///
/// Serialized with the rest of the state, because the guarantee has to survive the process dying.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClockWitness {
    last_wall: Timestamp,
    last_uptime: i64,
    last_boot_id: u64,
    /// The last value [`ClockWitness::observe`] returned, which no later reading may undercut.
    trusted: Timestamp,
}

impl ClockWitness {
    /// Start trusting the device from a first reading. The first wall clock has to be taken on
    /// faith — there is nothing yet to check it against.
    pub fn new(reading: Reading) -> Self {
        Self {
            last_wall: reading.wall,
            last_uptime: reading.uptime,
            last_boot_id: reading.boot_id,
            trusted: reading.wall,
        }
    }

    /// The current trusted time, without taking a new reading.
    pub fn now(&self) -> Timestamp {
        self.trusted
    }

    /// Take a reading and decide what it is worth.
    pub fn observe(&mut self, reading: Reading) -> Verdict {
        let rebooted = reading.boot_id != self.last_boot_id || reading.uptime < self.last_uptime;
        let wall_delta = reading.wall - self.last_wall;

        let mut verdict =
            Verdict { now: self.trusted, refused_forward: 0, refused_backward: 0, unverified: 0 };

        if rebooted {
            // Uptime restarted, so it can vouch for nothing. Forward time is credited and recorded
            // as unverified; a wall clock that went *backwards* across a reboot is still refused,
            // since no amount of being switched off makes it earlier than it was.
            if wall_delta > TOLERANCE_SECONDS {
                verdict.unverified = wall_delta;
                verdict.now = self.trusted + wall_delta;
            } else if wall_delta < -TOLERANCE_SECONDS {
                verdict.refused_backward = -wall_delta;
            }
        } else {
            // Same boot: uptime is the measure, and the wall clock is only allowed to agree with it.
            let elapsed = reading.uptime - self.last_uptime;
            verdict.now = self.trusted + elapsed;
            let drift = wall_delta - elapsed;
            if drift > TOLERANCE_SECONDS {
                verdict.refused_forward = drift;
            } else if drift < -TOLERANCE_SECONDS {
                verdict.refused_backward = -drift;
            }
        }

        // Monotone by construction: a verdict never moves time backwards, whatever was read.
        verdict.now = verdict.now.max(self.trusted);

        self.last_wall = reading.wall;
        self.last_uptime = reading.uptime;
        self.last_boot_id = reading.boot_id;
        self.trusted = verdict.now;
        verdict
    }
}
