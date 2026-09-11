# Fixes applied from the two reviews

**Branch:** `review-fixes` (from `installer-no-reboot` @ `525e691`)

This file is the running log of every change made in response to:

- `DESIGN_AND_CODE_REVIEW_FULL.md` — the code/design review (findings `P0-n`, `P1-n`, `P2-n`, `A-n`)
- `UX_INTERACTION_REVIEW.md` — the interaction review (findings `F-n`)

**How to read an entry.** Each one records the finding, what was actually changed, *why* that shape
was chosen over the alternatives, how it was verified, and — where it matters — what was deliberately
**not** changed and why. Findings that were investigated and found not to need a code change say so
rather than being silently dropped.

**Verification standard.** Every commit on this branch must leave `cargo test --workspace` green,
`cargo clippy --all-targets --all-features` clean, and `cargo fmt --all --check` clean. Where a change
is Android-only and cannot be compiled here, the entry says so explicitly and names what a maintainer
must run.

| # | Finding | Severity | Status |
| :-- | :--- | :--- | :--- |
| 1 | `Lock::Timer` was claimable — one pipe line ended any timer lock | **P0** | **Fixed** |
| 2 | Windows service never used the trusted clock | **P0** | **Fixed** |
| 3 | Service `Stop` obeyed, not refused; uninstall failed open | **P0** | **Fixed** |
| 4 | Android UI reconciled with the raw wall clock (A-1) | **P0** | **Fixed** |
| 5 | First block lost after the accessibility detour (F-1) | **P0** | **Fixed** |
| 6 | Windows had no way to start a block (F-16) | **P0** | Pending |
| 7 | README documented a config the service never reads (F-17) | **P0** | Pending |
| 8 | `Lock.Confirm` could never be satisfied (F-4) | **P1** | **Fixed** |
| 9 | Emergency pass unreachable; block screen claimed it was spent (F-5) | **P1** | **Fixed** |
| 10 | `[emergency]` never validated | **P1** | Pending |
| 11 | `end_with_pass` took the caller's word | **P1** | Pending |
| 12 | `UiState.message` set from 8 places, rendered on 2 (F-29) | **P1** | Pending |
| 13 | Unparseable config looked empty; Save destroyed it (F-30) | **P1** | Pending |
| 14 | Failed calendar read looked like an empty diary (F-31) | **P1** | Pending |
| 15 | Delete-profile: no confirm, refusal never read (F-32) | **P1** | Pending |
| 16 | Three false product claims, incl. "no internet permission" (F-46) | **P1** | **Fixed** |
| 17 | Nav/Switch touch targets under 48dp (F-38) | **P1** | Pending |
| 18 | Control channel unbounded read / no timeout / serial accept (P1-1) | **P1** | Pending |
| 19 | Unverified watchdog image executed as SYSTEM (P1-0, first half) | **P1** | **Fixed** |
| 20 | `%ProgramData%\Curfew` has no explicit ACL (P1-0, second half) | **P1** | Pending |
| 21 | Windows: no feedback, silent wrong password, config edits inert | **P1** | Pending |
| 22 | "Start without it" skipped the long-timer confirmation | **P1** | **Fixed** |

*(The table is updated as work lands. **"Pending" means exactly that** — the row is a plan, not a
claim. This table is the one place in the document where it would be easy to overstate progress, so
it is corrected against `git log` whenever an entry is added.)*

### Fixed so far, by commit

| Commit | What |
| :--- | :--- |
| `763ab9e` | A timer lock is not something a caller may claim (entry 1) |
| `843bc52` | The Windows service judges locks against a trusted clock (entry 2) |
| `e095b4f` | A stop is refused while a lock is held, and uninstall fails shut (entry 3) |
| `d7e1f40` | A watchdog image is verified by content, not by size and timestamp (entry 19) |
| *(next)* | The Android UI reconciles against the trusted clock (entry 4) |

### A note on the Android verification environment

The Android module **does** build and test on this machine, contrary to what the review assumed.
`./gradlew :app:compileDebugKotlin` and `:app:testDebugUnitTest` both run green.

It needed one thing that is not in the repository and not in CI: **a JDK the Gradle version
supports.** This machine has only JDK 25 (`JAVA_HOME=C:\Program Files\Java\jdk-25.0.2`, and Android
Studio's bundled JBR is 25.0.3 too), and Gradle 8.14.3 refuses it — it fails with a bare `What went
wrong: 25.0.2`, which reads like a config error rather than a version ceiling. CI pins
`java-version: '21'`, which is why CI has never seen this. Temurin 21 (aarch64, matching this
machine) was fetched to `C:\jdk21` and every Android command below was run with
`JAVA_HOME=C:\jdk21\jdk-21.0.12.1+1`.

**Worth fixing in the repo:** nothing enforces the JDK version locally. A `.java-version` file, or a
toolchain declaration in `android/build.gradle.kts`, would turn "Gradle refused Java 25 with a
one-line error" into an instruction.

---

## 1. `Lock::Timer` was a claimable condition — one pipe line ended any timer lock

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P0-1 (the worst bug in either report).

**What was wrong.** `claimable()` decides which lock conditions a *caller* is allowed to assert it
has satisfied. `Lock::Timer` was in that list, on both platforms:

```rust
// crates/curfew-win/src/tick.rs
fn claimable(lock: &curfew_core::Lock) -> bool {
    matches!(lock, Lock::Timer | Lock::Confirm | Lock::Challenge { .. })
}
```

`Lock::Confirm` and `Lock::Challenge` belong there: both are shown entirely in the UI, so the UI is
the only possible witness. `Lock::Timer` does not: its whole meaning is the machine fact `ends_at`,
which `LockSet::can_release` already honours through `is_expired`:

```rust
// crates/curfew-core/src/lock.rs
pub fn can_release(&self, now: Timestamp, satisfied: &BTreeSet<Lock>) -> bool {
    if self.is_expired(now) { return true; }
    self.conditions.is_subset(satisfied)
}
```

So accepting a caller's word for `Timer` added nothing that expiry did not already grant, and it let
a caller *assert* that a timer had run out. The control pipe is deliberately open to
`BUILTIN\Interactive Users` (`PIPE_SDDL`, `runner.rs:284`), so the whole exploit was one line:

```
echo {"request":"end","id":"...","satisfied":[{"kind":"timer"}]} > \\.\pipe\curfew.sock
```

No administrator, no UI, no tray. And it hit the flagship configuration: the weekly window in
`crates/curfew-core/tests/golden/example.toml` is locked exactly `locks = [{ kind = "timer" }]`, and
`curfew-cli` maps its `timer` keyword to `Lock::Timer`.

**Why it survived a review and 799 tests.** The test suite encoded it as correct behaviour.
`tests/tick.rs` called `Sessions::end` *directly* with `{Lock::Timer}` and the comment
`"the timer had run out"` — bypassing `claimable` entirely, at 09:40 for a window that ran to 12:00.

**What was changed.**

1. `crates/curfew-win/src/tick.rs` — removed `Lock::Timer` from `claimable`, with a comment
   explaining why its absence is load-bearing rather than an omission.
2. `crates/curfew-ffi/src/lib.rs` — the same, for Android.
3. `crates/curfew-win/tests/tick.rs` — replaced the test that asserted the bypass with two tests:
   - `claiming_a_timer_does_not_release_a_timer_lock` — the regression guard. Builds a real
     `Enforcer`, calls `handle(Request::End { satisfied: [Timer] })` at 09:40, asserts `Refused`, and
     then asserts that reaching `AFTER` (13:00) *does* reap it. Both halves matter: the refusal alone
     would also pass if expiry had broken.
   - `a_session_ended_by_hand_is_kept_too` — kept, because its intent (a hand-ended session still
     writes history) is legitimate. It now uses a `confirm` lock via a new `enforcer_with` helper, so
     it exercises a condition a caller genuinely may claim.

**Why this shape.** The alternative was to keep `Timer` claimable and validate it against `ends_at`
inside `claimable`. Rejected: `claimable` is a pure predicate over a `Lock` with no access to time,
and threading `now` through it would have duplicated a check `can_release` already makes correctly.
Removing the variant is the smaller, safer change and makes the invariant structural.

**Not changed.** `Lock::Confirm` and `Lock::Challenge` remain claimable. Both are still UI-witnessed
and there is no machine fact to check. (Note F-4 in the interaction review: Android currently never
*satisfies* `Confirm` — that is a separate, UI-side bug, fixed in entry 8.)

**Verification.** `cargo test -p curfew-win --test tick` → 17 passed. Full workspace green.
Clippy clean.

---

## 2. The Windows service never used the trusted clock

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P0-2.

**What was wrong.** The service read `SystemTime` and used it as the instant locks are judged
against. That clock is user-settable, so this was the cheapest bypass in the product — and it worked
through **two** doors, which the review named as one:

1. **The tick loop** (`runner.rs`), where `Sessions::reap` drops any session whose `ends_at` has
   passed.
2. **The control channel** (`serve`), where a request reaches `LockSet::can_release`, which grants a
   release the moment `is_expired(now)` holds. So `Request::End` with **no conditions claimed at
   all** ended a timer lock, once the caller had wound the clock forward. The second door is not in
   the original finding and would have survived fixing only the first.

Either way it left nothing behind: the session was written to history as one that had genuinely run
out, the hosts file was given back, and the resolver emptied.

`docs/ARCHITECTURE.md` lists "change the clock" as in scope and `GAPS C6` specifies the mechanism.
`curfew_core::clock` has implemented it since it was written and Android has called it all along.
**The Windows half was simply never wired to it** — `git grep -l ClockWitness -- crates/` returned
only core, its tests, and `curfew-ffi`.

**What was changed.**

- `Enforcer` gains `clock: Option<ClockWitness>`, `clock_verdict: Option<Verdict>` and
  `clock_notice: Option<String>`.
- `Enforcer::observe_clock(wall, uptime) -> Timestamp` — folds in a reading and returns the instant
  to judge against. It calls `observe_boot` **first**, because the witness compares against the boot
  id and a witness told about a boot after it has already seen a reading from that boot reads the
  change as a reboot and credits the wall clock's whole delta.
- `runner::now()` → `runner::wall_now()`, documented as untrusted and naming its two honest uses
  (reporting to a human, stamping a log line). Nothing on an enforcement path reads it any more.
- Both `serve` and the tick loop take their instant from the witness.
- `Persisted.clock` carries the baseline across restarts. This is the part that is easy to miss:
  without it, *stopping the service, moving the clock, starting it again* is the same bypass, because
  a fresh witness takes its first reading on faith.
- `Status.clock_warning` carries a sentence, shown on the window's "Is it working" page in **amber**,
  not red — a refused clock change is not a fault and nothing was lost.

**One design decision worth recording.** The notice is **sticky**, not recomputed from the latest
verdict. My first version recomputed it, and a test caught that the notice disappeared on the very
next tick: the pass after a tamper reads a clock that has caught up with uptime and is therefore
unremarkable, so the message was true for ~2 seconds and gone — which no UI polling at a sane rate
would ever show. It is now replaced by a later notable reading and cleared by restarting the service.
`tests/clock.rs` asserts it survives a following quiet reading.

**Not changed.** The residual the design already accepts and documents: a wall-clock jump **across a
reboot** is credited, because an honest overnight shutdown is indistinguishable from an hour stolen
in the firmware. That is `clock.rs`'s stated position and `tests/clock.rs` pins it — including that
the credited time is labelled as a caveat rather than an accusation.

**Verification.** New `crates/curfew-win/tests/clock.rs`, six tests, all of which fail against the
old code. Full suite: **806 green**. Clippy and fmt clean.

---

## 3. Service `Stop` was obeyed rather than refused; uninstall failed open

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P0-3.

**What was wrong.** Two halves of one hole.

The control handler's comment said *"A stop is a request, and while a lock is held it is refused"* —
and the code set the loop's stop flag and returned `NoError` for **both** `Stop` and `Shutdown`,
whatever was running. `NoError` *is* acceptance. So the service went down on any stop request, from
Task Manager's "End task", the Services console, or `sc stop Curfew`, with no administrator.

That mattered more than it looks, because the installer stops the service before replacing its files
(`<ServiceControl Stop="both" Wait="yes" />`), and the deferred `curfew.exe uninstall` then ran
against a **stopped** service. The uninstall guard was shaped as "refuse if the service answered
*and* listed a running session" with a `_ => {}` catch-all — so `Err` (what asking a stopped service
returns) fell straight through to `service::uninstall()`, which has no lock check of its own.

`Settings → Apps → Curfew → Uninstall` during a locked session therefore succeeded, and
`INSTALL.txt` and the `.wxs` both described protection that was not there.

**What was changed.**

1. **`service.rs`** — `Stop` and `Shutdown` are now separate arms. `Shutdown` is always obeyed: the
   machine is going away, the lock is carried across the reboot by the state file, and refusing would
   only hang the machine on its way down. `Stop` returns
   `ServiceControlHandlerResult::Other(ERROR_SERVICE_CANNOT_ACCEPT_CTRL)` (1061, spelled out because
   the enabled `windows-sys` features do not export it) while `watchdog::locks_running` says a lock
   is held.
2. **`main.rs`** — the uninstall guard is inverted to **fail closed**. It now needs a *positive*
   "nothing is running" from two witnesses: the service's answer, and the state file directly (the
   same witness the watchdog uses, and the only one left when nothing is answering). `Err`,
   `Ok(something unexpected)`, and an unreadable state file all refuse.
3. The decision is extracted into `refused_uninstall(service_says_running, state_says_locked)` and
   unit-tested, because **the bug was a default** and a default is invisible in a `match`. It is
   written as "refuse unless both agree there is nothing running" so a future reader adding a third
   witness cannot widen the hole by accident.

**A consequence worth stating.** The installer's `Stop="both"` will now *fail* when a lock is
running, which aborts and rolls back the uninstall — exactly what the `.wxs` comment already
promised. An upgrade over a locked service will therefore also refuse until the lock ends. That is
the intended trade: the alternative is replacing the binaries of a service that is holding a lock.

**Verification.** 3 new tests in `curfew-svc`, including the specific regression — "an unreachable
service refuses rather than permits". `cargo test -p curfew-svc`: 15 passed. Clippy and fmt clean.

**One residual, recorded rather than fixed.** The handler reads the state file on the SCM's control
thread, which is file I/O inside a service callback. It is a single bounded read and `windows-service`
only requires the handler not to block indefinitely, so this is acceptable — but it is the reason the
handler cannot ask the service directly, and worth knowing if the state file ever grows.

---

## 19. The watchdog image was verified by size and timestamp, then run as SYSTEM

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P1-0, **first half** only. The second half — the
missing ACL on `%ProgramData%\Curfew` — is entry 20 and is still open.

**What was wrong.** The watchdog deliberately copies itself out of `Program Files` so the installer
has one file to replace rather than two, and spawns that copy as SYSTEM. It decided whether to
re-copy by comparing **length and modification time**:

```rust
let same = existing.len() == current.len()
    && match (existing.modified(), current.modified()) {
        (Ok(there), Ok(here)) => there >= here,
        _ => false,
    };
if same { return Ok(()); }
```

The module's own comment asserts that `%ProgramData%\Curfew` "is administrator-owned". **No code in
the repository sets that ACL**, and `C:\ProgramData` grants `BUILTIN\Users` container-inherited
`Write` (`FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY`). And `curfew-watchdog.exe` **does not exist until
the service first runs** — so an unprivileged user can create it first, padded to the length of
`curfew.exe` with a newer timestamp. Both halves of the comparison are attacker-controlled, the copy
is skipped, and the service spawns their binary as SYSTEM.

**What was changed.**

- `refresh` now **reads both files and compares contents**. A difference is a difference, whatever
  the metadata says. Reading is the same cost as the `std::fs::copy` it replaces, and an identical
  image is left untouched, so a running watchdog holding the file open still costs nothing.
- It returns `bool` — "is this path safe to run from" — rather than `io::Result<()>`. **`spawn` only
  uses the image when that is `true`**, falling back to `std::env::current_exe()`, the one file whose
  contents are not in question.
- Failure is now reported and *not* fatal in the right way: the old code printed "not refreshed" and
  spawned the stale image anyway.

**The trade, stated.** Falling back to `curfew.exe` can make the installer want a reboot — which is
the exact reason this separate image exists (see the function's doc comment). A reboot request is a
far better outcome than executing an unverified binary as SYSTEM, and it only happens on the path
where the image could not be verified.

**Not fixed here.** The ACL itself. Fixing the comparison closes the *spawn* path; anyone who can
write the directory can still pre-create `calendars/`, `dns-before.json` and the state file. That is
entry 20, and it needs a `curfew install` change rather than a library one.

**Verification.** 4 new tests, including one that reproduces the exploit exactly: same length,
different content, newer timestamp — and asserts the planted bytes are replaced. The other three
cover the identical image (left alone), a missing image (created), and an unreadable build (never
vouched for). `cargo test -p curfew-svc`: 19 passed.

---

## 4. The Android UI judged sessions against the raw wall clock

**Findings:** `UX_INTERACTION_REVIEW.md` A-1 (P0) — the Android twin of entry 2.

**What was wrong.** The enforcement *service* does the right thing — `EnforcementService.kt:168`
calls `runtime.trustedNow()`. The **UI** did not. Four paths read `runtime.clock.now()`, which is
`System.currentTimeMillis()` and therefore settable:

| Path | What the raw clock decided |
| :--- | :--- |
| `refreshFast()` (`:107`) | whether any session's `endsAt <= now` — and therefore whether to call `reconcileNow()` |
| `reconcileNow()` (`:770`) | the instant handed to `runtime.reconcile`, which **reaps sessions** |
| `startTimer()` (`:533`) | `endsAt = clock.now() + seconds` — so a clock moved *back* stretches a lock |
| `refresh()` (`:141`) | the instant behind `activations`, `stats`, and every countdown on screen |

The bypass: start a 90-minute timer, set the clock forward two hours, **open Curfew**. Within a
second `refreshFast` sees `endsAt <= rawNow`, `reconcileNow()` reaps the session against the same
forged instant, and it is written to the audit trail as having ended normally. `trustedNow()` is
never consulted on that path — it is only reached from the service tick, by which point the session
is gone. This is the T6 bypass `ARCHITECTURE.md:165-166` lists as in scope, executed from the app's
own UI.

**What was changed.**

- `CurfewRuntime.trustedNow()` is split. The reading-and-deciding part moves into a private
  `observeClocks()`; `trustedNow()` keeps its behaviour (including the two `db.state().put` calls)
  for the service, and a new **`trustedNowLight()`** does the same decision without persisting.
- Rationale for the split rather than reusing `trustedNow` everywhere: the UI beat runs **every
  second**, and `trustedNow` puts two rows through the database per call. The decision is identical;
  only the durability differs, and the service persists it within a couple of seconds anyway.
- All four UI paths now use `trustedNowLight()`.
- `refreshFast()` became `suspend` (it had exactly one caller, inside a coroutine).

**Why the tamper flag is still raised on the light path.** A screen is the one place a person can be
told their clock moved, so `observeClocks()` sets `_clockTamper` in both cases. Only the persistence
is skipped.

**Not changed, deliberately.** `clock.now()` remains in five places that are *not* enforcement:
`sync.invite`/`accept`/`revoke` (pairing windows, which are wall-clock concepts and expire on their
own), and two display formatters (`relative`, `describePassRefusal`). Changing those would make a
message about "in 24 hours" disagree with the user's own clock for no security benefit.

**Verification — and an honest limit.**

`./gradlew :app:compileDebugKotlin` — **clean**, no errors or warnings in the touched files. The
change is type-checked, and the coroutine changes are correct as far as the compiler can tell.

`./gradlew :app:testDebugUnitTest` — **does not pass on this machine, and did not before this
change.** 121 of 164 tests fail, all with the same root cause:

```
java.lang.UnsatisfiedLinkError: no conscrypt_openjdk_jni-windows-aarch_64
    in java.library.path: ...
```

That is **Conscrypt**, pulled in transitively for the desktop JVM by `sqlcipher-android`, and it
**ships no Windows-ARM64 native build**. No amount of `jna.library.path` tuning reaches it; it is a
missing binary inside a third-party artifact. (JNA itself is fine — 5.15.0 does contain
`win32-aarch64/jnidispatch.dll` — and `curfew_ffi.dll` builds correctly at
`~/.cache/curfew-target/aarch64-pc-windows-msvc/release/`.)

**I confirmed this is pre-existing** by stashing both changed files and re-running the suite on the
untouched tree: the same tests fail the same way. This change neither caused nor worsened it.

Three things worth recording rather than leaving implicit:

- **CI cannot see this**, because it runs `ubuntu-latest`, where Conscrypt does ship
  `linux-x86_64`. So the Android suite really is exercised in CI; this is a local-host limitation on
  an ARM64 Windows machine, not evidence that the suite is broken.
- **This change is therefore not covered by an executing test.** Its reasoning is a direct
  translation of entry 2, which *is* covered by six Rust tests, and the Kotlin edit is four call
  sites swapping one accessor for another — but that is an argument, not evidence, and it is said
  plainly here rather than dressed up as a green suite.
- **What would close it properly** is a Windows-ARM64 or Linux-x86_64 host to run the suite on. A
  JVM test for `trustedNowLight` would need the FFI loaded and would hit the same wall.

---

## 5 and 22. The first block a user configures is the block that starts

**Findings:** `UX_INTERACTION_REVIEW.md` F-1 (P0), plus the "Start without it" defect from the UI audit.

**What was wrong — two bugs in one dialog.**

`TimerScreen`'s accessibility dialog has two buttons. The **primary** one, "Turn it on", did this:

```kotlin
TextButton(onClick = {
    pending = null                                   // ← forgets what the user configured
    Grant.Accessibility.settingsIntent(context)?.let { context.startActivity(it) }
}) { Text("Turn it on") }
```

It cleared `pending` and opened Settings, and **nothing started the timer on the way back.** There is
no `LaunchedEffect`, `ResumeEffect` or lifecycle observer anywhere in the file. So a user who did
exactly what they were asked ended up with no block, no receipt, and a Now screen saying nothing was
blocked. The *dismiss* path ("Start without it") worked; the recommended path did not.

This is the first block a new user ever attempts, and `UX-FLOWS.md` principle 1 is "the first block
must happen in under a minute". The honest count with the permission detour was seven interactions and
a Settings round-trip, with the app behaving as if the first five never happened.

Separately (entry 22), the four-hour confirmation was checked **only** on the primary button. Both
dialog paths skipped it, so "Start without it" would start the strongest lock in the app from a
twelve-hour dial in one tap — the exact case `LONG_MINUTES` exists to catch.

**What was changed.**

- `pending` is **no longer cleared** by "Turn it on" — it is what the resume effect watches, and
  clearing it was the whole bug.
- A `LifecycleResumeEffect(pending)` starts the block once `Grant.Accessibility.isGranted` is true,
  reading `minutes` and `strength` **when it fires** rather than capturing them, so a value changed
  before leaving is the one that takes effect.
- All three routes to a start now go through one local `begin(id)`: the primary button, the return
  from Settings, and "Start without it". One function means a route added later cannot quietly skip a
  check the others make — which is exactly how the long-timer bug got in.
- The dialog's copy says what actually happens on each path now: *"Turn it on and this 30m block
  starts the moment you come back. Or start it now, and nothing will be blocked until the switch is
  on."* The old sentence described only the dismiss path.

**Verification.** `:app:compileDebugKotlin` clean, no warnings. **Not covered by an executing test** —
see the Conscrypt limitation in entry 4. This is a lifecycle fix whose correct behaviour is only
observable by leaving the app and returning, so a JVM test could not reach it even with the FFI
loaded; it needs a device.

---

## 8. `Lock.Confirm` never confirmed anything

**Findings:** `UX_INTERACTION_REVIEW.md` F-4 (P1).

**What was wrong.** `TimerScreen.kt:64` sells the strength `Confirm("Ask me first", "One confirmation,
so it is never an accident.")` — it is the **default**, and it is offered in four more places
(`ScheduleEditor` "Ask before ending", `ProfileEditScreen` twice, `CalendarScreen`). The ending path
branched on `Lock.Challenge` only:

```kotlin
val challenge = session.lock.conditions.filterIsInstance<Lock.Challenge>().firstOrNull()
if (challenge == null) { finish(session, emptyList()) } else { ... }
```

`Lock.Confirm` is not a `Challenge`, so it went to the core with an empty satisfied set. The core
refused, correctly — the condition was unmet — and the refusal dialog offered nothing that could meet
it. A grep for `Lock.Confirm` under `android/app/src/main` returned six hits, **all constructing the
lock, none satisfying it.**

So the default strength, and the lock behind a seeded weeknight window, was a lock with **no early
exit at all** — the inverse of invariant 2's "except the unlock conditions the user chose".

**What was changed.** `NowScreen.end()` checks for `Lock.Confirm` first and opens a confirmation
naming the profile and the exit ("Keep it running" / "End it"). Confirming calls
`finish(session, listOf(Lock.Confirm))` — the one place in the UI that names that condition, which is
exactly the kind of claim `claimable` exists to permit (a dialog the core cannot see for itself).

**Worth noting.** This is the mirror of entry 1. There, `Lock::Timer` should never have been
*claimable*; here, `Lock::Confirm` should always have been *satisfiable* and had no code path at all.
Both are the same class of bug: a lock condition the UI and the core disagreed about.

---

## 9. The block screen said the user had spent a pass they were never given

**Findings:** `UX_INTERACTION_REVIEW.md` F-5 (P1), spread across two screens.

**What was wrong.** `EmergencyPolicy.passes` defaults to **0** — `emergency.rs:35-38`: *"Zero — the
default — disables the hatch entirely."* Nothing in either UI creates one; the only route is editing
`curfew.toml`. Yet the block screen printed, when `passes == 0`:

> **"No emergency pass left this month"**

*"left this month"* tells the user they **spent** a ration they were never given. And
`NowScreen.kt:627` then **deliberately hid** the honest explanation: the "no pass, and here is why"
line rendered only when `passRefusal !is PassRefusal.Disabled` — suppressing the single case a default
user is in. `TimerScreen.kt:67` made the same promise from the other side, labelling the strongest
lock *"No way out but an emergency pass"*.

**What was changed.**

- `BlockActivity` reads the actual refusal (`context.curfew.passRefusal()`, already exposed) and
  renders it through `describePassRefusal` — the one place that copy lives, and the one that already
  had honest wording for all three cases. Centred, because it is a sentence now rather than a label.
- `NowScreen` no longer excludes `Disabled`. The old comment — *"a hatch nobody switched on is not
  missing, it is unwanted"* — is a fair instinct and the wrong call: a fresh install configures no
  passes, so `Disabled` is the **only** state a new user can be in, and excluding it meant the one
  person who goes looking for the hatch is the one person not told why it is absent.
- `TimerScreen`'s note now reads *"Nothing ends this early. The 24-hour release is the way out."* —
  true on a default install, where the release **is** offered (`NowScreen` gates it on
  `isLocked && delayedReleaseAt == null`, and a timer session is locked).

**Not changed.** The default of zero passes. `emergency.rs` argues for it — *"somebody who wants an
escape hatch says so in their config, which means the decision is made while calm rather than at the
moment of wanting out"* — and that is a product decision, not a bug. The bug was the copy and the
suppression.

---

## 16. Three product claims were false, including the one a user can check

**Findings:** `UX_INTERACTION_REVIEW.md` F-46 (P1).

**What was wrong.** Three screens and one manifest comment asserted Curfew **"has no internet
permission at all"** — `SettingsScreen.kt:207`, `HealthScreen.kt:183` (*"so nothing it records can
leave this device even if it wanted to"*), `UsageScreen.kt:41`, and `AndroidManifest.xml:7`.
`AndroidManifest.xml:54` declares `android.permission.INTERNET`, because LAN sync needs it.

This is the most damaging copy finding in the review, for a reason worth stating: it is the app's core
privacy promise, on the exact screen a user opens to check that promise, and it is falsifiable in ten
seconds in Android Settings. Everything beside it on that card — encryption at rest, no telemetry,
nothing to a server — is true, and one checkable falsehood costs the rest their credibility.

**What was changed.** All four now state the narrower, true, still-strong claim, following wording
`DevicesScreen` already used correctly: *"Nothing goes to a server. Your devices sync directly to each
other — no account, no cloud, and nothing you record leaves the devices you paired."* The manifest
comment now says why it holds `INTERNET` and records that the previous comment was false.

**Not changed.** The permission itself. It is needed, and the design decision behind it — direct
pairing, no account — is sound. Only the claim was wrong.

---

## 22. "Start without it" skipped the long-timer confirmation

**Findings:** UI audit; fixed with entry 5 and recorded separately because it is a distinct defect.

**What was wrong.** `LONG_MINUTES` (4 hours) exists so that "a thumb that dragged too far" cannot cost
an evening. It was checked on the primary button and nowhere else, so declining the accessibility
permission started whatever was on the dial — up to twelve hours — with no confirmation. With the
strongest lock selected by default, that is an unbreakable twelve-hour block in one tap.

**What was changed.** Every route now goes through `begin(id)`, which performs that check. The same fix
as entry 5, which is why they share a commit.

**A pattern worth naming.** Entry 5, entry 22 and the original `Lock::Timer` bug are one shape: a check
that exists in one code path and not in its siblings. Where a rule matters, route every path through
one function — and where a decision encodes a rule, make the **default** the safe answer, because the
bug is always in the branch nobody wrote.
