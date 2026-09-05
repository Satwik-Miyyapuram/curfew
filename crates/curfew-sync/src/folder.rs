//! Sync through a folder somebody else runs: Dropbox, OneDrive, Syncthing, a USB stick.
//!
//! This is the transport that needs no network between the two devices and no cooperation from
//! anyone — which is why it is worth supporting even though the folder itself is a third party
//! (GAPS C5). Everything written here is sealed for exactly one peer, so the provider stores bytes
//! it cannot read; what it can see is the shape of the tree, and that is stated plainly below
//! rather than pretended away.
//!
//! The layout is chosen so that two devices writing at the same moment cannot collide, because a
//! cloud folder has no locks worth the name and its idea of "resolving a conflict" is to leave two
//! files behind and let a human sort it out:
//!
//! ```text
//! <root>/<from-device>/<to-device>/heads.json     what the writer holds
//! <root>/<from-device>/<to-device>/000001.curfew  immutable segment
//! ```
//!
//! Every path is owned by exactly one writer, and every segment file is written once and never
//! edited. A reader only ever reads; a writer only ever writes under its own id. So the folder
//! never has two versions of anything to reconcile, and a half-written file — the machine slept,
//! the sync client stopped mid-copy — is a file that fails to open and is skipped, not a file that
//! corrupts a log.
//!
//! What the provider can infer: how many devices are paired, their ids, when each last wrote, and
//! roughly how much happened. What it cannot infer: which apps are blocked, when, by whom, or
//! whether anything is blocked at all.

use crate::device::{DeviceId, Identity, PublicIdentity};
use crate::oplog::Log;
use crate::pair::Peers;
use crate::wire::{self, Message, Packet, Received};
use curfew_core::Timestamp;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};

/// How many segments one writer keeps for one reader before it stops writing new ones.
///
/// A reader that has been offline for a long time is the normal case — a laptop on holiday — so the
/// cap is generous. It exists only so that a reader which never comes back cannot grow the folder
/// without end; when it is reached the writer keeps the newest segments, and the missing history is
/// recovered from the other device directly rather than from the folder.
const KEEP_SEGMENTS: usize = 512;

/// A segment file: one sealed batch, plus the plaintext note of what it covers.
///
/// The coverage is outside the envelope on purpose. It is what lets a writer delete a segment the
/// reader has already taken in without opening it, and it says no more than the folder's own
/// directory names already do.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct Segment {
    covers: BTreeMap<DeviceId, u64>,
    packet: Packet,
}

/// What one pass over the folder did.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Pass {
    pub received: Received,
    /// Segments written for peers this time.
    pub written: usize,
    /// Segments deleted because the peer has taken them in.
    pub pruned: usize,
    /// Files that could not be read or opened, and were left where they were. A file still being
    /// copied by the sync client lands here and succeeds on the next pass.
    pub skipped: usize,
}

/// A folder used as a transport. Holds no state of its own: everything it knows is on disk, so a
/// device that is restarted mid-sync resumes by looking rather than by remembering.
#[derive(Debug, Clone)]
pub struct Folder {
    root: PathBuf,
}

impl Folder {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    fn outbox(&self, from: &DeviceId, to: &DeviceId) -> PathBuf {
        self.root.join(from.as_str()).join(to.as_str())
    }

    /// Write what a peer is missing, and clear away what it has already taken.
    pub fn publish(&self, me: &Identity, peer: &PublicIdentity, log: &Log) -> io::Result<Pass> {
        let mut pass = Pass::default();
        let Ok(peer_id) = peer.id() else {
            return Ok(pass);
        };
        let out = self.outbox(&me.id(), &peer_id);
        std::fs::create_dir_all(&out)?;

        // What the peer says it holds. Absent on the first ever pass, and absent for good if the
        // peer only ever reads — in which case it is sent everything, which is correct.
        let theirs = self.heads_from(me, peer, &peer_id)?.unwrap_or_default();
        pass.pruned = self.prune(&out, &theirs)?;

        let missing = log.since(&theirs);
        if !missing.is_empty() && self.segments(&out)?.len() < KEEP_SEGMENTS {
            let packet = wire::pack(me, peer, &Message::Entries(missing));
            let segment = Segment { covers: log.heads(), packet };
            let name = format!("{:06}.curfew", self.next_number(&out)?);
            write_atomically(&out.join(name), &serde_json::to_vec(&segment)?)?;
            pass.written = 1;
        }

        // Written last, so a peer that reads a stale heads file only ever asks for too much rather
        // than too little.
        let heads = wire::pack(me, peer, &Message::Heads(log.heads()));
        write_atomically(&out.join("heads.json"), &serde_json::to_vec(&heads)?)?;
        Ok(pass)
    }

    /// Take in everything every paired device has left for this one.
    pub fn collect(&self, me: &Identity, peers: &Peers, log: &mut Log) -> io::Result<Pass> {
        let mut pass = Pass::default();
        for id in peers.active_ids() {
            let inbox = self.outbox(id, &me.id());
            for path in self.segments(&inbox)? {
                let Ok(bytes) = std::fs::read(&path) else {
                    pass.skipped += 1;
                    continue;
                };
                let Ok(segment) = serde_json::from_slice::<Segment>(&bytes) else {
                    // Half-copied, or written by something that is not Curfew. Leaving it alone is
                    // the honest move: the sync client may still be working on it.
                    pass.skipped += 1;
                    continue;
                };
                match wire::unpack(me, peers, &segment.packet) {
                    Ok(Message::Entries(entries)) => {
                        let got = wire::receive(log, peers, &entries);
                        pass.received.accepted += got.accepted;
                        pass.received.known += got.known;
                        pass.received.refused += got.refused;
                    }
                    Ok(Message::Heads(_)) => pass.skipped += 1,
                    Err(_) => pass.skipped += 1,
                }
            }
        }
        Ok(pass)
    }

    /// When each paired device last wrote anything here.
    ///
    /// The UI needs this and must not guess it. A folder transport can be minutes behind and the
    /// only wrong thing to do is to show it as if it were live (GAPS C5), so what is reported is the
    /// last time bytes actually appeared, never the last time we tried.
    pub fn last_written(&self, me: &Identity, peers: &Peers) -> BTreeMap<DeviceId, Timestamp> {
        let mut out = BTreeMap::new();
        for id in peers.active_ids() {
            let heads = self.outbox(id, &me.id()).join("heads.json");
            if let Ok(at) = std::fs::metadata(&heads).and_then(|m| m.modified()) {
                if let Ok(since) = at.duration_since(std::time::UNIX_EPOCH) {
                    out.insert(id.clone(), since.as_secs() as Timestamp);
                }
            }
        }
        out
    }

    /// The heads a peer last announced to this device.
    fn heads_from(
        &self,
        me: &Identity,
        peer: &PublicIdentity,
        peer_id: &DeviceId,
    ) -> io::Result<Option<BTreeMap<DeviceId, u64>>> {
        let path = self.outbox(peer_id, &me.id()).join("heads.json");
        let Ok(bytes) = std::fs::read(&path) else {
            return Ok(None);
        };
        let Ok(packet) = serde_json::from_slice::<Packet>(&bytes) else {
            return Ok(None);
        };
        if packet.from != *peer_id {
            return Ok(None);
        }
        let plaintext = match crate::envelope::open(me, peer, &packet.sealed) {
            Ok(plaintext) => plaintext,
            Err(_) => return Ok(None),
        };
        match serde_json::from_slice::<Message>(&plaintext) {
            Ok(Message::Heads(heads)) => Ok(Some(heads)),
            _ => Ok(None),
        }
    }

    /// Segment files in one directory, oldest first. A missing directory is not an error: it is
    /// simply a peer that has not written yet.
    fn segments(&self, dir: &Path) -> io::Result<Vec<PathBuf>> {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return Ok(Vec::new());
        };
        let mut out: Vec<PathBuf> = entries
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|e| e == "curfew"))
            .collect();
        out.sort();
        Ok(out)
    }

    fn next_number(&self, dir: &Path) -> io::Result<u64> {
        let highest = self
            .segments(dir)?
            .iter()
            .filter_map(|p| p.file_stem()?.to_str()?.parse::<u64>().ok())
            .max()
            .unwrap_or(0);
        Ok(highest + 1)
    }

    /// Delete segments the peer has demonstrably taken in.
    fn prune(&self, dir: &Path, theirs: &BTreeMap<DeviceId, u64>) -> io::Result<usize> {
        let mut pruned = 0;
        for path in self.segments(dir)? {
            let Ok(bytes) = std::fs::read(&path) else { continue };
            let Ok(segment) = serde_json::from_slice::<Segment>(&bytes) else { continue };
            let taken = segment
                .covers
                .iter()
                .all(|(author, seq)| theirs.get(author).is_some_and(|held| held >= seq));
            if taken && std::fs::remove_file(&path).is_ok() {
                pruned += 1;
            }
        }
        Ok(pruned)
    }
}

/// Write through a temporary file in the same directory, then rename.
///
/// A reader must never see half a segment, and a cloud client must never upload half of one either.
/// Rename within a directory is the closest thing to atomic that every platform Curfew runs on
/// agrees about.
fn write_atomically(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let temp = path.with_extension("partial");
    std::fs::write(&temp, bytes)?;
    match std::fs::rename(&temp, path) {
        Ok(()) => Ok(()),
        // Windows refuses a rename onto an existing file.
        Err(_) => {
            let _ = std::fs::remove_file(path);
            std::fs::rename(&temp, path)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::oplog::Op;
    use crate::pair::Invite;
    use curfew_core::session::{Session, SessionSource};
    use curfew_core::{Lock, LockSet};

    const NOW: Timestamp = 1_788_510_600;

    struct World {
        _dir: tempfile::TempDir,
        folder: Folder,
        phone: Identity,
        pc: Identity,
        on_phone: Peers,
        on_pc: Peers,
        phone_log: Log,
        pc_log: Log,
    }

    fn world() -> World {
        let dir = tempfile::tempdir().unwrap();
        let folder = Folder::new(dir.path());
        let (phone, pc) = (Identity::generate("phone"), Identity::generate("pc"));
        let (mut on_phone, mut on_pc) = (Peers::default(), Peers::default());
        on_phone.accept(&phone, &Invite::new(&pc, NOW, [3; 16]), NOW).unwrap();
        on_pc.accept(&pc, &Invite::new(&phone, NOW, [3; 16]), NOW).unwrap();
        World {
            _dir: dir,
            folder,
            phone,
            pc,
            on_phone,
            on_pc,
            phone_log: Log::default(),
            pc_log: Log::default(),
        }
    }

    fn start(id: &str) -> Op {
        start_in(id, "deep-work")
    }

    fn start_in(id: &str, profile: &str) -> Op {
        Op::Start {
            session: Box::new(Session {
                id: id.into(),
                profile: profile.into(),
                source: SessionSource::Manual,
                started_at: NOW,
                lock: LockSet::new([Lock::DeviceCredential], None),
            }),
        }
    }

    /// One pass in each direction, as the service would run it on a timer.
    fn exchange(w: &mut World) {
        w.folder.publish(&w.pc, &w.phone.public(), &w.pc_log).unwrap();
        w.folder.publish(&w.phone, &w.pc.public(), &w.phone_log).unwrap();
        w.folder.collect(&w.phone, &w.on_phone, &mut w.phone_log).unwrap();
        w.folder.collect(&w.pc, &w.on_pc, &mut w.pc_log).unwrap();
    }

    #[test]
    fn a_session_started_on_the_pc_reaches_the_phone_through_the_folder() {
        let mut w = world();
        w.pc_log.append(&w.pc, NOW, start("s1"));

        exchange(&mut w);

        assert_eq!(w.phone_log.replay(NOW + 1), w.pc_log.replay(NOW + 1));
        assert_eq!(w.phone_log.replay(NOW + 1).sessions.running.len(), 1);
    }

    #[test]
    fn both_devices_writing_at_once_never_collide() {
        // Two writers, two disjoint paths. This is the whole reason for the layout: a cloud folder
        // that finds two edits to one file leaves a "conflicted copy" behind and moves on.
        let mut w = world();
        // Different profiles, because one profile has one running session by construction and the
        // point here is two independent writers rather than two views of the same thing.
        w.pc_log.append(&w.pc, NOW, start_in("from-pc", "deep-work"));
        w.phone_log.append(&w.phone, NOW, start_in("from-phone", "evenings"));

        exchange(&mut w);
        exchange(&mut w);

        assert_eq!(w.phone_log.replay(NOW + 1), w.pc_log.replay(NOW + 1));
        assert_eq!(w.phone_log.replay(NOW + 1).sessions.running.len(), 2);
    }

    #[test]
    fn a_half_written_file_is_skipped_and_works_on_the_next_pass() {
        let mut w = world();
        w.pc_log.append(&w.pc, NOW, start("s1"));
        w.folder.publish(&w.pc, &w.phone.public(), &w.pc_log).unwrap();

        // The sync client is still copying: the file exists and is truncated.
        let inbox = w.folder.outbox(&w.pc.id(), &w.phone.id());
        let segment = w.folder.segments(&inbox).unwrap()[0].clone();
        let whole = std::fs::read(&segment).unwrap();
        std::fs::write(&segment, &whole[..whole.len() / 2]).unwrap();

        let pass = w.folder.collect(&w.phone, &w.on_phone, &mut w.phone_log).unwrap();
        assert_eq!(pass.skipped, 1);
        assert!(w.phone_log.is_empty());

        std::fs::write(&segment, &whole).unwrap();
        let pass = w.folder.collect(&w.phone, &w.on_phone, &mut w.phone_log).unwrap();
        assert_eq!(pass.received.accepted, 1);
    }

    #[test]
    fn a_segment_the_peer_has_taken_in_is_cleared_away() {
        // Otherwise the folder grows for as long as the pairing lasts.
        let mut w = world();
        w.pc_log.append(&w.pc, NOW, start("s1"));
        // Three passes, because pruning is deliberately evidence-driven: the writer deletes only
        // once the reader's own heads file says it holds the entries. Pass one delivers, pass two
        // publishes the reader's new heads, pass three clears away.
        exchange(&mut w);
        exchange(&mut w);
        exchange(&mut w);

        let out = w.folder.outbox(&w.pc.id(), &w.phone.id());
        assert!(w.folder.segments(&out).unwrap().is_empty(), "the folder kept growing");
        assert_eq!(w.phone_log.len(), 1, "pruning cost an entry");
    }

    #[test]
    fn nothing_is_written_when_there_is_nothing_to_say() {
        let mut w = world();
        exchange(&mut w);
        let pass = w.folder.publish(&w.pc, &w.phone.public(), &w.pc_log).unwrap();

        assert_eq!(pass.written, 0);
    }

    #[test]
    fn the_provider_cannot_read_what_is_being_blocked() {
        let mut w = world();
        w.pc_log.append(
            &w.pc,
            NOW,
            Op::Used { key: "app:com.instagram.android".into(), at: NOW, seconds: 60 },
        );
        w.folder.publish(&w.pc, &w.phone.public(), &w.pc_log).unwrap();

        let out = w.folder.outbox(&w.pc.id(), &w.phone.id());
        for path in w.folder.segments(&out).unwrap().iter().chain(&[out.join("heads.json")]) {
            let bytes = std::fs::read(path).unwrap();
            assert!(
                !String::from_utf8_lossy(&bytes).contains("instagram"),
                "a package name reached the folder in the clear: {}",
                path.display()
            );
        }
    }

    #[test]
    fn a_stranger_who_can_write_to_the_folder_cannot_inject_anything() {
        // A shared folder is shared: whoever else has the link can add files to it.
        let mut w = world();
        let attacker = Identity::generate("attacker");
        let mut theirs = Log::default();
        theirs.append(&attacker, NOW, start("unlock-everything"));

        // Written into the PC's outbox, wearing the PC's directory name.
        let out = w.folder.outbox(&w.pc.id(), &w.phone.id());
        std::fs::create_dir_all(&out).unwrap();
        let segment = Segment {
            covers: theirs.heads(),
            packet: wire::pack(
                &attacker,
                &w.phone.public(),
                &Message::Entries(theirs.since(&BTreeMap::new())),
            ),
        };
        std::fs::write(out.join("000001.curfew"), serde_json::to_vec(&segment).unwrap()).unwrap();

        let pass = w.folder.collect(&w.phone, &w.on_phone, &mut w.phone_log).unwrap();
        assert_eq!(pass.skipped, 1);
        assert!(w.phone_log.is_empty(), "the folder was allowed to write history");
    }

    #[test]
    fn a_revoked_device_is_not_read_from_again() {
        let mut w = world();
        w.pc_log.append(&w.pc, NOW, start("s1"));
        w.folder.publish(&w.pc, &w.phone.public(), &w.pc_log).unwrap();
        w.on_phone.revoke(&w.pc.id(), NOW + 1).unwrap();

        let pass = w.folder.collect(&w.phone, &w.on_phone, &mut w.phone_log).unwrap();
        assert_eq!(pass.received.accepted, 0);
        assert!(w.phone_log.is_empty());
    }

    #[test]
    fn how_far_behind_a_peer_is_can_be_told_rather_than_guessed() {
        let mut w = world();
        assert!(w.folder.last_written(&w.phone, &w.on_phone).is_empty(), "a peer that never wrote");

        w.pc_log.append(&w.pc, NOW, start("s1"));
        w.folder.publish(&w.pc, &w.phone.public(), &w.pc_log).unwrap();

        let seen = w.folder.last_written(&w.phone, &w.on_phone);
        assert!(seen.contains_key(&w.pc.id()), "the PC wrote and it was not noticed");
    }

    #[test]
    fn a_folder_that_redelivers_everything_forever_changes_nothing() {
        // Some clients re-download files they already had. Idempotence is the property that makes
        // that boring rather than dangerous.
        let mut w = world();
        w.pc_log.append(&w.pc, NOW, start("s1"));
        w.folder.publish(&w.pc, &w.phone.public(), &w.pc_log).unwrap();

        let first = w.folder.collect(&w.phone, &w.on_phone, &mut w.phone_log).unwrap();
        let believed = w.phone_log.replay(NOW + 1);
        let again = w.folder.collect(&w.phone, &w.on_phone, &mut w.phone_log).unwrap();

        assert_eq!(first.received.accepted, 1);
        assert_eq!(again.received.known, 1);
        assert_eq!(w.phone_log.replay(NOW + 1), believed);
    }

    #[test]
    fn a_device_killed_mid_sync_resumes_from_what_is_on_disk() {
        // Killing either device mid-sync must leave both in a valid state: there is no session to
        // resume, so the next pass simply looks at the folder again.
        let mut w = world();
        w.pc_log.append(&w.pc, NOW, start("s1"));
        w.pc_log.append(&w.pc, NOW + 1, start("s2"));
        w.folder.publish(&w.pc, &w.phone.public(), &w.pc_log).unwrap();

        // The phone dies before it ever reads; a fresh log stands in for the restart.
        let mut restarted = Log::default();
        w.folder.collect(&w.phone, &w.on_phone, &mut restarted).unwrap();

        assert_eq!(restarted.replay(NOW + 2), w.pc_log.replay(NOW + 2));
    }
}
