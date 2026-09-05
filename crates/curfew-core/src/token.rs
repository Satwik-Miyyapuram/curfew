//! Physical tokens: the lock you walk to.
//!
//! [`Lock::Token`] is satisfied by a thing rather than by a secret you know — an NFC sticker on the
//! fridge, a printed QR code left in another room. The friction is the walk, so the whole design
//! goal is that possession of the tag is the only way to produce the evidence, and that knowing
//! Curfew's config is not.
//!
//! That is why the config stores a *fingerprint* of the tag's payload and never the payload
//! itself. Someone who reads `curfew.toml` — which is a plain file the user is encouraged to back
//! up, diff and sync — learns that a tag exists and what it is called, and cannot write one. The
//! fingerprint is a plain SHA-256: the payload is a long random string minted by
//! [`fingerprint`]'s caller rather than a human-chosen password, so there is nothing here for a
//! dictionary to attack and no reason to make honest verification slow.
//!
//! The service verifies. A caller that could simply *claim* `Lock::Token { id }` was satisfied
//! would make the tag decorative, which is the same mistake as trusting a client's "I typed the
//! password" — see [`crate::lock::Lock::DeviceCredential`].
//!
//! [`Lock::Token`]: crate::lock::Lock::Token

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// One physical tag, as the config declares it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Tag {
    /// What a lock refers to, and what the UI says out loud: "the tag on the fridge".
    pub id: String,
    /// Lowercase hex SHA-256 of the tag's payload. Produced by [`fingerprint`].
    pub hash: String,
}

/// The fingerprint of a tag's payload, as it is written in the config.
pub fn fingerprint(payload: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"curfew-token-v1");
    hasher.update(payload.trim().as_bytes());
    hasher.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

/// Which tag, if any, this payload is. `None` means the scan was of something else entirely.
///
/// Compared in constant time. The comparison is between two fingerprints rather than two secrets,
/// so a timing leak would only ever reveal how much of a *hash* matched — but a scanner that
/// answers faster for a nearly-right tag is a needless thing to have to reason about.
pub fn identify(tags: &[Tag], payload: &str) -> Option<String> {
    let seen = fingerprint(payload);
    let mut found: Option<String> = None;
    for tag in tags {
        if equal(&tag.hash.to_ascii_lowercase(), &seen) && found.is_none() {
            found = Some(tag.id.clone());
        }
    }
    found
}

fn equal(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.bytes().zip(b.bytes()) {
        diff |= x ^ y;
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tags() -> Vec<Tag> {
        vec![
            Tag { id: "fridge".into(), hash: fingerprint("a-long-random-payload") },
            Tag { id: "desk".into(), hash: fingerprint("another-payload") },
        ]
    }

    #[test]
    fn a_fingerprint_is_stable_and_is_not_the_payload() {
        let hash = fingerprint("a-long-random-payload");
        assert_eq!(hash, fingerprint("a-long-random-payload"));
        assert_eq!(64, hash.len());
        assert!(!hash.contains("payload"));
    }

    #[test]
    fn surrounding_whitespace_is_not_part_of_the_tag() {
        // An NFC read and a pasted string differ by a trailing newline often enough that treating
        // them as different tags would only ever be read as a broken reader.
        assert_eq!(fingerprint("payload"), fingerprint("  payload\n"));
    }

    #[test]
    fn the_right_tag_names_itself() {
        assert_eq!(Some("desk".to_string()), identify(&tags(), "another-payload"));
    }

    #[test]
    fn anything_else_is_nobody() {
        assert_eq!(None, identify(&tags(), "a-long-random-payloae"));
        assert_eq!(None, identify(&tags(), ""));
    }

    #[test]
    fn a_hash_written_in_capitals_still_matches() {
        let tags = vec![Tag {
            id: "fridge".into(),
            hash: fingerprint("a-long-random-payload").to_ascii_uppercase(),
        }];
        assert_eq!(Some("fridge".to_string()), identify(&tags, "a-long-random-payload"));
    }

    #[test]
    fn no_tags_configured_means_no_scan_can_ever_pass() {
        assert_eq!(None, identify(&[], "a-long-random-payload"));
    }
}
