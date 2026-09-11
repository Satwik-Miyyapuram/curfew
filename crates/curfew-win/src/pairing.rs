//! **What the Windows side needs from pairing, declared without the sync crate** — F-18, step 2.
//!
//! The request handler lives in `curfew_win::Enforcer::handle`, and `curfew-win` does not depend on
//! `curfew-sync` — correctly: the enforcement layer is the platform floor and the sync crate is a heavier
//! thing built on the core. So the handler cannot touch `Shared` directly.
//!
//! The answer is the same one this crate already uses for calendars: **declare what is needed as a trait,
//! and let the binary supply the implementation.** `curfew_win::calendar::Fetch` is the precedent — the
//! policy is tested against a scripted fetcher and `curfew-svc` provides the real one — and this is the
//! same shape for pairing.
//!
//! **Everything crosses as JSON.** A trait signature naming `curfew_sync::pair::Invite` would put the
//! dependency back, and the FFI already established JSON as the boundary type for exactly this reason. It
//! also means the trait is honest about what it is: a wire, not an API.
//!
//! ### The comparison step is not the service's to make
//!
//! `phrase_for` **computes** the six digits; nothing here compares them. Both devices show their phrase
//! and the human confirms they match before either `accept_invite` is called — that is the entire point of
//! the phrase, and a service that decided it for them would remove the only check a machine in the middle
//! cannot forge. `accept_invite`'s doc comment says so at the call site too.

use curfew_core::Timestamp;

/// Pairing, as the service needs it: identity, peers, and the four steps of the exchange.
///
/// Every method returns the same shape the FFI does, so the Android and Windows front doors are describing
/// one protocol rather than two.
pub trait Pairing: Send + Sync {
    /// **This device's id, as it appears to peers** — the key in the peers map, and what `revoke` takes.
    ///
    /// **Not the fingerprint.** There are two identifiers and they are not interchangeable:
    /// `DeviceId` is derived from the signing key alone and is what the protocol stores, while the
    /// fingerprint covers both keys and exists so a user can compare two screens. The first version of
    /// this trait returned the fingerprint here, which would have given a Devices page a list it could not
    /// act on — `revoke` would not accept its own display string. Found by the adapter test, not by
    /// reading.
    fn device_id(&self) -> String;

    /// The fingerprint to show **beside** the id, for a user comparing two screens.
    ///
    /// Covers both keys, so an attacker who substituted the exchange key while leaving the signing key
    /// alone is still caught by the comparison.
    fn fingerprint(&self) -> String;

    /// Every device this one has paired with, revoked ones included, as JSON.
    fn peers_json(&self) -> Result<String, String>;

    /// Offer to pair. The result is JSON to put in a code the other device reads; it is not secret and
    /// may be photographed, but it cannot authenticate itself — which is what the phrase is for.
    fn invite_json(&self, now: Timestamp) -> Result<String, String>;

    /// The six digits both devices must agree on before either accepts.
    ///
    /// Derived from both public keys and the invite's nonce, so a device in the middle that swapped a key
    /// produces a different phrase and is caught by the reading.
    fn phrase_for(&self, invite_json: &str) -> Result<String, String>;

    /// Answer an invite with this device's own keys, reusing the invite's nonce.
    ///
    /// The phrase covers both devices' keys and one nonce, so the device that *offered* cannot derive it
    /// until it has seen the other device's keys. That is what this is for.
    fn reply_to(&self, invite_json: &str) -> Result<String, String>;

    /// **Accept, and only after the user has confirmed the phrases match.** Nothing here can check that
    /// for them, and that is the design rather than a shortcoming.
    fn accept_invite(&self, invite_json: &str, now: Timestamp) -> Result<String, String>;

    /// Remove a device. Immediate and local: a phone that has been lost cannot be asked to agree to its
    /// own removal.
    fn revoke(&self, device_id: &str, now: Timestamp) -> Result<(), String>;
}
