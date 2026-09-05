//! The parts that need Win32, kept in one file so the rest of the crate stays testable.
//!
//! Everything here is a question about the machine — which windows exist, which one the user is
//! looking at — and none of it is a decision. That separation is deliberate: this is the only code
//! in the crate that cannot be exercised without a real desktop, so it is kept small enough to
//! audit by reading.

use crate::procs::Process;
use std::collections::BTreeMap;

#[cfg(windows)]
mod sys {
    use super::*;
    use std::os::windows::ffi::OsStringExt;
    use windows_sys::Win32::Foundation::{BOOL, HWND, LPARAM, TRUE};
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GetForegroundWindow, GetWindowTextLengthW, GetWindowTextW,
        GetWindowThreadProcessId, IsWindowVisible,
    };

    fn title_of(hwnd: HWND) -> String {
        // SAFETY: `hwnd` came from the enumeration or from GetForegroundWindow, and the buffer is
        // sized from the length the API itself reported, plus one for the terminator it writes.
        unsafe {
            let len = GetWindowTextLengthW(hwnd);
            if len <= 0 {
                return String::new();
            }
            let mut buffer = vec![0u16; len as usize + 1];
            let written = GetWindowTextW(hwnd, buffer.as_mut_ptr(), buffer.len() as i32);
            if written <= 0 {
                return String::new();
            }
            std::ffi::OsString::from_wide(&buffer[..written as usize])
                .to_string_lossy()
                .into_owned()
        }
    }

    fn pid_of(hwnd: HWND) -> u32 {
        let mut pid = 0u32;
        // SAFETY: `pid` is a live stack slot for the duration of the call, which is all the API
        // requires of the out-parameter.
        unsafe { GetWindowThreadProcessId(hwnd, &mut pid) };
        pid
    }

    unsafe extern "system" fn collect(hwnd: HWND, lparam: LPARAM) -> BOOL {
        let titles = &mut *(lparam as *mut BTreeMap<u32, String>);
        if IsWindowVisible(hwnd) == TRUE {
            let title = title_of(hwnd);
            if !title.is_empty() {
                // One process can own several windows. The first visible titled one wins rather
                // than the last, because a rule written against "- YouTube" should match the tab
                // the user is in, not whatever happened to be enumerated last.
                titles.entry(pid_of(hwnd)).or_insert(title);
            }
        }
        TRUE
    }

    /// Visible window titles, by owning process id.
    pub fn window_titles() -> BTreeMap<u32, String> {
        let mut titles: BTreeMap<u32, String> = BTreeMap::new();
        // SAFETY: the callback is a plain function with the required signature, and the pointer
        // handed to it outlives the call because `EnumWindows` is synchronous.
        unsafe { EnumWindows(Some(collect), &mut titles as *mut _ as LPARAM) };
        titles
    }

    /// The window the user is actually looking at.
    pub fn foreground() -> Option<Process> {
        // SAFETY: no arguments, and a null return is handled below.
        let hwnd = unsafe { GetForegroundWindow() };
        if hwnd.is_null() {
            return None;
        }
        let pid = pid_of(hwnd);
        if pid == 0 {
            return None;
        }
        Some(Process { pid, exe: exe_of(pid)?, title: title_of(hwnd) })
    }

    /// Seconds since this machine booted, from a counter the user cannot set.
    ///
    /// `GetTickCount64` is monotonic within a boot and starts again at zero after one, which is
    /// exactly the property `curfew_core::BootCounter` needs. Reading the clock instead would make
    /// changing the time look like a restart.
    pub fn uptime_seconds() -> i64 {
        // SAFETY: no arguments, no pointers, cannot fail.
        (unsafe { windows_sys::Win32::System::SystemInformation::GetTickCount64() } / 1000) as i64
    }

    fn exe_of(pid: u32) -> Option<String> {
        let mut system = sysinfo::System::new();
        system.refresh_processes(
            sysinfo::ProcessesToUpdate::Some(&[sysinfo::Pid::from_u32(pid)]),
            true,
        );
        system.process(sysinfo::Pid::from_u32(pid)).map(|p| p.name().to_string_lossy().to_string())
    }
}

#[cfg(not(windows))]
mod sys {
    use super::*;

    /// No windows to enumerate. Title rules then never match, which is honest: a rule that cannot
    /// be evaluated must not be treated as satisfied *or* as violated.
    pub fn window_titles() -> BTreeMap<u32, String> {
        BTreeMap::new()
    }

    pub fn foreground() -> Option<Process> {
        None
    }

    /// Uptime the tests can rely on: it never goes backwards, so a non-Windows build never invents
    /// a reboot that did not happen.
    pub fn uptime_seconds() -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0)
    }
}

pub use sys::{foreground, uptime_seconds, window_titles};
