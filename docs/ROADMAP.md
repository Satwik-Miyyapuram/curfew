# Roadmap

Each phase ends with something usable, and with **exit criteria** that must actually pass. "Done"
means the criteria hold, not that the code exists. Open problems per phase live in
[GAPS.md](GAPS.md).

## Phase 0 — Foundations
- [x] Git repo, AGPL-3.0, docs (research, architecture, roadmap, decisions, gaps)
- [x] Cargo workspace: `curfew-core` (lib) + `curfew-cli` (bin)
- [x] `curfew.toml` schema v0 with `schema_version`, serde model, golden-file tests
- [x] Core rule engine `decide()`: block / allow-only / budget / delay. Pure, no I/O
- [x] Property tests: a lock can never be shortened by any sequence of operations
- [x] CI: fmt + clippy + test on push (Linux + Windows runners)
- [x] Repo hygiene: CONTRIBUTING, SECURITY.md, CoC, issue/PR templates, versioning policy (E4)

**Exit criteria:** `cargo test` green on both CI runners; the engine decides a worked example
identically from a config file on Linux and Windows; property suite proves lock-monotonicity.

## Phase 1 — Android MVP (usable alone)
- [x] Foreground-app detection: AccessibilityService, with UsageStats poller as fallback
- [x] Block overlay screen (reason, time remaining, allowed exits)
- [x] Profiles + app picker, manual sessions, timer sessions, recurring schedules
- [x] Local encrypted SQLite store (D4 of GAPS), config import/export
- [x] Strictness: none / confirm / device-credential / timer-lock, plus the 24h delayed release
      (D7, GAPS D1). Credential checks go through `BiometricPrompt` restricted to
      `DEVICE_CREDENTIAL`; Curfew stores no password
- [x] Usage stats screen
- [x] Staged permission wizard + protection-health screen (GAPS A6)
- [x] Boot/force-stop/OEM-killer resilience + downtime banner (GAPS A1)
- [x] Own-UI accessibility pass (GAPS E5)
- [x] Trusted clock: a wall clock that outruns monotonic uptime is refused, so winding the clock
      forward cannot shorten a timer lock (GAPS C6)
- [x] Optional Device Admin for uninstall protection, last in the wizard, released with the last
      lock (GAPS G2)
- [x] Restricted Settings walkthrough for sideloaded installs (GAPS G4)

**Exit criteria:** blocks survive reboot and force-stop; a locked session cannot be ended early by
any in-app path, by clearing data, or by changing the system clock; added battery drain <3%/day
measured over 24h; instrumented tests green on an emulator in CI.

## Phase 2 — Windows MVP (usable alone)
- [x] Service + watchdog, tray UI, installer requiring admin
- [x] Process + window-title blocking, block overlay
- [x] Website blocking v1: hosts file + local DNS proxy
- [x] Same profiles/sessions/schedules/strictness as Android, same `curfew.toml`
- [x] Frozen mode with a cancellable countdown (GAPS B4)
- [x] x64 + ARM64 builds; winget/scoop manifests; checksums; AV false-positive notes (GAPS B1, B5)
- [x] Browser extension as a granularity layer only — URL-path rules a service cannot see, joined to
      the service by a native-messaging heartbeat so a removed extension blocks the browser outright
      (GAPS G1). It is never the enforcement floor.

**Exit criteria:** the same config file produces identical decisions on Android and Windows;
service survives kill, reboot and clock rollback; uninstaller refuses during a locked block while
the 24h delayed release still works; credential release is verified by `LogonUser` against the
Windows account, with no Curfew-held password anywhere.

## Phase 3 — Sync (differentiator, part 1)
- [x] Lock lattice defined and property-tested *before* any merge code (GAPS C1)
- [x] Concrete pairing protocol, per-device keys, revocation path (GAPS C2, C3)
- [x] Signed encrypted op-log, rollups + checkpoint compaction (GAPS C4)
- [x] LAN transport (signed multicast beacons + TCP; mDNS and QUIC dropped, reasons in `lan.rs`)
- [x] Shared-folder transport: immutable segments, concurrent writers (GAPS C5) — latency UI pending
- [x] QR pairing: codes generated on-device (zxing, no network); Bluetooth beam still open
- [x] Session mirroring: start on PC, phone blocks; budgets shared across devices (Windows service and Android app both wired)
- [x] Conflict UI: the Devices screen names every session a peer ended that is still locked here, with the reason

**Exit criteria:** merging any two lock states never produces a weaker lock (property test); a
session started on the PC blocks the phone within 5s on LAN; killing either device mid-sync leaves
both in a valid state; op-log stays under 5 MB/year/device.

## Phase 4 — Calendar (differentiator, part 2)
- [ ] Android `CalendarContract` reader
- [ ] Windows ICS URL / CalDAV / local `.ics` reader
- [ ] Matchers (calendar, title regex, busy status, category, duration) -> profile + lock
- [ ] Lead-in/lead-out padding, per-matcher profiles, timezone + DST + RRULE correctness
- [ ] `calendar.snapshot` events so one device's calendar drives another device's blocks
- [ ] Preview timeline: "here is what tomorrow will block"

**Exit criteria:** a PC-only calendar drives a phone block with no server involved; DST transitions
and all-day events handled correctly in a dated test suite; a deleted event releases its block.

## Phase 5 — Depth
- [ ] Allowances/budgets with refill policies, launch limits, friction delays
- [ ] Challenge locks (typing, math), restart-required, NFC/QR token, peer-release lock
- [x] ~~Biometric keyguard suppression~~ — researched and dropped: it requires device owner or
      profile owner, both out under D4 (DECISIONS D9, GAPS A7). Device Admin drops with it.
- [ ] Emergency passes with quota + cooldown
- [ ] Allow-only mode; notification muting; keyword blocking
- [x] Browser extension (Chrome + Firefox), paired to the service, removal detected (done in Phase 2)
- [ ] Android VpnService DNS filter incl. DoH endpoint blocking (GAPS A2)
- [ ] Windows WFP filtering — spike first, it is the largest unknown (GAPS B2)
- [ ] Hardening: service ACLs, clock-tamper detection, re-lock on boot

**Exit criteria:** domain rules hold with Chrome DoH enabled; disabling the browser extension during
a locked session is detected and reported; no hardening step can make a device unrecoverable;
killing the Curfew service mid-session leaves no WFP filter behind (D11).

## Phase 6 — Release
- [ ] Widgets, quick tiles, CLI, webhooks on session start/end
- [ ] Stats: streaks, trends, CSV/JSON export
- [ ] Themes, externalized strings
- [ ] GitHub Pages site; GitHub Releases; F-Droid; winget/scoop
- [ ] Threat-model page and an explicit "this is not parental-control software" statement (GAPS D3)

**Exit criteria:** a new user can install on both platforms, pair them, and run a synced calendar
block without reading the docs.

## Later / optional
- Linux and macOS enforcers (core and sync are already portable)
- iOS (Screen Time API only — separate design, after 1.0)
- Community rule packs / importable blocklists
