//! The `curfew.toml` document. Everything the UI can express lives here, so a config is a
//! complete, diffable description of a user's setup (design invariant 4).

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Bumped whenever the document shape changes. Migrations are forward-only (GAPS E3).
pub const CONFIG_SCHEMA_VERSION: u32 = 0;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Config {
    pub schema_version: u32,
    #[serde(default)]
    pub profiles: Vec<Profile>,
}

impl Default for Config {
    fn default() -> Self {
        Self { schema_version: CONFIG_SCHEMA_VERSION, profiles: Vec::new() }
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
}

impl Config {
    pub fn from_toml(s: &str) -> Result<Self, ConfigError> {
        let cfg: Config = toml::from_str(s)?;
        if cfg.schema_version > CONFIG_SCHEMA_VERSION {
            return Err(ConfigError::FromTheFuture {
                found: cfg.schema_version,
                supported: CONFIG_SCHEMA_VERSION,
            });
        }
        Ok(cfg)
    }

    pub fn to_toml(&self) -> Result<String, ConfigError> {
        Ok(toml::to_string_pretty(self)?)
    }

    pub fn profile(&self, id: &str) -> Option<&Profile> {
        self.profiles.iter().find(|p| p.id == id)
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    Android,
    Windows,
    Browser,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Target {
    /// Android package name, exact match.
    AppPackage { package: String },
    /// Windows executable name, case-insensitive, no path.
    WindowsExe { exe: String },
    /// Domain, matching the domain itself and any subdomain.
    Domain { domain: String },
    /// Substring of a window title. Cheap and predictable; regex is deliberately deferred until
    /// there is a rule the substring form cannot express.
    WindowTitleContains { text: String },
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
    },
    /// Friction rather than a wall: hold the user for N seconds before allowing through.
    Delay {
        seconds: u32,
    },
}

/// Consumed budget per target key, seconds. Materialized from the op-log by the caller.
pub type BudgetLedger = BTreeMap<String, u32>;
