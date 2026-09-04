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

## D8 — Rule patterns are globs, not regular expressions (2026-09-04)
Targets that need pattern matching (window titles, URLs, file paths) use shell-style globs — `*`
for any run of characters, `?` for one — matched case-insensitively.

Rationale: matching runs on every foreground change, on battery, in the one code path that must
never stall. A user-supplied regex is both a performance cliff and a denial-of-service surface
(catastrophic backtracking), and the user writing the regex is also the person the lock is
protecting — a rule that hangs the enforcer is a bypass. Globs express every rule people actually
write (`*/shorts/*`, `*- YouTube*`, `C:\Games\*`), match in bounded time with a single backtrack
point, and are teachable to someone who has never seen a regex. Revisit only when a concrete rule
appears that globs cannot express.

## D9 — Biometric device unlock cannot be suppressed, and we say so (2026-09-04)
**Supersedes the "suppress biometrics device-wide" clause of D7.**

Researched: `DevicePolicyManager.setKeyguardDisabledFeatures` with `KEYGUARD_DISABLE_BIOMETRICS` /
`KEYGUARD_DISABLE_FINGERPRINT` requires the caller to be a **device owner or profile owner**, plus
`USES_POLICY_DISABLE_KEYGUARD_FEATURES`. A plain active device admin cannot set it on modern
Android. Device owner requires provisioning from a factory-reset device — ruled out permanently by
the user constraint behind D4. Profile owner requires a managed work profile, which is a different
product shape and would not affect the personal profile's keyguard anyway. There is no third-party
API to disable device biometrics.

**Therefore: no device-wide biometric suppression on Android, and none on Windows (Windows Hello
suppression is a machine-wide logon policy, already out of scope in D7).**

What survives, and is what the user actually asked for at the layer we can honestly deliver:
**Curfew never accepts a biometric as proof for a release.** `Lock::DeviceCredential` prompts with
`setAllowedAuthenticators(DEVICE_CREDENTIAL)` on Android and `LogonUser` on Windows, so ending a
session early always costs a typed PIN or password, never a fingerprint reflex.

Consequences:
- `LockSet::disable_biometric_unlock` and `suppresses_biometrics` are **removed from the core**. A
  policy we cannot enforce must not be expressible; a field that silently does nothing is worse
  than an absent feature.
- **Device Admin is dropped from the plan entirely.** Its remaining powers for a non-owner admin
  are negligible post-deprecation, and it was the scariest dialog in the onboarding wizard (GAPS
  A6). One fewer permission, one fewer thing to revoke, one fewer way to look like malware.
- The protection-health screen states plainly: *"Unlocking your phone with a fingerprint still
  works. Curfew cannot change that. Ending a session early always asks for your PIN."*
- GAPS A7 closes as **cannot**, not as **pending spike**. No hardware spike is needed to learn
  something the API contract already says.

## D10 — Pairing is a QR-transferred group key, not a PAKE (2026-09-04)
Closes GAPS C2. Pairing a device transfers a 256-bit group key over the camera:

1. The already-paired device shows a QR containing `curfew://pair?v=1&g=<group-id>&k=<32 random
   bytes, base32>&f=<fingerprint of the shower's Ed25519 public key>`, valid for 120 seconds.
2. The scanner derives the group's symmetric key with HKDF-SHA256 over `k`, generates its own
   Ed25519 identity, and publishes a signed `device.join` op naming its public key.
3. The shower verifies the join arrived over a transport within the pairing window and admits it,
   writing a signed `device.admit`. Both sides then display a 6-word fingerprint to compare.
4. A device with no camera falls back to typing the same key as 52 base32 characters. Nothing else
   is offered: a short numeric code over a network is exactly the case that needs a PAKE, so we
   avoid needing one by never having a short code.

Rationale: SPAKE2 exists to turn a *low*-entropy secret into a strong channel. A QR code carries a
*high*-entropy secret directly, out of band, over an optical channel an attacker would have to be
in the room to observe. Adding a PAKE would add a dependency and a protocol state machine to solve
a problem we do not have. Replay is handled by the op-log's hash chain and per-device sequence
numbers, not by the pairing step.

Revocation (GAPS C3): any device may write `device.revoke`; revoked keys are refused for all ops
with a later sequence number. Revoking never shortens a lock on the remaining devices, so it is
safe to allow at any time, including during a lock.

## D11 — Windows enforcement ladder: hosts, then a local DNS proxy, then user-mode WFP (2026-09-04)
Closes GAPS B2's "largest unknown". Researched: the Windows Filtering Platform is usable from
**user mode** through the Base Filtering Engine — `FwpmEngineOpen0` / `FwpmFilterAdd0` with
conditions such as `FWPM_CONDITION_ALE_APP_ID` at the `ALE_AUTH_CONNECT` layers block a named
executable's connections outright. Only *callout* filters (deep packet inspection) need a kernel
driver, and we need none: block/permit by app and by remote address is enough.

That removes the driver-signing problem from the Windows track entirely — no kernel driver means no
EV certificate, no attestation signing, no boot-time risk (B1, D2). WFP filters are added with a
session that can be marked `FWPM_SESSION_FLAG_DYNAMIC`, so **every filter dies with the process**:
a crashed Curfew cannot leave a machine unable to reach the network. That property is the reason to
prefer WFP over the hosts file as the eventual primary, and it is exactly the D5-shaped guarantee
we want for anything that changes system state.

Ladder, each rung shipping only after the one below is solid:
1. **hosts file** (Phase 2) — trivial, reversible, defeated by DoH.
2. **local DNS proxy** (Phase 3-4) — answers on 127.0.0.1, set as the interface resolver; gives
   per-domain decisions and logging without touching packets.
3. **user-mode WFP** (Phase 5) — per-executable and per-address blocking, dynamic session, no
   driver.

DoH, on both platforms, is handled the same way (GAPS A2): serve NXDOMAIN for Mozilla's canary
domain `use-application-dns.net` so Firefox disables DoH by itself, block the known DoH endpoint
list at the DNS and address layers, and where a browser still resolves privately, **say so** — the
health screen marks that browser as domain-rules-best-effort rather than implying a guarantee.
