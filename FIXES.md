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
| 4 | Android UI reconciled with the raw wall clock | **P0** | **Fixed** |
| 5 | First block lost after the accessibility detour (F-1) | **P0** | **Fixed** |
| 6 | Windows had no way to start a block (F-16) | **P0** | **Fixed** |
| 7 | README documented a config the service never reads (F-17) | **P0** | **Fixed** |
| 8 | `Lock.Confirm` could never be satisfied (F-4) | **P1** | **Fixed** |
| 9 | Emergency pass unreachable; block screen claimed it was spent (F-5) | **P1** | **Fixed** |
| 10 | `[emergency]` never validated | **P1** | **Fixed** |
| 11 | `end_with_pass` took the caller's word | **P1** | **Fixed** |
| 12 | `UiState.message` set from 8 places, rendered on 2 (F-29) | **P1** | Pending |
| 13 | Unparseable config looked empty; Save destroyed it (F-30) | **P1** | Pending |
| 14 | Failed calendar read looked like an empty diary (F-31) | **P1** | Pending |
| 15 | Delete-profile: no confirm, refusal never read (F-32) | **P1** | Pending |
| 16 | Three false product claims, incl. "no internet permission" (F-46) | **P1** | Pending |
| 17 | Nav/Switch touch targets under 48dp (F-38) | **P1** | Pending |
| 18 | Control channel unbounded read / no timeout / serial accept (P1-1) | **P1** | Pending |
| 19 | `%ProgramData%\Curfew` ACL + unverified watchdog image (P1-0) | **P1** | Pending |
| 20 | Windows: no feedback, silent wrong password, config edits inert | **P1** | Pending |

*(The table is updated as work lands. A "Pending" row means it is on the list, not that it was
forgotten.)*

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

*(Entry written with the change; see the commit for the diff.)*

---

## 3. Service `Stop` was obeyed rather than refused; uninstall failed open

**Findings:** `DESIGN_AND_CODE_REVIEW_FULL.md` P0-3.

*(Entry written with the change; see the commit for the diff.)*
