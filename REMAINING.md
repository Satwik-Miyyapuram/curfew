# Curfew: Remaining Work & Future Roadmap

This document tracks remaining tasks, architectural improvements, and future milestones following the stabilization of multi-device peer-to-peer sync, tamper-resistant enforcement, and the unified Windows/Android release builds.

---

## 1. Device Pairing & Sync Protocol

- [ ] **BLE / mDNS Seamless Discovery (Zero-Input Pairing)**
  - *Current state*: Devices pair via compact cryptographic invitations (`CRFW:...`) and ISO-compliant QR codes with local HTTP direct-socket fallback.
  - *Next step*: Add Bluetooth Low Energy (BLE) advertisement and mDNS (`_curfew._tcp`) beaconing so nearby devices can detect and initiate pairing with a single button tap, even on complex corporate or guest Wi-Fi networks where peer-to-peer unicast packets are isolated.
- [ ] **Multi-Peer Gossip & Conflict Resolution (3+ Devices)**
  - *Current state*: Pairwise synchronization between phones and PCs over local Wi-Fi and shared folder transports.
  - *Next step*: Implement CRDT gossip propagation so that if Device A pairs with Device B, and Device B pairs with Device C, state reconciles deterministically across the mesh without requiring direct A $\leftrightarrow$ C connectivity.
- [ ] **Encrypted Relay / NAT Traversal Fallback**
  - *Current state*: Synchronization is strictly local (same Wi-Fi or shared cloud folder).
  - *Next step*: Optional, zero-knowledge end-to-end encrypted WebRTC / DERP relay for real-time remote sync across cellular and disjoint networks without compromising Curfew's zero-server, privacy-first architecture.

---

## 2. Windows Platform & Packaging

- [ ] **Production Code Signing**
  - *Current state*: Binaries and MSI packages are built unsigned in development (`curfew-arm64.msi`).
  - *Next step*: Integrate Authenticode hardware token or Azure Trusted Signing into GitHub Actions CI to eliminate Windows Defender SmartScreen warnings on first install.
- [ ] **Native Windows Notifications & Action Center**
  - *Current state*: Status is surfaced through the WebView2 UI, the system tray icon, and toast dialogs.
  - *Next step*: Integrate Windows WinRT Toast Notifications API to send native 5-minute schedule warnings and downtime recovery alerts directly to Windows Action Center.
- [ ] **Windows x64 / x86_64 MSI Installer Target**
  - *Current state*: MSI package builder supports ARM64 (`curfew-arm64.msi`) for Snapdragon / Surface Pro devices.
  - *Next step*: Add CI matrix build for standard `x86_64-pc-windows-msvc` target to produce `curfew-x64.msi`.

---

## 3. Android Application & Enforcement

- [ ] **App Distribution Pipelines (F-Droid & Google Play)**
  - *Current state*: Unsigned and debug APK builds generated via Gradle with reproducible JNA / UniFFI Rust core linkages.
  - *Next step*: Define release flavors:
    - *F-Droid / GitHub Releases*: Full accessibility service and device admin enforcement with in-app updater.
    - *Play Store*: Standard policies adhering to Google Play Accessibility policy disclosures.
- [ ] **OEM Background Battery Whitelisting**
  - *Current state*: Enforcement runs via foreground service with persistent notification and restart alarms.
  - *Next step*: Add deep-links and guides for aggressive OEM battery managers (Samsung "Never sleeping apps", Xiaomi Autostart, Huawei App Launch) to prevent task killers from suspending the sync heartbeat.
- [ ] **Per-App Usage Graphs & Daily Breakdown**
  - *Current state*: Screen time totals, daily rollups, and audit trails are tracked in the encrypted SQLCipher database.
  - *Next step*: Render detailed per-app visual charts and hour-by-hour breakdown on the Android "Where time went" (`UsageScreen`) view.

---

## 4. Browser Extensions

- [ ] **Chrome Web Store & Firefox AMO Submission**
  - *Current state*: Manifest V3 extension source exists under `crates/curfew-win` and extensions directory with native host messaging.
  - *Next step*: Submit extension packages to the Chrome Web Store and Mozilla Add-ons directory.
- [ ] **Automated Native Messaging Host Registration**
  - *Current state*: Windows service and extension communicate through the native hosts file and IPC named pipe.
  - *Next step*: Ensure the Windows MSI installer automatically registers the Native Messaging registry key under `HKLM\SOFTWARE\Google\Chrome\NativeMessagingHosts` and Edge equivalents.

---

## 5. CI / CD & Automated Verification

- [ ] **End-to-End Emulated Sync Tests in CI**
  - *Current state*: 147 Rust core tests, window harness tests, and Android unit tests run in GitHub Actions.
  - *Next step*: Add a matrix CI workflow that launches an Android headless emulator alongside a virtualized Windows container to test live TCP sync and beacon discovery across the network bridge.
