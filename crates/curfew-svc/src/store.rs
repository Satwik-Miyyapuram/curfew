//! Where this machine keeps its sync identity, its peers and its log.
//!
//! Under `%ProgramData%` beside the config and the state, for the same reason they are: a standard
//! user must not be able to hand themselves a new identity, delete the peer that holds their locks,
//! or truncate the log. The service runs as LocalSystem and the directory is protected with it.
//!
//! Three files rather than one. The identity is the only secret and is written on its own so it can
//! be given tighter permissions and so a corrupt log never costs the device its name; peers and log
//! are the parts that legitimately change every few seconds.

use curfew_sync::device::Identity;
use curfew_sync::node::Shared;
use curfew_sync::oplog::Log;
use curfew_sync::pair::Peers;
use std::io;
use std::path::{Path, PathBuf};

/// The directory the three files live in.
pub fn root() -> PathBuf {
    match std::env::var("CURFEW_SYNC_DIR") {
        Ok(path) if !path.is_empty() => PathBuf::from(path),
        _ => {
            let data = std::env::var("ProgramData").unwrap_or_else(|_| "C:\\ProgramData".into());
            Path::new(&data).join("Curfew").join("sync")
        }
    }
}

/// Load what is there, generating an identity the first time. A log or peer file that will not
/// parse is a serious matter, so it is reported rather than silently replaced: the caller decides,
/// and for the service that decision is to carry on with what it could read and say so.
pub fn open(root: &Path, name: &str) -> io::Result<(Shared, Vec<String>)> {
    std::fs::create_dir_all(root)?;
    let mut complaints = Vec::new();

    let identity_path = root.join("identity.bin");
    let identity = match std::fs::read(&identity_path) {
        Ok(bytes) => match Identity::from_secret_bytes(name, &bytes) {
            Ok(identity) => identity,
            Err(e) => {
                // Refusing to start would leave the machine unenforced; generating a new identity
                // silently would drop every pairing without saying so. Neither is acceptable
                // quietly, so it is done loudly.
                complaints.push(format!(
                    "Curfew's sync identity was unreadable ({e}). A new one was made, and this \
                     device has to be paired again."
                ));
                let identity = Identity::generate(name);
                write(&identity_path, &identity.secret_bytes())?;
                identity
            }
        },
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            let identity = Identity::generate(name);
            write(&identity_path, &identity.secret_bytes())?;
            identity
        }
        Err(e) => return Err(e),
    };

    let peers: Peers = read_json(&root.join("peers.json"), &mut complaints, "paired devices");
    let log: Log = read_json(&root.join("log.json"), &mut complaints, "sync log");

    Ok((Shared::new(identity, peers, log), complaints))
}

/// Write the parts that change. The identity is not rewritten: it never changes, and a write that
/// cannot fail is a write that cannot corrupt it.
pub fn save(root: &Path, shared: &Shared) -> io::Result<()> {
    let peers = shared.peers.lock().expect("the peers lock is never poisoned");
    write(&root.join("peers.json"), &serde_json::to_vec(&*peers)?)?;
    drop(peers);
    let log = shared.log.lock().expect("the log lock is never poisoned");
    write(&root.join("log.json"), &serde_json::to_vec(&*log)?)
}

fn read_json<T: Default + serde::de::DeserializeOwned>(
    path: &Path,
    complaints: &mut Vec<String>,
    what: &str,
) -> T {
    match std::fs::read(path) {
        Ok(bytes) => match serde_json::from_slice(&bytes) {
            Ok(value) => value,
            Err(e) => {
                complaints.push(format!("Curfew's {what} file was unreadable ({e})."));
                T::default()
            }
        },
        Err(e) if e.kind() == io::ErrorKind::NotFound => T::default(),
        Err(e) => {
            complaints.push(format!("Curfew's {what} file could not be read ({e})."));
            T::default()
        }
    }
}

/// Temp file and rename, so a machine that loses power mid-write keeps the previous version rather
/// than half of the new one.
fn write(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let temp = path.with_extension("tmp");
    std::fs::write(&temp, bytes)?;
    match std::fs::rename(&temp, path) {
        Ok(()) => Ok(()),
        // Windows will not rename onto an existing file on every filesystem.
        Err(_) => {
            std::fs::remove_file(path).ok();
            std::fs::rename(&temp, path)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curfew_core::session::{Session, SessionSource};
    use curfew_core::{Lock, LockSet};
    use curfew_sync::oplog::Op;

    const NOW: i64 = 1_788_510_600;

    fn temp() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("curfew-store-{}", std::process::id()));
        let dir = dir.join(format!("{:?}", std::thread::current().id()));
        std::fs::remove_dir_all(&dir).ok();
        dir
    }

    fn a_session() -> Op {
        Op::Start {
            session: Box::new(Session {
                id: "s1".into(),
                profile: "deep-work".into(),
                source: SessionSource::Manual,
                started_at: NOW,
                lock: LockSet::new([Lock::DeviceCredential], Some(NOW + 3600)),
            }),
        }
    }

    #[test]
    fn a_first_run_makes_an_identity_and_keeps_it() {
        let root = temp();
        let (first, complaints) = open(&root, "this pc").unwrap();
        assert!(complaints.is_empty());
        let (again, _) = open(&root, "this pc").unwrap();

        assert_eq!(first.identity.id(), again.identity.id(), "the device changed name overnight");
    }

    #[test]
    fn a_lock_survives_a_restart() {
        let root = temp();
        let (shared, _) = open(&root, "this pc").unwrap();
        shared.record(NOW, a_session());
        save(&root, &shared).unwrap();

        let (restarted, _) = open(&root, "this pc").unwrap();

        let believed = restarted.replay(NOW + 1);
        assert_eq!(believed.sessions.running.len(), 1, "a restart lost a running lock");
    }

    #[test]
    fn an_unreadable_log_is_reported_rather_than_hidden() {
        let root = temp();
        let (shared, _) = open(&root, "this pc").unwrap();
        shared.record(NOW, a_session());
        save(&root, &shared).unwrap();
        std::fs::write(root.join("log.json"), b"{ this is not a log").unwrap();

        let (recovered, complaints) = open(&root, "this pc").unwrap();

        assert_eq!(complaints.len(), 1, "a damaged log was passed over in silence");
        assert!(recovered.replay(NOW + 1).sessions.running.is_empty());
        assert_eq!(
            recovered.identity.id(),
            shared.identity.id(),
            "a damaged log cost the device its identity"
        );
    }

    #[test]
    fn a_half_written_file_is_never_what_is_read_back() {
        // The rename is the whole point: the reader sees the old file or the new one, never a
        // prefix of the new one.
        let root = temp();
        let (shared, _) = open(&root, "this pc").unwrap();
        shared.record(NOW, a_session());
        save(&root, &shared).unwrap();
        for i in 1..20 {
            shared.record(NOW + i, Op::Launched { key: "chrome".into(), at: NOW + i });
            save(&root, &shared).unwrap();
            let (read_back, complaints) = open(&root, "this pc").unwrap();
            assert!(complaints.is_empty(), "a save left a file that could not be read");
            assert_eq!(read_back.replay(NOW + 100).sessions.running.len(), 1);
        }
    }
}
