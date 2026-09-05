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
    #[error(transparent)]
    Device(#[from] DeviceError),
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
}
