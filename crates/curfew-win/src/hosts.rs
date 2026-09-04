//! Website blocking, rung one: the hosts file.
//!
//! This is the weakest rung of the ladder in GAPS B2 — a browser doing DNS-over-HTTPS never asks
//! the resolver, so it never sees this file — and it is still the right place to start, because it
//! needs no driver, no elevation beyond the service's own, and no network stack of our own. The
//! honest framing is in the UI, not here: this stops the ordinary case and says so.
//!
//! Everything in this module is a pure text transform over the file's contents. The file itself is
//! read and written in one place, [`apply`], so the part that can be got wrong is the part that can
//! be tested without a machine.

use std::collections::BTreeSet;
use std::io;
use std::path::{Path, PathBuf};

/// The marker lines around Curfew's block. Anything between them belongs to Curfew and is rewritten
/// wholesale; anything outside them is another program's or the user's, and is never touched.
pub const BEGIN: &str = "# BEGIN CURFEW — do not edit between these markers";
pub const END: &str = "# END CURFEW";

/// The address blocked names are pointed at.
///
/// `0.0.0.0` rather than `127.0.0.1`: a local web server is common on a developer's machine, and
/// pointing a blocked site at it serves that server's pages instead of failing, which is confusing
/// at best and a data leak at worst. `0.0.0.0` fails immediately and unambiguously.
const SINK: &str = "0.0.0.0";

/// Where Windows keeps the file. Split out so tests never touch the real one.
pub fn default_path() -> PathBuf {
    let root = std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".to_string());
    Path::new(&root).join("System32").join("drivers").join("etc").join("hosts")
}

/// The file with Curfew's block removed, and nothing else changed.
///
/// Tolerant on purpose: a missing end marker (a crash mid-write, an editor that truncated the file)
/// would otherwise leave a block nothing could ever remove, and a blocker that can permanently
/// break name resolution is not one anybody should run as SYSTEM.
pub fn strip(existing: &str) -> String {
    let mut out = String::with_capacity(existing.len());
    let mut inside = false;
    for line in existing.lines() {
        let trimmed = line.trim();
        if trimmed == BEGIN {
            inside = true;
            continue;
        }
        if trimmed == END {
            inside = false;
            continue;
        }
        if !inside {
            out.push_str(line);
            out.push_str("\r\n");
        }
    }
    out
}

/// The file with Curfew's block set to exactly `domains`.
///
/// Each domain is blocked together with its `www.` form, because a user writing `reddit.com` means
/// the site, not one label of it. Deeper subdomains cannot be covered here — the hosts file has no
/// wildcards — which is one more reason the DNS proxy exists further up the ladder.
pub fn render(existing: &str, domains: &BTreeSet<String>) -> String {
    let base = strip(existing);
    if domains.is_empty() {
        return base;
    }
    let mut out = base;
    if !out.is_empty() && !out.ends_with("\r\n") {
        out.push_str("\r\n");
    }
    out.push_str(BEGIN);
    out.push_str("\r\n");
    for domain in domains {
        let name = normalize(domain);
        if name.is_empty() {
            continue;
        }
        out.push_str(&format!("{SINK} {name}\r\n"));
        if !name.starts_with("www.") {
            out.push_str(&format!("{SINK} www.{name}\r\n"));
        }
    }
    out.push_str(END);
    out.push_str("\r\n");
    out
}

/// A domain as it should appear in the file: no scheme, no path, no port, lowercase.
///
/// Users paste URLs. Writing `https://reddit.com/r/rust` into a hosts file produces a line the
/// resolver ignores, which looks exactly like Curfew not working.
fn normalize(domain: &str) -> String {
    let d = domain.trim().to_lowercase();
    let d = d.strip_prefix("https://").or_else(|| d.strip_prefix("http://")).unwrap_or(&d);
    let d = d.split('/').next().unwrap_or("");
    let d = d.split('?').next().unwrap_or("");
    let d = d.split(':').next().unwrap_or("");
    let d = d.trim_end_matches('.');
    d.trim().to_string()
}

/// Read, rewrite and write back the hosts file at `path`.
///
/// The write goes through a temporary file in the same directory and a rename, so a power cut in
/// the middle leaves either the old file or the new one, never half of either. A machine whose
/// hosts file is truncated cannot resolve `localhost`, and plenty of software depends on that.
pub fn apply(path: &Path, domains: &BTreeSet<String>) -> io::Result<()> {
    let existing = match std::fs::read_to_string(path) {
        Ok(text) => text,
        Err(e) if e.kind() == io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(e),
    };
    let updated = render(&existing, domains);
    if updated == existing {
        return Ok(());
    }
    let dir = path.parent().unwrap_or_else(|| Path::new("."));
    let temp = dir.join("hosts.curfew.tmp");
    std::fs::write(&temp, updated.as_bytes())?;
    std::fs::rename(&temp, path)
}

/// Remove Curfew's block entirely. Called when the last session ends, and by the uninstaller.
pub fn clear(path: &Path) -> io::Result<()> {
    apply(path, &BTreeSet::new())
}
