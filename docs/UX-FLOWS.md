# The flows: what a person taps, in order

This is the app as a sequence of taps rather than as a set of screens. Every journey below is
written the way a user walks it — what they see, what they touch, what happens, and where they can
get stuck — and each one ends with **Gaps**: the places where the build did not yet do what the flow
says. Nothing here is aspirational UI: where a control does not exist yet it is marked as missing
rather than described as if it were there.

**Every gap in this document is closed as of 11 September 2026**, so those lines now read as a record
of what was missing and what closed it rather than as a to-do list. What is genuinely outstanding is
hardware verification, in the two sections at the end: a switch under a real thumb, a real calendar
provider, two real devices. *The build list these flows produce* carries the status of each and says
how the check was done.

## The principles these flows are held to

Five, taken from what blockers get wrong rather than from a style guide:

1. **The first block must happen in under a minute.** Anything a person has to configure before
   they can feel the app work is a reason to close it. Setup that can be deferred is deferred.
2. **Never ask for a permission before the thing that needs it.** A wizard on first launch teaches
   people to tap through without reading, which is how an app that watches your screen all day ends
   up trusted by accident. Each permission is asked at the first moment it is actually required,
   with one sentence saying what is lost without it.
3. **A refusal must name its price and its exit.** "No" without a reason is what makes someone
   uninstall a blocker in a bad moment. Every refusal says what is still missing and when it ends.
4. **The receipt is the changed thing, not a popup.** A save that changes a row is confirmed by the
   row changing. Dialogs are for decisions, never for acknowledgements.
5. **Simple and Power are the same flows.** Power adds detail inside a screen. It never adds a step,
   a tab, or a different route to the same outcome.

---

## Flow 1 — First run to first block (target: 40 seconds)

**Entry:** app icon.

1. **Now** opens. Headline: *"Nothing is blocked right now."* Dial reads **Next up** — or `—` when
   nothing is scheduled. One filled button: **Start a block now**.
2. Tap **Start a block now** → **Timer** screen.
3. Duration: a dial preset to 30m, with **drag the ring, or pick one** beneath. The ring is
   draggable minute by minute the way a phone's own timer is; presets are there for the common
   lengths. No typing.
   A block of four hours or more asks once before it starts — a dial makes a long block easy to set
   by accident, and under *Lock it* there is no taking it back.
4. Pick which profile to run. First run has none, so this is where the app offers **Block
   everything distracting** as a one-tap default rather than an empty list.
5. **If you change your mind** — three strengths, plain-language, one selected by default:
   - *Just ask me* — end whenever.
   - *Make me wait* — a delay before it ends.
   - *Lock it* — needs the device credential.
6. Tap **Lock it in for 30m** (amber, because amber is what "running" looks like everywhere else).
7. Back on **Now**: dial is amber, counting down, *"14m left · ends 10:05"*. Below it the session
   card with **End now**.
8. First app opened against the block → **Block screen**: the app's real name, the profile's real
   name, when it ends, and the one honest way out.

**Permission asked in this flow:** accessibility, and only at step 6 — the first moment Curfew
actually needs to see what is in front. Sheet: one sentence of cost, one button, straight to
Curfew's own row in Settings. Notifications is asked immediately after, by the system dialog.

**Gaps** — none. *(Gap 2 was here: accessibility was asked from the Health screen rather than at step
6, so a first-run user could start a block that then silently enforced nothing. **Closed:** the Timer
screen asks for the switch itself, and — this was the second half of the bug — starts the block when
the user comes back from Settings rather than leaving them with nothing. A route added later cannot
skip the check, because all three routes call one function. Re-verified against the code, not against
this sentence.)*

---

## Flow 2 — Ending a block

**Entry:** Now, session running.

1. Tap **End now** on the session card.
2. Three outcomes, decided by the lock and never by a mood:
   - **Just ask me** → it ends. Dial goes grey on the same tick. No confirmation dialog: the dial
     changing is the receipt.
   - **Locked** → the device's own credential prompt. Success ends it; cancel returns to Now with
     nothing changed and nothing scolded.
   - **Waiting** → **Not yet** dialog: what is still missing, and when it ends on its own. From
     here, **Ask to end in 24 hours** starts the delayed release, which cannot be moved later or
     cancelled — and says so before it is started, not after.
3. An ended block stays ended. A schedule still matching the current window does not restart it;
   the next occurrence starts normally.
4. A block whose timer reaches zero ends itself on the tick that shows zero. **End now** is for
   ending early, never for finishing.

**Gaps** — none known. Both behaviours in 3 and 4 were bugs and are fixed.

---

## Flow 3 — Making a schedule (weekly)

**Entry:** **Plan** tab.

1. Plan lists every trigger — weekly windows (amber) and calendar rules (blue) — each with a
   switch, the profile's name, and its window in words.
2. Tap **Add a window** → the window form: profile, days, start, end.
3. Save → the sheet closes and the row is there. No "Saved."
4. The switch on a row pauses that trigger without deleting it. Off means it starts nothing;
   it does not end anything already running (a lock is a promise).
5. Long-press or the row's own control removes it, with one confirm naming what is being removed.

**Gaps**
- *(Gap 3, closed in tests)* The pause switch is covered end to end — flag written, row kept, nothing
  started, restarted when switched back on, and a running session left alone. What is left needs a
  finger on a real switch, not a test.

---

## Flow 4 — Blocking from the calendar

**Entry:** **Events** tab (on the bar in both modes — it used to be named in text and hidden in
Simple, which is how a user is told to go somewhere that does not exist).

1. Without calendar permission: one card, one button — **Allow calendar access** → the system
   dialog. No settings detour; this one has a real Android prompt.
2. With it: the next day and a half of events, grouped **Today / Tomorrow**, with a search field
   that filters as you type.
0. The same list opens inside a profile, from its **Anything in my calendar** trigger. Picking an
   event never means leaving the profile for the Events tab and finding your way back.
3. Tap an event → **Block during this event**: which profile, and whether this is *just this event*
   or *every event whose title matches*.
4. Saving the matching kind puts a blue rule on **Plan**, where it can be paused or removed like
   any other trigger.
5. A calendar event that is deleted afterwards does not end a running session. It stops future
   occurrences only.

**Gaps**
- *(Gap 4, closed in tests except the provider read)* Rule → event → session → block is covered,
  padding and end-of-meeting included. The calendar-provider read itself still needs a phone with a
  real calendar on it.

---

## Flow 5 — Choosing what a profile blocks

**Entry:** the profile itself — **Plan → a profile → What it blocks**. There is no Apps tab. Apps
and sites belong to a profile, so a separate tab meant leaving the profile you were half way
through building in order to say what it blocks, and then finding your way back.

1. The row reads its own counts — *"3 apps and 2 sites"*, or *"Nothing yet"* — so the profile says
   what it does without opening anything.
2. Two panes — **Apps** and **Websites** — as tabs with counts, never one long scroll. Website
   addresses used to begin after two hundred app rows.
3. **Apps**: search, then tap to tick. The list is the phone's real apps with their real names and
   icons.
4. **Websites**: add by typing a domain. `youtube.com/shorts` names a page and needs the browser
   extension on desktop; the row says so rather than failing quietly.
5. Every tick writes immediately. The tick is the receipt.
6. **Copy from** another profile, for the second profile that blocks almost what the first one did.
   One confirm naming the counts; it adds and never removes.

**Gaps**
- *(Gap 5, closed in tests)* The running profile decides: its addresses are blocked and the other
  profile's are allowed, through the enforcer rather than by reading the config back.

---

## Flow 6 — Granting a permission (the pattern, everywhere)

Curfew never shows a wall of prose with an "App info" button. In order of preference:

1. **A system dialog** where Android has one — notifications, calendar. One tap, answered in place.
2. **A deep link to Curfew's own row** where Android has a details page — accessibility (12+),
   notification listener (11+). The user lands on the switch, not on a list of every service they
   have ever installed.
3. **The settings page for that permission** where there is no per-app page — usage access, overlay,
   exact alarms, battery.
4. **App info** as the fallback that always resolves, so a missing OEM page can never crash the one
   screen whose job is to fix permissions.

Each row says, in one sentence, what stops working without it. **Uninstall protection (device
admin)** is the sole explained-at-length exception, because it is the only one with a cost outside
Curfew — many banking apps refuse to run alongside any device admin — and it is never recommended,
only offered.

**Sideloaded installs** hit Android's "Restricted setting" wall: the accessibility switch is greyed
out with no explanation. Curfew detects the likely case and spells out the menu path, because the
dialog cannot be opened by an app.

**Gaps** — none. *(Gap 2 again was here: the asks lived on Health rather than at the moment of need.
**Closed** with the same change as in Flow 1. Health still explains a *blocked* switch — Android's
restricted-setting wall is a different problem from not having been asked — but it is no longer where
the asking happens.)*

---

## Flow 7 — Seeing that it is working

**Entry:** **Now**, always — the number that says "this is working" is not allowed to live behind a
tab.

1. Under the dial: **"2h 18m away from the phone today"**, the week behind it, and the run of days.
2. Power mode adds blocks kept and longest run, on the same card. No extra screen.
3. **Settings → Where your time went** for the detail: per app, per day.
4. The Usage screen's headline is a comparison, not a table: screen time before Curfew, screen time
   now, hours given back. The table is what Power mode reveals underneath it.

**Gaps** — none. *(Gap 6 was here: the before/after comparison needed a pre-Curfew baseline.
**Closed:** `CurfewRuntime` takes the average daily screen time over the days before the first session
Curfew ever ran — once, on the first launch that has usage access, and never rewritten, because a
baseline that moved with usage would let a heavy week look like the starting point.)*

---

## Flow 8 — Two devices

**Entry:** **Settings → Syncing** (visible in both modes; it used to exist only on a screen Simple
mode could not reach).

1. Settings states what is true now: paired or not, in earshot or not. **Sync now** and **Devices**.
2. **Devices** → **Pair a device**: a QR code and six words. The other device scans, both show the
   same six words, both confirm. No account and no server: the app holds `INTERNET`, and the only
   traffic it carries is to devices you paired, over your own network.
3. A block started on one device runs on both. Ending it needs the device that holds the lock —
   which is the point, and the screen says so rather than failing.

**Gaps**
- *(Gap 7, hardware only)* Pairing is **built** — the ceremony, the six words, confirm and answer, all
  covered by tests. What has never happened is walking it on two real devices, which no amount of test
  coverage substitutes for.

  *Correction, and it is the second time this sentence needed one:* this line used to read *"no
  internet permission anywhere in the app"*. `AndroidManifest.xml` declares `INTERNET`, because two
  devices on one network need a socket. The screens were corrected once already; this copy of the
  claim was missed then and is corrected now, which is what the manifest's own comment predicted.

---

## Flow 9 — Simple ↔ Power

**Entry:** **Settings → How much do you want to see?** — a two-tab tray, Simple selected by default.

Power changes what is *inside* screens and never the route to anything:

| Screen | Simple | Power adds |
|---|---|---|
| Now | dial, session card, today's hours | next 24h timeline; which rule fired; blocks kept |
| Plan | triggers with switches | ids, raw windows, the config |
| Profile | triggers, what it blocks | — |
| Usage | before / now / given back | per-app, per-day table |
| Settings | the six things | audit trail, raw config, export |

Both modes have the same four tabs — **Now · Plan · Events · Settings** — on the same floating
bar. Power mode used to add tabs until the bar was unreadable; Apps was removed because what a
profile blocks belongs inside that profile.

---

## The build list these flows produce

**There are no gaps left in this document.** Every one of them is closed, and each line below says
what closed it — because a build list that keeps listing finished work stops being a build list, which
is what had happened here: three of the five items had been done for a while and the list still read
as though they had not.

| Was | State |
| :--- | :--- |
| **Gap 2** — permissions asked at the moment of need, accessibility first | **Closed.** The Timer screen asks for the switch and starts the block when the user returns from Settings. |
| **Gap 6** — the pre-Curfew baseline, and Usage rewritten around the comparison | **Closed.** `CurfewRuntime` takes the baseline once and never rewrites it; the Usage headline is a comparison. |
| **Gap 3, 4, 5** — pause switch, calendar rules, per-profile enforcement | **Covered by tests**, and were when this list was written. What remains is a hardware pass: the switch under a finger, a real calendar provider, a real browser. See the two sections below for what has since been walked by hand. |
| **Gap 7** — pairing | **Built.** The ceremony, the six words, confirm and answer, all covered by tests. Nothing in it has met a second real device. |
| Glass material on sheets, dialogs and the block screen | **Still open.** The block screen and the nav bar have it; `DSheet` does not, and `NowScreen` still uses eight Material `AlertDialog`s — so the screen a user sees most has two visual languages for corners, buttons and elevation. |

### How to read the rest of this document

It is now a **specification with a status column**, not a to-do list, and the difference is worth
stating because this document has been read as both. The flows describe the app as it should behave.
The gap lines record what used to be missing and what closed it. Where a claim here is about
*hardware* — a switch under a real thumb, two real devices, a real calendar provider — it is in the
sections below and stays open until somebody has actually done it.

**Verified against the code on 11 September 2026**, and the two claims that were wrong are corrected
in place rather than deleted: `Gap 2` and `Gap 6` described work that was already done, `Gap 7` was
listed as a build item when only its hardware walk was outstanding, and Flow 8 repeated a false claim
about the internet permission that had already been corrected on the screens.

---

## What has never been touched by a finger

Everything below is build-verified and test-verified and nothing more. It is written down because
"the tests pass" is not the same claim as "it works", and the difference is exactly the kind of
thing that gets forgotten between one session and the next.

- The confirmation on a block of four hours or more.
- Setting a budget from inside a profile. The bug it hid — a budget replacing the app's block and
  emptying the profile's app list — is fixed in the core and covered by tests, but the fix has not
  been through a finger yet.
- The Plan page as one card per profile, and unticking a repeating schedule.
- Copying what another profile blocks. Needs a second profile to exist first.
- Two devices, and everything in Flow 8.

Picking calendar events from inside a profile **has** been done by hand, on a Galaxy S24 Ultra: the
sheet opens from the profile, reads real events, filters as you type, takes several events in one
visit, and the rules survive an app restart.

The emulator cannot stand in: this machine has no virtualisation extension for the x86 image and
the wrong host architecture for the arm64 one.

## What a finger has now done, on a Galaxy S24

Walked on the device on 10 September 2026, against the starter profile:

- **The draggable ring.** A thumb lands on a specific minute without fighting it, and a lap that
  goes past the hour keeps the handle under the thumb. Dragged 25m back to 3m in one movement.
- **Flow 1 end to end.** Start a block now, drag, pick a strength, lock it in; Now came back amber
  and counting down.
- **The one-second beat.** The block screen counted 2:26 → 2:21 across five seconds of real time.
- **Enforcement against an app that is actually installed.** Netflix, which the starter profile
  blocks, was replaced by the block screen naming the app, the profile and the end time.
- **The **What it blocks** row now that the Apps tab is gone**, with its two tabs, counts, search
  and the chosen apps pinned at the top.
- **A calendar rule read from a real provider** — an event from the user's Google calendar, shown
  on Plan.
- **A session ending itself at zero**, with the dial grey and the hours banked on the same tick.
  This is also where the finger found a bug the tests could not: the block screen it had put in
  front of Netflix stayed up after its session ended, still saying a session was running. Fixed —
  the screen now watches the profile that justifies it and leaves when it stops running.
