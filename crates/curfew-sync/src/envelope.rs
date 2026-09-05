//! What the transport is allowed to see: nothing.
//!
//! LAN, a folder in someone's cloud drive, a QR code held up to a camera — every transport Curfew
//! supports is somebody else's, and one of them is explicitly a third party's server (GAPS C5). So
//! entries are signed *and* sealed before they reach any of them. Signing stops a transport from
//! forging a block or lifting one; sealing stops it from learning which apps a person is blocking,
//! which is among the more personal things this program knows.
//!
//! The key is derived from the two devices' X25519 keys and nothing else, so it needs no exchange,
//! no rotation ceremony and no storage of its own: a device that is unpaired simply stops being
//! able to derive it.

use crate::device::{Identity, PublicIdentity};
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use serde::{Deserialize, Serialize};
use sha2::{Digest as _, Sha256};

/// Bound into every ciphertext so that a sealed message from one version of the protocol cannot be
/// replayed into another that reads the bytes differently.
const CONTEXT: &[u8] = b"curfew-envelope-v1";

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum Error {
    #[error("this message was not sealed for this device, or it has been altered")]
    NotForUs,
}

/// A sealed message. The nonce is public by design; the ciphertext carries its own authentication
/// tag, so a single flipped bit anywhere makes the whole thing refuse to open.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Sealed {
    pub nonce: [u8; 12],
    pub ciphertext: Vec<u8>,
}

/// The key two paired devices share. Both derive the same 32 bytes; neither ever sends them.
fn key(me: &Identity, peer: &PublicIdentity) -> [u8; 32] {
    let mine = me.public();
    // Ordered by key rather than by role, so the two sides derive the same key without first
    // agreeing which of them is the sender.
    let (first, second) = if mine.exchange <= peer.exchange {
        (mine.exchange, peer.exchange)
    } else {
        (peer.exchange, mine.exchange)
    };
    let mut hasher = Sha256::new();
    hasher.update(CONTEXT);
    hasher.update(me.agree(peer));
    hasher.update(first);
    hasher.update(second);
    hasher.finalize().into()
}

/// Seal a message for one peer.
pub fn seal(me: &Identity, peer: &PublicIdentity, plaintext: &[u8]) -> Sealed {
    use rand_core::RngCore as _;
    let mut nonce = [0u8; 12];
    rand_core::OsRng.fill_bytes(&mut nonce);
    seal_with(me, peer, plaintext, nonce)
}

/// Seal with a caller-chosen nonce. Public only so the tests can pin one; ordinary callers want
/// [`seal`], because a nonce reused with the same key is the one mistake this construction does
/// not survive.
pub fn seal_with(
    me: &Identity,
    peer: &PublicIdentity,
    plaintext: &[u8],
    nonce: [u8; 12],
) -> Sealed {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&key(me, peer)));
    let ciphertext = cipher
        .encrypt(Nonce::from_slice(&nonce), Payload { msg: plaintext, aad: CONTEXT })
        .expect("sealing cannot fail for a well-formed key and nonce");
    Sealed { nonce, ciphertext }
}

/// Open a message from one peer.
pub fn open(me: &Identity, peer: &PublicIdentity, sealed: &Sealed) -> Result<Vec<u8>, Error> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&key(me, peer)));
    cipher
        .decrypt(
            Nonce::from_slice(&sealed.nonce),
            Payload { msg: &sealed.ciphertext, aad: CONTEXT },
        )
        .map_err(|_| Error::NotForUs)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn devices() -> (Identity, Identity) {
        (Identity::generate("phone"), Identity::generate("pc"))
    }

    #[test]
    fn what_one_device_seals_the_other_opens() {
        let (phone, pc) = devices();
        let message = br#"{"op":"start","profile":"deep-work"}"#;

        let sealed = seal(&phone, &pc.public(), message);
        assert_eq!(open(&pc, &phone.public(), &sealed).unwrap(), message);
    }

    #[test]
    fn nobody_else_can_open_it() {
        // The transport is the third party here: a shared folder is somebody's cloud account, and
        // what is blocked is nobody's business but the user's.
        let (phone, pc) = devices();
        let eavesdropper = Identity::generate("cloud");
        let sealed = seal(&phone, &pc.public(), b"blocking instagram");

        assert_eq!(open(&eavesdropper, &phone.public(), &sealed), Err(Error::NotForUs));
        assert_eq!(open(&pc, &eavesdropper.public(), &sealed), Err(Error::NotForUs));
    }

    #[test]
    fn a_single_altered_byte_makes_it_refuse_to_open() {
        let (phone, pc) = devices();
        let mut sealed = seal(&phone, &pc.public(), b"start deep-work until 17:00");
        sealed.ciphertext[3] ^= 1;

        assert_eq!(open(&pc, &phone.public(), &sealed), Err(Error::NotForUs));
    }

    #[test]
    fn a_replaced_nonce_makes_it_refuse_to_open() {
        let (phone, pc) = devices();
        let mut sealed = seal(&phone, &pc.public(), b"start deep-work");
        sealed.nonce[0] ^= 0xff;

        assert_eq!(open(&pc, &phone.public(), &sealed), Err(Error::NotForUs));
    }

    #[test]
    fn the_plaintext_is_nowhere_in_the_ciphertext() {
        let (phone, pc) = devices();
        let secret = b"instagram";
        let sealed = seal(&phone, &pc.public(), secret);

        assert!(
            !sealed.ciphertext.windows(secret.len()).any(|w| w == secret),
            "the message travelled in the clear"
        );
    }

    #[test]
    fn sealing_the_same_thing_twice_does_not_look_the_same_twice() {
        // Otherwise a watcher of a shared folder learns "they just did the same thing again",
        // which over a day is most of the schedule.
        let (phone, pc) = devices();
        let a = seal(&phone, &pc.public(), b"start deep-work");
        let b = seal(&phone, &pc.public(), b"start deep-work");

        assert_ne!(a.nonce, b.nonce);
        assert_ne!(a.ciphertext, b.ciphertext);
    }

    #[test]
    fn an_empty_message_is_still_sealed_rather_than_passed_through() {
        let (phone, pc) = devices();
        let sealed = seal(&phone, &pc.public(), b"");

        assert!(!sealed.ciphertext.is_empty(), "an empty message came back unauthenticated");
        assert_eq!(open(&pc, &phone.public(), &sealed).unwrap(), b"");
    }

    #[test]
    fn a_sealed_message_survives_being_written_to_a_shared_folder_and_read_back() {
        let (phone, pc) = devices();
        let sealed = seal(&phone, &pc.public(), b"start deep-work");

        let text = serde_json::to_string(&sealed).unwrap();
        let restored: Sealed = serde_json::from_str(&text).unwrap();

        assert_eq!(open(&pc, &phone.public(), &restored).unwrap(), b"start deep-work");
    }
}
