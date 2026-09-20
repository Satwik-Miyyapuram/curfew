# Curfew

*Calendar-Unified Rules For Every Window.*

Open-source, account-free distraction blocker for **Android + PC** that **syncs peer-to-peer with no
server**, and that can drive its blocks from **your calendar**.

Think Freedom's cross-device sessions + Cold Turkey's strictness + StayFocusd's rule granularity —
without a subscription, without a cloud account, and with your data never leaving your devices.

> Status: **working on both platforms.** Windows binaries and an unsigned Android APK are published
> from a tag; see [Install](#install). Budgets, launch limits and friction delays are enforced by
> the engine but are still edited in the config file rather than in a form.

## Why

| | Freedom | Cold Turkey | StayFocusd | Curfew |
|---|---|---|---|---|
| Android + PC | yes | PC only | browser only | yes |
| Cross-device session sync | yes (their cloud) | no | no | **yes, P2P, no account** \* |
| Calendar-driven blocking | no | no | no | **yes** |
| Allowances / budgets | limited | yes | yes | yes |
| Hard locks + challenges | yes | yes | yes | yes |
| Open source | no | no | no | **AGPL-3.0** |
| Cost | subscription | paid | free | free |

> **\* The sync itself is real and account-free; the *pairing* is Android-only today (F-18).** Once two
> devices are paired the PC syncs normally. But the PC cannot yet start a pairing — the window has no
> Devices page, so pairing a second PC, or a PC and a phone, has to begin on the phone. See step 4 under
> [Using it](#using-it).

## Install

Both downloads come from [Releases](../../releases), with a `.sha256` beside each one.

**Windows.** Unzip `curfew-x64.zip` (or `curfew-arm64.zip`) and read `INSTALL.txt`. `curfew.exe` is
both the service and the command line; `curfew-tray.exe` is the tray icon.

**Android.** `curfew-android-unsigned.apk`. It is unsigned on purpose — a signing key is a cost and
a secret this project does not hold — so Android will warn you, and you can instead build it
yourself with `cd android && ./gradlew assembleRelease`.

Nothing here phones home, so there is no account step and nothing to sign up for.

## Using it

The two devices are set up the same way, in the same order:

1. **Make a profile** — a named set of things to block. On the phone: *Schedule → Add a profile*.
   On the PC: `curfew add-profile "<config>" --id deep-work`.
2. **Say what it blocks.** On the phone: *Apps*, which ticks apps and, under *Sites and words*,
   takes a domain, an address, a keyword or a window title. On the PC:
   `curfew block "<config>" --profile deep-work --site reddit.com`.
3. **Say when it runs** — a weekly window, or a calendar rule that matches events by title.
   On the phone: *Schedule*. On the PC: `curfew add-window` / `curfew add-calendar`.
4. **Pair the devices**, if you have both — *Devices → Pair*, scanning a QR from the other one.
   Sessions, budgets and calendar events then travel between them over a shared folder, with no
   server in the middle.

   > **Pairing is Android-only today (F-18).** The *Devices → Pair* step above is a phone screen; the
   > Windows window has no pairing page, so **two PCs, or a PC and a phone, cannot yet be paired from
   > the PC.** This is a gap in the Windows build and not a limit of the design: the sync engine, the
   > invitation format and the six-digit comparison all exist and are tested, and the service already
   > runs a sync node once a peer exists. What is missing is the front door on Windows — a Devices page
   > in the window, and the three messages it would send. Steps 1–3 have `curfew` commands for that
   > reason and this step does not yet.
   >
   > Once paired — on a phone, or by an existing peer's config — the PC syncs normally, including
   > receiving blocks and spending a ration shared with the other device.

`curfew schedules "<config>"` and `curfew blocks "<config>"` print back everything that is set.

> **`<config>` is the path the service actually reads**, which on an installed PC is
> `%ProgramData%\Curfew\curfew.toml` — **not** `curfew.toml` in whatever directory you happen to be
> in. This matters more than it sounds: every command above takes a path, and a path pointing at a
> file the service never opens edits a plan that is never enforced, silently. The Curfew window names
> the exact path on its **Plan** page and its **Is it working** page, so the reliable move is to copy
> it from there.
>
> The default config is created by `curfew install`. Before it exists there is nothing to edit, and
> `curfew run` writes one if you are running without the service.

## Docs

- [docs/RESEARCH.md](docs/RESEARCH.md) — teardown of Freedom, Cold Turkey, StayFocusd and friends;
  Android/Windows enforcement constraints; serverless sync options
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — design invariants, domain model, rule engine, sync
  protocol, enforcement layers, threat model
- [docs/ROADMAP.md](docs/ROADMAP.md) — phased build plan
- [docs/DECISIONS.md](docs/DECISIONS.md) — the ADR log, and what was rejected
- [docs/GAPS.md](docs/GAPS.md) — open problems, honestly listed

## Principles

1. No server. No account. No telemetry.
2. A lock is a promise — nothing shortens it except the conditions you chose.
3. The OS screen lock is the lock. Curfew never holds a password of its own, and never accepts a
   fingerprint to end a session, so getting out early always costs a real PIN entry. Unlocking your
   phone is unaffected — no app can change that without a factory reset, and we will not go there.
4. Every enforcement mechanism has a fallback; losing a permission weakens blocking, never kills it.
5. Everything the UI can do is expressible in an exportable config file.
6. There is always a way out: a 24-hour delayed release, on every lock, visible from the start.

## This is not parental-control software

Curfew is a tool for restricting **your own** devices, and it is built so that it cannot comfortably
be used for anything else. There is no parent role, no admin role, no remote monitoring of another
person's device, and no hidden or disguised mode. Usage data never leaves the group of devices you
paired yourself, and there is nowhere for it to be sent.

Two consequences follow, and both are deliberate:

- **Every lock is visible and every lock ends.** Each one shows what it is waiting for from the
  moment it starts, and each one offers a 24-hour delayed release. Somebody who installs this on
  another person's phone cannot make a block they cannot see or wait out.
- **Nothing is set that we could not lift.** Curfew never takes device-owner mode, and never asks
  for a factory reset to remove. Device admin is optional, user-revocable, and used only to make
  uninstalling deliberate rather than impossible.

If what you want is to watch or restrict somebody else, this is the wrong project, and no amount of
configuration will make it the right one.

## Security and privacy

The threat model is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the short version:

- **What it stops:** the impulse, and the moment of weakness ten minutes later. Blocking is enforced
  in the OS the app runs in, and a lock cannot be ended early by uninstalling, by editing the config,
  or by changing the clock.
- **What it does not stop:** somebody with root, an unlocked bootloader, an Administrator account
  they are willing to use, or a second device you never told Curfew about. That ceiling is a
  property of what Android and Windows let an app do, and pretending otherwise would be the
  dishonest part.
- **Where the data is:** on your devices. Usage statistics are stored in an encrypted database and
  excluded from cloud backup; there is no code in this repository that uploads them anywhere. The
  config file is exportable on demand, from *Schedule → Export*.

## Building

```
cargo test --workspace                 # the shared engine, the CLI, the Windows service
cd android && ./gradlew :policy:testDebugUnitTest :app:testDebugUnitTest assembleDebug
```

The engine is Rust and is shared verbatim by both platforms through UniFFI, so a policy question has
exactly one answer on the phone and on the PC.

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).
