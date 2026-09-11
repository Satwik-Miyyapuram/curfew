//! The browser extension's half of the boundary: framing, heartbeats, and what to do when the
//! extension is gone.
//!
//! A service can see that `chrome.exe` is running and it can stop a name resolving, but it cannot
//! see that the tab is on `youtube.com/shorts` rather than on a lecture. Only something inside the
//! browser can, which is why the extension exists at all (GAPS G1).
//!
//! It is never the enforcement floor. An extension can be disabled from a menu two clicks deep, and
//! a block that ends when you click "Remove" is not a block. So the arrangement is inverted: the
//! extension proves it is alive by heartbeat, and a browser that should be reporting and is not
//! gets closed outright by the service. Removing the extension therefore *costs* the whole browser
//! rather than buying back the sites — the strictest thing the service can honestly do without
//! pretending it can see inside the window.
//!
//! Everything decidable without a machine is decided here, because the interesting cases — a
//! browser that has just started, a machine that just woke, an extension that is being reinstalled
//! — are exactly the ones nobody wants to reproduce by hand.

use curfew_core::{Config, State, Target, Timestamp};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

/// How long a browser may go without saying anything before it is treated as unwatched.
///
/// Generous on purpose. The extension beats far more often than this, and the cost of being wrong
/// in this direction is a browser closed under someone who did nothing wrong — a machine coming
/// back from sleep, a service worker the browser suspended, a profile still loading.
pub const GRACE_SECONDS: i64 = 90;

/// A browser is given this long after it appears before its silence counts against it. Starting a
/// browser and having it close half a second later, before the extension's service worker has even
/// been spun up, would be indistinguishable from a crash.
pub const STARTUP_SECONDS: i64 = 30;

/// The executables this layer knows how to hold to account.
///
/// A browser that is not on this list is still subject to every domain rule, because those are
/// enforced below the browser by the resolver and the hosts file. What it escapes is only the
/// path-level layer — so the list being incomplete degrades granularity, never the floor.
pub const BROWSERS: &[&str] = &[
    "chrome.exe",
    "msedge.exe",
    "firefox.exe",
    "brave.exe",
    "vivaldi.exe",
    "opera.exe",
    "opera_gx.exe",
    "chromium.exe",
    "librewolf.exe",
    "waterfox.exe",
    "zen.exe",
    "arc.exe",
];

pub fn is_browser(exe: &str) -> bool {
    BROWSERS.iter().any(|b| b.eq_ignore_ascii_case(exe))
}

/// The browser that launched **this** process, from the process tree rather than from a guess.
///
/// `curfew extension-host` is spawned by the browser as its native-messaging host, so this process's
/// parent **is** the browser. Reading that is exact; the extension can only guess, and its guess is
/// wrong for every browser not in its own list.
///
/// That guess was P1-2, and the harm is not cosmetic. The extension recognises six browsers
/// (`msedge.exe`, `firefox.exe`, `opera.exe`, `vivaldi.exe`, `brave.exe`, and `chrome.exe` as the
/// fallback) while the service knows twelve. A **Zen**, **LibreWolf**, **Waterfox**, **Arc**,
/// **Chromium** or **Opera GX** user therefore reports their heartbeat as `chrome.exe` — so
/// `chrome.exe` is trusted and the browser they are actually running is never trusted at all.
/// `unwatched` then names it, and the service closes it outright, repeatedly, for as long as any
/// path-level rule is in force. The extension's own comment says a wrong guess "fails safe"; it does
/// not, because the fallback is a *different browser's* name.
///
/// Returns `None` when the parent cannot be read, which is the honest answer and leaves the caller
/// with whatever the message said. It is not a fallback to `chrome.exe`: inventing a name is what
/// caused this.
pub fn browser_that_launched_us() -> Option<String> {
    let me = sysinfo::get_current_pid().ok()?;
    let mut system = sysinfo::System::new();
    // `true` for tasks as well as processes: the browser may be a child of a launcher, and this is a
    // one-off call at host startup rather than something on a tick.
    system.refresh_processes(sysinfo::ProcessesToUpdate::All, true);
    let parent = system.process(me)?.parent()?;
    let name = system.process(parent)?.name().to_string_lossy().to_string();
    // Only a name this layer knows how to hold to account. A launcher in between — a browser's own
    // updater, a taskbar shim — would otherwise be reported as the browser and trusted under a name
    // that is not on the list, which is the same failure in the other direction.
    if is_browser(&name) {
        Some(name.to_ascii_lowercase())
    } else {
        None
    }
}

/// Whether anything currently in force needs to see inside a browser.
///
/// Only URL and keyword rules do. A profile that merely blocks whole domains is enforced perfectly
/// well without the extension, and closing someone's browser because they have not installed an
/// add-on they do not need would be gratuitous.
pub fn needs_extension(state: &State, config: &Config) -> bool {
    state.active_profiles.iter().filter_map(|id| config.profile(id)).any(|profile| {
        profile
            .rules
            .iter()
            .any(|rule| matches!(rule.target, Target::Url { .. } | Target::Keyword { .. }))
    })
}

/// When each browser was last heard from, and when each was first seen running.
#[derive(Debug, Default, Clone)]
pub struct Watch {
    beats: BTreeMap<String, Timestamp>,
    first_seen: BTreeMap<String, Timestamp>,
}

impl Watch {
    /// Record that a browser's extension is alive. Returns the seconds since its previous beat,
    /// capped at the grace interval: that is how long the page it reports can be assumed to have
    /// been up, and a gap longer than the grace means the browser was not trusted anyway.
    pub fn beat(&mut self, exe: &str, now: Timestamp) -> u32 {
        match self.beats.insert(exe.to_lowercase(), now) {
            Some(last) => (now - last).clamp(0, GRACE_SECONDS) as u32,
            None => 0,
        }
    }

    /// Note what is running, so a browser gets its startup grace from when it appeared rather than
    /// from when Curfew started.
    pub fn saw(&mut self, running: &BTreeSet<String>, now: Timestamp) {
        for exe in running {
            self.first_seen.entry(exe.to_lowercase()).or_insert(now);
        }
        // A browser that has gone starts fresh next time: yesterday's heartbeat must not vouch for
        // tomorrow's launch with the extension since removed.
        self.beats.retain(|exe, _| running.contains(exe));
        self.first_seen.retain(|exe, _| running.contains(exe));
    }

    /// Whether this browser is currently accounted for.
    pub fn trusted(&self, exe: &str, now: Timestamp) -> bool {
        let exe = exe.to_lowercase();
        if let Some(seen) = self.first_seen.get(&exe) {
            if now - *seen < STARTUP_SECONDS {
                return true;
            }
        }
        // A heartbeat from the future is a clock that moved, not a browser that is fine; the
        // trusted window is the interval, in either direction, so winding the clock cannot mint
        // trust that outlives the extension.
        self.beats.get(&exe).is_some_and(|beat| (now - *beat).abs() < GRACE_SECONDS)
    }

    /// The running browsers that must be closed, given whether the extension is needed at all.
    pub fn unwatched(
        &self,
        running: &BTreeSet<String>,
        now: Timestamp,
        needed: bool,
    ) -> BTreeSet<String> {
        if !needed {
            return BTreeSet::new();
        }
        running
            .iter()
            .filter(|exe| is_browser(exe) && !self.trusted(exe, now))
            .map(|exe| exe.to_lowercase())
            .collect()
    }

    pub fn clear(&mut self) {
        self.beats.clear();
        self.first_seen.clear();
    }
}

// --- what crosses the native-messaging pipe ---------------------------------------------------

/// The largest message either side will read or write. Chrome's own limit is 1 MB for messages to
/// the host and 64 MB back; nothing here is bigger than a URL, so the cap is small and the reason
/// is a hostile local process rather than the browser.
pub const MAX_MESSAGE: usize = 64 * 1024;

/// The native-messaging host's name, which the manifest and the extension must agree on exactly.
pub const HOST_NAME: &str = "com.curfew.host";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum FromExtension {
    /// Sent once when the service worker starts, and then on a timer. `url` is the focused tab's
    /// page, when a window of this browser is focused at all.
    Beat {
        browser: String,
        #[serde(default)]
        url: Option<String>,
    },
    /// "The user is trying to open this. May they?"
    Check { browser: String, url: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ToExtension {
    Ok,
    /// The answer to a check. `reason` is shown on the block page: a page that says only "blocked"
    /// invites the user to suspect a bug and go looking for a way round it.
    Verdict {
        blocked: bool,
        reason: Option<String>,
    },
    Error {
        detail: String,
    },
}

/// Frame one message the way the browser expects: a native-endian `u32` length, then the JSON.
pub fn frame(payload: &[u8]) -> Vec<u8> {
    let mut out = (payload.len() as u32).to_ne_bytes().to_vec();
    out.extend_from_slice(payload);
    out
}

/// Read one framed message, or `Ok(None)` at a clean end of stream.
pub fn read_message(reader: &mut impl std::io::Read) -> std::io::Result<Option<Vec<u8>>> {
    let mut header = [0u8; 4];
    match reader.read_exact(&mut header) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let length = u32::from_ne_bytes(header) as usize;
    if length > MAX_MESSAGE {
        // Refused rather than allocated: the length is attacker-controlled, and a host that
        // reserves whatever it is told to is a way to take the machine down from a tab.
        return Err(std::io::Error::other(format!("message of {length} bytes is too large")));
    }
    let mut body = vec![0u8; length];
    reader.read_exact(&mut body)?;
    Ok(Some(body))
}

/// The manifest that tells a browser this host exists and which extensions may talk to it.
///
/// Written per browser family because the allow-list key differs, and getting it wrong fails
/// silently — the extension simply never connects, which looks exactly like the extension working
/// and the service being down.
pub fn manifest(family: Family, exe: &std::path::Path, id: &str) -> String {
    let path = exe.display().to_string().replace('\\', "\\\\");
    let allowed = match family {
        Family::Chromium => format!("\"allowed_origins\": [\"chrome-extension://{id}/\"]"),
        Family::Firefox => format!("\"allowed_extensions\": [\"{id}\"]"),
    };
    format!(
        "{{\n  \"name\": \"{HOST_NAME}\",\n  \"description\": \"Curfew\",\n  \"path\": \"{path}\",\n  \"type\": \"stdio\",\n  {allowed}\n}}\n"
    )
}

/// One sentence for the block page, saying which rule did this.
///
/// Written here rather than in the extension so that a page shown by a tampered extension cannot
/// claim a reason the engine did not give, and so the wording stays the same as the tray's.
///
/// `names` resolves a profile id to the name its owner gave it. It is a parameter rather than a
/// lookup because this module has no config and should not grow one — the service holds the names on
/// the status it already sends, and the caller passes them in. The block page is shown *inside a
/// browser*, where a slug like `deep-work` looks like a leak from somebody's config file rather than
/// the name the user chose.
pub fn explain(reason: &curfew_core::BlockReason) -> String {
    explain_named(reason, &|id| id.to_string())
}

/// [`explain`], with profile ids resolved to names.
pub fn explain_named(reason: &curfew_core::BlockReason, names: &dyn Fn(&str) -> String) -> String {
    use curfew_core::BlockReason::*;
    // The id is passed through `names` at every site, so a new `BlockReason` that carries a profile
    // cannot be added without deciding what to call it.
    let p = |id: &str| names(id);
    match reason {
        Blocked { profile } => format!("Blocked by your {} profile.", p(profile)),
        NotAllowlisted { profile } => {
            format!(
                "Your {} profile allows only a few sites, and this is not one of them.",
                p(profile)
            )
        }
        BudgetExhausted { profile, seconds } => {
            let minutes = seconds / 60;
            format!("You have used the {minutes} minutes this site gets under {}.", p(profile))
        }
        LaunchLimitReached { profile, count } => {
            format!(
                "You have opened this {count} times today, which is what {} allows.",
                p(profile)
            )
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Family {
    Chromium,
    Firefox,
}

/// A browser Curfew knows how to register the host with.
///
/// Every one of these reads the manifest from a different place, and getting it wrong fails
/// silently — the extension simply never connects, which looks from the outside exactly like the
/// extension working and the service being down. So the locations are written down once, here, and
/// checked by a test rather than by trying it on six browsers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Browser {
    Chrome,
    Edge,
    Brave,
    Vivaldi,
    Chromium,
    Firefox,
    Librewolf,
}

impl Browser {
    pub fn parse(name: &str) -> Option<Self> {
        Some(match name.to_lowercase().as_str() {
            "chrome" => Self::Chrome,
            "edge" | "msedge" => Self::Edge,
            "brave" => Self::Brave,
            "vivaldi" => Self::Vivaldi,
            "chromium" => Self::Chromium,
            "firefox" => Self::Firefox,
            "librewolf" => Self::Librewolf,
            _ => return None,
        })
    }

    pub fn family(self) -> Family {
        match self {
            Self::Firefox | Self::Librewolf => Family::Firefox,
            _ => Family::Chromium,
        }
    }

    /// The per-user registry key this browser looks the host up under. Per-user (`HKCU`) rather
    /// than machine-wide on purpose: registering a native-messaging host for every account on a
    /// shared PC would be reaching into other people's browsers to enforce one person's block.
    pub fn registry_key(self) -> String {
        let vendor = match self {
            Self::Chrome => r"Google\Chrome",
            Self::Edge => r"Microsoft\Edge",
            Self::Brave => r"BraveSoftware\Brave-Browser",
            Self::Vivaldi => "Vivaldi",
            Self::Chromium => "Chromium",
            Self::Firefox | Self::Librewolf => "Mozilla",
        };
        format!(r"HKCU\Software\{vendor}\NativeMessagingHosts\{HOST_NAME}")
    }

    /// The file the manifest is written to, one per browser so two browsers with different
    /// extension ids do not overwrite each other's.
    pub fn manifest_file(self) -> String {
        format!("{self:?}-host.json").to_lowercase()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_core::{Action, Profile, Rule};

    const NOW: Timestamp = 1_772_355_600;

    fn now() -> Timestamp {
        NOW
    }

    fn later(seconds: i64) -> Timestamp {
        NOW + seconds
    }

    fn running(exes: &[&str]) -> BTreeSet<String> {
        exes.iter().map(|e| e.to_string()).collect()
    }

    fn config_with(target: Target) -> (State, Config) {
        let mut config = Config::default();
        config.profiles.push(Profile {
            id: "focus".into(),
            name: "Focus".into(),
            description: String::new(),
            rules: vec![Rule { target, action: Action::Block, platforms: Vec::new() }],
        });
        let state = State { active_profiles: vec!["focus".into()], ..Default::default() };
        (state, config)
    }

    #[test]
    fn a_profile_that_only_blocks_domains_never_costs_anyone_their_browser() {
        // The resolver already enforces this perfectly. Closing a browser over an add-on the user
        // has no need for would be punishment with nothing bought by it.
        let (state, config) = config_with(Target::Domain { domain: "reddit.com".into() });
        assert!(!needs_extension(&state, &config));

        let watch = Watch::default();
        assert!(watch.unwatched(&running(&["chrome.exe"]), now(), false).is_empty());
    }

    #[test]
    fn a_path_rule_is_the_thing_that_requires_the_extension() {
        let (state, config) = config_with(Target::Url { pattern: "*youtube.com/shorts*".into() });
        assert!(needs_extension(&state, &config));

        let (state, config) = config_with(Target::Keyword { text: "twitter".into() });
        assert!(needs_extension(&state, &config), "keyword rules need to see the URL too");
    }

    #[test]
    fn a_rule_in_a_profile_that_is_not_running_asks_nothing_of_the_browser() {
        let (_, config) = config_with(Target::Url { pattern: "*/shorts*".into() });
        assert!(!needs_extension(&State::default(), &config));
    }

    #[test]
    fn a_browser_that_is_beating_is_left_alone() {
        let mut watch = Watch::default();
        watch.saw(&running(&["chrome.exe"]), now());
        watch.beat("chrome.exe", later(60));

        assert!(watch.unwatched(&running(&["chrome.exe"]), later(120), true).is_empty());
    }

    #[test]
    fn a_browser_that_has_gone_quiet_is_closed_so_removing_the_extension_costs_the_browser() {
        let mut watch = Watch::default();
        watch.saw(&running(&["chrome.exe"]), now());
        watch.beat("chrome.exe", now());

        let unwatched = watch.unwatched(&running(&["chrome.exe"]), later(GRACE_SECONDS + 1), true);

        assert_eq!(unwatched, running(&["chrome.exe"]));
    }

    #[test]
    fn a_browser_that_has_only_just_started_is_given_time_to_load_its_extension() {
        let mut watch = Watch::default();
        watch.saw(&running(&["firefox.exe"]), now());

        assert!(
            watch
                .unwatched(&running(&["firefox.exe"]), later(STARTUP_SECONDS - 1), true)
                .is_empty(),
            "a browser killed before its service worker starts looks like a crash to the user"
        );
        assert!(!watch
            .unwatched(&running(&["firefox.exe"]), later(STARTUP_SECONDS + 1), true)
            .is_empty());
    }

    #[test]
    fn quitting_a_browser_does_not_leave_its_heartbeat_vouching_for_the_next_launch() {
        // Otherwise the way round is: start the browser, let it beat once, close it, remove the
        // extension, start it again inside the grace window.
        let mut watch = Watch::default();
        watch.saw(&running(&["chrome.exe"]), now());
        watch.beat("chrome.exe", now());

        watch.saw(&BTreeSet::new(), later(10));
        watch.saw(&running(&["chrome.exe"]), later(20));

        assert!(!watch.trusted("chrome.exe", later(20 + STARTUP_SECONDS + 1)));
    }

    #[test]
    fn a_heartbeat_dated_in_the_future_does_not_buy_trust_that_outlives_the_extension() {
        let mut watch = Watch::default();
        watch.saw(&running(&["chrome.exe"]), now());
        watch.beat("chrome.exe", later(60 * 60 * 24));

        assert!(!watch.trusted("chrome.exe", later(STARTUP_SECONDS + 1)));
    }

    #[test]
    fn only_browsers_are_held_to_this_and_the_case_of_the_name_does_not_matter() {
        let watch = Watch::default();
        let unwatched = watch.unwatched(&running(&["Chrome.exe", "notepad.exe"]), later(600), true);

        assert_eq!(unwatched, running(&["chrome.exe"]));
    }

    #[test]
    fn a_browser_curfew_has_never_heard_of_falls_back_to_the_floor_rather_than_to_nothing() {
        // It is not closed — Curfew will not close a program it cannot name — but every domain rule
        // still applies to it, because those are enforced below the browser.
        assert!(!is_browser("palemoon.exe"));
        let watch = Watch::default();
        assert!(watch.unwatched(&running(&["palemoon.exe"]), later(600), true).is_empty());
    }

    #[test]
    fn each_browser_is_registered_where_that_browser_actually_looks() {
        use Browser::*;
        for (browser, fragment) in [
            (Chrome, r"Google\Chrome"),
            (Edge, r"Microsoft\Edge"),
            (Brave, "Brave-Browser"),
            (Firefox, "Mozilla"),
        ] {
            let key = browser.registry_key();
            assert!(key.starts_with(r"HKCU\Software\"), "{key}");
            assert!(key.contains(fragment), "{key}");
            assert!(key.ends_with(HOST_NAME), "{key}");
        }
        assert_eq!(Librewolf.registry_key(), Firefox.registry_key(), "the same host location");
        assert_ne!(Librewolf.manifest_file(), Firefox.manifest_file(), "but not the same file");
    }

    #[test]
    fn the_browsers_are_named_the_way_people_type_them_and_a_typo_is_not_guessed_at() {
        assert_eq!(Browser::parse("Chrome"), Some(Browser::Chrome));
        assert_eq!(Browser::parse("msedge"), Some(Browser::Edge));
        assert_eq!(Browser::parse("firefox").map(Browser::family), Some(Family::Firefox));
        assert_eq!(Browser::parse("brave").map(Browser::family), Some(Family::Chromium));
        assert_eq!(Browser::parse("safari"), None);
    }

    #[test]
    fn a_block_page_is_told_which_rule_did_it_rather_than_just_that_something_did() {
        use curfew_core::BlockReason;
        let blocked = explain(&BlockReason::Blocked { profile: "deep work".into() });
        assert!(blocked.contains("deep work"));

        let spent =
            explain(&BlockReason::BudgetExhausted { profile: "evening".into(), seconds: 1800 });
        assert!(spent.contains("30"), "{spent}");
        assert!(spent.contains("evening"));

        let allow = explain(&BlockReason::NotAllowlisted { profile: "study".into() });
        assert!(allow.contains("study"));
    }

    #[test]
    fn a_message_survives_the_round_trip_through_the_framing() {
        let payload = serde_json::to_vec(&FromExtension::Check {
            browser: "chrome.exe".into(),
            url: "https://www.youtube.com/shorts/abc".into(),
        })
        .unwrap();
        let framed = frame(&payload);

        let mut reader = std::io::Cursor::new(framed);
        let read = read_message(&mut reader).unwrap().expect("nothing came back");

        assert_eq!(read, payload);
        assert!(read_message(&mut reader).unwrap().is_none(), "the stream should end cleanly");
    }

    #[test]
    fn two_messages_in_one_read_are_not_run_together() {
        let mut stream = frame(b"{\"type\":\"beat\",\"browser\":\"chrome.exe\"}");
        stream.extend(frame(b"{\"type\":\"beat\",\"browser\":\"firefox.exe\"}"));

        let mut reader = std::io::Cursor::new(stream);
        let first = read_message(&mut reader).unwrap().unwrap();
        let second = read_message(&mut reader).unwrap().unwrap();

        assert!(String::from_utf8_lossy(&first).contains("chrome"));
        assert!(String::from_utf8_lossy(&second).contains("firefox"));
    }

    #[test]
    fn a_length_larger_than_anything_real_is_refused_rather_than_allocated() {
        let mut stream = (u32::MAX).to_ne_bytes().to_vec();
        stream.extend_from_slice(b"nothing like that much follows");

        assert!(read_message(&mut std::io::Cursor::new(stream)).is_err());
    }

    #[test]
    fn a_truncated_message_is_an_error_and_not_a_half_message() {
        let mut stream = frame(b"{\"type\":\"beat\",\"browser\":\"chrome.exe\"}");
        stream.truncate(stream.len() - 5);

        assert!(read_message(&mut std::io::Cursor::new(stream)).is_err());
    }

    #[test]
    fn each_browser_family_is_told_about_the_host_in_the_dialect_it_reads() {
        let exe = std::path::Path::new(r"C:\Program Files\Curfew\curfew.exe");

        let chromium = manifest(Family::Chromium, exe, "abcdefghijklmnop");
        assert!(chromium.contains("chrome-extension://abcdefghijklmnop/"));
        assert!(chromium.contains(r"C:\\Program Files\\Curfew\\curfew.exe"), "{chromium}");

        let firefox = manifest(Family::Firefox, exe, "curfew@curfew.dev");
        assert!(firefox.contains("allowed_extensions"));
        assert!(firefox.contains("curfew@curfew.dev"));

        for manifest in [chromium, firefox] {
            serde_json::from_str::<serde_json::Value>(&manifest).expect("not valid JSON");
        }
    }
}
