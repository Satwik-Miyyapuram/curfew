//! Pairing two devices, with nobody to introduce them.
//!
//! There is no server to vouch for anyone, so the person holding both devices is the trust anchor:
//! one device shows an invite (a QR code, or a line of text), the other reads it, and both then
//! display the same short phrase for the user to compare. Matching phrases mean nobody sat in the
//! middle of the exchange, because the phrase is derived from both devices' real keys and the
//! invite's nonce — swap any of the three and the phrases differ (GAPS C2).
//!
//! The phrase is checked by a human, so it is short. That is a deliberate trade: a 50-bit phrase
//! gives an attacker who is relaying the exchange in real time one guess in a million million, and
//! the alternative — a phrase nobody reads because it is too long — gives them all of them.
//!
//! Unpairing is the other half. Every device holds its own key, so removing one is a local decision
//! that needs no coordination and no shared secret to be rotated: the peer is marked revoked, its
//! signatures stop being accepted from that moment, and nothing it says afterwards is replayed
//! (GAPS C3).

use crate::device::{DeviceId, Error as DeviceError, Identity, PublicIdentity};
use curfew_core::Timestamp;
use serde::{Deserialize, Serialize};
use sha2::{Digest as _, Sha256};
use std::collections::BTreeMap;

/// How long an invite is good for. Long enough to walk across a room, short enough that a QR code
/// photographed off a screen is not a standing invitation to join later.
pub const INVITE_SECONDS: i64 = 5 * 60;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum Error {
    #[error("that invite has expired — show a new one")]
    Expired,
    #[error("an invite from the future is not trusted")]
    NotYetValid,
    #[error("a device cannot pair with itself")]
    Itself,
    #[error("that device was removed on {at}; pair it again from both sides to undo that")]
    Revoked { at: Timestamp },
    #[error("that device is not paired with this one")]
    Unknown,
    #[error("that was not a valid Curfew pairing code")]
    Malformed,
    #[error(transparent)]
    Device(#[from] DeviceError),
}

const COMPACT_VERSION: u8 = 1;
const B64_CHARS: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

fn b64_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity((data.len() * 4 + 2) / 3);
    for chunk in data.chunks(3) {
        let b0 = chunk[0];
        let b1 = if chunk.len() > 1 { chunk[1] } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] } else { 0 };
        out.push(B64_CHARS[(b0 >> 2) as usize] as char);
        out.push(B64_CHARS[(((b0 & 0x03) << 4) | (b1 >> 4)) as usize] as char);
        if chunk.len() > 1 {
            out.push(B64_CHARS[(((b1 & 0x0f) << 2) | (b2 >> 6)) as usize] as char);
        }
        if chunk.len() > 2 {
            out.push(B64_CHARS[(b2 & 0x3f) as usize] as char);
        }
    }
    out
}

fn b64_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity((s.len() * 3) / 4);
    let mut buf = 0u32;
    let mut bits = 0;
    for &b in s.as_bytes() {
        let val = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'-' | b'+' => 62,
            b'_' | b'/' => 63,
            b'=' | b' ' | b'\r' | b'\n' | b'\t' => continue,
            _ => return None,
        };
        buf = (buf << 6) | (val as u32);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
            buf &= (1 << bits) - 1;
        }
    }
    Some(out)
}

/// What one device shows the other: its public keys and a one-time nonce.
///
/// Nothing here is secret — an invite may be photographed, logged or posted publicly without
/// weakening anything. What it cannot do is authenticate itself, which is exactly why the phrase
/// exists.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Invite {
    pub from: PublicIdentity,
    pub nonce: [u8; 16],
    pub issued_at: Timestamp,
    pub expires_at: Timestamp,
}

impl Invite {
    pub fn new(identity: &Identity, now: Timestamp, nonce: [u8; 16]) -> Self {
        Self { from: identity.public(), nonce, issued_at: now, expires_at: now + INVITE_SECONDS }
    }

    /// A fresh invite from the operating system's randomness.
    pub fn offer(identity: &Identity, now: Timestamp) -> Self {
        use rand_core::RngCore as _;
        let mut nonce = [0u8; 16];
        rand_core::OsRng.fill_bytes(&mut nonce);
        Self::new(identity, now, nonce)
    }

    /// A compact representation of this invite.
    ///
    /// Encodes the keys, nonce, and validity window into a ~130-character URL-safe string
    /// prefixed with `CRFW:`. Much smaller and faster to copy or scan than a 500-character JSON blob.
    pub fn to_compact(&self) -> String {
        let name_bytes = self.from.name.as_bytes();
        let name_len = name_bytes.len().min(32);
        let mut buf = Vec::with_capacity(90 + name_len);
        buf.push(COMPACT_VERSION);
        buf.extend_from_slice(&self.from.signing);
        buf.extend_from_slice(&self.from.exchange);
        buf.extend_from_slice(&self.nonce);
        buf.extend_from_slice(&(self.issued_at as u32).to_be_bytes());
        buf.extend_from_slice(&(self.expires_at as u32).to_be_bytes());
        buf.push(name_len as u8);
        buf.extend_from_slice(&name_bytes[..name_len]);
        format!("CRFW:{}", b64_encode(&buf))
    }

    /// Parse an invite from either the compact format or legacy JSON.
    pub fn from_str_lenient(text: &str) -> Result<Self, Error> {
        let trimmed = text.trim();
        if trimmed.is_empty() {
            return Err(Error::Malformed);
        }

        // If it starts with a JSON object, parse as legacy JSON first.
        if trimmed.starts_with('{') {
            return serde_json::from_str(trimmed).map_err(|_| Error::Malformed);
        }

        // If it is a deep link URL (e.g. https://curfew.dev/pair?code=CRFW:... or curfew://pair?code=...),
        // extract the code query parameter.
        let raw_code = if let Some(pos) = trimmed.find("code=") {
            let after = &trimmed[pos + 5..];
            let end = after.find('&').unwrap_or(after.len());
            &after[..end]
        } else {
            trimmed
        };

        // Strip prefix if present (case-insensitive "crfw:").
        let body = if raw_code.len() >= 5 && raw_code[..5].eq_ignore_ascii_case("crfw:") {
            &raw_code[5..]
        } else {
            raw_code
        };

        if let Some(bytes) = b64_decode(body) {
            if bytes.len() >= 90 && bytes[0] == COMPACT_VERSION {
                let signing: [u8; 32] = bytes[1..33].try_into().unwrap();
                let exchange: [u8; 32] = bytes[33..65].try_into().unwrap();
                let nonce: [u8; 16] = bytes[65..81].try_into().unwrap();
                let issued_at = u32::from_be_bytes(bytes[81..85].try_into().unwrap()) as i64;
                let expires_at = u32::from_be_bytes(bytes[85..89].try_into().unwrap()) as i64;
                let name_len = bytes[89] as usize;
                let name = if bytes.len() >= 90 + name_len && name_len > 0 {
                    String::from_utf8_lossy(&bytes[90..90 + name_len]).into_owned()
                } else {
                    "device".to_string()
                };
                return Ok(Self {
                    from: PublicIdentity { name, signing, exchange },
                    nonce,
                    issued_at,
                    expires_at,
                });
            }
        }

        // Fallback to JSON in case it was JSON with whitespace or without leading {
        serde_json::from_str(trimmed).map_err(|_| Error::Malformed)
    }

    fn check(&self, now: Timestamp) -> Result<(), Error> {
        if now >= self.expires_at {
            return Err(Error::Expired);
        }
        // A device whose clock is far behind would otherwise accept an invite for as long as the
        // skew lasts, which is the one window worth closing here.
        if now < self.issued_at {
            return Err(Error::NotYetValid);
        }
        Ok(())
    }
}

/// The phrase both devices show. Six digits in two groups, because a phrase people compare has to
/// survive being read down a phone line.
pub fn phrase(a: &PublicIdentity, b: &PublicIdentity, nonce: &[u8; 16]) -> String {
    // Ordered by key, not by who started it: both sides must derive the same phrase without
    // agreeing first on which of them is which.
    let (first, second) = if a.signing <= b.signing { (a, b) } else { (b, a) };
    let mut hasher = Sha256::new();
    hasher.update(b"curfew-pairing-v1");
    hasher.update(first.signing);
    hasher.update(first.exchange);
    hasher.update(second.signing);
    hasher.update(second.exchange);
    hasher.update(nonce);
    let digest = hasher.finalize();
    let value = u32::from_be_bytes(digest[..4].try_into().unwrap()) % 1_000_000;
    let text = format!("{value:06}");
    format!("{} {}", &text[..3], &text[3..])
}

/// One paired device, as this device remembers it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Peer {
    pub identity: PublicIdentity,
    pub paired_at: Timestamp,
    /// Set when the peer is removed. Kept rather than deleted so that anything it signed before
    /// that moment can still be recognised, and so the UI can say the device was removed instead
    /// of quietly forgetting it existed.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub revoked_at: Option<Timestamp>,
}

impl Peer {
    pub fn is_active(&self) -> bool {
        self.revoked_at.is_none()
    }
}

/// How many signature verifications this thread has performed. See [`Peers::verify`].
///
/// **Thread-local on purpose.** `cargo test` runs tests in parallel, so a global counter would be bumped
/// by whichever test was verifying at the same moment and the assertion would be flaky — reading as a bug
/// in `receive` rather than in the counter. Each test runs on its own thread and `receive` is synchronous,
/// so a per-thread count is exact.
#[cfg(test)]
pub(crate) mod verify_count {
    use std::cell::Cell;

    thread_local! {
        static COUNT: Cell<usize> = const { Cell::new(0) };
    }

    pub fn bump() {
        COUNT.with(|count| count.set(count.get() + 1));
    }

    /// The count since the last [`reset`], on this thread.
    pub fn taken() -> usize {
        COUNT.with(|count| count.get())
    }

    pub fn reset() {
        COUNT.with(|count| count.set(0));
    }
}

/// Every device this one is paired with.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Peers {
    devices: BTreeMap<DeviceId, Peer>,
}

impl Peers {
    /// Accept an invite the user has confirmed by reading the phrase.
    ///
    /// This is called *after* the human said the phrases matched. Nothing here can check that for
    /// them — that is the whole design — so the caller must not reach this point on its own.
    pub fn accept(
        &mut self,
        me: &Identity,
        invite: &Invite,
        now: Timestamp,
    ) -> Result<DeviceId, Error> {
        invite.check(now)?;
        let id = invite.from.id()?;
        if id == me.id() {
            return Err(Error::Itself);
        }
        // Re-pairing a revoked device is how a device comes back. It is deliberately not automatic:
        // it takes a new invite, read on both sides, which is the same ceremony as the first time.
        self.devices.insert(
            id.clone(),
            Peer { identity: invite.from.clone(), paired_at: now, revoked_at: None },
        );
        Ok(id)
    }

    /// Remove a device. Local, immediate, and needing nothing from the device being removed —
    /// a phone that has been lost cannot be asked to agree to its own removal.
    pub fn revoke(&mut self, id: &DeviceId, now: Timestamp) -> Result<(), Error> {
        let peer = self.devices.get_mut(id).ok_or(Error::Unknown)?;
        // Never moved later: the earliest removal is the one that counts, so a replayed revocation
        // cannot be used to un-remove anything.
        peer.revoked_at = Some(match peer.revoked_at {
            Some(existing) => existing.min(now),
            None => now,
        });
        Ok(())
    }

    /// The peer to trust for a message arriving now, or why not.
    pub fn active(&self, id: &DeviceId) -> Result<&Peer, Error> {
        match self.devices.get(id) {
            None => Err(Error::Unknown),
            Some(peer) => match peer.revoked_at {
                Some(at) => Err(Error::Revoked { at }),
                None => Ok(peer),
            },
        }
    }

    /// Check a signature, and that the signer is still someone this device listens to.
    pub fn verify(&self, id: &DeviceId, message: &[u8], signature: &[u8; 64]) -> Result<(), Error> {
        // Counted under `cfg(test)` only. P2-18 is a claim about *how many* of these a batch costs, and
        // that number is the only observable difference between a linear `receive` and a quadratic one —
        // so a test that does not count cannot tell the fix from the bug.
        #[cfg(test)]
        crate::pair::verify_count::bump();
        Ok(self.active(id)?.identity.verify(message, signature)?)
    }

    pub fn get(&self, id: &DeviceId) -> Option<&Peer> {
        self.devices.get(id)
    }

    pub fn active_ids(&self) -> impl Iterator<Item = &DeviceId> {
        self.devices.iter().filter(|(_, peer)| peer.is_active()).map(|(id, _)| id)
    }

    pub fn all(&self) -> impl Iterator<Item = (&DeviceId, &Peer)> {
        self.devices.iter()
    }

    pub fn is_empty(&self) -> bool {
        self.devices.values().all(|peer| !peer.is_active())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: Timestamp = 1_788_510_600;
    const NONCE: [u8; 16] = [7; 16];

    fn pair_up() -> (Identity, Identity, Peers, Peers) {
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
        on_phone.accept(&phone, &Invite::new(&pc, NOW, NONCE), NOW).unwrap();
        on_pc.accept(&pc, &Invite::new(&phone, NOW, NONCE), NOW).unwrap();
        (phone, pc, on_phone, on_pc)
    }

    #[test]
    fn both_devices_show_the_same_phrase() {
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));

        // Neither side knows which of them is "first", so the phrase must not depend on it.
        assert_eq!(
            phrase(&phone.public(), &pc.public(), &NONCE),
            phrase(&pc.public(), &phone.public(), &NONCE)
        );
    }

    #[test]
    fn a_machine_in_the_middle_changes_the_phrase() {
        // This is the only thing the phrase is for: an attacker who relays the invite while
        // substituting their own keys makes the two screens disagree, and the user stops.
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let attacker = Identity::generate("attacker");

        let honest = phrase(&phone.public(), &pc.public(), &NONCE);
        assert_ne!(honest, phrase(&phone.public(), &attacker.public(), &NONCE));
        assert_ne!(honest, phrase(&attacker.public(), &pc.public(), &NONCE));

        let mut swapped = pc.public();
        swapped.exchange = attacker.public().exchange;
        assert_ne!(honest, phrase(&phone.public(), &swapped, &NONCE));
    }

    #[test]
    fn the_same_two_devices_get_a_different_phrase_every_time() {
        // A phrase that never changed could be observed once and replayed at the next pairing.
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        assert_ne!(
            phrase(&phone.public(), &pc.public(), &[1; 16]),
            phrase(&phone.public(), &pc.public(), &[2; 16])
        );
    }

    #[test]
    fn a_phrase_is_short_enough_to_read_aloud() {
        let text =
            phrase(&Identity::generate("a").public(), &Identity::generate("b").public(), &NONCE);
        assert_eq!(text.len(), 7, "six digits and a space: {text}");
        assert!(text.chars().all(|c| c.is_ascii_digit() || c == ' '), "{text}");
    }

    #[test]
    fn pairing_makes_each_side_trust_the_other_and_nobody_else() {
        let (phone, pc, on_phone, on_pc) = pair_up();
        let message = b"start deep-work";

        on_phone.verify(&pc.id(), message, &pc.sign(message)).expect("the pc was not trusted");
        on_pc
            .verify(&phone.id(), message, &phone.sign(message))
            .expect("the phone was not trusted");

        let stranger = Identity::generate("stranger");
        assert_eq!(
            on_phone.verify(&stranger.id(), message, &stranger.sign(message)),
            Err(Error::Unknown),
            "an unpaired device was listened to"
        );
    }

    #[test]
    fn a_paired_device_cannot_sign_for_another() {
        let (phone, pc, on_phone, _) = pair_up();
        let message = b"end deep-work";

        // Signed by the phone, claimed to be from the pc.
        assert_eq!(
            on_phone.verify(&pc.id(), message, &phone.sign(message)),
            Err(Error::Device(DeviceError::BadSignature))
        );
    }

    #[test]
    fn an_expired_invite_is_refused() {
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let invite = Invite::new(&pc, NOW, NONCE);
        let mut peers = Peers::default();

        assert_eq!(peers.accept(&phone, &invite, NOW + INVITE_SECONDS), Err(Error::Expired));
        assert_eq!(peers.accept(&phone, &invite, NOW - 1), Err(Error::NotYetValid));
        peers.accept(&phone, &invite, NOW + INVITE_SECONDS - 1).expect("a live invite was refused");
    }

    #[test]
    fn a_device_cannot_pair_with_itself() {
        // Not a hypothetical: scanning your own screen in a mirror, or restoring a backup onto the
        // same machine, would otherwise create a peer that echoes everything it is told.
        let phone = Identity::generate("phone");
        let mut peers = Peers::default();
        assert_eq!(peers.accept(&phone, &Invite::new(&phone, NOW, NONCE), NOW), Err(Error::Itself));
    }

    #[test]
    fn a_revoked_device_stops_being_listened_to_and_needs_no_cooperation_to_remove() {
        // The device being removed is typically lost or stolen, so it cannot be part of removing
        // itself, and nothing else's keys may need rotating because of it.
        let (_, pc, mut on_phone, _) = pair_up();
        let message = b"end deep-work";
        let signature = pc.sign(message);

        on_phone.revoke(&pc.id(), NOW + 10).unwrap();

        assert_eq!(
            on_phone.verify(&pc.id(), message, &signature),
            Err(Error::Revoked { at: NOW + 10 }),
            "a removed device was still trusted"
        );
        assert!(on_phone.active_ids().next().is_none());
        assert!(on_phone.is_empty());
        assert!(on_phone.get(&pc.id()).is_some(), "the removal was forgotten instead of recorded");
    }

    #[test]
    fn revoking_twice_never_moves_the_removal_later() {
        let (_, pc, mut on_phone, _) = pair_up();
        on_phone.revoke(&pc.id(), NOW + 10).unwrap();
        on_phone.revoke(&pc.id(), NOW + 1000).unwrap();

        assert_eq!(on_phone.get(&pc.id()).unwrap().revoked_at, Some(NOW + 10));
    }

    #[test]
    fn revoking_a_device_that_was_never_paired_says_so() {
        let mut peers = Peers::default();
        assert_eq!(peers.revoke(&Identity::generate("x").id(), NOW), Err(Error::Unknown));
    }

    #[test]
    fn a_removed_device_can_be_paired_again_from_both_sides() {
        let (phone, pc, mut on_phone, _) = pair_up();
        on_phone.revoke(&pc.id(), NOW + 10).unwrap();

        on_phone.accept(&phone, &Invite::new(&pc, NOW + 20, [9; 16]), NOW + 20).unwrap();

        assert_eq!(on_phone.get(&pc.id()).unwrap().revoked_at, None);
        on_phone.verify(&pc.id(), b"hello", &pc.sign(b"hello")).unwrap();
    }

    #[test]
    fn peers_survive_being_written_down_and_read_back() {
        let (_, pc, mut on_phone, _) = pair_up();
        on_phone.revoke(&pc.id(), NOW + 10).unwrap();

        let text = serde_json::to_string(&on_phone).unwrap();
        let restored: Peers = serde_json::from_str(&text).unwrap();

        assert_eq!(restored, on_phone, "a restart would have changed who is trusted");
    }

    #[test]
    fn an_invite_carries_no_secret() {
        let phone = Identity::generate("phone");
        let text = serde_json::to_string(&Invite::offer(&phone, NOW)).unwrap();
        let secret = phone.secret_bytes();

        // Invites are shown on screens and photographed. If one carried key material, that would
        // be the end of the device.
        for window in secret.windows(8) {
            assert!(
                !text.contains(&format!("{window:?}")[1..]),
                "an invite contained key material: {text}"
            );
        }
    }

    #[test]
    fn two_invites_are_never_the_same() {
        let phone = Identity::generate("phone");
        assert_ne!(Invite::offer(&phone, NOW).nonce, Invite::offer(&phone, NOW).nonce);
    }

    #[test]
    fn compact_invite_roundtrips_and_derives_identical_phrase() {
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let original = Invite::offer(&phone, NOW);

        let compact = original.to_compact();
        assert!(compact.starts_with("CRFW:"), "compact code must have prefix: {compact}");
        assert!(
            compact.len() < 145,
            "compact invite is {len} chars, expected < 145",
            len = compact.len()
        );

        let json = serde_json::to_string(&original).unwrap();
        assert!(
            json.len() > 300,
            "json format was unexpectedly small: {len} chars",
            len = json.len()
        );

        // Parsing from compact gives exact same fields:
        let parsed = Invite::from_str_lenient(&compact).expect("valid compact invite");
        assert_eq!(parsed.from.signing, original.from.signing);
        assert_eq!(parsed.from.exchange, original.from.exchange);
        assert_eq!(parsed.nonce, original.nonce);
        assert_eq!(parsed.issued_at, original.issued_at);
        assert_eq!(parsed.expires_at, original.expires_at);
        assert_eq!(parsed.from.name, original.from.name);

        // Derives identical phrase:
        assert_eq!(
            phrase(&pc.public(), &parsed.from, &parsed.nonce),
            phrase(&pc.public(), &original.from, &original.nonce),
        );

        // Lenient parsing handles legacy JSON too:
        let from_json = Invite::from_str_lenient(&json).expect("parses legacy JSON");
        assert_eq!(from_json, original);

        // Lenient parsing handles lowercase prefix and surrounding whitespace:
        let with_spaces = format!("  crfw:{}  ", &compact[5..]);
        let from_spaces = Invite::from_str_lenient(&with_spaces).expect("parses lowercase prefix with whitespace");
        assert_eq!(from_spaces.from.signing, original.from.signing);

        // Lenient parsing handles deep link URLs:
        let url = format!("https://curfew.dev/pair?code={}&source=camera", compact);
        let from_url = Invite::from_str_lenient(&url).expect("parses deep link URL");
        assert_eq!(from_url.from.signing, original.from.signing);
    }

    #[test]
    fn compact_invite_rejects_malformed_input() {
        assert_eq!(Invite::from_str_lenient(""), Err(Error::Malformed));
        assert_eq!(Invite::from_str_lenient("   "), Err(Error::Malformed));
        assert_eq!(Invite::from_str_lenient("not a code"), Err(Error::Malformed));
        assert_eq!(Invite::from_str_lenient("CRFW:AQIDBA=="), Err(Error::Malformed));
    }
}
