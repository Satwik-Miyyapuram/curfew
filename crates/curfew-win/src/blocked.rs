//! Which domains a config would block right now.
//!
//! The hosts file is a list, not a decision function, so it has to be derived ahead of time rather
//! than asked question by question the way processes are. This walks the active profiles' rules and
//! returns the names that are blocked outright at `now`.

use curfew_core::{decide, Config, Decision, Observation, State, Target, Timestamp, Url};
use std::collections::BTreeSet;

/// Every domain that would be blocked at `now`, ready for the hosts file.
///
/// Budget and launch-limit rules are included only once they are actually exhausted: writing a
/// blocked entry for a site the user still has twenty minutes of would be a lie told by the
/// resolver, and the user would rightly call it a bug. That is why each candidate is put back
/// through [`decide`] rather than read off the rule's shape.
pub fn blocked_domains(now: Timestamp, state: &State, config: &Config) -> BTreeSet<String> {
    let mut blocked = BTreeSet::new();
    for profile in &config.profiles {
        if !state.active_profiles.contains(&profile.id) {
            continue;
        }
        for rule in &profile.rules {
            let Target::Domain { domain } = &rule.target else { continue };
            let url = Url::parse(&format!("https://{domain}/"));
            if let Decision::Block { .. } = decide(now, state, &Observation::Web { url }, config) {
                blocked.insert(domain.to_lowercase());
            }
        }
    }
    blocked
}
