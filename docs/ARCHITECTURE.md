# Architecture

**Curfew** — Calendar-Unified Rules For Every Window. License: AGPL-3.0.
No accounts, no servers, no telemetry.

## 0. Design invariants

1. **No server.** Devices talk to each other directly. Any relay is optional, self-hostable, and
   only ever sees ciphertext.
2. **A lock is a promise.** Once a session is locked, no code path — UI, sync, uninstall, clock
   change, peer device — may shorten it except the unlock conditions the user chose.
3. **Degrade, never die.** Every enforcement mechanism has a fallback (accessibility revoked ->
   usage-stats polling -> VPN filter). Losing one permission weakens blocking; it
   never disables the app.
4. **The config is a file.** Everything the UI can do is expressible in a versioned, exportable
   document. That is what makes "full customization" real rather than a settings screen.
5. **Local-first.** All data lives on device; sync is an optimization, not a dependency.

## 1. Component map

```
                       +--------------------------------------+
                       |  core (shared, Rust)                 |
                       |  - policy model + rule engine        |
                       |  - scheduler (sessions/timers)       |
                       |  - calendar rule evaluation          |
                       |  - op-log store + CRDT merge         |
                       |  - crypto: identity, pairing, E2EE   |
                       |  - sync: LAN / folder / BT beam      |
                       +------------------+-------------------+
                        UniFFI bindings   |   direct link
              +---------------------------+------------------------+
              |                                                    |
   +----------v-----------+                         +--------------v---------------+
   |  Android app (Kotlin) |                         |  Windows daemon (Rust svc)   |
   |  - Compose UI         |                         |  - process/window watcher    |
   |  - AccessibilitySvc   |                         |  - hosts/DNS/WFP blocking    |
   |  - UsageStats poller  |                         |  - lock screen / Frozen mode |
   |  - local VpnService   |                         |  - tamper watchdog           |
   |  - NotificationListener|                        +--------------+---------------+
   |  - DeviceAdmin (opt)  |                                        | IPC (named pipe)
   |  - CalendarContract   |                         +--------------v---------------+
   |  - WorkManager sync   |                         |  Desktop UI (tray + window)  |
   +-----------------------+                         +--------------+---------------+
                                                                    | native messaging
                                                     +--------------v---------------+
                                                     |  Browser extension (MV3)     |
                                                     |  - URL/path rules            |
                                                     +------------------------------+
```

## 2. Domain model

```
Profile        name, description, enabled, color, icon
  Rule[]       target + action + limits
    Target     AppPackage | WindowsExe | WindowTitleRegex | Domain | UrlRegex |
               Keyword | Path(file/folder) | NotificationSource | WholeDevice
    Action     Block | AllowOnly | Budget{seconds, refill} | LaunchLimit{n, window} |
               Delay{seconds} | MuteNotifications
    Scope      devices: [all | deviceIds], platforms: [android|windows|browser]
Trigger        Manual | Timer{duration} | Schedule{rrule, tz} |
               Calendar{source, matcher} | DeviceEvent{signin|wake|unlock_count} |
               Network{ssid} | Token{nfc|qr id}
Session        profileId, trigger, startedAt, endsAt, lock, originDeviceId, state
Lock           None | Confirm | DeviceCredential (OS screen lock, biometrics refused) |
               Challenge{typing|math} | Timer | RestartRequired | Token{id} |
               PeerRelease{deviceId} | EmergencyPasses{remaining, cooldown}
Event(op-log)  signed, ordered, encrypted: session.start/end, profile.edit, budget.consume,
               device.pair, calendar.snapshot, stat.rollup
```

Everything above serializes to a single TOML/JSON document (`curfew.toml`) that can be diffed,
version-controlled, and shared. The UI is a view over that document.

## 3. Rule engine

Pure function, no I/O, identical on both platforms:

```
decide(now, state, foreground(app|url|window), policy) -> Allow | Block(reason) | Delay(s) | Warn
```

- Inputs: active sessions, budgets consumed so far, calendar-derived intervals, device identity.
- Output drives the platform enforcer. Being pure makes it exhaustively testable and makes Android
  and Windows behave identically, which matters once sessions are mirrored.
- Budgets are stored as consumption events in the op-log, so "20 min of Reddit per day" is shared
  across devices instead of being 20 min *per device*.

## 4. Calendar integration

Sources:
- **Android**: `CalendarContract` via `READ_CALENDAR` — picks up whatever the phone already syncs
  (Google, Exchange, DAVx5). Zero extra credentials.
- **Windows**: ICS subscription URLs (Google/Outlook secret ICS links), CalDAV with locally stored
  credentials, and local `.ics` files. No server involvement.

Matching rules (any combination): calendar name, title regex or keyword (`#focus`, `[deep]`),
busy/free status, event category, attendee count, all-day flag, duration threshold.

Actions: activate profile for the event window, with lead-in/lead-out padding; optionally lock;
optionally different profiles per matcher (a "meeting" profile vs a "deep work" profile).

The evaluated calendar window is written to the op-log as a `calendar.snapshot` event, so the phone
enforces a rule derived from a PC-only calendar even if the phone cannot read that calendar. That is
the "calendar on PC drives lock on phone" case, done without a server.

## 5. Sync

**Identity**: each device generates an Ed25519 keypair on first run. Pairing shows a QR code
containing the public key + a one-time PAKE secret; scanning it on the other device establishes a
shared group key. A group is a set of paired devices; no account exists.

**State**: append-only, hash-chained op-log per device. Merge is CRDT-flavored:
- sessions/locks: additive; the *strictest* wins (a lock cannot be merged away)
- profiles/rules: last-writer-wins per field, with conflict surfaced in the UI
- budgets: grow-only counters (G-Counter) per device, summed for the global consumption
- stats: append-only, never merged destructively

**Transports** (pluggable, tried in order, all optional — see D3):
1. **LAN**: mDNS discovery + QUIC, direct, works fully offline.
2. **Shared folder**: encrypted log segments dropped into a Syncthing/Drive/Dropbox folder. The only
   cross-network path we ship, so it must be properly engineered: segment naming, compaction,
   concurrent writers, and honest latency indication in the UI.
3. **Bluetooth / manual QR beam**: no-network fallback for "push this session to my phone now".

Deferred: NAT-hole-punched P2P (iroh). Consequence accepted: with no shared folder configured, a
phone on mobile data does not receive a PC-started session until it rejoins the LAN.

**Anti-escape property**: a locked session mirrored from a peer stays enforced locally even if the
peer disappears; releasing requires the lock's own conditions. Un-pairing during an active locked
session is itself gated by the lock.

## 6. Enforcement layers

**Android** (in priority order, all optional but at least one required)
| Layer | Permission | Blocks | Survives |
|---|---|---|---|
| AccessibilityService + overlay | Accessibility | apps, in-app screens, browser URLs | until Advanced Protection revokes it |
| UsageStats poller | `PACKAGE_USAGE_STATS` | apps (1–2 s lag) | always |
| Local VpnService DNS filter | VPN consent | domains, per-app net | always |
| NotificationListener | notification access | notifications | always |

Device-owner / `dpm set-device-owner` is explicitly **out of scope** (D4): no factory-reset-class
setup, nothing that can make a device unrecoverable.

**Windows**
| Layer | Needs | Blocks |
|---|---|---|
| Service + process watcher | admin install | exes, window titles, files/folders |
| Hosts file | admin | domains (coarse) |
| WFP filters (phase 2) | admin + driver-free WFP API | domains, per-app network, DoH |
| Browser extension | user install | URL and path rules, rechecked on navigation, SPA history changes, and tab activation |

> **Corrected (P2-12).** This row used to read *"URL paths, in-page elements, search keywords"* — three
> capabilities, of which **one and a half exist**. `extension/manifest.json` declares `nativeMessaging`,
> `webNavigation`, `tabs` and `alarms`: there is no `content_scripts`, no `scripting` and no
> `declarativeNetRequest`, so **in-page element blocking is not implementable with the code present**.
> Search keywords are reachable only because they appear in the URL, which is what `tabs` gives.
>
> The extension blocks by redirecting the tab after `onBeforeNavigate`, i.e. once the load has begun —
> the same limitation `GAPS.md` uses to dismiss a competitor's extension. The honest claim is the one
> now in the row. Implement DNR plus a content script if the original three are wanted.
| Lock screen / Frozen mode | none | whole device |
| Watchdog pair + ACLs | admin | tamper resistance |

## 7. Storage

SQLite everywhere (SQLDelight on Android where convenient, `rusqlite` in core), holding: op-log,
materialized policy view, usage stats, budgets. Config import/export as TOML. Secrets in Android
Keystore / Windows DPAPI.

## 8. Threat model (the user is the adversary, willingly)

In scope: impulsive bypasses — kill the process, uninstall, edit hosts, change the clock, unpair a
device, use another browser, boot into safe mode (detect + re-lock on next boot).
Out of scope: a determined attacker with admin rights and time. Documented honestly rather than
pretended away; strictness is a ladder the user picks, and "hardcore mode" (device owner + Windows
service ACLs) is the top rung.

## 9. Stack (decided — see docs/DECISIONS.md D1)

- **Core**: Rust — one implementation of policy, sync, crypto, calendar parsing; UniFFI-generated
  Kotlin bindings for Android; linked directly by the Windows service; also gives a CLI for free.
- **Android**: Kotlin + Compose. Enforcement must be native regardless of the core language.
- **Windows**: Rust service + Tauri 2 desktop UI (small binary, native tray, shares the core).
- **Browser**: TypeScript MV3 extension (Chrome + Firefox), native-messaging to the daemon.

Rejected: Kotlin Multiplatform (JVM daemon on Windows is a weaker tamper and memory story) and
Flutter (core logic in Dart, weakest service story).

## 10. Reliability and degradation

Enforcement is a stack of layers, each of which can be revoked, killed or unsupported. The app must
always know which layers are live and say so.

- **Health model**: every layer reports a heartbeat. A `ProtectionHealth` view aggregates them into
  live / degraded / down, with the specific missing permission named.
- **Honest downtime**: if enforcement was down (reboot, force-stop, revoked permission, OEM killer),
  the next launch reports the exact window it was down. No silent failure.
- **Persistence**: locked sessions live in storage, not memory. `BOOT_COMPLETED` and
  `LOCKED_BOOT_COMPLETED` on Android, service auto-start on Windows, both re-entering the lock
  rather than resetting it.
- **Adaptive cost**: pollers run at 1s while a session is active, 15s idle, and stop entirely when
  no rule can fire. The VPN filter runs only while a session with domain rules is active.

## 11. Safety and recovery

The user is the adversary *by their own consent*, which puts a hard limit on what we are allowed to
do to them.

- **One gate, and it is the OS one** (D7): credential locks delegate to the device screen lock —
  `BiometricPrompt` restricted to `DEVICE_CREDENTIAL` on Android, `LogonUser` on Windows. Curfew
  stores no password of its own, so there is no secret to leak and no reset flow to abuse.
  Biometrics are never accepted for a release. Device unlock itself is untouched: no third-party
  app can suppress keyguard biometrics without device-owner provisioning (D9), so the honest claim
  is only about our own gate. Once locked, Curfew adds no second lock on top of that one.
- **Any system-wide change dies with the lock, and with the process.** We set nothing outside
  Curfew that we cannot guarantee to unset: Windows WFP filters use a dynamic session so the OS
  removes them if we crash (D11), and Android keyguard policy is not touched at all (D9).
- **Last-resort exit**: every lock, at every strictness level, can be released by starting a
  **24-hour delayed release**. It cannot be shortened, and it is visible from the moment the lock
  starts. This is what separates a commitment device from a trap. Tools without one produce users
  reinstalling their operating system.
- **Never unrecoverable**: no device-owner enrollment, no kiosk lock-task, no boot-loop risk, no
  encryption of user data we could fail to decrypt.
- **Frozen mode** always warns with a cancellable countdown, and a remote (synced) session can never
  freeze a machine without local confirmation.
- **Not a surveillance tool**: no parent/admin role, no remote monitoring of another person, no
  covert operation. Usage data never leaves the device group. This is a design constraint, not an
  unimplemented feature.
- **Sensitive data**: the usage timeline is encrypted at rest, excluded from cloud backup by
  default, and exported only on explicit action.

## 12. Distribution

- **Android**: GitHub Releases (APK) and F-Droid as reference channels; Play Store only if the
  AccessibilityService declaration survives review. The sideloaded build is the reference build.
- **Windows**: GitHub Releases with checksums, plus winget and scoop manifests. Binaries are
  unsigned (no recurring cost, D6), so SmartScreen friction and antivirus heuristics are expected
  and documented; vendor allowlisting is requested after first release.
- **Builds**: x86_64 and aarch64 for Windows; reproducible-build instructions published so a third
  party can verify a release matches the source.

See [GAPS.md](GAPS.md) for the open problems behind each of these sections.
