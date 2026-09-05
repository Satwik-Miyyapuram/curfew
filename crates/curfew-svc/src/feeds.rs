//! Fetching a calendar subscription over the network.
//!
//! Kept in the service rather than in `curfew-win` so that the enforcement crate — the part with
//! all the interesting decisions in it — has no HTTP client in its dependency tree and its tests
//! cannot accidentally reach a network.
//!
//! What this deliberately does *not* do is authenticate. A subscription URL is the whole credential
//! for the calendars that use them, and asking a user for a Google password so their meetings can
//! block their games would be asking for far more than the job needs. A calendar that will not
//! serve a secret URL can be exported to a file instead, which the local reader handles.

use curfew_win::calendar::{is_url, Fetch, LocalFiles, MAX_BYTES};
use std::io::Read;
use std::time::Duration;

/// How long to wait on a subscription. Short: a pass runs every two seconds, and a fetch that hangs
/// for a minute is a fetch holding up enforcement.
const TIMEOUT: Duration = Duration::from_secs(20);

/// Reads local files, and fetches `http(s)`/`webcal` subscriptions.
pub struct Subscriptions {
    agent: ureq::Agent,
}

impl Default for Subscriptions {
    fn default() -> Self {
        Self {
            agent: ureq::Agent::config_builder()
                .timeout_global(Some(TIMEOUT))
                // Redirects are followed, but not many: a subscription URL that bounces a dozen
                // times is not a calendar server having a bad day, it is something else.
                .max_redirects(5)
                .user_agent(concat!("Curfew/", env!("CARGO_PKG_VERSION")))
                .build()
                .into(),
        }
    }
}

impl Fetch for Subscriptions {
    fn fetch(&self, location: &str) -> Result<String, String> {
        if !is_url(location) {
            return LocalFiles.fetch(location);
        }
        let response = self.agent.get(location).call().map_err(|e| e.to_string())?;
        let status = response.status();
        if !status.is_success() {
            return Err(format!("the calendar server answered {status}"));
        }
        // Read with a hard ceiling rather than trusting Content-Length: the length is a claim made
        // by whatever is on the other end of a URL the user pasted.
        let mut text = String::new();
        response
            .into_body()
            .into_reader()
            .take(MAX_BYTES as u64 + 1)
            .read_to_string(&mut text)
            .map_err(|e| e.to_string())?;
        if text.len() > MAX_BYTES {
            return Err(format!("the calendar is larger than {MAX_BYTES} bytes"));
        }
        Ok(text)
    }
}
