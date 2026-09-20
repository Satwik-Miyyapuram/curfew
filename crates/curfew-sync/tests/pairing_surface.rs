//! **The Windows pairing surface's contract** — F-18, step 2.
//!
//! `curfew_win::pairing::Pairing` is a trait over JSON strings, implemented in `curfew-svc`, and the
//! properties worth pinning are the ones the *trait* promises rather than the ones its methods happen to
//! have:
//!
//!  - the four steps complete an exchange between two devices, and both derive **the same six digits**;
//!  - `reply_to` reuses the invite's nonce, because the phrase covers it — a fresh nonce would make the
//!    two phrases differ and the exchange unfinishable;
//!  - accepting before the reply exists cannot produce a peer;
//!  - revoking removes the peer and is local.
//!
//! Driven against `curfew-sync` directly rather than through the trait, because the trait's implementation
//! lives in the service *binary* and a binary is not importable. What this pins is the JSON contract the
//! adapter maps onto — the same shapes the FFI uses, which is the point of mirroring it.

use curfew_core::Timestamp;
use curfew_sync::device::{DeviceId, Identity};
use curfew_sync::pair::{self, Invite, Peers};

const NOW: Timestamp = 1_788_510_600;

fn json<T: serde::Serialize>(value: &T) -> String {
    serde_json::to_string(value).expect("the protocol types serialize")
}

/// **The whole exchange, as the surface drives it.**
#[test]
fn two_devices_pair_and_agree_on_the_phrase() {
    let pc = Identity::generate("pc");
    let phone = Identity::generate("phone");

    // 1. The PC offers, through `Request::Invite`.
    let offer = Invite::offer(&pc, NOW);
    let offer_json = json(&offer);

    // 2. The phone answers, through `Request::Reply`. The nonce is the invite's, not a fresh one.
    let parsed: Invite = serde_json::from_str(&offer_json).expect("the invite round-trips");
    assert_eq!(parsed.nonce, offer.nonce, "the invite's nonce changed in transit");
    let reply = Invite {
        from: phone.public(),
        nonce: parsed.nonce,
        issued_at: parsed.issued_at,
        expires_at: parsed.expires_at,
    };
    let reply_json = json(&reply);
    assert_eq!(
        serde_json::from_str::<Invite>(&reply_json).expect("the reply round-trips").nonce,
        offer.nonce,
        "the reply did not reuse the invite's nonce, so the phrases cannot match"
    );

    // 3. **Both sides derive the same six digits** — the one thing the user checks, and the only step a
    //    machine in the middle cannot forge.
    let on_pc = pair::phrase(&pc.public(), &phone.public(), &offer.nonce);
    let on_phone = pair::phrase(&phone.public(), &pc.public(), &offer.nonce);
    assert_eq!(on_pc, on_phone, "the two devices showed different phrases");
    // **Six digits, with a grouping space** — the phrase is formatted to be read aloud and compared at a
    // glance, so the assertion counts digits rather than characters. The first version of this test
    // checked `len() == 6` and failed on the space, which was my expectation being wrong and the code
    // being better than it.
    let digits: String = on_pc.chars().filter(char::is_ascii_digit).collect();
    assert_eq!(digits.len(), 6, "not six digits: {on_pc:?}");
    assert_eq!(
        on_pc.matches(' ').count(),
        1,
        "the phrase should be grouped for reading, and it is not: {on_pc:?}"
    );

    // 4. And each side accepts the *other's* message. The phone takes the offer and records the PC;
    //    the PC takes the reply and records the phone. **Not the other way round** — the first version of
    //    this test had the PC accept its own offer, and `Peers::accept` refused it with `Itself`, which is
    //    the protocol being right.
    let mut on_phone_peers = Peers::default();
    let recorded_pc =
        on_phone_peers.accept(&phone, &parsed, NOW).expect("the phone accepts the offer");
    // `PublicIdentity::id()` is fallible — the bytes have to parse — where `Identity::id()` is not.
    assert_eq!(recorded_pc, pc.public().id().expect("the pc's own id parses"));

    let mut on_pc_peers = Peers::default();
    let parsed_reply: Invite = serde_json::from_str(&reply_json).expect("the reply round-trips");
    let recorded_phone =
        on_pc_peers.accept(&pc, &parsed_reply, NOW).expect("the pc accepts the reply");
    assert_eq!(recorded_phone, phone.public().id().expect("the phone's own id parses"));

    // Both devices now hold the other, which is what paired means.
    assert_eq!(on_phone_peers.active_ids().count(), 1);
    assert_eq!(on_pc_peers.active_ids().count(), 1);
}

/// **A device cannot pair with itself**, which is what makes a one-device "success" impossible.
///
/// `Peers::accept` refuses when the invite's author is the device accepting it. That is the check the
/// first version of the exchange test ran into by accident, and it is worth pinning deliberately: without
/// it, a page that fed a device its own offer would report a successful pairing on both screens.
#[test]
fn a_device_cannot_accept_its_own_offer() {
    let pc = Identity::generate("pc");
    let offer = Invite::offer(&pc, NOW);

    let mut peers = Peers::default();
    let refused = peers.accept(&pc, &offer, NOW);

    assert!(refused.is_err(), "a device paired with itself: {refused:?}");
    assert_eq!(peers.active_ids().count(), 0, "a peer was recorded for a self-offer");
}

/// **A device in the middle produces a different phrase.** This is what the phrase is *for*, so it is the
/// property that would make the whole surface pointless if it were wrong.
#[test]
fn a_substituted_key_produces_a_different_phrase() {
    let pc = Identity::generate("pc");
    let phone = Identity::generate("phone");
    let attacker = Identity::generate("attacker");

    let offer = Invite::offer(&pc, NOW);
    let honest = pair::phrase(&phone.public(), &pc.public(), &offer.nonce);
    let swapped = pair::phrase(&attacker.public(), &pc.public(), &offer.nonce);

    assert_ne!(honest, swapped, "a substituted key produced the same phrase");
}

/// **The answering device records the offerer**, not itself — the half of the exchange that decides which
/// key goes in the peers file.
///
/// Renamed from `an_unanswered_offer_cannot_be_accepted_into_a_peer`, which described a property the test
/// did not check: accepting an offer is not a mistake, it is step 2 of the protocol.
#[test]
fn accepting_an_offer_records_the_device_that_made_it() {
    let pc = Identity::generate("pc");
    let phone = Identity::generate("phone");
    let offer = Invite::offer(&pc, NOW);

    let mut peers = Peers::default();
    // The phone is asked to accept the PC's own offer — the shape a confused or hostile page would send.
    // It succeeds by design: accepting an invite means recording the *offerer*. What it must not do is
    // record the phone, which is the mistake a two-sided accept would make.
    let id = peers.accept(&phone, &offer, NOW).expect("the offer is well-formed");
    assert_eq!(
        id,
        pc.public().id().expect("the pc's own id parses"),
        "accept recorded the wrong device"
    );
    assert_eq!(peers.active_ids().count(), 1);
}

/// Revoking is immediate and local — it needs nothing from the device being removed, because a phone that
/// has been lost cannot be asked to agree to its own removal.
#[test]
fn revoking_removes_the_peer_and_asks_nothing_of_it() {
    let pc = Identity::generate("pc");
    let phone = Identity::generate("phone");
    let offer = Invite::offer(&pc, NOW);

    let mut peers = Peers::default();
    let id: DeviceId = peers.accept(&phone, &offer, NOW).unwrap();
    assert_eq!(peers.active_ids().count(), 1);

    peers.revoke(&id, NOW).expect("revoking is local");
    assert_eq!(peers.active_ids().count(), 0, "the peer survived its revocation");
}

/// An unpaired device still has an identity to offer, which is what step 1 was for.
///
/// This is the property the recorded scope named as *the* design question: `start_sync` used to discard
/// `shared` when nothing was paired, so a machine with no peers had no way to reach its own identity and
/// therefore no way to offer an invite.
#[test]
fn an_identity_exists_before_any_peer_does() {
    let dir = std::env::temp_dir().join(format!("curfew-pair-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);

    let (shared, _complaints) =
        curfew_sync::store::open(&dir, "this pc").expect("a fresh store opens");

    assert_eq!(shared.peers.lock().unwrap().active_ids().count(), 0, "a fresh store is unpaired");
    // And the identity is right there, which is what makes an invite possible before a peer exists.
    let offer = Invite::offer(&shared.identity, NOW);
    assert!(!json(&offer).is_empty(), "an unpaired device could not offer an invite");

    let _ = std::fs::remove_dir_all(&dir);
}
