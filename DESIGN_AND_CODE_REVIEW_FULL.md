# Design and Code Review — Curfew, in full

**Windows app · Android app · Web surfaces · Shared core · Packaging · CI**

Revision 2 · branch `installer-no-reboot` · commit `525e691` (`package: 0.1.4 x64 MSI, built from this branch`)
Supersedes the scope of `DESIGN_AND_CODE_REVIEW.md` (September 2026), which reviewed the core, sync,
the Windows service and the Android enforcer, and explicitly **excluded** the desktop window, the tray,
the CLI, the ICS parser, the browser extension, the design system, packaging and CI. Those are covered
here. The earlier document's findings are re-verified in §2 rather than repeated.

---

## How this review was done, and what you can trust

Everything below was produced by reading the code in this checkout. Nothing was built, installed or
run against a live machine except where stated.

| Evidence | Result |
| :--- | :--- |
| `cargo test --workspace` | **799 tests, 35 suites, 0 failures** |
| `cargo fmt --all --check` / `cargo clippy` | clean in CI configuration (`-D warnings`) |
| Android unit tests | configured in CI (`:policy:testDebugUnitTest :app:testDebugUnitTest`); not run here |

**Calibration note, stated up front.** This review was assembled from eight parallel deep reads plus my
own verification pass. Six candidate findings were **withdrawn after checking them**, and they are
listed in §9 rather than deleted, because a review that reports only what survives gives you no way to
judge its error rate. Two of the withdrawn claims were asserted as headline P1s and were simply wrong;
one was backwards. Treat any single finding here as a lead to verify, not as an oracle.

**Counterweight, so that note is not read as an excuse for vagueness:** the three P0s were each traced by
hand from configuration syntax to the last line of the exploit path — and P0-1 was found by a sub-review
rather than by me. It is the most severe bug in the repository, and it survived a prior review, 799 passing
tests, and a test that asserts the bypass is correct behaviour. The sub-reviews were wrong in both
directions, which is the argument for reporting the withdrawn items rather than dropping them silently.

Provenance is marked on each finding:

- **[V]** — I read the cited code myself and confirmed it.
- **[R]** — Reported by a sub-review; the cited code exists as described and the claim is internally
  consistent, but I did not independently reproduce the behaviour.

---

## 1. What this round adds

Eight areas appear in no previous review. Each produced findings:

| Area | Key files | Verdict |
| :--- | :--- | :--- |
| Desktop window + webview | `curfew-app/src/app.rs`, `ui/app.html` | Two real promise-breaking defects; good security posture elsewhere |
| Tray | `curfew-tray/src/*.rs` | Sound; the overlay has a multi-window bug |
| Windows service + state + IPC | `curfew-svc/src/*.rs`, `curfew-win/src/{state,ipc,delay,blocked}.rs` | **Three of the five P0/P1-0 bypasses live here** |
| CLI + schedule editor | `curfew-cli/src/schedule.rs` | Reports "Added" for windows the core discarded; deletions gated by nothing |
| ICS recurrence | `curfew-ics/src/lib.rs` | **Weakest component in the repo** — RFC 5545 partial, with fail-open cases |
| Browser extension | `extension/*` | One self-inflicted browser-kill loop |
| Android remainder | `ui/*` (except Auth/ChallengeDialog), `data/*`, `enforce/*` (except the 4 previously covered), `policy/*`, `res/*` | A second clock bypass, an unendable default lock, silent policy loss |
| Packaging + CI + design | `packaging/*`, `.github/workflows/*`, `design/*` | Unsigned APK shipped; docs contradict code |

The previous review's own two "open" rows are resolved in §2: the named-pipe SDDL **is** implemented, and the
OEM battery row is answered in the negative by §5 (the adaptive polling §10 promises is not built).

---

## 2. Verification of the previous review's status table

The September document's ten numbered findings were each re-checked against the code.

### Confirmed fixed

| # | Claim | Evidence in current tree |
| :-- | :--- | :--- |
| 1 | Android refuses biometrics | `Auth.kt:35` — `ALLOWED = BiometricManager.Authenticators.DEVICE_CREDENTIAL` |
| 2 | Budget no longer freezes in-app | `Enforcer.kt:98` `onTick`, driven every `CHARGE_MILLIS = 5_000` (`EnforcementService.kt:85,239-241`); `ACTION_SCREEN_OFF` registered at `:64` |
| 3 | Sync deadlock | locks taken per frame; `set_read_timeout` present in `lan.rs` |
| 4 | Session 0 cannot see the window | `Request::Seen` sent by the tray (`shell.rs:142`), consumed at `tick.rs:596` |
| 5 | Localised `netsh` parsing | `dns.rs:605-636` registry/PowerShell path with `netsh` fallback at `:794` |
| 6 | Hello PIN unverifiable | `prompt.rs:137` uses `CREDUIWIN_GENERIC`; `credential.rs:48` uses `LOGON32_LOGON_INTERACTIVE` |
| 9 | Typing challenge paste | `ChallengeDialog.kt:38-41` documents the dropped-edit rule; no selection toolbar at `:107` |
| 10 | Web budgets never accrued | `Request::Check` handled at `tick.rs:604` |

### Corrections to the status table

- **Row "Named pipe SDDL (§2.4 item 2) — Not verified, Open" is stale.** It is implemented:
  `crates/curfew-svc/src/runner.rs:284` defines `PIPE_SDDL = "D:(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;IU)"`,
  applied via `security_descriptor(descriptor)` at `:295`, with a test that the SDDL parses at `:502-510`.
  The DACL is well reasoned: `0x12019B` is generic read+write with `FILE_CREATE_PIPE_INSTANCE` (`0x4`)
  masked off, so an interactive user cannot create a second pipe instance and answer for the service.
  **However, the ACL is meticulous while the read loop it protects is naive — see P1-1.**
- **Row "OEM battery-optimisation prompt (§3.6 item 3) — Not verified, Open" remains open**, and is now
  answered in the opposite direction by §7: the design promises adaptive polling that the code does not do.
- **§1.4 (FFI panic safety) is substantively correct but for a different reason than stated.** The claim is
  that "all FFI boundaries encapsulate calls within `std::panic::catch_unwind`". There is **no**
  `catch_unwind` anywhere in `crates/curfew-ffi` (`git grep` returns nothing). It is nevertheless safe
  because UniFFI 0.28.3 wraps every generated call in `uniffi_rust_call`, which catches panics and converts
  them to a `RustCallStatus` error (`uniffi_core-0.28.3/src/ffi/rustcalls.rs:151-207`). The protection is
  real; the attribution is wrong, and it is worth knowing that it is a property of the binding generator
  and not of this crate — nothing here would survive a switch to hand-written FFI.

---

## 3. Scorecard: design invariants vs. the code as shipped

| Invariant (`docs/ARCHITECTURE.md` §0) | Status | Evidence |
| :--- | :--- | :--- |
| 1. No server | **PASS** | LAN + shared folder only; no remote endpoint |
| 2. A lock is a promise | **FAIL** | Three independent verified bypasses: **P0-1** (a timer lock ends on one pipe line, both platforms), **P0-2** (a clock change ends every timer lock on Windows), **P0-3** (stop/uninstall). Plus P1-3, P1-4 |
| 3. Degrade, never die | **PARTIAL** | Layer fallback is real; the extension path kills browsers instead (P1-2), and an unparseable config disables enforcement while still reporting locked (P1-10) |
| 4. The config is a file | **PASS with a hole** | Round-trips, but typos are silently ignored (P2-7) |
| 5. Local-first | **PASS** | No telemetry anywhere |
| §10 Honest downtime | **FAIL on Windows** | Implemented on Android (`Downtime.kt`, surfaced in `NowScreen.kt:192`); absent on Windows (P1-8) |
| §10 Adaptive cost | **FAIL** | Android polls at a fixed 30 s; no 15 s idle / stop-when-nothing-can-fire (P1-8) |
| §11 One gate, not two | **PASS** | Android `DEVICE_CREDENTIAL`; Windows `LogonUser` |
| §12 Distribution | **FAIL for Android** | The shipped APK is unsigned and uninstallable (P1-7) |
| *(not stated as an invariant)* Process integrity | **FAIL** | A user-writable `%ProgramData%\Curfew` executes a SYSTEM binary from it (P1-0) |

**Overall.** The core is the strongest part of this codebase and the platform edges are the weakest. The
policy engine, the lock lattice, the op-log and the budget accounting are carefully built and densely
tested; the work of ending a lock early is guarded in the engine and then bypassed in four separate
places outside it. That is the single structural theme of this review.

---

## 4. P0 — Critical

Three independent, verified ways to end a lock early. The first is the worst bug in the repository.

### P0-1. `Lock::Timer` is treated as a claimable condition, so one line on the pipe ends any timer lock **[V]**

This is the most serious defect found in this review, and it is present on **both** platforms.

`crates/curfew-win/src/tick.rs:55-62`
```rust
/// A confirmation dialog and a retyped passage happen entirely in the UI: there is no machine fact
/// underneath them, so the process that showed them is the only possible witness and refusing its
/// word would make those locks unusable rather than stronger. Everything else -- a password, a tag,
/// a reboot, a peer -- is checked by the service, and is therefore never taken on a caller's say-so.
fn claimable(lock: &curfew_core::Lock) -> bool {
    use curfew_core::Lock;
    matches!(lock, Lock::Timer | Lock::Confirm | Lock::Challenge { .. })
}
```
The identical function exists for Android at `crates/curfew-ffi/src/lib.rs:92-94`.

**The doc comment states the correct rule and the code breaks it.** The justification for the claimable
set is "there is no machine fact underneath them" — true of `Confirm` and `Challenge`, and precisely
**false** of `Timer`. A timer lock's entire meaning is the machine fact `ends_at`, which
`LockSet::can_release` already honours directly:

`crates/curfew-core/src/lock.rs:138-144`
```rust
pub fn can_release(&self, now: Timestamp, satisfied: &BTreeSet<Lock>) -> bool {
    if self.is_expired(now) {
        return true;
    }
    self.conditions.is_subset(satisfied)
}
```
So `Timer` needs no caller testimony to release — expiry grants it — while accepting testimony for it
lets a caller *assert* expiry.

**The chain, end to end:**
1. `Request::End` filters the caller's `satisfied` set through `claimable` — `tick.rs:453-456`:
   ```rust
   Request::End { id, satisfied } => {
       let mut satisfied: BTreeSet<curfew_core::Lock> =
           satisfied.into_iter().filter(claimable).collect();
       satisfied.extend(self.proven(&id, now));
       match self.sessions.end(&id, now, &satisfied) {
   ```
2. `Sessions::end` → `can_release(now, satisfied)` → `conditions.is_subset(satisfied)` → **true** for the
   set `{Timer}` (`session.rs:150`, `lock.rs:143`).
3. `Request::End` arrives on the control pipe, which `PIPE_SDDL` (`runner.rs:284`) deliberately opens to
   `BUILTIN\Interactive Users`.

**The exploit** needs no administrator, no UI and no tray:
```
echo {"request":"end","id":"win-...-deep-work-1","satisfied":[{"kind":"timer"}]} > \\.\pipe\curfew.sock
```

**This is the flagship configuration.** `crates/curfew-core/tests/golden/example.toml:75` is the documented
way to lock a weekday-morning deep-work window:
```toml
[[weekly]]
# ... start_minute = 540, end_minute = 720
locks = [{ kind = "timer" }]
```
and `curfew-cli/src/schedule.rs:356` maps the CLI's `timer` keyword to `Lock::Timer`. So the most common
lock in the product — "this window runs on a timer, I cannot end it early" — is the one that is trivially
removable.

**The test suite encodes the bug as intended behaviour.** `crates/curfew-win/tests/tick.rs:360-365`:
```rust
enforcer.tick(NOW, 0, &[], &table);
let id = enforcer.sessions.running[0].id.clone();
enforcer
    .sessions
    .end(&id, NOW + 600, &BTreeSet::from([Lock::Timer]))
    .expect("the timer had run out");
```
The fixture is a 09:00–12:00 window with `NOW = 09:30` (`tick.rs:43-47`), so this releases the lock at
09:40 while claiming the timer "had run out" — 8,400 seconds early. The assertion that follows only checks
that history was written, so the early release is never questioned. **A test that asserts the bypass is
worse than no test**: it will defend the bug against the fix.

The author was already defending against exactly this attack and stopped one variant short — the comment
at `tick.rs:450-452` says *"Without this filter, an `end` message listing `restart_required` as satisfied,
typed into the pipe by hand, would be every lock's way out."* The filter was built for `restart_required`,
`token`, `device_credential` and `peer_release` — and `timer` was added by mistake.

**Fix.** Remove `Lock::Timer` from `claimable` in both implementations:
```rust
matches!(lock, Lock::Confirm | Lock::Challenge { .. })
```
Nothing legitimate breaks: expiry is handled by `is_expired`, and the tray already relies on that path
rather than on claiming (`crates/curfew-tray/src/menu.rs:118` filters `Lock::Timer` out of the conditions
it reasons about). Then **fix the test** to assert the refusal, so the invariant has a guard:
```rust
assert!(enforcer.sessions.end(&id, NOW + 600, &BTreeSet::from([Lock::Timer])).is_err());
assert!(enforcer.sessions.end(&id, AFTER, &BTreeSet::new()).is_ok()); // expiry does release it
```

### P0-2. The Windows service never uses the trusted clock, so changing the clock ends every timer lock **[V]**

`docs/ARCHITECTURE.md:165-166` lists "change the clock" as in-scope, and `GAPS.md C6` mandates the
mechanism: *"persist `(boot_id, monotonic_at_write, wall_at_write)` on every heartbeat; on boot, a wall
clock earlier than the last observed wall clock is treated as tampering."* That mechanism exists, is
tested, and is used by Android. The Windows service does not use it:

`crates/curfew-svc/src/runner.rs:97-99`
```rust
pub fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}
```
`git grep -l ClockWitness -- crates/` returns only `curfew-core` (itself and its re-export), the core's
tests, and `curfew-ffi` (Android). Neither `curfew-svc`, `curfew-win` nor `curfew-tray` references it.
`Persisted` (`curfew-win/src/state.rs:16-46`) carries sessions, usage, launches, passes, boots,
boot_counter, releases, history and `last_tick` — **no clock witness**, so nothing about the clock
survives a restart either. `Enforcer::observe_boot` feeds only `BootCounter`, which is derived from uptime
and is a different thing.

**Consequence.** Setting the clock forward one day makes `Sessions::reap(now)` drop every running session;
`blocked_domains` empties; `hosts::apply` rewrites the hosts file with nothing in it. The ended session is
then persisted as history, so winding the clock back does **not** restore it — the lock is permanently
gone, and the statistics record it as having genuinely ended. On the common single-admin home PC this needs
no privilege escalation, because the signed-in user may change their own clock.

**Fix.** Add `#[serde(default)] pub clock: Option<curfew_core::ClockWitness>` to `Persisted`, build
`Reading { wall, uptime, boot_id }` each pass from `curfew_win::windows::uptime_seconds()`, take `now` from
the witness rather than `SystemTime::now()`, and persist the witness in `persist()` / restore it in
`build()` beside `boot_counter` (`runner.rs:146-147`). Surface `verdict.tampered()` through `Status` so the
user is told rather than silently unblocked.

> Note the shape these two P0s share: the core's guarantees are correct, and each platform is free to route
> around them. P0-1 routes around `can_release`; P0-2 declines to call `ClockWitness` at all. Both are held
> by convention rather than construction, which is why the tests caught neither.

### P0-3. An active lock does not survive Stop or Uninstall on Windows **[V]**

The product's central claim, from `README.md`: *"a lock cannot be ended early by uninstalling, by editing
the config, or by changing the clock."* It can be ended early by uninstalling or by stopping the service.

**The control handler accepts a stop unconditionally, while its comment says the opposite:**

`crates/curfew-svc/src/service.rs:43-49`
```rust
ServiceControl::Stop | ServiceControl::Shutdown => {
    // A shutdown is the machine going away and is always obeyed; the state file is what
    // carries the lock across the reboot. A stop is a request, and while a lock is held
    // it is refused.
    asked_to_stop.store(true, Ordering::SeqCst);
    windows_service::service_control_handler::ServiceControlHandlerResult::NoError
}
```
`NoError` **is** acceptance. The flag is stored regardless of whether a lock is running, and `runner::run`
polls that same flag (`runner.rs:331-334`), so the service exits on any stop request. `Stop` and
`Shutdown` are also conflated, though only `Shutdown` should be unconditional.

**The installer relies on that refusal existing:**

`packaging/windows/curfew.wxs:72-75`
```xml
<!-- Stopped before its own file is overwritten, because a running service holds its binary
     open and the copy would fail. Stopping is refused while a lock is running, which is the
     same refusal `curfew uninstall` gives and for the same reason. -->
<ServiceControl Id="StopCurfew" Name="Curfew" Stop="both" Wait="yes" />
```
`Stop="both"` makes this fire on install **and** uninstall, with `Wait="yes"`, so the MSI stops the service
and blocks until it is down. `curfew.wxs:127` then runs the deferred `"curfew.exe" uninstall`.

**And the uninstall guard fails open on the case the MSI creates:**

`crates/curfew-svc/src/main.rs:826-837`
```rust
match runner::ask(&Request::Status) {
    Ok(Response::Status(status)) if !status.running.is_empty() => {
        println!(
            "Curfew is not going to uninstall itself while a lock is running — that is what \
             you asked it for.\n\
             The session ends on its own, or `curfew release <id>` starts the 24-hour release, \
             after which this will work."
        );
        return 1;
    }
    _ => {}
}
match service::uninstall() {
```
The `_ => {}` arm is meant to catch "nothing is running". It also catches `Err(..)` — and because the MSI
has *just stopped the service*, the pipe connect fails, `ask` returns `Err`, and execution falls straight
through to `service::uninstall()`. `service.rs:238` then removes the service registration with no lock
check of any kind.

**Consequence.** `Settings → Apps → Curfew → Uninstall` succeeds during a locked session. Enforcement
stops, the firewall rules are revoked, and nothing survives to re-apply `%ProgramData%` state. The same
hole is reachable more cheaply: `sc stop Curfew`, the Services console, or Task Manager's "End task" on
the service lifts enforcement for as long as the operator likes, because the watchdog only decides
whether to *restart* a service it can see (`watchdog.rs:47`). Both `INSTALL.txt:83-90` and
`curfew.wxs:12-14` ("The uninstall is deliberately allowed to fail") describe protection that is absent.

**Fix.** Two changes, both small and both independent:
1. `service.rs:43` — split the arms. Keep `Shutdown` unconditional; for `Stop`, if any session is
   running, return `ServiceControlHandlerResult::Other(ERROR_SERVICE_CANNOT_ACCEPT_CTRL)`. `Wait="yes"`
   then makes the MSI's stop fail, which aborts and rolls back the uninstall — exactly what the `.wxs`
   comment already promises.
2. `main.rs:826` — replace the catch-all with an explicit refusal:
   ```rust
   Err(e) => {
       eprintln!("curfew: cannot confirm no lock is running ({e}); refusing to uninstall.");
       return 1;
   }
   ```
   This is the more important of the two: it fails shut, where the current code fails open.

---

## 5. P1 — High

### P1-0. `%ProgramData%\Curfew` is user-writable, and a SYSTEM process is executed from it **[V for the missing ACL; R for the exploit]**

The watchdog deliberately copies itself out of `Program Files` so the installer has one file to replace
instead of two, and spawns that copy as SYSTEM:

`crates/curfew-svc/src/watchdog.rs:151-153, 159-170`
```rust
pub fn image_path() -> PathBuf {
    state::default_path().with_file_name("curfew-watchdog.exe")
}
...
    let image = image_path();
    if let Err(e) = refresh(&exe, &image) {
        eprintln!("curfew: watchdog image not refreshed: {e}");
    }
    let from = if image.exists() { image } else { exe };
    std::process::Command::new(from).arg("watchdog").spawn()
```
and `refresh` (`:173-191`) decides whether to re-copy by comparing **length and modification time**:
```rust
let same = existing.len() == current.len()
    && match (existing.modified(), current.modified()) {
        (Ok(there), Ok(here)) => there >= here,
        _ => false,
    };
if same {
    return Ok(());
}
```

The module asserts `%ProgramData%\Curfew` "is administrator-owned, the same as the config and the state
file beside it" (`watchdog.rs:149-150`), and `runner.rs:191-194` repeats it for the sync directory — but
**no code anywhere in the repository sets that ACL**: a search for `icacls`, `SetNamedSecurityInfo`,
`DACL` or `set_acl` across `crates/` and `packaging/` returns nothing but those two comments.
`ensure_config`/`create_dir_all` (`runner.rs:73-82`) rely on inherited rights, and `C:\ProgramData` grants
`BUILTIN\Users` `Write` (container-inherited), which is `FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY`.

**Consequence.** `curfew-watchdog.exe` does not exist until the service first runs. An unprivileged user
who creates it first, padded to exactly the size of `curfew.exe` with a newer timestamp, makes `refresh`'s
test succeed so the copy is skipped and the service spawns the attacker's binary **as SYSTEM**. The same
inheritance lets a user pre-create `calendars/` (the ICS cache, `calendar.rs:123-129`) and
`dns-before.json` (`runner.rs:159-161`, consumed by `dns::give_back_remembered`).

**Fix.** Set the ACL explicitly at install time and verify it at start:
`icacls "<dir>" /inheritance:r /grant "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" "*S-1-5-32-545:(OI)(CI)RX"`.
Independently, never execute an unverified image: compare the candidate's hash against the running image
and spawn `current_exe()` when it does not match.

### P1-1. The control channel is a single-threaded loop with no timeout and no read cap **[V]**

`crates/curfew-svc/src/runner.rs:304-321`
```rust
/// Serve control messages until the process ends. One connection, one request, one line back.
fn serve(enforcer: Arc<Mutex<Enforcer>>) -> std::io::Result<()> {
    let listener = control_listener()?;
    for connection in listener.incoming() {
        let Ok(stream) = connection else { continue };
        let mut reader = BufReader::new(stream);
        let mut line = String::new();
        if reader.read_line(&mut line).is_err() {
            continue;
        }
```
Three properties compound:

1. **No read timeout.** `git grep set_read_timeout` over `ipc.rs`, `host.rs` and `runner.rs` returns
   nothing. `read_line` blocks indefinitely.
2. **Single-threaded, sequential.** `for connection in listener.incoming()` handles exactly one
   connection at a time; there is no thread per connection and no connection pool.
3. **Unbounded allocation.** `read_line` grows `line` without limit, and this runs inside a SYSTEM process.

Any interactive user may connect: `PIPE_SDDL` (`runner.rs:284`) deliberately grants `IU` write access,
which is correct for the design. So a client that connects and never sends a newline wedges the accept
loop **forever**, and every subsequent connection queues behind it.

**Consequence.** The tray, the Curfew window and the CLI all go blind ("The service did not answer"). More
seriously, `curfew release <id>` — the 24-hour last-resort exit that §11 of the architecture calls "what
separates a commitment device from a trap" — is issued over this same channel, so the user's documented
escape route is unreachable for as long as the wedge lasts. Combined with P0-3 (where an unreachable
service is read as "no lock is running"), wedging the pipe is also a step on the path to uninstalling.
The same loop lets a client exhaust the service's memory with one unbounded line.

**Fix.** Bound the read (`BufRead::take(MAX_REQUEST)` or a manual capped read) and handle connections
concurrently — a small thread pool, or accept + spawn with a cap. On oversize, answer
`Response::Error` and close rather than looping.

**A complication worth knowing before you plan the fix:** `set_read_timeout` is not available on these
streams. `interprocess`'s Windows named-pipe stream returns
`Unsupported, "named pipes do not support I/O timeouts"` for that call, so a per-connection deadline has to
be enforced another way — a supervisor thread that closes the stream after a deadline, a
`CreateNamedPipeW` with `PIPE_NOWAIT`, or a `WaitForSingleObject` on the pipe handle with a timeout around
the read. Do not write the obvious fix and assume it compiles and works; verify it on Windows.

> Worth noting as a design point: the file spends twenty lines reasoning about the DACL and one line on
> the loop that consumes the data. The threat model was applied to *who may connect* and not to *what a
> connected peer can do*.

### P1-2. A wrong browser-name guess makes the service hard-kill the running browser, repeatedly **[V]**

`crates/curfew-win/src/extension.rs:40-53` lists twelve browser executables. `extension/background.js:20-30`
can only ever emit six:

```js
function guessBrowser() {
  // Only used so the service can tell which browser is reporting; a wrong guess costs nothing
  // beyond the service crediting the heartbeat to a name that is not running, which fails safe.
  const agent = navigator.userAgent;
  if (agent.includes("Edg/")) return "msedge.exe";
  if (agent.includes("Firefox/")) return "firefox.exe";
  if (agent.includes("OPR/")) return "opera.exe";
  if (agent.includes("Vivaldi")) return "vivaldi.exe";
  if (agent.includes("Brave")) return "brave.exe";
  return "chrome.exe";
}
```

For `chromium.exe`, `librewolf.exe`, `waterfox.exe`, `zen.exe`, `arc.exe` and `opera_gx.exe` the user
agent still contains `Chrome/` or `Firefox/`, so the heartbeat is credited to `chrome.exe` or
`firefox.exe`. That name is not running, and the next process pass discards the beat:

`crates/curfew-win/src/extension.rs:97-99`
```rust
// A browser that has gone starts fresh next time: yesterday's heartbeat must not vouch for
// tomorrow's launch with the extension since removed.
self.beats.retain(|exe, _| running.contains(exe));
```
`crates/curfew-win/src/procs.rs:125-141`
```rust
let running: BTreeSet<String> = listing.iter().map(|p| p.exe.to_lowercase()).collect();
watch.saw(&running, now);
...
if unwatched.contains(&process.exe.to_lowercase())
    && !matches!(verdict, Verdict::Close { .. })
{
    if processes.terminate(process.pid) {
```

The **actually running** browser therefore stays untrusted, and `procs::enforce` terminates it once the
90-second grace (`GRACE_SECONDS`, `extension.rs:23-28`) expires — then again on the next launch, and the
next, for as long as a URL or keyword rule is active. Unsaved work is lost each time.

The comment's claim that this "fails safe" is wrong, and it contradicts the module's own guarantee at
`extension.rs:39`: *"the list being incomplete degrades granularity, never the floor."* Here it degrades
into killing the browser.

**Fix.** Stop trusting a self-reported name. In `curfew-svc/src/host.rs::run()`, walk the parent-process
chain with `sysinfo` (`Process::parent()` is already a dependency) to the first ancestor satisfying
`extension::is_browser`, and use that exe. Keep the guess only as a fallback, and interim-extend
`guessBrowser()` for Chromium / Opera GX / Arc while the host-side fix lands.

### P1-3. The FFI persistence ingress can remove a lock without proof **[R]**

`crates/curfew-ffi/src/lib.rs:579-583`
```rust
pub fn restore_sessions(&self, sessions_json: String) -> Result<(), CurfewError> {
    let sessions: Sessions = serde_json::from_str(&sessions_json).map_err(payload)?;
    *self.sessions.write().expect("sessions lock") = sessions;
    Ok(())
}
```
This whole-structure replacement is the only writer of that state, and it performs no lock check, no
proof check and no op-log entry. `restore_sessions(r#"{"running":[],"dismissed":{}}"#)` ends every running
session. The core is explicit that this should be impossible — `session.rs` states that every path which
could end a session early goes through `Sessions::end`, and there is exactly one of it — and
`crates/curfew-ffi/src/lib.rs:88-91` names the post-root threat model as the reason. A bypass one method
away from the guarded path defeats the guard.

The same shape appears across the crate (`restore_clock` at `:638-642` replaces the trusted `ClockWitness`
wholesale, `restore_boots` at `:461-467` accepts a `BootCounter` and `Boots` unvalidated,
`restore_passes` at `:528-532` is at least merged rather than replaced — the one instance that got it
right).

**Fix.** Split persistence from mutation. `restore_sessions` should accept only monotonic strengthening:
refuse any input that removes a session or weakens a `LockSet` against the currently held state (compare
via `LockSet::merge`), and reject payloads above a small size cap. `restore_clock` should never take a
witness value from the caller.

### P1-4. The emergency ration is bypassable at the library level **[V]**

`crates/curfew-core/src/session.rs:177-189`
```rust
pub fn end_with_pass(
    &mut self,
    id: &str,
    now: Timestamp,
    pass: crate::emergency::Pass,
) -> Result<Session, Refusal> {
    let _ = pass;
    let Some(session) = self.running.iter().find(|s| s.id == id) else {
        return Err(Refusal::NotRunning);
    };
    let satisfied = session.lock.conditions.clone();
    self.end(id, now, &satisfied)
}
```
`Pass` is publicly constructible (`emergency.rs:91-94` — `pub struct Pass { pub at: Timestamp }`), the
parameter is discarded (`let _ = pass;`), and the function then declares **every** condition satisfied.
`end_with_pass(id, now, Pass { at: 0 })` releases any lock, including `DeviceCredential`, `Token`,
`PeerRelease` and `RestartRequired`, with no quota, no cooldown and no `Passes` bookkeeping.

Both in-tree callers happen to earn the pass correctly (`tick.rs:559-564` spends through
`self.passes.spend(...)` first; `curfew-ffi/src/lib.rs:486-503` likewise) — so this is not a live bypass
today. It is a safety property that rests entirely on caller discipline, in the one function whose whole
purpose is to be the rationed escape hatch, and the module's own doc argues the ration "is the service's
to enforce, not the caller's" (`ipc.rs:69-73`). The type system should hold that, not a comment.

**Fix.** Take the ration by reference and verify membership before releasing:

```rust
pub fn end_with_pass(&mut self, id: &str, now: Timestamp, passes: &Passes, pass: &Pass) -> ...
    // refuse unless passes.used.contains(&pass.at)
```
or mint an unforgeable token from `Passes` alone. `#[must_use]` and non-`Clone` do not constrain a value
the caller can construct from nothing.

### P1-5. `[emergency]` is never validated, so a zero window disables the ration **[V]**

`Config::validate` (`crates/curfew-core/src/config.rs:418-...`) checks the timezone, profile ids and
names, zero budgets, zero launch limits, token ids and fingerprints, weekly minutes and days, schedule
profile references and calendar sources. `git grep "emergency" -- crates/curfew-core/src/config.rs`
returns only the field declaration, its default, and the unused `use` at line 5 — **`validate` never
inspects `self.emergency`.**

`crates/curfew-core/src/emergency.rs:122-131`
```rust
fn recent(&self, now: Timestamp, policy: &EmergencyPolicy) -> Vec<Timestamp> {
    let since = now - i64::from(policy.window_seconds);
    self.used.iter().copied().filter(|at| *at > since).collect()
}
```
With `window_seconds = 0`, `since == now`, `recent` is always empty, `QuotaSpent` and `CoolingDown` can
never fire, and `remaining()` always reports the full quota: the escape hatch becomes unlimited. With
`cooldown_seconds = 0` the documented "minimum gap between two uses" (`emergency.rs:43-46`) disappears.

This is exactly the class of config that `validate`'s own doc-comment says it exists to catch — *"a config
that would behave surprisingly is rejected at load, where the user can still see why, rather than at 4am
inside a lock"* — and the zero-budget and zero-launch-limit refusals at `:434-446` are the precedent.

**Fix.** In `validate`, reject `emergency.window_seconds == 0`, and reject `emergency.cooldown_seconds == 0`
when `passes > 0`. Same class: reject `Refill::Rolling { seconds: 0 }`, which makes `used_since(Some(now))`
match nothing and the budget unenforceable.

### P1-6. The Curfew window cannot reach the last-resort exit, and its one release button is the wrong one **[V]**

`docs/ARCHITECTURE.md:211-214` promises: *"every lock, at every strictness level, can be released by
starting a 24-hour delayed release… visible from the moment the lock starts."* The tray honours this
(`menu.rs:180-189`). The main window does not.

`crates/curfew-app/ui/app.html:248-264`
```js
const releasable = (s.releasable || []).includes(session.id);
const released = (s.released || []).includes(session.id);
const delayed = session.lock?.delayed_release_at ?? null;
...
${released ? '<span class="pill ok">Released by this device</span>' :
  releasable ? `<button class="btn ghost" data-act="release" data-id="${esc(session.id)}">Release it</button>` : ""}
```
`Status::releasable` is defined at `ipc.rs:186-189` as *"Sessions whose lock names **this** device as the
one that may release it"*, and `tick.rs:182-192` confirms it is populated only for `Lock::PeerRelease`
naming this device. So for a `DeviceCredential`, `Token`, `Challenge` or `RestartRequired` lock the window
renders **no release affordance at all**. `delayed_release_at` is read at `:250` only for a caption and can
never become non-null from this UI.

A user who locked a session with their Windows password and has hidden the tray — an option the tray
itself presents as harmless — has no way to start the last-resort exit from the product's primary surface.

Two related defects in the same flow:
- The button that *is* drawn is labelled "Release it" and sends `{ request: "release" }`, which
  deserializes to `Request::Release` — the **immediate, irrevocable peer release** (`ipc.rs:61-66`). It is
  rendered without the confirmation dialog the tray uses (`shell.rs:307-321`), and `Request::Release`
  "cannot be withdrawn".
- The dead `case "frozen"` branch at `:168` can never fire: `Refusal` has exactly two variants,
  `NotRunning` and `Locked` (`session.rs:64-78`).

**Fix.** Drive the release control from the same predicate the tray uses — conditions non-empty and
`delayed_release_at === null` — rendering "Start the 24-hour release" through `Request::RequestRelease`,
and give the peer release a confirm sheet. Delete the dead branch or add `Refusal::Frozen`.

> This is one instance of a broader pattern: **three independent UIs each re-implement which locks are
> actionable**, in three different ways. `menu.rs:117-165` routes `DeviceCredential` and `PeerRelease`;
> `app.html:449-461` routes two variants *by comparing display strings*; neither routes `Challenge`,
> though `Lock::Challenge` exists in the core (`lock.rs:44-45`) and Android implements it.
> `State.lock` is populated by both enforcers (`tick.rs:262`, `curfew-ffi/src/lib.rs:294`) and read
> by neither consumer — a dead field where a shared `release` verdict belongs. See §7.

### P1-7. Android's own release APK is unsigned and cannot be installed **[V]**

`docs/ARCHITECTURE.md:227-228`: *"**Android**: GitHub Releases (APK) and F-Droid as reference channels…
The sideloaded build is the reference build."* There is no release to sideload.

`android/app/build.gradle.kts:31-37`
```kotlin
release {
    // R8 shrinks the app but must not strip the accessibility service or the JNA bindings;
    // both are named in proguard-rules.pro.
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```
There is no `signingConfig` block anywhere in the file, so the release variant is built unaligned and
unsigned. `.github/workflows/release.yml:127-143` then publishes it as `curfew-android-unsigned.apk`,
renaming the Gradle output to "unsigned" — which is honest, but means the artifact cannot be installed by
any user through any normal route without first signing it themselves.

**Fix.** Either generate a debug-style self-signed release key in CI and sign (`apksigner`) so the APK
actually installs, documenting that users should verify the certificate; or state in `ARCHITECTURE.md`
§12 that the reference Android build is source + F-Droid only, and that the published APK is an unsigned
artifact for downstream signing. The current text promises a channel that does not work.

### P1-8. Windows has no honest-downtime reporting; Android's polling is not adaptive **[V]** — design conformance

`docs/ARCHITECTURE.md:189-190`: *"**Honest downtime**: if enforcement was down (reboot, force-stop,
revoked permission, OEM killer), the next launch reports the exact window it was down. No silent failure."*

Android implements this: `Downtime.kt`, `CurfewRuntime.kt:671-682` (`detectDowntime`), surfaced in
`NowScreen.kt:192,433-450`. **Windows has nothing**: `git grep -i downtime -- crates/` finds only
`clock.rs:51` and tests, all about clock credit. A Windows service that was killed, refused to start, or
crashed leaves no record the user can see, and `Status` carries no downtime field (`ipc.rs:154-194`).

`docs/ARCHITECTURE.md:194-195`: *"**Adaptive cost**: pollers run at 1s while a session is active, 15s
idle, and stop entirely when no rule can fire."* Android polls at a fixed cadence:
`EnforcementService.kt:182` `delay(POLL_MILLIS)` with `POLL_MILLIS = 30_000` (`:239`) and
`CHARGE_MILLIS = 5_000` (`:241`). There is no idle backoff and no stop-when-nothing-can-fire, so the
documented battery strategy is not the one implemented — and `GAPS.md A4` sets a measured budget of
<3%/day against it.

**Fix.** Add `down_since`/`last_tick` to `curfew_win` state and a `downtime` field to `Status`, rendered
on the "Is it working" page and the tray tooltip. For Android, gate the 5 s charge loop on an active
profile and back the idle poll off, or amend §10 to describe what is actually built.

### P1-9. Deleting two files releases every lock *and* switches the watchdog off **[R]**

`crates/curfew-win/src/state.rs:82-94` distinguishes a missing main file with a readable backup
(`Recovered`), an unreadable backup (`Lost`), and **both missing — which it reports as `Fresh`**, i.e. "a
first run", with `warning = None` in `runner::build`. The comment asserts the deletion case is "answered
the same way" as a crash, but a crash mid-write leaves the backup; a deliberate deletion of two files
produces the one state that is silently benign.

The watchdog reads the same file as its oracle:
`crates/curfew-svc/src/watchdog.rs:65-71`
```rust
pub fn locks_running(state_path: &Path) -> bool {
    match state::load(state_path) {
        Loaded::Ok(state) | Loaded::Recovered { state, .. } => !state.sessions.running.is_empty(),
        Loaded::Fresh => false,
        Loaded::Lost { .. } => true,
    }
}
```
`Fresh` means "not locked", so `decide(Seen::Stopped, false)` returns `Action::Retire` (`watchdog.rs:57`):
the watchdog **quits**, the service is never restarted, and the machine is left entirely unprotected with
no trace. `Loaded::Lost` is handled loudly and correctly; the pair-deletion path is the hole.

**Fix.** Keep an out-of-band witness that deleting the state files cannot erase — a sentinel file or an
`HKLM\SOFTWARE\Curfew` value created when the first session starts and removed when none are running — and
report `Fresh` only when that is also absent. Point both `build()` and `locks_running()` at it.

### P1-10. An unparseable config while locks are running turns enforcement off (fail open) **[R]**

`crates/curfew-svc/src/runner.rs:129-139`
```rust
let config = match config {
    Ok(config) => config,
    Err(detail) if persisted.sessions.running.is_empty() => return Err(detail),
    // Locks are running and the config is gone. Nothing here can be enforced by rule any more,
    // but the sessions still exist and still have to be honoured, so the service starts with an
    // empty config and says so rather than quietly releasing them.
    Err(detail) => {
        eprintln!("curfew: config unreadable while locks are running ({detail})");
        Config::default()
    }
};
```
`Config::default()` has no profiles, no weekly windows, no calendars and the resolver off, so the first
pass computes an empty domain set and calls `hosts::apply(path, ∅)`, stripping every block, while no
process rule can match. The session still reports as running — so `curfew status` says locked, the
uninstall guard still refuses, and the watchdog keeps restarting — **while nothing at all is enforced for
the rest of the lock.** Truncating `curfew.toml` by one byte produces this. Note the asymmetry:
`Request::Reload` gets it right, keeping the old config on a parse error (`tick.rs:619-627`); only
`build()` fails open.

**Fix.** Persist the last successfully parsed config alongside the state and restore that; at minimum, do
not call `hosts::apply` with an empty set while a lock is running, and refuse to start rather than start
unenforcing.

### P1-11. A blocking calendar fetch runs under the enforcer mutex, and a *failing* feed is retried every 2 s **[R]**

`crates/curfew-svc/src/runner.rs:394-399` performs `feeds.events(...)` while holding
`enforcer.lock()`, and that same mutex is what `serve()` needs to answer anything (`:315`). `feeds.rs:16-18`
sets `TIMEOUT = 20 s` against `TICK = 2 s` (`runner.rs:27`), so one slow subscription stalls the control
channel — including `Status` and the 24-hour `release` — for up to twenty seconds.

Worse, a fetch that **fails** never advances `cached.at` (`calendar.rs:165-168`), so `due` stays true and a
blackholed URL is retried every pass: roughly one 2-second enforcement pass in every 22 seconds, with the
mutex held for most of it. `restore()` marks every source due on each start (`calendar.rs:118-129`), so
this is also the state after every watchdog restart. The doc comment shows the author saw the risk; the
constant chosen does not address it.

**Fix.** Move `Feeds` to its own thread and have the tick read the last snapshot; add exponential backoff on
failure (2 s → 30 s → 5 min); reduce `TIMEOUT` to about one tick.

### P1-12. The service has no logging sink, so every diagnostic it emits is discarded **[R]**

An SCM-started service has null standard handles, and Rust's std documents this as *silent success* —
`eprintln!` writes nothing. The service emits diagnostics on roughly twenty paths (state-save failure,
config unreadable, calendar fetch failure, hosts failure, sync failure, watchdog spawn failure), and none
reach any log, event log or file. This directly contradicts §10's "no silent failure" and the project's own
rule at `main.rs:591-592`: *"a blocker that quietly fails to block is worse than one that admits it."*

**Fix.** Add a real sink — a rolling `%ProgramData%\Curfew\curfew.log` behind a single `Mutex<File>`
helper, or `RegisterEventSourceW`/`ReportEventW` (both already available through `windows-sys`).

### P1-13. A session does not hold its own rules, so editing the config removes enforcement while the lock survives **[V]**

`docs/GAPS.md:169` states the rule plainly: *"edits that would weaken an active session are refused
outright until the lock ends."* They are not refused, and the reason is documented incorrectly in two
places.

`crates/curfew-core/src/config.rs:299-300`
```rust
/// As everywhere else, a session this profile started keeps running. Its rules are gone from
/// the config, but the session holds its own copy of what it blocks.
```
`crates/curfew-ffi/src/lib.rs:153-159`
```rust
/// Replace the config. Running sessions are untouched: a config edit is not a way out of a
/// lock, and the session already holds its own copy of what it promised.
pub fn set_config(&self, config_toml: String) -> Result<(), CurfewError> {
    let config = Config::from_toml(&config_toml)
        .map_err(|e| CurfewError::Config { detail: e.to_string() })?;
    *self.config.write().expect("config lock") = config;
    Ok(())
}
```
**The claim is false.** A `Session` is `{ id, profile, source, started_at, lock }`
(`crates/curfew-core/src/session.rs:47-54`) — it holds a profile **id** and its `LockSet`, not the
profile's rules. `decide` resolves rules through `config.profile(session.profile)` at decision time, so
removing a rule from a running profile removes the enforcement while leaving the lock — and therefore the
"locked" status, the uninstall refusal and the watchdog — completely intact.

`curfew unblock` demonstrates it with no lock check and no proof (`crates/curfew-cli/src/schedule.rs:633-651`):
```rust
let mut cfg = load(path)?;
if cfg.profile(&profile).is_none() {
    return Err(format!("no profile {profile:?} in {path}"));
}
// Every rule on that target goes, on every platform: ...
let removed = cfg.remove_rule(&profile, &target);
```
The same is reachable through `set_config` from the app process on Android, which is exactly the
"reachable from any code in the app process — and on a rooted device, from outside it" threat model the
FFI crate names for itself at `lib.rs:88-91`.

**Consequence.** The user (or anything else on the machine) runs one command, and the session still
advertises itself as running and locked — `curfew status` lists it, the tray shows a countdown, the window
shows "is running" — while nothing is blocked. This is the same failure shape as **P1-10** by a different
route, and it is worse for diagnosis, because nothing at all fails: every surface agrees the lock is
holding.

**Fix.** Either make the claim true — snapshot the resolved rules into the session at start, which is the
honest reading of "holds its own copy of what it blocks" and also makes enforcement independent of later
edits — or enforce D6 directly: have `set_config`, `remove_rule` and `remove_weekly` refuse any edit that
reduces what a currently running session blocks, until that session ends. The first is a small change to
`Session` and removes the class; the second is a validation pass over the delta.

---

## 6. P2 — Medium

### P2-1. The webview re-reads and re-parses the whole config from disk every second **[V]**

`app.html:176-192` calls `readConfig()` on every `refresh()`, and `refresh` runs from `setInterval(refresh, 1000)`
(`:527`). `app.rs:71-84` handles that call with `read_to_string` + `Config::from_toml` + `serde_json::to_string`
of the whole document, per second. A full TOML read, parse, validate and re-serialize every second, on a
laptop, to drive a clock that needs only `status.now`. It also means the Plan and Apps pages can flash
"The config could not be read" if a service-side save lands mid-read.

**Fix.** Read the config on start, on navigation to `plan`/`apps`, and after an action that changes it —
not on the clock tick.

### P2-2. `draw()` replaces the whole body every second, destroying scroll, hover and selection **[V]**

`app.html:421-435`: `document.getElementById("body").innerHTML = ...` on every tick, over a scrolling
container (`:25`). `scrollTop` resets each second and text selection is cleared, so on the "Is it working"
page a user cannot reach item five or copy a failure message — the page exists to be read and reported
from. `user-select:none` is set globally at `:15` with no exception for the content area.

**Fix.** Patch only the volatile parts (a dedicated element for the countdown, updated with `textContent`)
and redraw fully only on real state changes. At minimum preserve `scrollTop` across the redraw and allow
selection in `.body`.

### P2-3. `refresh()` has no ordering guard, so a stale status can overwrite a fresh one **[V]**

`refresh` is fired every second *and* awaited after every action (`:453, 502, 510`), performing two
sequential awaits and then assigning shared `state`. Two overlapping runs complete in arbitrary order.
After a successful "End it early", an in-flight older refresh can repaint the running-session card for a
session the service already ended, so the button looks broken and the user presses it again.

**Fix.** A generation counter captured at entry; drop the apply if a newer refresh has started.

### P2-4. The password sheet in the window reads `window.__curfewUser`, which is never assigned **[V]**

`app.html:477`
```html
<input id="user" placeholder="User name" value="${esc(window.__curfewUser || "")}">
```
`git grep __curfewUser` across the repository returns exactly one hit: this read. The only script the host
evaluates is `window.__curfewReply` (`app.rs:89-96`); there is no initialisation script. The username field
therefore always opens empty, so a user who types only their password gets a refusal from a correct
password — and `prompt.rs:60-68` documents the opposite decision ("Prefilled so the user types only the
password").

**Fix.** Prefill from the host over the existing bridge (add a `Call::Identity`), or drop the read and
make the expected shape visible in the placeholder.

### P2-5. Control flow is decided by comparing display strings **[V]**

`app.html:455-457`
```js
if (missing.some((l) => describeLock(l) === "asks before ending")) return confirmEnd(id, missing);
if (missing.some((l) => describeLock(l) === "your Windows password")) return password(id);
```
`describeLock` (`:124-136`) is a presentation function whose output is compared with `===` to choose which
exit flow runs. Only `confirm` and `device_credential` are routed; the tray does the same routing
*structurally*, on `Lock::DeviceCredential` (`menu.rs:124`). A copy edit or a localised build silently
turns "End it early" into a dialog that can never end anything.

**Fix.** Add `const lockKind = (l) => (typeof l === "string" ? l : l.kind)` and route on that.

### P2-6. The window draws its own password box, which `prompt.rs` exists to prevent **[V]**

`app.html:473-483` renders an `<input type="password">` in the webview; `prompt.rs:1-15` states the rule
plainly: *"Curfew never draws a password box of its own… a user can tell it from a phishing box drawn by
an application."* The tray follows it via `CredUIPromptForWindowsCredentialsW`; the window does not, and
the sheet's copy ("Windows checks it, not Curfew") is misleading about who drew the field. The product
teaches two different password rituals, and the one in the larger window is indistinguishable from a
credential-phishing box.

**Fix.** Route the window's unlock through a host-side `Call::Credential` that runs the OS prompt against
the app's HWND and returns only the fields, so the page never sees a password input.

### P2-7. Config typos are silently ignored **[R]**

`git grep deny_unknown_fields -- crates/` returns nothing. `[[weekly]] lockss = [...]` (a transposition)
loads successfully with no locks at all, so the window runs a session the user believes is locked and can
end with one tap. `curfew-ffi/src/lib.rs:133-134` promises the opposite: *"a config we cannot fully
understand is refused so it can never be written back with the user's rules missing."*

**Fix.** Add `#[serde(deny_unknown_fields)]` to `Config`, `WeeklySchedule`, `CalendarSchedule`, `Profile`,
`Rule`, `Action` and `Refill`, or capture unknown keys and surface them as a load error naming them.

### P2-8. ICS recurrence is partial, with fail-open cases **[V for the code; R for the observed output]**

`crates/curfew-ics/src/lib.rs`. The parser is the weakest component in the repository. Verified in the
source:

- **A bare date-form `DTSTART` is not treated as all-day.** `is_all_day` (`:148-151`) tests only the
  `VALUE=DATE` parameter, never the `YYYYMMDD` value form that RFC 5545 also permits. When the two
  disagree, `span` yields a zero-length event, and an all-day entry with no `DTEND` — the ordinary
  "Holiday"/"Leave" entry — fails the overlap test and is **dropped entirely**. A calendar rule silently
  stops matching leave days.
- **Negative `BYMONTHDAY` is discarded and an empty list means "unconstrained".** `:512-515` parses
  `u32`, so `-1` fails and `filter_map` drops it; `:539` treats an empty vector as no constraint. The same
  applies to `BYDAY=2FR`, where `weekday()` returns `None` and the token vanishes.
- `UNTIL` with a DATE value is parsed oddly (`:496-497`), and `WEEKLY;INTERVAL=n;BYDAY=…` falls through to
  `return None`, which `expand` turns into a stop rather than a refusal — so a rule fires its first
  occurrence and never again.

The module doc says unsupported recurrence should make an event *non-recurring*; the code's actual
behaviour after a dropped token is to widen the match, which is the fail-open direction.

**Fix.** Derive `is_all_day` from the value form as well as the parameter; parse `BYMONTHDAY` as `i32` and
resolve negatives against the month length; refuse (produce nothing) when a `BYDAY` token fails to parse
rather than leaving the list empty; and share one `local_instant` helper with `budget.rs`, which resolves
`minute == 1440` differently (`schedule.rs` → next-day 00:00, `budget.rs:116` → 23:59).

### P2-9. A feed that returns a valid-but-empty calendar releases the blocks it was driving **[R]**

`crates/curfew-win/src/calendar.rs:171-179` treats any successful parse as authoritative and overwrites
the cached copy, where success only means the text contained `BEGIN:VCALENDAR` (`curfew-ics/src/lib.rs:55-57`).
A provider returning an auth-expiry placeholder or truncated export therefore replaces the last-good copy,
`still_serving` becomes true, and every calendar-driven session ends. This is fail-open on precisely the
threat the module was written around (`calendar.rs:9-11` states the opposite guarantee).

**Fix.** Treat a document that parses to zero events as non-authoritative when a cached copy exists: keep
serving the cache and report a failed outcome. The false positive (a genuinely emptied calendar keeps
blocking until the source is removed) is the correct direction for a lock.

### P2-10. `curfew add-window` reports "Added" for a schedule the core discarded **[R]**

`Config::upsert_weekly` (`config.rs:331-349`) dedupes on profile/days/times and returns `Ok(())` **without
inserting** when an equivalent row with a different id exists. `crates/curfew-cli/src/schedule.rs:404,412-414`
prints `"Added"` based on whether the *id* was previously present, so the user is told a lock was created
when none was. On a self-binding tool that is the worst possible lie.

**Fix.** Verify the insert actually happened and error naming the existing window's id, or narrow the
core's dedupe to byte-identical windows including `locks` and `enabled`.

### P2-11. `curfew remove` weakens a live commitment with no lock check **[R]**

`crates/curfew-cli/src/schedule.rs:734-761` deletes a weekly window or calendar rule with no session-state
query and no proof. An already-started session keeps its own copy of what it blocks, so this is not an
immediate bypass — but `README.md:70-71` and `INSTALL.txt:88-90` tell the user nothing short of the
24-hour release shortens a lock, and a user reading that will not expect `curfew remove <the window
blocking me>` to work.

**Fix.** When the service is reachable, refuse if a running session derives from that schedule id,
mirroring the uninstall refusal.

### P2-12. Documentation and code disagree on what the browser extension blocks **[V]**

`docs/ARCHITECTURE.md:153` advertises *"URL paths, in-page elements, search keywords"*;
`extension/manifest.json` declares only `nativeMessaging`, `webNavigation`, `tabs` and `alarms` — no
`declarativeNetRequest`, no `content_scripts`, no `scripting`. `background.js:100-110` blocks by an async
`chrome.tabs.update` **after** `onBeforeNavigate`, i.e. after the load has begun — the very limitation
`GAPS.md:209` uses to dismiss a competitor's extension. In-page element blocking is not implementable with
the code present.

Separately, a URL or keyword rule that begins while a matching page is already open never takes effect:
the extension only checks on navigation, and `Request::Beat` answers `Response::Ok`, which carries no
verdict (`host.rs:24-27`). Start a URL rule with a matching tab open and nothing happens, and the browser
is not closed either because it *is* beating.

**Fix.** Correct the three doc claims to "URL/path-level rules, rechecked on navigation and SPA history
changes", or implement DNR + a content script. For the live-rule gap, add a focused-tab re-check
(`chrome.tabs.onActivated` + `check()`), and consider letting `Verdict` ride back on the heartbeat.

### P2-13. An over-long URL kills the native host, and the browser is then closed **[R]**

`extension.rs:142-145` refuses any frame over `MAX_MESSAGE` (64 KiB) and `host.rs:48-52` treats a framing
error as unresynchronisable and exits the process. The extension enforces no cap on the URL it forwards,
so a page that links to a 64 KiB query string kills the host, the port disconnects, `ask()` resolves
`null` (allow), and no beat is recorded. If that tab keeps the focus, every subsequent beat kills the
freshly spawned host; after the 90 s grace the beating browser becomes unwatched and is terminated. A
message-layer failure therefore turns a deliberate fail-open into a browser kill.

**Fix.** Clamp the URL in the extension before `postMessage`, and have `read_message` drain an oversize
frame and report a typed error that `run()` answers, rather than exiting.

### P2-14. Overlay notices share one thread-local text slot **[R]**

`crates/curfew-tray/src/overlay.rs:197-199,470` stores every overlay's text in a single `thread_local`, and
`overlay_proc`'s `WM_PAINT` reads that slot (`:354-357`). A second `show()` overwrites it before the first
window paints, so the earlier window renders the newer notice's text; the previous `HWND` is never
destroyed, and its close timer was set on a different window, so a superseded card can persist
indefinitely. Two notices within one watch tick are reachable (`shell.rs:177-196`). The user sees two
identical cards, or a delay notice under a block title.

**Fix.** Own the string per window (a `HWND → Vec<u16>` map, or window userdata), and destroy the previous
overlay before creating a new one.

### P2-15. The overlay is placed on the primary monitor only, with no DPI handling **[R]**

`overlay.rs:472-484` positions with `GetSystemMetrics(SM_CXSCREEN/SM_CYSCREEN)` — the primary display, in
physical pixels — with no `MonitorFromPoint`/`GetMonitorInfoW` and no `WM_DPICHANGED`. On a multi-monitor
desk the "why did my window disappear" card appears on the wrong screen, possibly behind a fullscreen
game; at 150 % scaling it is placed and sized in units that disagree with the fonts GDI draws.

**Fix.** Position against the work area of the monitor under the cursor or foreground window, and scale
the card's metrics by the monitor DPI.

### P2-16. Hiding the tray silently ends app-budget and window-title enforcement **[V]**

`crates/curfew-tray/src/shell.rs:140-144` is the **only** sender of `Request::Seen` in the repository
(`git grep`), and it runs on `WATCH_TIMER`. Choosing Quit destroys the window and with it both timers
(`:402-409`), so `tick.rs:596-599` never receives another report and `Enforcer::seen` goes stale
permanently — after which window-title rules and app budgets stop being charged. The menu line the user
clicks says the opposite: *"Hiding this icon does not stop Curfew. The service keeps enforcing everything
you asked for"* (`menu.rs:234-235`). Nothing reports the degradation; the tooltip is gone with the icon.

**Fix.** Either keep a message-only window alive to carry the watch timer, or make `QUIT_NOTE` tell the
truth. The service is the better home for the truth: add a `seen`-age to `Status` and surface it on "Is it
working" and the tray tooltip.

### P2-17. `ipc::ask` has no deadline, and the tray calls it from its message pump **[V]**

`crates/curfew-win/src/ipc.rs:205-219` performs a blocking connect, write and `read_line` with no timeout
(`git grep timeout -- ipc.rs` → nothing). `app.rs:144-157` spawns a thread per message, and the page issues
two calls per second, so a service that is *connected but wedged* grows blocked threads unboundedly —
the `NotFound` path fails fast only when the pipe is genuinely absent. Worse in the tray:
`report_foreground` and `watch_closures` (`shell.rs:140-152`) are called from `WM_TIMER` on the thread that
pumps `GetMessageW`, each doing a synchronous pipe round trip, so a stalled service freezes the tray icon
and its menu — the exact degradation `shell.rs:147-148` claims to avoid.

**Fix.** Set a read timeout on the client stream and map it to `ErrorKind::TimedOut`; cap in-flight work in
`app.rs` (one worker with a bounded queue) instead of a thread per message; and send `Seen` from a
short-lived helper thread so the message pump can never block.

### P2-18. Signatures are re-verified on every retry pass, O(n²) **[R]**

`crates/curfew-sync/src/wire.rs:92-113` retries the whole pending list whenever any entry is accepted, and
`accept` performs a full Ed25519 verification (`oplog.rs:243`). A batch delivered in reverse order costs
O(n²) verifications; `MAX_FRAME` permits 8 MiB of JSON, so one paired peer can pin a thread for minutes
with only 8 threads available (`node.rs:32`).

**Fix.** Verify once, bucket by author, apply each chain in one sorted pass.

### P2-19. The shared-folder reader has no size cap **[R]**

`crates/curfew-sync/src/folder.rs:126-135` reads and parses every `*.curfew` file with no
`fs::metadata` check, unlike the LAN path which caps at `MAX_FRAME`. A folder is someone else's cloud
account: a participant or a sync client resurrecting a large file can make the app allocate and parse a
multi-gigabyte document on the sync pass.

**Fix.** `metadata()` first; skip over `MAX_FRAME` and count it as skipped.

### P2-20. `deny_unknown_fields`-class gaps in recurrence and stats **[R]**

- `stats.rs:139-140` derives `total_sessions` by summing a per-day counter that increments once per day a
  record touches, so one session crossing midnight counts twice in the number the UI displays. The
  per-day field's own doc says it double-counts; the *aggregate* is what is misleading.
- `mirror.rs:99-108` publishes a session only when its profile changes, while its own comment says a
  changed lock must travel too: *"A session is republished when its lock changes as well as when it
  appears."* It compares `published.profile` only, and `Published` (`:78-83`) stores the profile and the
  release, not the `LockSet`. A locally strengthened lock therefore never reaches the peer, and the two
  devices enforce different promises for the same session id.

**Fix.** Compare the whole `LockSet`; track distinct session ids for the aggregate.

---

## 7. Android application

The previous review covered `Auth.kt`, `ChallengeDialog.kt`, `Enforcer.kt`, `EnforcementService.kt`,
`CurfewAccessibilityService.kt` and `BootReceiver.kt`. Everything else in `android/` is covered here.
Findings are numbered `A-n` so as not to collide with the sections above.

### A-1 (P0). The Android UI reaps sessions with the raw wall clock, not the trusted one **[V]**

`crates/curfew-core/src/lock.rs:132-134` is a bare comparison against whatever instant it is handed:
```rust
pub fn is_expired(&self, now: Timestamp) -> bool {
    let by_timer = self.ends_at.is_some_and(|t| now >= t);
    let by_delay = self.delayed_release_at.is_some_and(|t| now >= t);
    by_timer || by_delay
}
```
The enforcement service correctly hands it a trustworthy instant — `EnforcementService.kt:168` uses
`runtime.trustedNow()`. The **UI does not**:

`android/app/src/main/java/dev/curfew/app/ui/CurfewViewModel.kt:107-115`
```kotlin
private fun refreshFast() {
    val now = runtime.clock.now()
    // A timer that reaches zero has to end the session itself. ...
    if (_state.value.sessions.any { s -> s.lock.endsAt?.let { it <= now } == true }) {
        reconcileNow()
    }
```
`runtime.clock.now()` is `System.currentTimeMillis()/1000` — settable by the user. The same raw value is
used at `:535` (`startTimer` mints `endsAt = clock.now() + seconds`) and `:772` (`reconcileNow` →
`runtime.reconcile(runtime.clock.now(), events)`), and `reconcile` calls `policy.reap(now)` and persists
the result.

**Consequence.** Start a 90-minute timer, move the device clock forward two hours, open Curfew. Within
about two seconds `refreshFast` sees `endsAt <= rawNow`, `reconcileNow()` reaps the session against the
same forged instant, and it is written to the audit trail as having ended normally. `trustedNow()` — and
with it the `ClockTamper` banner that exists to explain exactly this — is never consulted on that path,
because it is only reached from the service tick, by which point the session is gone. The mirror image
also holds: move the clock *back* before starting and a 90-minute lock ends after 30 real minutes.

This is the T6 bypass that `docs/ARCHITECTURE.md:165-166` lists as in scope and `GAPS.md:131-135` claims is
closed, executed from the app's own UI rather than by an attacker. It is **P0-2's Android twin**: the core
guards the clock correctly and a caller declines to use the guard.

**Fix.** Use the already-recorded trusted instant on all three paths — it is non-suspending, so the 1 Hz
loop can afford it:
`val now = runtime.policy.clockWitness()?.now() ?: runtime.clock.now()`.

### A-2 (P1). Android's default lock strength — "Ask me first" — produces a lock that can never be ended **[V]**

`android/app/src/main/java/dev/curfew/app/ui/TimerScreen.kt:88`
```kotlin
var strength by remember { mutableStateOf(Strength.Confirm) }
```
`Confirm` carries `Lock.Confirm` (`TimerScreen.kt:64`, and it is also offered at
`ScheduleEditor.kt:54`, `ProfileEditScreen.kt:512`, `ProfileEditScreen.kt:231`, `CalendarScreen.kt:509`).
The only screen that can end a session branches on `Challenge` alone:

`android/app/src/main/java/dev/curfew/app/ui/NowScreen.kt:101-108`
```kotlin
fun end(session: Session) {
    val challenge = session.lock.conditions.filterIsInstance<Lock.Challenge>().firstOrNull()
    if (challenge == null) {
        finish(session, emptyList())
    } else {
        pending = PendingEnd(session, challenge, Challenge.generate(challenge.challenge))
    }
}
```
`Lock.Confirm` is not a `Lock.Challenge`, so it takes the `challenge == null` branch and calls
`finish(session, emptyList())`. The core then requires `conditions.is_subset(satisfied)` (`lock.rs:143`),
Confirm is not in the set, and the lock refuses **for ever** — because nothing anywhere passes
`Lock.Confirm` as satisfied: a repository-wide grep for `Lock.Confirm` in `android/app/src/main` finds six
hits, all of them *constructing* the lock, none satisfying it.

**Consequence.** The default flow — Now → start timer → "Lock it in" → "End now" — is refused with
"Still locked. It needs a confirmation", and there is no control anywhere in the app that can give one.
The label the user chose reads "One confirmation, so it is never an accident"; what ships is a lock with no
early exit at all, escaping only by timer expiry, the 24-hour release, or an emergency pass. This inverts
invariant 2: the release condition the user chose is the one they cannot satisfy.

**Fix.** Branch on Confirm as well, and satisfy it from an explicit dialog:
```kotlin
val needsConfirm = session.lock.conditions.any { it is Lock.Confirm }
if (needsConfirm) { confirming = session }        // a sheet that ends with finish(session, listOf(Lock.Confirm))
else if (challenge == null) finish(session, emptyList())
else pending = PendingEnd(...)
```
Then add a test that walks **every** `Lock` variant the UI can mint and asserts a satisfying path exists —
this class of bug is exactly what such a test catches.

### A-3 (P1). One throw in the enforcement loop stops enforcement permanently, and the honesty banner cannot fire **[R]**

`android/app/src/main/java/dev/curfew/app/enforce/EnforcementService.kt:153-184` runs
`while (runtime.scope.isActive)` with **no `try`/`catch`**, launched on
`CoroutineScope(SupervisorJob() + Dispatchers.Default)` (`CurfewRuntime.kt:55`) with no
`CoroutineExceptionHandler`. Several calls in the body can throw: `runtime.calendarEvents` →
`contentResolver.query` (`CalendarReader.kt:37-39`) can raise `SecurityException` or `DeadObjectException`;
`runtime.nextChange` → the FFI parses the config timezone and returns `CurfewError::Config`; and
`trustedNow`/`heartbeat`/`reconcile` all touch the database.

A single throw cancels that coroutine for good. The sibling `watch`/`charge` jobs survive, so the service
stays alive and the notification keeps saying "standing by" — while nothing is reconciled and no block is
applied. The downtime banner, which §10 designates as the answer to silent failure, **cannot fire**:
`detectDowntime` runs only from `restore()` (`CurfewRuntime.kt:504`), which runs once, at `:44`. The
`ScheduleAlarmReceiver` will call `EnforcementService.start(...)` again, but the service is already running,
so `onCreate` does not re-run and `tick()` is never restarted.

**Fix.** The `chargeLoop` at `:83-89` already shows the pattern — wrap the tick body in
`try { … } catch (t: Throwable) { Log.e(…) ; audit("enforcement.tick.failed", …) }`, add a
`CoroutineExceptionHandler` to `runtime.scope`, and re-enter `tick()` from `onStartCommand` when the job is
not active.

### A-4 (P1). Android offers site, URL and keyword rules that can never fire, and nothing says so **[V for the code; R for the UI walk]**

`android/app/src/main/java/dev/curfew/app/ui/AppPickerScreen.kt:302-311` presents site and word rules as
working features:
```kotlin
Text(
    "Nothing yet. A site blocks it and everything under it; a word blocks " +
        "anything whose address or title contains it.",
```
They cannot fire. `Observation.Web` is never constructed anywhere in `android/app/src/main` — the only
occurrence is the match arm in `Enforcer.kt:148`, and every producer is a test.
`CurfewAccessibilityService.kt:36-39` builds `Observation.App(packageName)`, as does
`UsageStatsPoller.kt:37`. In the core, `Target::Domain` and `Target::Url` match only `Observation::Web`,
and `Keyword` matches `searchable_text()`, which for `Observation::App` is `screen` — always null
(`crates/curfew-core/src/target.rs:80-88, 129-137`). No `VpnService` exists in the manifest or the source
tree, so the third enforcement layer in `ARCHITECTURE.md:140-142` is also absent.

**Consequence.** A user adds `reddit.com` to a profile, sees it listed and counted, and it blocks nothing —
ever. Three of the four rule families the UI can express are decorative, while the health screen reports
"Everything it needs to block an app is in place." `GAPS.md:41-45` refuses precisely this: *"the failure we
refuse to ship is a silent one."* `GAPS.md A2` already documents the missing VPN layer as a **known gap**,
so this is a disclosure failure rather than a missing feature — the gap is honest in the doc and hidden in
the app.

**Fix.** Make the health screen name it — "Site and word rules are not enforced on Android yet; they need
the VPN layer, which is not built" — and label the picker's site rows accordingly, until the VPN layer
exists.

### A-5 (P1). A truncated `db.key` makes the app die on launch and every stored lock unrecoverable **[R]**

`android/app/src/main/java/dev/curfew/app/data/DatabaseKey.kt:29-31`
```kotlin
fun passphrase(context: Context): ByteArray {
    val sealed = File(context.filesDir, "db.key")
    return if (sealed.exists()) open(sealed.readBytes()) else create(sealed)
}
```
`create()` writes the sealed key with a plain `sealed.writeBytes(cipher.iv + body)` (`:39`) — not atomic,
no temp-and-rename. A crash or a full disk during that write leaves a short file; `open()` then throws
`ArrayIndexOutOfBoundsException` (truncated IV) or `AEADBadTagException` (bad GCM tag), and nothing catches
it. The passphrase is referenced eagerly as a `Room.databaseBuilder` argument inside a `by lazy`
(`CurfewRuntime.kt:774-785`), so the throw escapes `Context.curfew` and takes down
`MainActivity.onCreate`, the accessibility service and the widget with it. `System.loadLibrary("sqlcipher")`
(`:805`) has the same shape.

**Consequence.** The database is unrecoverable — the passphrase exists nowhere else — and the app cannot
start in order to say so. The user's only route back is clearing app data, which destroys every running
lock. That crosses the "never unrecoverable" line of `ARCHITECTURE.md:215-216`.

**Fix.** Write `db.key` atomically (`.tmp` + rename, as `ConfigStore` already does). Treat a decryption
failure as a recoverable state: move the unreadable key and database aside, and surface a loud health
banner ("Curfew could not read its encrypted store; locks held before this launch could not be restored")
rather than throwing out of a `lazy`. Add the one missing test for a truncated key.

### A-6 (P1). An unreadable config is silently replaced with an empty one **[R]**

`android/app/src/main/java/dev/curfew/app/data/CurfewRuntime.kt:780-783`
```kotlin
// A config that fails to load is a bug in a previous write, not a reason to run with no
// policy at all: fall back to the empty config so the app still opens and can be fixed.
val policy = runCatching { Policy.load(config.read()) }
    .getOrElse { Policy.load(ConfigStore(File(context.filesDir, "unused")).read()) }
```
The fallback reads `<filesDir>/unused`, which does not exist, so it yields the built-in empty document —
zero profiles, zero rules — and **nothing records that it happened**: no audit row, no banner, no
`UiState` field. Every scheduled session stops starting and every app block evaporates; the only signal is
Now saying "Nothing is blocked right now." A committed session still in the database is restored against a
config that no longer defines it.

This is `P1-10`'s Android twin, and it is the same failure mode the module's own comment forbids. Note that
`ConfigStore.write` validates *before* touching the old file (`ConfigStore.kt:31-47`), so the config is
written carefully and read carelessly.

**Fix.** On the fallback path: `audit(now, "config.unreadable", e.message)`, rename the bad file aside
(`curfew.toml.broken`), and set a `StateFlow` the Now and Health screens surface.

### A-7 (P2 table). The rest of the Android surface

| # | Finding | Location | Fix |
| :-- | :--- | :--- | :--- |
| A-7 | Screen-off time keeps charging the last app indefinitely: `UsageStatsPoller` never emits `Idle`, so 30 s after `ACTION_SCREEN_OFF` the tick re-establishes the app as foreground and the 5 s charge loop drains the budget all night | `EnforcementService.kt:177-181`, `UsageStatsPoller.kt:28-37` | Emit `Observation.Idle` when the last event is `MOVE_TO_BACKGROUND`/`SCREEN_NON_INTERACTIVE`; call `onIdle` when the sample is null |
| A-8 | `prune()` and `SyncHub.compact()` are never called — no `Worker`/`WorkManager` usage exists anywhere, though `androidx.work` is a declared dependency — so usage and audit tables grow unbounded while `UsageScreen.kt:148-152` promises "Kept on this device for 30 days, then deleted" | `CurfewRuntime.kt:710-715`, `UsageScreen.kt:148` | Register the periodic worker the KDoc already assumes, or run prune/compact on a 24 h counter in the existing tick |
| A-9 | The manifest grants `INTERNET` (needed for LAN sync) while `HealthScreen.kt:182-185`, `SettingsScreen.kt:206-208` and `UsageScreen.kt:41-42` each state as fact that Curfew "has no internet permission at all" | `AndroidManifest.xml:54` vs the three screens | State the real property ("talks only to paired devices on your local network"), or split sync into a separate APK so the claim becomes true |
| A-10 | The block screen never closes for any profile whose display name differs from its id: `EXTRA_PROFILE` carries the **name** (`EnforcementService.kt:273`) but `running` holds **ids**, so `sawItRunning` stays false and `onEnded()` never fires. It works only for a user whose slug happens to equal the lowercased name | `BlockActivity.kt:186-195`, `ProfileEditScreen.kt:565` | Pass the id in the extra; resolve the name inside the activity |
| A-11 | No `BackHandler` in `BlockActivity`, though the `goHome()` KDoc claims "Back and Home both leave the blocked app rather than returning to it". Back finishes the activity and reveals the blocked app — a flicker under accessibility, 1–2 s of usable access per press under the usage-stats fallback | `BlockActivity.kt:99-110` | `BackHandler { goHome() }` |
| A-12 | Every config mutation does TOML serialisation, `Policy.check`, a file write and `reconcile` on the **main thread** — `viewModelScope` is `Dispatchers.Main.immediate` and the body is not dispatched. `ScheduleScreen` calls `saveWeekly` in a loop, so one master-switch flick is N main-thread writes | `CurfewViewModel.kt:551-603, 628-696`, `CurfewRuntime.kt:369` | `withContext(Dispatchers.IO)` inside `commitConfig` |
| A-13 | `restore()` and `reconcileNow()` do a calendar-provider query and DB work on the main thread, and the worst case (a large config, or first launch after granting usage access) blocks the first frame | `CurfewViewModel.kt:55-58, 770-777` | `withContext(Dispatchers.Default)` in both |
| A-14 | The view-model ticker never stops: with Curfew in recents it runs a **full** refresh — 56-day calendar query, two TOML serialisations, a 30-day audit scan, usage-stats query, 11 permission probes — every 5 s for ever, against §10's "stop entirely when no rule can fire" | `CurfewViewModel.kt:74-85` | Gate the slow beat on having sessions/activations/upcoming, or cancel the loop when backgrounded |
| A-15 | `startForeground` builds a `PendingIntent` from an unchecked `getLaunchIntentForPackage`, which is documented to return `null`. The NPE aborts `onCreate` *before* the notification is posted, missing the 5-second contract — and every entry point funnels through it | `EnforcementService.kt:196-211` | `?.let { … }`, and only `setContentIntent` when non-null |
| A-16 | `Db` is `version = 1` with `exportSchema = false`, no migrations and no migration test, while `StateRow` holds the running locks. Any future entity change throws on first open, and every read path wraps itself in `runCatching{}.getOrDefault(empty)` — so the failure surfaces as an empty app | `Db.kt:114-118` | `exportSchema = true` + `room.schemaLocation`, a per-version migration test, and a loud state on open failure |
| A-17 | `USE_EXACT_ALARM` is declared alongside `SCHEDULE_EXACT_ALARM`, so `canScheduleExactAlarms()` always returns true, the wizard row never appears, the decline path is dead code, and `ScheduleAlarmReceiver`'s documented fallback can never be taken. It also invites Play policy scrutiny for a sideload app | `AndroidManifest.xml:20-22`, `Permissions.kt:113-115` | Keep `SCHEDULE_EXACT_ALARM` only and let the health screen report the degraded case |
| A-18 | `singleTask` with no `onNewIntent`: re-opening Curfew from recents does **not** re-arm enforcement, because `onCreate` (and its `EnforcementService.start`) does not re-run. The comment states the opposite | `MainActivity.kt:70-75`, `AndroidManifest.xml:75` | Override `onNewIntent`, or move the call to `onStart()` |
| A-19 | The long-timer confirmation is skipped on the "Start without it" path, so "Until it ends" plus a 12-hour dial starts the strongest lock in one tap — the exact case the confirmation exists for | `TimerScreen.kt:311-315` vs `:250-253` | Re-check `minutes >= LONG_MINUTES` inside that dialog's confirm path |
| A-20 | `Lock.DeviceCredential` is labelled "Fingerprint or PIN" (`TimerScreen.kt:65`) and "Fingerprint" (`ProfileEditScreen.kt:512`) where D7 forbids accepting biometrics. The user picks a finger and is asked for a PIN | same | "Your screen lock (PIN, pattern or password — not a fingerprint)", plus a message when no credential is configured |
| A-21 | The health screen reports "Curfew cannot enforce / Nothing is being blocked" whenever accessibility is off, even though the usage-stats fallback *is* enforcing apps 1–2 s late — contradicting invariant 3 and §10's "always know which layers are live". `GAPS A5`'s explicit "detect and disclose work profiles" decision is unimplemented (no `UserManager` reference anywhere) | `HealthScreen.kt:44-55`, `SettingsScreen.kt:155-173` | Derive the verdict from the live layer stack; add the work-profile disclosure line |
| A-22 | Usage and audit tables have no index on `at`, and every foreground decision scans 25 hours of 5-second slices; `decide` also runs per notification and per window change | `Db.kt:74-108`, `CurfewRuntime.kt:78-97` | `indices = [Index("at")]`, narrower lookback, short-circuit on no active profile |
| A-23 | The widget is rebuilt every 30 s with no change detection, and each rebuild scans the 30-day audit table to render a footer streak | `EnforcementService.kt:186-194`, `CurfewWidget.kt:30-35` | Cache the face and return early when unchanged |
| A-24 | All screen copy is hardcoded Kotlin; `strings.xml` holds 12 strings and 6 references. `GAPS E6` requires externalisation from day one. Even the device-admin warning — read at the moment of decision — is a literal | `CurfewDeviceAdmin.kt:31-37`, `strings.xml` | Move whole sentences into `strings.xml` with positional args |
| A-25 | `themes.xml` is a **light** Material theme with no `values-night` variant and no `windowBackground`, against a deliberately dark app — so a light window is drawn for the first frame of every cold start | `themes.xml:3` | Dark parent plus `android:windowBackground` |
| A-26 | Uninstall guard can press Back on any system screen that merely mentions "Curfew" (it watches `com.android.settings` and `com.android.systemui` and matches the label), and its `lockHeld` flag lives in SharedPreferences written only from `refresh()`, so it can outlive the lock and keep blocking Curfew's own Settings page after every lock has ended | `UninstallGuard.kt:57-58, 89-95` | Match the package string plus uninstall-specific markers; re-derive `lockHeld` per event |
| A-27 | No `androidTest` directory exists at all, though `androidTestImplementation` is declared for JUnit, Espresso and Compose UI test. Every Robolectric test pins `@Config(sdk = [33])`, so nothing covers API 34/35 — foreground-service types, `specialUse`, notification permission, exact-alarm policy — at `targetSdk = 35` | `android/app/src/androidTest` (absent) | Stand up the instrumented suite; the highest-value first tests are A-1, A-2, A-5 and A-7 |
| A-28 | `gradle-wrapper.properties` has no `distributionSha256Sum` and there is no dependency verification metadata, against a project that sells reproducible builds and published checksums. `androidx.biometric 1.2.0-alpha05` is an alpha on the lock-release path | `gradle-wrapper.properties:3`, `libs.versions.toml:16` | Pin the wrapper checksum, enable `--write-verification-metadata`, move to a stable biometric release |
| A-29 | Screen-time day bucketing is UTC (`epoch / 86400`) while the user's midnight is local, so in UTC+13 the last two "days" are partial; and duplicate buckets are summed with no dedupe key, inflating screen time and understating the `savedSeconds` figure the card leads with | `ScreenTime.kt:34-45`, `CurfewRuntime.kt:526-527` | Bucket by local `startOfDay`, dedupe on `(packageName, firstTimeStamp)` |
| A-30 | `Url.raw` is lowercased in Rust (`target.rs:177`) but not in Kotlin (`Policy.kt:477`), and `PolicyTest` only checks host/path/query. Inert today (globbing lowercases both sides) but a latent divergence in a module whose stated contract is "mirror the Rust types exactly" | `Policy.kt:477` | Lowercase it, and assert the field in the existing parser test |

**Verified clean on Android** (so it is not re-audited): `CalendarReader`'s all-day localisation, `distinctBy`,
cursor closing and DST/Tokyo test coverage; `ConfigStore.write` validating before touching the old file;
`data_extraction_rules.xml` excluding root/database/sharedpref/file/external from both cloud backup and
device transfer; `Auth.kt` restricted to `DEVICE_CREDENTIAL` per D7; `SyncHub` never letting a peer weaken a
lock; every `Policy.kt` wire shape checked field-for-field against Rust except `Url.raw`; and no `!!`,
unchecked cast, unguarded `.first()` or division by zero in the reviewed UI files.

---

## 8. P3 — Polish

- **`Stats`/`describeMatcher` escaping is fine, but escaping is delegated.** `describeMatcher`
  (`app.html:351-359`) builds a string from raw config values and is escaped once at the call site
  (`:339`). Every other interpolation in the file escapes locally. Keep one rule.
- **Navigation is mouse-only.** `.nav`/`.tab` are `div`/`span` with click handlers, no `tabindex`, no
  `role`, no key handler (`app.html:62-72, 485-491`); the modal (`:439`) has no `role="dialog"`, no focus
  trap and no Escape handler. A keyboard user cannot reach the pages that explain what is blocked, but
  *can* tab to "End it early". Render them as `<button>` and add dialog semantics.
- **Raw JSON reaches the user.** `app.html:410,519` render `JSON.stringify` of `PassRefusal`/`Refusal`,
  where `ipc.rs:129-139` says the refusal is carried through *so the UI can explain it* and
  `curfew-tray/src/main.rs:124-136` already holds the exact wording. Render "the next one is available at
  Fri 4 Sep, 09:30", not `{"refusal":"quota_spent","next_at":1789410600}`.
- **Unknown enum variants degrade to machine tokens.** `describeLock`'s default (`app.html:134`) returns
  the raw discriminant, so a newer service produces "Ends early only for emergency_passes."
- ~~**Design tokens disagree on radii.**~~ **REFUTED — see correction below.** The original text claimed
  `design/_tok.txt:7` says `--r-card:22px; --r-ctl:14px` while the shipped Windows UI uses 14px/9px
  (`app.html:10`), "so the app cannot look like its own System artboard." Checking every artboard shows
  there are **two token sets and they are clean per platform**: all 19 phone artboards use
  `--r-card:22px; --r-ctl:14px`, and all 9 Windows artboards use `--r-card:14px; --r-ctl:9px`.
  `app.html:10` uses 14/9 (correct for Windows); `Design.kt:53-54` uses 22.dp/14.dp (correct for
  Android). The only misleading artifact is `design/_tok.txt`, which holds the *phone* values under a
  shared-sounding name. The design system is more disciplined than this finding claimed. Carried forward
  correctly in `UX_INTERACTION_REVIEW.md` §6.3.
- **`GAPS.md` contradicts itself on Device Admin.** A7 (`:60-63`) says it is "dropped entirely"; G2
  (`:220-232`) reverses that and `ROADMAP.md:33-34` marks it implemented. A7 carries no pointer forward.
- **`design/build.py` does not reproduce `Plan.dc.html`**, and `EditWindow.dc.html` is an orphan absent
  from `canvas.json`. `design/_parts.py` and `design/_base.css` are 0-byte files referenced nowhere.
- **`packaging/windows/RELEASE_NOTES.md:6-18`** — the GitHub release body — lists only the `.zip` and
  tells users to run `curfew install`, omitting the MSI that does service registration, PATH, the Start
  menu shortcut and the startup tray. `manifests.py:31-39` and `INSTALL.txt:17-25` say the opposite.
- **The committed `packaging/windows/curfew-x64.msi` / `.wixpdb` are stale artifacts**: `ProductVersion
  0.1.4` against `Cargo.toml` `version = "0.1.0"`, committed in a directory whose own header says release
  binaries are deliberately kept out of the repository.
- **`build.ps1` never ties the MSI version to the binaries**; `-Version` arrives from the git tag and is
  validated for shape only.
- **Scoop `autoupdate.hash` points at the combined `SHA256SUMS`**, which Scoop cannot match against a
  single download, and applies one hash URL to both architectures (`manifests.py:130-136`).

---

## 9. Claims examined and refuted

Recorded so you can calibrate the rest. These looked like findings and are not:

1. **"The overlay palette is warm brown and matches nothing in `design/`."** Wrong. GDI stores colours as
   `0x00BBGGRR`, and the file's comment says so. Converting each constant gives `0x00231B16 → #161B23`,
   `0x002E241D → #1D242E`, `0x0041332A → #2A3341`, `0x005AA6F2 → #F2A65A` — exactly the design tokens in
   `design/_tok.txt:4`. The palette is correct.
2. **"`describeMatcher` output is interpolated without `esc()` (XSS)."** Wrong twice: the call site
   *does* apply `esc()` (`app.html:339`), and `esc` escapes the composed string correctly.
3. **"The window's 'Release it' button starts the 24-hour delayed release."** Backwards. `{request:"release"}`
   deserializes to `Request::Release`, which is the *immediate* peer release (`ipc.rs:61-66`); the delayed
   one is `Request::RequestRelease`, sent only by the tray (`main.rs:105-107`) and the CLI
   (`main.rs:752`). The real defect is the opposite one (P1-6).
4. **"`curfew-svc::serve` calls `session.open_emergency` to end locked sessions" / "`open_emergency` does
   not hold locks at all."** No such function exists anywhere in the repository (`git grep` returns
   nothing). Withdrawn in full.
5. **"`curfew-ffi` has no panic protection, contradicting §1.4."** The grep is right that there is no
   `catch_unwind`, but the conclusion is wrong: UniFFI 0.28.3 wraps every generated call
   (`uniffi_core-0.28.3/src/ffi/rustcalls.rs:177-207`). Safe, but for a reason worth recording — see §2.
6. **"The named-pipe SDDL is missing."** It is present and well designed (`runner.rs:284`); the prior
   review's "open" row is stale. The genuine problem is the read loop behind it (P1-1).

Two of these (2 and 3) were asserted as headline P1s by sub-reviewers and were inverted or imaginary.
Every finding I have marked **[V]** was checked by me against the source; the **[R]** items are reported
with their cited code confirmed to exist, but I did not reproduce the runtime behaviour.

---

## 10. Open and unverified

- **Windows WebView2 navigation is not restricted.** `app.rs:139-158` sets no navigation handler; a
  repo-wide grep finds none. If wry's defaults allow navigation to a remote origin while keeping the IPC
  handler, the page that can send `Unlock`/`End` could become a web page — the exact scenario the `PAGE`
  comment (`app.rs:11-13`) exists to prevent. I could not confirm wry's default without building it.
  **Add a navigation handler that permits only the compiled-in document, regardless.**
- **`Envelope { id, #[serde(flatten)] call: Call }`** (`app.rs:29-34`) nests an internally-tagged enum
  inside buffered `Content`. This is the classic place for a runtime `invalid type` failure. If it fails,
  every page call lands in the `console.error` arm (`:152-153`) and the window shows "Nothing can be
  served" with a devtools-only diagnostic. Not reproduced; a one-line test would settle it.
- **`untracked/`** — not present; the working tree is clean (`git status --porcelain` empty).
- **Chrome API limits** referenced in P2-13 (message-size directions, alarm period clamping) were taken
  from the sub-review's reading and not confirmed against vendor documentation.
- **`background.scripts` alongside `service_worker`** in `extension/manifest.json:6` — whether Chrome
  rejects or ignores it needs a browser load.

---

## 11. Prioritized remediation plan

**P0 — do first. Items 1 and 2 are each a one-line change plus a test, and each one alone invalidates the
product's headline claim.**

1. **P0-1** Remove `Lock::Timer` from `claimable` in `tick.rs:61` **and** `curfew-ffi/src/lib.rs:93`, and
   fix `tests/tick.rs:362-365` to assert the refusal. A two-line fix for the worst bug here: it makes every
   timer-locked session releasable by any local user with one pipe line, on both platforms, and the test
   currently enshrines it. **Do this before anything else.**
2. **P0-2** Route the Windows service's clock through `ClockWitness` and persist it in `Persisted`. Today a
   clock change permanently ends every timer lock and records it as history.
3. **P0-3** Refuse a service `Stop` while a lock is running (`service.rs:43`), and make the uninstall guard
   refuse on an *unreachable* service (`main.rs:826`) rather than only on a positive "nothing running".

**P1 — the promise, the exit, and the platform floors.**
4. **P1-0** Set an explicit ACL on `%ProgramData%\Curfew` and verify the watchdog image before spawning it
   as SYSTEM.
5. **P1-13** Make a session hold its own rules, or refuse config edits that weaken a running one. Today
   `curfew unblock` removes enforcement while every surface still reports a healthy lock — and the two
   doc-comments that say otherwise are wrong.
6. **P1-1** Bound and time out the control-channel read; make `serve` concurrent. Without this the
   24-hour release is denial-of-service-able by any local user, and the service's memory with it.
7. **P1-9** Add an out-of-band lock witness so deleting the state files cannot retire the watchdog.
7. **P1-10** Stop failing open on an unparseable config while locks are running.
8. **P1-6** Give the window the same release affordance the tray has, and confirm the peer release before
   sending it. This is the documented last-resort exit on the primary surface.
9. **P1-4 + P1-5** Make `end_with_pass` require an earned pass, and validate `[emergency]` and
   `Refill::Rolling`.
10. **P1-3** Add an authenticated strengthening-only persistence boundary to the FFI.
11. **P1-2** Resolve the browser identity host-side; stop trusting the extension's guess.
12. **P1-7** Ship an installable signed APK or correct `ARCHITECTURE.md` §12.
13. **P1-8** Add Windows downtime reporting; make the Android poll adaptive or correct §10.
14. **P1-11** Get the calendar fetch off the enforcement thread and back it off on failure.
15. **P1-12** Give the service a log sink, so its ~20 diagnostic paths stop being silent.

**P2 — correctness, honesty and the failure modes users actually hit.**
16. The ICS set (**P2-8**, **P2-9**) — one root cause (candidate generation anchored to `DTSTART`, plus
    filter-and-drop parsing) and the only component that fails open by omission.
17. **P2-1/2/3/4/5/6** — the window: config polling, redraw, refresh ordering, `__curfewUser`, string
    predicates, the password box. Cheap, self-contained, and they remove the "the app is broken" class of
    report.
18. **P2-7** config strictness; **P2-10/11** the CLI's silent no-ops.
19. **P2-16/17** the tray's silent degradation and its blocking pipe calls.
20. **P2-12/13** extension doc claims and the host-kill path; **P2-14/15** the overlay.
21. **P2-18/19/20** sync robustness.

**P3 —** docs/design/packaging consistency; the whole list is mechanical.

**One structural recommendation.** The recurring shape across this review is that a policy invariant is
enforced carefully in one place and then re-implemented, incompletely, in each consumer. Three separate
UIs each decide which locks are actionable, in three different ways, and `State.lock` is computed by both
enforcers and read by neither. Adding a `release` verdict to the engine's decision payload — or a small
shared "what can be done about this session" helper consumed by tray, window, CLI and Android — would
retire P1-6, P2-5, the `Challenge` gap, and the class of bug where a new `Lock` variant silently becomes
unactionable on every surface at once.

---

*Generated from a full read of the source at commit `525e691`. No file was modified except this one.*
