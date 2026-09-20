# Comprehensive Design and Code Review: Curfew (Windows & Android)

**Date**: September 2026  
**Target Applications**: Windows Service / Tray / Webview UI (`curfew-svc`, `curfew-win`, `curfew-tray`, `curfew-app`), Android Kotlin Application (`android/app`, `android/policy`), and Shared Rust Core (`curfew-core`, `curfew-sync`, `curfew-ffi`).

---

## Status after verification (11 September 2026)

Every finding below was checked against the code before anything was changed. Findings that were true are fixed on branch `installer-no-reboot`; two claims in the original text were wrong and are corrected in place, with the original wording struck through.

| # | Finding | Verified as | Outcome |
| :-- | :--- | :--- | :--- |
| 1 | Android accepts a fingerprint to end a session (§3.1) | True | **Fixed** — `Auth.kt` allows `DEVICE_CREDENTIAL` only; Android 10 and below use the keyguard's confirm screen. |
| 2 | Android budget frozen while the app stays in front (§3.2) | True, but for *both* detectors, not only the accessibility service — the poller resamples the same app and the enforcer treated that as "no change" | **Fixed** — `Enforcer.onTick` charges and re-decides every 5 s; screen-off stops the meter. |
| 3 | Sync deadlock on mutual push (§1.3) | True | **Fixed** — locks held per frame, never across a conversation; the beacon wait holds nothing. |
| 4 | Session 0 cannot see the foreground window (§2.1) | True | **Fixed** — the tray reports the window (`Request::Seen`); a tick trusts the report for 10 s. |
| 5 | DNS interface list parsed from localized `netsh` output (§2.2) | True | **Fixed** — registry listing through PowerShell, `netsh` as fallback. |
| 6 | Windows Hello PIN offered but never verifiable (§2.3) | True | **Fixed** — generic name/password prompt, account prefilled, message says which password. Hello is *not* supported and will not be: `LogonUser` cannot check it. |
| 7 | Android has no URL/domain blocking (§3.3) | True | **Open, parked** — deliberately deferred until calendar sync and scheduling are solid. Documented gap. |
| 8 | `BootReceiver` crashes on direct boot (§3.4) | **Wrong** | Nothing crashed: without `directBootAware` the broadcast is never delivered, so the receiver ran at first unlock. The undeliverable action is removed and the reason written into the receiver. |
| 9 | Typing challenge can be pasted (§3.5) | True | **Fixed** — no selection toolbar; an edit longer than a keystroke is dropped. |
| 10 | Web budgets never accrued on Windows (§2.4) | True | **Fixed** — the heartbeat carries the focused tab's URL and the interval is charged. |
| — | Named pipe SDDL (§2.4 item 2) | Not verified | Open. |
| — | OEM battery-optimisation prompt (§3.6 item 3) | Not verified | Open. |

---

## Executive Summary

Curfew is designed as a strict, unyielding commitment device that enforces digital boundaries against the user's impulsive self. Unlike conventional screen-time trackers that rely on voluntary compliance, Curfew's core thesis requires:
1. **Mathematical Invariants**: Locks form a join-semilattice (locks only tighten, never loosen without proof).
2. **Platform Dumb Actuators**: Pure policy is decided in a shared Rust core (`curfew-core`); platforms only observe state and execute verdicts.
3. **Intentional Friction**: Bypassing an active curfew requires deliberate, slow cognitive effort (never reflex-based shortcuts like biometrics).
4. **Resilience to Tampering**: Immune to clock rollbacks, task termination, and network evasion.

This review conducted an end-to-end audit across the entire codebase. While the core mathematical engine (`curfew-core`) and serialization boundaries are well-constructed and all 136 core Rust unit and integration tests pass, **critical architectural and security defects were uncovered in both the Windows and Android platform implementations**, as well as a **distributed deadlock vulnerability in the P2P sync engine**.

---

## Scorecard: Architectural Principles & Invariants

| Principle / Invariant | Core Requirement | Windows Status | Android Status | Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **Principle 1: Locks Only Tighten** | Lattice merge $L = L_1 \sqcup L_2$; cannot shorten duration or drop constraints. | Verified in `curfew_core::lock`. | Verified via UniFFI bindings. | **PASSED** |
| **Principle 2: One Engine, Many Hands** | Pure policy in `curfew-core`; platforms are dumb actuators. | Verified; platforms consume `curfew_core::decide`. | Verified via UniFFI bindings. | **PASSED** |
| **Principle 3: Friction Scale Matches Intent** | High-friction unlock; biometrics strictly disallowed for ending sessions. | Verified (`CredUI` password prompt). | ~~**FAILED (`Auth.kt`)**: Accepts `BIOMETRIC_STRONG` (fingerprint/face).~~ Fixed: `DEVICE_CREDENTIAL` only. | ~~**CRITICAL FAILURE**~~ **PASSED** |
| **Principle 4: Fail Safe Means Fail Shut** | Crashes, power loss, or corrupt state must never unlock early. | Verified (`state.rs` atomic write & recovery). | ~~Mixed (`BootReceiver` crashes on Direct Boot).~~ Verified: enforcement resumes at first unlock; nothing user-installed runs before it. | **PASSED** |
| **Principle 5: No Cloud Dependency** | Local P2P sync via UDP multicast/TCP, no centralized telemetry. | Implemented in `curfew-sync`. | Implemented in `SyncHub.kt`. | **PASSED** |
| **Principle 6: Real-time Granular Budgets** | Active foreground usage charged per second; blocks instantly on expiry. | ~~**BROKEN in Service Mode**: Session 0 cannot see foreground window.~~ Fixed: tray reports the window. | ~~**BROKEN when A11y Active**: Timer does not tick down while remaining in app.~~ Fixed (both detectors): charged every 5 s. | ~~**CRITICAL FAILURE**~~ **PASSED** |
| **Invariant 4 / Gap A2: Web Domain Blocking** | Domain and path-level blocks across all browsers. | Implemented via Hosts + DNS Proxy + Native Messaging. | **MISSING**: A11y does not inspect browser URLs; no VPN service exists. | **CRITICAL GAP** |

---

## 1. Shared Core & Sync Review (`curfew-core`, `curfew-sync`, `curfew-ffi`)

### 1.1 Lock Lattice & Convergence Mechanics
- **Files**: [`crates/curfew-core/src/lock.rs`](crates/curfew-core/src/lock.rs), [`crates/curfew-core/src/engine.rs`](crates/curfew-core/src/engine.rs)
- **Status**: **Robust**
- **Analysis**:
  The `LockSet` structure implements a bounded join-semilattice:
  ```rust
  pub fn merge(&self, other: &Self) -> Self {
      let until = match (self.until, other.until) {
          (Some(a), Some(b)) => Some(a.max(b)), // latest deadline always wins
          (Some(a), None) | (None, Some(a)) => Some(a),
          (None, None) => None,
      };
      let conditions = self.conditions.union(&other.conditions).cloned().collect();
      Self { conditions, until }
  }
  ```
  The merge is monotonic, associative, and commutative. Once a lock condition is added to a session, no code path within `curfew-core` allows removing it without verified proof of satisfaction.

### 1.2 Clock Tampering & Time Witness Engine
- **Files**: [`crates/curfew-core/src/witness.rs`](crates/curfew-core/src/witness.rs), [`crates/curfew-win/src/state.rs`](crates/curfew-win/src/state.rs)
- **Status**: **Passed with Edge Case Note**
- **Analysis**:
  Curfew verifies time progression using a monotonic witness combining:
  1. Monotonic wall-clock checks ($T_{now} \ge T_{last}$).
  2. Operating system boot counters and monotonic uptime (`GetTickCount64` on Windows, `SystemClock.elapsedRealtime()` on Android).
  3. Peer gossip via signed sync headers.
- **Edge Case**:
  State writes on Windows are atomic (`tempfile` + rename). However, if an abrupt system shutdown or power loss occurs within 1 second of boot, the persisted boot counter only updates on the first service tick. Rapid reboot cycles can introduce a theoretical sub-second clock drift before detection activates.

### 1.3 Distributed Sync Deadlock & P2P Networking
- **File**: [`crates/curfew-sync/src/node.rs`](crates/curfew-sync/src/node.rs#L170-L260)
- **Severity**: **P0 High Severity (Distributed Deadlock)**
- **Root Cause**:
  In `Node::push()`:
  ```rust
  let (entries, peer) = {
      let mut log = self.shared.log.lock().expect("log");
      let mut peers = self.shared.peers.lock().expect("peers");
      // CRITICAL FLAW: Blocking network I/O executed while holding mutexes!
      crate::lan::dial(&peer.address, ...); 
  };
  ```
  In `answer_loop`:
  ```rust
  let mut log = self.shared.log.lock().expect("log");
  let mut peers = self.shared.peers.lock().expect("peers");
  crate::lan::serve(&mut stream, &mut log, &mut peers, ...);
  ```
- **Consequence**:
  When Device A (Windows) and Device B (Android) simultaneously push state updates (e.g. triggered by a beacon or concurrent session start):
  1. Device A holds its `shared.log` lock while blocking on TCP `dial` to Device B.
  2. Device B's incoming TCP listener tries to acquire Device B's `shared.log` lock to answer Device A.
  3. Concurrently, Device B is holding its own lock trying to dial Device A.
  4. Both devices hang until socket read timeouts (10 seconds) expire, leading to observed 45–55 second sync delays in `SyncHub.kt`.
- **Remediation**:
  Snapshot required log entries and peer addresses under the lock, release the lock immediately, and only then perform network I/O.

### 1.4 FFI Boundary & Memory Safety
- **File**: [`crates/curfew-ffi/src/lib.rs`](crates/curfew-ffi/src/lib.rs)
- **Status**: **Well-Architected**
- **Analysis**:
  The UniFFI layer cleanly translates Rust data structures to Kotlin. All FFI boundaries encapsulate calls within `std::panic::catch_unwind`, mapping panics to `CurfewError` and preventing abrupt JVM crashes.

---

## 2. Windows Implementation Review

### 2.1 Windows Service Architecture & Session 0 Isolation Failure
- **Files**: [`crates/curfew-svc/src/runner.rs`](crates/curfew-svc/src/runner.rs), [`crates/curfew-win/src/windows.rs`](crates/curfew-win/src/windows.rs#L60-L85), [`crates/curfew-win/src/tick.rs`](crates/curfew-win/src/tick.rs#L260-L290)
- **Severity**: **P0 Critical Architectural Flaw**
- **Root Cause**:
  When installed as a Windows service (`curfew install`), `curfew-svc.exe` runs under `NT AUTHORITY\SYSTEM` in **Session 0**. Since Windows Vista, Session 0 is completely isolated from the interactive user desktop (Session 1+).
  
  In `curfew-win/src/windows.rs`:
  ```rust
  pub fn foreground() -> Option<Process> {
      let hwnd = unsafe { GetForegroundWindow() }; // ALWAYS NULL IN SESSION 0!
      if hwnd.is_null() {
          return None;
      }
      // ...
  }
  ```
  In `curfew-win/src/tick.rs`:
  ```rust
  fn accrue(&mut self, now: Timestamp, seconds: u32, foreground: Option<Process>) {
      let Some(process) = foreground else { return }; // BAILS OUT IMMEDIATELY
      // ...
  }
  ```
- **Consequences**:
  1. **Application budgets never tick down in service mode**: Because `GetForegroundWindow()` always returns `NULL` in Session 0, `foreground` is always `None`. Usage is never accrued. A user with a 30-minute daily budget for an application can run it indefinitely without the budget ever depleting.
  2. **Window title and keyword rules never trigger**: `EnumWindows()` in Session 0 only inspects Session 0 windows. Rules matching `Target::WindowTitle` or `Target::Keyword` (e.g. matching `"- YouTube"`) are completely inert when running as a service.
- **Remediation**:
  The tray process (`curfew-tray.exe`) or UI runner runs in the interactive user's session (Session 1).
  1. Extend IPC protocol with `Request::ReportForeground { exe: String, title: String }`.
  2. Have `curfew-tray` query `GetForegroundWindow()` on a 1-second interval and push active window telemetry to `curfew-svc` over the named pipe.
  3. Alternatively, spawn a lightweight Session Agent in the active console session (`WTSGetActiveConsoleSessionId`).

### 2.2 DNS Proxy Internationalization Breakdown
- **File**: [`crates/curfew-win/src/dns.rs`](crates/curfew-win/src/dns.rs#L605-L635)
- **Severity**: **P1 High Bug**
- **Root Cause**:
  In `dns.rs`, Curfew configures network adapters to point to the local DNS proxy (`127.0.0.1:53`) by executing `netsh interface ipv4 show dnsservers` and scraping the output:
  ```rust
  if line.starts_with("Configuration for interface ") {
      let name = line.trim_start_matches("Configuration for interface ").trim().trim_matches('"');
      // ...
  }
  ```
- **Consequence**:
  On non-English Windows installations (German `"Konfiguration der Schnittstelle..."`, French `"Configuration de l'interface..."`, Spanish, Japanese), `netsh` outputs localized strings.
  Parsing fails completely: `parse_interfaces` returns an empty set. The system never points the adapters to `127.0.0.1`, and when Curfew stops, it fails to restore the user's original DNS configuration.
- **Remediation**:
  Use the Win32 IP Helper API (`GetAdaptersAddresses` / `SetInterfaceDnsSettings` from `iphlpapi.dll`) or PowerShell CIM cmdlets (`Get-DnsClientServerAddress`) instead of scraping localized console output.

### 2.3 Windows Hello PIN vs. `LogonUserW`
- **Files**: [`crates/curfew-win/src/credential.rs`](crates/curfew-win/src/credential.rs#L40-L85), [`crates/curfew-tray/src/prompt.rs`](crates/curfew-tray/src/prompt.rs#L50-L115)
- **Severity**: **P1 High Usability Bug**
- **Root Cause**:
  `curfew-tray` invokes `CredUIPromptForWindowsCredentialsW` with flag `CREDUIWIN_ENUMERATE_CURRENT_USER`. When the user enters their Windows Hello PIN, the credential buffer is sent to `curfew-svc`, which calls:
  ```rust
  LogonUserW(user, domain, secret, LOGON32_LOGON_INTERACTIVE, LOGON32_PROVIDER_DEFAULT, &mut token)
  ```
- **Consequence**:
  `LogonUserW` with `LOGON32_LOGON_INTERACTIVE` **strictly rejects Windows Hello PINs**; it only accepts traditional NT hashes (local passwords or domain passwords). Users entering their normal daily Windows PIN are rejected with `"Windows did not accept that password"`, leaving them trapped in locked sessions.
- **Remediation**:
  Specify `CREDUIWIN_PASSWORD_ONLY` in `CREDUI_INFOW` to clarify that an account password is required, or authenticate via `LsaLogonUser` with the appropriate PIN-capable authentication package.

### 2.4 Native Messaging Host & IPC Security
- **Files**: [`crates/curfew-win/src/extension.rs`](crates/curfew-win/src/extension.rs), [`crates/curfew-win/src/tick.rs`](crates/curfew-win/src/tick.rs#L560-L575)
- **Findings**:
  1. **Budget Accrual Missing on Web Checks**: In `Request::Check { browser, url }`, the service evaluates `decide()`, but never invokes `charged_keys()` or records active web usage against budget limits.
  2. **Named Pipe Access Rights**: The named pipe `\\.\pipe\curfew` is created via `interprocess::local_socket`. On Windows, services must explicitly assign a Security Descriptor (SDDL) allowing standard users (`BUILTIN\Users`) read/write access while restricting administrative configuration changes.

---

## 3. Android Implementation Review

### 3.1 Biometric Authentication Bypass (Critical Invariant Violation)
- **File**: [`android/app/src/main/java/dev/curfew/app/ui/Auth.kt`](android/app/src/main/java/dev/curfew/app/ui/Auth.kt#L24-L35)
- **Severity**: **P0 Critical Invariant Violation (Direct Breach of Design Invariant 7 / Principle 3)**
- **Code**:
  ```kotlin
  // Auth.kt line 24:
  private const val ALLOWED =
      BiometricManager.Authenticators.BIOMETRIC_STRONG or
      BiometricManager.Authenticators.DEVICE_CREDENTIAL
  ```
- **Architectural Violation**:
  In `README.md`, `ARCHITECTURE.md`, and `curfew-core/src/lock.rs`, the documentation explicitly states:
  > *"Curfew never accepts a fingerprint to end a session. A biometric is too easy to touch by reflex; a lock that asks for device credential requires the real PIN or password to be typed."*
- **Consequence**:
  By including `BIOMETRIC_STRONG`, any locked session can be unlocked instantly with a casual fingerprint touch or Face Unlock glance. The intentional psychological friction ladder is completely nullified.
- **Remediation**:
  Change `ALLOWED` to `BiometricManager.Authenticators.DEVICE_CREDENTIAL` only:
  ```kotlin
  private const val ALLOWED = BiometricManager.Authenticators.DEVICE_CREDENTIAL
  ```

### 3.2 Accessibility Service & The "Frozen Budget" Defect (fixed; scope was wider than stated)

> **Correction.** The freeze was not specific to the accessibility path. The poller resamples the same package every 30 s, but `Enforcer.onObservation` only flushed and re-decided on a *change* of target, so the budget was equally frozen with the fallback detector. The quoted snippets below are paraphrases, not the code as it stood (there was no `flushUsage` and no early return on `isEnabled`). Fixed by `Enforcer.onTick`, called every 5 s from `EnforcementService` regardless of detector, with `ACTION_SCREEN_OFF` stopping the meter so pocket time is not charged.

- **Files**: [`android/app/src/main/java/dev/curfew/app/EnforcementService.kt`](android/app/src/main/java/dev/curfew/app/EnforcementService.kt#L130-L140), [`android/app/src/main/java/dev/curfew/app/CurfewAccessibilityService.kt`](android/app/src/main/java/dev/curfew/app/CurfewAccessibilityService.kt#L35-L60), [`android/app/src/main/java/dev/curfew/app/Enforcer.kt`](android/app/src/main/java/dev/curfew/app/Enforcer.kt#L90-L125)
- **Severity**: **P0 Critical Architectural Bug**
- **Analysis**:
  In `EnforcementService.kt`:
  ```kotlin
  // If Accessibility Service is enabled, EnforcementService disables its periodic poller:
  if (CurfewAccessibilityService.isEnabled(this)) {
      return // Skips periodic timer to conserve battery
  }
  ```
  In `CurfewAccessibilityService.kt`:
  Events are **only received on window state transitions**:
  ```kotlin
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
      if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
          val pkg = event.packageName?.toString() ?: return
          Enforcer.observe(this, Observation.App(pkg))
      }
  }
  ```
  In `Enforcer.kt`:
  Usage time is **only flushed when switching apps** (`current != target`):
  ```kotlin
  fun observe(context: Context, obs: Observation) {
      if (obs != currentObservation) {
          flushUsage(context) // only flushes on change!
          currentObservation = obs
      }
  }
  ```
- **Consequence**:
  When a user opens an app governed by a time budget (e.g. 15 minutes of YouTube or Instagram) and remains active inside it:
  1. No window state change events fire while using the app.
  2. The periodic background poller is disabled because Accessibility Service is on.
  3. `flushUsage` is never called.
  4. The policy engine is never re-evaluated.
  **Result**: The user can stay inside the app for 10 consecutive hours without ever being blocked. The budget only gets charged hours later when they finally exit the app!
- **Remediation**:
  Even when Accessibility Service provides instant app switch detection, `EnforcementService` must maintain an active 1-second coroutine timer while a budgeted app is in the foreground to tick usage and enforce limits in real time.

### 3.3 Missing URL/Domain Enforcement Layer (Unimplemented Gap A2)
- **Files**: [`android/app/src/main/java/dev/curfew/app/CurfewAccessibilityService.kt`](android/app/src/main/java/dev/curfew/app/CurfewAccessibilityService.kt)
- **Severity**: **P1 Feature Gap**
- **Analysis**:
  In `CROSS_PLATFORM.md`, Section `Gaps: A2 (Android domain blocking)` promises URL and domain blocking on Android.
  However, `CurfewAccessibilityService.kt` contains:
  ```kotlin
  val pkg = event.packageName?.toString() ?: return
  Enforcer.observe(this, Observation.App(pkg))
  ```
  It contains **no code** to inspect browser address bars (`EditText` node with view IDs for Chrome, Firefox, Brave, etc.), and no Android `VpnService` is implemented.
- **Consequence**:
  Any rule targeting domains (`Target::Domain`), URLs (`Target::Url`), or keywords (`Target::Keyword`) is **100% inert on Android**. Users can freely access blocked websites in Chrome or Firefox on their phone despite having active website blocks.
- **Remediation**:
  Implement URL bar node inspection in `CurfewAccessibilityService` for major browsers (inspecting `AccessibilityNodeInfo` for URL resource IDs like `com.android.chrome:id/url_bar`) or implement a local loopback `VpnService` intercepting port 53 DNS.

### 3.4 Direct Boot & Encrypted Key Storage ~~Crash~~ (claim withdrawn)

> **Correction.** There was no crash. Because no component was `directBootAware`, Android never delivered `LOCKED_BOOT_COMPLETED` to the receiver, so the CE-storage access described below never happened before first unlock; `BOOT_COMPLETED` arrived after the unlock and the service started normally. The remediation proposed below would have *introduced* the crash by moving the receiver into direct boot while the database and its key stayed in CE storage. What was changed instead: the undeliverable action is removed from the manifest and the receiver explains why enforcement begins at first unlock. Original text kept for the record:

- **Files**: [`android/app/src/main/AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml), [`android/app/src/main/java/dev/curfew/app/BootReceiver.kt`](android/app/src/main/java/dev/curfew/app/BootReceiver.kt), [`android/app/src/main/java/dev/curfew/app/data/DatabaseKey.kt`](android/app/src/main/java/dev/curfew/app/data/DatabaseKey.kt)
- **Severity**: **P1 Stability & Security Defect**
- **Analysis**:
  In `BootReceiver.kt`, the receiver registers for `Intent.ACTION_LOCKED_BOOT_COMPLETED`.
  However:
  1. Neither `BootReceiver`, `EnforcementService`, nor `CurfewApplication` specifies `android:directBootAware="true"` in `AndroidManifest.xml`. Therefore, `LOCKED_BOOT_COMPLETED` is never delivered before first user unlock.
  2. In `DatabaseKey.kt`:
     ```kotlin
     val file = File(context.filesDir, "db.key")
     ```
     `context.filesDir` is located in Credential Encrypted (CE) storage. In Direct Boot mode (before initial PIN entry), CE storage is locked and cryptographically unreadable. Attempting to access it throws an `IllegalStateException` or returns empty, crashing the app on startup.
- **Remediation**:
  Mark components `android:directBootAware="true"` and migrate `db.key` to Device Protected (DE) storage via `context.createDeviceProtectedStorageContext().filesDir`.

### 3.5 Typing Challenge Clipboard Bypass
- **File**: [`android/app/src/main/java/dev/curfew/app/ui/ChallengeDialog.kt`](android/app/src/main/java/dev/curfew/app/ui/ChallengeDialog.kt#L60-L95)
- **Severity**: **P2 Bypass Defect**
- **Analysis**:
  The typing challenge requires the user to type a 50-word philosophical passage to cancel or alter a session.
  However, the Compose `OutlinedTextField` does not intercept clipboard paste events.
- **Consequence**:
  Users copy the target passage from the prompt text and paste it into the input field in 0.5 seconds, defeating the 2-minute deliberate friction requirement.
- **Remediation**:
  Disable paste in `TextField` or calculate keystroke velocity / timing intervals between characters.

### 3.6 Device Admin, Doze Mode & OEM Process Lifecycles
- **Files**: [`android/app/src/main/java/dev/curfew/app/CurfewDeviceAdmin.kt`](android/app/src/main/java/dev/curfew/app/CurfewDeviceAdmin.kt), [`android/app/src/main/java/dev/curfew/app/UninstallGuard.kt`](android/app/src/main/java/dev/curfew/app/UninstallGuard.kt)
- **Evaluation**:
  1. Device Admin integration is properly implemented to prevent silent uninstallation.
  2. `UninstallGuard` monitors `android.settings.MANAGE_UNKNOWN_APP_SOURCES` and `DEVICE_ADMIN_SETTINGS` to block tampering.
  3. **Vulnerability**: Aggressive OEM battery managers (e.g. Xiaomi MIUI/HyperOS, Samsung OneUI, Huawei) kill foreground services during Doze mode unless the user explicitly exempts the app from battery optimization (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Curfew should audit this permission during onboarding.

---

## 4. Threat Model, Anti-Tamper & Security Analysis

| Threat Vector | Attack Mechanism | Windows Mitigation | Android Mitigation | Residual Risk |
| :--- | :--- | :--- | :--- | :--- |
| **Clock Rollback** | Setting system time back to evade session expiry. | Monotonic boot counter + uptime witness in `state.rs`. | `SystemClock.elapsedRealtime()` witness. | **Low**: Skew detected; session extended. |
| **Process Killing** | Killing enforcer process via Task Manager / `kill -9`. | Watchdog process restarts `curfew-svc.exe`; service runs as SYSTEM. | Device Admin + sticky foreground service. | **Low**: Auto-restarts within 1s. |
| **Hosts File Tampering** | Editing `hosts` file directly as Administrator. | Service rewrites hosts file every tick (1s). | N/A (hosts inaccessible without root). | **Low**: Overwritten within 1s. |
| **Bypass via Biometrics** | Using fingerprint to bypass lock. | N/A (Windows uses `CredUI` password). | ~~**Vulnerable**: `Auth.kt` allows `BIOMETRIC_STRONG`.~~ Device credential only. | **Low**: fixed. |
| **Direct Boot Bypass** | Rebooting phone and using apps before unlock. | N/A | ~~**Vulnerable**: Direct boot crashes on CE storage.~~ Not a bypass: before first unlock only the lock screen runs; enforcement starts with the unlock. | **None**. |
| **Pipe Impersonation** | Non-admin user sending spoofed IPC messages. | Service verifies permissions; only allows claimable conditions. | Local binder service / internal broadcasts. | **Low**: Malicious inputs safely refused. |

---

## 5. Prioritized Remediation Plan

### Priority P0: Critical Fixes (Core Invariants & Enforcement Breaches)

#### 1. Fix Android Biometric Authentication Bypass
- **Target**: [`android/app/src/main/java/dev/curfew/app/ui/Auth.kt`](android/app/src/main/java/dev/curfew/app/ui/Auth.kt)
- **Patch**:
```kotlin
// In Auth.kt line 24:
- private const val ALLOWED =
-     BiometricManager.Authenticators.BIOMETRIC_STRONG or
-     BiometricManager.Authenticators.DEVICE_CREDENTIAL
+ private const val ALLOWED = BiometricManager.Authenticators.DEVICE_CREDENTIAL
```

#### 2. Fix Android In-App Budget Freeze
- **Target**: [`android/app/src/main/java/dev/curfew/app/EnforcementService.kt`](android/app/src/main/java/dev/curfew/app/EnforcementService.kt)
- **Patch**: Maintain a continuous 1-second interval ticker even when Accessibility Service is enabled, flushing elapsed seconds to the core engine while an app remains in the foreground:
```kotlin
// In EnforcementService.kt:
coroutineScope.launch {
    while (isActive) {
        delay(1000L)
        Enforcer.tick(this@EnforcementService, 1)
    }
}
```

#### 3. Resolve Distributed Sync Deadlock
- **Target**: [`crates/curfew-sync/src/node.rs`](crates/curfew-sync/src/node.rs)
- **Patch**: Extract entries and peer endpoints from `shared.log` and `shared.peers` under a short-lived lock, drop the mutex guards, and perform TCP network I/O outside the lock.

#### 4. Fix Windows Session 0 Foreground Tracking & Budget Accrual
- **Target**: [`crates/curfew-win/src/ipc.rs`](crates/curfew-win/src/ipc.rs) and [`crates/curfew-tray/src/main.rs`](crates/curfew-tray/src/main.rs)
- **Patch**:
  1. Add `Request::Foreground { exe: String, title: String }` to `Request` enum in `ipc.rs`.
  2. Have `curfew-tray` (running in interactive user session) poll `GetForegroundWindow()` and forward the active window to the service.
  3. In `Enforcer::tick`, use the reported foreground process if `processes.foreground()` returns `None`.

---

### Priority P1: High Importance (Platform Compatibility & Gaps)

#### 5. Fix Windows DNS Proxy Parsing on Non-English Locales
- **Target**: [`crates/curfew-win/src/dns.rs`](crates/curfew-win/src/dns.rs)
- **Patch**: Replace localized string parsing of `netsh interface ipv4 show dnsservers` with `GetAdaptersAddresses` from Windows IP Helper API (`windows-sys`).

#### 6. Support Windows Hello PIN in Unlock Flow
- **Target**: [`crates/curfew-win/src/credential.rs`](crates/curfew-win/src/credential.rs)
- **Patch**: Clarify in the UI that the user's primary Windows account password is required, or authenticate via LSA / Credential Provider architecture supporting Hello PIN.

#### 7. Implement Android Domain / URL Blocking
- **Target**: [`android/app/src/main/java/dev/curfew/app/CurfewAccessibilityService.kt`](android/app/src/main/java/dev/curfew/app/CurfewAccessibilityService.kt)
- **Patch**: Implement `AccessibilityNodeInfo` inspection for browser address bars (Chrome, Firefox, Brave, Samsung Internet) and emit `Observation.Web { url }` to the engine.

#### 8. Fix Direct Boot Storage Access on Android
- **Target**: [`android/app/src/main/AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml) and [`DatabaseKey.kt`](android/app/src/main/java/dev/curfew/app/data/DatabaseKey.kt)
- **Patch**: Add `android:directBootAware="true"` and use `createDeviceProtectedStorageContext()` for `db.key`.

---

### Priority P2: Medium / Polish

#### 9. Prevent Typing Challenge Clipboard Paste
- **Target**: [`android/app/src/main/java/dev/curfew/app/ui/ChallengeDialog.kt`](android/app/src/main/java/dev/curfew/app/ui/ChallengeDialog.kt)
- **Patch**: Block paste shortcuts / context menus and enforce minimum typing duration.

#### 10. Accrue Usage for Browser Extension Checks on Windows
- **Target**: [`crates/curfew-win/src/tick.rs`](crates/curfew-win/src/tick.rs)
- **Patch**: Call `accrue()` with domain/URL observation in `Request::Check`.
