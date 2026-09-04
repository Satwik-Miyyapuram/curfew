# Competitive teardown + platform constraints

Date: 2026-09-04. Sources listed at bottom.

## 1. What the incumbents actually do

### Freedom (freedom.to) — Win/Mac/iOS/Android/Chromebook, subscription
- Blocklists: presets + custom; lists of websites *and* apps.
- **Allow-only sessions** (desktop): whitelist a handful of sites, everything else blocked.
- Sessions: start-now, scheduled-for-later, recurring schedules (e.g. Mon–Fri 09:00–17:00).
- **Locked Mode**: session cannot be ended early, settings cannot be edited mid-session.
- **Cross-device sync**: start a session on desktop, phone is blocked too. This is their headline
  moat, implemented through their cloud account. This is the exact thing we replicate *without a
  server*.
- Weak spots we can beat: subscription, cloud-account dependency, no calendar integration, limited
  rule expressiveness, closed source.

### Cold Turkey Blocker (getcoldturkey.com) — Win/Mac, one-time paid
- Blocks: websites, applications, **files, folders, Microsoft Store apps, and window titles** (Windows).
- **Allowances**: a time budget for blocked items (e.g. 20 min of Reddit) on a rolling window, or
  refilled per block / day / week / month at a custom refill time.
- Scheduled blocks: time-of-day + weekday, or triggered on sign-in / wake-from-sleep.
- **Locked blocks** with unlock conditions: timer, schedule, password, **typing challenge**,
  restart-required. Uninstaller is blocked while a locked block is active.
- **Frozen Turkey**: locks / logs off / shuts down the whole machine for a period; re-login re-locks.
- Enforcement: background Windows service (`KCTRP`) + hosts-file rewriting + browser extension.
- Weak spots: Windows/Mac only, no mobile, no sync, no calendar.

### StayFocusd (Chrome extension)
- Per-site **daily time budget**; blocks whole sites, subdomains, paths, single pages, and even
  in-page content types (videos, images, forms).
- **Nuclear Option**: block for N hours on chosen days, cannot be cancelled.
- **Require Challenge**: retype a paragraph exactly — no backspace, no paste, mistakes restart it —
  gating *settings changes*, not just unblocking.
- Active days/hours config, usage stats.

### StayFocused (Android)
- Per-app usage limits + launch-count limits, app blocking, keyword blocking inside browsers,
  strict mode that makes settings changes hard.

### Others worth stealing from
| App | Idea worth taking |
|---|---|
| SelfControl (mac, OSS) | Irreversible timer; no unlock path at all, even by uninstall |
| LeechBlock (browser, OSS) | Per-site time budgets, lockdown, "delay page" friction, password |
| one sec / ScreenZen | **Friction instead of blocking**: forced N-second delay before an app opens |
| Forest / Opal | Limited-quota emergency passes; gamified streaks |
| Foqos (OSS Android/iOS) | **Physical unlock**: NFC tag / QR code must be scanned to start or end a block |
| Curbox (OSS Android) | Home-screen widgets for stats + quick controls, dynamic app selection |
| Digital Wellbeing | Focus mode, per-app timers, bedtime schedules |
| focus-time-app (OSS) | **Calendar-driven** focus: CalDAV/Outlook events trigger OS focus mode |
| Freedom | Cross-device session mirroring |

Nobody in this list does **all** of: cross-platform + calendar-driven + P2P/no-account + open
source. That is the product gap.

## 2. Feature surface to cover (union of the above, plus our additions)

**Targets** — Android apps (package), Windows processes (exe), window titles, websites (domain /
subdomain / path / URL regex), in-page & URL patterns (YouTube Shorts, Reels), search keywords,
files/folders (Windows), notification sources, whole-device (Frozen Turkey equivalent).

**Rule types** — hard block; allow-only (inverse list); time allowance/budget with refill policy;
launch-count limit; friction delay; usage-limit-then-block; notification muting; side effects
(system DND, grayscale).

**Triggers** — manual, timer/pomodoro, recurring schedule, **calendar events** (our
differentiator), device events (sign-in, wake, unlock count, charging), Wi-Fi SSID / location,
NFC or QR scan.

**Strictness ladder** — off → confirm dialog → password → typing/math challenge → timer lock (no
exit) → restart-required → NFC/QR token in another room → **cross-device lock** (only the *other*
paired device can release it; novel, only possible because we sync) → limited emergency passes.

**Insight/stats** — local-only usage timeline, per-target breakdown, streaks, block-attempt counts,
CSV/JSON export, zero telemetry.

**Customization** — profiles/presets, import/export config files, rule expressions, CLI, theming,
webhooks or scripts on block start/end.

## 3. Platform enforcement constraints (the hard part)

### Android
- **AccessibilityService + overlay** is the standard mechanism (detect foreground app, draw block
  screen). Risks: Play Console requires a declared justification for non-accessibility use, and
  **Android 17 Advanced Protection Mode auto-revokes AccessibilityService from apps not classified
  as accessibility tools**; Android 16 restricts *sideloaded* apps from being granted Accessibility
  and Device Admin through the normal flow. Conclusion: it cannot be our only mechanism.
- **UsageStatsManager polling** (`PACKAGE_USAGE_STATS`): laggier, cannot pre-empt a launch as
  cleanly, but survives accessibility revocation — and it is the stats source anyway.
- **VpnService local DNS filter** (no root, no traffic leaves the device — how Blokada /
  personalDNSfilter / RethinkDNS work): blocks domains system-wide and can starve a chosen app of
  network. Strong and hard to bypass; per-app blocking needs the socket-owner lookup path.
- **DevicePolicyManager as device owner** (set once via `adb shell dpm set-device-owner` on an
  unprovisioned device): `setPackagesSuspended`, uninstall blocking, lock-task. Nuclear-grade tamper
  resistance for power users; opt-in "hardcore mode", never required.
- **NotificationListenerService** to mute notifications from blocked apps.
- Foreground-service rules: a `dataSync` FGS has a 6h/24h budget on recent Android, so sync must be
  WorkManager/JobScheduler-based; the blocker itself runs tied to the accessibility service or a
  `specialUse` FGS.

### Windows
- **Process/window watching**: enumerate processes + UI Automation window titles, then suspend/kill
  or cover with a block window. A service running as SYSTEM gives cross-session reliability.
- **Website blocking**, three tiers: (a) hosts file rewrite — trivial, bypassable via DoH;
  (b) local DNS proxy / DNS interception; (c) **WFP (Windows Filtering Platform)** filters for real
  per-app and per-endpoint blocking including DoH endpoints. Start at (a) + browser extension,
  architect for (c).
- **Browser extension (MV3 `declarativeNetRequest`)** for URL-path-level and in-page rules that
  network blocking cannot express. Paired to the local service; its removal must be detectable.
- **Tamper resistance**: service + watchdog pair, service ACL'd against stop/delete, uninstaller
  refuses during a locked block, integrity-signed config store, clock-rollback detection.
- Elevation: installer needs admin; the UI at runtime does not.

### Cross-cutting
- **Clock trust**: every lock carries a monotonic anchor plus a signed wall-clock stamp; moving the
  clock backwards must never shorten a lock.
- **Sync must not become an escape hatch**: a locked session mirrored to a peer is releasable only
  by the rules of the lock, never by uninstalling the app on the other device.

## 4. Serverless sync options

| Option | Cross-network | Infra | Notes |
|---|---|---|---|
| LAN direct (mDNS + QUIC/TLS, QR pairing) | no (same LAN) | none | Always works at home/desk. Baseline. |
| iroh (QUIC + dial-by-pubkey + NAT hole-punch, v1.0 June 2026) | yes | public relays as fallback only, self-hostable | Direct P2P when possible, relay only for rendezvous. |
| Shared-folder transport (Syncthing / Drive / Dropbox folder holding an encrypted op-log) | yes | user's own | No transport code, latency in seconds-to-minutes, solid fallback. |
| Bluetooth / BLE | no | none | Phone↔PC with no Wi-Fi. Optional later. |

Data model: an **append-only, signed, encrypted op-log** replicated between paired devices, with
CRDT/LWW merge (Automerge gives history for free; a hand-rolled log is smaller). Pairing is a QR
code carrying a public key plus a shared secret; payloads are E2E encrypted, so a relay or a cloud
folder only ever sees ciphertext.

## Sources
- https://freedom.to/features , https://support.freedom.to/en/articles/1802927-locked-mode
- https://getcoldturkey.com/features/ , https://getcoldturkey.com/support/user-guide/
- https://chromewebstore.google.com/detail/stayfocusd-%E2%80%93-website-bloc/laankejkbhbdhmipfmgcngdelahlfoji
- https://developer.android.com/identity/providers/calendar-provider
- https://support.google.com/googleplay/android-developer/answer/10964491
- https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://learn.microsoft.com/en-us/windows/win32/fwp/windows-filtering-platform-start-page
- https://github.com/n0-computer/iroh , https://docs.iroh.computer/connecting/local-discovery
- https://github.com/curbox-app/curbox-android , https://github.com/nish261/foqos-android
- https://github.com/focus-time/focus-time-app
