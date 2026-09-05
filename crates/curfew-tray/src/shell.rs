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
    DispatchMessageW, GetCursorPos, GetMessageW, KillTimer, LoadIconW, MessageBoxW, PostQuitMessage,
    RegisterClassW, SetForegroundWindow, SetTimer, TrackPopupMenu, TranslateMessage, HMENU,
    IDI_INFORMATION, MB_ICONINFORMATION, MB_OK, MF_GRAYED, MF_SEPARATOR, MF_STRING, MSG,
    TPM_BOTTOMALIGN, TPM_RIGHTALIGN, WM_APP, WM_COMMAND, WM_DESTROY, WM_RBUTTONUP, WM_TIMER,
    WNDCLASSW, WS_OVERLAPPED,
};

/// The message the shell sends us when someone clicks the icon.
const TRAY_MESSAGE: u32 = WM_APP + 1;
/// The timer that refreshes the tooltip.
const TOOLTIP_TIMER: usize = 1;
/// How often the tooltip is refreshed. Slow: it is a tooltip, and the menu is what has to be exact.
const TOOLTIP_MS: u32 = 15_000;

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

fn refresh_tooltip(window: HWND) {
    let text = match ask(&Request::Status) {
        Ok(curfew_win::ipc::Response::Status(status)) => tooltip(&status),
        // Said on the icon itself, because an unreachable service means nothing is being enforced
        // and that is not something to find out by opening a menu.
        _ => "Curfew — not running".to_string(),
    };
    let mut data = icon_data(window);
    set_tip(&mut data, &text);
    unsafe { Shell_NotifyIconW(NIM_MODIFY, &data) };
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
            Item::End { label, .. } | Item::Unlock { label, .. } | Item::Release { label, .. } => unsafe {
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
        Item::End { .. } | Item::Release { .. } => {
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
            if event == WM_RBUTTONUP || event == windows_sys::Win32::UI::WindowsAndMessaging::WM_LBUTTONUP {
                show_menu(window);
            }
            0
        }
        WM_COMMAND => {
            chosen(window, (wparam & 0xffff) as usize);
            0
        }
        WM_TIMER => {
            refresh_tooltip(window);
            0
        }
        WM_DESTROY => {
            let data = icon_data(window);
            Shell_NotifyIconW(NIM_DELETE, &data);
            KillTimer(window, TOOLTIP_TIMER);
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
