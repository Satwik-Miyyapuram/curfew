# Gaps, risks and open problems

Honest audit of the plan as of 2026-09-04. Anything unresolved here is a thing that will bite during
implementation if we pretend it is settled. Each item: the problem, and the decision or the decision
we still owe. Items marked **OPEN** need a call before the phase that touches them.

## A. Android reliability — the biggest cluster

**A1. Surviving reboot, force-stop and OEM killers.** Accessibility services stop on reboot until
the user unlocks once; a force-stop from Settings kills everything until the app is launched again;
Xiaomi/Oppo/Vivo/Samsung aggressively kill background apps unless whitelisted for autostart. A
blocker that silently dies is worse than no blocker.
*Decision:* `BOOT_COMPLETED` receiver + `LOCKED_BOOT_COMPLETED`; battery-optimization exemption
request in onboarding; per-OEM autostart deep-links (dontkillmyapp-style table); and a **heartbeat**
— if enforcement has not reported in for N minutes, the next app launch shows a "protection was
down from HH:MM to HH:MM" banner rather than pretending nothing happened. Locked sessions resume
on boot from persisted state, never reset.

**A2. Browser URL blocking is per-browser and brittle.** Reading the URL bar via accessibility node
inspection differs across Chrome, Firefox, Samsung Internet, Brave, and breaks on updates.
*Decision:* two layers — VpnService DNS filtering for domain-level blocking (browser-agnostic,
robust), accessibility URL reading only as an enhancement for path-level rules, with a per-browser
adapter table and graceful "domain-level only" fallback when a browser is unrecognized. Note DoH:
Chrome/Firefox with DNS-over-HTTPS bypass DNS filtering entirely, so the VPN layer must also block
known DoH endpoints, or the user must be told the rule is best-effort for that browser. Concretely (D11): serve
NXDOMAIN for `use-application-dns.net` so Firefox disables DoH itself, block the known DoH endpoint
list, and label any browser that still resolves privately as best-effort on the health screen.

**A3. In-app targets (Shorts, Reels, explore tabs).** Requires accessibility node matching per app,
and it breaks with app updates.
*Decision:* ship as clearly-labeled "fragile rules" with a version-pinned matcher pack, updatable
independently of the app. Never advertised as guaranteed.

**A4. Battery.** Accessibility + polling + VPN + notification listener at once is a real drain.
*Decision:* budget of <3%/day added drain; poller frequency adaptive (1s while a session is active,
15s idle, stopped when no rules can fire); VPN filter enabled only while a session with domain
rules is active. Measured in Phase 1 exit criteria, not assumed.

**A5. Multi-user, work profiles, secondary users.** Blocking applies per user profile; a work
profile or second user is an obvious bypass.
*Decision:* **detect and disclose, do not chase.** Enforcing across users requires device-owner
mode, which is out (D4), so we detect that other users or a work profile exist and say so on the
health screen — "apps are not blocked in your work profile" — rather than implying a guarantee we
cannot keep. The threat model already says a determined user gets out; the failure we refuse to
ship is a *silent* one.

**A6. Permission onboarding is the actual product.** Five permissions with scary system dialogs
(accessibility, usage access, overlay, notification access, VPN consent, battery exemption, and
optionally device admin) is where users abandon these apps.
*Decision:* a staged wizard — the app must be useful after granting *one* permission, with each
further permission framed as "this adds X". Explicit "what breaks without this" per item, and a
health screen showing which layers are live.

**A7. Suppressing biometric device unlock — CLOSED: we cannot, and we say so (D9).**
Researched rather than spiked: `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_BIOMETRICS)` requires
the caller to be a **device owner or profile owner**. Device owner needs factory-reset provisioning
(out under D4, by explicit user constraint); profile owner needs a managed work profile and would
not touch the personal keyguard anyway. No third-party API exists.
*Decision:* the feature is removed from the plan, not deferred — `LockSet` no longer carries it, so
the core cannot express a policy nothing can enforce. **Device Admin is dropped entirely**, which
also removes the scariest permission from onboarding (A6). What remains, and is the part that
matters: Curfew never accepts a biometric for a release, so ending a session early always costs a
typed PIN. The health screen states that phone unlock itself is unaffected.

**B1. Unsigned binaries = SmartScreen warnings and antivirus false positives.** A service that
blocks uninstallation, kills processes, rewrites hosts and filters network traffic looks exactly
like malware to heuristic AV. Code-signing certificates cost €200-400/year, which contradicts D6
(zero recurring cost).
*Decision:* accept SmartScreen friction for the reference build; publish through **winget and
scoop** (which users trust as a channel) and via GitHub Releases with published checksums and
reproducible-build instructions; document AV false-positive workarounds and submit the binaries to
Microsoft and major vendors for allowlisting once there is a release. Revisit signing only if
donations cover it.
*Also decided:* **no free-for-OSS certificate programs.** They require re-application, attach the
identity of one maintainer to the binaries, and can be revoked on a vendor's judgement — a
dependency on someone else's goodwill for a project whose whole premise is depending on nobody.
Reproducible builds are the substitute: verifiability instead of vouching.

**B2. DoH and alternative DNS defeat hosts-file blocking — CLOSED (D11).** Ladder is hosts
(Phase 2) then a local DNS proxy then **user-mode WFP** (Phase 5). Researched: WFP block filters by
`FWPM_CONDITION_ALE_APP_ID` need no kernel driver — only callouts do — so the Windows track needs no
driver signing and carries no boot-time risk. Filters are added on a dynamic session, so they die
with the process and a crash cannot strand the machine offline. DoH itself: NXDOMAIN for the
`use-application-dns.net` canary so Firefox stands down, a blocked DoH endpoint list, and honest
"best effort for this browser" labelling where a browser still resolves privately.

**B3. Non-admin and multi-session Windows.** Standard users cannot install the service; a second
Windows account is an obvious bypass.
*Decision:* installer requires admin, service runs as SYSTEM and enforces across all sessions;
document that another admin account can always disable it — this is the honest ceiling of D4.

**B4. Frozen mode risks data loss.** Locking or logging off the machine can discard unsaved work.
*Decision:* countdown warning, cancellable up to 60s before it fires, never available as an
instant-fire action, never triggered by a synced remote session without local confirmation.

**B5. ARM64.** Development machine is Windows on ARM.
*Decision:* CI builds `x86_64-pc-windows-msvc` and `aarch64-pc-windows-msvc`; the Android NDK's
x86_64 Windows toolchain runs under emulation locally — slower, acceptable.

## C. Sync and crypto

**C1. "Strictest lock wins" is not yet a definition — CLOSED and implemented.** Locks form a
partial order, not a total one (is a credential lock stricter than a 2-hour timer?).
*Decision, now shipped in `crates/curfew-core/src/lock.rs`:* merge is a lattice join — conjunction
of every merged lock's conditions, maximum of the end times, minimum of any promised delayed
release, with the empty set as the identity element. Monotonicity holds by construction rather than
by care. Six property tests hold it down, and they caught a real non-commutativity bug on the first
run, which is the whole argument for writing them first.

**C2. Pairing protocol — CLOSED (D10).** A 256-bit group key carried by QR code, HKDF-SHA256
to the group key, Ed25519 per-device identities, `device.join` / `device.admit` / `device.revoke`
ops, 120-second pairing window, 52-character base32 fallback for a device with no camera. No PAKE:
a PAKE exists to rescue a low-entropy secret, and an optical channel does not carry one. Replay is
the op-log's job (hash chain + per-device sequence numbers), not pairing's.

**C3. Device revocation during an active lock.** Removing a device must not be an escape hatch, but
a lost or stolen phone must be removable.
*Decision:* unpairing is permitted but the *remaining* devices keep the merged lock until its own
conditions are met. Losing a device never shortens a lock, so revocation is safe to allow freely.

**C4. Op-log growth and compaction.** An append-only log with per-second budget consumption events
grows without bound, especially in a shared cloud folder.
*Decision:* budget consumption written as periodic rollups (1/min while active), snapshot +
truncate below a signed checkpoint, target <5 MB/year/device.

**C5. Shared-folder transport correctness.** Concurrent writers, partial file visibility during
cloud sync, and clients that resurrect deleted files.
*Decision:* write-once immutable segments named by `(deviceId, seq, hash)`, never mutated; readers
tolerate missing/duplicated/late segments; no locking assumptions. Latency shown honestly in the UI.

**C6. Clock tampering across reboots.** A monotonic clock resets at boot, so "lock until T" can be
attacked by rebooting with a rolled-back clock.
*Decision:* persist `(boot_id, monotonic_at_write, wall_at_write)` on every heartbeat; on boot,
a wall clock earlier than the last observed wall clock is treated as tampering — the lock stays
active and the UI says why. Never *extend* a lock on suspicion; just refuse to shorten it.

## D. Safety, recovery and ethics

**D1. Forgotten password mid-lock.** Users will lock themselves out with a challenge they cannot
pass, on a device they need.
*Decision:* every lock, at every strictness, has a documented **last-resort exit: a 24-hour delayed
release** the user can start at any time. It cannot be shortened, it is visible in the UI from the
start, and it makes the tool honest rather than a trap. Cold Turkey's uninstall-block and
SelfControl's no-exit design have both produced users reinstalling their OS; we will not ship that.

**D2. The app must never make a device unrecoverable.** Reaffirms D4. No device-owner enrollment,
no lock-task kiosk, no boot-loop risk, no encryption of user data we could fail to decrypt.

**D3. This is a self-control tool, not a surveillance tool.** Usage data never leaves the device
group; there is no "parent" or "admin" role, no remote monitoring of another person, no covert
mode. Refusing that use case is a design constraint, and it should be stated in the README.

**D4. Usage statistics are sensitive.** A full app/URL usage timeline is among the most private data
on a device.
*Decision:* SQLite encrypted at rest (SQLCipher or platform keystore-wrapped key), no cloud backup
of the stats DB by default (`allowBackup=false`), export requires explicit action.

**D5. A policy we cannot lift must never be set — CLOSED (D9).** Originally about biometric
suppression, which is now impossible for us to set at all. The general rule it produced survives and
binds every future feature that changes state outside Curfew: **any system-wide change is a property
of the lock, not a setting.** It must be lifted when the lock expires, when the delayed release
fires, when the session is released, and on uninstall; it must be re-evaluated and cleared on every
boot and service start; and a synced remote session may never apply it without local confirmation
(B4). The Windows WFP work is the next feature this binds — hence the dynamic filter session in D11,
which makes the OS itself undo our changes if we die.

**D6. One gate, not two (D7).** Curfew does not put a password on its own settings, app entry or
uninstall path. A second secret is a second thing to forget and a second thing to leak, and it
buys nothing: edits that would weaken an active session are refused outright until the lock ends.

## E. Process and delivery

**E1. No acceptance criteria per phase.** Fixed in ROADMAP — each phase now has explicit exit
criteria; "done" means the criteria pass, not that the code exists.

**E2. Enforcement is hard to test in CI.** Rule engine is pure and fully testable; the platform
layers are not.
*Decision:* pure core with property tests and golden files (CI), instrumented Android tests on an
emulator (CI), Windows service tests in a VM (manual per release, scripted checklist).

**E3. Config schema migrations.** `curfew.toml` will change shape; users' configs must survive.
*Decision:* `schema_version` field from day one, forward-only migrations with golden-file tests per
version, and a refusal-to-load path that preserves the old file rather than corrupting it.

**E4. Repo hygiene not yet in place.** No CONTRIBUTING, SECURITY.md, CoC, issue templates, PR
template, changelog policy, or release/versioning policy. Cheap; do it in Phase 0.

**E5. Our own UI accessibility.** An app that uses the accessibility API owes it to be accessible:
screen-reader labels, contrast, font scaling, no timing-only interactions. Phase 1 checklist item.

**E6. Localization.** Strings externalized from day one; no translations promised until 1.0.

## F. Deliberately out of scope (stated so nobody re-litigates it later)

- iOS (Screen Time API is a different design; revisit after 1.0)
- Any parental-control or employee-monitoring use case (D3 above)
- Rooted-device or admin-level bypass prevention (documented ceiling, see D4)
- NAT-hole-punched P2P until the shared-folder path proves insufficient (D3)
- Custom domain and any paid infrastructure (D6)

## G. Prior-art review: Curbox (2026-09-05)

Read in full: [curbox-android](https://github.com/curbox-app/curbox-android) and
[curbox-extension](https://github.com/curbox-app/curbox-extension), the closest existing project to
this one — GPL-3, no telemetry, Android plus a browser extension, no PC app.

**G1. A browser extension cannot be a lock, so it cannot replace the Windows app — OPEN, decided.**
Curbox's extension blocks by injecting an overlay from a content script. It requests no
`declarativeNetRequest` permission, so nothing is stopped before a page loads, and an extension can
be disabled from `chrome://extensions` in two clicks — a page extensions are forbidden to act on, so
no watchdog is possible there. Their own framing concedes this: warning screens, a `canProceed`
flag, a Proceed button. That is intervention, not enforcement, and it would collapse Invariant 2.
*Decision:* the Windows service stays the enforcement floor and Phase 2 is unchanged. The extension
is a **granularity layer, not a substitute**: a service sees processes and hostnames and can never
see URL paths, so `/shorts`, `/reels` and an in-page feed are only reachable from a content script.
The two are joined so the weak layer cannot be quietly removed — during a locked session the service
requires a native-messaging heartbeat from the extension, and a missing extension blocks the browser
process outright. The granularity is then gained without spending any enforcement.

**G2. Device Admin was dropped for the wrong reason — OPEN.** A7 removed Device Admin because it
cannot suppress biometric unlock, which is true. Curbox uses it for something else: `AdminReceiver`
plus `AntiUninstallBlocker` keeps the admin active so the app cannot be uninstalled from Settings,
and bounces the user off the deactivation screen while a lock is held. Device *admin* is not device
*owner*: it needs no factory-reset provisioning and is deactivated by the user at will once a lock
ends, so it stays inside both D2 (never unrecoverable) and the standing constraint that anything
requiring a factory reset is out. It is a real strengthening that A7 discarded as a side effect.
*Decision needed:* re-evaluate Device Admin for uninstall protection only, weighed against A6 — it
is the scariest dialog in onboarding, so it must be optional, last in the wizard, and clearly
described as "makes uninstalling harder while a lock is running", never as a requirement.

**G3. Their sync design is unusable here; their envelope is not — CLOSED.** Curbox syncs through a
hosted Supabase project with accounts (`src/lib/supabase.ts`), which is a server and a recurring
cost, both ruled out by D6 and the no-server premise. The cryptographic envelope, however, is sound
and transport-independent, and matches what D10 already describes: PBKDF2-HMAC-SHA256 at 600k
iterations derives a KEK, the KEK wraps a random 32-byte DEK, records are AES-256-GCM with the AAD
bound to `user|namespace|record_key` so a ciphertext cannot be replayed into another slot, and a QR
payload carries the DEK to a new device without retyping the passphrase.
*Decision:* keep D10's pairing as designed and reuse this AAD-binding discipline for the op-log
records, over Curfew's own transports. No dependency on their code is taken.
