//! Who a device is, in a system with nobody to ask.
//!
//! With no server there is no account to be the root of trust, so a device *is* its keys: an
//! Ed25519 pair it signs with and an X25519 pair it agrees keys with. Everything else — the id in
//! the UI, the fingerprint read aloud during pairing, the signature on every operation — is derived
//! from those two, so there is exactly one secret to protect and losing it means the device is a
//! new device rather than a compromised one (GAPS C2).
//!
//! The signing and exchange keys are deliberately separate. Reusing one key for both is a known way
//! to lose the security proofs of either, and the cost of a second 32-byte key is nothing.

use ed25519_dalek::{Signature, Signer as _, SigningKey, Verifier as _, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest as _, Sha256};
use x25519_dalek::{PublicKey as ExchangePublic, StaticSecret};

/// Crockford's base32: no I, L, O or U, so nothing in a device id can be misread aloud or
/// mistyped, and nothing spells a word by accident.
const ALPHABET: &[u8] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/// How many bytes of the key hash an id carries. 16 bytes is 128 bits: far past anything a
/// collision could be engineered into, and short enough to print on a screen.
const ID_BYTES: usize = 16;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum Error {
    #[error("this device's key material is {0} bytes, and a key is 64")]
    KeyLength(usize),
    #[error("that is not a valid public key")]
    BadKey,
    #[error("the signature does not match")]
    BadSignature,
}

/// A device's public name in the protocol: the hash of its signing key, in base32.
///
/// Ids are compared, never parsed for meaning. Two devices with the same id have the same signing
/// key, which is the only claim the protocol ever needs to make.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct DeviceId(String);

impl DeviceId {
    fn of(signing: &VerifyingKey) -> Self {
        let digest = Sha256::digest(signing.as_bytes());
        Self(base32(&digest[..ID_BYTES]))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for DeviceId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

fn base32(bytes: &[u8]) -> String {
    let mut out = String::new();
    let (mut acc, mut bits) = (0u32, 0u32);
    for byte in bytes {
        acc = (acc << 8) | u32::from(*byte);
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(ALPHABET[((acc >> bits) & 0x1f) as usize] as char);
        }
    }
    if bits > 0 {
        out.push(ALPHABET[((acc << (5 - bits)) & 0x1f) as usize] as char);
    }
    out
}

/// Everything about a device that may be given away.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PublicIdentity {
    /// What the user called this device. Advisory only — it is not authenticated by anything, and
    /// nothing may be decided from it.
    pub name: String,
    pub signing: [u8; 32],
    pub exchange: [u8; 32],
}

impl PublicIdentity {
    pub fn id(&self) -> Result<DeviceId, Error> {
        Ok(DeviceId::of(&self.verifying()?))
    }

    fn verifying(&self) -> Result<VerifyingKey, Error> {
        VerifyingKey::from_bytes(&self.signing).map_err(|_| Error::BadKey)
    }

    /// Check a signature this device is claimed to have made.
    pub fn verify(&self, message: &[u8], signature: &[u8; 64]) -> Result<(), Error> {
        self.verifying()?
            .verify(message, &Signature::from_bytes(signature))
            .map_err(|_| Error::BadSignature)
    }

    /// The fingerprint two people read to each other while pairing.
    ///
    /// It covers both keys, not just the signing one, so an attacker who could substitute the
    /// exchange key while leaving the signing key alone would still be caught by the reading.
    pub fn fingerprint(&self) -> String {
        let mut hasher = Sha256::new();
        hasher.update(b"curfew-fingerprint-v1");
        hasher.update(self.signing);
        hasher.update(self.exchange);
        let digest = hasher.finalize();
        let text = base32(&digest[..10]);
        text.as_bytes()
            .chunks(4)
            .map(|chunk| std::str::from_utf8(chunk).unwrap_or_default())
            .collect::<Vec<_>>()
            .join("-")
    }
}

/// This device, keys and all. Never leaves the machine and is never serialized whole — only
/// [`Identity::secret_bytes`] is written to disk, and only where the platform already protects it.
pub struct Identity {
    name: String,
    signing: SigningKey,
    exchange: StaticSecret,
}

impl std::fmt::Debug for Identity {
    /// Written by hand so that no accident — a log line, a panic message, a `dbg!` left in — can
    /// ever print the private half.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Identity")
            .field("name", &self.name)
            .field("id", &self.id())
            .finish_non_exhaustive()
    }
}

impl Identity {
    /// A brand new device, from the operating system's randomness.
    pub fn generate(name: impl Into<String>) -> Self {
        let mut rng = rand_core::OsRng;
        Self {
            name: name.into(),
            signing: SigningKey::generate(&mut rng),
            exchange: StaticSecret::random_from_rng(rng),
        }
    }

    /// Restore the device from what was stored: the two 32-byte secrets, signing first.
    pub fn from_secret_bytes(name: impl Into<String>, bytes: &[u8]) -> Result<Self, Error> {
        let bytes: &[u8; 64] = bytes.try_into().map_err(|_| Error::KeyLength(bytes.len()))?;
        let (signing, exchange): ([u8; 32], [u8; 32]) =
            (bytes[..32].try_into().unwrap(), bytes[32..].try_into().unwrap());
        Ok(Self {
            name: name.into(),
            signing: SigningKey::from_bytes(&signing),
            exchange: StaticSecret::from(exchange),
        })
    }

    /// The bytes to store. Whoever holds these *is* this device, so they belong wherever the
    /// platform keeps secrets and nowhere else.
    pub fn secret_bytes(&self) -> [u8; 64] {
        let mut out = [0u8; 64];
        out[..32].copy_from_slice(&self.signing.to_bytes());
        out[32..].copy_from_slice(&self.exchange.to_bytes());
        out
    }

    pub fn public(&self) -> PublicIdentity {
        PublicIdentity {
            name: self.name.clone(),
            signing: self.signing.verifying_key().to_bytes(),
            exchange: ExchangePublic::from(&self.exchange).to_bytes(),
        }
    }

    pub fn id(&self) -> DeviceId {
        DeviceId::of(&self.signing.verifying_key())
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn sign(&self, message: &[u8]) -> [u8; 64] {
        self.signing.sign(message).to_bytes()
    }

    /// The shared secret with another device. Both sides compute the same 32 bytes and neither
    /// sends it anywhere; it is the input to the key that encrypts the op-log between them.
    pub fn agree(&self, other: &PublicIdentity) -> [u8; 32] {
        self.exchange.diffie_hellman(&ExchangePublic::from(other.exchange)).to_bytes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_device_that_is_restored_is_the_same_device() {
        // Losing identity across a restart would mean every reboot looks like a new device to
        // every peer, and the pairing list would grow forever.
        let original = Identity::generate("desk");
        let restored = Identity::from_secret_bytes("desk", &original.secret_bytes()).unwrap();

        assert_eq!(original.id(), restored.id());
        assert_eq!(original.public(), restored.public());
    }

    #[test]
    fn two_devices_are_never_the_same_device() {
        assert_ne!(Identity::generate("a").id(), Identity::generate("b").id());
    }

    #[test]
    fn a_name_is_not_part_of_who_a_device_is() {
        // Renaming a laptop must not unpair it.
        let identity = Identity::generate("laptop");
        let renamed = Identity::from_secret_bytes("work laptop", &identity.secret_bytes()).unwrap();
        assert_eq!(identity.id(), renamed.id());
    }

    #[test]
    fn key_material_of_the_wrong_length_is_refused_rather_than_padded() {
        assert_eq!(Identity::from_secret_bytes("x", &[0u8; 32]).unwrap_err(), Error::KeyLength(32));
        assert_eq!(Identity::from_secret_bytes("x", &[]).unwrap_err(), Error::KeyLength(0));
    }

    #[test]
    fn a_signature_proves_the_device_and_the_message_both() {
        let phone = Identity::generate("phone");
        let pc = Identity::generate("pc");
        let message = b"start deep-work until 17:00";
        let signature = phone.sign(message);

        phone.public().verify(message, &signature).expect("a device cannot verify itself");
        assert_eq!(
            pc.public().verify(message, &signature),
            Err(Error::BadSignature),
            "one device's signature passed as another's"
        );

        let mut tampered = *message;
        tampered[0] = b's' + 1;
        assert_eq!(phone.public().verify(&tampered, &signature), Err(Error::BadSignature));
    }

    #[test]
    fn a_forged_public_key_is_refused_rather_than_trusted() {
        let mut broken = Identity::generate("phone").public();
        // Not every 32 bytes are a point on the curve; this one is not, and a library that
        // accepted it would be verifying signatures against nothing.
        broken.signing = [0u8; 32];
        broken.signing[0] = 2;
        assert_eq!(broken.id().unwrap_err(), Error::BadKey);
        assert_eq!(broken.verify(b"anything", &[0u8; 64]), Err(Error::BadKey));
    }

    #[test]
    fn both_sides_of_a_pair_agree_on_the_same_secret() {
        let phone = Identity::generate("phone");
        let pc = Identity::generate("pc");

        assert_eq!(phone.agree(&pc.public()), pc.agree(&phone.public()));
        assert_ne!(phone.agree(&pc.public()), phone.agree(&Identity::generate("x").public()));
    }

    #[test]
    fn an_id_is_readable_aloud_without_ambiguity() {
        let id = Identity::generate("phone").id();
        let text = id.as_str();

        assert_eq!(text.len(), 26, "128 bits in base32 is 26 characters: {text}");
        assert!(
            text.chars().all(|c| ALPHABET.contains(&(c as u8))),
            "an id contained a character that can be misread: {text}"
        );
    }

    #[test]
    fn a_fingerprint_changes_if_either_key_is_swapped() {
        // The whole point of reading it aloud is to catch a machine in the middle that supplied
        // one of its own keys, so a fingerprint that covered only the signing key would be
        // theatre.
        let phone = Identity::generate("phone");
        let mut swapped = phone.public();
        swapped.exchange = Identity::generate("attacker").public().exchange;

        assert_ne!(phone.public().fingerprint(), swapped.fingerprint());
        assert_eq!(phone.public().fingerprint(), phone.public().fingerprint());
        assert_eq!(phone.public().fingerprint().len(), 19, "four groups of four and three dashes");
    }

    #[test]
    fn the_private_half_is_not_printable_by_accident() {
        let identity = Identity::generate("phone");
        let printed = format!("{identity:?}");
        let secret = identity.secret_bytes();

        assert!(printed.contains(identity.id().as_str()));
        assert!(
            !printed.contains(&base32(&secret[..8]))
                && !printed.contains(&format!("{:?}", &secret[..8])),
            "the debug output leaked key material: {printed}"
        );
    }
}
