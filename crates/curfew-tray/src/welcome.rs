//! The first thing this icon ever says, and the only thing it says unprompted.
//!
//! Two facts are worth a person's attention exactly once, and both of them are things a user would
//! otherwise learn from something that looks like a failure:
//!
//! 1. **Windows called this app unrecognised.** Curfew's builds are not signed with a code-signing
//!    certificate, because one costs money every year and this project has no revenue. SmartScreen
//!    therefore says "Windows protected your PC" on first run, and an antivirus may flag the service
//!    for closing other people's windows, which is a thing malware also does. Saying this plainly
//!    inside the app is the honest move: an app that stayed silent about it would be leaving the
//!    user to guess whether they had installed something they should not have.
//! 2. **This icon is not the blocker.** The service enforces with the tray closed, hidden, or
//!    killed. Someone who works out that hiding the icon ends their block has found a hole; there
//!    is none, and the sentence that says so is worth more than any dialog that argues about it.
//!
//! Kept away from Win32 so the wording is testable, and shown once: a notice repeated every login is
//! a notice nobody reads.

use std::path::{Path, PathBuf};

/// The whole notice. Read it as the thing a person sees the first time the icon appears.
pub const WELCOME: &str = "Curfew is running. The icon by the clock is where blocks are ended.\n\n\
     Two things worth knowing once:\n\n\
     Windows may have called this app unrecognised, and an antivirus may have flagged it. Curfew is \
     not signed with a code-signing certificate — those are paid every year and this project has no \
     revenue — and the service closes other applications' windows, which is behaviour scanners \
     watch for. The source is public and the builds are reproducible from it.\n\n\
     This icon is not what does the blocking. The service enforces with it hidden, closed or ended, \
     so hiding it is not a way out of a session.";

/// `%LOCALAPPDATA%\Curfew\tray-welcomed` — per user, because the notice is per person and not per
/// machine, and outside `%ProgramData%` because nothing here needs protecting from the user.
pub fn marker_path() -> PathBuf {
    let root = std::env::var("LOCALAPPDATA")
        .or_else(|_| std::env::var("APPDATA"))
        .unwrap_or_else(|_| ".".to_string());
    Path::new(&root).join("Curfew").join("tray-welcomed")
}

/// The notice to show now, if any. `None` once it has been shown.
///
/// Writing the marker *before* showing anything is deliberate: if the write fails there is no marker
/// to write later either, and a notice that reappears at every login would be worse than one missed.
pub fn take(marker: &Path) -> Option<&'static str> {
    if marker.exists() {
        return None;
    }
    if let Some(parent) = marker.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    std::fs::write(marker, "shown\n").ok()?;
    Some(WELCOME)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_notice_says_both_things_it_exists_to_say() {
        assert!(WELCOME.contains("unrecognised"), "the SmartScreen warning is not explained");
        assert!(WELCOME.contains("not signed"), "the reason for the warning is not given");
        assert!(WELCOME.contains("hiding it is not a way out"));
    }

    #[test]
    fn it_is_shown_once_and_then_never_again() {
        let dir = std::env::temp_dir().join(format!("curfew-welcome-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let marker = dir.join("tray-welcomed");
        assert_eq!(take(&marker), Some(WELCOME));
        assert_eq!(take(&marker), None, "the notice came back on a second run");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
