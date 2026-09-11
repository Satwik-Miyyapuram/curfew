# User Interaction and Flow Review — Curfew

**How the product should behave · how comparable apps do it · what we actually built · where the three disagree.**

Revision 1 · branch `installer-no-reboot` · commit `525e691`

Companion to `DESIGN_AND_CODE_REVIEW_FULL.md`. That document judged the code as code. This one judges the product as something a person uses: the screens, the flows, the copy, and whether the promises the design makes are the promises the build keeps.

---

## 0. Method, and what you can trust

| Input | How it was used |
| :--- | :--- |
| `docs/UX-FLOWS.md` | The design's own statement of nine journeys, five principles, and a *"the gaps are the build list"* claim. Treated as the **specification** to audit against. |
| `docs/PLAN-mobile-polish.md`, `ARCHITECTURE.md` §10–§11, `GAPS.md` | Secondary intent — honesty, degradation, the escape hatch, externalised strings. |
| Android build — `ui/*.kt`, `block/*.kt`, `enforce/*.kt`, `res/*` | Read directly; `path:line` throughout. |
| Windows + web build — `curfew-app/ui/app.html`, `curfew-tray/*`, `curfew-svc/main.rs`, `extension/*` | Read directly. |
| Design canvas — 19 phone artboards, 9 Windows artboards, `_tok.txt`, `build.py`, `canvas.json` | Compared against the build **asset by asset**. |
| Comparable products | Vendor sites, help centres, docs and GitHub wikis fetched directly. |
| WCAG 2.2 SC 2.5.8 (Target Size) | Fetched, for the touch-target findings. |

**Provenance.** `[V]` = I read the code/asset and confirmed it. `[R]` = reported by a parallel deep audit with citations, consistent but not independently reproduced. `[fetched]` = retrieved from a real URL, cited inline.

**Two limitations, stated plainly.**

1. **`web_search` is broken in this environment** (HTTP 401 on the search endpoint). All competitor research was done by fetching URLs directly. That worked well for vendor documentation and help centres; it did **not** reach Reddit, Trustpilot, app-store reviews or any third-party roundup. **There is no independent user-complaint data in this report** — only what vendors and their own featured reviews admit. Treat the competitor analysis as *how these products document themselves*, not as *how users experience them*.
2. **No screenshot was viewed.** Image downloads failed at the TLS layer in this sandbox, so every screen description derives from source, vendor captions and prose — never from looking at an image.

**Four research gaps, stated rather than filled from memory:** Brick (could not be fetched at all), Opal's onboarding flow, and the block pages of Freedom and SelfControl (undocumented in anything reachable).

---

## 1. What Curfew says it is trying to do

`docs/UX-FLOWS.md:9-24` sets five principles. They are unusually good, and they are the yardstick below — not because they are the only valid principles, but because they are the ones this product published and the ones its users will hold it to.

1. **The first block must happen in under a minute.** Setup that can be deferred is deferred.
2. **Never ask for a permission before the thing that needs it.** A wizard teaches people to tap through.
3. **A refusal must name its price and its exit.** "No" without a reason causes uninstalls.
4. **The receipt is the changed thing, not a popup.** Dialogs are for decisions, never acknowledgements.
5. **Simple and Power are the same flows.** Power adds detail inside a screen, never a step or a tab.

Plus three product-level promises:

- **The last-resort exit is universal.** `ARCHITECTURE.md:211-214`: *"every lock, at every strictness level, can be released by starting a 24-hour delayed release… visible from the moment the lock starts. This is what separates a commitment device from a trap."*
- **Honest degradation.** `ARCHITECTURE.md:186-191`: the app must always know which enforcement layers are live and say so; a silent failure is the one thing the project refuses to ship.
- **Strings externalised from day one.** `GAPS.md:191` (E6).

**Scorecard, before the detail:**

| Promise | Verdict | Where |
| :--- | :--- | :--- |
| 1. First block under a minute | **Android: met (2 taps) when permissioned — P0 when not. Windows: no route at all** | §5.1, §7.2 |
| 2. Never ask before needed | **Met, and to a fault** — nothing asks, so usage access is never granted | §7.1 |
| 3. Refusal names its price and exit | **Best-in-class where it exists; absent or dishonest in six places** | §6.3 |
| 4. Receipt is the changed thing | **Broken on both platforms** — acknowledgement modals on Android, silence on Windows | §6.2 |
| 5. Simple and Power | **Does not exist** | §5.4 |
| Universal last-resort exit | **The 24-hour release works; the emergency pass does not exist on a default install** | §5.2 |
| Honest degradation | **Android has the best screen in the app; Windows has none** | §6.4 |
| Strings externalised | **12 strings, 6 references, ~122 hardcoded** | §6.5 |

**Overall judgement, up front.** The *feel* of this product is good, and in four specific ways better than anything reviewed (§6.1). The problem is not taste. It is that the build keeps about half of what the design promised, drops most of the rest silently, and — on Windows — does not have the product's central verb at all. The failures cluster at exactly the moment a first-run user decides whether to keep the app.

---

## 2. How comparable products do it

Every claim carries the URL it was fetched from.

### 2.1 The headline finding: there is no single "interception moment"

There are **four distinct architectures**, and they are not variations of one idea:

| Architecture | What the user sees on opening a blocked app | Products |
| :--- | :--- | :--- |
| **A. Full-screen wall** — no way forward | A block screen; no path into the app | Opal Hard Mode, Jomo Strict Mode, one sec Blocking, Apple *Block at Downtime* |
| **B. Pause-then-proceed** — friction, always winnable | A timed or effortful screen, then the app opens | one sec default, ScreenZen, Unpluq |
| **C. Physical proof** | A screen demanding a physical object | Unpluq Tag (NFC), ScreenZen halo (BLE room boundary) |
| **D. Loss framing** — no block screen at all | *Nothing.* The app opens; a tree dies | Forest |

The sharpest disagreement in the category, in the products' own words: **is the intervention a decision aid or a wall?** one sec publishes a four-level user-agency framework arguing that a system nudge *"is a risk that a one-size nudge won't actually be suitable for anyone"* [fetched: one-sec.app/blog/self-nudging/]. Opal sells the opposite, named literally **"Hard Mode — No way out. You can't turn Opal off."** [fetched: opalapp.com/pricing]. Both premium, both well-reviewed. **The market has not settled this.**

**Where Curfew sits.** Architecture **A** — a full-screen `BlockActivity` with one button that leaves (`BlockActivity.kt:266-288`). That is legitimate and matches the project's philosophy. But it inherits architecture A's obligation: **if there is no way forward, the screen must be maximally informative**, and it must contain the exit, because architecture A offers no friction-based release at the moment of denial. §6.1 shows Curfew does the first better than anyone. §5.2 shows it does not do the second.

### 2.2 Convergent patterns — well-earned conventions

| # | Pattern | Evidence |
| :-- | :--- | :--- |
| 1 | **Strictness is a separate, later decision from what-gets-blocked** | Cold Turkey: locks on point 5 of the Blocks tab [fetched: getcoldturkey.com/support/user-guide/]; FocusMe: step 3 of 4 [fetched: docs.focusme.com/quick-start]; Freedom: one global toggle [fetched: support.freedom.to/en/articles/1802927-locked-mode] |
| 2 | **The friction primitive is "type N random characters", and N is small** | Cold Turkey 1–999 (guide) / 1–5000 (features page — the two docs disagree); FocusMe 1–2000, presets at 5/10/25 [fetched: docs.focusme.com/concepts/plan-protection] |
| 3 | **A wait is a first-class lock, and the wait comes *before* the challenge** | Cold Turkey Delay = 1 min to **40 days**; FocusMe: *"Choose Stop. The plan keeps blocking and the countdown starts; right-click the plan to Cancel Stop if you change your mind"* |
| 4 | **One-way ratchets everywhere** — a running lock may be tightened, never loosened | Cold Turkey *"You can always extend the end time"*; FocusMe the literal error *"You cannot set a time period that is less than the previous one"*; Freedom allows adding to a locked blocklist but never removing |
| 5 | **Uninstall is explicitly defended and named as a feature** | Cold Turkey: a locked block makes *"the uninstaller fail when you click Next"*; FocusMe **Protect Uninstall**; Freedom a whole support article |
| 6 | **Task Manager is a first-class bypass surface** | Cold Turkey **Block task managers**; Freedom **Block Task Manager** / **Block Activity Monitor**; FocusMe **Protect FocusMe Process** |
| 7 | **"Day" is user-definable and defaults to 4 AM** | FocusMe **Next Day Offset Hours**, default 4AM, *"Late-night usage counts towards the previous day"*; Freedom resets breaks *"at 4 AM local time"* |
| 8 | **The escape hatch exists everywhere, it is scarce, and the scarcity is published** | Opal **1/week**; Jomo **2/week**; Unpluq **5 min, 1/day** plus 5 lifetime lost-tag uses; Forest pause **5 min**, Pro-only |
| 9 | **A "no way out" premium tier with a distinct name** | Opal **Hard Mode**; Jomo **Strict Mode**; ScreenZen **lock mode / No-bypass**; one sec **Strict Block Sessions** |
| 10 | **Progress is a headline number, almost always *time reclaimed*** | one sec "time you've saved"; Unpluq "1h 20m a day"; ScreenZen "3 hours everyday"; Jomo "1h48 per day". **Only Opal leads with a composite score** (Opal Score®) |
| 11 | **Explicit anti-guilt framing, stated as design principle** | Unpluq *"Joy, not guilt… not through rules, not through guilt"*; Forest *"No shame, no preaching, no 'crush your goals'"*, dead trees *"stay in your forest as an honest record of the journey, not a verdict"* |
| 12 | **Every premium product sells against the free OS feature** via a named comparison page | Opal, Forest, Jomo, one sec all publish one |
| 13 | **The free tier is one unit of the core mechanic** so the principle can be validated | one sec = **1 app**; Opal = **1 Rule**; Unpluq = **2 apps, 1 schedule, 2 barriers**; Forest = **blocking free**, analytics paid |
| 14 | **Statistics are not an engagement surface** — nobody ships streaks-as-reward except Opal | Freedom, verbatim: *"There are no streaks, points, leaderboards, or rewards. Every feature exists to reduce distractions… not to keep you coming back to the app."* [fetched: freedom.to/features] |
| 15 | **Friction sits above the app, not inside it** | one sec intervenes *"right when the action happens"*; Forest at *"the moment level"*; Unpluq *"right where the habit usually wins"*. Android's *"the app closes"* is the counter-example that validates the rule |

### 2.3 Divergent choices, and what the disagreement is really about

**(a) Onboarding: teach vs. delegate vs. nothing.**

| Product | First-run | Steps to first block |
| :--- | :--- | :--- |
| Cold Turkey | Nothing. Four install steps including a **browser-detection popup**; a pre-made "Distractions" list | Blocks tab → toggle |
| Freedom | 5-step checklist + video; **login gates everything** | Dashboard → 4 choices; vendor claims *"about 10 seconds"* |
| **FocusMe** | **Two questions** — *"What are you fighting?"* (6 options), *"How bad is it, honestly?"* (3) — **builds 3 plans**, pre-marks a suggested schedule | ~3 screens |
| SelfControl | Nothing. Empty state is normal | domain → duration → Start |
| one sec | **Permissions up front** (Accessibility + App Overlay) before app selection | 2 permissions, then pick one app |
| Opal | Account-first; Screen Time permission with its own explainer | — |

The disagreement is about **who is the expert on your distraction**. FocusMe assumes you know your problem but not the mechanics, and maps problem → configuration. Everyone else presents primitives. FocusMe is the only vendor that documents step counts, the only one with a **Plan Library**, and the only one whose wizard pre-answers "when" (Always on 24/7 / Weekdays 9-5 / On demand) rather than leaving a blank.

**(b) Strictness as a knob vs. a mode — and the vocabulary difference that follows.**

Cold Turkey models locking as an orthogonal attribute with **seven mechanism-named** types (Timer, Time range, Random text, Delay, Schedule, Restart, Password). FocusMe models it as **one tier that sets six coupled challenges at once**, named after **intent**. FocusMe's four, verbatim, with the cost in the label:

> **Unlocked** — *"Blocks just the same - but stopping is always one click. Pure willpower."*
> **Gentle Guidance** — *"Type 5 random characters to stop or whitelist - a decision, not a reflex. Pause freely."*
> **Firm but Flexible** — *"Stopping takes a 2 minute wait, then 10 random characters. 3 pauses a day when you need one."*
> **Lock it Down** — *"Stopping takes a 10 minute wait, then 25 random characters typed perfectly. No pauses or workarounds."*
> [fetched: docs.focusme.com/concepts/plan-protection]

**Cold Turkey names the mechanism; FocusMe names the feeling.** FocusMe's labels survive not knowing what a "time range lock" is — which is why FocusMe can publish a recommended default and Cold Turkey cannot:

> *"**Escalate, don't jump.** Most people never need Enforced. A preset that lets you out at a price you rarely want to pay is the sweet spot."* [fetched: docs.focusme.com/guides/protect-plan-from-yourself]

**(c) What the escape hatch costs — four different answers.**

| Product | The hatch | Cost |
| :--- | :--- | :--- |
| Cold Turkey | **Pause for a Cause** — 10 minutes, **on the block page** | Money, to charity. *"$126,182 CAD donated so far"* [fetched: getcoldturkey.com/donations/] |
| Freedom | **Session Break** 5 min; **once-per-7-days** exit; a **1-minute grace window** at start | Time, rationed |
| FocusMe | **Assisted Stop** — a partner approves by email, or pay a self-imposed penalty to ask support | Social capital, or money |
| SelfControl | None. *"You can't. That's the idea. Just wait."* | Refusal |

The disagreement is about **what the commitment is to**. Freedom answers explicitly and refuses to let even its own staff end a session: *"Freedom Customer Support is not authorized to end block sessions… even by support agents."* [fetched: support.freedom.to/en/articles/1776713-ending-a-block-session]

**Freedom's two-tier escape is the best-reasoned mechanic found in this review**: a **1-minute grace window** at the start (*"allowing for immediate adjustments if needed"*) next to a **7-day** rationed credit. It separates *immediate mistakes* from *momentary weakness* — a distinction Cold Turkey (one 10-minute donation) and FocusMe (flat daily pause counts) both blur. See recommendation §8.22.

**(d) Where the paywall sits — the widest divergence in the set.**

- **ScreenZen: no paywall at all.** Free app, donation-supported, monetised on a **$49** BLE device. The control case.
- **Forest: the blocker is free**; analytics, custom allowlists and cosmetics are paid. *"No trick, no expiration."*
- **one sec: app count** gated (1 free).
- **Opal: the *difficulty tier* and the block-screen personality are gated** — the paywall sits inside the interception screen itself.
- **Unpluq: the hardware is bricked without the subscription** (*"The Unpluq Tag only works with Unpluq Premium"*).
- **Curfew: no paywall** (AGPL, no revenue). Structurally closest to ScreenZen.

### 2.4 The strongest single idea in the category

**one sec's randomised intervention portfolio.** When a target app is opened, the app picks **at random** from whichever intervention types the user enabled:

> **Breathing exercise** (default) · **Minimal breathing** · **Follow the dot** — follow with your finger for **10 seconds** · **Black screen** — **10 seconds** · **Rotate phone** — **three times** around its axis · **Mirror** — *"Look in your own eyes and tell yourself that you really want to open that app right now."*
> [fetched: tutorials.one-sec.app/en/articles/3310978]

Why it is the best idea found:

- **It defeats habituation, which is the failure mode of every fixed block screen.** A single screenshot is learned within days; a random draw from six is not. The only mechanic in the set explicitly designed to resist the user's own adaptation.
- **It converts a delay into a decision.** A fixed 10-second timer is something you wait out; "follow the dot with your finger", "rotate your phone three times", "look in your own eyes" require **active participation**, so they cannot be absorbed while walking toward the app.
- **Variety, not duration, does the work** — which sidesteps the arms race every other product is in (increase delay → habituate → increase again). No competitor publishes an escalating-duration mechanic.
- Cheap to build, cheap to extend, and it gives the paywall a natural home: one sec gates *"Different Intervention Types"* behind Pro — and marks it **N/A on Android**, which is where an Android competitor can differentiate on day one.
- The vendor's own field numbers support the pause being sufficient: **36% of interventions ended with the user closing the app**, **37% fewer open attempts over six weeks** [fetched: one-sec.app/blog/self-nudging/].

**Runner-up, and the best idea specifically for Android: Jomo's "Complete habits" rule** — *"'Do the dishes' unlocks 15 minutes of YouTube, or walking 5,000 steps fully unlocks Netflix"* [fetched: help.jomo.so/en/article/how-to-start-with-jomo-mseknq/]. The only mechanic in the corpus that routes the escape hatch into a *different, wanted behaviour* rather than a timer, making the unlock itself productive. No other product does it.

### 2.5 What the vendors themselves admit

Only sources that publish complaints are cited; nothing here is invented. **No third-party complaint source was reachable** (Reddit returned a login wall / HTTP 403; no roundup was discoverable without search), so this section documents only failure modes the vendors admit.

**Platform fragility — the biggest one.**
- Apple's app picker crashes. one sec: *"**Apple's app picker is super buggy and unreliable**… it can crash frequently"* [fetched: tutorials.one-sec.app/en/articles/3035202]. Unpluq documents the **identical** bug and workaround: *"Write the name of the app you want in the Notes app → Copy → Paste… we've reported this issue to Apple directly"* [fetched: unpluq.com/pages/faq].
- The **49 app/site cap** is imposed by Apple on *every* screen-time app — confirmed independently by Jomo and Unpluq.
- Android background-killing breaks blocking: Unpluq documents the per-OEM battery settings.
- **Uninstalling can leave apps blocked**: *"If you have deleted Unpluq while apps were being blocked, you may find the apps are still blocked. This is because of a bug in Apple's ScreenTime infrastructure"* [fetched: unpluq.com/pages/faq].
- Opal: *"Screen Time permissions screen is stuck."*

**Bypass routes users found, and how each vendor answered.**
- **Changing the device clock.** ScreenZen user: *"I'm able to bypass that by changing my phone's time"* [fetched: screenzen.co/]. Opal penalises it (*"your Streak will not be maintained"*); Jomo Strict Mode exists partly to *"Prevent changes to your iPhone's date and time"*; Cold Turkey ships **Block time and date settings**. **Curfew's answer is `ClockWitness` — and on Windows it is not wired up** (`DESIGN_AND_CODE_REVIEW_FULL.md` P0-2), **and the Android UI bypasses it with the raw clock** (P0 A-1).
- **Disabling the OS permission.** one sec ships *"How can I lock the Screen Time Permission?"*; Unpluq ships a pre-built Shortcut to block iPhone Settings.
- **Uninstalling.** Every product has uninstall protection. Curfew has device admin on Android and a watchdog on Windows.

**Silent / intermittent failure** — the failure mode Curfew's own `ARCHITECTURE.md:189-191` forbids:
- ScreenZen: *"**Occasionally there will be a day where it just doesn't block at all**"*
- Apple: *"If you don't see a summary of their activity, they might need to use their device longer in order to show a report."*
- Jomo: *"Blocking doesn't seem to be working"* is a top article.

**Too-easy vs. too-hard tension.**
- Too easy: one sec *"How many times do you press that 'one more minute' button? Is it already part of your muscle memory?"*
- Felt ceilings: ScreenZen *"if the cooldown was longer. 2 hours is a long time, but some apps I'd like… 6, 10, or 12 hours"*; Forest *"I would LOVE it if I can be focused for as long as, like, 12 hours."*

**Setup burden.** A ScreenZen user on the category leader: *"I tried **one sec** and it was much more expensive and was **incredibly tedious to set up, and most of the work done isn't even in the app itself**"* [fetched: screenzen.co/]. This is the permission-and-Shortcuts setup tax — directly relevant to Curfew's Windows problem (§7.2), where the tax is a terminal command.

### 2.6 The comparison, in one table

| Dimension | Cold Turkey | Freedom | FocusMe | SelfControl | one sec | **Curfew** |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| First block from install | Blocks tab → toggle | Dashboard, *"~10 seconds"* | **2 questions → 3 plans** | domain → duration → Start | 2 permissions, then one app | **Android: 2 taps if permissioned. Windows: none** |
| Onboarding | None + guide | Checklist + video | **Best in class** | None | Permission-first | **Android: 5 unsolicited perms. Windows: none built** |
| Strictness vocabulary | 7 mechanism names | 1 boolean | 4 **intent** names | none | intervention types | 5 names, Android only; **default is a no-op** |
| Default strictness | continuous (unlocked) | off | **Gentle Guidance, stated** | n/a | breathing | "Ask me first" — **and it cannot be satisfied** |
| Recommended default published? | No | No | **Yes, with reasoning** | n/a | default intervention | No |
| Block screen explains *why* | Motivational quote | — | Customisable page | DNS-level, no page | Intervention | **Yes — best in class** |
| Exit at the moment of denial | **Yes**, on the block page | Yes, session card | **Yes**, break screen | No | n/a (the pause *is* the exit) | **Deliberately not** — defensible, but see §5.2 |
| Exit in-app | Yes | Yes | Yes | n/a | Yes | **Broken on a default install** |
| Anti-uninstall | Uninstaller fails | Full article | **Protect Uninstall** | Survives deletion | Anti-delete settings | Android device admin; Windows service + watchdog |
| Stats lead with | counts | history | **period-over-period** | none | time saved | **Comparison to a frozen baseline** |
| Streaks / gamification | No | **Explicitly refuses** | No | n/a | No | Streak shown — **but absent on day one** |

---

## 3. The Android information architecture as built

`MainActivity.kt:100-110` declares exactly **four** tabs:

| # | route | long label | visible label | icon | screen |
| :-- | :--- | :--- | :--- | :--- | :--- |
| 1 | `now` | Now | **Now** | `CheckCircle` | `NowScreen` |
| 2 | `schedule` | Plan | **Plan** | `Edit` | `ScheduleScreen` |
| 3 | `calendar` | Calendar | **Events** | `DateRange` | `CalendarScreen` |
| 4 | `settings` | Settings | **Settings** | `Settings` | `SettingsScreen` |

The bar reads **Now · Plan · Events · Settings**. This matches `UX-FLOWS.md:228` (*"the same four tabs"*) and `UX-FLOWS.md:130` (*"There is no Apps tab"*), and **contradicts `PLAN-mobile-polish.md:115` and `:122`, which both say "five tabs".** The docs disagree with each other; the code is four. [V]

The bar renders **only on tab destinations** (`:187-196`) — pushed screens (Timer, Profile, Usage, Devices, Health) get a back arrow instead. The reasoning is sound and documented (`:182-186`): a glass bar with no reserved space beneath it was covering the Timer's own primary button. Every tab screen ends with `Dsn.BottomRoom = 116.dp` to clear the float.

**One naming inconsistency:** `Tab.Calendar` has `route = "calendar"`, visible label **"Events"**, and a screen title reading *"Pick from your calendar"* (`CalendarScreen.kt:80`). It is also reachable from Settings' "Your calendar" row. One screen, three names.

Full navigation graph: `now` → `timer`; `schedule` → `profile/new` | `profile/{id}` | `calendar`; `settings` → `devices` | `health` | `usage` | `calendar`; plus `CalendarPickerSheet` and `AppPickerSheet` as full-screen dialogs from `ProfileEditScreen`. `usage`, `devices` and `health` have no onward links.

---

## 4. Where the docs and the build disagree

This is a finding in itself, because `UX-FLOWS.md:5-7` presents the file as *"the build list"* and *"Nothing here is aspirational UI."*

| `UX-FLOWS.md` claim | Reality | Citation |
| :--- | :--- | :--- |
| `:216` Settings has a **Simple/Power** two-tab tray | **Does not exist.** No mode field in `UiState`. `SettingsScreen.kt:33` even asserts in a comment that the screen leads with it | `CurfewViewModel.kt:806-878` |
| `:222` Power adds a next-24h timeline and "blocks kept" to Now | Rendered **unconditionally** | `NowScreen.kt:232-295, 421-427` |
| `:226` Power adds audit/raw-config/export to Settings | All exist, but on **Usage** (audit, export) and **Plan** (config editor) | `UsageScreen.kt:144-171`; `ScheduleScreen.kt:328-370` |
| `:58` **Gap 2** — accessibility asked from Health, not at step 6 | **Closed in code.** Asked on the Timer's own button | `TimerScreen.kt:246-249` |
| `:191` **Gap 6** — pre-Curfew baseline not implemented | **Closed in code.** Baseline captured once, never rewritten; comparison card exists | `CurfewRuntime.kt:522-553`; `UsageScreen.kt:64` |
| `:206` pairing shows "a QR code and **six words**" | Six **digits** | `DevicesScreen.kt:202-204` |
| `:35-36` dial preset to **30m**; three strengths | Default **90**; presets 25/50/90/180; **five** strengths | `TimerScreen.kt:39-40, 62-68` |
| `:40-41` first run offers **"Block everything distracting"** | A seeded profile named "Distractions" with nine installed apps | `CurfewRuntime.kt:571-585` |
| `:54` notifications "asked immediately after, by the system dialog" | Never proactively asked anywhere | `Permissions.kt:57-62` |
| `:164-168` five permission categories | **Ten** grants exposed | `Permissions.kt:39-102` |
| Calendar window: *"the next day and a half"* (`CalendarScreen.kt:186`) | 56 days | `CalendarReader.kt:55` |

**F-47 (P2).** The doc is stale in **both** directions — describing gaps that are closed and surfaces that were never built. Since it is the build list, that makes it neither a spec nor a status report.

---

## 5. Flow-by-flow audit

Findings are numbered **F-n**. Severity is UX severity — **P0** means a user cannot complete a core journey at all.

### 5.1 Flow 1 — First run to first block (target: 40 seconds)

**Verdict: BROKEN.** It works beautifully, then breaks at the exact moment the user complies.

What actually happens on a fresh install `[V]`:

1. `seedStarterProfile()` (`CurfewRuntime.kt:571-585`) silently creates a profile named **"Distractions"** with block rules for whichever of Instagram, TikTok, YouTube, Twitter, X, Facebook, Reddit, Snapchat and Netflix are installed (`STARTER_APPS`, `:755-765`). **Nothing on any screen says so.**
2. Now renders `Title("Nothing is blocked\nright now.")` + one `PrimaryButton("Start a block now")` (`NowScreen.kt:124-131, 182`). Matches the flow. **Good.**
3. Tap → **Timer**: a draggable 12-hour dial, presets, a profile row, five strengths, `"Lock it in for 1h 30m"`. **Two taps to a live block** when accessibility is already granted — comfortably inside the target. **Genuinely impressive.**
4. If accessibility is **not** granted — the realistic sideloaded case — the flow collapses.

`TimerScreen.kt:246-249` and `:287-318`
```kotlin
if (!Grant.Accessibility.isGranted(context)) {
    pending = id
    return@PrimaryButton
}
...
confirmButton = {
    TextButton(onClick = {
        pending = null
        Grant.Accessibility.settingsIntent(context)?.let { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }) { Text("Turn it on") }
},
dismissButton = {
    TextButton(onClick = {
        pending = null
        model.startTimer(id, minutes * 60, listOfNotNull(strength.lock))
        onDone()
    }) { Text("Start without it") }
},
```

**F-1 (P0). "Turn it on" — the primary, recommended action — never starts the timer.** It clears `pending` and launches Settings. There is no `LaunchedEffect`, `ResumeEffect` or lifecycle observer anywhere in the file (`git grep` for `LaunchedEffect|Lifecycle|onResume|ResumeEffect|DisposableEffect` over `TimerScreen.kt` returns only the `collectAsStateWithLifecycle` import and call). The user returns from granting the permission to find **no block and no record that they configured one.** `[V]`

Three things make this worse than an omission:

- **The primary path is the broken one.** A user who declines ("Start without it") gets a working block with no enforcement. A user who complies gets nothing.
- **The dialog's own copy is then false.** *"Without it the timer will run and nothing will be blocked"* (`:293-294`) describes the *dismiss* path.
- **It hits Flow 1 specifically.** With the permission detour, the honest count is **seven interactions and a Settings round-trip** — and the app behaves as if the first five never happened. On Android 13+ sideloaded, add a second excursion through App info → Allow restricted settings. `[R]`

Principle 4 is broken in the worst possible direction: nothing changed, and no receipt.

**F-2 (P2). The first run silently blocks nine apps and never mentions it.** `[V]`
The seeding rationale is sound and documented (`CurfewRuntime.kt:555-570`). But a self-binding tool that has already written a policy owes the user a sentence about it, and the profile is discoverable only by opening Plan. Fix: one line on the first Now render — *"We have set up a profile called Distractions, blocking Instagram, YouTube and 7 others. Change it on Plan."* The counts are already computed.

**F-3 (P2). The default duration is 90 minutes; the design says 30.** `[V]`
`TimerScreen.kt:40` `DEFAULT_MINUTES = 90`; `PRESETS = listOf(25, 50, 90, 180)`. A person who taps through without touching the dial commits **an hour and a half** on the default strength.

**F-4 (P1). `Lock.Confirm` never confirms anything — and it is the default strength.** `[V]`
`TimerScreen.kt:64` sells `Confirm("Ask me first", "One confirmation, so it is never an accident.")`, it is the default (`:88`), and it is offered in four more places (`ScheduleEditor.kt:54` "Ask before ending", `ProfileEditScreen.kt:512`, `:231`, `CalendarScreen.kt:509`). The ending path branches on `Challenge` only:

`NowScreen.kt:101-108`
```kotlin
fun end(session: Session) {
    val challenge = session.lock.conditions.filterIsInstance<Lock.Challenge>().firstOrNull()
    if (challenge == null) {
        finish(session, emptyList())
    } else {
        pending = PendingEnd(session, challenge, Challenge.generate(challenge.challenge))
    }
}
```
`Lock.Confirm` is not a `Lock.Challenge`, so it takes the `challenge == null` branch and is handed to the core with `satisfied = emptyList()`. A repository-wide grep for `Lock.Confirm` under `android/app/src/main` returns **six hits, all constructing the lock, none satisfying it.** The condition is *unmeetable* — the lock refuses forever, and the user is shown "Still locked. It needs a confirmation" with no control that can give one.

The user chose a label reading *"One confirmation, so it is never an accident"* and received **a lock with no early exit at all** — the inverse of invariant 2's *"except the unlock conditions the user chose."*

**F-5 (P1). The emergency pass is unreachable on a default install, and the copy claims it was spent.** `[V]` for code, `[R]` for the derived path.
`EmergencyPolicy.passes` defaults to **0** — `emergency.rs:35-38`: *"Zero — the default — disables the hatch entirely."* Nothing in either UI creates or enables one; the only route is hand-editing `curfew.toml`. Yet:

- `TimerScreen.kt:67` labels the strongest lock **`Locked("Until it ends", "No way out but an emergency pass.", Lock.Timer)`** — promising a hatch that is off.
- `BlockActivity.kt:291-299` prints, when `passes == 0`: **`"No emergency pass left this month"`**. The phrase *"left this month"* tells the user they **spent** a ration they were never given. The honest string exists at `Format.kt:125-127` and `BlockActivity` does not use it.
- `NowScreen.kt:586` **deliberately suppresses the explanation**: the line renders only when `passesLeft == 0 && passRefusal != null && passRefusal !is PassRefusal.Disabled` — i.e. it hides the one case a default user is in.

**The accurate version:** the **24-hour delayed release does work** for a Timer session (`NowScreen.kt:630` gates on `isLocked && delayedReleaseAt == null`). The universal exit holds. What is broken is the *second* exit and every sentence about it.

**F-6 (P1). The credential strength can be chosen on a phone with no screen lock.** `[R]`
`TimerScreen.kt:65` offers `Credential("Fingerprint or PIN", …)` with no check of `Auth.isAvailable(context)` — the exact predicate that answers it (`Auth.kt:38-39`). When the user later taps **End now**, `Auth.prove` finds no screen lock and calls `onResult(false)` without showing anything (`Auth.kt:55-60`), which the core answers with *"Still locked. It needs your screen lock."* **The user is told they need something their phone does not have, with no route to create it.** This is also a D7 vocabulary failure: the label says "Fingerprint" while `Auth.kt:35` restricts to `DEVICE_CREDENTIAL` — the user picks a finger and is asked for a PIN.

### 5.2 Flow 2 — Ending a block

**Verdict: reaches the right outcomes, then breaks principle 4 and the release promise.**

The three strengths are implemented and the credential path is carefully reasoned (`Auth` excludes biometrics per D7; on cancel it asks the core rather than deciding itself, `NowScreen.kt:84-95`). But:

**F-7 (P1). The irreversible 24-hour release is a single unguarded tap.** `[R]`
`NowScreen.kt:630-637`. The button reads "Ask to end in 24 hours" and fires `model.requestRelease(session)` immediately. `UX-FLOWS.md:73-74` explicitly requires *"it says so before it is started, not after."* Contrast the same class of action elsewhere, where the code is scrupulous: spending a pass gets a dialog with a full cost sentence (`:298-320`), releasing a peer gets one (`:322-344`).

**F-8 (P2). Ending an unlocked block raises an acknowledgement modal — against the app's own principle 4.** `[R]`
`CurfewViewModel.kt:387` calls `say("${session.profile} ended.")`, which raises an `AlertDialog` on Now. `UX-FLOWS.md:21-22`: *"Dialogs are for decisions, never for acknowledgements."* The receipt should be the dial going grey and the card disappearing. Not isolated — see F-29.

### 5.3 Flow 3 / 5 / 6 — schedules, profiles, permissions

**Verdict: the mechanics hold. The permission pattern is the strongest part of the product.**

`Permissions.kt` implements a four-tier preference order — system dialog → deep link to Curfew's own row → that permission's settings page → App info as an always-resolving fallback — with each grant carrying a `because` (why) and a `cost` (what is lost). Better than one sec (two permissions up front before app selection), and better than FocusMe or Cold Turkey (extension nag during setup).

**F-9 (P1). The profile-scoped calendar picker writes a rule on the first tap, with no confirmation and no scope choice.** `[R]`
`CalendarScreen.kt:497-511`. Everywhere else, a durable write is either immediate-*and-legible* (a toggle: the row changes) or confirmed. This is neither. Worse, the **scope** — the very question Flow 4 step 3 says must be asked ("just this event" vs "every event whose title matches") — is decided silently as *"every event with this exact title, forever."* A user who taps one lecture blocks every lecture with that title and is never told. The two entry points also disagree: the Events tab opens a proper `CalendarDialog`; the profile sheet does not.

**F-34 (P2). Two rows promise a path they do not implement.** `[R]`
- `ScheduleScreen.kt:269-281`: *"Nothing starts it yet — Pick a meeting, or set a schedule"* opens **only the calendar picker**.
- `ProfileEditScreen.kt:188-194`: "Always on" tells the user to go and add a full-week window — which requires leaving the profile, opening Plan's window sheet, and re-selecting the profile from a pill row. `UX-FLOWS.md:130-132` states the reason Apps was removed: *"a separate tab meant leaving the profile you were half way through building."* **The same objection applies verbatim.**

**F-35 (P2). "A repeating schedule" is tappable and does nothing.** `[R]`
`ProfileEditScreen.kt:205-210` has an explicit `Unit` branch behind a `clickable`. The comment is true and the design is reasonable, but a row with a **✓** that does nothing when tapped is indistinguishable from a broken button.

**F-33 (P1). Settings' Fix buttons can throw where Health's are hardened.** `[R]`
`SettingsScreen.kt:102-104` calls `context.startActivity(...)` bare. Its near-twin at `HealthScreen.kt:144-153` wraps the same call in `runCatching { … }.onFailure { appInfoIntent }` with a comment saying exactly why (*"an ActivityNotFoundException here would kill the one screen whose job is to fix permissions"*). Only one of the two is hardened.

### 5.4 Flow 9 — Simple ↔ Power

**Verdict: DOES NOT EXIST.**

`git grep -rn -i "powerMode|simpleMode|isPower|Mode.Simple|Mode.Power"` across `android/app/src/main/java` returns **nothing**. There is no mode field in `UiState`, no switch, no divergence. Meanwhile `SettingsScreen.kt:33` carries a comment claiming the screen *"leads with the Simple/Power switch"* (`[R]`), and `MainActivity.kt:112` refers to "Simple" as if it exists.

**F-11 (P1).** Two of the product's stated principles — 5, and *"Simple mode is the default and is not a crippled build"* (`PLAN-mobile-polish.md:49`) — describe a feature that was designed, documented and never built, while a screen comment claims it is the first thing Settings shows.

**The substantive half is real and good:** `MainActivity.kt:94` says *"Usage, Health and Devices are one tap away in Settings instead"* — and they are. The *de-cluttering* goal of Power mode was achieved by deleting the tabs rather than hiding them. That is a defensible outcome; it needs the docs to say so.

### 5.5 Flow 7 — Seeing that it is working

**Verdict: the best-executed flow in the product, with one day-one hole.**

`UsageScreen.kt:55-95` leads with a **comparison** — `Title("Where your time went")`, then `ComparisonCard` before anything else — then a streak, then day bars, then per-app detail. The empty state is honest and specific: *"Nothing counted yet. Time is only measured for targets a budget or a launch limit actually covers — Curfew does not keep a record of everything you open."* (`:89-92`)

`CurfewRuntime.kt:509-519` documents the pre-Curfew baseline: taken once, on the first launch with usage access, *"never rewritten afterwards"*, because *"a baseline that moved with recent behaviour would compare the app against itself and always flatter it."* That is a **more rigorous position than any competitor's stats screen** — FocusMe leads with period-over-period tiles, but nothing in the corpus freezes its own baseline by rule. **Gap 6 is stale: it is implemented.** `[V]`

**F-12 (P2). A first-day user sees no evidence line at all.** `[R]`
`NowScreen.kt:398` — `GivenBackCard` returns early when `today == 0 && week == 0L && currentStreak == 0`. The card vanishes entirely, so Now is a dial, "Nothing is blocked right now.", and a timeline. The doc's central claim — *"the number that says 'this is working' is not allowed to live behind a tab"* (`:181-182`) — is unmet **precisely when the user is deciding whether to keep the app.** The honest zero copy already exists one branch down at `:402`.

**F-13 (P2). The absence of the comparison is unexplained.** `[R]`
`UsageScreen.kt:64` renders `ComparisonCard` only when `state.screenTime` is non-null, which requires usage access **and** a whole day on each side (`CurfewRuntime.kt:539-546`). Nothing says either thing. Fix: a placeholder naming the missing prerequisite.

### 5.6 Flow 8 — Two devices

**Verdict: holds, and the state copy is excellent — with one dangerous control.**

`SettingsScreen.kt:112-147` states sync truth in a five-way `when` (not set up / none paired / paired but not listening / none in earshot / N of M in earshot). `DevicesScreen.kt:276-289` distinguishes active-on-network, active-elsewhere and removed. That is better state honesty than any competitor reviewed.

**F-14 (P1). Revoking the last peer can delete a lock's designed exit, with no warning and no visible result.** `[R]`
`DevicesScreen.kt:290-292` renders `Tap("Remove this device", Palette.Bad) { model.revokeDevice(peer.id) }` — immediate, unconfirmed. The copy below says *"Removing it does not end any block it started here"* — true, but not the consequence that matters: `Lock.PeerRelease` requires another device to release (`Format.kt:112`), so removing the last peer converts a peer-held lock into one **whose intended exit no longer exists**. And the action's own confirmation is invisible: `revokeDevice` calls `say("That device will be ignored from now on.")` (`CurfewViewModel.kt:333`), which Devices never renders. The user's experience is a tap that appears to do nothing.

**F-15 (P3). The pairing copy disagrees with itself.** `UX-FLOWS.md:206` promises **"six words"**; `DevicesScreen.kt:202-204` renders and describes **six digits**. This matters because the security argument — *"nothing in the app can check that two screens showed the same digits"* — depends on the user understanding what they are comparing.

### 5.7 Windows: the flows do not exist

The largest structural finding in the report.

**F-16 (P0). The Windows GUI cannot start a block. The product's central verb is unavailable without a terminal.** `[V]`

`Request::Start` — the message that begins a session — has exactly **one non-test sender in the entire repository**:
```
$ git grep -rn "Request::Start" -- crates/
crates/curfew-svc/src/main.rs:627   ← the only producer
crates/curfew-win/src/tick.rs:400   ← the handler
```
The Curfew window (`app.html`, 528 lines) sends exactly five requests — `status`, `end`, `unlock`, `release`, `emergency` (`:449-521`). A grep for "start" across `app.html` finds only `padStart`, `start_minute`, `started_at` and *"When Curfew starts a session by itself."* The tray's `Item` enum (`menu.rs:11-47`) has **no `Start` variant**. So on Windows:

- The only way to begin a block by hand is `curfew start <profile> <minutes> [--credential]` in a terminal (`main.rs:605-627`).
- Everything else is schedule-driven — weekly windows and calendar rules.
- The intended answer was **`design/win/Setup.dc.html`**, a first-run screen that was never built.

The CLI is well-written — it warns *before* starting a locked session, which is exactly right:
> *"Starting a locked session for 60 minutes. It will need your Windows password to end early, and if you cannot use it, the 24-hour release is the way out."* (`main.rs:622-625`)

But a terminal command is not an interaction design. Compare: Freedom's desktop flow is a **taskbar popover** that starts a session in the vendor's own words *"about 10 seconds"*; FocusMe's Quick Setup is two questions; Cold Turkey's Blocks tab is a toggle. **Curfew's Windows "first block" journey is: install an MSI, open a terminal, know the profile id, know the flag syntax, and type a duration in minutes.** `[R]` counts ≈8–10 deliberate actions including two SmartScreen steps and a UAC prompt.

`UX-FLOWS.md` Flow 1 describes "Now → Start a block now → Timer → Lock it in" and never says it is Android-only — and the doc presents itself as *the app's* flows.

**F-17 (P0). The documented first block applies to a file the service never reads.** `[R]`
The service's config is `%ProgramData%\Curfew\curfew.toml` (`runner.rs:34-37`, `app.rs:39-42`), but `README.md:44-55`, `docs/index.html:161-163` and the `curfew run` "Using it" section all use a **cwd-relative `curfew.toml`**. Following the README verbatim edits a file the service never reads, and `curfew schedules curfew.toml` then fails outright on an installed machine. Nothing reports the discrepancy.

**F-18 (P0). Pairing — the advertised headline feature — has no Windows front door.** `[R]`
`docs/index.html:117-119` leads with *"Blocks on both devices at once"*; `README.md:51-53` describes *"Devices → Pair"*. On Windows there is no `pair` verb, no Devices page (the nav is four items, `app.html:63-66`), no tray item, and no artboard for it in the nine Windows designs.

**F-19 (P1). The nav is 6 pages in the design and 4 in the build.** `[V]`
Every `design/win/*.dc.html` carrying a nav renders **six**: Now · Plan · Apps & sites · **Where time went** · **Devices** · Is it working. The build (`app.html:63-66`) has **four**. Both exist on Android. **On Windows the only route to the user's own usage data is `curfew stats` in a terminal.**

**F-20 (P1). A wrong Windows password fails completely silently.** `[R]`
`app.html:504-511` handles only `response === "refused"`, but a rejected password returns `Response::Error` (`tick.rs:472-480`). The sheet closes and nothing appears. The same swallowing affects `emergency` and `release` (`:512-521`). A user typing a wrong password concludes the button is broken. The tray handles the identical case correctly (`shell.rs:299`).

**F-21 (P1). A blocked website with no extension shows the browser's own error page.** `[R]`
`hosts.rs:26` maps blocked domains to `0.0.0.0`, and `dns.rs:114` documents the choice deliberately (*"`0.0.0.0` rather than NXDOMAIN, and rather than a page of our own"*). The user gets `ERR_CONNECTION_REFUSED`: **no Curfew surface, no reason, no exit** — from the user's point of view the internet broke. This is the largest population of users (domain rules, extension not installed) and it gets the least explained refusal, the exact failure `overlay.rs:3-6` says it exists to prevent. It is also the *opposite* of Curfew's own excellent Android block screen.

**F-22 (P1). No feedback anywhere that a block has started.** `[R]`
`report(Response::Ok)` is silent (`main.rs:460-461`); the tray has no toast or balloon (`shell.rs:100-104` uses `NIF_ICON|NIF_MESSAGE|NIF_TIP` only); no session-start notice exists (`overlay::show` fires only for freeze, wait, close and welcome).

**F-23 (P1). Editing the config does not take effect, and the UI claims it does.** `[R]`
The service reads the config only at start or on `Request::Reload` (`tick.rs:619`); no edit command sends `Reload`. `add-source` even prints *"The service picks it up on its next reload"* (`schedule.rs:724`) without naming `curfew reload`. Yet `app.html:348` claims the Plan page is *"what the service is enforcing"* — and the window reads the file, so it shows the new window while the service still enforces the old one.

**F-24 (P2). Two opposite trust stories for the same unlock.** `[R]`
The window asks for the Windows password in **its own HTML form** (`app.html:473-481`) while the tray deliberately uses the OS credential dialog and documents why (`prompt.rs:1-15`: *"Curfew never draws a password box of its own… a user can tell it from a phishing box drawn by an application"*). The bigger, more prominent surface is the one that breaks the rule.

**F-25 (P1). The window's navigation is mouse-only.** `[V]`
`.nav` and `.tab` are `div`/`span` with click handlers — no `tabindex`, no `role`, no key handler (`app.html:62-72, 379-384`) — and the modal has no `role="dialog"`, no focus trap, no Escape handler (`:439`). `user-select:none` is global (`:15`) with no exception for content, so a user cannot copy the config path or the command the Plan page tells them to run (`:348-349`). A keyboard-only user cannot reach the pages that explain what is blocked — but *can* tab to "End it early".

**F-26 (P1). A freeze is shown in the window with no way to cancel it.** `[R]`
`app.html:299` renders *"A freeze is counting down"* as a pill with no duration. Cancel exists only in the tray (`menu.rs:102`) and `curfew cancel`. The window tells the user about an imminent whole-machine freeze and offers no action — while the tray's own design puts *"Cancel the freeze"* first (`menu.rs:85-86`).

**F-27 (P2). The tray menu does not open when the service is unreachable.** `[R]`
`shell.rs:199-210` calls `say(...)` and returns. So *"What is blocked…"*, *"Why Windows warned about this…"* and *"Hide this icon"* are unreachable exactly when the user is trying to diagnose a problem. The card's own copy is excellent (`main.rs:38-72`); it just cannot be reached from the menu.

**F-28 (P2). The profile **id** is printed where the name belongs.** `[R]`
`overlay.rs:63` composes *"…is blocked during {profile}"* from `Session.profile`, which is the **id**. The starter config's id is `"distractions"`, so the Windows overlay says *"Steam is blocked during distractions."* where the Android block screen says *"Distractions"*. The tray tooltip and menu do the same (`menu.rs:111`, `144`), and so does the extension (`extension.rs:228`) and the CLI. **Only the window resolves the name** (`app.html:238-241`).

### 5.8 Cross-cutting: the message surface

**F-29 (P1). `UiState.message` is set from eight places and rendered on two.** `[R]`

The single most damaging defect in the Android UI. The message surface is a modal `AlertDialog` present only in `NowScreen.kt:369-375` and `ProfileEditScreen.kt:366-372`. Every one of these raises it from somewhere else:

| Raised by | User is on | Visible? |
| :--- | :--- | :--- |
| `syncNow` ("Up to date." / "Took up N block(s)…") | Settings / Devices | ✗ |
| `setBlockedApps` failure | Apps & Websites sheet | ✗ |
| `copyBlocksFrom` failure | Apps & Websites sheet | ✗ |
| `saveWeekly` / `saveCalendarRule` failure | Plan sheet | ✗ |
| `deleteWeekly` / `deleteCalendarRule` failure | Plan | ✗ |
| `revokeDevice` | Devices | ✗ |
| `deleteProfile` failure | **Now, after popping the profile** | ✓ by accident — on the wrong screen |

The experience: tap something, watch nothing happen, receive no explanation. Because `MainActivity` is `singleTask`, unexplained modals also *accumulate* and surface later on Now. **This one fix resolves F-14, part of F-32, and every import/export/sync failure in a single change.**

### 5.9 Cross-cutting: unreachable and dishonest states

**F-30 (P1). A config that will not parse looks exactly like an empty config — and Save would overwrite it.** `[R]`
`CurfewViewModel.kt:190,199` wraps both reads in `runCatching{}.getOrDefault("")`. `CurfewRuntime.kt:780-783` has a deliberate fallback for the unreadable case, but **nothing on any screen distinguishes "you have not set anything up" from "your config could not be read."** The user sees new-user copy on Plan, an empty Now, and an empty code editor — and pressing Save on that editor runs `saveConfig("")`, **destroying their real config.** Against `ARCHITECTURE.md:189-191` and `GAPS E3`.

**F-31 (P1). A failed calendar-provider read renders as "your calendar is empty".** `[R]`
`CurfewViewModel.kt:145-146,196` swallows the exception. Three different situations — permission granted but the provider threw, provider returned nothing, diary genuinely clear — render the same sentence, which asserts the third. Precisely the silent failure `ARCHITECTURE.md:188-190` forbids.

**F-32 (P1). Delete-profile has no confirmation, and its routine refusal is never read.** `[R]`
`ProfileEditScreen.kt:318-327` deletes on a single tap and calls `onDone()` immediately — success or not. But `deleteProfile` is **routinely refused** (`CurfewRuntime.kt:292-303`: *"Refused while a schedule still names it"*, with good copy naming the schedules). That refusal goes to `say(...)` and the screen has already popped, so **the user lands on Plan, the profile is still there, and the app has said nothing.** Every other destructive action in the app is confirmed with the consequence named.

**F-36 (P2). The audit list renders internal event names.** `[R]`
`UsageScreen.kt:273-281` falls back to `"$kind $detail"`. At least ten kinds the runtime writes are not named in the `when` (`release.given`, `pass.spent`, `sync.adopted`, `sync.refused`, `sync.failed`, `profile.saved`, `rule.saved`, …), so a user sees `sync.failed timeout` in a section whose stated purpose is to be checkable.

**F-37 (P3). Dead code with good copy in it.** `[R]`
`AppPickerScreen`'s unpinned half — the profile pill row and the cross-profile comparison line — can never render, because `AppPickerScreen(model, pinned = profileId)` is the only call site (`:579`). Plus `WeeklyCard` and `CalendarRuleCard` (`ScheduleEditor.kt:127,163`), `Step` (`TimerScreen.kt:361`), and `strings.xml:16` `block_open_curfew` ("Open Curfew") which is **defined and never rendered** — the block screen's only button is "Back to my home screen".

---

## 6. Design consistency and craft

### 6.1 What is genuinely better than the competition

Stated first, because a review of only deficits is not usable and because these are real advantages.

**The block screen is the best in the category.** `design/Blocked.dc.html:48-56` and the build agree:

> **"Instagram is blocked"** → *"Deep work is running until **22:00**, because your calendar says Graph Machine Learning."* → a large **1:12** countdown → **"Back to my home screen"**

It names the app, the profile, the end time **and the reason**. Cold Turkey shows a motivational quote; FocusMe a customisable page; Freedom's and SelfControl's are undocumented; Apple and Google show nothing. Curfew's is the only one that explains *which rule fired and why*. `explain()` (`BlockActivity.kt:137-149`) covers every `BlockReason` in plain language, including *"You have used all 15 minutes of your time here today."*

**The refusal dialect.** `Format.kt:104-115` `describeLock`; `NowScreen.kt:599-606` (the restart condition, which explains that *"Force-stopping Curfew or reopening it is not a restart"*); `NowScreen.kt:672-691` `RefusalDialog`; `Format.kt:124-132` `describePassRefusal` — every branch but `Disabled` carries a *when*, with a comment explaining that is the point. `CurfewRuntime.kt:578` — *"Removed. A session it already started keeps running."* `ScheduleScreen.kt:439-443` anticipates the wrong expectation: *"If you only want it off for a while, use the switch instead."* **Principle 3 is honoured here better than in any competitor.**

**The permission pattern.** Four-tier preference order, every grant carrying a `because` and a `cost`. The notification-access row even volunteers the downside: *"Granting it lets Curfew see every notification on the device, so leave it off unless you use a mute rule."* (`Permissions.kt:78`)

**`DragDial` is a real physical control.** `Design.kt:397-410` reasons about the failure modes it avoids: laps so a minute is six degrees of arc rather than half a degree; **no wrap-around** (*"a timer that silently jumps from twelve hours to one minute under a thumb is a timer nobody trusts"*); haptic feedback on every whole-minute crossing; a visible handle drawn *at the value*. The `shortestTurn` seam handling (`:522-535`) is the detail that normally ships broken.

**Accessibility is taken seriously where it is easy to forget.** `mergeDescendants` with one assembled sentence for permission and app rows; per-session button labels so a list of "End now" buttons is usable with a screen reader (`NowScreen.kt:620-627`); `contentDescription = ""` on the one-second countdown (`BlockActivity.kt:261`); a `Polite` live region on the delay screen (`:381-388`); the multiplication sign spoken as "times" (`ChallengeDialog.kt:69-72`); and `ChallengeDialog`'s `NoToolbar` (`:108-119`) making the typing challenge genuinely un-pasteable — a rare case of an accessibility API used correctly for a security purpose.

**`Theme.kt:19-25` refuses dynamic colour, with the right reason:** *"a dynamic accent means the colour that says a block is running is whatever the user's wallpaper happened to be."*

### 6.2 Design craft findings

**F-38 (P1). The primary navigation has the smallest touch targets in the app.** `[V]` + WCAG.
`MainActivity.kt:249-285`: the tab's clickable area is `padding(horizontal = 10.dp, vertical = 4.dp)` around a 30dp icon plus a 10sp label — roughly **38dp tall**. WCAG 2.2 SC 2.5.8 requires **24×24 CSS px** minimum (Level AA), and the W3C recommends aiming higher for important controls, with SC 2.5.5 at 44×44 [fetched: w3.org/WAI/WCAG22/Understanding/target-size-minimum.html]. Material's own guidance is 48dp. Other undersized controls: `Switch` 26dp (`Design.kt:327`), Fix/Grant buttons 32dp, `Tap` 34dp, day circles 36dp — against the app's own `PrimaryButton` at 52dp and `GhostButton` at 46dp. **The system is internally consistent about spacing and inconsistent about hit area**, and the nav is the control every user touches every session.
`GlassTab` also sets `indication = null` (`:257-261`), so the primary nav has **no touch feedback at all**.

**F-39 (P2). `DSheet` is the only surface without the app's own glass.** `[R]`
`Design.kt:585` is a flat opaque `Palette.Surface`, while the nav bar (`MainActivity.kt:224-238`) and block screen (`BlockActivity.kt:266-288`) both get the two-layer glass. `PLAN-mobile-polish.md:59-64` asked for *"the same treatment on sheets and the block screen, so the app has one material."* The block screen got it; the sheets did not. **The app's hand-built sheet therefore looks less like the app's own material than the Material dialogs do.**

**F-40 (P2). `DSheet` exists specifically to avoid Material, and NowScreen uses Material for all eight of its dialogs.** `[R]`
`Design.kt:555-566` explains the reason at length: Material dialogs *"came from a theme this app otherwise never shows… the form read as a screen borrowed from another application."* Yet `NowScreen.kt` has eight `AlertDialog`s (`:299, :323, :358, :370, :685, :755`), and `ScheduleScreen` has five. Because `CurfewTheme` maps Material's `surfaceContainer*` to translucent `Palette` colours (`Theme.kt:68-74`) they do not look *wrong* — but there are two visual languages for corners, buttons, typography and elevation on the screen the user sees most.

**F-41 (P2). Four duration formats and three clock formats.** `[R]`
`spellDuration` ("1h 30m") · `duration` ("1 hr 30 min") · `countdown` on Now ("1:12", "14m") · `countdown` on the block screen ("1:12" above an hour, **"14:00"** below — a *different* rule). So the same remaining duration reads "14m" on Now and "14:00" on the block screen. Meanwhile `clockOf`/`clock`/`clockTime` are three HH:mm implementations, two hardcoded 24-hour. The schedule editor's hardcoding is deliberate and *justified* (*"this string is also what gets typed back in"*, `ScheduleEditor.kt:59-64`); the Now dial has no such excuse, so a user on a 12-hour locale sees "13:30" next to a stats card reading "1 hr 30 min".

**F-42 (P2). Amber's documented meaning is broken in three places.** `[R]`
`Theme.kt:26`: *"[Live] means a block is running right now. It appears when one is, and never otherwise."* Violations: `DragDial` defaults to amber so the **setup** screen is amber while nothing runs (`TimerScreen.kt:107`); `ProfileEditScreen.kt:167-172` tints an idle trigger row amber; and `DevicesScreen.kt:152` / `SettingsScreen.kt:134` colour a **neutral** sync line amber (*"Paired, but not listening right now."*), which reads as "something is running".
Positively, amber *is* used correctly where it matters most: the running dial, `Pill("Blocking now")`, the session card, the block screen's gradient, the ENFORCING dot, the notification and the tile.

**F-44 (P2). `Welcome` and `Setup` are designed and unbuilt.** `[V]`
`design/win/Welcome.dc.html` is two icon cards with bold leads and **Got it** / **Read the notes** buttons. The shipped version (`welcome.rs:22-29`, shown as one `say()` call at `shell.rs:459-460`) is **a single dense Win32 message box** containing the same words as four paragraphs. The design also had a **Dismiss** button and a *"Closes on its own in 7 seconds"* caption on the overlay (`Overlay.dc.html:50-51`) that the GDI card dropped — and the first-run card's dwell is `7000 + chars*55` capped at 40 s, so `WELCOME` sits for the full **40 seconds with no way to acknowledge it early**. The *words* survived; the shape did not.

### 6.3 The design canvas is the source of truth, and it is drifting

**F-43 (P2). The canvas is out of sync with itself and with the build.** `[V]`
- **`canvas.json` lists 18 phone artboards; 19 exist on disk.** `EditWindow.dc.html` is an **orphan** — absent from the canvas, therefore invisible to anyone reviewing the design.
- **`design/_parts.py` and `design/_base.css` are 0-byte files referenced nowhere.**
- `design/build.py` regenerates the artboards; the committed `Plan.dc.html` is not reproducible from it.
- The Windows canvas specifies **six** nav pages; the build has four (F-19).
- Nine Windows artboards exist; `Setup` (onboarding), `Tray`, `Welcome` and `Overlay` are all partially or wholly unbuilt.

**A finding from the earlier report is REFUTED.** `DESIGN_AND_CODE_REVIEW_FULL.md` §8 P3 claims `design/_tok.txt` says 22/14 while the shipped UI uses 14/9, "so the app cannot look like its own System artboard." **That is wrong.** I checked every artboard: **all 19 phone artboards use `--r-card:22px; --r-ctl:14px`**, and **all 9 Windows artboards use `--r-card:14px; --r-ctl:9px`.** `app.html:10` uses 14/9 (correct for Windows); `Design.kt:53-54` uses 22.dp/14.dp (correct for Android). The tokens are **clean per platform** — the design system is *more* disciplined than that finding claimed. The only artifact that is actually misleading is `design/_tok.txt`, which holds the *phone* values under a shared-sounding name. **Corrected in `DESIGN_AND_CODE_REVIEW_FULL.md`.**

### 6.4 Copy and localisation

**F-45 (P2). `strings.xml` is effectively unused.** `[R]`
`strings.xml` holds **12** strings; `grep -rn "R.string\.|stringResource" android/app/src/main` returns **6** hits — all consumed by non-Compose surfaces (manifest labels, notification channel, tile). `Grep getString(` across `ui/` returns **zero** app-copy calls. Compose screens contain at least **122** inline string literals in text positions. `GAPS.md:191` E6 requires externalisation *"from day one."*

The irony is sharp: the accessibility service *description* is in `strings.xml` and is excellent, while the permission table's `because`/`cost` copy and every empty state are literals. **Curfew's copy is good; it is simply not translatable.**

**F-46 (P1). Three product claims are factually false in this build.** `[V]`/`[R]`

1. **"Curfew has no internet permission at all."** — `SettingsScreen.kt:207`, `HealthScreen.kt:183` (*"so nothing it records can leave this device even if it wanted to"*), `UsageScreen.kt:41`. `AndroidManifest.xml:54` declares `android.permission.INTERNET`, required by the mDNS sync path. The manifest's own comment says the opposite (`:7`). **This is the most damaging copy finding in the review**: it is the app's core privacy promise, on the screen a user visits to check that promise, and a user can falsify it in ten seconds in Android Settings. Everything else on the Health screen — encryption at rest, no telemetry, local-only — is **true**, and this is the one claim that costs the rest their credibility. Fix: state the true and still-strong claim — *"Curfew never talks to a server. No account, nothing sent anywhere; the only network traffic is to devices you paired yourself, on your own network"* — which `DevicesScreen.kt:78-79` already says correctly.
2. **"No emergency pass left this month."** — `BlockActivity.kt:295`. See F-5.
3. **"One confirmation, so it is never an accident."** / "Ask before ending" — `TimerScreen.kt:64`, `ScheduleEditor.kt:54`. See F-4.

**Plus raw machine output shown to users in four places** on Windows: `app.html:410` renders `{"refusal":"quota_spent","next_at":1788510600}` as the detail under "No emergency pass right now"; `:460` renders *"The service said {"response":"error",…}"*; `:519` and `:169` likewise. And `main.rs:513` prints TOML section names to an end user — *"Turn them on in curfew.toml under [emergency]"* — with a run of ~14 literal spaces mid-sentence from a source-formatting artefact.

**F-48 (P1). The app's best first-run asset is buried — and its central sentence is false.** `[R]`
The "nothing leaves the device" card — the single strongest thing the app can say to someone deciding whether to trust a screen-watching tool — lives on Settings (`:203-214`) and Health (`:179-191`), not at the moment of first run.

**What the copy gets right** — preserve, do not refactor away: `Format.kt:104-115`; `NowScreen.kt:599-606`, `:672-691`; `Format.kt:124-132`; `CurfewRuntime.kt:578`; `ScheduleScreen.kt:439-443`; `UsageScreen.kt:87-96` (the best empty state in the app, because it explains the *scope* of what is not measured); `Permissions.kt:78`; `main.rs:441-443` and `:531-551`; the tray's unreachable-service card (`main.rs:38-72`, with tests asserting no errno and no markdown leak at `:174-190`); and `INSTALL.txt:88-90` — *"The release takes 24 hours. Nothing shortens it — not reinstalling Windows, not deleting the files, not the developer."* I found **no string that scolds the user.** Even the clock-tamper banner explains rather than accuses: *"If the clock is genuinely wrong, fixing it will not shorten a running lock."*

---

## 7. The first-run experience, end to end

### 7.1 Android — what actually happens

There is **no onboarding screen**. `MainActivity.kt:70-82` goes straight to `CurfewApp()` → Now. Traced literally:

**t=0.** `onCreate` calls `EnforcementService.start`, composes the theme; `CurfewViewModel.init` launches `restore()` then `refresh()`.

**t≈0 — the invisible seeding.** `seedStarterProfile()` creates the "Distractions" profile with rules for whichever of nine apps are installed, writes `curfew.toml`, reconciles — so a session could already be running — and calls `captureBaseline(now)`, which silently does nothing without usage access. **None of this is announced.** [F-2]

**Screen 1 — Now.** Before the first `refresh()` returns, `loading == true` (`CurfewViewModel.kt:808`) and **Now never reads that flag** — so the first painted frame asserts *"Nothing is blocked right now."* `[R]` Below: the Upcoming card, three status pills, and **Check the schedules now**. No stats card (F-12).

**Permissions asked, and when:**

| Permission | Asked at | Mechanism |
| :--- | :--- | :--- |
| Accessibility | **Only** on the Timer's primary button | Dialog → `ACCESSIBILITY_DETAILS_SETTINGS` |
| Notifications | **Never proactively** — despite `UX-FLOWS.md:54` saying "asked immediately after, by the system dialog" | A `Fix` row the user must find |
| Everything else (usage, overlay, notification access, exact alarms, battery, calendar, device admin) | **Never proactively** | Settings/Health rows |

Principle 2 — *"never ask before the thing that needs it"* — is honoured **literally and to a fault.** Nothing asks, so a user who does not go looking never grants usage access, and the Usage comparison, budgets and screen-time card stay dark forever with no prompt. Meanwhile the one ask that *is* made is the one that loses the user's configuration (F-1).

**Taps to first working block:**
- **Accessibility already enabled: 2 taps** (Start a block now → Lock it in). Well inside target.
- **Accessibility not enabled: 7 interactions plus a Settings round-trip**, and the configured timer is lost (F-1). On Android 13+ sideloaded, add a second excursion through App info → Allow restricted settings.

### 7.2 Windows — there is no first-run experience

`[R]` From MSI to first block: download → SmartScreen *"Windows protected your PC"* → More info → Run anyway → UAC → EULA page (AGPL full text) → Install → Finish. **No launch checkbox, no "what next" text, and `INSTALL.txt` is not installed.** Then `RegisterService` runs `curfew.exe install` deferred and elevated, writing the starter config and registering the service with three restart actions; `StartTray` launches the tray for the installing user.

The tray then shows **one card** — the welcome notice, 40 seconds, no buttons — containing the SmartScreen explanation and the "this icon is not the blocker" statement as four paragraphs. `design/win/Welcome.dc.html` had already designed this as two icon cards with a **Got it** button; only the string was ported.

Then: **no way to start a block** (F-16), **the README tells you to edit a file the service does not read** (F-17), **no Devices page** (F-18), **no usage page** (F-19). To get a block the user must know (a) the verb, (b) a profile id, and (c) that a profile already exists.

**F-49 (P1). The two platforms are not one product.** Android has a Timer screen, a Usage screen, a Devices screen and a permission model. Windows has none of those. Windows has a tray overlay and a freeze countdown. The shared parts are the core and the config format; the *interaction model* is disjoint — and the design canvas, which has both a phone set and a Windows set, shows that this was not the intent.

**Which surface can do what** `[R]`:

| Concept | Window | Tray | CLI | Extension |
| :--- | :--- | :--- | :--- | :--- |
| Start a block | **—** | **—** | `curfew start` | — |
| End early | always drawn, may be refused | only when it will succeed | `curfew end <id>` | — |
| Password unlock | own HTML form | OS credential dialog | refused by design | — |
| 24-hour release | only if this device is named | yes + Yes/No confirm | `curfew release` | — |
| Extend a block | **—** | **—** | **—** | — |
| Make/pause a schedule | read-only + a CLI note | — | `add-window` (no pause flag) | — |
| Pair a device | **—** | **—** | **—** | — |
| Where time went | **—** (designed) | — | `curfew stats` (hashes in a terminal) | — |
| Profile naming | display name | raw **id** | **id** | **id** |

Four partial front doors: the tray is the most visible and least capable; the CLI is the most capable and least discoverable; the window is the most legible and most inert. **The window's only unique capability is a one-second live countdown.**

---

## 8. Recommendations

Ordered by what a real user hits first. Fixes are deliberately small where the design is already right.

**P0 — these break a core journey today.**

1. **F-1 — start the timer after the accessibility detour.** `TimerScreen.kt`: keep `pending` and re-attempt on resume via `LifecycleResumeEffect`, re-reading `Grant.Accessibility.isGranted`. **One `LaunchedEffect`.** It converts the worst first-run experience in the app into the best, and it is the single highest-value change in this report.
2. **F-16 — give Windows a way to start a block that is not a terminal.** The design already chose: build `design/win/Setup.dc.html` for first run, and add a **Start** affordance to the window's Now page plus a tray item. `Request::Start` already exists (`tick.rs:400`); only the affordance is missing. Until this lands, `UX-FLOWS.md` Flow 1 is fiction on Windows and the product's central verb is unavailable to anyone who does not use a shell.
3. **F-17 — fix the README's config path.** One line, and without it the documented first block silently does nothing.
4. **F-18 — give pairing a Windows surface**, or stop advertising it as the first feature on the website.
5. **F-4 — make `Lock.Confirm` confirm.** Branch on it in `NowScreen.end()` and satisfy it from a dialog. The default strength is currently a lock with no exit, contradicting the label the user chose.

**P1 — the product's own promises.**

6. **F-29 — one app-level message surface.** A snackbar host in `CurfewApp` (`MainActivity.kt:146`) reading `state.message`, replacing the two screen-local dialogs. **One change fixes the silent no-op class wholesale** — delete-profile, sync, revoke, import, export, weekly/rule saves.
7. **F-30 — carry a `configError` in `UiState`**, render it at the top of Plan with the file path, and **disable Save while it is set.** Today an unreadable config is indistinguishable from an empty one, and saving destroys the real one.
8. **F-31 — distinguish a failed calendar read from an empty diary.** Three states, one sentence today.
9. **F-5 — stop lying about the emergency pass.** Fix `BlockActivity`'s zero-case to *"Emergency passes are not switched on"* (`Format.kt:125-127` already has the wording), drop the `!is Disabled` suppression at `NowScreen.kt:586`, and either default `passes` non-zero or change `TimerScreen.kt:67`'s note.
10. **F-32 + F-7 + F-14 — confirm the three irreversible actions.** Delete-profile (and wait for its result before navigating), the 24-hour release, and peer revoke (naming that it can remove a lock's exit).
11. **F-38 — raise the nav bar, Switch and Fix to 48dp and restore tab feedback.** The primary navigation is currently the hardest thing in the app to hit; the icons can stay 30dp inside a larger hit area.
12. **F-46 — tell the truth about `INTERNET`.** One string, three screens. The cheapest trust repair available, and the most important.
13. **F-33 — lift `HealthScreen`'s guarded dispatch into `Permissions.kt`** and call it from Settings too.
14. **F-19 / F-6 — build the two missing Windows screens**, or explicitly retire them from the canvas. Windows users currently cannot see their own usage without a terminal.
15. **F-11 — either build Simple/Power or delete it from both docs.** A screen comment currently claims Settings leads with a switch that does not exist.
16. **F-21 / F-22 / F-23 / F-20 — the Windows block experience.** Give blocked sites a real page (or accept and document that they get the browser error), say when a block starts, make config edits take effect, and stop swallowing the wrong-password error.
17. **F-6 (credential) — disable or annotate the Credential strength when `!Auth.isAvailable(context)`**, and give the refusal copy a branch for "this phone has no screen lock".
18. **F-48 — show the trust card at first run** rather than burying it on Settings and Health.

**P2 — quality, coherence and honesty.**

19. **F-9 — open the same `CalendarDialog` from the profile sheet** (it already supports `prefill` and `existing`), or state the scope on the chip. A rule that silently means "every event with this title, forever" is not what the flow promised.
20. **F-2 / F-12 / F-13 — fix the day-one screens:** announce the seeded profile, render the stats card with honest zero copy, and explain a missing comparison.
21. **F-45 / F-47 — externalise strings and re-sync the docs.** Start with `Permissions.kt:39-102` and the empty states; then correct `UX-FLOWS.md`'s stale gaps in both directions.
22. **F-40 / F-39 / F-41 / F-42 — one material, one type scale, one duration format, one meaning for amber.** Build a `DConfirm` on `DSheet` and use it for the eight NowScreen dialogs; give `DSheet` the same glass as the block screen.
23. **F-43 / F-44 — add `EditWindow` to `canvas.json`, delete or populate the two empty files, and port `Welcome.dc.html`'s shape** (two cards, a **Got it** button, and the overlay's Dismiss caption).
24. **F-24 / F-25 / F-26 / F-27 / F-28 — the remaining Windows set:** route the window's unlock through the OS prompt, make the nav keyboard-reachable and content selectable, allow cancelling a freeze from the window, keep the tray menu reachable when the service is down, and print profile *names* not ids.
25. **F-34 / F-35 / F-36 / F-37 / F-15 — hygiene:** make the two rows do what they say, name the missing audit kinds, delete the dead composables and the `block_open_curfew` string, and reconcile "six words" with six digits.

**Two ideas worth adopting from the research, both of which fit Curfew's stated philosophy:**

26. **A short grace window at the start of a locked session** (Freedom's 1-minute window, §2.3c). It separates *"I configured this wrong"* from *"I want out"*, costs one conditional, and fits Curfew's universal-exit position better than the current all-or-nothing. **The single best mechanic found in the category that Curfew does not already have.**
27. **Randomise the block screen, or at least let it vary** (one sec, §2.4). Curfew shows one fixed screen in architecture A — the architecture with no way forward. If that screen is the only thing between the user and the app, varied copy is the one design move that resists habituation, and it is cheap: Curfew already has the reason, the profile, the end time and the emergency-pass state to vary across.

---

## 9. Findings index

| # | Finding | Sev | Primary citation |
| :-- | :--- | :-- | :--- |
| F-1 | Returning from the accessibility prompt loses the configured timer | **P0** | `TimerScreen.kt:246-249, 287-318` |
| F-16 | The Windows GUI cannot start a block at all | **P0** | `app.html` (whole); `main.rs:627` |
| F-17 | The documented first block edits a file the service never reads | **P0** | `README.md:44-55`; `runner.rs:34-37` |
| F-18 | Pairing has no Windows surface, though it is the advertised headline | **P0** | `docs/index.html:117-119`; `app.html:63-66` |
| F-4 | `Lock.Confirm` (the default strength) never confirms anything | **P1** | `NowScreen.kt:101-108`; `TimerScreen.kt:64` |
| F-5 | The emergency pass is unreachable, and the block screen claims it was spent | **P1** | `NowScreen.kt:651, 586`; `BlockActivity.kt:295` |
| F-6 | A credential lock can be chosen on a phone with no screen lock | **P1** | `TimerScreen.kt:65`; `Auth.kt:38-39, 55-60` |
| F-7 | The irreversible 24-hour release is a single unguarded tap | **P1** | `NowScreen.kt:630-637` |
| F-9 | The profile calendar picker writes a rule with no confirm and no scope choice | **P1** | `CalendarScreen.kt:493-511` |
| F-11 | Simple/Power is documented, referenced in a code comment, and absent | **P1** | `SettingsScreen.kt:33`; `MainActivity.kt:100-110` |
| F-14 | Revoking the last peer can remove a lock's exit, silently | **P1** | `DevicesScreen.kt:290-299`; `Format.kt:112` |
| F-19 | Windows design specifies 6 nav pages; the build ships 4 | **P1** | `design/win/*.dc.html`; `app.html:63-66` |
| F-20 | A wrong Windows password fails completely silently | **P1** | `app.html:504-511`; `tick.rs:472-480` |
| F-21 | A blocked site shows the browser's error page — no reason, no exit | **P1** | `hosts.rs:26`; `dns.rs:114` |
| F-22 | No feedback when a Windows block starts | **P1** | `main.rs:460-461`; `shell.rs:100-104` |
| F-23 | Editing the config does not take effect; the UI claims it does | **P1** | `tick.rs:619`; `app.html:348` |
| F-25 | The window's navigation is mouse-only; content cannot be selected | **P1** | `app.html:62-72, 379-384, 15` |
| F-26 | A freeze is shown in the window with no way to cancel it | **P1** | `app.html:299`; `menu.rs:102` |
| F-29 | `UiState.message` is set from 8 places and rendered on 2 | **P1** | `CurfewViewModel.kt:477`; `NowScreen.kt:369` |
| F-30 | An unparseable config looks empty — and Save would overwrite it | **P1** | `CurfewViewModel.kt:190, 199` |
| F-31 | A failed calendar read renders as "your calendar is empty" | **P1** | `CurfewViewModel.kt:145-146` |
| F-32 | Delete-profile: no confirmation, and its routine refusal is never read | **P1** | `ProfileEditScreen.kt:318-327` |
| F-33 | Settings' Fix buttons can throw where Health's are hardened | **P1** | `SettingsScreen.kt:102-104` |
| F-38 | Nav tabs 38dp, Switch 26dp, no touch feedback | **P1** | `MainActivity.kt:249-285`; `Design.kt:327` |
| F-46 | Three false product claims, incl. "no internet permission" | **P1** | `SettingsScreen.kt:207`; `AndroidManifest.xml:54` |
| F-48 | The app's best trust argument is buried on Settings/Health | **P1** | `SettingsScreen.kt:203-214` |
| F-49 | Windows and Android share a core, not an interaction model | **P1** | §7.2 |
| F-2 | First run silently blocks nine apps and never says so | **P2** | `CurfewRuntime.kt:571-585` |
| F-3 | Default block is 90 minutes; the design says 30 | **P2** | `TimerScreen.kt:40` |
| F-8 | Ending an unlocked block raises an acknowledgement modal | **P2** | `CurfewViewModel.kt:387` |
| F-10 | Three figures for one calendar read window | **P2** | `CalendarScreen.kt:186`; `SettingsScreen.kt:180` |
| F-12 | A first-day user sees no evidence line at all | **P2** | `NowScreen.kt:398` |
| F-13 | The missing comparison is unexplained | **P2** | `UsageScreen.kt:64` |
| F-24 | Two opposite trust stories for the same Windows unlock | **P2** | `app.html:473-481`; `prompt.rs:1-15` |
| F-27 | The tray menu does not open when the service is unreachable | **P2** | `shell.rs:199-210` |
| F-28 | The profile **id** is printed where the name belongs | **P2** | `overlay.rs:63`; `menu.rs:111` |
| F-34 | Two rows promise a path they do not implement | **P2** | `ScheduleScreen.kt:269-281`; `ProfileEditScreen.kt:188-194` |
| F-35 | "A repeating schedule" is tappable and does nothing | **P2** | `ProfileEditScreen.kt:205-210` |
| F-36 | The audit list renders internal event names | **P2** | `UsageScreen.kt:273-281` |
| F-39 | `DSheet` is the only surface without the app's own glass | **P2** | `Design.kt:585` vs `BlockActivity.kt:266-288` |
| F-40 | `DSheet` exists to avoid Material; NowScreen uses Material for 8 dialogs | **P2** | `Design.kt:555-566`; `NowScreen.kt:299, 323, 370, 685, 755` |
| F-41 | Four duration formats, three clock formats | **P2** | `TimerScreen.kt:322`; `Format.kt:23`; `BlockActivity.kt:333` |
| F-42 | Amber's documented meaning broken in three places | **P2** | `Theme.kt:26`; `TimerScreen.kt:107`; `SettingsScreen.kt:134` |
| F-43 | Canvas out of sync: orphan artboard, two empty files, non-reproducible Plan | **P2** | `canvas.json`; `_parts.py`; `_base.css` |
| F-44 | `Welcome`/`Setup` designed, unbuilt; welcome is 40 s with no button | **P2** | `design/win/Welcome.dc.html`; `welcome.rs:22-29` |
| F-45 | `strings.xml` effectively unused; ~122 hardcoded strings | **P2** | `strings.xml`; `Permissions.kt:39-102` |
| F-47 | `UX-FLOWS.md` stale in both directions (§4 table) | **P2** | §4 |
| F-15 | "six words" (doc) vs six digits (screen) | **P3** | `UX-FLOWS.md:206`; `DevicesScreen.kt:202` |
| F-37 | Dead composables and an unused `block_open_curfew` string | **P3** | `AppPickerScreen.kt:132-151`; `strings.xml:16` |

**The five changes I would make first:** F-1 (resume the timer — one `LaunchedEffect`), F-29 (one app-level message host), F-4 (make Confirm confirm), F-46 (fix the three false claims), F-38 (48dp nav and Switch). And on Windows, F-16 — without it there is no product there.

---

*Generated from a direct read of the source, the design canvas, WCAG 2.2 SC 2.5.8, and vendor documentation at commit `525e691`. `web_search` was unavailable (HTTP 401); every competitor claim is cited to a fetched URL, and the four gaps in that research — Brick, Opal's onboarding, and Freedom's and SelfControl's block pages — are stated in §2 rather than filled in from memory. Findings marked `[R]` come from parallel deep audits and were not independently reproduced.*
