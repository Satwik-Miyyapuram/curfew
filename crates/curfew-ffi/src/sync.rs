//! The Android binding for sync: pairing, the node, and one call that squares the log with what
//! the app is enforcing.
//!
//! Everything difficult is in `curfew-sync` and is tested there. What this file adds is the shape
//! the Kotlin side needs, and one rule: the phone's sessions live behind [`crate::Curfew`], so the
//! mirror is handed that object rather than a copy of the sessions. Kotlin never holds a `Sessions`
//! it could edit and hand back, which is what stops "sync" from becoming a way to write a weaker
//! lock into the core.
//!
//! Budgets are the exception and they cross as JSON, because on Android the usage history lives in
//! Room rather than in the core. The pass takes what the database holds, returns what the log says
//! it should now hold, and the caller writes that back.

use crate::{payload, Curfew, CurfewError};
use curfew_core::budget::{Consumption, Launches};
use curfew_core::{CalendarEvent, Timestamp};
use curfew_sync::device::DeviceId;
use curfew_sync::mirror::Mirror;
use curfew_sync::node::{Node, Shared};
use curfew_sync::pair::{self, Invite};
use curfew_sync::{folder, store};
use serde::Serialize;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

/// What one [`Sync::pass`] produced. The budgets come back because the caller owns them.
#[derive(Debug, Serialize)]
struct PassResult {
    published: usize,
    adopted: Vec<String>,
    /// Sessions another device ended that are still locked here. The UI owes the user this
    /// explanation: their phone says the block is over and this device disagrees, and the reason is
    /// the lock they themselves asked for.
    still_locked: Vec<String>,
    usage: BTreeMap<String, Consumption>,
    launches: BTreeMap<String, Launches>,
    /// Events the *other* devices' calendars hold, for this device's rules to act on. This is how a
    /// phone that was never given calendar permission still goes quiet during a meeting.
    calendar: Vec<CalendarEvent>,
    /// Sessions this device is the named releaser for and has not yet released, so the UI can
    /// offer the button. Recomputed every pass, because adoption is what makes a lock appear here.
    releasable: Vec<String>,
}

/// Sync on this device: the log, the peers, and the node that carries them, if it is running.
#[derive(uniffi::Object)]
pub struct Sync {
    shared: Shared,
    root: PathBuf,
    node: Mutex<Option<Node>>,
    mirror: Mutex<Mirror>,
    /// Anything the store could not read at startup, kept so the app can show it once. A pairing
    /// silently lost is worse than a pairing loudly lost.
    complaints: Vec<String>,
}

impl std::fmt::Debug for Sync {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Sync").field("id", &self.device_id()).finish_non_exhaustive()
    }
}

fn sync_error<E: std::fmt::Display>(e: E) -> CurfewError {
    CurfewError::Payload { detail: e.to_string() }
}

#[uniffi::export]
impl Sync {
    /// Open, or create on first run, this device's sync state in `dir`.
    ///
    /// `dir` must be storage only this app can read: the identity file is the device's private key,
    /// and a device that can be impersonated can be told its locks are over.
    #[uniffi::constructor]
    pub fn open(dir: String, device_name: String) -> Result<Arc<Self>, CurfewError> {
        let root = PathBuf::from(dir);
        let (shared, complaints) = store::open(&root, &device_name).map_err(sync_error)?;
        Ok(Arc::new(Self {
            shared,
            root,
            node: Mutex::new(None),
            mirror: Mutex::new(Mirror::default()),
            complaints,
        }))
    }

    /// Trouble found while opening: an unreadable log, a lost identity. Empty on a healthy device.
    pub fn complaints(&self) -> Vec<String> {
        self.complaints.clone()
    }

    /// This device's id, as it appears to peers.
    pub fn device_id(&self) -> String {
        self.shared.identity.id().to_string()
    }

    /// The fingerprint to show beside the id, for a user comparing two screens.
    pub fn fingerprint(&self) -> String {
        self.shared.identity.public().fingerprint()
    }

    /// Offer to pair. The result is JSON to put in a QR code; it is not secret and may be
    /// photographed, but it cannot authenticate itself, which is what the phrase is for.
    pub fn invite_json(&self, now: Timestamp) -> Result<String, CurfewError> {
        serde_json::to_string(&Invite::offer(&self.shared.identity, now)).map_err(payload)
    }

    /// The six digits both devices must show before anyone presses accept. Derived from both public
    /// keys and the invite's nonce, so a device in the middle that swapped a key produces a
    /// different phrase and is caught by the reading.
    pub fn phrase_for(&self, invite_json: String) -> Result<String, CurfewError> {
        let invite: Invite = serde_json::from_str(&invite_json).map_err(payload)?;
        Ok(pair::phrase(&self.shared.identity.public(), &invite.from, &invite.nonce))
    }

    /// Answer an invite with this device's own keys, reusing the invite's nonce.
    ///
    /// The phrase covers both devices' keys and one nonce, so the device that *offered* cannot
    /// derive it until it has seen the other device's keys. That is what this is for: the phone
    /// scans the PC's code, shows this reply, and the PC reads the reply back. Two codes, one
    /// nonce, and both screens show the same six digits before either side accepts.
    pub fn reply_to(&self, invite_json: String) -> Result<String, CurfewError> {
        let invite: Invite = serde_json::from_str(&invite_json).map_err(payload)?;
        let reply = Invite {
            from: self.shared.identity.public(),
            nonce: invite.nonce,
            issued_at: invite.issued_at,
            expires_at: invite.expires_at,
        };
        serde_json::to_string(&reply).map_err(payload)
    }

    /// Accept an invite. Call this only after the user has confirmed the phrases match: nothing
    /// here can check that for them, and that is the design rather than a shortcoming.
    pub fn accept_invite(
        &self,
        invite_json: String,
        now: Timestamp,
    ) -> Result<String, CurfewError> {
        let invite: Invite = serde_json::from_str(&invite_json).map_err(payload)?;
        let id = {
            let mut peers = self.shared.peers.lock().expect("the peers lock is never poisoned");
            peers.accept(&self.shared.identity, &invite, now).map_err(sync_error)?
        };
        self.save()?;
        Ok(id.to_string())
    }

    /// Every device this one has paired with, revoked ones included, as JSON.
    pub fn peers_json(&self) -> Result<String, CurfewError> {
        let peers = self.shared.peers.lock().expect("the peers lock is never poisoned");
        serde_json::to_string(&*peers).map_err(payload)
    }

    /// Remove a device. Immediate, local, and needing nothing from the device being removed — a
    /// phone that has been lost cannot be asked to agree to its own removal.
    pub fn revoke(&self, device_id: String, now: Timestamp) -> Result<(), CurfewError> {
        let id: DeviceId = device_id.parse().map_err(sync_error)?;
        {
            let mut peers = self.shared.peers.lock().expect("the peers lock is never poisoned");
            peers.revoke(&id, now).map_err(sync_error)?;
        }
        self.save()
    }

    /// Start listening and announcing on the local network. Idempotent.
    ///
    /// A phone with no multicast — a captive network, or the multicast lock not held — still starts
    /// and still answers; it simply will not find peers by itself. Failing here would take away the
    /// transport that does work.
    pub fn start_node(&self) -> Result<(), CurfewError> {
        let mut node = self.node.lock().expect("the node lock is never poisoned");
        if node.is_none() {
            *node = Some(Node::start(self.shared.clone()).map_err(sync_error)?);
        }
        Ok(())
    }

    /// Stop the network threads. Called when the app is backgrounded and the phone would rather
    /// not hold a socket open. Enforcement is unaffected: what this device already knows it keeps.
    pub fn stop_node(&self) {
        if let Some(node) = self.node.lock().expect("the node lock is never poisoned").take() {
            node.stop();
        }
    }

    pub fn is_running(&self) -> bool {
        self.node.lock().expect("the node lock is never poisoned").is_some()
    }

    /// Talk to every peer heard from recently. Cheap and safe to call often; a peer that has left
    /// the room costs an error that is thrown away.
    pub fn push_all(&self, now: Timestamp) {
        if let Some(node) = self.node.lock().expect("the node lock is never poisoned").as_ref() {
            node.push_all(now);
        }
    }

    /// Ids of the peers currently reachable on this network, for the UI to say "in sync with".
    pub fn nearby(&self, now: Timestamp) -> Vec<String> {
        match self.node.lock().expect("the node lock is never poisoned").as_ref() {
            Some(node) => node.nearby(now).into_iter().map(|(id, _)| id.to_string()).collect(),
            None => Vec::new(),
        }
    }

    /// One exchange through a shared folder, for devices that are never on the same network.
    /// Latency is whatever the folder's own syncing costs, which is why the UI reports when each
    /// peer last wrote rather than pretending this is live.
    pub fn folder_pass(&self, root: String) -> Result<String, CurfewError> {
        let folder = folder::Folder::new(PathBuf::from(root));
        let peers = self.shared.peers.lock().expect("the peers lock is never poisoned");
        let mut log = self.shared.log.lock().expect("the log lock is never poisoned");
        let collected =
            folder.collect(&self.shared.identity, &peers, &mut log).map_err(sync_error)?;
        let mut wrote = 0;
        for id in peers.active_ids() {
            if let Ok(peer) = peers.active(id) {
                let pass = folder
                    .publish(&self.shared.identity, &peer.identity, &log)
                    .map_err(sync_error)?;
                wrote += pass.written;
            }
        }
        let last = folder.last_written(&self.shared.identity, &peers);
        drop(log);
        drop(peers);
        self.save()?;
        serde_json::to_string(&serde_json::json!({
            "accepted": collected.received.accepted,
            "written": wrote,
            "last_written": last.into_iter().map(|(k, v)| (k.to_string(), v)).collect::<BTreeMap<_, _>>(),
        }))
        .map_err(payload)
    }

    /// Square the log with what this device is enforcing: publish what happened here, then adopt
    /// what everyone knows. Returns the pass, and the budgets the caller should now store.
    pub fn pass(
        &self,
        curfew: Arc<Curfew>,
        now: Timestamp,
        usage_json: String,
        launches_json: String,
        calendar_json: String,
    ) -> Result<String, CurfewError> {
        let mut usage: BTreeMap<String, Consumption> =
            serde_json::from_str(blank_to_object(&usage_json)).map_err(payload)?;
        let mut launches: BTreeMap<String, Launches> =
            serde_json::from_str(blank_to_object(&launches_json)).map_err(payload)?;
        let seen: Vec<CalendarEvent> =
            serde_json::from_str(blank_to_array(&calendar_json)).map_err(payload)?;

        // Passes go out before the merge comes back, so one spent here in the last few seconds is
        // already in the log this device then adopts its ration from.
        let said_passes = {
            let passes = curfew.passes.read().expect("passes lock");
            self.mirror.lock().expect("the mirror lock is never poisoned").publish_passes(
                &self.shared,
                now,
                &passes,
            )
        };

        let pass = {
            let mut sessions = curfew.sessions.write().expect("sessions lock");
            self.mirror.lock().expect("the mirror lock is never poisoned").pass(
                &self.shared,
                now,
                &mut sessions,
                &mut usage,
                &mut launches,
            )
        };

        // Releases go out before the merge comes back too, and for the same reason: one given
        // seconds ago must be in the log the peer reads on its very next pass, or the other device
        // stays shut with the user standing in front of the one that said yes.
        let said_releases = {
            let releases = curfew.releases.read().expect("releases lock").clone();
            self.mirror.lock().expect("the mirror lock is never poisoned").publish_releases(
                &self.shared,
                now,
                &releases,
            )
        };

        // What every device has released, as the log now has it. Adopted before `releasable` is
        // read, so a lock that arrived in this same pass is already answerable.
        {
            let mut released = curfew.released.write().expect("released lock");
            *released = pass
                .released
                .iter()
                .map(|(session, devices)| (session.clone(), devices.iter().cloned().collect()))
                .collect();
            *curfew.device_id.write().expect("device lock") =
                Some(self.shared.identity.id().as_str().to_string());
        }

        // Only the events a rule on this device would act on are published. The log is encrypted
        // and goes nowhere but the user's own devices, but a lock does not need the name of every
        // meeting in someone's week to do its job.
        let said = {
            let config = curfew.config.read().expect("config lock");
            let matched: Vec<CalendarEvent> = seen
                .into_iter()
                .filter(|event| config.calendars.iter().any(|s| s.matcher.matches(event)))
                .collect();
            self.mirror.lock().expect("the mirror lock is never poisoned").publish_calendar(
                &self.shared,
                now,
                &matched,
            )
        };

        *curfew.passes.write().expect("passes lock") = pass.passes.clone();

        if pass.published + said + said_passes + said_releases > 0 {
            self.push_all(now);
        }
        self.save()?;
        serde_json::to_string(&PassResult {
            published: pass.published,
            adopted: pass.adopted,
            still_locked: pass.still_locked,
            usage,
            launches,
            calendar: pass.calendar,
            releasable: curfew.releasable(),
        })
        .map_err(payload)
    }

    /// Summarize everything older than `through` into a checkpoint, so a phone's log does not grow
    /// forever. Sessions and locks are carried whole: compaction is about size and must never be a
    /// way to forget a promise.
    pub fn compact(&self, through: Timestamp, now: Timestamp) -> Result<(), CurfewError> {
        self.shared.log.lock().expect("the log lock is never poisoned").compact(through, now);
        self.save()
    }

    /// Write peers and log back to storage.
    pub fn save(&self) -> Result<(), CurfewError> {
        store::save(&self.root, &self.shared).map_err(sync_error)
    }
}

fn blank_to_array(text: &str) -> &str {
    match text.trim().is_empty() {
        true => "[]",
        false => text,
    }
}

fn blank_to_object(text: &str) -> &str {
    match text.trim().is_empty() {
        true => "{}",
        false => text,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_core::session::{Session, SessionSource, Sessions};
    use curfew_core::{Lock, LockSet};

    const NOW: Timestamp = 1_788_510_600;
    const HOUR: Timestamp = 3600;

    fn dir(tag: &str) -> String {
        let path = std::env::temp_dir().join("curfew-ffi-sync").join(format!(
            "{}-{tag}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::remove_dir_all(&path).ok();
        path.to_string_lossy().into_owned()
    }

    fn core() -> Arc<Curfew> {
        Curfew::new(
            "timezone = \"UTC\"\n\n[[profiles]]\nid = \"deep-work\"\nname = \"Deep work\"\n".into(),
        )
        .unwrap()
    }

    /// Two devices that have been through the pairing ceremony, phrases and all.
    fn paired(tag: &str) -> (Arc<Sync>, Arc<Sync>) {
        let phone = Sync::open(dir(&format!("{tag}-phone")), "phone".into()).unwrap();
        let pc = Sync::open(dir(&format!("{tag}-pc")), "pc".into()).unwrap();
        let invite = pc.invite_json(NOW).unwrap();
        let reply = phone.reply_to(invite.clone()).unwrap();
        assert_eq!(
            phone.phrase_for(invite.clone()).unwrap(),
            pc.phrase_for(reply.clone()).unwrap(),
            "the two devices would have shown the user different phrases"
        );
        phone.accept_invite(invite, NOW).unwrap();
        pc.accept_invite(reply, NOW).unwrap();
        (phone, pc)
    }

    fn session_json(id: &str) -> String {
        serde_json::to_string(&Session {
            id: id.into(),
            profile: "deep-work".into(),
            source: SessionSource::Manual,
            started_at: NOW,
            lock: LockSet::new([Lock::DeviceCredential], Some(NOW + HOUR)),
        })
        .unwrap()
    }

    /// Move everything one device holds to the other, as a transport would.
    fn carry(from: &Sync, to: &Sync) {
        let entries = {
            let log = from.shared.log.lock().unwrap();
            log.since(&to.shared.log.lock().unwrap().heads())
        };
        let peers = to.shared.peers.lock().unwrap();
        let mut log = to.shared.log.lock().unwrap();
        curfew_sync::wire::receive(&mut log, &peers, &entries);
    }

    #[test]
    fn a_lock_started_on_one_device_is_held_by_the_other() {
        let (phone, pc) = paired("held");
        let (phone_core, pc_core) = (core(), core());
        pc_core.start_session(session_json("pc-1")).unwrap();

        pc.pass(pc_core, NOW, String::new(), String::new(), String::new()).unwrap();
        carry(&pc, &phone);
        let result: serde_json::Value = serde_json::from_str(
            &phone
                .pass(phone_core.clone(), NOW + 1, String::new(), String::new(), String::new())
                .unwrap(),
        )
        .unwrap();

        assert_eq!(result["adopted"][0], "pc-1");
        let held: LockSet =
            serde_json::from_str(&phone_core.merged_lock_json(NOW + 1).unwrap()).unwrap();
        assert!(held.is_locked(), "the phone did not take up the PC's lock");
    }

    #[test]
    fn a_phone_cannot_be_talked_out_of_a_lock_by_a_peer() {
        // The other half of invariant 2 as the app sees it: the PC saying "it is over" is a
        // request, and the phone's own lock is what answers it.
        let (phone, pc) = paired("refuse");
        let (phone_core, pc_core) = (core(), core());
        pc_core.start_session(session_json("pc-1")).unwrap();
        pc.pass(pc_core.clone(), NOW, String::new(), String::new(), String::new()).unwrap();
        carry(&pc, &phone);
        phone
            .pass(phone_core.clone(), NOW + 1, String::new(), String::new(), String::new())
            .unwrap();

        // The PC releases it with the evidence its own user gave: the credential prompt succeeded
        // there, and nowhere else. The phone was given none.
        pc_core.record_credential("pc-1".into(), NOW + 2);
        pc_core.end_session("pc-1".into(), NOW + 2, String::new()).unwrap();
        pc.pass(pc_core, NOW + 2, String::new(), String::new(), String::new()).unwrap();
        carry(&pc, &phone);
        let result: serde_json::Value = serde_json::from_str(
            &phone
                .pass(phone_core.clone(), NOW + 3, String::new(), String::new(), String::new())
                .unwrap(),
        )
        .unwrap();

        assert_eq!(result["still_locked"][0], "pc-1");
        let held: LockSet =
            serde_json::from_str(&phone_core.merged_lock_json(NOW + 3).unwrap()).unwrap();
        assert!(held.is_locked(), "a peer talked this device out of its lock");
    }

    #[test]
    fn a_budget_is_shared_rather_than_handed_out_twice() {
        let (phone, pc) = paired("budget");
        let spent = serde_json::to_string(&BTreeMap::from([(
            "com.instagram.android".to_string(),
            serde_json::json!({ "rollups": [{ "at": NOW, "seconds": 1800 }] }),
        )]))
        .unwrap();

        phone.pass(core(), NOW, spent, String::new(), String::new()).unwrap();
        carry(&phone, &pc);
        let result: serde_json::Value = serde_json::from_str(
            &pc.pass(core(), NOW + 1, String::new(), String::new(), String::new()).unwrap(),
        )
        .unwrap();

        let usage: BTreeMap<String, Consumption> =
            serde_json::from_value(result["usage"].clone()).unwrap();
        assert_eq!(usage["com.instagram.android"].used_since(None), 1800);
    }

    #[test]
    fn a_revoked_device_is_ignored_from_then_on() {
        let (phone, pc) = paired("revoked");
        let pc_core = core();
        phone.revoke(pc.device_id(), NOW).unwrap();
        pc_core.start_session(session_json("pc-1")).unwrap();
        pc.pass(pc_core, NOW + 1, String::new(), String::new(), String::new()).unwrap();
        carry(&pc, &phone);

        let phone_core = core();
        phone
            .pass(phone_core.clone(), NOW + 2, String::new(), String::new(), String::new())
            .unwrap();

        let sessions: Sessions =
            serde_json::from_str(&phone_core.sessions_json().unwrap()).unwrap();
        assert!(sessions.running.is_empty(), "a removed device still changed this one");
    }

    #[test]
    fn everything_survives_the_app_being_killed() {
        let (phone_dir, pc_dir) = (dir("restart-phone"), dir("restart-pc"));
        let phone = Sync::open(phone_dir.clone(), "phone".into()).unwrap();
        let pc = Sync::open(pc_dir, "pc".into()).unwrap();
        phone.accept_invite(pc.invite_json(NOW).unwrap(), NOW).unwrap();
        pc.accept_invite(phone.invite_json(NOW).unwrap(), NOW).unwrap();
        let pc_core = core();
        pc_core.start_session(session_json("pc-1")).unwrap();
        pc.pass(pc_core, NOW, String::new(), String::new(), String::new()).unwrap();
        carry(&pc, &phone);
        phone.pass(core(), NOW + 1, String::new(), String::new(), String::new()).unwrap();
        let id = phone.device_id();

        // Force-stopped, then opened again.
        let after = Sync::open(phone_dir, "phone".into()).unwrap();
        let restored = core();
        after.pass(restored.clone(), NOW + 2, String::new(), String::new(), String::new()).unwrap();

        assert_eq!(after.device_id(), id, "the phone came back as a different device");
        let sessions: Sessions = serde_json::from_str(&restored.sessions_json().unwrap()).unwrap();
        assert_eq!(sessions.running.len(), 1, "a restart lost a lock the phone was under");
    }

    #[test]
    fn a_shared_folder_carries_a_lock_between_two_devices() {
        let (phone, pc) = paired("folder");
        let shared_folder = dir("folder-root");
        std::fs::create_dir_all(&shared_folder).unwrap();
        let pc_core = core();
        pc_core.start_session(session_json("pc-1")).unwrap();
        pc.pass(pc_core, NOW, String::new(), String::new(), String::new()).unwrap();

        pc.folder_pass(shared_folder.clone()).unwrap();
        phone.folder_pass(shared_folder).unwrap();
        let phone_core = core();
        phone
            .pass(phone_core.clone(), NOW + 1, String::new(), String::new(), String::new())
            .unwrap();

        let sessions: Sessions =
            serde_json::from_str(&phone_core.sessions_json().unwrap()).unwrap();
        assert_eq!(sessions.running.len(), 1, "the folder did not carry the lock");
    }

    #[test]
    fn the_node_starts_and_stops_without_ceremony() {
        let (phone, _) = paired("node");
        phone.start_node().unwrap();
        phone.start_node().unwrap();
        assert!(phone.is_running());
        phone.stop_node();
        assert!(!phone.is_running());
        phone.stop_node();
    }

    /// A core whose rules act on meetings, so calendar events have something to match.
    fn core_with_calendar_rule() -> Arc<Curfew> {
        Curfew::new(
            "timezone = \"UTC\"\n\n[[profiles]]\nid = \"deep-work\"\nname = \"Deep work\"\n\n\
             [[calendars]]\nid = \"meetings\"\nprofile = \"deep-work\"\n\
             [calendars.matcher]\nbusy_only = true\n"
                .into(),
        )
        .unwrap()
    }

    fn events_json(title: &str, busy: bool) -> String {
        serde_json::json!([{
            "id": "e1",
            "title": title,
            "calendar": "Work",
            "start": NOW + 600,
            "end": NOW + 4200,
            "busy": busy,
        }])
        .to_string()
    }

    #[test]
    fn a_meeting_only_one_device_can_see_reaches_the_other() {
        // The phone was never given calendar permission; the PC has the subscription. The phone is
        // still blocked during the meeting.
        let (phone, pc) = paired("calendar");
        pc.pass(
            core_with_calendar_rule(),
            NOW,
            String::new(),
            String::new(),
            events_json("Design review", true),
        )
        .unwrap();
        carry(&pc, &phone);

        let result: serde_json::Value = serde_json::from_str(
            &phone.pass(core(), NOW + 1, String::new(), String::new(), String::new()).unwrap(),
        )
        .unwrap();

        assert_eq!(result["calendar"][0]["title"], "Design review");
        assert_eq!(
            result["calendar"][0]["id"],
            serde_json::Value::String(format!("{}/e1", pc.device_id())),
            "an event did not say which device saw it",
        );
    }

    #[test]
    fn a_meeting_no_rule_here_cares_about_is_not_published() {
        // Minimal disclosure: the log carries the meetings that drive a block, not a transcript of
        // someone's week.
        let (phone, pc) = paired("calendar-quiet");
        pc.pass(
            core_with_calendar_rule(),
            NOW,
            String::new(),
            String::new(),
            events_json("Lunch", false),
        )
        .unwrap();
        carry(&pc, &phone);

        let result: serde_json::Value = serde_json::from_str(
            &phone.pass(core(), NOW + 1, String::new(), String::new(), String::new()).unwrap(),
        )
        .unwrap();

        assert_eq!(result["calendar"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn a_device_does_not_take_its_own_calendar_back() {
        let (_phone, pc) = paired("calendar-own");
        let result: serde_json::Value = serde_json::from_str(
            &pc.pass(
                core_with_calendar_rule(),
                NOW,
                String::new(),
                String::new(),
                events_json("Design review", true),
            )
            .unwrap(),
        )
        .unwrap();

        assert_eq!(result["calendar"].as_array().unwrap().len(), 0);
    }

    /// The whole point of the peer lock: the phone is locked, and only the PC can let it out. The
    /// user walks to the PC, presses the button there, and the phone opens on its next pass.
    #[test]
    fn a_lock_only_the_pc_can_open_is_opened_by_the_pc() {
        let (phone, pc) = paired("release");
        let (phone_core, pc_core) = (core(), core());

        // The phone has to know the PC's id to name it, which is exactly what pairing gave it.
        let pc_id = pc.device_id();
        let locked = serde_json::to_string(&Session {
            id: "p1".into(),
            profile: "deep-work".into(),
            source: SessionSource::Manual,
            started_at: NOW,
            lock: LockSet::new([Lock::PeerRelease { device_id: pc_id.clone() }], None),
        })
        .unwrap();
        phone_core.start_session(locked).unwrap();
        phone.pass(phone_core.clone(), NOW, String::new(), String::new(), String::new()).unwrap();
        carry(&phone, &pc);

        // The PC adopts the session and finds it is the device being asked.
        let adopted: serde_json::Value = serde_json::from_str(
            &pc.pass(pc_core.clone(), NOW + 1, String::new(), String::new(), String::new())
                .unwrap(),
        )
        .unwrap();
        assert_eq!(adopted["releasable"][0], "p1", "the PC was not offered the release");

        pc_core.release_peer("p1".into(), NOW + 2);
        pc.pass(pc_core, NOW + 2, String::new(), String::new(), String::new()).unwrap();
        carry(&pc, &phone);
        phone
            .pass(phone_core.clone(), NOW + 3, String::new(), String::new(), String::new())
            .unwrap();

        phone_core.end_session("p1".into(), NOW + 4, String::new()).expect("the PC said yes");
    }

    /// Until it does. A peer lock is not a countdown, and the device it names not having answered
    /// yet is the ordinary state of one.
    #[test]
    fn the_phone_stays_shut_until_the_pc_actually_answers() {
        let (phone, pc) = paired("release-waiting");
        let (phone_core, pc_core) = (core(), core());
        let locked = serde_json::to_string(&Session {
            id: "p1".into(),
            profile: "deep-work".into(),
            source: SessionSource::Manual,
            started_at: NOW,
            lock: LockSet::new([Lock::PeerRelease { device_id: pc.device_id() }], None),
        })
        .unwrap();
        phone_core.start_session(locked).unwrap();
        phone.pass(phone_core.clone(), NOW, String::new(), String::new(), String::new()).unwrap();
        carry(&phone, &pc);
        pc.pass(pc_core, NOW + 1, String::new(), String::new(), String::new()).unwrap();
        carry(&pc, &phone);
        phone
            .pass(phone_core.clone(), NOW + 2, String::new(), String::new(), String::new())
            .unwrap();

        assert!(phone_core.end_session("p1".into(), NOW + 3, String::new()).is_err());
    }
}
