# Roadmap

Each phase ends with something usable. No phase depends on a server at any point.

## Phase 0 — Foundations (repo is here now)
- [x] Git repo, AGPL-3.0, docs (research, architecture, roadmap)
- [ ] Workspace scaffolding for the chosen stack, CI (build + test on push), issue templates
- [ ] `nodis.toml` schema v0 + golden-file tests
- [ ] Core rule engine: `decide()` with block / allow-only / budget / delay, plus a property test
      suite. No I/O, no platform code.

## Phase 1 — Android MVP (usable alone)
- [ ] Foreground-app detection: AccessibilityService, with UsageStats poller as fallback
- [ ] Block overlay screen (reason, time remaining, allowed exits)
- [ ] Profiles + app picker, manual sessions, timer sessions
- [ ] Recurring schedules
- [ ] Local SQLite store, config import/export
- [ ] Strictness: none / confirm / password / timer-lock
- [ ] Usage stats screen

## Phase 2 — Windows MVP (usable alone)
- [ ] Service + watchdog, tray UI, installer (admin)
- [ ] Process + window-title blocking, block overlay
- [ ] Website blocking v1: hosts file + local DNS proxy
- [ ] Same profiles/sessions/schedules/strictness as Android, same `nodis.toml`
- [ ] Frozen-mode (lock the machine for a period)

## Phase 3 — Sync (the differentiator, part 1)
- [ ] Device identity, QR pairing, group key
- [ ] Signed encrypted op-log + merge rules (strictest-lock-wins, G-counter budgets)
- [ ] LAN transport (mDNS + QUIC)
- [ ] Session mirroring: start on PC, phone blocks; shared budgets across devices
- [ ] Conflict UI, unpair gated by active locks
- [ ] Shared-folder transport as fallback
- [ ] iroh transport for cross-network

## Phase 4 — Calendar (the differentiator, part 2)
- [ ] Android `CalendarContract` reader
- [ ] Windows ICS URL / CalDAV / local `.ics` reader
- [ ] Matcher rules (calendar, title regex, busy status, category, duration) -> profile + lock
- [ ] Lead-in/lead-out padding, per-matcher profiles
- [ ] `calendar.snapshot` events so one device's calendar drives another device's blocks
- [ ] Preview timeline: "here is what tomorrow will block"

## Phase 5 — Depth (feature parity + beyond)
- [ ] Allowances/budgets with refill policies, launch limits, friction delays
- [ ] Challenge locks (typing, math), restart-required, NFC/QR token, **peer-release lock**
- [ ] Emergency passes with quota + cooldown
- [ ] Allow-only mode
- [ ] Notification muting, keyword blocking
- [ ] Browser extension (Chrome + Firefox) for URL/path/in-page rules
- [ ] Android VpnService DNS filter; Windows WFP filtering
- [ ] Hardcore mode: device owner setup wizard, Windows service ACL hardening, clock-tamper detection

## Phase 6 — Polish + release
- [ ] Widgets, quick tiles, CLI, webhooks/scripts on session start-end
- [ ] Stats: streaks, trends, CSV/JSON export
- [ ] Themes, localization scaffolding
- [ ] F-Droid + GitHub releases; Play Store listing only if the accessibility declaration survives
      review (sideloaded build stays the reference build)
- [ ] Docs site, threat-model page, contributor guide

## Later / optional
- Linux and macOS desktop enforcers (core and sync are already portable)
- iOS (Screen Time API only; heavily restricted — separate design)
- Community blocklists, importable rule packs
