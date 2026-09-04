# Decisions

Short ADR log. Newest last.

## D1 — Stack: Rust core, Kotlin Android, Tauri desktop (2026-09-04)
Core (`curfew-core`): policy model, rule engine, scheduler, calendar evaluation, op-log + merge,
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

## D5 — Name: Curfew (2026-09-04)
**Curfew** — *Calendar-Unified Rules For Every Window*. Repo `curfew`, crate `curfew-core`, config
`curfew.toml`, Android application id `com.curfew.app`.
Chosen because the name states the differentiator (calendar-scheduled blocking windows mirrored
across devices) and reads naturally in the product itself ("curfew active until 17:00").
**Rejected:**
- *DEADBOLT* — collides with the DeadBolt ransomware family (mass NAS attacks), plus a well-known
  Play-framework authorization module. Bad association for software that locks a machine and
  resists uninstall.
- *FOCI* — crowded scientific namespace (robotics planner, interferometry toolkit, microscopy foci
  counting) and most readers parse it as a typo of "focus".
- *YAFA / YAWP* ("Yet Another Focus App") — 5+ and 6+ existing GitHub projects respectively.
Namespace notes: `github.com/curfew` is an empty squatted account, which does not matter since the
repo lives under the owner's account. `usecurfew.com` was verified unregistered on 2026-09-04.

## D6 — No domain; site on GitHub Pages (2026-09-04)
No custom domain. Project site and docs ship from `/docs` or a `gh-pages` branch at
`https://<owner>.github.io/curfew`. Releases are GitHub Releases plus F-Droid.
Rationale: zero recurring cost matches the project's premise (no subscriptions, no infrastructure),
and a serverless app has nothing a domain would serve. `usecurfew.com` stays unregistered; if that
ever changes, Pages accepts a custom domain later with no migration.

## D7 — Locks use the OS screen lock, and suppress biometrics while a session runs (2026-09-04)
**Curfew does not invent a credential.** The `Password{hash}` lock is removed. Where a lock needs
proof of identity, it asks the operating system to verify the credential the user already has:
- **Android**: `BiometricPrompt` with `setAllowedAuthenticators(DEVICE_CREDENTIAL)` — the real
  keyguard PIN, pattern or password. No permission needed, no secret stored by us.
- **Windows**: `LogonUser` against the signed-in account's password.

Rationale: a homegrown password is a secret we must store, hash, migrate, sync and eventually leak,
and it is *weaker friction* than the device PIN because the user picks something typeable. Reusing
the OS credential means there is nothing to steal from us, nothing to forget separately, and no
"reset my Curfew password" escape hatch to build.

**Biometrics are never accepted for a release.** A fingerprint is a reflex; typing a PIN is the
friction being bought. The prompt asks for device credential only.

**While a session runs, biometric unlock of the device itself can be suppressed**, so every glance
at the phone costs a full PIN entry — the strongest legitimate friction available without touching
device-owner mode (D4). Layers, strongest first:
1. Device Admin + `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_BIOMETRICS)`. Whether a plain
   device admin (not owner) may set this varies by Android version, so it needs a hardware spike
   before Phase 5 promises it (GAPS A7).
2. If unavailable: our own release prompt still refuses biometrics, and the health screen says
   plainly that device unlock is unaffected. Degrade, never die.

On Windows the equivalent — disabling Windows Hello sign-in — is a machine-wide security policy
affecting logon itself. **Out of scope.** Windows gets the credential prompt only.

**Once a session is locked, Curfew adds no lock of its own on top.** There is exactly one gate. We
do not password-protect our settings screen, our app, or our uninstall path with a second secret;
edits that would weaken an active session are simply refused until the lock ends, and the only
ways out are the lock's own conditions or the 24-hour delayed release (GAPS D1).

**Consequence to accept:** suppressing biometrics is a change to the device the user notices
everywhere, not only inside Curfew. It must be lifted the instant the lock ends, restored after a
crash, a reboot or an uninstall, and never applied by a synced remote session without local
confirmation (GAPS D5).
