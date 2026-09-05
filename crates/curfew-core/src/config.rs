//! The `curfew.toml` document. Everything the UI can express lives here, so a config is a
//! complete, diffable description of a user's setup (design invariant 4).

use crate::budget::Refill;
use crate::schedule::{CalendarSchedule, WeeklySchedule};
use crate::target::Target;
use serde::{Deserialize, Serialize};

/// Bumped whenever the document shape changes. Migrations are forward-only (GAPS E3).
pub const CONFIG_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Config {
    pub schema_version: u32,
    /// IANA name. Budgets reset and schedules fire in *this* zone, not the device's, so a phone
    /// carried across a timezone does not silently hand back an allowance (or take one away).
    #[serde(default = "default_timezone")]
    pub timezone: String,
    #[serde(default)]
    pub profiles: Vec<Profile>,
    /// Recurring weekly windows.
    #[serde(default)]
    pub weekly: Vec<WeeklySchedule>,
    /// Calendar-driven sessions (DECISIONS: the feature the whole project is named for).
    #[serde(default)]
    pub calendars: Vec<CalendarSchedule>,
    /// Website blocking beyond the hosts file.
    #[serde(default)]
    pub resolver: Resolver,
}

/// The local DNS proxy, which is what makes a blocked domain cover its subdomains.
///
/// Off by default, and deliberately so: it takes over name resolution for the whole machine, and a
/// tool that quietly repoints a user's DNS the first time it runs has helped itself to something it
/// was not given. The hosts file works without it and stays the floor underneath it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Resolver {
    #[serde(default)]
    pub enabled: bool,
    /// Where everything that is not blocked is sent. Whatever the machine used before Curfew is not
    /// usable here — the interface now points at Curfew, so following it would be a loop.
    #[serde(default = "default_upstream")]
    pub upstream: String,
}

fn default_upstream() -> String {
    "1.1.1.1:53".to_string()
}

impl Default for Resolver {
    fn default() -> Self {
        Self { enabled: false, upstream: default_upstream() }
    }
}

fn default_timezone() -> String {
    "UTC".to_string()
}

impl Default for Config {
    fn default() -> Self {
        Self {
            schema_version: CONFIG_SCHEMA_VERSION,
            timezone: default_timezone(),
            profiles: Vec::new(),
            weekly: Vec::new(),
            calendars: Vec::new(),
            resolver: Resolver::default(),
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("could not parse config: {0}")]
    Parse(#[from] toml::de::Error),
    #[error("could not serialize config: {0}")]
    Serialize(#[from] toml::ser::Error),
    /// The file is from a newer Curfew. Refuse rather than silently dropping fields we cannot
    /// round-trip — the user's file is preserved untouched (GAPS E3).
    #[error("config schema version {found} is newer than supported version {supported}")]
    FromTheFuture { found: u32, supported: u32 },
    #[error("unknown timezone {0:?}")]
    UnknownTimezone(String),
    #[error("{0}")]
    Invalid(String),
}

impl Config {
    /// Parse, migrating older schema versions forward on the way in.
    pub fn from_toml(s: &str) -> Result<Self, ConfigError> {
        let raw: toml::Value = toml::from_str(s)?;
        let found =
            raw.get("schema_version").and_then(|v| v.as_integer()).unwrap_or(0).max(0) as u32;
        if found > CONFIG_SCHEMA_VERSION {
            return Err(ConfigError::FromTheFuture { found, supported: CONFIG_SCHEMA_VERSION });
        }

        let migrated = migrate::forward(raw, found)?;
        let cfg: Config = migrated.try_into().map_err(ConfigError::Parse)?;
        cfg.validate()?;
        Ok(cfg)
    }

    pub fn to_toml(&self) -> Result<String, ConfigError> {
        Ok(toml::to_string_pretty(self)?)
    }

    pub fn profile(&self, id: &str) -> Option<&Profile> {
        self.profiles.iter().find(|p| p.id == id)
    }

    /// Replace a profile's blocked-app list with exactly `packages`.
    ///
    /// This is what an app picker means: the checked boxes are the whole answer, so unchecking one
    /// has to remove its rule. Only plain `app_package` + `block` rules are touched — a budget on
    /// an app, or a rule written by hand with a delay or a platform filter, is the user saying
    /// something more specific than the picker can express, and is left exactly as it was.
    ///
    /// Round-tripping through the parsed document loses comments and reorders keys, which is why
    /// the UI that calls this says so before it writes.
    pub fn set_blocked_apps(
        &mut self,
        profile: &str,
        packages: &[String],
    ) -> Result<(), ConfigError> {
        let Some(p) = self.profiles.iter_mut().find(|p| p.id == profile) else {
            return Err(ConfigError::Invalid(format!("no profile {profile:?}")));
        };
        p.rules.retain(|r| {
            !matches!(
                (&r.target, &r.action),
                (Target::AppPackage { .. }, Action::Block) if r.platforms.is_empty()
            )
        });
        // Deduplicated and ordered, so the written file does not churn when the picker returns the
        // same set in a different order.
        let mut wanted: Vec<String> = packages.to_vec();
        wanted.sort();
        wanted.dedup();
        for package in wanted {
            if package.trim().is_empty() {
                return Err(ConfigError::Invalid("an app rule has an empty package".into()));
            }
            p.rules.push(Rule {
                target: Target::AppPackage { package },
                action: Action::Block,
                platforms: Vec::new(),
            });
        }
        self.validate()
    }

    /// The packages a picker should show as checked: the ones [`Config::set_blocked_apps`] owns.
    pub fn blocked_apps(&self, profile: &str) -> Vec<String> {
        self.profile(profile)
            .map(|p| {
                p.rules
                    .iter()
                    .filter_map(|r| match (&r.target, &r.action) {
                        (Target::AppPackage { package }, Action::Block)
                            if r.platforms.is_empty() =>
                        {
                            Some(package.clone())
                        }
                        _ => None,
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    /// The timezone budgets and schedules are evaluated in.
    pub fn tz(&self) -> Result<chrono_tz::Tz, ConfigError> {
        self.timezone.parse().map_err(|_| ConfigError::UnknownTimezone(self.timezone.clone()))
    }

    /// Structural checks that serde cannot express. A config that would behave surprisingly is
    /// rejected at load, where the user can still see why, rather than at 4am inside a lock.
    pub fn validate(&self) -> Result<(), ConfigError> {
        self.tz()?;
        let mut seen = std::collections::BTreeSet::new();
        for p in &self.profiles {
            if p.id.trim().is_empty() {
                return Err(ConfigError::Invalid("a profile has an empty id".into()));
            }
            if !seen.insert(&p.id) {
                return Err(ConfigError::Invalid(format!("duplicate profile id {:?}", p.id)));
            }
            for r in &p.rules {
                if let Action::Budget { seconds: 0, .. } = r.action {
                    // A zero budget is a block wearing a costume, and it reads as a mistake.
                    return Err(ConfigError::Invalid(format!(
                        "profile {:?} has a zero-second budget; use a block rule instead",
                        p.id
                    )));
                }
                if let Action::LaunchLimit { count: 0, .. } = r.action {
                    return Err(ConfigError::Invalid(format!(
                        "profile {:?} has a zero launch limit; use a block rule instead",
                        p.id
                    )));
                }
            }
        }
        // A schedule naming a profile that does not exist would start a session that enforces
        // nothing: a lock with no rules behind it, which is worse than an error because it looks
        // like it is working. Catch the typo at load, where it can still be corrected.
        for (kind, id, profile) in self
            .weekly
            .iter()
            .map(|w| ("weekly schedule", &w.id, &w.profile))
            .chain(self.calendars.iter().map(|c| ("calendar rule", &c.id, &c.profile)))
        {
            if self.profile(profile).is_none() {
                return Err(ConfigError::Invalid(format!(
                    "{kind} {id:?} names profile {profile:?}, which is not defined"
                )));
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Profile {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub rules: Vec<Rule>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Rule {
    pub target: Target,
    pub action: Action,
    /// Empty means every platform.
    #[serde(default)]
    pub platforms: Vec<Platform>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    #[default]
    Android,
    Windows,
    Browser,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Action {
    Block,
    /// Everything *not* matched by an allow-only rule in the active profile is blocked.
    AllowOnly,
    /// A shared time budget. Consumption is summed across devices from the op-log, so the budget
    /// is global rather than per-device (ARCHITECTURE.md §3).
    Budget {
        seconds: u32,
        #[serde(default)]
        refill: Refill,
    },
    /// A cap on how many times a thing may be opened in the window, regardless of time spent.
    /// Catches the compulsive-checking pattern that a time budget misses entirely.
    LaunchLimit {
        count: u32,
        #[serde(default)]
        refill: Refill,
    },
    /// Friction rather than a wall: hold the user for N seconds before allowing through.
    Delay {
        seconds: u32,
    },
    /// Silence notifications from the target without blocking it.
    MuteNotifications,
}

mod migrate {
    use super::{ConfigError, CONFIG_SCHEMA_VERSION};
    use toml::Value;

    /// Apply migrations in order until the document is current. Forward-only, one step per version
    /// bump, each step total: a document that reaches here has already been version-checked.
    pub fn forward(mut doc: Value, from: u32) -> Result<Value, ConfigError> {
        let mut version = from;
        while version < CONFIG_SCHEMA_VERSION {
            doc = match version {
                0 => v0_to_v1(doc),
                v => {
                    return Err(ConfigError::Invalid(format!("no migration from version {v}")));
                }
            };
            version += 1;
        }
        if let Some(t) = doc.as_table_mut() {
            t.insert("schema_version".into(), Value::Integer(CONFIG_SCHEMA_VERSION as i64));
        }
        Ok(doc)
    }

    /// v0 -> v1.
    ///
    /// - `window_title_contains { text }` became a glob target (DECISIONS D8). A substring match is
    ///   exactly `*text*`, so the migration is lossless.
    /// - budgets gained a refill policy. v0 budgets had no window at all, which in practice meant
    ///   "until someone clears the ledger"; the honest reading of user intent is a daily reset, and
    ///   that is also what the UI offered, so v0 budgets migrate to the default daily refill.
    /// - `timezone` did not exist; UTC is filled in by serde's default.
    fn v0_to_v1(mut doc: Value) -> Value {
        let Some(profiles) = doc.get_mut("profiles").and_then(|p| p.as_array_mut()) else {
            return doc;
        };
        for profile in profiles {
            let Some(rules) = profile.get_mut("rules").and_then(|r| r.as_array_mut()) else {
                continue;
            };
            for rule in rules {
                if let Some(target) = rule.get_mut("target").and_then(|t| t.as_table_mut()) {
                    if target.get("kind").and_then(|k| k.as_str()) == Some("window_title_contains")
                    {
                        let text = target
                            .remove("text")
                            .and_then(|t| t.as_str().map(str::to_string))
                            .unwrap_or_default();
                        target.insert("kind".into(), Value::String("window_title".into()));
                        target.insert("pattern".into(), Value::String(format!("*{text}*")));
                    }
                }
            }
        }
        doc
    }
}
