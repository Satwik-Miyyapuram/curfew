//! Asking Windows to ask for the password.
//!
//! Curfew never draws a password box of its own. `CredUIPromptForWindowsCredentialsW` is the same
//! dialog the operating system uses everywhere else, which matters twice: a user can tell it from a
//! phishing box drawn by an application, and Curfew never has to be trusted to handle the field.
//!
//! What comes back is an opaque buffer. It is unpacked, sent to the service to be checked there,
//! and wiped — the tray is not allowed to decide whether the password was right, only to carry it.
//!
//! It is asked for in its *generic* shape — a name and a password, nothing else — on purpose. The
//! dialog's default shape offers whatever the machine signs in with, and on most Windows 11 machines
//! that is a Hello PIN or a face. Those are checked by a hardware-backed provider that the
//! service's `LogonUser` cannot speak to, so a PIN typed there would come back as "wrong" every
//! time, and the user would conclude the lock has no way out. The password is the one credential
//! the service can check.

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
        CredPackAuthenticationBufferW, CredUIPromptForWindowsCredentialsW,
        CredUnPackAuthenticationBufferW, CREDUIWIN_GENERIC, CREDUI_INFOW,
        CRED_PACK_GENERIC_CREDENTIALS,
    };
    use windows_sys::Win32::System::Com::CoTaskMemFree;

    fn wide(text: &str) -> Vec<u16> {
        std::ffi::OsStr::new(text).encode_wide().chain(std::iter::once(0)).collect()
    }

    fn text_of(buffer: &[u16], len: u32) -> String {
        String::from_utf16_lossy(&buffer[..len as usize])
    }

    /// The account the desktop is signed in as, in the `DOMAIN\user` shape the dialog and
    /// `LogonUser` both understand. Prefilled so the user types only the password: the name is not
    /// a secret, and a name field left blank is where "which name does it want?" begins.
    fn signed_in_as() -> String {
        let user = std::env::var("USERNAME").unwrap_or_default();
        match std::env::var("USERDOMAIN") {
            Ok(domain) if !domain.is_empty() && !user.is_empty() => format!("{domain}\\{user}"),
            _ => user,
        }
    }

    /// A generic credential holding just the name, to seed the dialog with.
    fn prefilled(name: &str) -> Vec<u8> {
        let name = wide(name);
        let empty = wide("");
        let mut size = 0u32;
        // SAFETY: the first call only measures. A null buffer with a zero size is the documented
        // way to ask how much is needed.
        unsafe {
            CredPackAuthenticationBufferW(
                CRED_PACK_GENERIC_CREDENTIALS,
                name.as_ptr(),
                empty.as_ptr(),
                std::ptr::null_mut(),
                &mut size,
            );
        }
        let mut buffer = vec![0u8; size as usize];
        // SAFETY: the buffer is exactly the size the first call asked for.
        let ok = unsafe {
            CredPackAuthenticationBufferW(
                CRED_PACK_GENERIC_CREDENTIALS,
                name.as_ptr(),
                empty.as_ptr(),
                buffer.as_mut_ptr(),
                &mut size,
            )
        };
        if ok == 0 {
            buffer.clear();
        }
        buffer
    }

    /// Show the prompt. `None` means the user closed it, which is not an error and not a failed
    /// attempt: changing your mind about ending a block early is the system working.
    pub fn ask(parent: HWND, message: &str) -> Option<Credential> {
        let caption = wide("Curfew");
        let message = wide(message);
        let seed = prefilled(&signed_in_as());
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
                if seed.is_empty() { std::ptr::null() } else { seed.as_ptr().cast() },
                seed.len() as u32,
                &mut out,
                &mut out_size,
                // Never saved: `save` is passed only because the API requires somewhere to write to,
                // and this credential is used once and wiped.
                &mut save,
                // Generic: a name and a password, never the PIN and face tiles the machine signs in
                // with, which the service could not check (see the module note).
                CREDUIWIN_GENERIC,
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
        let (username, domain) = super::split_name(
            text_of(&user, user_len.saturating_sub(1)),
            text_of(&domain, domain_len.saturating_sub(1)),
        );
        let credential = Credential {
            username,
            domain,
            password: text_of(&password, password_len.saturating_sub(1)),
        };
        for unit in password.iter_mut() {
            unsafe { std::ptr::write_volatile(unit, 0) };
        }
        Some(credential)
    }
}

/// A generic credential comes back with the name exactly as typed, `DOMAIN\user` and all, and an
/// empty domain. `LogonUser` wants them apart. A name with no backslash — a bare account name or a
/// `user@domain` UPN — is left alone: `LogonUser` reads both of those with no domain given.
pub fn split_name(username: String, domain: String) -> (String, String) {
    if !domain.is_empty() {
        return (username, domain);
    }
    match username.split_once('\\') {
        Some((domain, user)) if !domain.is_empty() && !user.is_empty() => {
            (user.to_string(), domain.to_string())
        }
        _ => (username, domain),
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
    fn a_domain_typed_into_the_name_is_handed_to_logon_user_as_a_domain() {
        assert_eq!(
            split_name("PC\\satwik".into(), String::new()),
            ("satwik".to_string(), "PC".to_string())
        );
        assert_eq!(
            split_name("satwik".into(), String::new()),
            ("satwik".to_string(), String::new())
        );
        assert_eq!(
            split_name("satwik@example.com".into(), String::new()),
            ("satwik@example.com".to_string(), String::new())
        );
        // A domain the dialog already separated is not second-guessed.
        assert_eq!(split_name("a\\b".into(), "PC".into()), ("a\\b".to_string(), "PC".to_string()));
    }

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
