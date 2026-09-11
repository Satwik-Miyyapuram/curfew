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
| 6 | Windows had no way to start a block (F-16) | **P0** | **Fixed** |
| 7 | README documented a config the service never reads (F-17) | **P0** | **Fixed** |
| 8 | `Lock.Confirm` could never be satisfied (F-4) | **P1** | **Fixed** |
| 9 | Emergency pass unreachable; block screen claimed it was spent (F-5) | **P1** | **Fixed** |
| 10 | `[emergency]` never validated | **P1** | **Fixed** |
| 11 | `end_with_pass` took the caller's word | **P1** | **Fixed** |
| 12 | Three false product claims, incl. "no internet permission" (F-46) | **P1** | **Fixed** |
| 13 | "Start without it" skipped the long-timer confirmation | **P1** | **Fixed** |
| 14 | The tray could never end a `Confirm`-locked session | **P1** | **Fixed** |
| 15 | The tray had no route to the window | **P1** | **Fixed** |
| 16 | A wrong Windows password failed silently in the window (F-20) | **P1** | **Fixed** |
| 17 | Exit paths chosen by comparing display strings (P2-5) | **P1** | **Fixed** |
| 18 | Unverified watchdog image executed as SYSTEM (P1-0, first half) | **P1** | **Fixed** |
| 19 | `UiState.message` set from 8 places, rendered on 2 (F-29) | **P1** | **Fixed** |
| 20 | A failed config read looked empty; Save and Export destroyed it (F-30) | **P1** | **Fixed** |
| 21 | A failed calendar read looked like an empty diary (F-31) | **P1** | **Fixed** |
| 22 | Delete-profile: no confirm, refusal never read, screen left early (F-32) | **P1** | **Fixed** |
| 23 | Nav/Switch touch targets under 48dp (F-38) | **P1** | **Fixed, in part — the review's headline figure was wrong** (entry 23) |
| 24 | Control channel unbounded read / serial accept (P1-1) | **P1** | **Fixed** (read cap + concurrency; read deadline still impossible — see entry 24) |
| 25 | `%ProgramData%\Curfew` had no explicit ACL (P1-0, second half) | **P1** | **Fixed** |
| 26 | Config edits do not take effect and nothing says so (F-23) | **P1** | **Fixed** |
| 27 | A blocked site shows the browser's own error page (F-21) | **P1** | **Fixed as far as the design allows** — the "serve a page" fix is already refused in-code (entry 27) |
| 28 | Missing Windows nav pages: usage and devices (F-19) | **P1** | **Half fixed** — "Where time went" built; "Devices" turned out to be a missing feature, not a missing page (entry 28) |
| 29 | `AppPickerScreen` showed every app unticked after a failed config read | **P2** | **Fixed** |

*(The table is updated as work lands. **"Pending" means exactly that** — the row is a plan, not a
claim. This table is the one place in the document where it would be easy to overstate progress, so
it is corrected against `git log` whenever an entry is added.)*

### Fixed so far, by commit

| Commit | What |
| :--- | :--- |
| `763ab9e` | A timer lock is not something a caller may claim (entry 1) |
| `843bc52` | The Windows service judges locks against a trusted clock (entry 2) |
| `e095b4f` | A stop is refused while a lock is held, and uninstall fails shut (entry 3) |
| `d7e1f40` | A watchdog image is verified by content, not by size and timestamp (entry 18) |
| `5dd09d9` | The Android UI judges sessions against the trusted clock (entry 4) |
| `f2f0956` | The block the user configured is the block that starts (entries 5, 13) |
| `fde2973` | Locks that promised an exit now have one (entries 8, 9, 12) |
| `1a63581` | The emergency ration is enforced by the type, and the docs name the real config (entries 7, 10, 11) |
| `668c6df` | Windows can start a block at last (entries 6, 14, 15, 16, 17) |
| `44b3c63` | An edit to the config reaches the service that enforces it (entry 26) |
| `dbdfb07` | The control channel stops reading without a limit, and stops serving one client at a time (entry 24) |
| `d1bc146` | The data directory is made what it was always claimed to be (entry 25) |
| `34af161` | One place for the app to say something, on whatever screen raised it (entry 19) |
| `cd1021f` | A read that failed is not an empty result (entries 20, 21), and a delete asks first (entry 22) |
| `7f11fbe` | The nav and the toggle get hit areas that can be hit (entry 23) |
| `8c41f09` | A blocked site's symptom is named where the user will read it (entry 27) |
| `23dbd92` | The window gets the "Where time went" page the design always had (entry 28) |
| *(this commit)* | The app picker stops claiming you block nothing when it cannot see (entry 29) |

### A note on the Android verification environment

The Android module **builds** on this machine, and `./gradlew :app:compileDebugKotlin` runs clean. It
needed one thing that is not in the repository and not in CI: **a JDK the Gradle version supports.**
This machine has only JDK 25 (`JAVA_HOME=C:\Program Files\Java\jdk-25.0.2`, and Android Studio's
bundled JBR is 25.0.3 too), and Gradle 8.14.3 refuses it — it fails with a bare `What went
wrong: 25.0.2`, which reads like a config error rather than a version ceiling. CI pins
`java-version: '21'`, which is why CI has never seen this. Temurin 21 (aarch64, matching this
machine) was fetched to `C:\jdk21`, and every Android command in this log was run with
`JAVA_HOME=C:\jdk21\jdk-21.0.12.1+1`.

**Worth fixing in the repo:** nothing enforces the JDK version locally. A `.java-version` file, or a
toolchain declaration in `android/build.gradle.kts`, would turn "Gradle refused Java 25 with a
one-line error" into an instruction.

#### The unit suite does not run on this machine, and this note used to say it did

An earlier revision of this section claimed `:app:testDebugUnitTest` "runs green". **That was wrong**,
and it is corrected here rather than deleted, because it is the same class of mistake the status table
was corrected for: a claim about verification that the verification did not support. The table is not
the only place this document could overstate things, and this was the other one.

What is actually true, measured in this session:

```
:app:testDebugUnitTest  →  121 of 164 fail, 43 pass
all 121: java.lang.UnsatisfiedLinkError: no conscrypt_openjdk_jni-windows-aarch_64
```

**The cause is Robolectric, not the Rust core** — and the first attempt at this diagnosis got that
wrong too. Entry 4 implied the native dependency came from `sqlcipher-android` pulling Conscrypt in
for the desktop JVM. It does not. The isolation experiment:

| Test class | Robolectric? | Result |
| :--- | :--- | :--- |
| `WordsTest`, `DialDragTest`, `ScheduleEditorTest` | no | **pass** (`BUILD SUCCESSFUL`) |
| `EnforcerTest`, `CurfewRuntimeTest`, … | yes | fail, Conscrypt |

Every failing class is a `@RunWith(RobolectricTestRunner::class)` class and every passing class is
not, so **Robolectric 4.14's native runtime is what needs Conscrypt**, and it ships no
`windows-aarch_64` build. The Rust core is not implicated: JNA 5.15.0 *does* ship
`win32-aarch64/jnidispatch.dll`, and `curfew_ffi.dll` builds and loads.

**Consequences, stated plainly.**

- CI is unaffected: it runs `ubuntu-latest`, where Conscrypt has a `linux-x86_64` build, and `ci.yml`
  does run `:app:testDebugUnitTest`.
- On this host there is **no executable Android coverage of any kind** — not for the FFI, not for the
  Compose UI. Every Android change in this log is therefore compile-verified and reasoned, and each
  entry says so in its own words rather than inheriting a general green tick.
- The 43 passing tests are the pure-logic ones, and they were used wherever they applied.

**A test worth having, for whoever has a working host.** Compose layout can be measured in a unit test
— `createComposeRule()` under Robolectric, `getUnclippedBoundsInRoot()`, then assert `>= Dsn.MinTouch`
— and that would be a permanent CI guard on the whole F-38 class, which is exactly the kind of defect
(right *look*, wrong *hit area*) with no other executable check. It was attempted here and abandoned:
`androidx.compose.ui.test.junit4` was added to `testImplementation`, a measurement test was written,
and it failed with the same
`java.lang.UnsatisfiedLinkError: … conscrypt_openjdk_jni-windows-aarch_64`. **The dependency change was
reverted rather than shipped**, because a test I cannot run is a test I cannot claim, and a red build in
CI is a worse deliverable than an honest gap. The recipe is above.

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

That is **Conscrypt**, and it **ships no Windows-ARM64 native build**. No amount of `jna.library.path`
tuning reaches it; it is a missing binary inside a third-party artifact. (JNA itself is fine — 5.15.0
does contain `win32-aarch64/jnidispatch.dll` — and `curfew_ffi.dll` builds correctly at
`~/.cache/curfew-target/aarch64-pc-windows-msvc/release/`.)

**Correction.** This entry first attributed Conscrypt to `sqlcipher-android` pulling it in for the
desktop JVM. That was wrong, and the isolation experiment is in "A note on the Android verification
environment" above: **every failing class is a Robolectric class and every passing class is not**, so
the requirement comes from Robolectric 4.14's own native runtime. The practical consequence is the
same — no Android tests run here — but the cause matters, because it rules out the fix the original
wording implied (chasing the SQLCipher dependency) and points at the real one.

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

---

## 10. `[emergency]` was never validated

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P1-5.

**What was wrong.** `Config::validate` checked the timezone, profile ids and names, zero budgets, zero
launch limits, token ids and fingerprints, weekly minutes and days, schedule profile references and
calendar sources — and **never looked at `self.emergency` at all**. `git grep emergency -- config.rs`
returned the field declaration, its default, and an unused import.

With `window_seconds = 0`, `EmergencyPolicy::recent` computes `since = now - 0`, so it never finds a
spent pass; `QuotaSpent` and `CoolingDown` can never fire and `remaining()` always reports the full
quota. **The escape hatch becomes unlimited.** With `cooldown_seconds = 0` the documented "minimum gap
between two uses" disappears.

`validate`'s own doc-comment states the principle it was breaking: *"a config that would behave
surprisingly is rejected at load, where the user can still see why, rather than at 4am inside a lock."*
The zero-budget refusal at `:434-446` is the precedent.

**What was changed.** Three checks, in the existing style, each naming the field and the fix:

- `emergency.window_seconds == 0` → refused, pointing at `passes = 0` as the way to switch the hatch
  off deliberately.
- `emergency.cooldown_seconds == 0` **when `passes > 0`** → refused. Gated on `passes`, because with
  the hatch off there is nothing to ration and the number is meaningless; turning it on is when it
  starts to matter.
- `Refill::Rolling { seconds: 0 }` → refused. The same defect one level down: `used_since(Some(now))`
  would match only rollups stamped at or after `now`, so the budget could never be exhausted and the
  rule would never fire.

**Verification.** 4 new tests in `crates/curfew-core/tests/config.rs`, including the negative case — a
zero cooldown *is* accepted while the hatch is off, so the check is not merely refusing everything.
`cargo test --workspace`: **817 passed, 0 failed.**

---

## 11. The emergency ration was enforced by convention, not by the type

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P1-4.

**What was wrong.** `Pass` was `pub struct Pass { pub at: Timestamp }` — publicly constructible — and
`Sessions::end_with_pass` opened with `let _ = pass;` before declaring **every** condition satisfied:

```rust
pub fn end_with_pass(&mut self, id: &str, now: Timestamp, pass: Pass) -> Result<Session, Refusal> {
    let _ = pass;
    ...
    let satisfied = session.lock.conditions.clone();
    self.end(id, now, &satisfied)
}
```

So `end_with_pass(id, now, Pass { at: 0 })` released any lock — `DeviceCredential`, `Token`,
`PeerRelease`, `RestartRequired` — with no quota, no cooldown and no bookkeeping. Both in-tree callers
happened to spend properly first (`tick.rs` and the FFI both call `Passes::spend`), so this was not a
live bypass — but the safety property rested entirely on caller discipline, in the one function whose
whole purpose is to be the rationed hatch, and the module's own doc argues the ration *"is the service's
to enforce, not the caller's"*.

**What was changed.** The field is now **private**, with a `pub fn at(&self)` accessor for the
documented "write it to the op-log" contract. That makes a `Pass` a **receipt**: the only way to obtain
one is `Passes::spend`, which is the only thing that checks the quota and the cooldown. The
`let _ = pass;` is no longer "we trust the caller" but "the caller could not have this unless the ration
allowed it", and the method's doc comment now says exactly that.

**Why this shape rather than a runtime check.** The alternatives were to pass `&Passes` in and verify
membership, or to add a `Refusal` variant. The first duplicates accounting `spend` already did; the
second changes a serialized enum Android matches on. Making the constructor private is one line of
intent and moves the invariant into the compiler, which is where `#[must_use]` and the deliberate
absence of `Clone` already put the same kind of guarantee.

**Verification — and this one has real evidence.** I wrote a temporary test that attempted
`Pass { at: 0 }` from outside the module and confirmed the compiler refuses it:

```
error[E0451]: field `at` of struct `Pass` is private
 --> crates\curfew-core\tests\forge_attempt.rs:7:20
```

The probe file was then deleted. The build then confirmed nothing else read the field: the only
external read was one `assert_eq!(spent.at, now)` in `curfew-sync`'s tests, now `spent.at()`.
`cargo test --workspace`: 817 passed.

---

## 7. The documented config path was not the one the service reads

**Findings:** `UX_INTERACTION_REVIEW.md` F-17 (P0).

**What was wrong.** The service reads `%ProgramData%\Curfew\curfew.toml` (`runner.rs:34-37`; the window
reads the same path, `app.rs:39-42`). `README.md` opened its "Using it" walkthrough with four commands
that all take a path — and every one of them said a bare `curfew.toml`:

```
curfew add-profile curfew.toml --id deep-work
curfew block curfew.toml --profile deep-work --site reddit.com
curfew schedules curfew.toml
```

On an installed machine, following the README verbatim creates a config the service never opens, and
`schedules` then fails outright. Nothing reported the discrepancy. The window's **Plan** page made it
worse by rendering an unresolved placeholder — `curfew add-window <config> …` — the one instruction on
that page a user cannot act on, because `<config>` is precisely what they do not know.

**What was changed.**

- `README.md` quotes the path, names `%ProgramData%\Curfew\curfew.toml` explicitly, states why it
  matters — *"a path pointing at a file the service never opens edits a plan that is never enforced,
  silently"* — and points at the window as the reliable place to copy it from.
- `app.rs` returns the resolved path alongside the config in the `Call::Config` reply, on the failure
  path too, so a page can name the file that would not parse.
- `app.html` renders the real command: `curfew add-window "<path>" …`, with a fallback sentence when
  the path is unknown.
- That note also gains the `wrap` class. `.note` had no `pre-wrap`, so the command rendered as one
  run-on line — the third of three separate reasons a user could not act on it.

**Not changed, recorded as a follow-up.** The commands still *require* a path. Defaulting it to the
service's config when omitted is the durable fix — it would let the README say
`curfew add-profile --id deep-work` and remove the class of error entirely — but it changes argument
parsing across `curfew-cli/src/schedule.rs` and is a bigger change than this pass should make
unverified. **Recorded as entry 23.**

**Verification.** `cargo build -p curfew-app` clean; full workspace suite green; the HTML is compiled
into the binary by `include_str!`, so a syntax error would be a compile error.

---

## Still open

Recorded here so the remaining work is a list rather than a memory. Rewritten after every round, and
the entries it named as open in the previous revision — F-16, F-38, the control channel, the
`%ProgramData%` ACL, the wrong-password swallow, the config-reload gap, the single message surface, and
the two read-failure findings — are all fixed above. What follows is what is actually left.

**P0 — none.** All four are fixed: the claimable `Timer`, the untrusted Windows clock, the obeyed
`Stop`, and the Android UI's wall clock, plus the three P0s from the interaction review (F-1, F-16,
F-17).

**P1 — one, and it is not what the review said it was**

- **Entry 28 second half — Windows cannot pair a device.** The "Devices" page is not built because
  there is nothing to build it on: the service runs a sync node and reads `peers.json`, but
  `curfew_sync::pair`'s `offer`/`accept`/`revoke` have **no caller outside `curfew-ffi`**, so no
  Windows surface can create a peer and the page would be empty on every machine. This is a
  key-exchange-and-transport feature rather than a UI fix, and it is the only P1 left. See entry 28
  for the greps.

**P2 — the remaining interaction set**

- **F-22, F-24–F-28 — the rest of the Windows interaction set.** No feedback that a block has started;
  the window asks for the Windows password in its own HTML form where the tray deliberately uses the OS
  credential dialog (`prompt.rs:1-15` argues the case, and the louder surface is the one breaking the
  rule — the two findings point in opposite directions and want one decision, not two patches);
  mouse-only nav; an uncancellable freeze from the window; the tray menu unreachable when the service
  is down; profile **id** shown where the name belongs. Each is small; together they are a session's
  work on `app.html`, `menu.rs` and `shell.rs`.
- **The design-craft set — F-39–F-45, F-47.** `DSheet` is the only surface without the app's glass
  (F-39) while `NowScreen` uses Material `AlertDialog`s for all eight of its dialogs (F-40), so there
  are two visual languages on the screen the user sees most; four duration formats and three clock
  formats (F-41); amber's documented meaning broken in three places (F-42); `Welcome` and `Setup`
  designed and unbuilt (F-44). These are real, and they are craft rather than correctness — which is
  why they come after everything above.
- **The `curfew-cli` path default** (recorded in entry 7): making the config path optional would let
  the README stop quoting it at all. It changes argument parsing across
  `curfew-cli/src/schedule.rs`, so it was left for a pass that can test it properly.

### Two things this pass learned about the repository, worth acting on separately

1. **Robolectric-based unit tests cannot run on Windows ARM64.** Not a code fault — Conscrypt, which
   Robolectric 4.14's native runtime loads, ships no `windows-aarch_64` native build — but it means a
   maintainer on ARM64 Windows has no local test signal for anything that needs an Android runtime,
   which is 121 of the 164 tests and the whole Compose layer. See the note above entry 1.
2. **Nothing pins the JDK locally.** Gradle 8.14.3 rejects Java 25 with a bare `What went wrong:
   25.0.2`. CI pins 21 and never sees it. A `.java-version` or a Gradle toolchain declaration would
   turn that into an instruction.

---

## 6. Windows had no way to start a block

**Findings:** `UX_INTERACTION_REVIEW.md` F-16 (the last P0).

**What was wrong.** `Request::Start` has always existed, and has always been handled and tested. It
had exactly one non-test sender in the entire repository: the command line.

```
$ git grep -rn "Request::Start" -- crates/
crates/curfew-svc/src/main.rs:630   ← the only producer
crates/curfew-win/src/tick.rs:517   ← the handler
```

The Curfew window sent `status`, `end`, `unlock`, `release` and `emergency`, and nothing else. The
tray's `Item` enum had no variant for it. So on Windows every block was schedule-driven or started
from a terminal, and `UX-FLOWS.md` Flow 1 — *"Now opens … one filled button: **Start a block now**"* —
was reachable on the phone and nowhere else. The intended answer, `design/win/Setup.dc.html`, was never
built.

**What was changed.**

- **The window's Now page** gains a filled **Start a block** button. It shows whether or not something
  is running, which is deliberate rather than an oversight: `Request::Start` merges into the running
  session by lattice join, so starting the same profile again is how a block is *extended* and starting
  a different one is how a second profile joins it.
- **The sheet** carries the three decisions the Android Timer screen carries, in the same words: which
  profile, how long, and what it takes to end it. Four durations (25m / 50m / 1h 30m / 3h, default
  1h 30m) and four strengths — *I can stop it* · *Ask me first* · *Your Windows password* · *Until it
  ends* — defaulting to the middle one.
- **It says what it will do** before it does it: `whatItBlocks()` reads the chosen profile's rules and
  writes *"Deep work closes 11 apps and 4 sites while it runs"*, or says plainly that the profile has no
  rules yet and would block nothing.
- **It handles the freeze path.** A profile that takes the whole machine is *announced*, never started
  on the spot (GAPS B4). That arrives as `Response::Announced`, and the sheet says so with the time it
  fires, rather than leaving a countdown the user never saw.
- **The tray gains "Open Curfew…"** (entry 15), because the window is now the only place a block can be
  started by hand and the tray previously had no route to it at all.

**Why a sheet and not a wizard, and why the default is the middle strength.** The Android screen's own
comment is the argument: *"a person about to be distracted has a few seconds of resolve to spend."* A
wizard would eat them. And a form that opens on "no way out" trains people to change it without
reading, which is how a strict lock gets chosen by accident — the failure `LONG_MINUTES` exists to catch
on the phone.

**Verification.** Three new tests, because a UI change with no executable guard is a UI change that gets
deleted:

- `curfew-win` `the_windows_page_can_start_a_block_over_the_wire` — parses the **literal JSON the page
  builds** (one case per strength, including the empty-`locks` case a first-run user sends) into the
  real `Request` enum. The round-trip test beside it proves the enum is symmetric with *itself*, which
  stays true if `seconds` is renamed; this one fails.
- `curfew-app` `the_page_still_offers_to_start_a_block` — the affordance and the call are both still in
  the compiled-in page.
- `curfew-app` `every_strength_the_page_offers_is_a_real_lock` — scrapes the page's `lock: { kind: … }`
  entries and pins them to the set `Lock` actually deserializes, so a fifth strength invented in the
  page cannot become a button that fails with "unknown variant".

`node --check` on the page's extracted script confirms the JavaScript parses — `include_str!` only
checks that the file is UTF-8, so a syntax error would otherwise reach a user as a blank window.

---

## 14. The tray could never end a `Confirm`-locked session

**Found while building entry 6**, not in either review.

**What was wrong.** `menu.rs` sorted lock conditions into "the credential, which this menu can satisfy"
and "everything else, which lives somewhere else":

```rust
let credential = conditions.iter().any(|lock| matches!(lock, Lock::DeviceCredential));
let others = conditions.iter().any(|lock| !matches!(lock, Lock::DeviceCredential));
```

`Lock::Confirm` fell into `others`, so a session locked *"ask me first"* got the sentence **"this
session is locked elsewhere"** and **no action at all** — from the one surface most Windows users ever
open. `Item::End` also sent `satisfied: Default::default()`, so it could not have claimed the
confirmation even if it had been offered.

The window *could* end such a session (its `confirmEnd` sheet), so the two surfaces disagreed about
whether the same lock had an exit. That is worse than either failing alone: it makes the product
contradict itself about the user's own escape hatch. This is the Windows twin of the Android
`Lock.Confirm` bug fixed in entry 8.

**What was changed.**

- `others` excludes `Confirm`, and a confirm-only lock offers `Item::End { confirm: true }` labelled
  *"End X — it asks to be confirmed"*, so the dialog it opens is not a surprise.
- `act()` sends `satisfied: {Confirm}` **only** when `confirm` is true. That claim is the record of a
  dialog the shell showed; everything machine-checkable still comes from the service's own `proven`,
  which is why an empty set remains right for every other lock.
- The shell shows a **Yes/No** before sending it (`Item::End { confirm: true, .. }` has its own arm),
  because sending the claim without asking would turn "ask me first" into "end it without asking" — the
  same defect the window had.

**Verification.** Three menu tests —
`a_confirmation_lock_offers_a_way_to_end_it`, `a_lock_with_nothing_to_confirm_does_not_ask_to_be_confirmed`,
`a_running_timer_is_reported_rather_than_offered` (the negative case, which caught a wrong premise in my
own first draft) — plus `confirming_from_the_tray_claims_the_confirmation_and_nothing_else`, which pins
the claim to exactly one element. `cargo test -p curfew-tray`: 54 passed.

---

## 15. The tray had no route to the window

**Findings:** the UI audit's "the menu has no way to open the window", promoted to a blocker by entry 6.

**What was wrong.** The window was reachable only from the Start menu. The tray — the surface actually
on screen — could end a session, cancel a freeze and explain SmartScreen, but had no item that opened
the window. Once the only way to start a block by hand lives in the window, a tray with no route to it
is a dead end: the icon in front of the user cannot reach the product's central verb.

`design/win/Tray.dc.html` had the same shape, so this was a shipped omission rather than a deviation
from the design — worth noting, because it means the canvas would not have caught it either.

**What was changed.** `Item::OpenWindow`, placed above the two "explain something" items (it is the only
one that *does* something), labelled **"Open Curfew…"**. `open_window()` resolves `curfew-app.exe`
against `current_exe()` rather than `PATH` — the same install that put this icon in the startup folder
put the window beside it, and a `PATH` lookup could find a different build. It spawns without waiting,
so the menu never blocks on the window's lifetime. A missing window is *reported* with the fix
(`curfew install`) rather than being a click that appears to do nothing, and the copy says enforcement
is unaffected either way.

---

## 16 and 17. The window's exit paths were chosen by comparing sentences

**Findings:** `UX_INTERACTION_REVIEW.md` F-20 (silent wrong password) and P2-5 (display strings as
control flow).

**What was wrong — two things in one function.**

```js
if (missing.some((l) => describeLock(l) === "asks before ending")) return confirmEnd(id, missing);
if (missing.some((l) => describeLock(l) === "your Windows password")) return password(id);
```

`describeLock` is a *presentation* function. The window decided which exit to offer by comparing its
output against literal sentences, so a copy edit — or a translated build — would silently turn "End it
early" into a button that can never end anything. The tray routes structurally, on
`Lock::DeviceCredential`, and always did.

Separately, the `unlock` handler checked only `response === "refused"`. A rejected password comes back
as `Response::Error` — `LogonUser` said no, rather than the core declining a policy — so the sheet
closed and **nothing appeared at all**. A user who mistyped their password watched the dialog vanish and
concluded the button was broken. The tray has always handled this case correctly; the window did not.

**What was changed.**

- `lockKind(lock)` is extracted, and every decision uses it. `describeLock` keeps the wording, and its
  fallback now says *"a condition this version of Curfew does not know about"* rather than printing a raw
  discriminant into a sentence, with the raw value going to the console for diagnosis.
- The wrong password is handled as its own case, showing `answer.detail`.
- **An ordering fix that makes multi-condition locks endable at all.** A lock can want a password *and*
  a confirmation. `Unlock` writes the password proof down for a couple of minutes and `End` folds fresh
  proofs back in — so asking for the **password first** leaves the confirmation riding on a proof that is
  still valid, and one more round trip through `end()` finishes it. The old order would have needed
  three, and would fail outright if the user took longer than the proof lives.
- `confirm-end`, and a refused `unlock`, now re-enter `end(id)` rather than declaring the rest
  unreachable — so the next unmet condition is offered instead of a dead end.

**Verification.** `node --check` clean on the extracted script, and `cargo build` of the binary that
`include_str!`s it. **Not covered by an executing test** — these are DOM handlers, and the honest
statement is that the reasoning is checked and the behaviour is not. What *is* pinned is the wire shape
(entry 6's contract test) and the strength vocabulary.

---

## 26. An edit to the config never reached the service that enforces it

**Findings:** `UX_INTERACTION_REVIEW.md` F-23 (P1).

**What was wrong.** The service reads its config at startup, or on `Request::Reload`. Nothing sent
`Reload` except a user typing the verb by hand, and no edit command did:

```
$ git grep -n "Request::Reload" -- crates/
crates/curfew-svc/src/main.rs:102   ← the verb, dispatched from argv
crates/curfew-win/src/tick.rs:...   ← the handler
```

So `curfew add-window …` printed **"Added"**, the window read the same file and showed the new window,
and the running service kept enforcing the old plan — while the Plan page said *"This is what the
service is enforcing"*. That is the worst shape this failure can take: every surface the user can see
agrees the change landed, and the one that blocks never heard about it. `add-source` even printed
*"The service picks it up on its next reload"*, telling the user to do something they could not
usefully do.

**What was changed.**

- `curfew_cli::writes_config(command)` separates the two reading verbs (`schedules`, `blocks`) from the
  seven that write. `CONFIG_ARG` names where every writing verb takes its path.
- The **dispatcher** in `curfew-svc/src/main.rs` follows any successful write with a best-effort
  reload. It lives there rather than in each verb because every config verb goes through one place, so
  a verb added later cannot forget — the same reasoning as `begin(id)` in entry 5.
- Deliberately quiet. A machine with no service is not an error case: someone preparing a config to
  copy elsewhere, or running `curfew check` on Linux, should not be told off. The one case worth a
  sentence is a write to the file the service reads when nothing answered, because *there* the user is
  entitled to think it is already live.
- `add-source`'s line is corrected. It is picked up now, and telling someone to do something that has
  already happened is how they learn to distrust the output.

**Why `same_file` canonicalizes.** Deciding "is this the config the service reads" by comparing strings
is wrong in the case that actually happens: `curfew add-window curfew.toml …` run from
`%ProgramData%\Curfew`, or a path containing `..`, both name the service's own config. So canonicalize
first, and fall back to a literal comparison because the file may not exist yet — and where neither
resolves, answer "not the same", because a missing sentence is a smaller error than telling someone
their edits are live when they are not.

**Verification.** 5 new tests: the read/write split in both directions (a reading verb that owed a
reload would restart enforcement's view for no reason; a writing verb missing from the list *is* the
bug), that every writing verb still takes its config at index 1, and `same_file` across the same path,
a different path, and the same file reached by two spellings plus a path that does not exist.
`cargo test --workspace`: 829 passed.

---

## 24. The control channel read without a limit and served one client at a time

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P1-1. **Fixed in part** — the part that can be fixed is
fixed, and the part that cannot is named below rather than left as a surprise.

**What was wrong.** `serve` was one loop over `listener.incoming()` doing three things in sequence:

```rust
let mut line = String::new();
if reader.read_line(&mut line).is_err() { continue; }
```

1. **`read_line` into an unbounded `String`.** The service runs as SYSTEM and the client does not have
   to be privileged — `PIPE_SDDL` deliberately admits `BUILTIN\Interactive Users`. A client sending
   megabytes with no newline had the service allocate all of it.
2. **A single connection slot for the whole machine.** One connection at a time meant a client that
   connected and never sent a newline held the channel for ever. The tray, the window, the command line
   and every browser host went unanswered, and nothing made the bad connection go away.

The second is the serious one, and the review is right about what it costs: **`curfew release` is the
24-hour last-resort exit that §11 of the architecture calls the thing separating a commitment device
from a trap, and it is issued over this same channel.** Wedging the pipe did not merely blind the
window — it removed the user's documented way out.

**What was changed.**

- `read_request` is now a `fill_buf`/`consume` loop capped at `MAX_REQUEST` (64 KiB). Nothing allocates
  more than the cap in total, and a line that reaches the cap comes back **truncated**, which fails to
  parse, which is answered with an error — so the cap refuses rather than merely shrinking the attack,
  and no separate length check is needed.
- **One thread per connection**, with `MAX_CONNECTIONS = 32` bounding how many the service will hold.
  The accept loop is single-threaded, so the load-then-add count is exact rather than racy.
- The per-connection work moved into `answer_one`, and the slot is released whether or not it returns,
  so a panic in a handler cannot leak a slot for ever.

**The residual, stated plainly.** A wedged client still occupies *its own* thread, because a read
deadline cannot be set on these streams: `interprocess`'s Windows named-pipe stream returns
`Unsupported` for `set_read_timeout`. Bounding the count is what removes the total-wedge property — the
accept loop and every other slot keep working, so the escape hatch stays reachable — but a client that
opens 32 connections and sits on them can still exhaust the cap. A supervisor that closed the stream
from another thread would need `CancelIoEx` on a handle this crate does not expose. This is recorded in
the function's own doc comment as well, so the next person meets it before writing the fix that does
not compile.

**Why the read cap rather than a timeout.** The obvious fix — `set_read_timeout` — is the one that does
not work here, and the review warned about exactly that. The cap is provable on a byte slice, needs no
platform support, and closes the unbounded-allocation half outright.

**Verification.** 4 tests, all on `read_request` as a pure function over `BufRead` so no socket is
needed: a whole line; a line truncated by EOF; **an oversized request bounded at exactly the cap and
then failing to parse** (the assertion cannot hold under an unbounded read, so it is not vacuous); and
a line split one byte at a time across buffers, with a *second* request read afterwards to prove the
bounded read does not eat the rest of the stream. `cargo test -p curfew-svc`: 26 passed.

---

## 25. `%ProgramData%\Curfew` was not administrator-owned, and everything said it was

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P1-0, **second half** (the first half — the spawn path —
was entry 18).

**What was wrong.** Two places asserted the directory is administrator-owned:

- `watchdog.rs`: *"`%ProgramData%\Curfew` is administrator-owned, the same as the config and the state
  file beside it, so running from here is no easier to tamper with than running from Program Files."*
- `runner.rs:191-194` repeated the claim for the sync directory.

**Nothing in the repository set that ACL.** `C:\ProgramData` grants `BUILTIN\Users` container-inherited
`Write`, which is `FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY`. So an unprivileged user could create files in
the directory holding the config, the state file, the cached calendars, the DNS record — and, before
entry 18, the watchdog image the service executes as SYSTEM.

Entry 18 closed the worst consequence by verifying the *image* before running it. This closes the rest:
the directory now actually is what the comments claimed, so nothing can be planted in the first place.
`dns-before.json` is the one worth naming — `give_back_remembered` restores the machine's DNS settings
from it, so a planted copy is a way to point someone's resolver somewhere else.

**What was changed.** A new `curfew_win::acl` module. `hardening_args` builds the `icacls` invocation
and `harden` runs it; the service calls it on the way up, and `install` calls it after starting the
service, so a machine whose service fails to start is still hardened.

```
icacls <dir> /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F *S-1-5-32-545:(OI)(CI)RX /Q /C
```

**Three deliberate choices.**

1. **SIDs, not names.** `icacls SYSTEM:(OI)(CI)F` works only on an English Windows. These are well-known
   and language-independent — the same class of mistake as the localized `netsh` parsing this project
   has already been bitten by.
2. **`icacls` rather than `SetNamedSecurityInfoW`.** A trade: one more process at install and start,
   against a hand-built `EXPLICIT_ACCESS_W` array and a second place to get pointer lifetimes wrong in a
   SYSTEM process. Only the *exit status* is read, never the output, which is localized.
3. **`/inheritance:r` before `/grant:r`.** That pair is what makes the result exactly three grants
   rather than three plus whatever was inherited — which is the entire bug.

**Failure is reported, not fatal.** The service still enforces; refusing to start over a permission
change would be a worse trade than running with the old ACL, and an install that aborted here would
leave the user with nothing.

**Verification — and this one was demonstrated, not just argued.**

- 3 pure tests: the three grants and `/inheritance:r` are present, no non-administrator trustee is
  granted write, `Everyone` is never named, and a missing directory is reported rather than silently
  treated as done.
- 1 live test: `harden` is actually run against a scratch directory, and `icacls` is asserted to accept
  the arguments. That is the failure which would otherwise be found by a user whose install silently
  hardened nothing.
- **The resulting ACL was checked by hand**, because reading it back inside a test means parsing
  localized `icacls` output. Before: the directory inherited two `Modify, Synchronize` grants and a
  `FullControl` grant. After, exactly:

  ```
  NT AUTHORITY\SYSTEM       FullControl     (not inherited)
  BUILTIN\Administrators    FullControl     (not inherited)
  BUILTIN\Users             ReadAndExecute  (not inherited)
  ```

`cargo test --workspace`: 837 passed.

---

## 19. The app had eight ways to say something and two places to show it

**Findings:** `UX_INTERACTION_REVIEW.md` F-29 (P1). This is the one that also closes several smaller
findings by construction.

**What was wrong.** `UiState.message` is written from **45 call sites** in `CurfewViewModel` — a failed
app toggle, an import that would not parse, a device that could not be revoked, a profile that could not
be deleted, a schedule that could not be saved, a CSV that could not be written — and it was rendered by
**two**: `NowScreen` and `ProfileEditScreen`, both as an `AlertDialog`.

So the app's only feedback channel for failure belonged to whichever screen happened to be composed.
Concretely: a user flips a switch on the app picker, the write fails, and **nothing happens on screen**.
The refusal had been recorded in state the app picker does not read, and would surface as a modal
minutes later if and when the user returned to Now — worse than silence, because by then the sentence
has lost its context.

**What was changed.**

- `MessageBanner` in `CurfewApp`, above the nav bar, rendered once for the whole app. It is now
  structurally impossible to raise a message and not show it: the surface does not belong to a screen
  any more.
- The two per-screen `AlertDialog`s are removed. A banner rather than a dialog because `UX-FLOWS.md`
  principle 4 is that the receipt is the changed thing and dialogs are for decisions; an
  acknowledgement is not a decision. The modal was also actively harmful, interrupting whatever the
  user did next.
- **Severity, added because the first version of this fix was wrong.** `message` was one
  undifferentiated string, and the banner's first draft painted all of it red. But 14 of those 45 sites
  are *successes* — "Paired.", "Up to date.", "Removed. A session it already started keeps running."
  Showing those as errors would have traded one bug for a louder one. So `Notice(text, bad)` was
  introduced, with `say()` defaulting to bad and a new `note()` for confirmations.

**Why the flag defaults to "bad".** Most of these sentences are failures, and a new error path that
forgets to classify itself gets shown as a problem — where the opposite default would hide a real
failure behind a reassuring tick. Failing loudly is the better direction for this default to be wrong in.

**Verification.** `:app:compileDebugKotlin` clean, with no warnings in the touched files. The 14 success
sites were converted by a scripted replace and then **re-listed to confirm every one landed**: `note(`
now appears at lines 316–652 and no success string remains on `say(`. One scripting mistake on the way —
my first pass piped file *contents* to `Select-String -Path` and reported zero matches for all ten
patterns while the replace itself had worked, so the guard was the re-listing rather than the count.
**Not covered by an executing test**: the Conscrypt limitation in entry 4 still applies to this host,
and this is composition-level behaviour in any case.

---

## 20 and 21. A read that failed was rendered as an empty result

**Findings:** `UX_INTERACTION_REVIEW.md` F-30 (the config) and F-31 (the calendar). One defect in two
places, so one commit and one entry.

**What was wrong.** Both were `runCatching { … }.getOrDefault(emptyList())` / `.getOrDefault("")`:

```kotlin
val events = runCatching { runtime.calendarEvents(now, …) }.getOrDefault(emptyList())
val configToml = runCatching { runtime.policy.configToml() }.getOrDefault("")
```

An empty list and an empty string are both **valid** values to every reader, so a failure was
indistinguishable from an honest nothing. Three consequences, and the third is data loss:

1. **The Events screen** drew its ordinary *"Nothing in the next day and a half"* empty state over a
   calendar it had failed to read — the app asserting something it had no way to know. §10 of the
   architecture is explicit that downtime is admitted rather than papered over.
2. **The config editor** on the Schedule screen opened on a blank page. Nothing looked wrong: an empty
   config is a legal config.
3. **`saveConfig` validates what it is given, not that it replaced what was there.** So saving that
   blank page would have replaced the user's entire config — profiles, windows, rules, blocked apps —
   with whatever they had just typed. And **Export** was worse: it writes `state.configToml` to a file,
   so exporting after a failed read saved an empty backup **over a good one**, from a button whose whole
   purpose is to keep a copy.

**What was changed.**

- `UiState.configError` and `UiState.calendarError`, both `String?`, set from `exceptionOrNull()`
  rather than discarded. `null` means "read fine, and here is what is there"; non-null means "this is
  empty because we could not look".
- **The config editor is not offered at all** while `configError` is set. Not disabled-but-visible:
  there is nothing to edit *from*, so the screen says so, names the reason, and states that nothing has
  been changed. Reopening retries.
- **Export refuses**, with a sentence, and writes nothing.
- **The Events screen** says *"Your calendar could not be read just now, so this is not a complete
  list"* in the warning colour, rather than the free-afternoon sentence.
- `calendarError` is deliberately separate from `calendarGranted`: permission can be granted and the
  provider still fail, and the two need different sentences and different remedies.

**One sub-item left, recorded rather than quietly dropped.** `AppPickerScreen` still derives its tick
state from `state.configToml`, so with a failed read it shows everything unticked. **This is a display
fault and not a destructive one** — I checked the write path: `Policy.setBlockedApps(runtime.policy.
configToml(), …)` re-reads the config from the runtime rather than using the possibly-empty state, so a
toggle after a failed read either works on the real config or throws into the banner. Guarding the
picker's display is in the "Still open" list at the end of this document, as **entry 29** — it is the
one sub-item of this fix that was not done, and it is listed there rather than left to look complete.

**Verification.** `:app:compileDebugKotlin` clean, no warnings in the touched files. **Not covered by an
executing test** — the Conscrypt limitation in entry 4. The write-path claim above was verified by
reading the call sites, and that is stated as reading rather than as a test.

---

## 22. Deleting a profile asked nothing, and left before hearing the answer

**Findings:** `UX_INTERACTION_REVIEW.md` F-32 (P1).

**What was wrong.** Two things, one line apart:

```kotlin
) {
    model.deleteProfile(existing.id)
    onDone()                       // ← navigates back immediately
}
```

1. **No confirmation.** A single tap on "Delete" destroyed a profile and its apps, sites and windows.
   *Removing a window* — trivially recreated — has asked for confirmation all along, so the app was
   more careful about the cheap thing than the expensive one.
2. **`onDone()` ran before the core had answered.** `deleteProfile` launches a coroutine, so the screen
   popped while the write was still in flight. There is a *routine* refusal here — the core names the
   schedules still pointing at the profile, and names them precisely so the user can fix them — and it
   arrived on a screen that no longer existed. The user was told about a problem, on a page where the
   problem was not.

**What was changed.**

- A confirmation that says what is actually lost and how it interacts with a running session: *"A
  session it has already started keeps running until its own lock lets it go."* It also points at the
  reversible alternative — turning the profile off from the schedule list — because the destructive
  option should not be the only one a hurried user can find.
- `deleteProfile(id, onDone)` now runs `onDone` **only on success**. On failure the user stays exactly
  where the problem is, with the banner (entry 19) explaining it. `onDone` defaults to `{}`, so the
  other caller in `ScheduleScreen`, which does not navigate, is unchanged.

**Verification.** `:app:compileDebugKotlin` clean, no warnings. **Not covered by an executing test** for
the usual host reason; the callback-ordering change is a control-flow change in a coroutine, which is
the part a test *would* have caught on a working host, and I am not claiming otherwise.

---

## 23. The nav and the toggle: one claim wrong, one claim right, one thing nobody mentioned

**Findings:** `UX_INTERACTION_REVIEW.md` F-38 (P1). **Partly fixed, and the headline is refuted** — the
first time in this pass that a P1's main claim did not survive checking, so the working is set out in
full.

**The claim.** *"F-38 (P1). The primary navigation has the smallest touch targets in the app."* The
review measures a tab at **"roughly 38dp tall"** from `padding(horizontal = 10.dp, vertical = 4.dp)`
around a 30dp icon plus a 10sp label.

**Checked against the source, and the figure is a miscount.** The tab's clickable area is the whole
`Column`, and the `Column` contains the 30dp icon Box **and** a 3dp gap **and** the label:

```
30 (icon box)  +  3 (Arrangement.spacedBy)  +  ~13 (10sp label)  =  ~46dp of content
     + 4dp vertical padding, twice                              =  ~54dp clickable height
width:  30 (icon box)  +  10dp horizontal padding, twice         =  ~50dp clickable width
```

The review stopped at `30 + 4 + 4 = 38` — the icon and the padding, without the label or the gap. Both
axes are therefore **over** Material's 48dp, and the nav is one of the *larger* targets in the app rather
than the smallest. This is the seventh finding refuted during this pass, recorded here rather than in
§9 of the interaction review because the fix went in anyway, for the two reasons below.

**What was genuinely wrong, and is now fixed.**

1. **The nav had no press feedback at all.** `indication = null` in `GlassTab`, and a repo-wide grep
   confirms it was **the only** such suppression in the app — `Switch`, `GhostButton` and every row body
   show a ripple. It was there to stop Material's sliding pill, which is not what a ripple is: the reason
   was sound and the remedy too broad. A tap that registered and a tap that missed felt identical, on the
   control every user touches every session.
2. **The toggle was 26dp tall.** `Switch` drew a 44×26dp pill and put the clickable on that same box, so
   the control deciding whether an app is blocked was less than half the height of the 52dp primary
   button it usually sits opposite — and a miss on a switch is silent.
3. **And the part that was fine was fine by accident.** At 10sp the nav label clears 48dp; a shorter
   label, a tighter `Arrangement`, or a smaller type token would have taken it under quietly, and nothing
   would have said so. Same failure mode as the original `Lock::Confirm` bug: a property nobody wrote
   down.

**What was changed.** `Dsn.MinTouch = 48.dp` — the app's own standard written down, since its buttons are
already 52 and 46dp — plus `Modifier.minimumInteractiveComponentSize()` on both. That modifier is
Material's guarantee that the *touch* target is at least 48dp while the *visual* size stays whatever was
drawn, and the distinction is the whole point: a 44×26dp pill and a 36dp day circle are the right look
and the wrong hit area, so the fix is separating the two properties, not enlarging the drawing. The nav
keeps its ripple, and the two now-unused imports (`MutableInteractionSource`, `remember`) were removed
rather than left dead.

**Deliberately not changed, with the reason.** The 36dp day circles on the profile editor
(`ProfileEditScreen.kt:483`). WCAG 2.2 SC 2.5.8 asks for 24×24 CSS px and adds a *spacing* exception for
undersized targets — seven circles at 36dp with 6dp between them pass it comfortably, because a 24dp
circle centred on each does not intersect its neighbours. Taking them to 48dp needs
`7 × 48 + 6 × 6 = 372dp` of width, more than a 360dp phone has inside a card, so the honest options were
"redesign the row" or "leave it" — and leaving it is correct, because they already meet the standard that
applies. That is a judgement about the exception, written down so the next reader need not re-derive it.

**Verification.** `:app:compileDebugKotlin` clean, no warnings in the touched files. **Not covered by an
executing test** — and this is the finding where that costs most, because the defect class here is exactly
"the layout looks right and measures wrong", which is what a measurement test is for. See the Android
verification note above entry 1: `createComposeRule()` under Robolectric was tried for precisely this and
hit the Conscrypt wall, so the recipe is recorded there for a host that can run it. The dimension claims
above come from reading the modifier chain and the literal `dp` values, and they are offered as reading,
not as measurement.

---

## 27. A blocked site: the fix the review implies is already refused, and the real gap is smaller

**Findings:** `UX_INTERACTION_REVIEW.md` F-21 (P1). **Fixed as far as the design allows** — and this is
the second finding this round whose premise needed correcting, so both halves are set out.

**The claim.** *"A blocked website with no extension shows the browser's own error page … The user gets
`ERR_CONNECTION_REFUSED`: no Curfew surface, no reason, no exit — from the user's point of view the
internet broke."* The implied remedy is a block page.

**The design already considered a block page and refused it, in the code, with reasons.** Two separate
comments, and both are sound:

```rust
// hosts.rs — the address blocked names are pointed at
// `0.0.0.0` rather than `127.0.0.1`: a local web server is common on a developer's machine, and
// pointing a blocked site at it serves that server's pages instead of failing, which is confusing
// at best and a data leak at worst.

// dns.rs — build the refusal for a query
// `0.0.0.0` rather than NXDOMAIN, and rather than a page of our own: NXDOMAIN makes some clients
// retry against a hard-coded resolver, and serving a real page from a blocker means holding a
// certificate for someone else's domain, which is a thing Curfew is never going to do.
```

So there is no version of "show a Curfew page for reddit.com" that this program can implement: over
HTTPS it requires a certificate for somebody else's domain, and over plain HTTP it requires binding a
local port that may already be a developer's server. The review's own comparison — *"the opposite of
Curfew's own excellent Android block screen"* — is the tell: on Android the app **is** the thing drawing
the screen, and on Windows the browser is, and Curfew has no seam to draw in.

**What was actually wrong, and is now fixed.** The refusal is right; **leaving the user to guess was
not.** The cost of the `0.0.0.0` decision is that a person sees *"This site can't be reached"* and has no
way to learn Curfew caused it — and that is the largest population of users, since a domain rule needs no
extension. Curfew cannot show a page, but it *can* name the symptom, and neither surface that mentions
blocked domains was doing so:

- **The tray's "What is blocked" list** printed the domains and stopped. It now adds, when there is at
  least one: *"A blocked site shows your browser's own 'can't be reached' page. Curfew refuses the name
  rather than serving a page, because it will not hold a certificate for somebody else's domain. If a
  site fails that way while a session is running, that is Curfew and not your connection."* That is the
  whole of the honest fix: it names the symptom, says why Curfew cannot do better, and supplies the one
  fact the user lacks — that this is the product working.
- **The window's "Is it working" page** said *"12 sites in the hosts file — written by the service,
  checked every tick"*, which describes the mechanism rather than the experience. It now says what the
  user will see, and pluralises correctly at one site. With nothing blocked it keeps the old wording,
  because then the symptom means something else — a dead connection or a broken hosts file — and blaming
  Curfew for it would be a lie.

**Verification.** Two new tests on `details()`, a pure function over `Status` and therefore the one part
of this that *is* executable on this host: `the_details_say_what_a_blocked_site_looks_like` (the symptom
is named, and the sentence says this is Curfew and not a connection fault) and
`an_idle_tray_does_not_explain_a_page_it_did_not_block` (the negative case — nothing blocked means the
symptom is not Curfew's, so it is not mentioned). `node --check` clean on the window's extracted script.
`cargo test --workspace`: **839 passed**; clippy and fmt clean.

**What is still missing, and cannot be fixed here.** A user who never opens the tray or the window still
sees only the browser's error page. Closing that properly needs one of the two things Curfew has ruled
out, so it is recorded as a genuine limitation rather than left to look like an oversight.

---

## 28. The window's nav was four pages where the design has six

**Findings:** `UX_INTERACTION_REVIEW.md` F-19 (P1). **Half fixed** — one of the two pages is built, and
the other turned out not to be a missing page at all. That distinction is the substance of this entry.

**The claim.** *"Every `design/win/*.dc.html` carrying a nav renders six: Now · Plan · Apps & sites ·
**Where time went** · **Devices** · Is it working. The build has four. Both exist on Android. On
Windows the only route to the user's own usage data is `curfew stats` in a terminal."* All of that is
correct, and it is the one finding this round that needed no correction.

### "Where time went" — built

The data was already there and had no way to reach it. `Enforcer::history` has held thirty days of
`SessionRecord`s all along — *"kept for thirty days so `curfew stats` can say what the fortnight looked
like"* — and `curfew_core::stats::summarize` is a pure function that turns them into a fortnight of
days, streaks and totals. On Windows the only caller was the command line, so a user who never opened a
terminal could not see what their own blocking had added up to.

- **`Request::Stats { days }`** and **`Response::Stats(Box<Stats>)`** on the control channel. The
  service answers rather than the window reading the state file for itself, even though the file is
  readable: the service already holds the history in memory, it is the only writer, and a second reader
  of a file being rewritten under it is how two views of one fortnight start to disagree. Same reasoning
  as everything else on that channel. Boxed for the same reason `Status` is.
- **`days` has a serde default of 14**, so the window can omit it — and a test pins that omitting it
  gives the same fortnight as asking for it, because the chart's labels and its totals have to agree.
- **The page** is a bar per day with the blocked time printed above each, plus totals and the streaks.
  Two details are deliberate: **a day with any blocking at all gets a visible bar** (a minimum height,
  not a proportional hairline), because a chart that rounds a real day down to nothing tells the user
  they did nothing, which is the one thing this page must not get wrong; and **an empty day is a flat
  stub in the line colour rather than a gap**, so a fortnight with a gap in it reads as a fortnight
  rather than a broken chart.
- The `total` in the subtitle and the bars are derived from the same array, so they cannot disagree.

**A note on the escape hatch this needed.** The note at the foot of the page names `curfew stats`, and it
was first written with backticks inside a JavaScript template literal — which does not parse.
`cargo build` **succeeded anyway**, because `include_str!` checks only that the file is valid UTF-8; the
only thing that caught it was `node --check` on the extracted script. Worth recording, because it is the
same gap this document has already flagged twice: the window's HTML has no compile-time check beyond
"it is text".

### "Devices" — not a missing page, and this is the more useful finding

I went looking for the peer state to build the page on, and it is not there. The Windows service **does**
run a sync node: `start_sync()` opens `%ProgramData%\Curfew\sync`, reads `peers.json`, and starts a
listener — but only when there is already at least one peer, and there is **no way to create one**.

```
$ git grep -rn "add_peer\|pair::\|Peers::" -- crates/curfew-svc/src crates/curfew-win/src \
    crates/curfew-cli/src crates/curfew-tray/src crates/curfew-app/src
(no matches)
```

`curfew_sync::pair` exposes `offer`, `accept`, `revoke`, `active`, `all` — and every caller of it is in
`curfew-ffi`, Android's binding. So on Windows a `peers.json` can only arrive by being placed there, and
a Devices page would be **permanently empty with no way to fill it**. The review's framing — "the nav is
6 pages in the design and 4 in the build" — treats the two missing pages as the same kind of omission.
They are not: one was a page over data that existed, and the other is a UI over a feature that was never
built on this platform.

**So it is not built, and it is recorded as a feature rather than a fix.** Building the page alone would
produce the most misleading surface in the app: a "Devices" tab that is empty on every machine, with
nothing to explain why. The honest options are to build Windows pairing (a key-exchange and transport
feature, not a P1 UI fix) or to leave the nav at five and say why. This entry takes the second, and the
item is in "Still open" below.

**Verification.** Three new tests in `crates/curfew-win/tests/tick.rs`, all executable on this host
because `Enforcer::handle` and the IPC enums are ordinary Rust:

- `the_time_page_gets_its_figures_over_the_wire` — sends the **literal JSON the page builds**
  (`{"request":"stats","days":14}`), after running a real session, and asserts a fortnight of days and a
  non-zero total. The same contract-test shape the Start button has, so a rename on either side is a
  failing test rather than an empty page.
- `a_machine_with_no_history_still_answers` — a fresh install gets an empty fortnight, not an error.
- `omitting_the_window_gives_the_same_fortnight_as_asking_for_it` — the serde default is pinned.

`node --check` clean on the window's script. `cargo test --workspace`: **842 passed**; clippy and fmt
clean. **The page's own rendering is not covered by an executing test**: it is DOM building rather than
Compose, but it needs a browser, and see the Android verification note above entry 1 for why nothing in
this project runs a UI on this host.

---

## 29. The app picker said "you block nothing" when it could not see

**Findings:** recorded as the one sub-item left by entries 20/21, not a separate finding in either
review.

**What was wrong.** `AppPickerScreen` derives both of its panes from the config:

```kotlin
val blocked = remember(current, state.configToml) { current?.let { model.blockedApps(it) }.orEmpty() }
val sites   = remember(current, state.configToml) { current?.let { model.rulesBeyondApps(it) }.orEmpty() }
```

and `ViewModel.blockedApps` ends in the same swallow as entry 20:

```kotlin
Policy.blockedApps(runCatching { runtime.policy.configToml() }.getOrDefault(""), profile)
```

A failed read therefore produced an **empty set**, which this screen drew as every app *allowed* and
every site *unblocked*. That is the app asserting something false about the user's own plan, on the one
screen whose entire job is to answer *"what does this profile block?"* — the same defect as entries 20
and 21, in the third place it appears.

**Why it was listed separately rather than folded into entry 20.** Because the *consequence* is
different, and saying so was the point. This one is not destructive: the write path re-reads the config
from the runtime rather than using the possibly-empty state, so flipping a switch after a failed read
either works on the real config or throws into the banner (entry 19). I checked that by reading the call
sites, and it is why this is P2 where F-30 was P1 — but a screen that says "you block nothing" when it
cannot see is still worse than one that admits it cannot see.

**What was changed.** A guard above the pane split — not inside each half, because both panes read the
same file and the honest answer is the same for both. When `state.configError` is set the screen says so,
names the reason, states that nothing has been changed, and returns before drawing any rows.

**Refusing to draw the rows rather than drawing them disabled**, which is deliberate: there is no tick
state to show, and a column of switches that cannot be trusted is not a thing to put a finger near. A
disabled list would still have answered the screen's central question wrongly.

**Verification.** `:app:compileDebugKotlin` clean, no warnings in the touched file. **Not covered by an
executing test** — the Conscrypt limitation in the note above entry 1, and this is composition-level in
any case. That this log now has a P2 entry whose only evidence is "it compiles" is itself worth stating:
it is the honest description of what can be checked on this host for Compose code, and the reason the
Android verification note sits at the front of this document rather than in an appendix.
