//! Sync between a phone and a PC with nothing in the middle.
//!
//! There is no account, no server and no relay (DECISIONS D1). Two devices that have been paired
//! hold each other's public keys and nothing else, and everything they exchange afterwards is an
//! append-only log of signed, encrypted operations that either side can replay in any order and
//! arrive at the same state.
//!
//! Three properties are load-bearing, and each one is tested rather than asserted:
//!
//! * **Merging never weakens a lock.** The op-log is a set, replay is a fold, and every path that
//!   touches a lock goes through the lattice in `curfew_core::lock` (GAPS C1).
//! * **A device can be removed.** Pairing is not a shared secret that everybody holds forever;
//!   each device has its own key, so revoking one is a signed operation the others can verify
//!   without ceremony (GAPS C3).
//! * **A transport is untrusted.** LAN, a shared folder, or a QR code beamed across a table are
//!   all pipes. Bytes are signed and encrypted before they reach one, so the pipe cannot forge a
//!   block, lift one, or read what is being blocked (GAPS C5).
pub mod device;
pub mod envelope;
pub mod oplog;
pub mod pair;
