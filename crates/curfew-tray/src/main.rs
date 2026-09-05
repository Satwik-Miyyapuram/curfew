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

mod menu;
mod overlay;
mod prompt;
#[cfg(windows)]
mod shell;

use curfew_win::ipc::{self, Request, Response};

/// Ask the service, turning an unreachable service into a sentence rather than a silence.
pub fn ask(request: &Request) -> Result<Response, String> {
    ipc::ask(request).map_err(|e| {
        format!(
            "Could not reach the Curfew service ({e}).\n\n\
             It may not be installed yet, or it may have been stopped. Blocks are not being \
             enforced while this is true."
        )
    })
}

/// Turn one menu item into the message it means, and the sentence to show afterwards.
///
/// Split out from the Win32 layer so the mapping from click to request can be read — and so no
/// item can quietly acquire a second meaning.
pub fn act(item: &menu::Item, credential: Option<prompt::Credential>) -> Option<(Request, String)> {
    match item {
        menu::Item::End { id, .. } => Some((
            // No claim of a satisfied condition, ever, from a process running as the user.
            Request::End { id: id.clone(), satisfied: Default::default() },
            String::new(),
        )),
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
        menu::Item::Release { id, .. } => Some((
            Request::RequestRelease { id: id.clone() },
            String::new(),
        )),
        _ => None,
    }
}

/// What to say about an answer. Empty means say nothing: an action that worked is visible in the
/// menu the next time it is opened, and a dialog for every success is noise.
pub fn describe(response: &Response) -> String {
    match response {
        Response::Ok => String::new(),
        Response::Release { at } => format!(
            "The release has started. It lands at {}, and cannot be brought forward.",
            menu::when(*at)
        ),
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

    #[test]
    fn ending_from_the_tray_claims_nothing() {
        let item = menu::Item::End { id: "s1".into(), label: "End".into() };
        let (request, _) = act(&item, None).unwrap();
        match request {
            // The service re-checks everything anyway, but a tray that sent a claim would mean the
            // check depended on a process running as the user.
            Request::End { satisfied, .. } => assert!(satisfied.is_empty()),
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
