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
| 4 | Android UI reconciled with the raw wall clock (A-1) | **P0** | Pending |
| 5 | First block lost after the accessibility detour (F-1) | **P0** | Pending |
| 6 | Windows had no way to start a block (F-16) | **P0** | Pending |
| 7 | README documented a config the service never reads (F-17) | **P0** | Pending |
| 8 | `Lock.Confirm` could never be satisfied (F-4) | **P1** | Pending |
| 9 | Emergency pass unreachable; block screen claimed it was spent (F-5) | **P1** | Pending |
| 10 | `[emergency]` never validated | **P1** | Pending |
| 11 | `end_with_pass` took the caller's word | **P1** | Pending |
| 12 | `UiState.message` set from 8 places, rendered on 2 (F-29) | **P1** | Pending |
| 13 | Unparseable config looked empty; Save destroyed it (F-30) | **P1** | Pending |
| 14 | Failed calendar read looked like an empty diary (F-31) | **P1** | Pending |
| 15 | Delete-profile: no confirm, refusal never read (F-32) | **P1** | Pending |
| 16 | Three false product claims, incl. "no internet permission" (F-46) | **P1** | Pending |
| 17 | Nav/Switch touch targets under 48dp (F-38) | **P1** | Pending |
| 18 | Control channel unbounded read / no timeout / serial accept (P1-1) | **P1** | Pending |
| 19 | Unverified watchdog image executed as SYSTEM (P1-0, first half) | **P1** | **Fixed** |
| 20 | `%ProgramData%\Curfew` has no explicit ACL (P1-0, second half) | **P1** | Pending |
| 21 | Windows: no feedback, silent wrong password, config edits inert | **P1** | Pending |

*(The table is updated as work lands. **"Pending" means exactly that** — the row is a plan, not a
claim. This table is the one place in the document where it would be easy to overstate progress, so
it is corrected against `git log` whenever an entry is added.)*

### Fixed so far, by commit

| Commit | What |
| :--- | :--- |
| `763ab9e` | A timer lock is not something a caller may claim (entry 1) |
| `843bc52` | The Windows service judges locks against a trusted clock (entry 2) |
| `e095b4f` | A stop is refused while a lock is held, and uninstall fails shut (entry 3) |
| *(next)* | A watchdog image is verified by content, not by size and timestamp (entry 19) |

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
