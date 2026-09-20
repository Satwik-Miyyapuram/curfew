//! What a rule points at, and what the platform reports seeing.
//!
//! Patterns are globs (`*` and `?`), not regular expressions. That is deliberate: rule matching
//! runs on every foreground change on a battery-powered device, and a user-supplied regex is both
//! a performance cliff and a denial-of-service surface (catastrophic backtracking) in the one code
//! path that must never stall. Globs cover every rule anyone actually writes -- `*/shorts/*`,
//! `*- YouTube*` -- and match in linear-ish time with no allocation (DECISIONS D8).

use serde::{Deserialize, Serialize};

/// A thing a rule can point at.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Target {
    /// Android package name, exact, case-insensitive.
    AppPackage { package: String },
    /// A screen *inside* an app, identified by an accessibility node signature. Fragile by nature:
    /// it breaks when the app updates, which is why these ship as a separately versioned matcher
    /// pack and are labelled as best-effort in the UI (GAPS A3).
    AppScreen { package: String, screen: String },
    /// Windows executable name, no path, case-insensitive.
    WindowsExe { exe: String },
    /// Glob over a window title.
    WindowTitle { pattern: String },
    /// A domain and all of its subdomains.
    Domain { domain: String },
    /// Glob over the whole URL, for path- and query-level rules.
    Url { pattern: String },
    /// A word appearing in a URL, a search query, or a window title.
    Keyword { text: String },
    /// Glob over a file or folder path (Windows).
    FilePath { pattern: String },
    /// Notifications from one app.
    NotificationSource { package: String },
    /// Everything. The Frozen-Turkey shape: the device itself is the target.
    WholeDevice,
}

impl Target {
    /// Stable identity, used as the key for budgets, launch counts and usage stats. Two rules
    /// naming the same thing share one budget, on this device and on every paired one.
    pub fn key(&self) -> String {
        match self {
            Target::AppPackage { package } => format!("app:{}", package.to_lowercase()),
            Target::AppScreen { package, screen } => {
                format!("screen:{}:{}", package.to_lowercase(), screen.to_lowercase())
            }
            Target::WindowsExe { exe } => format!("exe:{}", exe.to_lowercase()),
            Target::WindowTitle { pattern } => format!("title:{}", pattern.to_lowercase()),
            Target::Domain { domain } => {
                format!("domain:{}", trim_dots(domain.trim()).to_lowercase())
            }
            Target::Url { pattern } => format!("url:{}", pattern.to_lowercase()),
            Target::Keyword { text } => format!("keyword:{}", text.to_lowercase()),
            Target::FilePath { pattern } => format!("path:{}", pattern.to_lowercase()),
            Target::NotificationSource { package } => format!("notif:{}", package.to_lowercase()),
            Target::WholeDevice => "device".to_string(),
        }
    }

    /// Whether this target describes what the user is looking at.
    pub fn matches(&self, obs: &Observation) -> bool {
        match (self, obs) {
            // WholeDevice covers anything the user could be doing, but not an idle screen: there
            // is nothing to block when nothing is in the foreground.
            (Target::WholeDevice, Observation::Idle) => false,
            (Target::WholeDevice, _) => true,

            (Target::AppPackage { package }, Observation::App { package: p, .. }) => {
                package.eq_ignore_ascii_case(p)
            }
            (Target::AppScreen { package, screen }, Observation::App { package: p, screen: s }) => {
                package.eq_ignore_ascii_case(p)
                    && s.as_deref().is_some_and(|s| screen.eq_ignore_ascii_case(s))
            }
            (Target::WindowsExe { exe }, Observation::Window { exe: e, .. }) => {
                exe.eq_ignore_ascii_case(e)
            }
            (Target::WindowTitle { pattern }, Observation::Window { title, .. }) => {
                glob_match(pattern, title)
            }
            (Target::Domain { domain }, Observation::Web { url }) => {
                domain_matches(domain, &url.host)
            }
            // Globs match the normalized form, not the raw string: a rule saying
            // `*youtube.com/shorts*` must still fire when the browser hands us a port, credentials
            // or a scheme, none of which the person writing the rule was thinking about.
            (Target::Url { pattern }, Observation::Web { url }) => {
                glob_match(pattern, &url.normalized())
            }
            // Path separators are noise: a rule written `C:\Games\*` must match a path reported
            // as `C:/Games/x`, and TOML makes backslashes awkward to write in the first place.
            (Target::FilePath { pattern }, Observation::FileOpen { path }) => {
                glob_match(&pattern.replace('\\', "/"), &path.replace('\\', "/"))
            }
            (
                Target::NotificationSource { package },
                Observation::Notification { package: p, .. },
            ) => package.eq_ignore_ascii_case(p),

            // A keyword is about text, wherever the text came from.
            (Target::Keyword { text }, obs) => obs
                .searchable_text()
                .is_some_and(|haystack| haystack.to_lowercase().contains(&text.to_lowercase())),

            _ => false,
        }
    }
}

/// What the platform enforcer reports. One of these per foreground change.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Observation {
    /// Android foreground app, with the in-app screen when the accessibility layer could name one.
    App { package: String, screen: Option<String> },
    /// Windows foreground window.
    Window { exe: String, title: String },
    /// A page in a browser.
    Web { url: Url },
    /// A notification arriving. Decisions about these are about muting, not blocking.
    Notification { package: String, title: String },
    /// A file or folder being opened (Windows).
    FileOpen { path: String },
    /// Nothing in the foreground: screen off, launcher, lock screen.
    Idle,
}

impl Observation {
    /// The text a [`Target::Keyword`] rule searches.
    pub fn searchable_text(&self) -> Option<String> {
        match self {
            Observation::Web { url } => Some(url.normalized()),
            Observation::Window { title, .. } => Some(title.clone()),
            Observation::Notification { title, .. } => Some(title.clone()),
            Observation::App { screen, .. } => screen.clone(),
            Observation::FileOpen { path } => Some(path.clone()),
            Observation::Idle => None,
        }
    }

    /// True for observations that are notifications, which only ever produce mute decisions.
    pub fn is_notification(&self) -> bool {
        matches!(self, Observation::Notification { .. })
    }
}

/// Just enough URL for rule matching. Deliberately not a full parser: we take what the browser or
/// the accessibility layer hands us, lowercase the host, and keep the rest verbatim.
///
/// **"The rest" means the rest, and this used to be a lie.** The scheme was dropped, and every
/// other part was lowercased on the way in, so a rule written `https://github.com/User/Repo` could
/// never match anything: the pattern kept its capital letters and the URL it was compared against
/// had lost them. URL paths are case-sensitive (RFC 3986), so a path segment naming a GitHub user, a
/// video id or a base64 query value is a real distinction and not a cosmetic one.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Url {
    /// The URL as handed in, trimmed and otherwise untouched. Its case is information.
    pub raw: String,
    /// Lowercase: a host is case-insensitive, and this is the only part that is.
    pub host: String,
    /// The scheme, lowercased, or empty when the input carried none. Stored so that
    /// [`Url::normalized`] can put it back.
    ///
    /// Defaulted, because it arrived after the field did: a payload written by an older Kotlin
    /// binding carries no scheme, and refusing to deserialize it would turn a version skew into a
    /// crash in the enforcer.
    #[serde(default)]
    pub scheme: String,
    /// Verbatim. Case-sensitive per RFC 3986.
    pub path: String,
    /// Verbatim, and without the leading `?`. Case-sensitive.
    pub query: String,
}

impl Url {
    pub fn parse(raw: &str) -> Self {
        let trimmed = raw.trim();
        // Split the scheme off rather than discarding it: `normalized` needs it back, and a rule
        // is allowed to name one.
        let (scheme, after_scheme) = match trimmed.find("://") {
            Some(i) => (trimmed[..i].to_ascii_lowercase(), &trimmed[i + 3..]),
            None => (String::new(), trimmed),
        };
        let (authority, rest) = match after_scheme.find(['/', '?', '#']) {
            Some(i) => (&after_scheme[..i], &after_scheme[i..]),
            None => (after_scheme, ""),
        };
        // Strip credentials and port; neither participates in matching.
        let host = authority.rsplit('@').next().unwrap_or(authority);
        let host = host.split(':').next().unwrap_or(host).to_lowercase();

        let without_fragment = rest.split('#').next().unwrap_or("");
        let (path, query) = match without_fragment.split_once('?') {
            Some((p, q)) => (p.to_string(), q.to_string()),
            None => (without_fragment.to_string(), String::new()),
        };

        Self { raw: trimmed.to_string(), scheme, host, path, query }
    }

    /// `scheme://host/path?query`: the URL with everything that never participates in matching
    /// (credentials, port, fragment) already removed, and the scheme kept when there was one.
    ///
    /// The scheme is kept rather than dropped so that both spellings of a rule work: a pattern
    /// written `*youtube.com/shorts*` still matches, because `*` spans `https://www.`, and a
    /// pattern written `https://github.com/User/*` now matches too, which is what it always meant.
    pub fn normalized(&self) -> String {
        let mut out = String::with_capacity(
            self.scheme.len() + self.host.len() + self.path.len() + self.query.len() + 4,
        );
        if !self.scheme.is_empty() {
            out.push_str(&self.scheme);
            out.push_str("://");
        }
        out.push_str(&self.host);
        out.push_str(&self.path);
        if !self.query.is_empty() {
            out.push('?');
            out.push_str(&self.query);
        }
        out
    }
}

/// A domain rule covers the domain itself and every subdomain, but never a domain that merely ends
/// with the same characters: `notreddit.com` is not `reddit.com`.
///
/// Compared on bytes, with no allocation. This runs once per domain rule per observation and once
/// per blocked name per DNS query (`curfew_win::dns`), so the `format!(".{rule}")` this used to
/// build on every call was pure waste: the label-boundary test is a single byte comparison.
///
/// Folding is ASCII-only, which is what a hostname is. The DNS is ASCII by definition, so the
/// Unicode path would be doing work to compare strings that cannot contain it.
pub fn domain_matches(rule: &str, observed: &str) -> bool {
    let rule = trim_dots(rule.trim());
    let observed = trim_dots(observed.trim());
    if rule.is_empty() || observed.len() < rule.len() {
        return false;
    }
    if observed.len() == rule.len() {
        return observed.eq_ignore_ascii_case(rule);
    }
    // A subdomain: the character before the suffix has to be the label dot, or `notreddit.com`
    // would be covered by `reddit.com`.
    let split = observed.len() - rule.len();
    observed.as_bytes()[split - 1] == b'.'
        && observed.as_bytes()[split..].eq_ignore_ascii_case(rule.as_bytes())
}

/// A domain with its leading and trailing dots removed, for comparison.
fn trim_dots(d: &str) -> &str {
    d.trim_start_matches('.').trim_end_matches('.')
}

/// Case-insensitive glob match supporting `*` (any run, including empty) and `?` (one character).
///
/// Iterative with a single backtrack point, so it cannot blow up the way a backtracking regex can:
/// worst case is O(pattern x text), and there is no recursion to overflow.
///
/// **No allocation on the path that matters.** This used to collect the lowercased pattern and text
/// into two `Vec<char>` on every call — two allocations and two passes to compare a 30-character
/// pattern against a window title, on every foreground change, on a battery. Everything a rule
/// actually matches (URLs, window titles, package names, file paths) is ASCII, so ASCII input is
/// compared byte by byte with `eq_ignore_ascii_case`, and only genuinely non-ASCII input falls back
/// to the char-based fold, where correctness is worth the allocation.
pub fn glob_match(pattern: &str, text: &str) -> bool {
    if pattern.is_ascii() && text.is_ascii() {
        glob_match_ascii(pattern.as_bytes(), text.as_bytes())
    } else {
        glob_match_folded(pattern, text)
    }
}

fn glob_match_ascii(p: &[u8], t: &[u8]) -> bool {
    let (mut pi, mut ti) = (0usize, 0usize);
    // Where to resume if the current `*` expansion turns out to be too short.
    let (mut star, mut star_ti) = (None, 0usize);

    while ti < t.len() {
        if pi < p.len() && (p[pi] == b'?' || p[pi].eq_ignore_ascii_case(&t[ti])) {
            pi += 1;
            ti += 1;
        } else if pi < p.len() && p[pi] == b'*' {
            star = Some(pi);
            star_ti = ti;
            pi += 1;
        } else if let Some(s) = star {
            // Let the last `*` swallow one more character and try again.
            pi = s + 1;
            star_ti += 1;
            ti = star_ti;
        } else {
            return false;
        }
    }

    while pi < p.len() && p[pi] == b'*' {
        pi += 1;
    }
    pi == p.len()
}

/// The non-ASCII path: fold both sides to chars, then run the same algorithm.
fn glob_match_folded(pattern: &str, text: &str) -> bool {
    let p: Vec<char> = pattern.to_lowercase().chars().collect();
    let t: Vec<char> = text.to_lowercase().chars().collect();

    let (mut pi, mut ti) = (0usize, 0usize);
    let (mut star, mut star_ti) = (None, 0usize);

    while ti < t.len() {
        if pi < p.len() && (p[pi] == '?' || p[pi] == t[ti]) {
            pi += 1;
            ti += 1;
        } else if pi < p.len() && p[pi] == '*' {
            star = Some(pi);
            star_ti = ti;
            pi += 1;
        } else if let Some(s) = star {
            pi = s + 1;
            star_ti += 1;
            ti = star_ti;
        } else {
            return false;
        }
    }

    while pi < p.len() && p[pi] == '*' {
        pi += 1;
    }
    pi == p.len()
}
