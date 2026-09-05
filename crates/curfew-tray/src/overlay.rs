//! The screen that explains why an application just disappeared.
//!
//! Closing someone's window without saying anything is the worst thing a blocker does: it is
//! indistinguishable from a crash, it invites a second launch, and it makes the tool feel hostile
//! rather than agreed-to. So every close gets a sentence, and the sentence says what the user asked
//! for, when it ends, and how to get out early if there is a way.
//!
//! The overlay is deliberately not a wall. It does not cover the screen, does not take the keyboard,
//! and closes on a click or on its own after a few seconds — a modal that had to be dismissed would
//! be one more thing to fight with while trying to work.

use curfew_core::Timestamp;
use curfew_win::ipc::Status;
use std::collections::BTreeSet;

/// How long the notice stays up.
pub const DWELL_MS: u32 = 7_000;

/// Names closed since the previous poll.
///
/// The service reports what the last pass closed, and passes repeat every couple of seconds while
/// an application keeps relaunching. Only the names that were not there before are new, so a program
/// that respawns in a loop produces one explanation rather than a flood of them.
pub fn newly_closed(previous: &BTreeSet<String>, current: &BTreeSet<String>) -> Vec<String> {
    current.difference(previous).cloned().collect()
}

/// A pretty name for an executable: what the user calls the thing, not what the file is called.
fn app_name(exe: &str) -> String {
    let stem = exe.strip_suffix(".exe").unwrap_or(exe);
    let mut chars = stem.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => stem.to_string(),
    }
}

/// The sentence shown when `closed` were closed.
pub fn message(closed: &[String], status: &Status) -> String {
    let names: Vec<String> = closed.iter().map(|exe| app_name(exe)).collect();
    let what = match names.as_slice() {
        [] => return String::new(),
        [one] => one.clone(),
        [first, second] => format!("{first} and {second}"),
        many => format!("{} and {} others", many[0], many.len() - 1),
    };

    let profile = status.running.first().map(|s| s.profile.clone());
    let mut text = match profile {
        Some(profile) => format!("{what} is blocked during {profile}."),
        // No session and yet something was closed: the pass that closed it has since ended. Saying
        // "you asked for this" would be wrong, so the notice states the fact and stops.
        None => format!("{what} was closed by Curfew."),
    };

    let ends = status.running.iter().filter_map(|s| s.lock.ends_at).max();
    if let Some(ends) = ends {
        text.push_str(&format!("\n\nIt ends at {}.", crate::menu::when(ends)));
    }

    let locked = status
        .running
        .iter()
        .any(|s| s.lock.conditions.iter().any(|l| !matches!(l, curfew_core::Lock::Timer)));
    if locked {
        text.push_str("\nThe Curfew icon can end it early, or start the 24-hour release.");
    }
    text
}

/// The sentence shown when an app is being held behind a delay rule.
///
/// Said differently from a block on purpose. Nothing has been refused here — the app opens in a few
/// seconds whatever the user does — so the notice reads as a pause, not as a wall, and it never
/// mentions the release or the lock, neither of which has anything to do with it.
pub fn waiting_message(exe: &str, seconds_left: i64) -> String {
    let name = app_name(exe);
    match seconds_left.max(0) {
        0 | 1 => format!(
            "{name} opens in a moment.

This pause is what you asked for."
        ),
        n => format!(
            "{name} opens in {n} seconds.

This pause is what you asked for. Open it again when              the wait is up."
        ),
    }
}

/// Which held apps are new since the last poll, so a wait produces one notice and not one per pass.
pub fn newly_delayed(
    previous: &BTreeSet<String>,
    current: &std::collections::BTreeMap<String, i64>,
) -> Vec<(String, i64)> {
    current
        .iter()
        .filter(|(exe, _)| !previous.contains(*exe))
        .map(|(exe, left)| (exe.clone(), *left))
        .collect()
}

/// Whether a notice is worth showing at all, given what is happening.
///
/// Nothing is shown for an application the user has not touched since the last one: repetition is
/// how a helpful notice becomes an enemy.
pub fn should_show(newly: &[String], last_shown: Option<Timestamp>, now: Timestamp) -> bool {
    if newly.is_empty() {
        return false;
    }
    // At most one notice every ten seconds, whatever happens. An application relaunching under a
    // different name each time must not be able to turn Curfew into a popup machine.
    match last_shown {
        Some(at) => now - at >= 10,
        None => true,
    }
}

#[cfg(windows)]
mod sys {
    use std::os::windows::ffi::OsStrExt as _;
    use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, RECT, WPARAM};
    use windows_sys::Win32::Graphics::Gdi::{
        BeginPaint, CreateSolidBrush, DrawTextW, EndPaint, FillRect, SetBkMode, SetTextColor,
        DT_CENTER, DT_VCENTER, DT_WORDBREAK, PAINTSTRUCT, TRANSPARENT,
    };
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DestroyWindow, GetClientRect, GetSystemMetrics, KillTimer,
        RegisterClassW, SetTimer, ShowWindow, SM_CXSCREEN, SM_CYSCREEN, SW_SHOWNA, WM_DESTROY,
        WM_LBUTTONUP, WM_PAINT, WM_TIMER, WNDCLASSW, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW,
        WS_EX_TOPMOST, WS_POPUP,
    };

    const CLOSE_TIMER: usize = 7;
    const WIDTH: i32 = 520;
    const HEIGHT: i32 = 190;

    thread_local! {
        static TEXT: std::cell::RefCell<Vec<u16>> = const { std::cell::RefCell::new(Vec::new()) };
    }

    fn wide(text: &str) -> Vec<u16> {
        std::ffi::OsStr::new(text).encode_wide().chain(std::iter::once(0)).collect()
    }

    unsafe extern "system" fn overlay_proc(
        window: HWND,
        message: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        match message {
            WM_PAINT => {
                let mut paint: PAINTSTRUCT = std::mem::zeroed();
                let dc = BeginPaint(window, &mut paint);
                let mut rect: RECT = std::mem::zeroed();
                GetClientRect(window, &mut rect);
                let brush = CreateSolidBrush(0x00201810);
                FillRect(dc, &rect, brush);
                SetBkMode(dc, TRANSPARENT as i32);
                SetTextColor(dc, 0x00F0F0F0);
                let mut inner = RECT {
                    left: rect.left + 24,
                    top: rect.top + 24,
                    right: rect.right - 24,
                    bottom: rect.bottom - 24,
                };
                TEXT.with(|text| {
                    let text = text.borrow();
                    DrawTextW(
                        dc,
                        text.as_ptr(),
                        -1,
                        &mut inner,
                        DT_CENTER | DT_VCENTER | DT_WORDBREAK,
                    );
                });
                EndPaint(window, &paint);
                0
            }
            // Clicking it dismisses it. There is nothing behind the click: the overlay explains, it
            // does not ask.
            WM_LBUTTONUP | WM_TIMER => {
                DestroyWindow(window);
                0
            }
            WM_DESTROY => {
                KillTimer(window, CLOSE_TIMER);
                0
            }
            _ => DefWindowProcW(window, message, wparam, lparam),
        }
    }

    /// Put the notice on screen. It never takes focus, so it cannot steal a keystroke from whatever
    /// the user moved on to.
    pub fn show(text: &str, dwell_ms: u32) {
        unsafe {
            let instance = GetModuleHandleW(std::ptr::null());
            let class_name = wide("CurfewOverlay");
            let mut class: WNDCLASSW = std::mem::zeroed();
            class.lpfnWndProc = Some(overlay_proc);
            class.hInstance = instance;
            class.lpszClassName = class_name.as_ptr();
            // Registering twice is harmless and returns zero; the second overlay reuses the class.
            RegisterClassW(&class);

            TEXT.with(|slot| *slot.borrow_mut() = wide(text));

            let screen_w = GetSystemMetrics(SM_CXSCREEN);
            let screen_h = GetSystemMetrics(SM_CYSCREEN);
            let window = CreateWindowExW(
                WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                class_name.as_ptr(),
                class_name.as_ptr(),
                WS_POPUP,
                (screen_w - WIDTH) / 2,
                // A third of the way down rather than centred: the middle of the screen is where the
                // work is.
                screen_h / 3,
                WIDTH,
                HEIGHT,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                instance,
                std::ptr::null(),
            );
            if window.is_null() {
                return;
            }
            ShowWindow(window, SW_SHOWNA);
            SetTimer(window, CLOSE_TIMER, dwell_ms, None);
        }
    }
}

#[cfg(not(windows))]
mod sys {
    pub fn show(_text: &str, _dwell_ms: u32) {}
}

pub use sys::show;

#[cfg(test)]
mod delay_tests {
    use super::*;
    use std::collections::BTreeMap;

    #[test]
    fn a_wait_is_described_as_a_pause_and_never_as_a_block() {
        let text = waiting_message("slack.exe", 15);
        assert!(text.contains("Slack opens in 15 seconds"));
        assert!(!text.to_lowercase().contains("blocked"));
        assert!(!text.contains("release"), "a delay has nothing to do with the 24-hour release");
    }

    #[test]
    fn the_last_second_does_not_read_as_a_countdown_of_one() {
        assert!(waiting_message("slack.exe", 1).contains("in a moment"));
        assert!(waiting_message("slack.exe", 0).contains("in a moment"));
    }

    #[test]
    fn a_wait_already_being_shown_is_not_announced_again_every_pass() {
        let current = BTreeMap::from([("slack.exe".to_string(), 12)]);
        let seen = BTreeSet::from(["slack.exe".to_string()]);
        assert!(newly_delayed(&seen, &current).is_empty());
        assert_eq!(newly_delayed(&BTreeSet::new(), &current), vec![("slack.exe".to_string(), 12)]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_core::{Lock, LockSet, Session, SessionSource};

    const NOW: Timestamp = 1_788_510_600;

    fn set(names: &[&str]) -> BTreeSet<String> {
        names.iter().map(|n| n.to_string()).collect()
    }

    fn status(conditions: Vec<Lock>) -> Status {
        Status {
            now: NOW,
            running: vec![Session {
                id: "s1".into(),
                profile: "deep work".into(),
                source: SessionSource::Manual,
                started_at: NOW,
                lock: LockSet::new(conditions, Some(NOW + 3600)),
            }],
            ..Default::default()
        }
    }

    #[test]
    fn an_application_that_keeps_relaunching_is_explained_once() {
        let first = newly_closed(&set(&[]), &set(&["steam.exe"]));
        assert_eq!(first, vec!["steam.exe".to_string()]);

        // The next pass closes it again, and reports the same name.
        let again = newly_closed(&set(&["steam.exe"]), &set(&["steam.exe"]));
        assert!(again.is_empty(), "a relaunching app would produce a popup per pass");
    }

    #[test]
    fn a_second_application_is_explained_even_while_the_first_is_still_being_closed() {
        let newly = newly_closed(&set(&["steam.exe"]), &set(&["steam.exe", "discord.exe"]));
        assert_eq!(newly, vec!["discord.exe".to_string()]);
    }

    #[test]
    fn notices_are_rate_limited_however_many_names_appear() {
        assert!(should_show(&["steam.exe".into()], None, NOW));
        assert!(!should_show(&["steam.exe".into()], Some(NOW - 3), NOW));
        assert!(should_show(&["steam.exe".into()], Some(NOW - 30), NOW));
        assert!(!should_show(&[], None, NOW));
    }

    #[test]
    fn the_message_names_the_app_the_profile_and_the_end_time() {
        let text = message(&["steam.exe".into()], &status(vec![Lock::Timer]));
        assert!(text.contains("Steam"));
        assert!(text.contains("deep work"));
        assert!(text.contains("It ends at"));
    }

    #[test]
    fn a_locked_session_is_told_where_the_way_out_is() {
        let text = message(&["steam.exe".into()], &status(vec![Lock::DeviceCredential]));
        assert!(text.contains("24-hour release"));
    }

    #[test]
    fn a_timer_only_session_is_not_offered_a_way_out_it_does_not_have() {
        let text = message(&["steam.exe".into()], &status(vec![Lock::Timer]));
        assert!(!text.contains("24-hour release"));
    }

    #[test]
    fn several_apps_at_once_are_summarised_rather_than_listed_forever() {
        let closed: Vec<String> =
            ["a.exe", "b.exe", "c.exe", "d.exe"].iter().map(|s| s.to_string()).collect();
        let text = message(&closed, &status(vec![Lock::Timer]));
        assert!(text.contains("3 others"), "{text}");
    }

    #[test]
    fn a_close_with_no_session_running_does_not_claim_the_user_asked_for_it() {
        let text = message(&["steam.exe".into()], &Status { now: NOW, ..Default::default() });
        assert!(text.contains("was closed by Curfew"));
        assert!(!text.contains("during"));
    }
}
