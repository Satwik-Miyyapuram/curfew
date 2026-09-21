# audit.md — verification and fix plan

`docs/audit.md` contains findings from two different review passes, concatenated. Two
consequences follow, and both are handled below:

1. **The two passes use overlapping IDs.** There are two `B-01`s, two `B-02`s, … with
   different meanings. Everything here is keyed by (file, symptom) rather than by ID.
2. **A second pass makes claims the first pass contradicts**, and several claims do not
   survive contact with the code. Every finding was re-checked against the working tree
   before anything was changed. The verdicts are below.

Legend: **CONFIRMED** (reproduced in the code) · **PARTLY** (real, but the finding's
description or severity is wrong) · **ALREADY FIXED** (true of an older revision) ·
**WRONG** (does not reproduce).

---

## 1. Verification

### Android — accessibility surface

| Finding | Where | Verdict | Evidence |
| --- | --- | --- | --- |
| `canRetrieveWindowContent=true` makes payment/banking apps refuse to run | `CurfewAccessibilityService.kt` + `accessibility_service_config.xml` | **CONFIRMED** | Android's rule is per-service, not per-package. `android:packageNames` is a delivery filter, not a security boundary, and banks gate on `getEnabledAccessibilityServiceList(FLAG_RETRIEVE_INTERACTIVE_WINDOWS)`. Curfew holds the bit unconditionally today. |
| **Website blocking is silently broken: `flagReportViewIds` is missing** | `res/xml/accessibility_service_config.xml` | **CONFIRMED — highest-value bug in the document** | `extractUrl()` is built entirely on `findAccessibilityNodeInfosByViewId()`; `viewIdResourceName` is only populated for a service declaring `flagReportViewIds`. The config declares `flagIncludeNotImportantViews` only, so `extractUrl()` returns null on every event and **every URL/site rule has never fired on Android**. The failure is invisible because null is also the legitimate "new tab" answer. |
| The KDoc claims window content retrieval is off | `CurfewAccessibilityService.kt:19-20` | **CONFIRMED** | The KDoc says "window content retrieval is off in the configuration, so it is not capable of reading the screen even if it wanted to". The XML says `canRetrieveWindowContent="true"` and subscribes to `typeWindowContentChanged` for every package. The XML header repeats the same false claim ("No content events"). The code could not work at all if either were true. |
| `isBrowser()` false-positives on `.browser` / `.chrome` substrings | `BrowserUrlExtractor.kt:68-71` | **CONFIRMED** | `packageName.endsWith(".browser") \|\| packageName.contains(".chrome")` admits `com.chromecast.*`, `com.example.mybrowser`, `com.company.chrometools`. Those are then routed to `rootInActiveWindow` + three fabricated view-id probes on every content event — reading the node tree of an app the user never asked Curfew to inspect. |
| Guessed-id fallback in `extractUrl()` | `BrowserUrlExtractor.kt:80-84` | **CONFIRMED** | `BROWSER_URL_BAR_IDS[packageName] ?: listOf("$packageName:id/url_bar", …)`. Dead weight once `isBrowser()` is exact, and it is exactly the "read an arbitrary app" path. |
| `cleanUrl()` rejects every host starting with "search" | `BrowserUrlExtractor.kt:110` | **CONFIRMED** | `startsWith("Search", ignoreCase = true)` runs *before* the scheme/dot shape check, so `search.brave.com`, `search.yahoo.com`, `searchengineland.com` are discarded before parsing and can never be blocked. |
| `UninstallGuard.WATCHED` includes `com.android.systemui`, unreachable | `CurfewAccessibilityService.kt:38` vs `UninstallGuard.kt:36` | **CONFIRMED** | The `com.android.systemui` early return fires before `guard(packageName)`, so the systemui entry can never intervene. |
| Browser no-URL path emits a duplicate `App` observation | `CurfewAccessibilityService.kt:93-115` | **CONFIRMED** | The no-URL `else` branch has no `return`; control reaches the bottom block with `lastNonBrowserPackage` freshly nulled, so a second coroutine launches a second `App` observation for one event, and `lastNonBrowserPackage` is then set to a *browser* package. |
| `activeBrowserPackage` is never cleared | `CurfewAccessibilityService.kt` | **PARTLY — was rated too high** | It is never cleared on leaving a browser (real), but its only consumer, `BlockActivity.openNewTab()`, already treats null correctly and has a package-less fallback. Cosmetic, not a misdirected block. |
| `FLAG_ACTIVITY_NEW_TASK` block screens can stack | `BlockActivity.kt:166,173` | **WRONG** | `BlockActivity` is declared `android:launchMode="singleTask"` with its own `taskAffinity` in `AndroidManifest.xml:106-113`. `singleTask` guarantees one instance in its task; a second launch delivers `onNewIntent` to the existing one. |
| `notificationTimeout="0"` | `accessibility_service_config.xml:23` | **CONFIRMED** | Confirmed as written: no system-side coalescing, so the in-code 250 ms throttle discards work only *after* the process has been woken. |
| `AccessibilityNodeInfo` never recycled below API 33 | `BrowserUrlExtractor.kt`, `UninstallGuard.kt` | **CONFIRMED** | No `recycle()` anywhere in either file. |
| `setServiceInfo` never used to shrink the declared surface | `CurfewAccessibilityService.kt` | **CONFIRMED** | The config is static XML; a user with zero URL rules still subscribes to the whole content-event firehose. |

### Android — enforcement runtime

| Finding | Where | Verdict | Evidence |
| --- | --- | --- | --- |
| Every `decide()` re-reads 25 h of history from SQLCipher and marshals it across FFI | `CurfewRuntime.kt:79-98`, `Policy.kt:231` | **CONFIRMED — critical** | `decide()` calls `usage(now)` unconditionally: `usageSince(now - 25h)` + `launchesSince`, a `groupBy`/`map`, then `json.encodeToString(usage)` into the FFI on *every* call. On every accessibility event and every charge tick. The audit understates it: the `UsageState` is re-serialised to JSON per call, not merely rematerialised. |
| `Enforcer`'s mutable state is raced from a multi-threaded dispatcher | `Enforcer.kt:39-42`, `CurfewRuntime.kt:56` | **CONFIRMED** | `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` (multi-threaded). Callers: the accessibility service (`runtime.scope.launch` per event), `chargeLoop` `onTick`, the screen-off receiver `onIdle`, and the notification listener. `current`/`observation`/`since`/`charging` are plain `var`s and `flush()` suspends at `runtime.recordUsage`, so two entries interleave and slices are dropped, double-charged or charged to the wrong target. There is also no ordering guarantee between events, so a stale observation can be applied after a newer one. |
| Every browser event runs `decide()` up to three times | `CurfewAccessibilityService.kt:81-89` + `Enforcer.onObservation` | **CONFIRMED** | `decide(App)` → `decide(Web)` → `decide(Web)` again inside `onObservation`. With the finding above, each is a full decrypt-read-marshal. |
| `UsageStatsPoller` reports a left app as foreground for up to 120 s | `UsageStatsPoller.kt:28-37` | **CONFIRMED** | Scans a 120 s window and keeps the last `MOVE_TO_FOREGROUND`, ignoring `MOVE_TO_BACKGROUND`/`ACTIVITY_PAUSED`. Deprecated since API 29 in favour of `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED`. |
| `UsageStatsPoller` re-scans the whole 120 s window every poll | `UsageStatsPoller.kt:28` | **CONFIRMED** | No watermark; every event is parsed ~120× at the 1 s cadence. |
| Enforcement jobs live in the app-wide scope | `EnforcementService.kt:43-60,250-257` | **PARTLY** | Real but mischaracterised. `onDestroy` does cancel all four jobs, so the common path is fine. The genuine residue is that job lifetime is tied to another owner's scope, and `onCreate` cannot tell a re-entry from a first entry. Low rank: fix by giving the service its own scope. |
| Charge loop wakes every 5 s with the screen off | `EnforcementService.kt:83-98` | **CONFIRMED** | The only gate is `activeProfiles.isNotEmpty()`. No screen-state check; `screenReceiver` calls `onIdle` but does not suspend the loop. |
| Charge-loop gating adds up to 15 s of lag | `EnforcementService.kt:90-94` | **CONFIRMED** | `if (!due) { delay(15 s); continue }; delay(5 s)` — worst case first charge is ~20 s after a session starts. |
| Notification listener processes every notification, including OTPs | `CurfewNotificationListener.kt:25-38` | **CONFIRMED** | No carve-out. Because `engine::decide` mutes notifications of any blocked target, and a sensitive app stays blockable, blocking a bank app suppresses its OTP notifications mid-payment. |

### Rust core

| Finding | Where | Verdict | Evidence |
| --- | --- | --- | --- |
| `Url::parse` lowercases the whole URL and drops the scheme | `target.rs:157-192` | **CONFIRMED** | `raw: trimmed.to_lowercase()` destroys case in `path`/`query` (split from the already-lowercased `after_scheme`), contradicting the comment directly above it. `normalized()` has no scheme, so a rule written `https://github.com/User/Repo` can never match — URL paths are case-sensitive per RFC 3986. |
| `glob_match` allocates two `Vec<char>` per call | `target.rs:211-213` | **CONFIRMED** | `pattern.to_lowercase().chars().collect()` and the same for the text: two allocations and two passes per comparison, on the hot path. |
| `domain_matches` allocates per call | `target.rs:197-205` | **CONFIRMED** | `normalize_domain` allocates for both sides plus `format!(".{rule}")` per call, on the DNS-adjacent path. |
| Budget prune/used_since double-count on the boundary | `budget.rs:161-191` | **WRONG** | The claim needs a code path where a pre-prune total survives alongside the post-prune sum; there is none. `used_since` sums the rollups the collection holds *now*, `prune` only drops. `r.at >= before` and `r.at >= from` are consistent, and `at == from` should be counted. Adding a property test is still worthwhile as a regression guard, which is what was done. |
| `Consumption.rollups` searched linearly | `budget.rs:161-166` | **PARTLY** | True, but the audit's "525,600 rollups per target" is wrong — rollups are per-minute, so a year is ~525,600 *seconds* of use spread over ≤525,600 one-minute entries only if the target was used every minute of every day. At realistic sizes this is tens of entries. Low priority; the sorted-suffix optimization was not taken. |
| Resolver re-parses the config blocklist on every DNS query | `crates/curfew-svc/src/runner.rs` | **WRONG** | `runner.rs:818` calls `resolver.set(tick.domains.clone())` — a precomputed `BTreeSet` — once per pass, and `curfew_win::dns` never sees the config. The claim that 5,000 domains are matched per query describes a different architecture. See `blocked.rs`. |
| Sync re-serialises the whole op-log each pass | `crates/curfew-sync/` | **WRONG** | The protocol is already delta-based with per-peer head exchange: `greet` → `answer(&log, &heads)`, four frames, no negotiation (`lan.rs:197-259`). This is precisely the fix the finding proposes, already built. |
| Watchdog freshness uses file length | `crates/curfew-svc/src/watchdog.rs:185` | **ALREADY FIXED** | `refresh()` reads and compares file **contents**, with a test asserting two same-length different-content files are distinguished. The doc comment already describes the old metadata check as the bug it replaced. |
| `ClockWitness.observe()` on every tick | `clock.rs` | **WRONG** | `TOLERANCE_SECONDS = 60` and a tick is 1 s/15 s; `trustedNow()` already reads once per tick and the UI uses `trustedNowLight()`. Two clock reads per tick is not a cost worth code. |

**One defect the audit missed entirely, found while checking the above:** `curfew_win::dns::covered()`
built a `format!(".{domain}")` for **every entry on every DNS query** — not because the blocklist is
large, but because the boundary test was written as a string suffix. The allocation is gone; see
§3 item 5 for why the O(n) shape was deliberately kept.

### Already fixed in the working tree, though the first pass reports them

- **Device-admin banking warning** (`R-03`/`PAY-05`): `perm_uninstall_protection_cost`
  already says "many banking and payment apps refuse to run while any device admin is
  active, so leave this off if you use one", and `Permissions.kt:99-113` already ranks it
  last and marks it optional. `EXTRA_ADD_EXPLANATION` in the Android dialog is still
  narrower than the wizard copy and is worth one clause.
- **`isAccessibilityTool`** (`PAY-04`): correctly absent. Needs documenting, plus a guard
  so a future edit cannot quietly turn it on.
- **Dead-code fallback** (`R-05`): resolution is part of the `isBrowser()` fix.

---

## 2. Deduplication

| Real issue | Reported as |
| --- | --- |
| Payment apps refuse to run alongside a content-capable service | B-01 (pass 1), R-01, PAY-01 |
| False privacy claims in KDoc and XML header | B-01/XML comment (pass 1), B-02 (pass 2), PAY-04 (partly) |
| Fuzzy `isBrowser()` + guessed-id fallback | B-02 (pass 1), B-07 (pass 2), R-05, PAY-01 |
| `canRetrieveWindowContent` / package scope | B-01 (pass 1), R-01, R-02, PAY-01, PAY-02, PAY-03 |
| Device-admin banking trade-off | R-03, PAY-05 |
| Blocking sensitive apps but muting their notifications | PAY-06 (novel, kept) |

Feature proposals with no defect behind them — the two-service split, `enforcement.mode`,
"Bank-friendly mode" — are **not fixes**; they are product decisions. They are listed in
§4 rather than implemented unilaterally.

---

## 3. What was fixed

Every item below is implemented and covered by a test. The gates that were run:

- `cargo test --workspace --all-targets` — green.
- `cargo clippy -p curfew-core -p curfew-win --all-targets` — clean.
- `:app:testDebugUnitTest` + `:policy:testDebugUnitTest` — **287 tests, 0 failures** (219 app,
  68 policy).
- `assembleDebug` — the debug APK builds.

> **Two plan items were implemented differently than written.** Both are recorded here rather than
> quietly dropped, because in each case the plan was wrong and a test is what proved it.

### Rust core (`crates/curfew-core`)

1. **`Url` keeps case and remembers the scheme** (`target.rs`). `parse()` now stores the scheme,
   lowercases only the host, and keeps `path`/`query` verbatim, as the comment always promised.
   `normalized()` re-attaches the scheme, so a rule written `https://github.com/User/Repo` matches.
   `raw` keeps its original case. `scheme` is `#[serde(default)]` so a payload from an older binding
   still deserializes instead of crashing the enforcer. Tests added for case-sensitive paths,
   uppercase query values, and scheme-qualified patterns.

   *Honest scope note:* glob matching itself stays case-**in**sensitive, because one engine also
   serves window titles and file paths. What changed is that the URL is no longer folded before it
   is compared, so `https://github.com/User/Repo` and `https://github.com/user/Repo` are now
   different strings. The test says this rather than implying globs became case-sensitive.

2. **`glob_match` is allocation-free on the ASCII path** (`target.rs`). Iterates bytes with
   `eq_ignore_ascii_case` and `?` as a single byte, falling back to the existing char-based loop for
   non-ASCII. The single-backtrack structure, the O(pattern × text) bound, and the
   pathological-pattern test are unchanged.

3. **`domain_matches` compares bytes** (`target.rs`): explicit length guard, byte-slice suffix
   compare, boundary byte must be `b'.'`. No allocation. Same semantics, including the lookalike
   rejections the property tests assert.

4. **Budget boundary tests** (`tests/budget.rs`) — three new tests pinning the prune/`used_since`
   convention, including the case the audit claimed was broken: pruning exactly at the window start
   must not change the sum, and it does not.

### Rust service (`crates/curfew-win`)

5. **`dns::covered()` — the allocation was removed, the predicate was not changed.** This is the
   item that was reverted part-way, so it is worth stating exactly what happened.

   The plan was the audit's: walk the query's labels and look each parent up in the set, O(labels)
   instead of O(blocklist). It was written, and a test comparing it against the old linear scan
   *name by name* showed it was not equivalent. Two things came out of that:

   - **The real defect here was only ever the `format!(".{domain}")` built per entry per query**, not
     the O(n) shape. The blocklist is the config's domain rules — a handful — not a 5,000-entry
     subscription list, because the desktop resolver is fed a precomputed set once per tick and
     never touches the config on the query path. The audit's `5 000 domains` and `1000x speedup`
     figures do not describe this code.
   - The walk reports a *different rule name* than the scan for some inputs — its own candidate
     spelling rather than the configured entry — and the callers use that name: it is what the
     refusal says. Changing which rule is reported is a behaviour change nobody asked for.

   The shipped version keeps the scan and removes the allocation: an exact match needs none, and a
   suffix match is compared on byte slices rather than against a freshly built string. **The
   predicate and its decision order are the old ones**, asserted by
   `the_allocation_free_scan_decides_what_the_allocating_one_did` across three blocklists and
   sixteen names, `www.` quirks included. `the_label_walk_would_not_have_been_equivalent` documents
   the reverted attempt and why, so it is not tried a fourth time.

### Rust FFI (`crates/curfew-ffi`)

6. **`has_metered_rules`** (new). Answers whether any *running* profile has a budget or launch
   limit. `engine::decide` only ever touches `State::usage` inside those two arms, so this is what
   lets the Android caller skip materializing a day of history at all — and it is asked of the
   active profiles rather than the whole config, because a budget that is not running cannot change
   an answer.

### Android

7. **`flagReportViewIds` added** (`accessibility_service_config.xml`) — **site blocking on Android
   works after this and did not before.** The stale and false claims in the XML header and the
   service KDoc are replaced with an accurate description of the two places content is read; the
   `canRetrieveWindowContent` cost is stated rather than denied; `isAccessibilityTool` is documented
   as deliberately absent and why.

8. **`SensitiveApps`** (new). A curated set of payment, banking, wallet and credential packages, with
   `SensitiveAppsTest` checking both directions: the banks are on it, and nothing a fresh install
   blocks — nor any browser, settings or systemui package — is. `onAccessibilityEvent` short-circuits
   on it **as its first statement**: before the guard, before `isBrowser()`, before
   `rootInActiveWindow`, so the guarantee is structural rather than a matter of review. Sensitive
   apps stay blockable as apps from the package name alone.

9. **`BrowserUrlExtractor` de-fuzzed**: `isBrowser()` is the exact key set of the url-bar map — no
   `.browser` / `.chrome` substring matching. `urlBarIds()` exposes the ids so the caller never
   reaches node access for an unknown package, and `extractUrl()` takes them as a required parameter
   instead of fabricating them. `cleanUrl()` tests URL shape *before* prefix matching, so
   `search.brave.com` and `searchengineland.com` block and `Search or type web address` still does
   not. Tests added for all of it, including the packages the old heuristics admitted
   (`com.google.android.apps.chromecast.app`, `com.company.chrometools`).

10. **One event, one observation** (`CurfewAccessibilityService.kt`). The browser and non-browser
    branches collapse into a single `classify()` producing exactly one `Observation?`, emitted once
    at one exit. This removes the duplicate-observation bug, fixes the corrupted
    `lastNonBrowserPackage`, clears `activeBrowserPackage` when the user leaves a browser, and
    removes the service's two redundant `runtime.decide()` calls.

11. **Back-navigation moved into the enforcer's `Actions`** — which is why the service no longer
    calls `decide()` at all. The plan said "one event, one decide"; the implementation is the
    stronger "the service decides nothing". `Enforcer.act()` is the single place a block is
    performed, because both the observation path and the tick path block, and inlining it in both is
    exactly how they come to disagree. Writing that test is what caught the fact that the shared
    fixture's `reddit.com` rule is a *budget* — which correctly allows while there is time left — so
    the test uses an outright block.

12. **Sensitive-app notification carve-out** (`CurfewNotificationListener.kt`): financial
    notifications are never muted and never inspected, so blocking a bank app can no longer swallow
    an OTP mid-payment. This closes the interaction between `SensitiveApps` and `engine::decide`'s
    "a blocked target's notifications are muted too".

13. **`CurfewRuntime` stops reading the database on the hot path.** `decide()` asks
    `Policy.hasMeteredRules` first and passes `UsageState()` when nothing metered is running — the
    common case, since most blocking is "this app is blocked". When something *is* metered a
    write-through `usageCache` answers: `recordUsage`/`recordLaunch` append in O(1), and
    `invalidateUsage()` is called by every writer that is not the enforcer (sync adoption, the daily
    prune). `usageFromDb` remains the exact read, used by `syncPass`, the statistics screens and the
    tests, so nothing that needs what is on disk now has been handed a cached view by accident.

14. **`Enforcer` confined to one lane and ordered** (`Enforcer.kt`): every public entry point hops
    onto a shared `limitedParallelism(1)` dispatcher, so the time accounting the file itself calls
    "the part most likely to be subtly wrong" cannot interleave — and observations cannot be applied
    out of order.

15. **Charge loop follows the screen and starts on time** (`EnforcementService.kt`): it *waits* on
    `activeProfiles.first { it.isNotEmpty() }` and on a `screenOn` flow rather than sleeping through
    a blind 15 s poll, so a screen-off night costs no wake-ups and the first slice of a session is
    five seconds late rather than up to twenty.

16. **Service-owned job scope** (`EnforcementService.kt`): all four loops launch into a
    `serviceScope` cancelled in `onDestroy`, so a `START_STICKY` restart cannot leave a second
    `chargeLoop` charging at 2×. The individual `cancel` calls are kept as documentation that the
    loops exist, with a comment saying the scope is the actual mechanism.

17. **`notificationTimeout` raised from 0 to 250 ms**, matching the in-code throttle, so bursts are
    coalesced by the framework instead of after a wake-up.

18. **The device-admin dialog states the banking-app cost** (`CurfewDeviceAdmin.kt`), in one clause,
    naming the accessibility guard as the cheaper alternative, with a test asserting both are
    present. The wizard already said this; the dialog is where the user is standing when they decide.
    This is the last residue of `R-03`/`PAY-05`.

### Deliberately not done, so the omissions are visible

- **`UsageStatsPoller` was left as it is.** The staleness finding is real and the fix is small, but
  the poller is the *fallback* — it only runs when the accessibility service is off — and the
  proposed fix (track `ACTIVITY_PAUSED`/`ACTIVITY_STOPPED` plus a watermark, with a doze-gap
  fallback) is a behaviour change to the least-exercised detector in the app, with no device here to
  test on. Folded into D2 below rather than changed unilaterally.
- **`AccessibilityNodeInfo` recycling was not added.** Every call site would need a
  `finally`-blocking helper, and getting it wrong recycles a node still in use — a crash in the one
  process that must stay resident. A real leak on API < 33 and a real regression risk; it wants a
  device.
- **`setServiceInfo` surface shrinking** — see D4. Unchanged.

---

## 3a. The second round: decisions taken, and what a real device changed

D1–D4 were put to the owner and answered; then the build was installed on a Galaxy S24
(SM-S928B, Android 17 / API 37) over `adb` and driven there. The device found three things
no amount of reading had.

### D1 — the split, done

Two services, one running at a time:

| | `ForegroundTracker` | `UrlReaderService` |
| --- | --- | --- |
| `canRetrieveWindowContent` | **false** | true |
| App blocking | yes | yes |
| URL / site / keyword rules | no — refused at config load | yes |
| Uninstall guard | **no** | yes |
| Banking-app warning | none | yes |

Shared `ScreenWatcher`, so what an event *means* is decided once. `Watchers` holds the mode,
which is read from the system's enabled-service list rather than from a stored preference,
because the preference is a request and the list is the fact.

The uninstall guard is the real casualty of app-only mode and is described as such: it needs
`rootInActiveWindow`, which is exactly the capability that mode does not have. Device admin is
named as the alternative rather than the guard being silently absent.

### D2 — not built, at the owner's decision (no wizard; Windows does not need one)

### D3 — done, but the planned signal does not exist

**`ApplicationInfo.CATEGORY_FINANCE` is not a constant in the platform.** `ApplicationInfo`
defines `CATEGORY_GAME`, `AUDIO`, `VIDEO`, `IMAGE`, `SOCIAL`, `NEWS`, `MAPS`, `PRODUCTIVITY`,
`ACCESSIBILITY` and `UNDEFINED` — and nothing for finance, in API 35 or API 37. A check written
against it would have compiled into a comparison that is false for every app on every device: a
privacy feature that silently does nothing, which is the failure this whole round was about.

What shipped instead is a conjunction of two weak signals, both required: **the app requests a
biometric permission** and **its label contains a word that means money**. Label first, because
it is free from `getInstalledApplications`; the permission list costs a `getPackageInfo` per
candidate and is only asked of apps that already looked financial.

The list is now user-extendable in Settings, curated entries are not removable, and an app wrongly
caught can be exempted — an exemption rather than a deletion, so the label signal cannot put it
straight back.

### D4 — done, with the fallback

`ServiceSurface` narrows `eventTypes` at runtime when no URL or keyword rule exists. The fallback
is that it **mutates the service's existing `AccessibilityServiceInfo`** rather than rebuilding
one, so `flags` — including `flagReportViewIds`, whose absence was the original silent bug — are
carried through by construction. It refuses to act if the platform reports no flags at all, and a
failure leaves the more capable subscription standing. Verified by test; whether a given OEM
accepts the change still needs a device, and this one does.

### And: bank apps are blockable, after a detour that was wrong

**This section originally said the opposite, and the reversal is the point.** A block on a payment or
banking app was refused, then narrowed off, on the argument that `engine::decide` mutes the
notifications of any target a block covers and a one-time password that never arrives is a payment that
never completes. The premise was wrong: `CurfewNotificationListener` returns early for every package on
the sensitive list, so a blocked bank app is blocked on screen and **its notifications still arrive**.

Which apps a person blocks is theirs to decide, and the reading restriction belongs to the
accessibility service rather than to the config. All three of the mechanisms above are gone:

- the app pickers filter nothing out;
- `ConfigStore.write` refuses only url/keyword rules in a mode that cannot read a window;
- `ConfigStore.read` narrows nothing — `dropRulesFor` no longer exists.

The cost of getting this wrong was real: the first version loaded the *baseline* config when it met a
rule it would not honour, which would have silently stopped enforcing an entire fifty-rule config; the
second dropped only the offending rules, which still deleted two Amazon blocks from a live device. Both
are recorded in `ConfigGuardTest` and `AppOnlyConfigTest`, which now assert the removed behaviour stays
removed.

### What driving the real device found

1. **The stale service name.** The installed build still declared
   `dev.curfew.app.enforce.CurfewAccessibilityService` — a class this work deleted — and
   `settings get secure enabled_accessibility_services` still named it. A reinstall fixes it
   (the component no longer exists, so the entry is dropped), but it is worth knowing that the
   split renames the component and every existing install lands in that state once.
2. **`com.amazon.mShop.android.shopping` was not on the curated list.** The list had
   `in.amazon.mShop.android.shopping` — the *India* package. The global one is a different
   package and the one a device outside India has, and it was blocked by name in the owner's own
   config. Two Amazon packages, two entries, both now present. This is the difference between a
   guarantee and a guarantee that does not apply to the phone it is installed on.
3. **The first guard dropped the entire config rather than the offending rule.** The owner's
   config blocks approximately thirty apps and four domains, and one of them is Amazon. The
   original `guard` loaded the *baseline* when anything was unblockable, which would have
   silently stopped enforcing all thirty-four rules. Now the single rule is removed and the rest
   is kept, validated by the core first, with the baseline only as the last resort.

Two of those three were found by pointing a unit test at the config file the *core* writes —
nested `[profiles.rules.target]` tables, `platforms = []` on every rule — rather than at a tidy
fixture. The guard was text-matching rule chunks and the tidy fixtures had concealed nothing; it
was the real shape that had never been tried. `ConfigGuardTest` and `ConfigGuardInputsTest` now
use the core's own spelling.

### What is live, what is cached, and what was missing

Challenged on this, and the challenge was fair: the prose said "listed" where it should have said
exactly which of three things was meant.

| | How current |
| --- | --- |
| `SensitiveApps.CURATED` | A constant. Correct by construction; nothing to refresh. |
| `SensitiveApps.resolve()` | **Live.** A fresh `getInstalledApplications` walk per call — no memo, no cached field. |
| The app picker's candidate list | `InstalledAppsCache.cached`, a process-lifetime snapshot. |
| The set every accessibility reader checks | A snapshot in `Watchers`, refreshed at the moments it can change. |

**Nothing observed package installs, and that was a real hole.** `InstalledAppsCache.clear()` was
called from nowhere, so an app installed after Curfew started did not appear in the picker until the
process died; and each `ScreenWatcher` held the sensitive list resolved when its service connected,
so a bank installed afterwards was not yet unreadable by the running service. The second is the one
that matters: a stale picker is cosmetic, a readable payment app is not.

Fixed with one manifest receiver on `PACKAGE_ADDED` / `REMOVED` / `REPLACED` / `CHANGED` that clears
the picker cache, re-resolves the shared sensitive list, and re-sizes the reader's subscription.
**Not polling** — the platform pushes these, and they are the only moments the answer changes. The
`data android:scheme="package"` on the filter is load-bearing: these broadcasts carry a `package:`
URI and a filter with no data element matches none of them, failing silently. Confirmed in the
parsed manifest on the device — all four actions and the scheme.

`Watchers.sensitive()` starts as `CURATED` rather than empty, because an empty set means "nothing is
exempt" — a reader starting before its first refresh would read a bank's screen. `SensitiveAppsTest`
asserts both that the shared value tracks a resolution and that starting point.

### Verification, second round

`cargo test --workspace` green · clippy clean · `cargo fmt --check` clean ·
**316 Android tests, 0 failures** (248 app, 68 policy) · `assembleDebug` builds · installed on
the device, launches with no fatal, the guard fires and names the offending rule, and the
package-change receiver is in the parsed manifest with all four actions and the `package:` scheme.

---

## 4. Left for your decision

The original D1–D4 have been answered and implemented. D5 and D6 are answered below.

These are real, verified, and **not** implemented, because each one changes behaviour the
user sees rather than fixing something objectively broken. They are ordered by how much
they matter.

### D1. Split the service in two, or accept the payment-app warning — `B-01`, `R-01`, `PAY-01`, `PAY-02`, `PAY-03`

**The problem.** `canRetrieveWindowContent` is set per service, not per package. There is
no way to say "this service may read only browsers". So as long as Curfew holds the bit,
every UPI app, banking app and wallet on the device is entitled to refuse to run — and
`android:packageNames` does not help, because banks check the *capability*, not the filter.

**Why I did not just do it.** Splitting into a `ForegroundTracker`
(`canRetrieveWindowContent=false`, does all app blocking) plus an opt-in `UrlReaderService`
does fix it, and it is the only technical answer. But it has three consequences that are
yours to weigh:

- It **breaks the uninstall guard** in app-only mode. `UninstallGuard.mentions()` needs
  `rootInActiveWindow`, which needs the content bit. So "app-only mode" would silently drop
  the guard and fall back to device admin — the thing that itself breaks banking apps. The
  guard's raison d'être is being the cheaper alternative to admin, and the split would
  remove the alternative in exactly the mode that exists to serve banking-app users.
- It **adds a second user-visible setting** and a migration for existing installs (which
  service is enabled now?), plus a second permission-wizard step.
- The gain is conditional on the user granting the right one of the two, so the wizard copy
  carries most of the value.

**Options.**

- **(a) Do nothing further.** Comment fixes and the sensitive-app exemption already remove
  the accidental exposure; deliberate URL rules still cost the warning. Cheapest, honest,
  leaves the warning unexplained.
- **(b) Document the trade-off, no split.** Add an explicit "Site blocking requires a
  stronger accessibility privilege, and some banking apps will refuse to run" line to the
  accessibility permission's `cost` string and the health screen. ~30 minutes, no
  architecture, no risk. **This is what I would do first regardless of the answer here.**
- **(c) Do the split.** `ForegroundTracker` + opt-in `UrlReaderService`, URL rules rejected
  at config load in app-only mode, `UninstallGuard` documented as unavailable there and
  device admin offered as the substitute. A day of work and a real Android-behaviour test
  matrix (Xiaomi/Samsung/Oppo installers) which cannot be done from this machine.
- **(d) Split, and re-implement the uninstall guard without window content.** No such API
  exists; this is not actually an option, listed only to close it.

**My recommendation: (b) now, (c) only if you have a device to test on.** If you want (c),
say so and I will build it behind a config flag so the current behaviour stays the default.

### D2. "Bank-friendly mode" as a named wizard option — `PAY-03`

Accessibility off, usage-stats polling instead, honest copy about the ~1 s detection gap.
With the poller's staleness fixed (done) this is genuinely viable rather than a grudging
fallback. It is a product decision — a named mode is a promise about accuracy — and it
needs copy in your voice. Recommended, but not mine to add silently.

### D3. Sensitive-package list contents

`SensitiveApps` ships with the UPI, wallet and major-bank packages. Because the list is
load-bearing for a privacy guarantee, over-inclusion silently disables blocking for an
app the user asked to block, and under-inclusion silently exposes a bank. Two questions:
should it be **user-extendable in Settings**, and should it **auto-include any package with
`ApplicationInfo.category == CATEGORY_FINANCE`** (catches regional banks, but can catch
budgeting apps the user genuinely wants blocked)? Current state: curated list, not
extendable. My recommendation is to add the finance-category signal as an *addition to*,
not a replacement for, the curated list.

### D4. `Runtime.setServiceInfo` surface shrinking — `PAY-02`

Reducing `eventTypes` at runtime when no URL rule exists is a genuine improvement for
users with zero site rules. It is left out because `setServiceInfo` is the kind of API
whose real behaviour differs across OEMs and Android versions, and shipping it untested on
a device risks silently disabling content events for users who *do* have site rules — the
opposite failure to the one being fixed. Wants a device.

### D5. Notification-listener sensitivity — `PAY-06`

Done as a carve-out, and it is load-bearing rather than tidy: financial notifications are never muted
and never inspected. A bank app **is** blockable, so the interaction the finding was about arises
exactly as it describes — a block on a bank would otherwise suppress that bank's notifications — and
this carve-out is what makes blocking one safe. The stronger options (not holding the listener at all,
or scanning notification content) were rejected: the first removes a feature users asked for, the
second contradicts the documented promise that notification content is never read.

### D6. `NotificationListener` / `UsageStatsPoller` as a general detector — `PAY-03`

Dropped with D2. `UsageStatsPoller` remains what it was — the fallback for a user who grants
neither accessibility service — and it gives no URL and no sub-second detection, which the health
screen already says in one sentence because the poller's `cost` string says it. Nothing more to do
while there is no "bank-friendly mode" to describe.

### Still open, and now more clearly worth doing

The two things deliberately left undone in §3 remain undone, and the split has made the first of
them matter more:

- **`UsageStatsPoller` staleness.** Real, and now the *only* detector for a user who grants neither
  service — which is a legitimate configuration the app supports rather than a fallback nobody
  uses. It reports a left app as foreground for up to 120 s and re-scans that whole window every
  poll. The fix is small and this device can now verify it.
- **`AccessibilityNodeInfo` recycling.** A real leak on API < 33, and a real crash risk if done
  wrong. The device is API 37, where `recycle()` is a no-op, so it cannot be verified here.

---

## 5. The third round: the five remaining items, worked

### 1. `UsageStatsPoller` — fixed

Both faults, and the class now says what it is for.

**Both edges.** A `Resumed` step sets the app in front; a `Paused` or `Stopped` step *for that same
package* clears it. That single missing edge is what made "open Instagram, press Home" leave Instagram
reported as being in front for the rest of the window — a launcher that is already running emits no
resume of its own, so the stale one won.

**A watermark.** Each poll asks only for events since the last one seen, instead of re-parsing 120 s of
history every second. `queryEvents` is the most expensive call in that loop and this was why.

**A fallback to the wide window**, kept and used whenever the incremental answer could be incomplete:
no watermark yet, a watermark older than the window (a doze gap), or one in the future (a clock
correction). A watermark that under-reads would *lose* a foreground change, and a missed block is worse
than a late one.

**The logic moved out of Android to be testable.** `UsageEventFold` takes `List<UsageStep>` — a
two-field data class whose whole reason to exist is that `UsageEvents.Event` is getter-only in the SDK
stub, so a test cannot construct the input the code under test receives. That is the second time this
logic was untestable, and it is why it stayed wrong. The platform's event type is translated to a step
in one `when` in `UsageStatsPoller.read`; twelve tests cover the fold with no Android in the way.

A bug found while writing those tests: an empty batch must answer *unchanged*, not *unknown*. The old
code returned null there and its caller read that as "nothing is in front" — so an app that stayed open
stopped being charged, and a block stopped being re-asserted.

### 2. `AccessibilityNodeInfo` recycling — deferred, in writing

**Decision: not doing it.** The reasoning, recorded so it is a decision rather than an omission:

- `recycle()` is a **documented no-op from API 33**. It matters only for API 26–32, and the
  development device is API 37, where neither the fault nor the fix is observable.
- The failure mode of doing it wrong is releasing a node that is still referenced — a crash in the
  process that must stay resident — in exchange for reduced GC pressure on old devices.
- The audit rated it **low**.
- Node access is two places and closely bounded: `BrowserUrlExtractor.extractUrl` (one named view id,
  in a package already known to be a browser) and `UninstallGuard.mentions` (at most 400 nodes, only
  while a lock is held, only on a watched package).

Revisit if Curfew is ever installed on API 26–32 in numbers that justify an emulator test. The cheapest
honest version would be a `use`-style helper plus `finally` at every site, tested at API 30 — not a
change to make blind.

### 3. `PERF-06` — measured, and the audit's claim was wrong

The eager clones are gone (`get_or_insert_with`), and `decide` no longer computes `rule.target.key()`
for rules whose action never reads it — an allocation per rule of every active profile, on every
foreground change, mostly for `Block` rules in a config that is mostly blocks.

Then the claim was measured rather than argued, with `crates/curfew-core/tests/engine_cost.rs`
(`#[ignore]`d, so no timing threshold sits in CI to go flaky):

```text
  rules |    keys |      per call |  per rule        <- all-metered config, worst case
     10 |       1 |     7.73µs     | 773ns
    100 |       1 |    59.76µs     | 597ns
   1000 |       1 |   595.14µs     | 595ns            <- flat per rule
   2000 |       1 |     1.19ms     | 595ns
```

**Flat per-rule cost. This is not O(n²).** The audit said `charged_keys` uses `Vec::contains`, which is
true — but the vector only ever holds the keys that *matched*, and for any observation narrower than
`WholeDevice` that is a handful, so the quadratic term never materialises. The note the audit wanted
("worth a note as rule counts grow") was unactionable because nobody had asked at what *n*; the answer
is that it does not grow.

The ordering is where the real cost was, in the config a person actually writes:

```text
  10 metered rules among N total |   per call
                            100 |  597.00ns
                            500 |    2.54µs
                           2000 |   10.69µs
```

About **5 ns per block rule** once the action is asked about first, against ~595 ns per rule for a
metered one. Before the reorder a block rule cost what a metered rule did: the target matcher ran and
its answer was thrown away.

**No tier, and here is the threshold for one.** `decide` — the whole policy — costs ~620 ns per rule in
a config: 10 rules ≈ 8 µs, 100 ≈ 60 µs, 1000 ≈ 0.6 ms. The audit's own target for an indexed engine was
~0.5 µs, which this misses by ~16× at ten rules, and its proposed fix was to index rules by target kind.
That is the real tier boundary: **below roughly 200 rules (≈125 µs) a scan is fine and an index is
unnecessary complexity; above it, an index discriminated by target kind would remove the ~600 ns per
non-matching kind.** No config in this repository is anywhere near it, so a tiering mechanism would be
code nobody crosses. The measurement is committed so the decision can be re-made with a number if rule
counts ever grow.

### 4. `R-02` — evidence gathered, and the premise does not hold

Checked against the SDK rather than argued: `AccessibilityServiceInfo` is `Parcelable`, but the only
route an ordinary app has to another service's record is
`AccessibilityManager.getEnabledAccessibilityServiceList(Int)`, which answers with the *enabled*
services and their capabilities. The per-service `packageNames` delivery filter is not part of what
that returns.

So the claim this finding rests on — that "some apps check whether they appear in the service's
`packageNames` filter, and being excluded is a positive signal" — describes a check no app can perform.
And the filter has a real cost: it is a **delivery** filter, so anything not listed gets no events at
all, which means a hand-added browser would silently stop having its site rules enforced, and the list
would have to be kept in sync with a user-editable set compiled into the APK. That is precisely the
failure class this audit round was about.

**Decision: not adding it, with the reasons here rather than in a commit message.** What the finding
asked for that *is* sound — an early return for sensitive packages — is done, in code, for every
service.

### 5. `PAY-02` — verified on the device

The runtime surface shrinking is D4's `ServiceSurface`, and it was confirmed against the live service
on a Galaxy S24 (API 37), read from `dumpsys accessibility` with both services enabled and a config of
52 rules containing no url or keyword rule:

```text
Service[label=Curfew app blocking, id=.../ForegroundTracker,
        retrieveInteractiveWindows=false, fetchFlags=0, capabilities=0,
        eventTypes=TYPE_WINDOW_STATE_CHANGED, notificationTimeout=0]
Service[label=Curfew site blocking, id=.../UrlReaderService,
        retrieveInteractiveWindows=false, fetchFlags=384, capabilities=1,
        eventTypes=TYPE_WINDOW_STATE_CHANGED, notificationTimeout=0]
```

Read carefully, because that is three findings closed at once:

- **`eventTypes=TYPE_WINDOW_STATE_CHANGED`** on the *reader* — no `TYPE_WINDOW_CONTENT_CHANGED`. The
  subscription is narrowed to what a config with no web rule can use. That is `PAY-02`.
- **`fetchFlags=384`** on the reader is `FLAG_REPORT_VIEW_IDS` (0x80) plus
  `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` (0x100). The flag that makes site blocking work at all survived
  the narrowing — which is the failure `ServiceSurface` was shaped to make impossible, since it mutates
  the service's own info rather than rebuilding one.
- **`retrieveInteractiveWindows=false`** on the tracker, with `fetchFlags=0` and `capabilities=0`: the
  app-only mode holds nothing that can read a window. That is `B-01`.

**The widening half is implemented but not observed**, and why is worth recording. Adding a url rule
and restarting produced the *app-only* branch of the guard — "App blocking only cannot read a window,
so these rules cannot be enforced" — because the force-stop had cleared
`enabled_accessibility_services`. That is correct behaviour for app-only mode, and it is also the
reminder that these services cannot be enabled from `adb` on this device: the shell's writes to the
accessibility settings are reverted, so the toggle is a real user tap and nothing should be built on
the assumption otherwise. The config was restored byte-for-byte afterwards (52 rules, 3 profiles, no
url rules — verified).

`ServiceSurfaceTest` covers the widening decision itself. What remains unobserved is only whether this
OEM accepts the wider `setServiceInfo`; the narrowing call — the one in doubt, because it is the one
that could have dropped `flagReportViewIds` — is proven to have been accepted.

### Verification, third round

`cargo test --workspace` green · clippy clean · `cargo fmt --check` clean ·
**328 Android tests, 0 failures** (260 app, 68 policy) · three `#[ignore]`d measurement tests in
`crates/curfew-core/tests/engine_cost.rs` · `PAY-02` and `B-01` observed on the device.

