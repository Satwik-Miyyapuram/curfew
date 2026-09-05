//! What the tray offers, worked out from the service's answer and nothing else.
//!
//! This is the part of the tray worth testing, so it is the part with no Win32 in it. Every rule
//! about what a user may do to a running lock lives in the core and is enforced by the service;
//! what lives here is only which of those actions is worth *offering*, and how to say it.

use curfew_core::{Lock, Timestamp};
use curfew_win::ipc::Status;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Item {
    /// A line of text with nothing behind it.
    Note(String),
    /// End a session that has no unmet conditions.
    End {
        id: String,
        label: String,
    },
    /// End one that needs the machine's password, via the operating system's own prompt.
    Unlock {
        id: String,
        label: String,
    },
    /// Start the 24-hour delayed release.
    Release {
        id: String,
        label: String,
    },
    /// Call off an announced freeze. Never refused, and always first on the menu.
    CancelFreeze {
        label: String,
    },
    /// Agree, here, to a freeze another device asked for.
    ConfirmFreeze {
        label: String,
    },
    Separator,
    /// Show what is blocked, and anything that is failing.
    Details,
    /// Hide the tray icon. It never stops enforcement, and says so.
    Quit,
}

pub fn when(ts: Timestamp) -> String {
    use chrono::TimeZone as _;
    match chrono::Local.timestamp_opt(ts, 0).single() {
        Some(local) => local.format("%a %-d %b, %H:%M").to_string(),
        None => ts.to_string(),
    }
}

fn remaining(now: Timestamp, ends_at: Option<Timestamp>) -> String {
    let Some(ends) = ends_at else { return "until released".to_string() };
    let minutes = ((ends - now).max(0) + 59) / 60;
    match minutes {
        0 => "finishing now".to_string(),
        1 => "1 minute left".to_string(),
        m if m < 90 => format!("{m} minutes left"),
        m => format!("{} h {} min left", m / 60, m % 60),
    }
}

/// The menu for a given answer from the service.
pub fn menu(status: &Status) -> Vec<Item> {
    let mut items = Vec::new();

    // Before anything else, and above even what is running: a machine that is about to close every
    // window is not a fact to find halfway down a menu.
    if let Some(countdown) = &status.freeze {
        match curfew_core::frozen::due(countdown, status.now) {
            curfew_core::frozen::Due::AwaitingConfirmation => {
                items.push(Item::Note(format!(
                    "Another device asked to freeze {} — nothing has happened yet",
                    countdown.profile
                )));
                items
                    .push(Item::ConfirmFreeze { label: "Yes, freeze this device too".to_string() });
            }
            _ => items.push(Item::Note(format!(
                "{} freezes everything in {} s — save your work",
                countdown.profile,
                curfew_core::frozen::remaining(countdown, status.now)
            ))),
        }
        items.push(Item::CancelFreeze { label: "Cancel the freeze".to_string() });
        items.push(Item::Separator);
    }

    if status.running.is_empty() {
        items.push(Item::Note("Nothing is running. Curfew is watching.".to_string()));
    }

    for session in &status.running {
        items.push(Item::Note(format!(
            "{} — {}",
            session.profile,
            remaining(status.now, session.lock.ends_at)
        )));

        let conditions: Vec<&Lock> =
            session.lock.conditions.iter().filter(|lock| !matches!(lock, Lock::Timer)).collect();

        // Only the credential can be satisfied from here. A token, a peer release or a challenge is
        // satisfied somewhere else by design, and an item that opened a prompt leading nowhere would
        // be worse than no item at all.
        let credential = conditions.iter().any(|lock| matches!(lock, Lock::DeviceCredential));
        let others = conditions.iter().any(|lock| !matches!(lock, Lock::DeviceCredential));

        if others {
            items.push(Item::Note("    this session is locked elsewhere".to_string()));
        } else if credential {
            items.push(Item::Unlock {
                id: session.id.clone(),
                label: format!("End {} with your Windows password", session.profile),
            });
        } else if session.lock.ends_at.is_some_and(|ends| ends > status.now) {
            // A timer that has not run out is a lock; the service will refuse, and it is honest to
            // say so on the item rather than to offer it and fail.
            items.push(Item::Note("    ends when the timer runs out".to_string()));
        } else {
            items.push(Item::End {
                id: session.id.clone(),
                label: format!("End {}", session.profile),
            });
        }

        match session.lock.delayed_release_at {
            // Already running, and never offered twice: asking again cannot move it, so an item that
            // looked like it might would be a lie.
            Some(at) => items.push(Item::Note(format!("    release lands {}", when(at)))),
            None if credential || others => items.push(Item::Release {
                id: session.id.clone(),
                label: "Start the 24-hour release".to_string(),
            }),
            None => {}
        }
    }

    // Waits are listed after the sessions and before the trouble: they are neither, being the one
    // thing on this menu that resolves itself with no action from anyone.
    for (exe, left) in &status.delayed {
        items.push(Item::Note(format!("{exe} opens in {left} s")));
    }

    // A closed browser is the one piece of trouble whose cause is not obvious from the outside: it
    // looks like a crash. Naming it on the menu, above details, is the difference between the user
    // installing the extension and the user filing a bug.
    for exe in &status.unwatched {
        items.push(Item::Note(format!("{exe} was closed — it is running without the extension")));
    }

    if status.hosts_error.is_some()
        || !status.failing.is_empty()
        || status.state_warning.is_some()
        || !status.unwatched.is_empty()
    {
        items.push(Item::Note("Something is not being enforced — see details".to_string()));
    }

    items.push(Item::Separator);
    items.push(Item::Details);
    items.push(Item::Quit);
    items
}

/// What the tray says when it is closed, so nobody closes it expecting the blocks to lift.
pub const QUIT_NOTE: &str = "Hiding this icon does not stop Curfew. The service keeps enforcing \
                             everything you asked for, and `curfew status` still answers.";

/// The details text: everything the service reported that is not an offer to do something.
pub fn details(status: &Status) -> String {
    let mut text = String::new();
    if status.blocked_domains.is_empty() {
        text.push_str("No websites are blocked right now.\n");
    } else {
        text.push_str("Blocked websites:\n");
        for domain in &status.blocked_domains {
            text.push_str(&format!("  {domain}\n"));
        }
    }
    for exe in &status.failing {
        text.push_str(&format!(
            "\nCould not close {exe}. It is running with privileges Curfew does not have.\n"
        ));
    }
    if !status.unwatched.is_empty() {
        text.push_str(
            "
A rule you are running names a page rather than a whole site, and only the browser 
             can see which page a tab is on. These browsers were closed because they are running 
             without the Curfew extension:
",
        );
        for exe in &status.unwatched {
            text.push_str(&format!(
                "  {exe}
"
            ));
        }
        text.push_str(
            "Install the extension and register it with `curfew extension <browser> <id>`; they 
             will stay open. Nothing else lifts this, because a browser Curfew cannot see is a 
             browser that can go anywhere.
",
        );
    }
    if let Some(e) = &status.hosts_error {
        text.push_str(&format!("\nWebsite blocking is not working: {e}\n"));
    }
    if let Some(warning) = &status.state_warning {
        text.push_str(&format!("\n{warning}\n"));
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_core::{LockSet, Session, SessionSource};

    const NOW: Timestamp = 1_788_510_600;

    fn session(locks: impl IntoIterator<Item = Lock>, ends_at: Option<Timestamp>) -> Session {
        Session {
            id: "s1".into(),
            profile: "deep-work".into(),
            source: SessionSource::Manual,
            started_at: NOW,
            lock: LockSet::new(locks, ends_at),
        }
    }

    fn status(running: Vec<Session>) -> Status {
        Status { now: NOW, running, ..Default::default() }
    }

    #[test]
    fn an_idle_tray_says_so_and_offers_nothing_to_end() {
        let items = menu(&status(vec![]));
        assert!(matches!(items[0], Item::Note(_)));
        assert!(!items.iter().any(|i| matches!(i, Item::End { .. } | Item::Unlock { .. })));
    }

    #[test]
    fn a_credential_lock_offers_the_password_and_the_delayed_release() {
        let items = menu(&status(vec![session([Lock::DeviceCredential], Some(NOW + 3600))]));
        assert!(items.iter().any(|i| matches!(i, Item::Unlock { .. })));
        assert!(items.iter().any(|i| matches!(i, Item::Release { .. })));
    }

    #[test]
    fn a_release_already_running_is_never_offered_again() {
        let mut session = session([Lock::DeviceCredential], Some(NOW + 3600));
        session.lock.delayed_release_at = Some(NOW + 24 * 3600);

        let items = menu(&status(vec![session]));

        assert!(
            !items.iter().any(|i| matches!(i, Item::Release { .. })),
            "offering it twice would suggest asking again could move it"
        );
    }

    #[test]
    fn a_running_timer_is_not_offered_as_something_to_end() {
        // The service would refuse, and an item that fails when clicked teaches people to distrust
        // the whole menu.
        let items = menu(&status(vec![session([Lock::Timer], Some(NOW + 600))]));
        assert!(!items.iter().any(|i| matches!(i, Item::End { .. })));
    }

    #[test]
    fn a_timer_that_has_run_out_can_be_ended_from_here() {
        let items = menu(&status(vec![session([Lock::Timer], Some(NOW - 1))]));
        assert!(items.iter().any(|i| matches!(i, Item::End { .. })));
    }

    #[test]
    fn a_lock_that_cannot_be_satisfied_here_offers_no_prompt_that_leads_nowhere() {
        let items =
            menu(&status(vec![session([Lock::Token { id: "t1".into() }], Some(NOW + 3600))]));
        assert!(!items.iter().any(|i| matches!(i, Item::Unlock { .. } | Item::End { .. })));
        assert!(items.iter().any(|i| matches!(i, Item::Release { .. })));
    }

    #[test]
    fn trouble_is_surfaced_on_the_menu_rather_than_only_in_the_details() {
        let mut status = status(vec![]);
        status.hosts_error = Some("access denied".into());
        assert!(menu(&status)
            .iter()
            .any(|i| matches!(i, Item::Note(n) if n.contains("not being enforced"))));
    }

    #[test]
    fn details_lists_what_is_blocked_and_what_failed() {
        let mut status = status(vec![]);
        status.blocked_domains.insert("reddit.com".into());
        status.failing.insert("steam.exe".into());
        let text = details(&status);
        assert!(text.contains("reddit.com"));
        assert!(text.contains("steam.exe"));
    }

    #[test]
    fn a_browser_closed_for_want_of_the_extension_says_so_where_it_will_be_read() {
        let mut status = status(vec![]);
        status.unwatched.insert("chrome.exe".into());

        let items = menu(&status);
        let notes: Vec<&String> = items
            .iter()
            .filter_map(|i| match i {
                Item::Note(note) => Some(note),
                _ => None,
            })
            .collect();
        assert!(
            notes.iter().any(|n| n.contains("chrome.exe") && n.contains("extension")),
            "the menu blamed nothing for the browser vanishing: {notes:?}"
        );
        assert!(notes.iter().any(|n| n.contains("see details")));

        // The details have to carry the fix, not just the fact: a user who is told what happened
        // and not what to do about it will conclude that Curfew is broken.
        let text = details(&status);
        assert!(text.contains("chrome.exe"));
        assert!(text.contains("curfew extension"));
    }

    #[test]
    fn a_tray_with_nothing_wrong_never_mentions_the_extension() {
        let text = details(&status(vec![]));
        assert!(!text.contains("extension"), "an idle tray advertised at the user: {text}");
    }

    fn freezing(origin: curfew_core::Origin) -> Status {
        Status {
            now: NOW,
            freeze: Some(curfew_core::frozen::announce(NOW, "frozen", 3600, origin, 60)),
            ..Default::default()
        }
    }

    #[test]
    fn a_countdown_is_the_first_thing_on_the_menu_and_can_always_be_called_off() {
        let items = menu(&freezing(curfew_core::Origin::Local));
        assert!(matches!(&items[0], Item::Note(n) if n.contains("save your work")), "{items:?}");
        assert!(items.iter().any(|i| matches!(i, Item::CancelFreeze { .. })));
    }

    #[test]
    fn a_peer_request_asks_rather_than_announces_and_is_still_cancellable() {
        let items = menu(&freezing(curfew_core::Origin::Peer));
        assert!(matches!(&items[0], Item::Note(n) if n.contains("nothing has happened yet")));
        assert!(items.iter().any(|i| matches!(i, Item::ConfirmFreeze { .. })));
        assert!(items.iter().any(|i| matches!(i, Item::CancelFreeze { .. })));
    }

    #[test]
    fn a_local_countdown_is_never_offered_a_confirmation_it_does_not_need() {
        let items = menu(&freezing(curfew_core::Origin::Local));
        assert!(!items.iter().any(|i| matches!(i, Item::ConfirmFreeze { .. })));
    }

    #[test]
    fn a_quiet_machine_offers_nothing_to_cancel() {
        assert!(!menu(&status(vec![]))
            .iter()
            .any(|i| matches!(i, Item::CancelFreeze { .. } | Item::ConfirmFreeze { .. })));
    }

    #[test]
    fn a_wait_in_flight_is_visible_without_offering_anything_to_click() {
        let mut status = status(vec![]);
        status.delayed.insert("slack.exe".into(), 9);
        let items = menu(&status);
        assert!(items
            .iter()
            .any(|i| matches!(i, Item::Note(n) if n.contains("slack.exe opens in 9 s"))));
        // Nothing to click: a wait that could be dismissed from the menu would not be a wait.
        assert!(!items.iter().any(|i| matches!(i, Item::End { .. } | Item::Unlock { .. })));
    }

    #[test]
    fn remaining_time_is_rounded_up_so_nothing_reads_as_zero_while_it_is_still_running() {
        assert_eq!(remaining(NOW, Some(NOW + 1)), "1 minute left");
        assert_eq!(remaining(NOW, Some(NOW + 3600)), "60 minutes left");
        assert_eq!(remaining(NOW, Some(NOW + 2 * 3600)), "2 h 0 min left");
        assert_eq!(remaining(NOW, None), "until released");
    }
}
