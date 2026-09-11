//! The `curfew.toml` document. Everything the UI can express lives here, so a config is a
//! complete, diffable description of a user's setup (design invariant 4).

use crate::budget::Refill;
use crate::emergency::EmergencyPolicy;
use crate::schedule::{CalendarSchedule, CalendarSource, WeeklySchedule};
use crate::target::Target;
use serde::{Deserialize, Serialize};

/// Bumped whenever the document shape changes. Migrations are forward-only (GAPS E3).
pub const CONFIG_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
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
    /// Where calendar events come from on a desktop, which has no system calendar to read. Empty
    /// on Android, where the events come from the platform's own provider instead.
    #[serde(default)]
    pub calendar_sources: Vec<CalendarSource>,
    /// Website blocking beyond the hosts file.
    #[serde(default)]
    pub resolver: Resolver,
    /// The escape hatch, and how tightly it is rationed. Disabled unless the user asks for it.
    #[serde(default)]
    pub emergency: EmergencyPolicy,
    /// Physical tags a `Lock::Token` can be satisfied by, as fingerprints rather than payloads
    /// (see [`crate::token`]). Empty means no scan can release anything, which is the right
    /// default: a token lock with no tag behind it must fail shut.
    #[serde(default)]
    pub tokens: Vec<crate::token::Tag>,
}

/// The local DNS proxy, which is what makes a blocked domain cover its subdomains.
///
/// Off by default, and deliberately so: it takes over name resolution for the whole machine, and a
/// tool that quietly repoints a user's DNS the first time it runs has helped itself to something it
/// was not given. The hosts file works without it and stays the floor underneath it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
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
            calendar_sources: Vec::new(),
            resolver: Resolver::default(),
            emergency: EmergencyPolicy::default(),
            tokens: Vec::new(),
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

/// What an upsert did, so a caller can say so instead of guessing.
///
/// `AlreadyPresent` is the one that earns its keep. The upsert deliberately declines to store a
/// duplicate, and before this a caller had no way to know: `curfew add-window` printed "Added"
/// for a window the core had just discarded, based on whether the *id* was new. On a tool whose
/// whole job is to be trusted about whether a lock exists, that is the wrong kind of wrong.
///
/// `#[must_use]`-shaped by convention rather than by attribute: every caller either reports it or
/// discards it with `.map(|_| ())`, and the ones that report are the ones that print.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Upserted {
    /// Stored. It was not there before.
    Added,
    /// Stored, replacing the row with the same id.
    Replaced,
    /// **Nothing was stored**: an equivalent window already exists under another id, and two of
    /// them cannot behave differently from one. The caller's request is satisfied by what is
    /// already in the plan, not by a new row.
    AlreadyPresent,
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

    /// What `next` would take away from a running session on `profile` — P1-13.
    ///
    /// **A session does not hold its own rules.** `Engine::decide` reads them from the live `Config`
    /// on every pass (`engine.rs:64` takes `config` as an argument), so a rule removed from the config
    /// stops being enforced immediately while the session — and its lock — carries on. The user sees a
    /// lock still running and nothing being blocked, which is the worst state this product has: the
    /// surface says yes and the machine does no.
    ///
    /// Two comments in this repository claim otherwise and both were wrong: `Config::remove_profile`
    /// below says *"the session holds its own copy of what it blocks"* and `GAPS.md` D6 says *"edits
    /// that would weaken an active session are refused outright until the lock ends"*. Neither was
    /// true. This function is the second of those claims, made true.
    ///
    /// A rule is identified the way [`Config::upsert_rule`] identifies it — the target's key plus the
    /// platform set — so this catches a rule removed, a rule that no longer covers the platform, and a
    /// rule whose **action** changed (a budget cut from an hour to a minute keeps its target and is
    /// still a weakening).
    ///
    /// Deliberately conservative in one direction: **changing** an action counts as a loss even when
    /// the new one is stricter, because telling "stricter" from "weaker" per action kind needs a
    /// lattice this does not have. Refusing a strengthening edit until the lock ends is an annoyance;
    /// accepting a weakening one is the bug.
    ///
    /// Returns one sentence per loss, so a caller can say exactly what it would not adopt.
    pub fn rules_weakened_by(&self, next: &Config, profile: &str) -> Vec<String> {
        let Some(before) = self.profile(profile) else {
            // The profile is not in the config that is running. Nothing of its was ever enforced, so
            // there is nothing to lose — a session outliving its profile is the case `remove_profile`
            // documents, and it is not this function's to judge.
            return Vec::new();
        };
        let after = next.profile(profile);

        let mut lost = Vec::new();
        for rule in &before.rules {
            let key = rule.target.key();
            let found = after.and_then(|p| {
                p.rules.iter().find(|r| r.target.key() == key && r.platforms == rule.platforms)
            });
            match found {
                None => lost.push(format!("{key} is no longer blocked")),
                Some(new) if new.action != rule.action => {
                    lost.push(format!("{key} is enforced differently now"))
                }
                Some(_) => {}
            }
        }
        lost
    }

    /// **Which rules a config change would take away from each running session** — P1-13.
    ///
    /// Empty means the change is safe to adopt. Anything else is a list of sentences naming the profile
    /// and what would stop being enforced, ready to put in front of a user.
    ///
    /// **One implementation, two platforms.** The decision is a property of the core — a session carries
    /// its own copy of what it blocks, so a rule removed underneath it stops being enforced while the lock
    /// runs on — and both Windows and Android have to make it. Windows grew this loop inline (entry 54)
    /// and Android had no check at all, which is the state this fixes. A second copy of a security check is
    /// how the two come to disagree, so the loop lives here and both callers pass what they have.
    ///
    /// `profile_names` maps a profile id to the name the user gave it, because the refusal is a sentence
    /// somebody reads and an id is not a name. A caller with no map passes an empty one and gets the id —
    /// degraded, never wrong.
    pub fn weakening_a_running_session(
        &self,
        next: &Config,
        running: &[crate::session::Session],
        profile_names: &std::collections::BTreeMap<String, String>,
    ) -> Vec<String> {
        let mut lost: Vec<String> = Vec::new();
        for session in running {
            let named = profile_names
                .get(&session.profile)
                .cloned()
                .unwrap_or_else(|| session.profile.clone());
            for detail in self.rules_weakened_by(next, &session.profile) {
                lost.push(format!("{named}: {detail}"));
            }
        }
        lost.sort();
        lost.dedup();
        lost
    }

    /// Add a rule to a profile, or replace the one already pointing at the same thing.
    ///
    /// Keyed on the target's own identity rather than on the whole rule, because "block
    /// youtube.com" and "give youtube.com twenty minutes" are two answers to one question, and
    /// keeping both would leave the stricter one deciding while the user reads the other. Platform
    /// is part of that identity: a rule for Windows only and a rule for everywhere are different
    /// statements about the same target, and a user who wrote both meant both.
    pub fn upsert_rule(&mut self, profile: &str, rule: Rule) -> Result<(), ConfigError> {
        let Some(p) = self.profiles.iter_mut().find(|p| p.id == profile) else {
            return Err(ConfigError::Invalid(format!("no profile {profile:?}")));
        };
        let before = p.rules.clone();
        let key = rule.target.key();
        match p.rules.iter_mut().find(|r| r.target.key() == key && r.platforms == rule.platforms) {
            Some(existing) => *existing = rule,
            None => p.rules.push(rule),
        }
        if let Err(e) = self.validate() {
            if let Some(p) = self.profiles.iter_mut().find(|p| p.id == profile) {
                p.rules = before;
            }
            return Err(e);
        }
        Ok(())
    }

    /// Drop every rule in a profile pointing at `target`, whatever it does to it and on whichever
    /// platform, and say how many went. Nothing is validated, because a config with a rule removed
    /// is valid whenever the one it came from was.
    pub fn remove_rule(&mut self, profile: &str, target: &Target) -> usize {
        let key = target.key();
        let Some(p) = self.profiles.iter_mut().find(|p| p.id == profile) else {
            return 0;
        };
        let before = p.rules.len();
        p.rules.retain(|r| r.target.key() != key);
        before - p.rules.len()
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
        // What a package was rationed by before, so a tick survives being re-saved: the picker
        // owns budgeted apps as well as plainly blocked ones (they are shown ticked), and dropping
        // them to a plain block on every save would have quietly deleted the budget the profile
        // screen had just set. Unticking still removes both — not blocked is not rationed.
        let budgets: Vec<(String, Action)> = p
            .rules
            .iter()
            .filter_map(|r| match (&r.target, &r.action) {
                // Cloned whole, refill included: a budget is more than its number of seconds.
                (Target::AppPackage { package }, Action::Budget { .. })
                    if r.platforms.is_empty() =>
                {
                    Some((package.clone(), r.action.clone()))
                }
                _ => None,
            })
            .collect();
        p.rules.retain(|r| {
            !matches!(
                (&r.target, &r.action),
                (Target::AppPackage { .. }, Action::Block | Action::Budget { .. })
                    if r.platforms.is_empty()
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
            let action = budgets
                .iter()
                .find(|(had, _)| *had == package)
                .map(|(_, action)| action.clone())
                .unwrap_or(Action::Block);
            p.rules.push(Rule {
                target: Target::AppPackage { package },
                action,
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
                    // A budget counts. There is one rule per target, so putting a package on a
                    // budget replaces its plain block — and when that dropped the package out of
                    // this list, setting a budget from the profile screen read as "Nothing yet"
                    // and the app picker forgot every tick. Rationed is a way of being blocked,
                    // not a way of being let go.
                    .filter_map(|r| match (&r.target, &r.action) {
                        (Target::AppPackage { package }, Action::Block | Action::Budget { .. })
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

    // --- profiles -------------------------------------------------------------------------------

    /// Add a profile, or rename the one that already has this id.
    ///
    /// The rules are *not* touched when a profile already exists: renaming "Deep work" must not
    /// quietly empty the list of apps it blocks, and the app picker owns that list. A new profile
    /// starts with no rules, which blocks nothing until the picker is used — an empty profile is a
    /// valid thing to have half-made, and refusing to create one would mean the only way to start
    /// is to write the whole thing at once.
    pub fn upsert_profile(
        &mut self,
        id: &str,
        name: &str,
        description: &str,
    ) -> Result<(), ConfigError> {
        let before = self.profiles.clone();
        match self.profiles.iter_mut().find(|p| p.id == id) {
            Some(existing) => {
                existing.name = name.to_string();
                existing.description = description.to_string();
            }
            None => self.profiles.push(Profile {
                id: id.to_string(),
                name: name.to_string(),
                description: description.to_string(),
                rules: Vec::new(),
            }),
        }
        if let Err(e) = self.validate() {
            self.profiles = before;
            return Err(e);
        }
        Ok(())
    }

    /// Delete a profile and everything it blocks.
    ///
    /// Refused while a schedule still names it, because the alternative is a config that will not
    /// load: a window pointing at a profile that is gone is exactly what [`Config::validate`]
    /// rejects, and discovering that at the next launch is discovering it with no blocker running.
    /// The schedules are named in the error so the user knows what to remove first.
    ///
    /// As everywhere else, a session this profile started keeps running. Its rules are gone from the
    /// config — **and the session does not hold a copy of them**, which this comment used to claim and
    /// which was false. `Engine::decide` reads the rules from the live `Config` on every pass, so a
    /// session whose profile is removed keeps its lock and blocks nothing.
    ///
    /// What stops that being a way out is the *adoption* side rather than this one:
    /// [`Config::rules_weakened_by`] is consulted before a reload is taken, and the service refuses a
    /// config that would enforce less than a running session promised. So the order is: the lock ends
    /// first, then the profile goes.
    pub fn remove_profile(&mut self, id: &str) -> Result<(), ConfigError> {
        let mut used: Vec<&str> = self
            .weekly
            .iter()
            .filter(|w| w.profile == id)
            .map(|w| w.id.as_str())
            .chain(self.calendars.iter().filter(|c| c.profile == id).map(|c| c.id.as_str()))
            .collect();
        used.sort_unstable();
        if !used.is_empty() {
            return Err(ConfigError::Invalid(format!(
                "profile {id:?} is still used by {}; remove or repoint {} first",
                used.join(", "),
                if used.len() == 1 { "it" } else { "them" }
            )));
        }
        self.profiles.retain(|p| p.id != id);
        Ok(())
    }

    // --- schedules ------------------------------------------------------------------------------

    /// Add a weekly window, or replace the one that already has this id.
    ///
    /// Upsert rather than add-or-fail because that is what an editor screen does: the same form
    /// opens for a new window and for an existing one, and which it was is not something the caller
    /// should have to tell us. The whole config is validated afterwards, so a window naming a
    /// profile that does not exist is refused here rather than at the moment it would have fired.
    ///
    /// The return value says which of the three happened; see [`Upserted`].
    pub fn upsert_weekly(&mut self, window: WeeklySchedule) -> Result<Upserted, ConfigError> {
        let before = self.weekly.clone();
        let outcome = match self.weekly.iter_mut().find(|w| w.id == window.id) {
            Some(existing) => {
                *existing = window;
                Upserted::Replaced
            }
            None => {
                // A new window that runs the same profile on the same days between the same two
                // minutes is not a second window, it is the first one asked for twice. Two of them
                // cannot behave differently from one, so keeping both only fills the plan with
                // rows the user then has to delete individually. A phone here collected eight of
                // them from a screen that saved on every recomposition; the screen was fixed, and
                // this is the layer that cannot be got wrong from a new caller.
                if self.weekly.iter().any(|w| {
                    w.profile == window.profile
                        && w.days == window.days
                        && w.start_minute == window.start_minute
                        && w.end_minute == window.end_minute
                }) {
                    // **Said out loud rather than returned as a bare `Ok`** — P2-10. This branch
                    // deliberately stores nothing, and a caller that cannot tell it apart from a
                    // real insert will tell the user the opposite of what happened: `curfew
                    // add-window` printed "Added" for a window it had just discarded. On a
                    // self-binding tool the difference matters, because the user is checking
                    // whether the lock they asked for exists.
                    return Ok(Upserted::AlreadyPresent);
                }
                self.weekly.push(window);
                Upserted::Added
            }
        };
        // Put the config back exactly as it was if the edit does not stand up. A validate that
        // leaves the invalid value behind turns one bad edit into a config nobody can save.
        if let Err(e) = self.validate() {
            self.weekly = before;
            return Err(e);
        }
        Ok(outcome)
    }

    /// Delete a weekly window. Deleting one that is not there is not an error: the caller wanted it
    /// gone, and it is gone.
    pub fn remove_weekly(&mut self, id: &str) {
        self.weekly.retain(|w| w.id != id);
    }

    /// Add a calendar rule, or replace the one that already has this id. See [`Config::upsert_weekly`].
    pub fn upsert_calendar(&mut self, rule: CalendarSchedule) -> Result<(), ConfigError> {
        let before = self.calendars.clone();
        match self.calendars.iter_mut().find(|c| c.id == rule.id) {
            Some(existing) => *existing = rule,
            None => self.calendars.push(rule),
        }
        if let Err(e) = self.validate() {
            self.calendars = before;
            return Err(e);
        }
        Ok(())
    }

    /// Delete a calendar rule.
    ///
    /// A session this rule started keeps running: it is a promise already made, and a settings
    /// edit is not a way out of a lock. The rule simply stops starting new ones.
    pub fn remove_calendar(&mut self, id: &str) {
        self.calendars.retain(|c| c.id != id);
    }

    /// Subscribe to a calendar, or replace the subscription with this id.
    ///
    /// The location is not fetched here: whether a URL answers is a question for the machine that
    /// will poll it, and a config edited on a phone would have no way to check a path on a PC.
    pub fn upsert_source(&mut self, source: CalendarSource) -> Result<(), ConfigError> {
        let before = self.calendar_sources.clone();
        match self.calendar_sources.iter_mut().find(|s| s.id == source.id) {
            Some(existing) => *existing = source,
            None => self.calendar_sources.push(source),
        }
        if let Err(e) = self.validate() {
            self.calendar_sources = before;
            return Err(e);
        }
        Ok(())
    }

    /// Unsubscribe. Rules that matched events from it simply stop matching; a session one of them
    /// already started keeps running, as every other settings edit does.
    pub fn remove_source(&mut self, id: &str) {
        self.calendar_sources.retain(|s| s.id != id);
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
            // A profile with no name is a chip with nothing written on it, which is unusable in
            // every UI that lists them.
            if p.name.trim().is_empty() {
                return Err(ConfigError::Invalid(format!("profile {:?} has no name", p.id)));
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
        let mut tags = std::collections::BTreeSet::new();
        for tag in &self.tokens {
            if tag.id.trim().is_empty() {
                return Err(ConfigError::Invalid("a token has an empty id".into()));
            }
            if !tags.insert(&tag.id) {
                return Err(ConfigError::Invalid(format!("duplicate token id {:?}", tag.id)));
            }
            // A payload written where a fingerprint belongs would be a secret sitting in a file
            // the user is told to back up and sync, and it would silently never match. Both are
            // worth catching at load rather than at the fridge.
            let hash = tag.hash.trim();
            if hash.len() != 64 || !hash.bytes().all(|b| b.is_ascii_hexdigit()) {
                return Err(ConfigError::Invalid(format!(
                    "token {:?} needs a 64-character hex fingerprint; run `curfew tag` to make one",
                    tag.id
                )));
            }
        }
        // A schedule naming a profile that does not exist would start a session that enforces
        // nothing: a lock with no rules behind it, which is worse than an error because it looks
        // like it is working. Catch the typo at load, where it can still be corrected.
        let mut schedules = std::collections::BTreeSet::new();
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
            // Ids identify a schedule to the editor that changes it and to the session it starts.
            // Two windows sharing one would make an edit to either land on whichever came first.
            if id.trim().is_empty() {
                return Err(ConfigError::Invalid(format!("a {kind} has an empty id")));
            }
            if !schedules.insert((kind, id)) {
                return Err(ConfigError::Invalid(format!("duplicate {kind} id {id:?}")));
            }
        }
        // A subscription with no id cannot be cached under a file name, and two sharing one would
        // overwrite each other's cached copy — an outage on one would then be served the other's
        // meetings. A refresh of zero would re-fetch someone's calendar server every pass.
        let mut sources = std::collections::BTreeSet::new();
        for s in &self.calendar_sources {
            if s.id.trim().is_empty() {
                return Err(ConfigError::Invalid("a calendar source has an empty id".into()));
            }
            if !sources.insert(&s.id) {
                return Err(ConfigError::Invalid(format!(
                    "duplicate calendar source id {:?}",
                    s.id
                )));
            }
            if s.location.trim().is_empty() {
                return Err(ConfigError::Invalid(format!(
                    "calendar source {:?} has no file or URL to read",
                    s.id
                )));
            }
            if s.refresh_seconds == 0 {
                return Err(ConfigError::Invalid(format!(
                    "calendar source {:?} refreshes every zero seconds",
                    s.id
                )));
            }
        }
        // The escape hatch is a safety rail, and an unvalidated rail is not one. Nothing used to
        // look at `self.emergency` at all, so a zero-length window made the ration unlimited
        // (`recent` computes `since = now - 0` and therefore never finds a spent pass) and a zero
        // cooldown removed the minimum gap between two uses. Both are the same class as the
        // zero-second budget and the zero launch limit above: a config that disables a documented
        // guard, accepted silently, discovered at 4am inside a lock.
        if self.emergency.window_seconds == 0 {
            return Err(ConfigError::Invalid(
                "[emergency] has a zero-second window, which makes the quota unlimited; \
                 use a positive number of seconds, or set passes = 0 to switch the hatch off"
                    .into(),
            ));
        }
        if self.emergency.passes > 0 && self.emergency.cooldown_seconds == 0 {
            return Err(ConfigError::Invalid(
                "[emergency] allows passes but sets a zero-second cooldown, which removes the \
                 minimum gap between two uses; use a positive number of seconds"
                    .into(),
            ));
        }
        // A rolling window of zero is the same defect one level down: `used_since(Some(now))` would
        // match only rollups stamped at or after `now`, so the budget could never be exhausted and
        // the rule would never fire.
        for p in &self.profiles {
            for r in &p.rules {
                if let Action::Budget { refill: Refill::Rolling { seconds: 0 }, .. } = r.action {
                    return Err(ConfigError::Invalid(format!(
                        "profile {:?} has a budget with a zero-second rolling window, so it can \
                         never be spent; use a positive number of seconds",
                        p.id
                    )));
                }
            }
        }
        // A minute past the end of the day is not a time. `end == start` is the one that reads as a
        // mistake either way — an empty window or a whole day, depending on who is asked — so it is
        // refused rather than guessed at.
        for w in &self.weekly {
            if w.start_minute >= 1_440 || w.end_minute >= 1_440 {
                return Err(ConfigError::Invalid(format!(
                    "weekly schedule {:?} has a time outside the day",
                    w.id
                )));
            }
            if w.start_minute == w.end_minute {
                return Err(ConfigError::Invalid(format!(
                    "weekly schedule {:?} starts and ends at the same minute",
                    w.id
                )));
            }
            if w.days.iter().any(|d| *d > 6) {
                return Err(ConfigError::Invalid(format!(
                    "weekly schedule {:?} names a day that is not a day of the week",
                    w.id
                )));
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Profile {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub rules: Vec<Rule>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Rule {
    pub target: Target,
    pub action: Action,
    /// Empty means every platform.
    #[serde(default)]
    pub platforms: Vec<Platform>,
}

impl Rule {
    /// **Whether this rule can only be decided by knowing the foreground window** — P2-16.
    ///
    /// Two things need it. A [`Target::WindowTitle`] or [`Target::Keyword`] rule matches against the
    /// title of whatever is in front, and a [`Action::Budget`] or [`Action::LaunchLimit`] is charged to
    /// whatever is in front. Every other target — an exe, a domain, a path, the whole device — is
    /// decided from the process list or the resolver, which works without anybody's attention.
    ///
    /// This exists so a surface can say *which* rules have stopped being enforced when nothing can
    /// supply the foreground window, rather than the gap being silent. See
    /// `curfew_win::Enforcer::foreground_warning`.
    pub fn needs_foreground(&self) -> bool {
        matches!(self.target, Target::WindowTitle { .. } | Target::Keyword { .. })
            || matches!(self.action, Action::Budget { .. } | Action::LaunchLimit { .. })
    }
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
#[serde(deny_unknown_fields, tag = "kind", rename_all = "snake_case")]
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
