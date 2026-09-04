# Decisions

Short ADR log. Newest last.

## D1 — Stack: Rust core, Kotlin Android, Tauri desktop (2026-09-04)
Core (`nodis-core`): policy model, rule engine, scheduler, calendar evaluation, op-log + merge,
crypto, sync transports. Exposed to Android through UniFFI; linked directly by the Windows service;
also compiled as a CLI.
Android app: Kotlin + Compose; all enforcement native.
Windows: Rust service + watchdog, Tauri 2 tray/desktop UI.
Browser extension: TypeScript MV3, native messaging to the service.
**Rejected:** Kotlin Multiplatform (JVM daemon on Windows = weak tamper story), Flutter (core logic
in Dart, weakest service story).

## D2 — Build order: Android first (2026-09-04)
Phase 1 delivers a standalone, usable Android blocker before any Windows or sync work. The phone is
where the hard permission constraints live, so failures surface early.

## D3 — Sync transports: LAN + shared folder + BT/QR. No iroh for now (2026-09-04)
1. **LAN** (mDNS + QUIC) — baseline, offline, zero infra.
2. **Shared cloud folder** (Syncthing / Drive / Dropbox holding encrypted log segments) — this is
   now the *only* cross-network path, so it moves up in priority and must be solid, not a toy:
   segment naming, compaction, conflicting writers, latency expectations shown in the UI.
3. **Bluetooth / manual QR beam** — offline, no-network fallback.
**Deferred:** iroh / NAT-hole-punched P2P. Any relay, even ciphertext-only, is infrastructure the
user did not ask for. Revisit only if the shared-folder path proves too laggy for locked-session
mirroring. Consequence to accept: with no shared folder configured, a phone on mobile data will not
receive a session started on the PC until it is back on the same LAN.

## D4 — Strictness ceiling: strongest possible *without* factory-reset-class setup (2026-09-04)
**Out:** device-owner mode (`dpm set-device-owner`), anything requiring a factory reset or wiping
the device to enroll, anything that cannot be undone by uninstalling with effort.
**In, as the top rung:**
- Android: AccessibilityService + overlay, UsageStats fallback, local VpnService DNS filter,
  NotificationListener, and **Device Admin** (not device owner) to require deactivation before
  uninstall — gated by the active lock. Note: sideloaded apps may be restricted from being granted
  Accessibility/Device Admin on Android 16+, so the setup wizard must detect and explain this.
- Windows: SYSTEM service + watchdog pair, service ACL'd against stop/delete, uninstaller refuses
  during a locked block, integrity-signed config, clock-rollback detection, re-lock on boot.
- Locks: password, typing/math challenge, timer, restart-required, NFC/QR token, peer-release,
  emergency passes with quota + cooldown.
Honest framing in the docs: a determined user with admin rights and time can always get out. We
raise the cost, we do not make it impossible, and we never make the device unrecoverable.
