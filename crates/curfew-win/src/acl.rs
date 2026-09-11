//! Who is allowed to write the directory Curfew runs as SYSTEM from.
//!
//! The watchdog is copied out of `Program Files` and executed from here (see
//! [`crate::state::default_path`]), and so are the config, the state file, the cached calendars and
//! the record of the machine's DNS settings. The module that does the copying asserted that
//! `%ProgramData%\Curfew` "is administrator-owned, the same as the config and the state file beside
//! it" — and nothing anywhere set that ACL. `C:\ProgramData` grants `BUILTIN\Users` container-inherited
//! `Write`, which is `FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY`, so any unprivileged user of the machine
//! could create files in it.
//!
//! Closing the *spawn* path was done separately, by verifying the watchdog image's contents rather
//! than its metadata. This module closes the rest: it makes the directory actually be what the
//! comment claimed, so a planted `calendars/` entry or a planted `dns-before.json` cannot be written
//! in the first place.
//!
//! Everything here shells out to `icacls` rather than calling `SetNamedSecurityInfoW` directly. That
//! is a deliberate trade: it is one more process at install and service start, against a hand-built
//! `EXPLICIT_ACCESS_W` array and a second place to get pointer lifetimes wrong in a SYSTEM process.
//! `icacls` is present on every supported Windows and its *exit status* is the only thing read — never
//! its output, which is localized, and localized console parsing is a bug this project has already
//! been bitten by once.

use std::path::Path;

/// The three trustees, by SID.
///
/// SIDs and not names, because `icacls SYSTEM:(OI)(CI)F` works only on an English Windows — the same
/// class of mistake as parsing `netsh` output. These are well-known and language-independent.
///
/// - `S-1-5-18` — `LocalSystem`, the account the service runs as.
/// - `S-1-5-32-544` — `Administrators`, so an administrator can still fix a broken install.
/// - `S-1-5-32-545` — `Users`, read and execute only. This is the grant that matters: the tray, the
///   window and the command line all run as the logged-in user and must be able to *read* the config
///   and the state file, and none of them needs to write either.
const SYSTEM: &str = "*S-1-5-18";
const ADMINISTRATORS: &str = "*S-1-5-32-544";
const USERS: &str = "*S-1-5-32-545";

/// The arguments `icacls` needs to make `dir` administrator-owned and user-readable.
///
/// Split out from running it so the shape can be tested without touching a real ACL — the ordering of
/// `/inheritance:r` and `/grant:r` is what makes the result *exactly* these three grants rather than
/// these three plus whatever was inherited, and that is worth pinning.
///
/// `/inheritance:r` removes every inherited ACE. `/grant:r` then replaces the grant for each named
/// trustee. Together they mean the directory ends up with precisely: SYSTEM full, Administrators
/// full, Users read-and-execute. No ACE grants write, delete, or the right to change permissions to
/// anything else.
pub fn hardening_args(dir: &Path) -> Vec<String> {
    vec![
        dir.display().to_string(),
        "/inheritance:r".to_string(),
        "/grant:r".to_string(),
        format!("{SYSTEM}:(OI)(CI)F"),
        format!("{ADMINISTRATORS}:(OI)(CI)F"),
        format!("{USERS}:(OI)(CI)RX"),
        // Quiet on success, and never interactive. A prompt inside a service would hang it for ever
        // with no window for anyone to answer.
        "/Q".to_string(),
        "/C".to_string(),
    ]
}

/// Make `dir` administrator-owned and user-readable, recursively applying to new contents.
///
/// Returns the reason on failure rather than an `io::Error`, because every caller reports it to a
/// different audience — an installer prompt, a service that has no console, a test.
pub fn harden(dir: &Path) -> Result<(), String> {
    if !dir.exists() {
        // Nothing to harden yet, and creating it here would race the caller that owns creation.
        return Err(format!("{} does not exist yet", dir.display()));
    }
    let output = std::process::Command::new("icacls")
        .args(hardening_args(dir))
        .output()
        .map_err(|e| format!("could not run icacls: {e}"))?;
    if output.status.success() {
        return Ok(());
    }
    // The exit code is the whole message. `icacls` prints in the machine's language, so quoting its
    // output would produce a sentence in a language this program cannot read.
    Err(format!(
        "icacls refused to set permissions on {} (exit {:?}). Curfew will still run, but its \
         directory stays writable by ordinary users.",
        dir.display(),
        output.status.code()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn the_directory_is_made_administrator_owned_and_user_readable() {
        let args = hardening_args(&PathBuf::from("C:\\ProgramData\\Curfew"));
        let joined = args.join(" ");

        assert!(joined.contains("C:\\ProgramData\\Curfew"), "the directory is not named: {joined}");
        assert!(
            joined.contains("/inheritance:r"),
            "without this the directory keeps the inherited BUILTIN\\Users write, which is the whole \
             bug: {joined}"
        );
        assert!(joined.contains(&format!("{SYSTEM}:(OI)(CI)F")), "{joined}");
        assert!(joined.contains(&format!("{ADMINISTRATORS}:(OI)(CI)F")), "{joined}");
        assert!(joined.contains(&format!("{USERS}:(OI)(CI)RX")), "{joined}");
    }

    /// Nothing may grant a general write to a trustee that is not SYSTEM or Administrators.
    #[test]
    fn no_grant_gives_users_or_everyone_write_access() {
        let args = hardening_args(&PathBuf::from("C:\\ProgramData\\Curfew"));
        for arg in &args {
            if let Some((trustee, rights)) = arg.split_once(':') {
                let rights = rights.trim_start_matches('(');
                let writes = rights.contains('W') || rights.contains('F') || rights.contains('M');
                let privileged = trustee == SYSTEM || trustee == ADMINISTRATORS;
                assert!(
                    !(writes && !privileged),
                    "a non-administrator trustee was granted write access: {arg}"
                );
            }
        }
        // And "everyone" is never named at all.
        assert!(
            !args.iter().any(|a| a.contains("S-1-1-0") || a == "Everyone"),
            "the ACL names Everyone: {args:?}"
        );
    }

    /// A directory that is not there yet is reported, not created: the caller owns creation, and two
    /// writers racing to create the same directory is how a permission change lands on a path that
    /// does not exist.
    #[test]
    fn a_missing_directory_is_reported_rather_than_treated_as_done() {
        let missing = std::env::temp_dir()
            .join(format!("curfew-acl-absent-{}", std::process::id()))
            .join("nested");
        let _ = std::fs::remove_dir_all(missing.parent().unwrap());
        let err =
            harden(&missing).expect_err("a directory that does not exist was reported as hardened");
        assert!(err.contains("does not exist"), "the reason was unhelpful: {err}");
    }

    /// The arguments are accepted by the machine's own `icacls`.
    ///
    /// The three tests above check the *shape* of what is passed; this one checks that the tool agrees
    /// to it, which is the failure that would otherwise be discovered by a user whose install silently
    /// hardened nothing. `icacls` is present on every supported Windows, and the directory is one this
    /// test owns.
    ///
    /// The resulting ACL is deliberately not asserted here. Reading it back means parsing `icacls`
    /// output, which is localized — the exact mistake this module exists to avoid. It was verified by
    /// hand instead, before and after: a directory inheriting `BUILTIN\Users: Modify` alongside a
    /// full-control sandbox grant comes out with exactly SYSTEM full, Administrators full, Users
    /// read-and-execute, and no inherited ACEs. That record is in FIXES.md entry 25.
    #[test]
    fn the_machine_accepts_the_arguments() {
        let dir = std::env::temp_dir().join(format!("curfew-acl-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        let outcome = harden(&dir);
        // Clean up first, whatever happened: after a successful hardening an ordinary user may no
        // longer be able to delete inside the directory, so this is best-effort either way.
        let _ = std::fs::remove_dir_all(&dir);

        outcome.expect("icacls refused arguments this program builds");
    }
}
