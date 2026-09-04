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
known DoH endpoints, or the user must be told the rule is best-effort for that browser.

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

**A7. Suppressing biometric device unlock (D7).** The strong form needs Device Admin plus
`DevicePolicyManager.setKeyguardDisabledFeatures(KEYGUARD_DISABLE_BIOMETRICS)`. Whether a plain
device admin — not a device owner, which is out under D4 — may set that varies by Android version
and OEM, and recent releases have narrowed what legacy admins can do.
*Decision:* treat it as unproven until measured. **Spike on real hardware before Phase 5 promises
it**, across at least one Pixel and one heavily-skinned OEM device, and record the API level where
it stops working. The always-available layer beneath it is our own release prompt refusing
biometrics, which needs no permission at all; if the strong form is unavailable the health screen
says device unlock is unaffected rather than letting the user assume otherwise.

## B. Windows

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

**B2. DoH and alternative DNS defeat hosts-file blocking.** Already noted; the phased answer is
hosts (v1) then WFP (Phase 5). WFP work is the largest single unknown in the Windows track and
should get a spike before Phase 5 is scheduled.

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

**C1. "Strictest lock wins" is not yet a definition.** Locks form a partial order, not a total one
(is a password lock stricter than a 2-hour timer?).
*Decision owed in Phase 3, before any merge code:* define a lattice — merge produces a lock whose
release requires satisfying *every* merged lock's condition (conjunction), with the end time being
the maximum. Conjunction is total, monotone, and impossible to weaken by merging, which is exactly
the invariant. Write it as property tests first.

**C2. Pairing protocol unspecified.** "QR + PAKE" is a hand-wave.
*Decision owed in Phase 3:* concrete choice (SPAKE2 or a QR-transferred high-entropy key with no
PAKE at all — simpler and sufficient when the secret never crosses a network), plus replay
protection, per-device keys, and a documented revocation path.

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

**D5. Biometric suppression must never outlive the lock.** Turning off fingerprint unlock is a
change the user feels on every unlock, everywhere — not just inside Curfew. If our process dies
with the policy still set, we have degraded someone's device without being around to undo it.
*Decision:* the restriction is a property of the lock, not a setting. It is lifted when the lock
expires, when the 24-hour delayed release fires, when the session is released, on Device Admin
deactivation, and on uninstall; it is re-evaluated (and cleared if no session is live) on every
boot and every service start; and a synced remote session can never apply it without local
confirmation, exactly like Frozen mode (B4). `LockSet::suppresses_biometrics` returns false for any
expired lock, so the core cannot express "suppressed forever" even by mistake.

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
