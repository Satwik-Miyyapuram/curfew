//! `curfew-tray` — the icon by the clock.
//!
//! The tray is a window onto the service and nothing more. It holds no state, decides nothing, and
//! cannot end a session by itself: every item on its menu turns into a message the service is free
//! to refuse. That is deliberate — a tray that could release a lock would be the weakest link in
//! the whole design, since it runs as the user, in the user's session, where anything can be
//! attached to it.
//!
//! It is not required. Curfew enforces with the tray closed, and the menu says so.

#![cfg_attr(windows, windows_subsystem = "windows")]
// Off Windows there is no shell to call any of this: `main` prints a line and stops. The pure
// halves — the menu model, the overlay wording, the credential prompt — are still compiled and
// still tested there, because they are the parts worth testing and a Linux runner is where the
// tests are cheapest to run. Unused-on-Linux is therefore the intended state, not an oversight.
#![cfg_attr(not(windows), allow(dead_code, unused_imports))]

mod menu;
mod overlay;
#[cfg(windows)]
mod shell;
mod welcome;

// The credential prompt lives in `curfew-win` now, because the window needs it too.
//
// It used to be here, and the window drew a password box of its own in HTML — the bigger, more
// prominent surface breaking the rule the smaller one documents. A module that only one of the two
// callers can reach is how that happens; `curfew-app` already depends on `curfew-win`, so the prompt
// moves to where both can use it.
use curfew_win::prompt;

use curfew_win::ipc::{self, Request, Response};

/// Ask the service, turning an unreachable service into a sentence rather than a silence.
pub fn ask(request: &Request) -> Result<Response, String> {
    ipc::ask(request).map_err(|e| unreachable_service(e.kind(), &e.to_string()))
}

/// What to say when the service does not answer.
///
/// The old wording was one sentence for every failure and it was wrong in the commonest case: the
/// pipe is missing because nobody has run `curfew install` yet, which is not a fault to report but
/// a step to take. Ending on a raw `os error 2` and then offering two guesses left the reader with
/// a number and nothing to do. Kept out of the Win32 layer so the wording can be read and tested.
pub fn unreachable_service(kind: std::io::ErrorKind, detail: &str) -> String {
    match kind {
        // No pipe at all: the service has never been registered on this machine, or has been
        // removed. There is exactly one thing to do about it, so the dialog says that thing.
        std::io::ErrorKind::NotFound => "Curfew is not installed on this PC, so nothing is being \
             blocked.\n\n\
             To install it, open Windows Terminal or PowerShell as an administrator and run:\n\
             \tcurfew install\n\n\
             Windows asks for administrator once, because a service any user could stop would not \
             be much of a lock. The tray cannot do it for you, for the same reason.\n\n\
             To try Curfew without installing anything, run this in an ordinary terminal and \
             leave it open \u{2014} it enforces for as long as it is running:\n\
             \tcurfew run"
            .to_string(),
        // The pipe is there but shut to this account. Since the service began writing an explicit
        // descriptor on its control pipe, the honest reading of this is a service older than the
        // tray talking to it \u{2014} so say that, and say the repair, rather than guessing at accounts.
        std::io::ErrorKind::PermissionDenied => "Windows refused this program access to the \
             Curfew service.\n\n\
             That usually means the installed service is older than this program. Reinstalling it \
             from an administrator terminal replaces it with a matching one:\n\
             \tcurfew install\n\n\
             Blocks are still being enforced while this is true \u{2014} this program just cannot see \
             or change them."
            .to_string(),
        _ => format!(
            "The Curfew service is installed but did not answer, so blocks are not being enforced \
             right now.\n\n\
             It may be starting or stopping; opening this menu again in a moment is worth a try. \
             If it stays this way, run this again from an administrator terminal:\n\
             \tcurfew install\n\n\
             Windows said: {detail}"
        ),
    }
}

/// Turn one menu item into the message it means, and the sentence to show afterwards.
///
/// Split out from the Win32 layer so the mapping from click to request can be read — and so no
/// item can quietly acquire a second meaning.
pub fn act(item: &menu::Item, credential: Option<prompt::Credential>) -> Option<(Request, String)> {
    match item {
        menu::Item::End { id, confirm, .. } => {
            // A confirmation is the one condition a caller may claim, and the shell has already
            // shown the Yes/No by the time this runs — the claim is the record of that dialog, not
            // an assertion about the machine. Everything machine-checkable still comes from the
            // service's own `proven`, which is why an empty set is the right answer for every other
            // lock: a process running as the user must never be able to say "I did the reboot".
            let satisfied = match confirm {
                true => std::collections::BTreeSet::from([curfew_core::Lock::Confirm]),
                false => Default::default(),
            };
            Some((Request::End { id: id.clone(), satisfied }, String::new()))
        }
        menu::Item::Unlock { id, .. } => {
            let credential = credential?;
            Some((
                Request::Unlock {
                    id: id.clone(),
                    username: credential.username.clone(),
                    domain: credential.domain.clone(),
                    password: credential.password.clone(),
                },
                String::new(),
            ))
        }
        menu::Item::CancelFreeze { .. } => Some((Request::CancelFreeze, String::new())),
        menu::Item::ConfirmFreeze { .. } => Some((Request::ConfirmFreeze, String::new())),
        menu::Item::Emergency { id, .. } => {
            Some((Request::Emergency { id: id.clone() }, String::new()))
        }
        menu::Item::PeerRelease { id, .. } => {
            Some((Request::Release { id: id.clone() }, String::new()))
        }
        menu::Item::Release { id, .. } => {
            Some((Request::RequestRelease { id: id.clone() }, String::new()))
        }
        _ => None,
    }
}

/// What to say about an answer. Empty means say nothing: an action that worked is visible in the
/// menu the next time it is opened, and a dialog for every success is noise.
pub fn describe(response: &Response) -> String {
    match response {
        Response::Ok => String::new(),
        // Nothing on this menu asks for the figures, so this is unreachable in practice — but the
        // match is exhaustive rather than wildcarded on purpose: that is why every other variant in
        // this enum has a sentence, and a catch-all would let the next one arrive silently.
        Response::Stats(stats) => format!(
            "{} session(s) in the last {} day(s); `curfew stats` has the breakdown.",
            stats.total_sessions,
            stats.days.len(),
        ),
        Response::Release { at } => format!(
            "The release has started. It lands at {}, and cannot be brought forward.",
            menu::when(*at)
        ),
        Response::Announced { countdown } => {
            curfew_core::frozen::warning(countdown, countdown.announced_at)
        }
        Response::NoPass { refusal } => match refusal {
            curfew_core::PassRefusal::Disabled => {
                "Emergency passes are switched off in your rules.".to_string()
            }
            curfew_core::PassRefusal::QuotaSpent { next_at } => format!(
                "No emergency passes left. The next one becomes available at {}.",
                menu::when(*next_at)
            ),
            curfew_core::PassRefusal::CoolingDown { until } => format!(
                "A pass was used recently. The next one can be spent at {}.",
                menu::when(*until)
            ),
        },
        Response::Refused { refusal } => match refusal {
            curfew_core::Refusal::NotRunning => "That session has already ended.".to_string(),
            curfew_core::Refusal::Locked { delayed_release_at: Some(at), .. } => format!(
                "This session is still locked. The release you started lands at {}.",
                menu::when(*at)
            ),
            curfew_core::Refusal::Locked { .. } => {
                "This session is still locked. The 24-hour release is the way out.".to_string()
            }
        },
        Response::Error { detail } => detail.clone(),
        Response::Status(_) => String::new(),
        // The tray never asks for a verdict; only the extension host does. Saying so is better
        // than a wildcard that would quietly swallow a real answer added later.
        Response::Verdict { .. } => String::new(),
    }
}

#[cfg(windows)]
fn main() {
    shell::run();
}

#[cfg(not(windows))]
fn main() {
    eprintln!("curfew-tray: the tray is a Windows thing; `curfew status` works everywhere");
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_core::Refusal;

    /// The dialog a first-run user actually sees. It has to name the step, not the errno: the
    /// version that said "os error 2" and "it may not be installed yet" was a screenshot someone
    /// had to ask about.
    #[test]
    fn a_missing_service_is_told_how_to_install_it_and_never_shown_the_errno() {
        let text = unreachable_service(std::io::ErrorKind::NotFound, "os error 2");
        assert!(text.contains("curfew install"), "no step to take: {text}");
        assert!(text.contains("administrator"), "does not say why it needs one: {text}");
        assert!(text.contains("curfew run"), "no way to try it without installing: {text}");
        assert!(!text.contains("os error"), "the errno leaked into the dialog: {text}");
        // The card draws a command as a chip when the line starts with a tab, and draws it as prose
        // otherwise. Backticks are markdown, and this window renders none: they used to reach the
        // screen as two stray characters the reader had to guess the status of.
        assert!(!text.contains('`'), "markdown leaked into a window that cannot render it: {text}");
        for command in ["curfew install", "curfew run"] {
            assert!(
                text.contains(&format!("	{command}")),
                "{command} is not marked as a command, so it will be set as prose: {text}"
            );
        }
    }

    /// Every other failure keeps the detail, because there is no single step that fixes it and a
    /// message with nothing specific in it cannot be reported.
    #[test]
    fn a_service_that_is_installed_but_silent_says_so_and_keeps_the_detail() {
        let text = unreachable_service(std::io::ErrorKind::BrokenPipe, "the pipe has been ended");
        assert!(text.contains("installed"), "{text}");
        assert!(text.contains("the pipe has been ended"), "the detail was dropped: {text}");
    }

    #[test]
    fn ending_from_the_tray_claims_nothing() {
        let item = menu::Item::End { id: "s1".into(), confirm: false, label: "End".into() };
        let (request, _) = act(&item, None).unwrap();
        match request {
            // The service re-checks everything anyway, but a tray that sent a claim would mean the
            // check depended on a process running as the user.
            Request::End { satisfied, .. } => assert!(satisfied.is_empty()),
            other => panic!("ending sent {other:?}"),
        }
    }

    /// The one exception, and it is narrow on purpose.
    ///
    /// `Lock::Confirm` is a condition no machine can check — the whole point of it is that a dialog
    /// was shown — so the process that showed the dialog is the only possible witness. The shell
    /// shows a Yes/No before reaching here, so this records a dialog rather than asserting a fact.
    /// It must stay the *only* claim: a `Timer` or a `RestartRequired` in this set would be a way out
    /// of every lock on the machine.
    #[test]
    fn confirming_from_the_tray_claims_the_confirmation_and_nothing_else() {
        let item = menu::Item::End { id: "s1".into(), confirm: true, label: "End".into() };
        let (request, _) = act(&item, None).unwrap();
        match request {
            Request::End { satisfied, .. } => assert_eq!(
                satisfied,
                std::collections::BTreeSet::from([curfew_core::Lock::Confirm]),
                "a confirmation lock sent the wrong claim"
            ),
            other => panic!("ending sent {other:?}"),
        }
    }

    #[test]
    fn a_cancelled_password_prompt_sends_nothing_at_all() {
        let item = menu::Item::Unlock { id: "s1".into(), label: "Unlock".into() };
        assert!(act(&item, None).is_none(), "closing the prompt attempted an unlock anyway");
    }

    #[test]
    fn a_refusal_points_at_the_release_rather_than_at_a_dead_end() {
        let refusal = Refusal::Locked {
            missing: Default::default(),
            ends_at: None,
            delayed_release_at: None,
        };
        let text = describe(&Response::Refused { refusal });
        assert!(text.contains("24-hour release"));
    }

    #[test]
    fn a_release_already_running_is_reported_with_the_time_it_lands() {
        let refusal = Refusal::Locked {
            missing: Default::default(),
            ends_at: None,
            delayed_release_at: Some(1_788_510_600),
        };
        assert!(describe(&Response::Refused { refusal }).contains("lands at"));
    }

    #[test]
    fn a_successful_action_says_nothing() {
        assert!(describe(&Response::Ok).is_empty());
    }
}
