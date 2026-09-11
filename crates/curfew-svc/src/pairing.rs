//! The real [`curfew_win::pairing::Pairing`], over `curfew-sync` — F-18, step 2.
//!
//! Everything difficult — the invite format, the nonce, the phrase, the peers file, the signature — is in
//! `curfew-sync` and tested there. This is the adapter, and it is deliberately thin: every method is one
//! call and a `map_err`, because an adapter that decided anything would be a second place for the protocol
//! to be wrong.
//!
//! The one rule it does carry is that **nothing here compares the phrase**. `phrase_for` computes the six
//! digits and the two people reading them decide; a service that checked would remove the only step a
//! machine in the middle cannot forge.

use curfew_core::Timestamp;
use curfew_sync::device::DeviceId;
use curfew_sync::node::Shared;
use curfew_sync::pair::{self, Invite};
use std::sync::Arc;

/// Pairing over one device's sync state.
pub struct Pairing {
    shared: Shared,
    root: std::path::PathBuf,
}

impl Pairing {
    pub fn new(shared: Shared, root: std::path::PathBuf) -> Arc<Self> {
        Arc::new(Self { shared, root })
    }

    /// Write the peers file. Called after anything that changes it, so a pairing survives a restart —
    /// without this the pairing would work until the service stopped and then be gone.
    fn save(&self) -> Result<(), String> {
        curfew_sync::store::save(&self.root, &self.shared).map_err(|e| e.to_string())
    }
}

impl curfew_win::pairing::Pairing for Pairing {
    fn device_id(&self) -> String {
        // **`id()`, not `fingerprint()`** — the id peers store and `revoke` accepts. See the trait's doc.
        self.shared.identity.id().to_string()
    }

    fn fingerprint(&self) -> String {
        self.shared.identity.public().fingerprint()
    }

    fn peers_json(&self) -> Result<String, String> {
        let peers = self.shared.peers.lock().expect("the peers lock is never poisoned");
        serde_json::to_string(&*peers).map_err(|e| e.to_string())
    }

    fn invite_json(&self, now: Timestamp) -> Result<String, String> {
        serde_json::to_string(&Invite::offer(&self.shared.identity, now)).map_err(|e| e.to_string())
    }

    fn phrase_for(&self, invite_json: &str) -> Result<String, String> {
        let invite: Invite = serde_json::from_str(invite_json).map_err(|e| e.to_string())?;
        Ok(pair::phrase(&self.shared.identity.public(), &invite.from, &invite.nonce))
    }

    fn reply_to(&self, invite_json: &str) -> Result<String, String> {
        let invite: Invite = serde_json::from_str(invite_json).map_err(|e| e.to_string())?;
        // The same nonce, this device's keys: that is what lets the offering side derive the same phrase
        // once it has read this reply. Built field by field rather than with a constructor, because there
        // is no `answer` — the FFI does exactly this, and `Invite::new` would mint a fresh nonce and
        // break the phrase, which is the one thing this reply exists to carry.
        let reply = Invite {
            from: self.shared.identity.public(),
            nonce: invite.nonce,
            issued_at: invite.issued_at,
            expires_at: invite.expires_at,
        };
        serde_json::to_string(&reply).map_err(|e| e.to_string())
    }

    fn accept_invite(&self, invite_json: &str, now: Timestamp) -> Result<String, String> {
        let invite: Invite = serde_json::from_str(invite_json).map_err(|e| e.to_string())?;
        let id = {
            let mut peers = self.shared.peers.lock().expect("the peers lock is never poisoned");
            peers.accept(&self.shared.identity, &invite, now).map_err(|e| e.to_string())?
        };
        self.save()?;
        Ok(id.to_string())
    }

    fn revoke(&self, device_id: &str, now: Timestamp) -> Result<(), String> {
        let id: DeviceId =
            device_id.parse().map_err(|e: curfew_sync::device::Error| e.to_string())?;
        {
            let mut peers = self.shared.peers.lock().expect("the peers lock is never poisoned");
            peers.revoke(&id, now).map_err(|e| e.to_string())?;
        }
        self.save()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_win::pairing::Pairing as _;

    /// A scratch directory of its own, so parallel tests do not fight over one identity.
    fn adapter(name: &str) -> (Arc<Pairing>, std::path::PathBuf) {
        let dir =
            std::env::temp_dir().join(format!("curfew-adapter-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let (shared, _complaints) =
            curfew_sync::store::open(&dir, "this pc").expect("a fresh store");
        (Pairing::new(shared, dir.clone()), dir)
    }

    const NOW: Timestamp = 1_788_510_600;

    /// **The whole exchange through the adapter**, which is what a Devices page would drive.
    #[test]
    fn two_adapters_complete_the_exchange() {
        let (pc, pc_dir) = adapter("pc");
        let (phone, phone_dir) = adapter("phone");

        // The PC offers; each side phrases the message it **received**, which is what the two people
        // then read out to each other.
        let offer = pc.invite_json(NOW).expect("an offer");
        let on_phone = phone.phrase_for(&offer).expect("the phone phrases the offer it was given");
        let reply = phone.reply_to(&offer).expect("the phone answers");
        let on_pc = pc.phrase_for(&reply).expect("the pc phrases the reply it was given");

        assert_eq!(
            on_pc, on_phone,
            "the two sides disagree about the phrase, so the comparison step cannot pass"
        );

        // And phrasing the message a device authored itself gives a *different* answer, because both
        // keys collapse to its own. Pinned so nobody "simplifies" the page into calling `phrase_for`
        // with its own invite.
        assert_ne!(
            pc.phrase_for(&offer).unwrap(),
            on_pc,
            "phrasing its own offer happened to agree, so the fixture no longer proves the direction"
        );

        // Each accepts the other's message.
        phone.accept_invite(&offer, NOW).expect("the phone records the pc");
        let recorded = pc.accept_invite(&reply, NOW).expect("the pc records the phone");

        // **Each side holds the other**, which is what paired means. A device is not its own peer, so
        // looking for a device's own id in its own list is not a check that can pass — which is what the
        // first version of this fixture did.
        let pc_peers = pc.peers_json().expect("peers as json");
        let phone_peers = phone.peers_json().expect("peers as json");
        assert_eq!(pc_peers.matches("\"paired_at\"").count(), 1, "the pc holds {pc_peers}");
        assert_eq!(
            phone_peers.matches("\"paired_at\"").count(),
            1,
            "the phone holds {phone_peers}"
        );
        assert!(pc_peers.contains(&recorded), "the pc does not hold the phone it accepted");
        assert!(
            phone_peers.contains(&pc.device_id()),
            "the phone does not hold the pc whose offer it accepted"
        );

        let _ = std::fs::remove_dir_all(&pc_dir);
        let _ = std::fs::remove_dir_all(&phone_dir);
    }

    /// **`reply_to` must reuse the invite's nonce**, and this is the mutation that survived the
    /// integration test: a fresh nonce makes the two phrases differ and the pairing unfinishable.
    #[test]
    fn the_reply_reuses_the_invites_nonce() {
        let (pc, pc_dir) = adapter("nonce-pc");
        let (phone, phone_dir) = adapter("nonce-phone");

        let offer = pc.invite_json(NOW).expect("an offer");
        let reply = phone.reply_to(&offer).expect("a reply");

        let parsed_offer: Invite = serde_json::from_str(&offer).unwrap();
        let parsed_reply: Invite = serde_json::from_str(&reply).unwrap();
        assert_eq!(
            parsed_reply.nonce, parsed_offer.nonce,
            "the reply minted its own nonce, so the two phrases can never match"
        );
        // And it is answered with *this* device's keys, not the offerer's.
        assert_eq!(parsed_reply.from, phone_public(&phone));

        let _ = std::fs::remove_dir_all(&pc_dir);
        let _ = std::fs::remove_dir_all(&phone_dir);
    }

    /// `revoke` removes the peer locally, and `peers_json` reflects it.
    #[test]
    fn revoke_removes_the_peer() {
        let (pc, pc_dir) = adapter("revoke-pc");
        let (phone, phone_dir) = adapter("revoke-phone");

        let offer = pc.invite_json(NOW).unwrap();
        let reply = phone.reply_to(&offer).unwrap();
        let id = pc.accept_invite(&reply, NOW).expect("paired");
        assert!(pc.peers_json().unwrap().contains(&id));

        pc.revoke(&id, NOW + 1).expect("revoking is local");
        assert!(
            !pc.peers_json().unwrap().contains(&format!("\"id\":\"{id}\"")),
            "the peer survived its revocation"
        );

        let _ = std::fs::remove_dir_all(&pc_dir);
        let _ = std::fs::remove_dir_all(&phone_dir);
    }

    /// **`device_id` is the id peers use, and `fingerprint` is the display string.** They are different
    /// on purpose, and a page that conflated them would show a list it could not revoke from.
    #[test]
    fn the_device_id_is_the_one_peers_store() {
        let (pc, pc_dir) = adapter("id");
        let id = pc.device_id();
        let fingerprint = pc.fingerprint();

        assert!(!id.is_empty(), "a device has no id");
        assert!(!fingerprint.is_empty(), "a device has no fingerprint");
        assert_ne!(
            id, fingerprint,
            "the two identifiers are the same string, so one of them is wrong"
        );
        assert_eq!(pc.device_id(), id, "the id is not stable across calls");
        // And the id is the one a peer records: pairing with it puts exactly this string in the map.
        assert_eq!(id, pc.shared.identity.id().to_string());

        let _ = std::fs::remove_dir_all(&pc_dir);
    }

    fn phone_public(pairing: &Arc<Pairing>) -> curfew_sync::device::PublicIdentity {
        pairing.shared.identity.public()
    }
}
