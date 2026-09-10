# Plan: depth, immediacy, and one app in two voices

Everything below came out of using the phone build, plus the brief that followed it. It is ordered
by what makes the app wrong today, not by what is easy.

## 1. Nothing updates until you leave and come back (blocking)

The symptom: change a rule, end a session, grant a permission — the screen keeps showing the old
answer until the activity is recreated.

- Every screen reads one `CurfewViewModel.state` snapshot. The runtime refreshes it on explicit
  calls, not on the events that change it. Make the state a derived flow over the runtime's own
  `StateFlow`s (lock, activeProfiles, config generation) so a write publishes a new snapshot.
- Add a config generation counter to `CurfewRuntime`, bumped in `commitConfig`, `saveProfile`,
  `saveRule`, `deleteRule`, `setConfig`. Everything derived from the config recomputes off it.
- A one-second ticker while any session is live, so `now` moves without a resume.
- Kill the modal "Saved." dialog after an app toggle: the row itself changing is the receipt.

## 2. Ending a block does not end it

Ending a session appears to work, then enforcement comes back for a few seconds. The end must be a
single, observed transition: `Sessions::end` writes, the runtime republishes the lock, and the
enforcer's cached decision is invalidated in the same step rather than at its next poll. Nothing may
re-block on a stale snapshot. Invariant 2 ("a lock is a promise") already says the exit is one
place; this makes the exit take effect at one instant too.

## 3. Permissions asked the way every other app asks

Runtime permission prompts wherever Android offers one (notifications, usage access where a dialog
exists); a direct deep link into the exact settings page where it does not; never a wall of prose
with an "Open App info" button. The optional device-admin item stays the one explained-at-length
exception, because it is the one that deserves it. Health screen keeps the honest "what is lost
without it" line, but the action is one tap and it is the system's own UI.

## 4. Two voices, one app

**Simple mode** is the default and is not a crippled build:
- Sync lives in Settings, not on the home screen — but it is *there*, discoverable, with a plain
  "last synced" line. Today a simple-mode user cannot see syncing at all.
- Usage is written for a person, not a dashboard: screen time before Curfew, screen time now, hours
  given back, a streak. Numbers with a subject.

**Power mode** stops being a denser copy of simple mode. The nav bar is over-stuffed; it drops to
the same small set, and the extra surfaces (rules, audit, devices, raw config) live one level in.
Power users get more information, presented as simply as the information allows.

## 5. Depth and soul

A floating nav bar everywhere on mobile — etched-glass translucency, blur behind, a hairline edge,
lifted off the bottom with real shadow. The same treatment on sheets and the block screen, so the
app has one material rather than flat cards on flat backgrounds. This is the app's own look; not
Material defaults, not a copy of the desktop.

## 6. Carried over from before this brief

- Screens never yet exercised on hardware: Events, Health, Usage, Devices, Settings.
- Per-profile website blocking; calendar sync end to end; the Plan pause switch writing
  `enabled = false`.
- `ProfileNew.dc.html` drew a colour swatch row the `Profile` model has no field for — either add
  the field or drop the row.
- Phase 6 leftovers: webhooks on session start/end, externalized strings, F-Droid and winget/scoop.
- Parked by request: NFC/QR (Phase 7), Shizuku (undecided), device-owner mode (refused outright).

---

# Revision 2 — design the experience first

The order of work changed on instruction: **draw it before building it.** Every item below gets an
artboard on the phone canvas first, and the code follows the artboard. What follows is the whole
remaining list, in the order it will be done.

## Step 0 — draw (design canvas, phone)

New or redrawn artboards:

1. **NavGlass** — the floating bar in both states, with the material spelled out: translucent
   ground, lit top hairline, shaded belly, shadow, selected tab on a faint disc. This is the app's
   material, and sheets and the block screen inherit it.
2. **NowSimple / NowPower** — the same screen in two voices. Power shows more *inside* the screen
   (which rule fired, what it matched, when it next runs) and not more tabs.
3. **UsageSimple** — screen time before Curfew, screen time now, hours given back, streak. Numbers
   with a subject and a comparison. The table of per-app seconds is the Power view of the same
   screen, not the default one.
4. **SettingsSimple** — where syncing lives for everyone: a plain "last synced" line, the devices
   it syncs with, one control. Plus the way in to Usage, Health and Devices.
5. **Permission** — the one-tap pattern: system dialog where Android has one, a deep link into the
   exact settings page where it does not, one sentence saying what is lost without it.
6. **BlockScreen** — restyled in the same glass material, no ids.

## Step 1 — build, in this order

1. **Immediacy.** Every mutation already calls `refresh()`, and there is a one-second tick, so the
   "only updates when I leave and come back" symptom is not a missing call: it is `refresh()` being
   slow or stalling. It reads the filesystem, the database, the sync node (which waits on the same
   lock the discovery thread holds), the package manager and the permission states — all of it,
   every second, for every screen. Split it: a fast path (lock, sessions, now, activations) that
   runs on the tick and after every write, and a slow path (sync, stats, audit, grants) that runs
   on its own longer cadence and on resume. Measure it on the device before and after.
2. **Ending a block ends it.** The end must invalidate the enforcer's cached decision in the same
   step it writes, not at the enforcer's next poll — the few seconds of re-blocking after an end is
   that gap.
3. **Permissions** as drawn in step 0.5.
4. **Power mode redefined**: same five tabs, depth inside screens.
5. **Usage rewritten** for a person.
6. **Sync surfaced in Settings** for Simple.
7. **Glass material** applied beyond the nav bar: sheets, dialogs, block screen.

## Already landed while this plan was being written

- Floating etched-glass nav bar, five tabs, identical in both modes. Events is now on the bar in
  Simple mode — the previous build told a Simple user to "go to the Events tab" and then hid it.
- No more "Saved." popup: a save is silent, the changed row is the receipt.
- No ids on the block screen; the Now dial's countdown reads epoch seconds correctly.
