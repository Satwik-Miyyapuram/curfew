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
        /// Whether the session also asks to be confirmed first.
        ///
        /// `Lock::Confirm` is the one condition this menu *can* satisfy, and it could not until now.
        /// It was classified with the conditions that live elsewhere — a tag, a peer, a restart — so
        /// a session locked "ask me first" got the sentence "this session is locked elsewhere" and no
        /// action at all, from the one surface most Windows users ever open. The window could end it
        /// and the tray could not, which is a worse bug than either being unable to: the product
        /// disagreed with itself about whether the lock had an exit.
        confirm: bool,
        label: String,
    },
    /// End one that needs the machine's password, via the operating system's own prompt.
    Unlock {
        id: String,
        label: String,
    },
    /// Spend an emergency pass on a session no evidence here can end.
    Emergency {
        id: String,
        label: String,
    },
    /// Give the release a lock on this or another device is waiting on this machine for.
    PeerRelease {
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
    /// Say again what the first-run notice said.
    ///
    /// That notice explains why Windows called an unsigned build unrecognised and why the icon is
    /// not what does the blocking. It is shown once, which is right for something nobody wants at
    /// every login and wrong for the only place those two facts are written down: a user who
    /// dismissed it, or who inherited the machine, had no way back to it.
    About,
    /// Open the Curfew window.
    ///
    /// The window is where a block is started by hand, and the tray is the surface almost every
    /// Windows user actually sees. Until this item existed the two had no route between them: the
    /// icon could end a session but not begin one, and the window was reachable only from the Start
    /// menu — which is the wrong way round, because the icon is the thing in front of them.
    OpenWindow,
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

        // Which conditions this menu can actually satisfy by being clicked.
        //
        // The credential is proved by the operating system's own prompt. A confirmation is proved by
        // asking — the menu shows a Yes/No before it sends anything, so the item *is* the dialog.
        // Everything else (a tag scanned, a challenge answered in the app, a restart, a peer) lives
        // somewhere this menu cannot reach, and an item that opened a prompt leading nowhere would be
        // worse than no item at all.
        let credential = conditions.iter().any(|lock| matches!(lock, Lock::DeviceCredential));
        let confirm = conditions.iter().any(|lock| matches!(lock, Lock::Confirm));
        let others =
            conditions.iter().any(|lock| !matches!(lock, Lock::DeviceCredential | Lock::Confirm));
        let releasable = status.releasable.contains(&session.id);

        // Said before the actions, because it is the answer to "why can I not end this?" and it is
        // the one condition the user satisfies by doing something to the whole machine.
        if conditions.iter().any(|lock| matches!(lock, Lock::RestartRequired)) {
            items.push(Item::Note("    needs this machine restarted".to_string()));
        }
        for lock in &conditions {
            if let Lock::Token { id } = lock {
                items.push(Item::Note(format!("    needs the tag {id}")));
            }
        }
        if releasable {
            if status.released.contains(&session.id) {
                items.push(Item::Note("    you have released this; the lock is elsewhere".into()));
            } else {
                items.push(Item::PeerRelease {
                    id: session.id.clone(),
                    label: format!("Release {} — this device is the one it asks", session.profile),
                });
            }
        }

        if others {
            items.push(Item::Note("    this session is locked elsewhere".to_string()));
        } else if credential {
            items.push(Item::Unlock {
                id: session.id.clone(),
                label: format!("End {} with your Windows password", session.profile),
            });
        } else if confirm {
            // Offered with a Yes/No in front of it, which is what "ask me first" asked for. The
            // label says so, because an item that then opens a dialog should not be a surprise, and
            // it is also the honest description: the menu is asking on the lock's behalf.
            items.push(Item::End {
                id: session.id.clone(),
                confirm: true,
                label: format!("End {} — it asks to be confirmed", session.profile),
            });
        } else if session.lock.ends_at.is_some_and(|ends| ends > status.now) {
            // A timer that has not run out is a lock; the service will refuse, and it is honest to
            // say so on the item rather than to offer it and fail.
            items.push(Item::Note("    ends when the timer runs out".to_string()));
        } else {
            items.push(Item::End {
                id: session.id.clone(),
                confirm: false,
                label: format!("End {}", session.profile),
            });
        }

        // Offered only where it is the only way out, and only when there is one to spend. A hatch
        // shown next to a session that can simply be ended would train people to reach for the
        // scarce thing first.
        if (credential || others) && status.passes_left > 0 {
            items.push(Item::Emergency {
                id: session.id.clone(),
                label: format!(
                    "Use an emergency pass on {} ({} left)",
                    session.profile, status.passes_left
                ),
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

    // Why the hatch is not on the menu, said only when someone is actually locked and would look
    // for it. A device with nothing running does not need to be told about a pass it does not need.
    if !status.running.is_empty() {
        match &status.pass_refusal {
            Some(curfew_core::PassRefusal::QuotaSpent { next_at }) => items
                .push(Item::Note(format!("    no emergency passes left until {}", when(*next_at)))),
            Some(curfew_core::PassRefusal::CoolingDown { until }) => items
                .push(Item::Note(format!("    next emergency pass available {}", when(*until)))),
            // Disabled is not mentioned: a hatch nobody switched on is not missing.
            _ => {}
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
    // Above the two "explain something" items, because it is the only one that *does* something:
    // starting a block is the product's whole verb, and this icon is the surface most people see.
    items.push(Item::OpenWindow);
    items.push(Item::Details);
    items.push(Item::About);
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
        // What the user will actually see, said here because Curfew cannot say it in the browser.
        //
        // A blocked domain is answered `0.0.0.0`, deliberately and for good reasons (`hosts::SINK`,
        // `dns::refusal`): a local web server is common on a developer's machine, so `127.0.0.1` would
        // serve that server's pages, and showing a page of Curfew's own for somebody else's domain
        // means holding a certificate for it, which is not a thing this program will ever do.
        //
        // The cost of that decision is that the user gets the browser's own "can't be reached" page
        // and no other sign that Curfew is involved. From where they are sitting the internet broke.
        // Serving a page is closed off, so the next best thing is to make the *symptom* legible — and
        // this list is the only place Curfew has to do it, so it does it here rather than nowhere.
        text.push_str(
            "A blocked site shows your browser's own \u{201c}can't be reached\u{201d} page. Curfew \
             refuses the name rather than serving a page, because it will not hold a certificate for \
             somebody else's domain. If a site fails that way while a session is running, that is \
             Curfew and not your connection.\n",
        );
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
    fn the_hatch_is_offered_only_where_it_is_the_only_way_out() {
        let mut with_passes = status(vec![session([Lock::DeviceCredential], Some(NOW + 3600))]);
        with_passes.passes_left = 2;
        let items = menu(&with_passes);
        assert!(
            items.iter().any(|i| matches!(i, Item::Emergency { .. })),
            "a locked session with a ration left was offered no way to spend it"
        );

        // An expired timer can simply be ended. Offering a scarce pass next to the free way out
        // would teach people to reach for the expensive one.
        let mut endable = status(vec![session([Lock::Timer], Some(NOW - 1))]);
        endable.passes_left = 2;
        assert!(!menu(&endable).iter().any(|i| matches!(i, Item::Emergency { .. })));
    }

    #[test]
    fn a_spent_ration_is_explained_rather_than_offered() {
        let mut spent = status(vec![session([Lock::DeviceCredential], Some(NOW + 3600))]);
        spent.passes_left = 0;
        spent.pass_refusal = Some(curfew_core::PassRefusal::CoolingDown { until: NOW + 3600 });

        let items = menu(&spent);

        assert!(!items.iter().any(|i| matches!(i, Item::Emergency { .. })));
        assert!(
            items.iter().any(
                |i| matches!(i, Item::Note(text) if text.contains("next emergency pass available"))
            ),
            "the menu went silent about a hatch that exists and is merely not ready"
        );
    }

    #[test]
    fn a_hatch_nobody_switched_on_is_never_mentioned() {
        let mut off = status(vec![session([Lock::DeviceCredential], Some(NOW + 3600))]);
        off.pass_refusal = Some(curfew_core::PassRefusal::Disabled);
        assert!(!menu(&off)
            .iter()
            .any(|i| matches!(i, Item::Note(text) if text.contains("emergency"))));
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

    /// The regression guard for a gap in this menu, not in the service.
    ///
    /// `Lock::Confirm` used to be classified with the conditions that live elsewhere, so a session
    /// locked "ask me first" was described as *"this session is locked elsewhere"* and given no
    /// action — from the one surface most Windows users ever open. The window could end it and the
    /// tray could not, which is worse than either failing: the product disagreed with itself about
    /// whether the lock had an exit.
    #[test]
    fn a_confirmation_lock_offers_a_way_to_end_it() {
        let items = menu(&status(vec![session([Lock::Confirm], Some(NOW + 3600))]));

        assert!(
            items.iter().any(|i| matches!(i, Item::End { confirm: true, .. })),
            "a lock that only asks to be confirmed offered no way to confirm it: {items:?}"
        );
        assert!(
            !items
                .iter()
                .any(|i| matches!(i, Item::Note(text) if text.contains("locked elsewhere"))),
            "a confirmation is satisfiable here and was described as unreachable: {items:?}"
        );
    }

    /// …and the claim travels with the item rather than being assumed by the sender, because the
    /// Yes/No the shell shows is what makes the claim true.
    ///
    /// A session with no conditions and no end time can simply be ended, so it must not ask to be
    /// confirmed — otherwise every ordinary block would open a dialog nobody requested.
    #[test]
    fn a_lock_with_nothing_to_confirm_does_not_ask_to_be_confirmed() {
        let items = menu(&status(vec![session([], None)]));
        assert!(
            items.iter().any(|i| matches!(i, Item::End { confirm: false, .. })),
            "an unlocked session was not offered as endable: {items:?}"
        );
        assert!(
            !items.iter().any(|i| matches!(i, Item::End { confirm: true, .. })),
            "a session with nothing to confirm offered a confirmation: {items:?}"
        );
    }

    /// A timer that has not run out is a lock, so there is nothing to click — `ends_at` in the future
    /// is the condition, and offering an End item would offer something the service refuses.
    #[test]
    fn a_running_timer_is_reported_rather_than_offered() {
        let items = menu(&status(vec![session([], Some(NOW + 3600))]));
        assert!(
            !items.iter().any(|i| matches!(i, Item::End { .. })),
            "a timer with time left was offered as endable: {items:?}"
        );
        assert!(items.iter().any(|i| matches!(i, Item::Note(text) if text.contains("runs out"))));
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

    /// The symptom is named, because Curfew cannot show a page of its own.
    ///
    /// A blocked domain is answered `0.0.0.0`, so the user gets the browser's error page and no other
    /// sign that Curfew was involved — the largest population of users meets this product as "the
    /// internet broke". Serving a page is closed off for good reasons (see `hosts::SINK`), so the
    /// detail text is the only surface that can connect the two, and this pins that it does.
    #[test]
    fn the_details_say_what_a_blocked_site_looks_like() {
        let mut status = status(vec![]);
        status.blocked_domains.insert("reddit.com".into());

        let text = details(&status);

        assert!(
            text.contains("can't be reached"),
            "the browser's own symptom was not named: {text}"
        );
        assert!(
            text.contains("Curfew") && text.contains("not your connection"),
            "the sentence does not say this is Curfew and not a fault: {text}"
        );
    }

    /// …and is not said when nothing is blocked, because then the symptom means something else — a
    /// dead connection, or a broken hosts file — and blaming Curfew for it would be a lie.
    #[test]
    fn an_idle_tray_does_not_explain_a_page_it_did_not_block() {
        let text = details(&status(vec![]));
        assert!(text.contains("No websites are blocked"));
        assert!(
            !text.contains("can't be reached"),
            "a machine blocking nothing explained a blocked-page symptom: {text}"
        );
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

    /// A restart is the one condition satisfied by doing something to the whole machine, so it has
    /// to be said before the actions rather than left under "locked elsewhere".
    #[test]
    fn a_restart_lock_says_what_it_wants() {
        let items = menu(&status(vec![session([Lock::RestartRequired], None)]));
        assert!(
            items.iter().any(|i| matches!(i, Item::Note(n) if n.contains("machine restarted"))),
            "the menu did not say a restart was needed: {items:?}"
        );
    }

    /// Naming the tag is the whole use of the note: "locked elsewhere" would send someone hunting
    /// through the house for a tag they cannot identify.
    #[test]
    fn a_token_lock_names_the_tag_by_its_id() {
        let items = menu(&status(vec![session([Lock::Token { id: "fridge".into() }], None)]));
        assert!(
            items.iter().any(|i| matches!(i, Item::Note(n) if n.contains("the tag fridge"))),
            "the menu did not name the tag: {items:?}"
        );
    }

    #[test]
    fn a_peer_lock_this_device_is_asked_for_is_a_button_and_not_a_note() {
        let mut s = status(vec![session([Lock::PeerRelease { device_id: "PC1".into() }], None)]);
        s.releasable = vec!["s1".into()];
        let items = menu(&s);
        assert!(
            items.iter().any(|i| matches!(i, Item::PeerRelease { id, .. } if id == "s1")),
            "the device the lock names was not offered the release: {items:?}"
        );
    }

    /// The release cannot be taken back, so offering it twice would suggest it could be redone --
    /// and pressing it again would do nothing anyone could see.
    #[test]
    fn a_release_already_given_is_reported_rather_than_offered_again() {
        let mut s = status(vec![session([Lock::PeerRelease { device_id: "PC1".into() }], None)]);
        s.releasable = vec!["s1".into()];
        s.released = vec!["s1".into()];
        let items = menu(&s);
        assert!(!items.iter().any(|i| matches!(i, Item::PeerRelease { .. })), "offered twice");
        assert!(items
            .iter()
            .any(|i| matches!(i, Item::Note(n) if n.contains("you have released"))));
    }

    #[test]
    fn a_peer_lock_naming_another_device_offers_nothing_to_press() {
        let items =
            menu(&status(vec![session([Lock::PeerRelease { device_id: "PHONE7".into() }], None)]));
        assert!(
            !items.iter().any(|i| matches!(i, Item::PeerRelease { .. } | Item::End { .. })),
            "a lock for another device offered a way out here: {items:?}"
        );
    }
}
