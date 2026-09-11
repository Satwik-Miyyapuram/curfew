//! What the tray and the CLI are allowed to ask the service.
//!
//! The service runs as SYSTEM and the things talking to it do not, so this boundary is a privilege
//! boundary: everything expressible here is something an unprivileged user on this machine may do.
//! That is why there is no "stop enforcing" message and no "delete session" message. The only exits
//! are [`Request::End`], which the core refuses unless the lock's conditions are satisfied, and
//! [`Request::RequestRelease`], which starts the 24-hour wait and can never be shortened.
//!
//! Framing is one JSON object per line over a named pipe. Boring on purpose: the wire format of a
//! local control channel is not a place to be clever, and a line-delimited protocol can be driven
//! by hand when something has gone wrong.

use curfew_core::{Lock, Refusal, Session, Timestamp};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

/// The pipe the service listens on. `\\.\pipe\` is machine-local; it is never reachable over the
/// network, which is what makes an unauthenticated local protocol acceptable at all.
pub const PIPE_NAME: &str = r"\\.\pipe\curfew";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "request", rename_all = "snake_case")]
pub enum Request {
    /// What is running, what is blocked, and what is wrong.
    Status,
    /// Start a session by hand.
    Start {
        profile: String,
        seconds: u32,
        #[serde(default)]
        locks: Vec<Lock>,
    },
    /// End one, having satisfied its conditions. The service re-checks; a caller claiming to have
    /// satisfied a lock is not evidence that it did.
    End {
        id: String,
        #[serde(default)]
        satisfied: BTreeSet<Lock>,
    },
    /// End a session by proving ownership of the machine.
    ///
    /// The password is checked by the service with `LogonUser`, never by the caller: the point of
    /// [`Request::End`] refusing an unproven claim is lost if some other message accepts one. The
    /// pipe is machine-local and the process at the other end is SYSTEM, which is the same trust
    /// boundary the operating system's own credential prompt sits on.
    Unlock {
        id: String,
        username: String,
        #[serde(default)]
        domain: String,
        password: String,
    },
    /// End a session by presenting a physical tag: the payload of an NFC sticker or a QR code,
    /// typed or pasted here.
    ///
    /// The payload is checked against the config's fingerprints by the service, for the same
    /// reason [`Request::Unlock`] checks the password itself. A tag that is not one of the
    /// configured ones is not an error message worth being precise about: it says the lock is
    /// still shut, and nothing about which tags exist.
    Token { id: String, payload: String },
    /// "This device releases that session", for a `Lock::PeerRelease` naming this device.
    ///
    /// The release is announced through the op-log, where the signature makes the claim of
    /// authorship real. It cannot be withdrawn: a release a peer could take back would let one
    /// device shut a lock the user has already been told they are out of.
    Release { id: String },
    /// Start the 24-hour delayed release (GAPS D1). Returns when it lands.
    RequestRelease { id: String },
    /// Spend an emergency pass on one session, ending it whatever its lock says.
    ///
    /// The rationing is the service's to enforce, not the caller's: a tray that decided for itself
    /// whether a pass was available would be a tray that could decide there were more of them.
    /// Refused with [`Response::NoPass`] when there is none to spend.
    Emergency { id: String },
    /// Call off an announced freeze.
    ///
    /// This one message needs no lock satisfied and can never be refused on policy grounds, because
    /// a countdown has started nothing yet — the promise Curfew keeps is about sessions it is
    /// already running (GAPS B4).
    CancelFreeze,
    /// Agree, at this machine, to a freeze a paired device asked for.
    ConfirmFreeze,
    /// Re-read the config from disk.
    Reload,
    /// "The extension in this browser is alive." Sent by the native-messaging host on a timer.
    ///
    /// Unauthenticated, like everything else on this pipe, and that is a known limit rather than an
    /// oversight: anyone who can write here could forge a heartbeat and get their browser back
    /// without path rules being enforced. It buys them nothing below that layer — domains, apps and
    /// the lock itself are enforced by the service and are not reachable from here — which is
    /// exactly why the extension is a granularity layer and never the floor (GAPS G1).
    ///
    /// `url` is the page in the browser's focused tab, or nothing when no window of that browser
    /// is focused. It is what web budgets are charged against: the service cannot see a tab
    /// from where it runs, so time on a page is counted from the extension saying so.
    Beat {
        browser: String,
        #[serde(default)]
        url: Option<String>,
    },
    /// "This is the window the user is looking at." Sent by the tray every couple of seconds.
    ///
    /// The service runs in session 0, where `GetForegroundWindow` sees no desktop at all, so the
    /// only process that can answer the question is one in the user's session. Unauthenticated,
    /// like the heartbeat, and with the same limit: a program that lies here can under-charge an
    /// app budget. It cannot touch a block, a schedule or a lock.
    Seen { exe: String, title: String },
    /// "The user is opening this URL. May they?" The service decides; the extension only reports
    /// and obeys, so a tampered extension cannot invent an allow the core did not give.
    Check { browser: String, url: String },
    /// What the blocking has added up to over the last `days` local days.
    ///
    /// The service answers this rather than the window reading the state file for itself, even
    /// though the file is readable by the user: the service already holds the history in memory, it
    /// is the only writer, and a second reader of a file being rewritten under it is how two views of
    /// one fortnight start to disagree. Same reasoning as everything else on this channel.
    ///
    /// `days` is bounded by the caller and clamped by the core, so a bad number costs a smaller
    /// answer rather than a large allocation.
    Stats {
        #[serde(default = "default_stats_days")]
        days: u32,
    },
}

/// A fortnight, which is what `curfew stats` defaults to and what the Time page charts.
fn default_stats_days() -> u32 {
    14
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "response", rename_all = "snake_case")]
pub enum Response {
    /// Boxed: a status carries the whole visible world — every running session, every schedule
    /// due, every warning — and it is one of a dozen variants the rest of which are a word and a
    /// timestamp. Without the box every response everywhere would be as large as the largest.
    Status(Box<Status>),
    /// The figures for the Time page. Boxed for the same reason as [`Response::Status`]: a fortnight
    /// of day rows is the largest thing on this channel after the status itself.
    Stats(Box<curfew_core::stats::Stats>),
    Ok,
    /// Release lands at this instant, and not before.
    Release {
        at: Timestamp,
    },
    /// A freeze has been announced and will happen unless it is cancelled.
    Announced {
        countdown: curfew_core::Countdown,
    },
    /// The core said no. Carried through verbatim so the UI can explain exactly which condition is
    /// unmet, rather than saying "denied" and leaving the user guessing.
    Refused {
        refusal: Refusal,
    },
    /// No emergency pass could be spent, and why — a disabled hatch, a spent quota, or a cooldown
    /// with a time on it. Separate from [`Response::Refused`] because nothing about the session's
    /// own lock was the problem.
    NoPass {
        refusal: curfew_core::PassRefusal,
    },
    /// The answer to a [`Request::Check`].
    Verdict {
        blocked: bool,
        /// Said out loud on the block page. A page that says only "blocked" invites the reader to
        /// suspect a bug and go looking for the way round it.
        reason: Option<String>,
    },
    /// Something went wrong that is not a policy decision: a config that no longer parses, a
    /// profile that does not exist.
    Error {
        detail: String,
    },
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Status {
    pub now: Timestamp,
    pub running: Vec<Session>,
    pub blocked_domains: BTreeSet<String>,
    /// Executables the last pass wanted to close and could not. Shown, never hidden.
    pub failing: BTreeSet<String>,
    /// Executables the last pass closed. The tray watches this to know when to explain itself: a
    /// window that vanishes with no reason given is indistinguishable from a crash.
    pub closed: BTreeSet<String>,
    /// Browsers closed for having no extension answering for them while a path-level rule was in
    /// force. The tray says how to fix it, because this one is fixable.
    #[serde(default)]
    pub unwatched: BTreeSet<String>,
    /// Executables held behind a delay rule, and the seconds left of each wait.
    pub delayed: std::collections::BTreeMap<String, i64>,
    /// A freeze that has been announced and has not happened yet. Carried on every status so no UI
    /// can be showing a stale "nothing is about to happen" while the machine counts down.
    #[serde(default)]
    pub freeze: Option<curfew_core::Countdown>,
    /// Profile **ids** mapped to the names their owner gave them.
    ///
    /// Carried rather than looked up, because every consumer of this struct that prints a profile has
    /// the same problem and had all solved it the same wrong way: `Session.profile` is the id, so the
    /// tray overlay said *"Steam is blocked during distractions."* where the phone says
    /// *"Distractions"*. The starter config's id happens to read like a word, which is why this went
    /// unnoticed — with `deep-work` it would have been obvious immediately.
    ///
    /// The service already holds the config, so resolving it once here means the tray, the overlay,
    /// the extension and the command line all get the name without each re-reading a file that only
    /// the service knows the path of.
    #[serde(default)]
    pub profile_names: std::collections::BTreeMap<String, String>,
    /// Why website blocking is not working, if it is not.
    pub hosts_error: Option<String>,
    /// Set when the state file could not be read on startup. The user is owed this: it means locks
    /// may have been lost.
    pub state_warning: Option<String>,
    /// Set when the machine's clock has been moved, or when time was credited across a shutdown.
    ///
    /// The user is owed this for the same reason they are owed `state_warning`: both are cases where
    /// something a lock depends on is not what it appears to be. It is a sentence rather than a flag
    /// because the two cases mean different things — one is tampering that was caught, the other is
    /// ordinary downtime — and a UI cannot tell them apart from a boolean.
    #[serde(default)]
    pub clock_warning: Option<String>,
    /// **Set when a rule in force can no longer be decided** — P2-16.
    ///
    /// Window-title and keyword rules and app budgets need to know what is in front. That report comes
    /// from the tray, because the service runs in session 0 where there is no interactive desktop at
    /// all — so quitting or hiding the tray silently stops those rules being enforced while the session
    /// keeps running. This is the sentence that says so.
    ///
    /// A sentence rather than a flag for the same reason as `clock_warning`: which rules, and what to do
    /// about it, is the whole content, and a boolean cannot carry either.
    #[serde(default)]
    pub foreground_warning: Option<String>,
    /// **Whether any running session has a rule that needs the foreground window** — P2-16.
    ///
    /// `foreground_warning` says the gap has already opened. This says it *would*, which is what a
    /// surface needs in order to warn somebody before they cause it: the tray's "hide this icon" is the
    /// action that causes it, and a warning delivered afterwards is no use to the person deciding.
    #[serde(default)]
    pub needs_foreground: bool,
    /// Emergency passes that could be spent right now, and why not when the answer is none. Both
    /// on every status, so a UI never has to ask a second question to know whether to offer the
    /// hatch or to explain its absence.
    #[serde(default)]
    pub passes_left: u32,
    #[serde(default)]
    pub pass_refusal: Option<curfew_core::PassRefusal>,
    /// Sessions whose lock names *this* device as the one that may release it, so the tray can
    /// offer the button rather than the user having to know which device was named.
    #[serde(default)]
    pub releasable: Vec<String>,
    /// Sessions this device has already released. Kept so the button becomes a sentence — the
    /// release is given once and there is nothing further to press.
    #[serde(default)]
    pub released: Vec<String>,
    /// **What each running session's lock allows a surface to offer**, keyed by session id.
    ///
    /// P1-6: the tray worked this out from `conditions`, the window worked it out from
    /// `releasable`/`released` plus a string comparison, and neither offered `Challenge`. A dead
    /// `State.lock` field was described in the review as "where a shared release verdict belongs",
    /// which is what this is: [`curfew_core::LockSet::offers`] computed once by the service, so a
    /// surface renders what it is told instead of deciding for itself.
    ///
    /// Carried rather than left to the caller because one input — whether *this* device is the one a
    /// peer release names — is known only here.
    #[serde(default)]
    pub offers: std::collections::BTreeMap<String, curfew_core::Offers>,
    /// What this machine's sync is doing, as a fact rather than a promise.
    ///
    /// **Windows had no way to say this at all.** `Status` carried nothing about sync, so a user
    /// could not tell paired from unpaired even once pairing worked, and no page of the window
    /// mentioned it. The interaction review praises Android for stating sync truth in a five-way
    /// `when`, and the only Windows surface that mentioned sync was one that could not be reached.
    #[serde(default)]
    pub sync: SyncState,
}

/// Which of the five sync situations a machine is in.
///
/// A name rather than a sentence, so the window, the tray and the CLI can each write their own copy
/// and a test can assert the *distinction* rather than the wording. The five are separated because
/// they have five different next steps, not because the enum looked thin.
///
/// Serialized, and that is why it exists separately from [`SyncState`]'s four facts: the window
/// switches on this rather than re-deriving it from them, so the rule lives in **one** place. A second
/// copy in JavaScript would be free to drift from this one, and removing pairs of copies that drift is
/// what this branch has spent ten rounds doing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SyncPhase {
    /// Sync could not start and something is wrong: an unwritable sync directory, a log that will not
    /// parse. The next step is to fix it.
    Broken,
    /// Nothing is paired, which is not an error. The next step is to pair, if the user wants to.
    Unpaired,
    /// Devices are paired and the node is not up yet — the ordinary state for one pass after a pairing
    /// lands, because the service starts the node on the following pass rather than binding a listener
    /// the instant a peer appears. The next step is to wait.
    Idle,
    /// Listening, and no paired device is on this network. The next step is to wait: these devices talk
    /// when they are in earshot, not on a schedule.
    Waiting,
    /// Listening, with at least one paired device reachable. Working.
    Working,
}

/// What this machine's sync is doing right now.
///
/// Carried on every status rather than asked for separately, for the same reason the pass count is: a
/// UI that had to make a second request to know whether to mention sync would mention it late or not
/// at all.
///
/// `#[serde(default)]` is on the **struct**, not only on [`Status::sync`], and the difference was
/// found by the test below rather than by reasoning: with the attribute only on the field, a payload
/// carrying `"sync": {}` failed with `missing field 'running'`. The window and the service are separate
/// binaries and can be updated at different times, so every field here has to be optional on the wire.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(default)]
pub struct SyncState {
    /// Whether a node is listening. False is normal on an unpaired machine — see [`Self::why_off`].
    pub running: bool,
    /// Paired devices that have not been revoked.
    pub paired: usize,
    /// Paired devices reachable on this network right now.
    pub nearby: usize,
    /// Why there is no node, when there is none **and something is wrong**.
    ///
    /// `None` covers both "sync is up" and "nothing is paired yet", which are the two cases where
    /// there is nothing to report and nothing to do. [`Self::phase`] separates them, so a caller never
    /// has to infer a failure from a missing sentence.
    pub why_off: Option<String>,
}

impl SyncState {
    /// The phase, which is the whole public meaning of this struct.
    ///
    /// The order of the checks is the order of the exceptions: a failure outranks everything, because a
    /// machine that cannot sync must say so rather than report the ordinary idle state and leave the
    /// user waiting for something that will not happen.
    pub fn phase(&self) -> SyncPhase {
        match (self.why_off.is_some(), self.paired, self.running, self.nearby) {
            (true, ..) => SyncPhase::Broken,
            (false, 0, ..) => SyncPhase::Unpaired,
            (false, _, false, _) => SyncPhase::Idle,
            (false, _, true, 0) => SyncPhase::Waiting,
            (false, _, true, _) => SyncPhase::Working,
        }
    }
}

/// Hand-written so `phase` is **derived output rather than a stored field**.
///
/// A `phase` field on the struct could disagree with the four facts printed beside it — a status saying
/// "working" while `nearby` is zero. Deriving it at the moment of writing makes that unrepresentable:
/// there is one constructor of the JSON and it always computes the phase from the same values it is
/// about to write.
impl Serialize for SyncState {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut st = s.serialize_struct("SyncState", 5)?;
        st.serialize_field("running", &self.running)?;
        st.serialize_field("paired", &self.paired)?;
        st.serialize_field("nearby", &self.nearby)?;
        st.serialize_field("why_off", &self.why_off)?;
        st.serialize_field("phase", &self.phase())?;
        st.end()
    }
}
impl Status {
    /// What to call a profile in a sentence a person reads.
    ///
    /// **Every** surface that prints a profile name goes through this, and that is the point: the
    /// fallback has to be written once. Falling back to the id is deliberate — a profile deleted while
    /// a session from it is still running has no name to look up, and showing the id beats showing
    /// nothing, or panicking in a UI thread.
    /// The returned reference is either the name from [Self::profile_names] or the `id` handed in, so
    /// both are tied to the same lifetime — which in practice is always the status itself, since call
    /// sites pass `&session.profile` from a session inside it.
    pub fn name_of<'a>(&'a self, id: &'a str) -> &'a str {
        self.profile_names.get(id).map(String::as_str).unwrap_or(id)
    }

    /// The name of the profile the user is currently inside, if any.
    ///
    /// The overlay, the tooltip and the menu all want "which profile is this about", and they all want
    /// the first running session's — the one the user is actually in.
    pub fn running_name(&self) -> Option<&str> {
        self.running.first().map(|s| self.name_of(&s.profile))
    }
}

/// The control channel's name. Namespaced, so on Windows this is a named pipe under
/// [`PIPE_NAME`]'s directory, which is machine-local and never reachable over the network.
pub const SOCKET: &str = "curfew.sock";

/// Send one request to a running service and read the answer.
///
/// This lives beside the message types rather than in the service, because everything that talks to
/// the service — the command line, the tray, the overlay — needs it, and a second hand-written copy
/// of the framing is a second place for it to drift.
pub fn ask(request: &Request) -> std::io::Result<Response> {
    use interprocess::local_socket::traits::Stream as _;
    use interprocess::local_socket::{GenericNamespaced, Stream, ToNsName};
    use std::io::{BufRead, BufReader, Write};

    let name = SOCKET.to_ns_name::<GenericNamespaced>()?;
    let stream = Stream::connect(name)?;
    let mut reader = BufReader::new(stream);
    let line = format!("{}\n", serde_json::to_string(request)?);
    reader.get_mut().write_all(line.as_bytes())?;
    reader.get_mut().flush()?;
    let mut answer = String::new();
    reader.read_line(&mut answer)?;
    serde_json::from_str(answer.trim()).map_err(std::io::Error::other)
}

/// Parse one line from the wire.
pub fn parse_request(line: &str) -> Result<Request, String> {
    serde_json::from_str(line.trim()).map_err(|e| e.to_string())
}

/// Render a response as the single line that answers it.
pub fn encode(response: &Response) -> String {
    // A response that cannot be serialized would leave the caller hanging on a read forever, so
    // there is a last-resort line rather than a panic.
    let body = serde_json::to_string(response)
        .unwrap_or_else(|e| format!(r#"{{"response":"error","detail":"{e}"}}"#));
    format!("{body}\n")
}

#[cfg(test)]
mod sync_state_tests {
    use super::{SyncPhase, SyncState};

    /// The five phases, each pinned by the fact that distinguishes it.
    ///
    /// Windows had no sync state on the wire at all before this, so there was nothing to test and
    /// nothing to get wrong. Now there is one thing to get wrong, and it is the ordering: a machine
    /// that **cannot** sync must report `Broken` rather than the ordinary `Unpaired`, because
    /// `Unpaired` reads as "nothing to do here" and would leave a user waiting for a pairing that
    /// cannot happen. Each case below is the one its phase exists for.
    #[test]
    fn a_failure_outranks_every_other_state() {
        // Broken even while a node is up and a peer is in earshot: the reason outranks the rest.
        let s = SyncState {
            running: true,
            paired: 3,
            nearby: 2,
            why_off: Some("sync directory is not writable".into()),
        };
        assert_eq!(s.phase(), SyncPhase::Broken);
    }

    #[test]
    fn nothing_paired_is_not_an_error() {
        let s = SyncState::default();
        assert_eq!(s.phase(), SyncPhase::Unpaired);
        assert!(s.why_off.is_none(), "an unpaired machine has nothing to report");
    }

    /// One pass after a pairing lands: peers on disk, node not started yet. Ordinary, and it must
    /// not read as broken — the service brings the node up on the next pass by design.
    #[test]
    fn paired_but_not_listening_yet_is_idle_rather_than_broken() {
        let s = SyncState { running: false, paired: 1, nearby: 0, why_off: None };
        assert_eq!(s.phase(), SyncPhase::Idle);
    }

    #[test]
    fn listening_with_nobody_in_earshot_is_waiting() {
        let s = SyncState { running: true, paired: 2, nearby: 0, why_off: None };
        assert_eq!(s.phase(), SyncPhase::Waiting);
    }

    #[test]
    fn one_reachable_peer_is_working() {
        let s = SyncState { running: true, paired: 2, nearby: 1, why_off: None };
        assert_eq!(s.phase(), SyncPhase::Working);
    }

    /// `nearby` without `running` cannot happen, and the phase says the safe thing if it ever does:
    /// a count of reachable peers is not evidence that we are listening to them.
    #[test]
    fn a_reachable_count_does_not_imply_a_listener() {
        let s = SyncState { running: false, paired: 1, nearby: 3, why_off: None };
        assert_eq!(s.phase(), SyncPhase::Idle);
    }

    /// The state survives the wire, including the `Option`, because it is read by a separate process.
    #[test]
    fn it_round_trips_through_json() {
        let s = SyncState { running: true, paired: 2, nearby: 1, why_off: Some("a reason".into()) };
        let line = serde_json::to_string(&s).expect("serializes");
        assert_eq!(serde_json::from_str::<SyncState>(&line).expect("parses"), s);
    }

    /// And an older service that does not send the field at all still parses, as `Unpaired`.
    ///
    /// `#[serde(default)]` is why, and this is the test that keeps somebody from removing it: the
    /// window and the service are separate binaries and can be updated at different times, so a new
    /// window must not fail to read an old service's status.
    #[test]
    fn a_status_without_the_field_still_parses() {
        let s: SyncState = serde_json::from_str("{}").expect("absent field defaults");
        assert_eq!(s, SyncState::default());
        assert_eq!(s.phase(), SyncPhase::Unpaired);
    }
    /// The phase travels on the wire, and that is the assertion worth making about the hand-written
    /// `Serialize`: if it stopped emitting `phase`, the window would silently fall back to its default
    /// branch and every machine would report "no devices paired" while the Rust side said otherwise.
    #[test]
    fn the_phase_is_on_the_wire_and_agrees_with_the_facts() {
        let s = SyncState { running: true, paired: 3, nearby: 2, why_off: None };
        let v: serde_json::Value = serde_json::to_value(&s).expect("serializes");
        assert_eq!(v["phase"], "working");
        assert_eq!(v["paired"], 3);
        assert_eq!(v["nearby"], 2);

        // And a failure outranks the rest on the wire too, not only in `phase()`.
        let broken =
            SyncState { running: true, paired: 3, nearby: 2, why_off: Some("not writable".into()) };
        let v: serde_json::Value = serde_json::to_value(&broken).expect("serializes");
        assert_eq!(v["phase"], "broken");
    }
}
