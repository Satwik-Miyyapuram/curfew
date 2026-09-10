//! The Win32 half: an invisible window, an icon beside the clock, and a menu built fresh each time.
//!
//! Nothing is cached. The menu is built from a status fetched at the moment of the click, because a
//! menu drawn from a minute-old answer can offer to end a session that has already ended, and the
//! whole value of this icon is that it tells the truth about what is running.

use crate::menu::{self, Item};
use crate::{act, ask, describe, prompt};
use curfew_win::ipc::{Request, Status};
use std::cell::RefCell;
use std::os::windows::ffi::OsStrExt as _;
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::Shell::{
    Shell_NotifyIconW, NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD, NIM_DELETE, NIM_MODIFY,
    NOTIFYICONDATAW,
};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyMenu, DestroyWindow,
    DispatchMessageW, GetCursorPos, GetMessageW, KillTimer, LoadIconW, MessageBoxW,
    PostQuitMessage, RegisterClassW, SetForegroundWindow, SetTimer, TrackPopupMenu,
    TranslateMessage, HMENU, IDI_INFORMATION, IDYES, MB_ICONINFORMATION, MB_ICONWARNING, MB_OK,
    MB_YESNO, MF_GRAYED, MF_SEPARATOR, MF_STRING, MSG, TPM_BOTTOMALIGN, TPM_RIGHTALIGN, WM_APP,
    WM_COMMAND, WM_DESTROY, WM_RBUTTONUP, WM_TIMER, WNDCLASSW, WS_OVERLAPPED,
};

/// The message the shell sends us when someone clicks the icon.
const TRAY_MESSAGE: u32 = WM_APP + 1;
/// The timer that refreshes the tooltip.
const TOOLTIP_TIMER: usize = 1;
/// How often the tooltip is refreshed. Slow: it is a tooltip, and the menu is what has to be exact.
const TOOLTIP_MS: u32 = 15_000;
/// The timer that watches for applications the service has closed.
const WATCH_TIMER: usize = 2;
/// How often that watch runs. Short, because the explanation is only useful while the user is still
/// wondering where the window went.
const WATCH_MS: u32 = 2_000;

thread_local! {
    /// What the service reported closed on the previous poll, so only new names are explained.
    static CLOSED: RefCell<std::collections::BTreeSet<String>> =
        const { RefCell::new(std::collections::BTreeSet::new()) };
    /// The freeze already announced on screen, so one countdown is one warning.
    static ANNOUNCED: std::cell::Cell<Option<curfew_core::Timestamp>> =
        const { std::cell::Cell::new(None) };
    /// Apps already announced as waiting, so one wait is one notice.
    static WAITING: RefCell<std::collections::BTreeSet<String>> =
        const { RefCell::new(std::collections::BTreeSet::new()) };
    /// When the last notice went up, for the rate limit.
    static LAST_SHOWN: std::cell::Cell<Option<curfew_core::Timestamp>> =
        const { std::cell::Cell::new(None) };
}

thread_local! {
    /// The items behind the menu currently on screen. Command ids are indices into this, so the
    /// list is replaced every time the menu is built and never consulted otherwise.
    static ITEMS: RefCell<Vec<Item>> = const { RefCell::new(Vec::new()) };
}

fn wide(text: &str) -> Vec<u16> {
    std::ffi::OsStr::new(text).encode_wide().chain(std::iter::once(0)).collect()
}

fn say(window: HWND, text: &str) {
    if text.is_empty() {
        return;
    }
    let body = wide(text);
    let caption = wide("Curfew");
    unsafe {
        MessageBoxW(window, body.as_ptr(), caption.as_ptr(), MB_OK | MB_ICONINFORMATION);
    }
}

/// One line for the tooltip: what a glance at the icon should tell you.
fn tooltip(status: &Status) -> String {
    if let Some(countdown) = &status.freeze {
        return format!(
            "Curfew — freezing everything in {} s",
            curfew_core::frozen::remaining(countdown, status.now)
        );
    }
    match status.running.len() {
        0 => "Curfew — nothing running".to_string(),
        1 => format!("Curfew — {} running", status.running[0].profile),
        n => format!("Curfew — {n} sessions running"),
    }
}

fn icon_data(window: HWND) -> NOTIFYICONDATAW {
    let mut data: NOTIFYICONDATAW = unsafe { std::mem::zeroed() };
    data.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
    data.hWnd = window;
    data.uID = 1;
    data.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    data.uCallbackMessage = TRAY_MESSAGE;
    data.hIcon = unsafe { LoadIconW(std::ptr::null_mut(), IDI_INFORMATION) };
    data
}

fn set_tip(data: &mut NOTIFYICONDATAW, text: &str) {
    let tip = wide(text);
    let len = tip.len().min(data.szTip.len());
    data.szTip[..len].copy_from_slice(&tip[..len]);
    // Truncation is possible for a very long profile name, so the last cell is forced to a
    // terminator rather than trusted.
    if let Some(last) = data.szTip.get_mut(len.saturating_sub(1)) {
        *last = 0;
    }
}

/// Put `text` on the icon.
fn show_tip(window: HWND, text: &str) {
    let mut data = icon_data(window);
    set_tip(&mut data, text);
    unsafe { Shell_NotifyIconW(NIM_MODIFY, &data) };
}

fn refresh_tooltip(window: HWND) {
    let text = match ask(&Request::Status) {
        Ok(curfew_win::ipc::Response::Status(status)) => tooltip(&status),
        // Said on the icon itself, because an unreachable service means nothing is being enforced
        // and that is not something to find out by opening a menu.
        _ => "Curfew — not running".to_string(),
    };
    show_tip(window, &text);
}

/// Notice what the service has closed since the last poll, and explain it.
///
/// An unreachable service clears the memory rather than keeping it: when the service comes back, the
/// first thing it reports is not news the user needs a popup about.
fn watch_closures(window: HWND) {
    let Ok(curfew_win::ipc::Response::Status(status)) = ask(&Request::Status) else {
        CLOSED.with(|slot| slot.borrow_mut().clear());
        return;
    };
    // A countdown gets the screen, not a tooltip. It is the one thing Curfew does that can cost
    // work the user cannot get back, so it is announced where they are looking, once per freeze.
    let announced = status.freeze.as_ref().map(|c| c.fires_at);
    if announced != ANNOUNCED.with(|slot| slot.get()) {
        ANNOUNCED.with(|slot| slot.set(announced));
        if let Some(countdown) = &status.freeze {
            crate::overlay::show(
                &curfew_core::frozen::warning(countdown, status.now),
                crate::overlay::DWELL_MS,
            );
        }
    }

    // While a countdown runs the icon carries the seconds, so it is refreshed on this timer rather
    // than the slow one: a tooltip reading "in 60 s" a minute after the fact is worse than none.
    if status.freeze.is_some() {
        show_tip(window, &tooltip(&status));
    }

    // A held app is closed too, so it needs its own sentence — and it needs the *right* one. Saying
    // "blocked" about something that opens again in ten seconds is the sort of lie that makes people
    // stop reading the notices.
    let waiting =
        WAITING.with(|slot| crate::overlay::newly_delayed(&slot.borrow(), &status.delayed));
    WAITING.with(|slot| *slot.borrow_mut() = status.delayed.keys().cloned().collect());
    if let Some((exe, left)) = waiting.first() {
        crate::overlay::show(
            &crate::overlay::waiting_message(exe, *left),
            crate::overlay::DWELL_MS,
        );
        return;
    }

    let newly = CLOSED.with(|slot| crate::overlay::newly_closed(&slot.borrow(), &status.closed));
    CLOSED.with(|slot| *slot.borrow_mut() = status.closed.clone());

    let last = LAST_SHOWN.with(|slot| slot.get());
    if !crate::overlay::should_show(&newly, last, status.now) {
        return;
    }
    LAST_SHOWN.with(|slot| slot.set(Some(status.now)));
    crate::overlay::show(&crate::overlay::message(&newly, &status), crate::overlay::DWELL_MS);
}

fn show_menu(window: HWND) {
    let status = match ask(&Request::Status) {
        Ok(curfew_win::ipc::Response::Status(status)) => status,
        Ok(other) => {
            say(window, &describe(&other));
            return;
        }
        Err(detail) => {
            say(window, &detail);
            return;
        }
    };

    let items = menu::menu(&status);
    let handle: HMENU = unsafe { CreatePopupMenu() };
    if handle.is_null() {
        return;
    }

    for (index, item) in items.iter().enumerate() {
        let id = index + 1;
        match item {
            Item::Separator => unsafe {
                AppendMenuW(handle, MF_SEPARATOR, 0, std::ptr::null());
            },
            // A note is on the menu to be read, so it is drawn greyed rather than left clickable and
            // inert: a menu entry that does nothing when clicked reads as a bug.
            Item::Note(text) => unsafe {
                AppendMenuW(handle, MF_STRING | MF_GRAYED, id, wide(text).as_ptr());
            },
            Item::End { label, .. }
            | Item::Unlock { label, .. }
            | Item::Release { label, .. }
            | Item::Emergency { label, .. }
            | Item::PeerRelease { label, .. }
            | Item::CancelFreeze { label }
            | Item::ConfirmFreeze { label } => unsafe {
                AppendMenuW(handle, MF_STRING, id, wide(label).as_ptr());
            },
            Item::Details => unsafe {
                AppendMenuW(handle, MF_STRING, id, wide("What is blocked…").as_ptr());
            },
            Item::Quit => unsafe {
                AppendMenuW(handle, MF_STRING, id, wide("Hide this icon").as_ptr());
            },
        }
    }

    ITEMS.with(|slot| *slot.borrow_mut() = items);

    let mut point = POINT { x: 0, y: 0 };
    unsafe {
        GetCursorPos(&mut point);
        // Required, and not a formality: without it the menu does not close when the user clicks
        // away from it.
        SetForegroundWindow(window);
        TrackPopupMenu(
            handle,
            TPM_RIGHTALIGN | TPM_BOTTOMALIGN,
            point.x,
            point.y,
            0,
            window,
            std::ptr::null(),
        );
        DestroyMenu(handle);
    }
}

fn chosen(window: HWND, id: usize) {
    let item = ITEMS.with(|slot| slot.borrow().get(id.wrapping_sub(1)).cloned());
    let Some(item) = item else { return };

    match &item {
        Item::Details => {
            let text = match ask(&Request::Status) {
                Ok(curfew_win::ipc::Response::Status(status)) => menu::details(&status),
                Ok(other) => describe(&other),
                Err(detail) => detail,
            };
            say(window, &text);
        }
        Item::Quit => {
            say(window, menu::QUIT_NOTE);
            unsafe { DestroyWindow(window) };
        }
        Item::Unlock { .. } => {
            let credential = prompt::ask(
                window,
                "Ending this session early needs the password for this computer.",
            );
            // Closing the prompt is not an attempt and gets no dialog: changing your mind is the
            // system working, not a failure to report.
            let Some((request, _)) = act(&item, credential) else { return };
            match ask(&request) {
                Ok(response) => say(window, &describe(&response)),
                Err(detail) => say(window, &detail),
            }
            refresh_tooltip(window);
        }
        // A release cannot be taken back, so it is asked about once. The wording says who it
        // frees rather than what it does, because on this machine it usually appears to do nothing
        // at all: the lock it opens is on the other device.
        Item::PeerRelease { .. } => {
            let confirmed = unsafe {
                MessageBoxW(
                    window,
                    wide(
                        "Release this session? The device holding it can then end it whenever it likes, and this cannot be taken back.",
                    )
                    .as_ptr(),
                    wide("Curfew").as_ptr(),
                    MB_YESNO | MB_ICONWARNING,
                ) == IDYES
            };
            if !confirmed {
                return;
            }
            let Some((request, _)) = act(&item, None) else { return };
            match ask(&request) {
                Ok(response) => say(window, &describe(&response)),
                Err(detail) => say(window, &detail),
            }
            refresh_tooltip(window);
        }
        // Spending a pass is asked about first. It is the one item on this menu that consumes
        // something scarce and shared, and a mis-click that burned a week's ration would be the
        // kind of mistake the whole design exists to avoid.
        Item::Emergency { .. } => {
            let confirmed = unsafe {
                MessageBoxW(
                    window,
                    wide(
                        "Use an emergency pass? It ends this session now, it is counted against your ration on every paired device, and it cannot be given back.",
                    )
                    .as_ptr(),
                    wide("Curfew").as_ptr(),
                    MB_YESNO | MB_ICONWARNING,
                ) == IDYES
            };
            if !confirmed {
                return;
            }
            let Some((request, _)) = act(&item, None) else { return };
            match ask(&request) {
                Ok(response) => say(window, &describe(&response)),
                Err(detail) => say(window, &detail),
            }
            refresh_tooltip(window);
        }
        Item::End { .. }
        | Item::Release { .. }
        | Item::CancelFreeze { .. }
        | Item::ConfirmFreeze { .. } => {
            let Some((request, _)) = act(&item, None) else { return };
            match ask(&request) {
                Ok(response) => say(window, &describe(&response)),
                Err(detail) => say(window, &detail),
            }
            refresh_tooltip(window);
        }
        Item::Note(_) | Item::Separator => {}
    }
}

unsafe extern "system" fn window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        TRAY_MESSAGE => {
            // Either button opens the menu. There is no separate left-click action, because the one
            // thing a left click could reasonably do — end the session — is exactly the thing that
            // must never happen by accident.
            let event = (lparam as u32) & 0xffff;
            if event == WM_RBUTTONUP
                || event == windows_sys::Win32::UI::WindowsAndMessaging::WM_LBUTTONUP
            {
                show_menu(window);
            }
            0
        }
        WM_COMMAND => {
            chosen(window, wparam & 0xffff);
            0
        }
        WM_TIMER => {
            match wparam {
                w if w == WATCH_TIMER => watch_closures(window),
                _ => refresh_tooltip(window),
            }
            0
        }
        WM_DESTROY => {
            let data = icon_data(window);
            Shell_NotifyIconW(NIM_DELETE, &data);
            KillTimer(window, TOOLTIP_TIMER);
            KillTimer(window, WATCH_TIMER);
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

/// Put the icon up and pump messages until it is taken down.
pub fn run() {
    unsafe {
        let instance = GetModuleHandleW(std::ptr::null());
        let class_name = wide("CurfewTray");

        let mut class: WNDCLASSW = std::mem::zeroed();
        class.lpfnWndProc = Some(window_proc);
        class.hInstance = instance;
        class.lpszClassName = class_name.as_ptr();
        RegisterClassW(&class);

        // Never shown. The window exists because a tray icon needs somewhere to send its messages.
        let window = CreateWindowExW(
            0,
            class_name.as_ptr(),
            class_name.as_ptr(),
            WS_OVERLAPPED,
            0,
            0,
            0,
            0,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );
        if window.is_null() {
            return;
        }

        let mut data = icon_data(window);
        set_tip(&mut data, "Curfew");
        Shell_NotifyIconW(NIM_ADD, &data);
        refresh_tooltip(window);
        SetTimer(window, TOOLTIP_TIMER, TOOLTIP_MS, None);
        // Seed the memory before the watch starts, so a tray opened while something is already
        // being closed does not explain a closure the user has long since understood.
        if let Ok(curfew_win::ipc::Response::Status(status)) = ask(&Request::Status) {
            WAITING.with(|slot| *slot.borrow_mut() = status.delayed.keys().cloned().collect());
            CLOSED.with(|slot| *slot.borrow_mut() = status.closed);
        }
        SetTimer(window, WATCH_TIMER, WATCH_MS, None);

        // Said after the icon is up, so the notice has an icon to point at, and only ever once.
        if let Some(text) = crate::welcome::take(&crate::welcome::marker_path()) {
            say(window, text);
        }

        let mut message: MSG = std::mem::zeroed();
        while GetMessageW(&mut message, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_core::{LockSet, Session, SessionSource};

    fn status(n: usize) -> Status {
        let running = (0..n)
            .map(|i| Session {
                id: format!("s{i}"),
                profile: format!("profile-{i}"),
                source: SessionSource::Manual,
                started_at: 0,
                lock: LockSet::new([], None),
            })
            .collect();
        Status { running, ..Default::default() }
    }

    #[test]
    fn the_tooltip_says_what_is_running_without_being_opened() {
        assert!(tooltip(&status(0)).contains("nothing running"));
        assert!(tooltip(&status(1)).contains("profile-0"));
        assert!(tooltip(&status(3)).contains("3 sessions"));
    }

    #[test]
    fn a_long_name_cannot_overrun_the_tooltip_buffer() {
        let mut data: NOTIFYICONDATAW = unsafe { std::mem::zeroed() };
        set_tip(&mut data, &"x".repeat(500));
        assert_eq!(*data.szTip.last().unwrap(), 0, "the tooltip was left unterminated");
    }
}
