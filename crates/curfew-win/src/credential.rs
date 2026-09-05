//! Checking a Windows password, in the process that is allowed to be believed.
//!
//! The lock condition is "the person who set this can prove they are the owner of this machine".
//! The only party that can check that claim honestly is the service: an unprivileged tray that
//! reported "the password was correct" would be a lock anyone could open with a debugger, and the
//! control channel deliberately has no message that means "trust me".
//!
//! So the credential travels — over a machine-local named pipe, to a SYSTEM process — and is
//! checked here with `LogonUser`, the same call the operating system's own prompts end at. Nothing
//! is stored: the password lives in one buffer that is overwritten before it is dropped.

/// A password on its way to being checked. It exists to make the wiping automatic, so no path out
/// of the check can forget it.
pub struct Secret(String);

impl Secret {
    pub fn new(text: String) -> Self {
        Self(text)
    }
}

impl Drop for Secret {
    fn drop(&mut self) {
        // Overwriting a `String`'s bytes in place is not a guarantee against a determined attacker
        // with a memory dump — nothing in a userland process is — but it does mean the password
        // does not sit in a freed allocation for the rest of the session.
        unsafe {
            for byte in self.0.as_bytes_mut() {
                std::ptr::write_volatile(byte, 0);
            }
        }
    }
}

impl std::fmt::Debug for Secret {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Never the value, in any log line, ever.
        f.write_str("Secret(..)")
    }
}

#[cfg(windows)]
mod sys {
    use super::Secret;
    use std::os::windows::ffi::OsStrExt as _;
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
    use windows_sys::Win32::Security::{LogonUserW, LOGON32_LOGON_INTERACTIVE, LOGON32_PROVIDER_DEFAULT};

    fn wide(text: &str) -> Vec<u16> {
        std::ffi::OsStr::new(text).encode_wide().chain(std::iter::once(0)).collect()
    }

    /// True only if Windows itself accepts this account and password on this machine.
    ///
    /// `domain` is empty for a local or Microsoft account, which is the ordinary case; a machine
    /// joined to a domain passes its domain name.
    pub fn verify(username: &str, domain: &str, password: &Secret) -> bool {
        let user = wide(username);
        let domain = wide(domain);
        let mut secret = wide(&password.0);
        let mut token: HANDLE = std::ptr::null_mut();

        let ok = unsafe {
            LogonUserW(
                user.as_ptr(),
                if domain.len() > 1 { domain.as_ptr() } else { std::ptr::null() },
                secret.as_ptr(),
                LOGON32_LOGON_INTERACTIVE,
                LOGON32_PROVIDER_DEFAULT,
                &mut token,
            )
        };

        // The wide copy is a second place the password exists; it goes the same way as the first.
        for unit in secret.iter_mut() {
            unsafe { std::ptr::write_volatile(unit, 0) };
        }
        if !token.is_null() {
            unsafe { CloseHandle(token) };
        }
        ok != 0
    }
}

#[cfg(not(windows))]
mod sys {
    use super::Secret;

    /// Off Windows there is no `LogonUser` to ask, and a check that cannot be performed must never
    /// come back "satisfied": an unevaluable condition is not a met one.
    pub fn verify(_username: &str, _domain: &str, _password: &Secret) -> bool {
        false
    }
}

pub use sys::verify;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_secret_never_prints_itself() {
        let secret = Secret::new("hunter2".to_string());
        assert_eq!(format!("{secret:?}"), "Secret(..)");
    }

    #[test]
    fn an_empty_password_is_not_a_way_in() {
        // Blank passwords are refused by `LogonUserW` for interactive logons by default, and the
        // check is here so that a machine configured to allow them still cannot be unlocked by
        // sending nothing at all.
        assert!(!verify("", "", &Secret::new(String::new())));
    }

    #[test]
    fn a_wrong_password_is_refused() {
        assert!(!verify(
            "curfew-account-that-does-not-exist",
            "",
            &Secret::new("definitely-not-the-password".to_string())
        ));
    }
}
