# The flows: what a person taps, in order

This is the app as a sequence of taps rather than as a set of screens. Every journey below is
written the way a user walks it — what they see, what they touch, what happens, and where they can
get stuck — and each one ends with **Gaps**: the places where the current build does not yet do
what the flow says. The gaps are the build list. Nothing here is aspirational UI: where a control
does not exist yet it is marked as missing rather than described as if it were there.

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
3. Duration: a dial preset to 30m, with **nudge it, or pick one** beneath. Tap a preset chip or
   drag. No typing.
4. Pick which profile to run. First run has none, so this is where the app must offer **Block
   everything distracting** as a one-tap default rather than an empty list. *(Gap 1.)*
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

**Gaps**
- *(Gap 1)* No starter profile. A first-run user reaches the Timer screen and has nothing to pick.
- *(Gap 2)* Accessibility is asked from the Health screen rather than at step 6, so a first-run user
  can start a block that then silently enforces nothing.

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

**Entry:** **Apps** tab.

1. A profile picker at the top: apps and sites are **per profile**, and the screen says which one
   it is showing.
2. Two panes — **Apps** and **Websites** — as tabs with counts, never one long scroll. Website
   addresses used to begin after two hundred app rows.
3. **Apps**: search, then tap to tick. The list is the phone's real apps with their real names and
   icons.
4. **Websites**: add by typing a domain. `youtube.com/shorts` names a page and needs the browser
   extension on desktop; the row says so rather than failing quietly.
5. Every tick writes immediately. The tick is the receipt.

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

**Gaps**
- *(Gap 2, again)* The asks still live on Health rather than at the moment of need.

---

## Flow 7 — Seeing that it is working

**Entry:** **Now**, always — the number that says "this is working" is not allowed to live behind a
tab.

1. Under the dial: **"2h 18m away from the phone today"**, the week behind it, and the run of days.
2. Power mode adds blocks kept and longest run, on the same card. No extra screen.
3. **Settings → Where your time went** for the detail: per app, per day.
4. The Usage screen's headline is a comparison, not a table: screen time before Curfew, screen time
   now, hours given back. The table is what Power mode reveals underneath it.

**Gaps**
- *(Gap 6)* The before/after comparison needs a pre-Curfew baseline. `UsageStatsManager` keeps daily
  totals for weeks, so the baseline is the average daily screen time over the days before the first
  session Curfew ever ran. Until it exists the home screen states only what Curfew can vouch for:
  time the phone was actually held shut.

---

## Flow 8 — Two devices

**Entry:** **Settings → Syncing** (visible in both modes; it used to exist only on a screen Simple
mode could not reach).

1. Settings states what is true now: paired or not, in earshot or not. **Sync now** and **Devices**.
2. **Devices** → **Pair a device**: a QR code and six words. The other device scans, both show the
   same six words, both confirm. No account, no server, no internet permission anywhere in the app.
3. A block started on one device runs on both. Ending it needs the device that holds the lock —
   which is the point, and the screen says so rather than failing.

**Gaps**
- *(Gap 7)* Pairing has never been walked on two real devices.

---

## Flow 9 — Simple ↔ Power

**Entry:** **Settings → How much do you want to see?** — a two-tab tray, Simple selected by default.

Power changes what is *inside* screens and never the route to anything:

| Screen | Simple | Power adds |
|---|---|---|
| Now | dial, session card, today's hours | next 24h timeline; which rule fired; blocks kept |
| Plan | triggers with switches | ids, raw windows, the config |
| Usage | before / now / given back | per-app, per-day table |
| Settings | the six things | audit trail, raw config, export |

Both modes have the same five tabs — **Now · Plan · Events · Apps · Settings** — on the same
floating bar. Power mode used to add tabs until the bar was unreadable.

---

## The build list these flows produce

In order:

1. **Gap 1** — a starter profile so the first Timer screen has something to pick.
2. **Gap 2** — permissions asked at the moment of need, accessibility first among them.
3. **Gap 6** — the pre-Curfew baseline, and the Usage screen rewritten around the comparison.
4. **Gap 3, 4, 5** — covered by tests. What remains of them is a hardware pass: the switch under a
   finger, a real calendar provider, a real browser.
5. **Gap 7** — pairing, which needs two devices and cannot be faked.
6. Glass material on sheets, dialogs and the block screen, so the app has one material.
