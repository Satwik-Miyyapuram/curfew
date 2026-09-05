//! Frozen mode: the countdown that stands between a click and losing unsaved work.
//!
//! A profile that blocks [`Target::WholeDevice`] does not close one application, it closes the lot.
//! Everything Curfew does is reversible except this: a document nobody saved is gone, and no lock
//! design makes it come back. So GAPS B4 puts a wall in front of it — a freeze is announced, it
//! counts down, and it is cancellable free of charge for the whole countdown.
//!
//! Three rules are encoded here, and each of them exists because of a way this feature could hurt
//! someone:
//!
//! * **Never instant.** There is no path that freezes now. A minimum warning is applied even to a
//!   request that asks for none, because "I'll only be a second" is exactly when the unsaved work
//!   is open.
//! * **Cancellable throughout.** Cancelling a countdown is free and needs no lock satisfied: the
//!   promise Curfew makes is about sessions it has *started*, and a countdown has started nothing.
//!   A freeze that could not be called off would make the announcement a taunt.
//! * **Never remote-fired.** A paired device may ask this one to freeze; it may not do it. The
//!   person whose unsaved work it is has to say yes on the machine holding the work (GAPS B4), so a
//!   countdown from a peer waits at the confirmation and expires rather than firing.
//!
//! Everything here is pure, so both platforms freeze on the same terms.

use crate::{Action, Config, Target, Timestamp};
use serde::{Deserialize, Serialize};

/// The shortest warning a freeze may have. GAPS B4 says cancellable up to 60 seconds before it
/// fires, which is only meaningful if there are always at least 60 seconds to cancel in.
pub const MINIMUM_WARNING_SECONDS: u32 = 60;

/// How long an unanswered remote request waits before it is dropped.
///
/// It expires rather than firing: a request nobody was at the machine to confirm is a request that
/// should quietly come to nothing, not one that freezes an unattended desktop hours later.
pub const CONFIRMATION_WINDOW_SECONDS: i64 = 5 * 60;

/// Who asked for the freeze.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Origin {
    /// Someone at this machine.
    Local,
    /// A paired device. Cannot fire without a local yes.
    Peer,
}

/// A freeze that has been announced and has not yet happened.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Countdown {
    /// The profile that will start when it fires.
    pub profile: String,
    /// How long the session will then run for.
    pub seconds: u32,
    pub origin: Origin,
    /// When it was announced, which is when a peer's confirmation window starts running.
    pub announced_at: Timestamp,
    /// When it fires, if nothing stops it.
    pub fires_at: Timestamp,
    /// Set when someone at this machine agreed to a peer's request.
    #[serde(default)]
    pub confirmed: bool,
}

/// Whether starting `profile` would freeze the whole device.
///
/// Read off the config rather than off a flag on the session, so a profile that acquires a
/// whole-device rule later acquires the countdown with it. A budget or a delay on the whole device
/// is not a freeze: it takes nothing away without warning.
pub fn freezes(config: &Config, profile: &str) -> bool {
    config.profiles.iter().filter(|p| p.id == profile).flat_map(|p| &p.rules).any(|rule| {
        matches!(rule.target, Target::WholeDevice)
            && matches!(rule.action, Action::Block | Action::AllowOnly)
    })
}

/// Announce a freeze. `warning` is clamped up to the minimum, never down.
pub fn announce(
    now: Timestamp,
    profile: &str,
    seconds: u32,
    origin: Origin,
    warning: u32,
) -> Countdown {
    let warning = warning.max(MINIMUM_WARNING_SECONDS);
    Countdown {
        profile: profile.to_string(),
        seconds,
        origin,
        announced_at: now,
        fires_at: now + i64::from(warning),
        confirmed: false,
    }
}

/// What should happen to a countdown at `now`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Due {
    /// Keep counting, and keep showing it.
    Waiting,
    /// A peer asked and nobody here has said yes yet.
    AwaitingConfirmation,
    /// Start the session.
    Fire,
    /// Drop it, unfired.
    Expired,
}

/// Decide the fate of a countdown without touching anything.
pub fn due(countdown: &Countdown, now: Timestamp) -> Due {
    if countdown.origin == Origin::Peer && !countdown.confirmed {
        // The window is measured from the announcement, so a peer cannot buy itself more time by
        // choosing a long warning.
        return match now - countdown.announced_at >= CONFIRMATION_WINDOW_SECONDS {
            true => Due::Expired,
            false => Due::AwaitingConfirmation,
        };
    }
    match now >= countdown.fires_at {
        true => Due::Fire,
        false => Due::Waiting,
    }
}

/// Seconds left before it fires, floored at zero.
pub fn remaining(countdown: &Countdown, now: Timestamp) -> i64 {
    (countdown.fires_at - now).max(0)
}

/// What the user is told while it counts down.
///
/// It names the cost in the first sentence. Everything in this project can be undone except unsaved
/// work, and a warning that buries that under the profile name is not a warning.
pub fn warning(countdown: &Countdown, now: Timestamp) -> String {
    let left = remaining(countdown, now);
    let who = match countdown.origin {
        Origin::Local => String::new(),
        Origin::Peer => " (asked for by another of your devices)".to_string(),
    };
    format!(
        "Everything will be closed in {left} seconds{who}. Save your work now.\n\n\
         {} then blocks this whole device. You can cancel until the moment it starts, and \
         cancelling costs nothing.",
        countdown.profile
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Profile, Rule};

    const NOW: Timestamp = 1_788_510_600;

    fn config(action: Action, target: Target) -> Config {
        let mut config = Config::default();
        config.profiles.push(Profile {
            id: "frozen".into(),
            name: "Frozen".into(),
            description: String::new(),
            rules: vec![Rule { target, action, platforms: vec![] }],
        });
        config
    }

    #[test]
    fn a_whole_device_block_is_a_freeze_and_a_normal_block_is_not() {
        assert!(freezes(&config(Action::Block, Target::WholeDevice), "frozen"));
        assert!(!freezes(
            &config(Action::Block, Target::WindowsExe { exe: "steam.exe".into() }),
            "frozen"
        ));
    }

    #[test]
    fn an_allow_only_list_over_the_whole_device_still_takes_everything_else_away() {
        assert!(freezes(&config(Action::AllowOnly, Target::WholeDevice), "frozen"));
    }

    #[test]
    fn a_budget_on_the_whole_device_is_not_a_freeze() {
        // It ends the day's use eventually, but it never closes the machine out from under someone
        // mid-sentence, so it does not need the countdown.
        let config = config(
            Action::Budget { seconds: 3600, refill: Default::default() },
            Target::WholeDevice,
        );
        assert!(!freezes(&config, "frozen"));
    }

    #[test]
    fn a_freeze_can_never_be_asked_for_instantly() {
        let countdown = announce(NOW, "frozen", 3600, Origin::Local, 0);
        assert_eq!(remaining(&countdown, NOW), i64::from(MINIMUM_WARNING_SECONDS));
        assert_eq!(due(&countdown, NOW), Due::Waiting);
    }

    #[test]
    fn a_longer_warning_than_the_minimum_is_honoured() {
        let countdown = announce(NOW, "frozen", 3600, Origin::Local, 600);
        assert_eq!(remaining(&countdown, NOW), 600);
    }

    #[test]
    fn a_local_countdown_fires_when_it_runs_out() {
        let countdown = announce(NOW, "frozen", 3600, Origin::Local, 60);
        assert_eq!(due(&countdown, NOW + 59), Due::Waiting);
        assert_eq!(due(&countdown, NOW + 60), Due::Fire);
    }

    #[test]
    fn a_peer_can_ask_for_a_freeze_and_cannot_perform_one() {
        let countdown = announce(NOW, "frozen", 3600, Origin::Peer, 60);
        // Long past the moment it would otherwise have fired, and still nothing has been closed.
        assert_eq!(due(&countdown, NOW + 120), Due::AwaitingConfirmation);
    }

    #[test]
    fn an_unanswered_peer_request_expires_instead_of_freezing_an_empty_desk() {
        let countdown = announce(NOW, "frozen", 3600, Origin::Peer, 60);
        assert_eq!(due(&countdown, NOW + CONFIRMATION_WINDOW_SECONDS), Due::Expired);
    }

    #[test]
    fn a_confirmed_peer_request_still_gets_the_whole_countdown() {
        let mut countdown = announce(NOW, "frozen", 3600, Origin::Peer, 60);
        countdown.confirmed = true;
        assert_eq!(due(&countdown, NOW + 30), Due::Waiting);
        assert_eq!(due(&countdown, NOW + 60), Due::Fire);
    }

    #[test]
    fn a_peer_cannot_extend_its_confirmation_window_with_a_long_warning() {
        let countdown = announce(NOW, "frozen", 3600, Origin::Peer, 3600);
        assert_eq!(due(&countdown, NOW + CONFIRMATION_WINDOW_SECONDS), Due::Expired);
    }

    #[test]
    fn the_warning_leads_with_the_unsaved_work_and_says_cancelling_is_free() {
        let text = warning(&announce(NOW, "frozen", 3600, Origin::Local, 60), NOW);
        assert!(text.starts_with("Everything will be closed in 60 seconds."), "{text}");
        assert!(text.contains("Save your work"));
        assert!(text.contains("cancel"));
    }

    #[test]
    fn a_peer_request_says_where_it_came_from() {
        let text = warning(&announce(NOW, "frozen", 3600, Origin::Peer, 60), NOW);
        assert!(text.contains("another of your devices"));
    }

    #[test]
    fn a_countdown_past_its_time_never_reads_as_negative() {
        let countdown = announce(NOW, "frozen", 3600, Origin::Local, 60);
        assert_eq!(remaining(&countdown, NOW + 600), 0);
    }
}
