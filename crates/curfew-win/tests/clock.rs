//! The trusted clock, on Windows.
//!
//! These are the tests that would have caught the cheapest bypass in the product. Before them, the
//! service read `SystemTime` in two places and used it to judge locks: the tick loop, and the
//! control channel that answers `Request::End`. Moving the system clock forward therefore ended
//! every timer lock — through `is_expired` on the next pass, or immediately through `can_release`
//! granting a release — and it left nothing behind to notice, because the ended session was written
//! to history as one that had genuinely run out.
//!
//! `curfew_core::clock` has had the answer since it was written, and Android has called it all
//! along. Nothing here is new logic; it is the Windows half finally being wired to it.

use curfew_core::{Config, Lock, Timestamp};
use curfew_win::ipc::{Request, Response};
use curfew_win::Enforcer;
use std::collections::BTreeSet;
use std::path::PathBuf;

const CONFIG: &str = r#"
schema_version = 1
timezone = "Europe/London"

[[profiles]]
id = "deep-work"
name = "Deep work"

[[profiles.rules]]
target = { kind = "windows_exe", exe = "steam.exe" }
action = { kind = "block" }
"#;

/// An arbitrary Friday lunchtime. The exact value does not matter; only that it is far from zero,
/// so a two-day jump below is unmistakably a jump.
const T0: Timestamp = 1_788_510_600;
/// A boot that has been up for a while, so `uptime` has somewhere to move from.
const UPTIME: i64 = 90_000;

fn enforcer(name: &str) -> (Enforcer, PathBuf) {
    let dir = std::env::temp_dir().join(format!("curfew-clock-{}-{name}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("hosts");
    std::fs::write(&path, "127.0.0.1 localhost\r\n").unwrap();
    let config = Config::from_toml(CONFIG).expect("the test config must parse");
    (Enforcer::new(config, path.clone()), path)
}

/// Start a one-hour timer lock and return its id, with the trusted clock settled at `T0`.
fn locked_timer(e: &mut Enforcer) -> String {
    let now = e.observe_clock(T0, UPTIME);
    assert_eq!(now, T0, "the first reading is taken on faith, so it must be returned verbatim");
    let answer = e.handle(
        now,
        Request::Start { profile: "deep-work".into(), seconds: 3600, locks: vec![Lock::Timer] },
    );
    assert_eq!(answer, Response::Ok, "the timer lock did not start");
    e.sessions.running[0].id.clone()
}

#[test]
fn a_clock_wound_forward_does_not_end_a_timer_lock() {
    let (mut e, _) = enforcer("forward");
    let id = locked_timer(&mut e);
    let ends_at = e.sessions.running[0].lock.ends_at.expect("a timer lock has an end");

    // Two days forward on the wall clock, five seconds forward on the uptime. Uptime cannot be set,
    // so the difference is proof the clock was moved rather than that time passed.
    let now = e.observe_clock(T0 + 2 * 86_400, UPTIME + 5);

    let verdict = e.clock_verdict.expect("a reading was taken");
    assert!(verdict.tampered(), "two days of unbacked wall clock was not treated as tampering");
    assert_eq!(verdict.refused_forward, 2 * 86_400 - 5);
    assert_eq!(now, T0 + 5, "the trusted clock followed the wall clock instead of the uptime");
    assert!(T0 + 2 * 86_400 > ends_at, "the test is not exercising the bug: the jump is too small");

    // …and the session is still running, because nothing has actually expired.
    assert_eq!(e.sessions.running.len(), 1);
    assert_eq!(e.sessions.running[0].id, id);
}

#[test]
fn a_clock_wound_forward_does_not_release_a_timer_lock_through_the_control_channel() {
    // The second door into the same room. `can_release` grants a release the moment `is_expired`
    // holds, so answering `End` with an untrusted instant ends a timer lock without the caller
    // claiming anything at all — which is why `serve` takes its time from the witness too.
    let (mut e, _) = enforcer("control-channel");
    let id = locked_timer(&mut e);

    let now = e.observe_clock(T0 + 2 * 86_400, UPTIME + 5);
    let answer = e.handle(now, Request::End { id: id.clone(), satisfied: BTreeSet::new() });

    match answer {
        Response::Refused { refusal } => {
            assert!(
                matches!(refusal, curfew_core::Refusal::Locked { .. }),
                "refused for the wrong reason: {refusal:?}"
            );
        }
        other => panic!("a wound-forward clock released a timer lock: {other:?}"),
    }
    assert_eq!(e.sessions.running.len(), 1);
}

#[test]
fn the_clock_only_ever_moves_forward() {
    // Winding the clock *back* is the other half of the same attack: it makes a fresh lock last
    // longer than it was set to, and it can pull a session out of the past. The trusted clock simply
    // does not go back, and the attempt is recorded rather than obeyed.
    let (mut e, _) = enforcer("backward");
    let _ = locked_timer(&mut e);

    let now = e.observe_clock(T0 - 7 * 86_400, UPTIME + 5);

    let verdict = e.clock_verdict.expect("a reading was taken");
    assert!(verdict.tampered(), "a week of missing wall clock was not treated as tampering");
    assert_eq!(verdict.refused_backward, 7 * 86_400 + 5);
    assert_eq!(now, T0 + 5, "the trusted clock went backwards");
    assert!(e.clock_warning().is_some(), "tampering was detected and then not reported");
}

#[test]
fn an_honest_restart_is_credited_and_not_called_tampering() {
    // The residual the design accepts: uptime restarts at zero, so an hour switched off and an hour
    // stolen in the firmware are the same observation. Refusing it would punish every honest
    // shutdown, so it is credited — and labelled, because it is not proof of anything.
    let (mut e, _) = enforcer("reboot");
    let id = locked_timer(&mut e);

    // A different boot, two hours later. Uptime has gone backwards, which is the signal.
    let now = e.observe_clock(T0 + 7200, 12);

    let verdict = e.clock_verdict.expect("a reading was taken");
    assert!(!verdict.tampered(), "an honest restart was reported as tampering");
    assert_eq!(verdict.unverified, 7200, "time across a reboot was not credited");
    assert_eq!(now, T0 + 7200);

    // Two hours is longer than the one-hour lock, so it has genuinely expired now — which is the
    // point: a real shutdown must end a timer, or a machine that was off would stay locked.
    e.observe_clock(T0 + 7201, 13);
    let answer = e.handle(now, Request::End { id, satisfied: BTreeSet::new() });
    assert_eq!(answer, Response::Ok, "a lock that outlived the shutdown was not released");

    let warning = e.clock_warning().expect("credited time was not reported");
    assert!(warning.contains("switched off"), "the wrong thing was reported: {warning}");
}

#[test]
fn the_baseline_survives_being_written_down_and_read_back() {
    // A baseline a restart reset is exactly what someone moving the clock is hoping for: stop the
    // service, wind the clock, start it again, and a fresh witness would take the new time on faith.
    let (mut e, _) = enforcer("persisted");
    let _ = locked_timer(&mut e);
    e.observe_clock(T0 + 100, UPTIME + 100);

    let json = serde_json::to_string(&e.clock).expect("the witness must serialize");

    // A new process, restoring only what was written down.
    let (mut fresh, _) = enforcer("persisted-restored");
    fresh.clock = serde_json::from_str(&json).expect("the witness must deserialize");
    fresh.boot_counter = e.boot_counter.clone();

    // It must still refuse the jump, which it can only do if it remembered where it was.
    let now = fresh.observe_clock(T0 + 2 * 86_400, UPTIME + 105);
    assert!(fresh.clock_verdict.expect("a reading was taken").tampered());
    assert_eq!(now, T0 + 105, "a restored witness trusted the wall clock");
}

#[test]
fn nothing_is_said_when_the_clock_behaves() {
    let (mut e, _) = enforcer("quiet");
    let _ = locked_timer(&mut e);
    e.observe_clock(T0 + 60, UPTIME + 60);
    assert_eq!(e.clock_warning(), None, "an ordinary minute produced a warning");
}
