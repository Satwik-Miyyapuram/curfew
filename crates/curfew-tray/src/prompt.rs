//! Asking Windows to ask for the password.
//!
//! Curfew never draws a password box of its own. `CredUIPromptForWindowsCredentialsW` is the same
//! dialog the operating system uses everywhere else, which matters twice: a user can tell it from a
//! phishing box drawn by an application, and Curfew never has to be trusted to handle the field.
//!
//! What comes back is an opaque buffer. It is unpacked, sent to the service to be checked there,
//! and wiped — the tray is not allowed to decide whether the password was right, only to carry it.

/// A credential on its way to the service. Wiped when dropped.
pub struct Credential {
    pub username: String,
    pub domain: String,
    pub password: String,
}

impl Drop for Credential {
    fn drop(&mut self) {
        unsafe {
            for byte in self.password.as_bytes_mut() {
                std::ptr::write_volatile(byte, 0);
            }
        }
    }
}

impl std::fmt::Debug for Credential {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Credential {{ username: {:?}, password: .. }}", self.username)
    }
}

#[cfg(windows)]
mod sys {
    use super::Credential;
    use std::os::windows::ffi::OsStrExt as _;
    use windows_sys::Win32::Foundation::{ERROR_CANCELLED, HWND};
    use windows_sys::Win32::Security::Credentials::{
        CredUIPromptForWindowsCredentialsW, CredUnPackAuthenticationBufferW, CREDUIWIN_ENUMERATE_CURRENT_USER,
        CREDUI_INFOW,
    };
    use windows_sys::Win32::System::Com::CoTaskMemFree;

    fn wide(text: &str) -> Vec<u16> {
        std::ffi::OsStr::new(text).encode_wide().chain(std::iter::once(0)).collect()
    }

    fn text_of(buffer: &[u16], len: u32) -> String {
        String::from_utf16_lossy(&buffer[..len as usize])
    }

    /// Show the prompt. `None` means the user closed it, which is not an error and not a failed
    /// attempt: changing your mind about ending a block early is the system working.
    pub fn ask(parent: HWND, message: &str) -> Option<Credential> {
        let caption = wide("Curfew");
        let message = wide(message);
        let info = CREDUI_INFOW {
            cbSize: std::mem::size_of::<CREDUI_INFOW>() as u32,
            hwndParent: parent,
            pszMessageText: message.as_ptr(),
            pszCaptionText: caption.as_ptr(),
            hbmBanner: std::ptr::null_mut(),
        };

        let mut package = 0u32;
        let mut out: *mut std::ffi::c_void = std::ptr::null_mut();
        let mut out_size = 0u32;
        let mut save = 0i32;

        let result = unsafe {
            CredUIPromptForWindowsCredentialsW(
                &info,
                0,
                &mut package,
                std::ptr::null(),
                0,
                &mut out,
                &mut out_size,
                // Never saved: `save` is passed only because the API requires somewhere to write to,
                // and this credential is used once and wiped.
                &mut save,
                CREDUIWIN_ENUMERATE_CURRENT_USER,
            )
        };

        if result == ERROR_CANCELLED || out.is_null() {
            return None;
        }

        let mut user = vec![0u16; 513];
        let mut user_len = user.len() as u32;
        let mut domain = vec![0u16; 513];
        let mut domain_len = domain.len() as u32;
        let mut password = vec![0u16; 513];
        let mut password_len = password.len() as u32;

        let unpacked = unsafe {
            CredUnPackAuthenticationBufferW(
                0,
                out,
                out_size,
                user.as_mut_ptr(),
                &mut user_len,
                domain.as_mut_ptr(),
                &mut domain_len,
                password.as_mut_ptr(),
                &mut password_len,
            )
        };

        // The buffer the dialog allocated holds the password too, so it is overwritten before it is
        // handed back rather than merely freed.
        unsafe {
            std::ptr::write_bytes(out as *mut u8, 0, out_size as usize);
            CoTaskMemFree(out);
        }

        if unpacked == 0 {
            return None;
        }

        // Lengths include the terminator; the text does not.
        let credential = Credential {
            username: text_of(&user, user_len.saturating_sub(1)),
            domain: text_of(&domain, domain_len.saturating_sub(1)),
            password: text_of(&password, password_len.saturating_sub(1)),
        };
        for unit in password.iter_mut() {
            unsafe { std::ptr::write_volatile(unit, 0) };
        }
        Some(credential)
    }
}

#[cfg(not(windows))]
mod sys {
    use super::Credential;

    pub fn ask(_parent: isize, _message: &str) -> Option<Credential> {
        None
    }
}

pub use sys::ask;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_credential_never_prints_its_password() {
        let credential = Credential {
            username: "satwik".into(),
            domain: String::new(),
            password: "hunter2".into(),
        };
        let printed = format!("{credential:?}");
        assert!(printed.contains("satwik"));
        assert!(!printed.contains("hunter2"), "a password would reach a log line");
    }
}
