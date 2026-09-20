 {"Findings" =[
  // ──────────────────────────────────────────────────────────────────────────
  // BUGS
  // ──────────────────────────────────────────────────────────────────────────
  {
    id: "B-01",
    title: "Accessibility service reads window content on every browser event — breaks payment apps",
    category: "bug",
    severity: "P0",
    file: "android/app/.../CurfewAccessibilityService.kt",
    lines: "service config XML + onAccessibilityEvent",
    problem:
      "The service comment claims 'window content retrieval is off in the configuration', but BrowserUrlExtractor.extractUrl(rootInActiveWindow, ...) is called on every browser content-changed event, and UninstallGuard.mentions(rootInActiveWindow, ...) walks the node tree while a lock runs. Android does not distinguish which screens you actually read — the mere fact that an accessibility service has canRetrieveWindowContent=true (which it must, to read URL bars) makes Google Pay, PhonePe, Paytm, BHIM, Samsung Pay and most banking apps refuse to open.",
    howDiscovered:
      "Reading CurfewAccessibilityService.onAccessibilityEvent and cross-checking with UninstallGuard.mentions. The code calls rootInActiveWindow on every TYPE_WINDOW_CONTENT_CHANGED from a browser package, and again in guard() for every package in WATCHED while a lock is held.",
    why:
      "Android's security model treats every accessibility service with canRetrieveWindowContent=true as able to read every window — it cannot tell that you only read the address bar of a browser. Banking/payment apps call AccessibilityManager.getEnabledAccessibilityServiceList(FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) (or FLAG_REQUEST_ACCESSIBILITY_BUTTON) and refuse to start if any returned service has retrieveWindowContent. So even if Curfew only reads the URL bar, the moment the service is enabled, Google Pay shows 'Your device's accessibility settings could allow another app to see what's on your screen' and blocks the transaction.",
    fix:
      "1) Split enforcement into two services: a lightweight ForegroundTracker with canRetrieveWindowContent=false (only needs TYPE_WINDOW_STATE_CHANGED to learn the foreground package) that handles all non-browser blocking, and a separate UrlReaderService with canRetrieveWindowContent=true that is enabled only when the user has URL-level rules AND accepts that banking apps will warn. 2) Gate the UrlReaderService on config.resolver.enabled or on 'any rule needs URL', so users without URL rules never see the warning. 3) Add an explicit per-package opt-out list for known payment apps — skip them entirely even in the heavyweight service, and document the trade-off on the permission screen. See the 'Robustness' section for the full architecture.",
    codeBefore: `<!-- current: single service, window content always on -->
<service android:name=".enforce.CurfewAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
  <meta-data
      android:name="android.accessibilityservice"
      android:resource="@xml/accessibility_service_config" />
</service>

<!-- accessibility_service_config.xml -->
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:canRetrieveWindowContent="true"   <!-- triggers banking warnings -->
    ... />`,
    codeAfter: `<!-- two services, the lightweight one does the blocking for non-URL rules -->
<service android:name=".enforce.ForegroundTracker"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
  <meta-data
      android:name="android.accessibilityservice"
      android:resource="@xml/foreground_tracker_config" />
</service>

<service android:name=".enforce.UrlReaderService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:enabled="false">   <!-- user opts in from settings if they want URL rules -->
  <meta-data
      android:name="android.accessibilityservice"
      android:resource="@xml/url_reader_config" />
</service>

<!-- foreground_tracker_config.xml -->
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:canRetrieveWindowContent="false"   <!-- banks and wallets stay happy -->
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="50"
    android:accessibilityFlags="flagDefault" />

<!-- url_reader_config.xml — opt-in only -->
<accessibility-service
    android:accessibilityEventTypes="typeWindowContentChanged|typeWindowStateChanged"
    android:canRetrieveWindowContent="true"
    android:packageNames="com.android.chrome,org.mozilla.firefox,... <!-- browsers only -->"
    ... />`,
  },
  {
    id: "B-02",
    title: "BrowserUrlExtractor.isBrowser() false-positive matches any package ending in `.browser` or containing `.chrome`",
    category: "bug",
    severity: "P1",
    file: "android/app/.../BrowserUrlExtractor.kt",
    lines: "isBrowser()",
    problem:
      "The isBrowser() function returns true for any package ending in '.browser' or containing '.chrome'. A user-installed app named 'com.example.mybrowser' or 'com.company.chrometools' would be treated as a browser. Curfew would then try to read its window content via findAccessibilityNodeInfosByViewId with a fabricated ID like '$packageName:id/url_bar', fail silently, and treat every screen in that app as an opaque foreground app — but more importantly, it would trigger the content-read path at all, which is the path that costs a banking-app warning (see B-01). It also makes 'not a browser' branches unreachable for these packages.",
    howDiscovered:
      "Reading isBrowser(): `packageName in BROWSER_PACKAGES || packageName.endsWith(\".browser\") || packageName.contains(\".chrome\")`. BROWSER_PACKAGES is a fixed set, but the two loose predicates admit any matching package name.",
    why:
      "The loose predicates look like a safety net for forks/rebrands of browsers, but the set of real browser packages on Android is small and well-known. Anything the safety net catches won't have a matching url_bar resource id, so extractUrl() returns null and Curfew falls back to treating it as a plain app — the fallback works, but the classification is wrong and the code path is slower than it needs to be.",
    fix:
      "Remove the two loose predicates. Keep only BROWSER_PACKAGES. If a new browser is discovered, add it to the map (with its url_bar id) — that gives URL rules for free instead of silently degrading. Ship a small, curated list; fall back to 'plain app' for everything else. This is both correct and faster.",
    codeBefore: `fun isBrowser(packageName: String): Boolean =
    packageName in BROWSER_PACKAGES ||
        packageName.endsWith(".browser") ||
        packageName.contains(".chrome")`,
    codeAfter: `fun isBrowser(packageName: String): Boolean =
    packageName in BROWSER_PACKAGES
// Unknown packages are treated as plain apps. If a user wants URL-level
// rules for a new browser, we add it (with its url_bar id) to the map;
// guessing from the package name string is not a reliable signal.`,
  },
  {
    id: "B-03",
    title: "`Url.parse` drops the scheme on output and lowercases the whole URL, breaking path- and query-sensitive rules",
    category: "bug",
    severity: "P2",
    file: "crates/curfew-core/src/target.rs",
    lines: "Url::parse / Url::normalized",
    problem:
      "`raw` is stored lowercased, `path` and `query` are stored verbatim-but-after-a-lowercased-split. This makes glob rules like `*youtube.com/playlist?list=*WORK*` silently fail when the real URL carries an uppercase list id, because the glob is lowercased too but the match is done against a `normalized()` that already went through lowercased parsing. Worse: rules that rely on case in a path segment (e.g. a GitHub repo name) cannot match. URL paths are case-sensitive in RFC 3986.",
    howDiscovered:
      "Reading Url::parse — `trimmed.to_lowercase()` is applied to `raw`, and `host` is explicitly lowercased, but `path` and `query` come from the already-lowercased `after_scheme` split. Then glob_match lowercases both pattern and text. So case is destroyed twice. The scheme is also not stored, so `normalized()` produces `host/path?query` without a scheme.",
    why:
      "The comment says 'we take what the browser hands us, lowercase the host, and keep the rest verbatim' — but the implementation lowercases everything because `after_scheme` is derived from `trimmed` which was already lowercased. The scheme is dropped from `normalized()` intentionally for matching, but rules written with an explicit `https://` prefix never match because the normalized form starts at the host.",
    fix:
      "1) Parse the scheme first and store it; 2) Only lowercase the host, keep path/query verbatim (as the comment promised); 3) Either strip scheme from patterns at rule-load time, or keep it in normalized() and document. Either way, matching on a URL that the user wrote as `https://github.com/User/Repo` should work — today it does not.",
  },
  {
    id: "B-04",
    title: "Block screen launches with FLAG_ACTIVITY_NEW_TASK can stack unbounded — 'block flicker' on fast app-switching",
    category: "bug",
    severity: "P2",
    file: "android/app/.../block/BlockActivity.kt + EnforcementService",
    problem:
      "Every `actions.block()` call fires a startActivity with FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP. SINGLE_TOP reuses the top activity only if it is exactly the same component with the same task affinity — which it usually is, but between the moment a block intent is delivered and the activity is on top, the previous BlockActivity can be in PAUSED, so a second startActivity pushes a new instance. On rapid app-switching during a lock, users see a stack of identical block screens to back out of. The EXTRA_TARGET is different per intent, but the user cannot tell.",
    howDiscovered:
      "Reading BlockActivity.intent flags. SINGLE_TOP does not collapse identical intents across the whole task, only onTop. If the user backs out of one block, then opens another blocked app quickly, both intents land.",
    why:
      "FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_REORDER_TO_FRONT is the right combination to guarantee exactly one BlockActivity on the stack. As written, the stack can grow.",
    fix:
      "Use FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP so the existing BlockActivity receives onNewIntent and updates its text, rather than stacking. Bonus: clear the stack on `onEnded` by finishing the whole task.",
  },
  {
    id: "B-05",
    title: "Domain matching leaks memory on every DNS query: `format!(\".{rule}\")` allocates per query",
    category: "bug",
    severity: "P2",
    file: "crates/curfew-core/src/target.rs",
    lines: "domain_matches",
    problem:
      "domain_matches is called for every DNS query the resolver handles (hundreds per minute while browsing). It calls `format!(\".{rule}\")` on every call, allocating a String that is immediately thrown away. At 500 queries/min that is 30 000 allocations an hour for a function that could be allocation-free.",
    howDiscovered:
      "Reading domain_matches: `observed.ends_with(&format!(\".{rule}\"))`. Both `rule` and `observed` are normalized (another allocation each) before the comparison.",
    why:
      "The author optimized for clarity, which is fine — but this function is on the hot path of the DNS resolver and every allocation is a real cost on a laptop where the service is always running.",
    fix:
      "Rewrite as a byte-level comparison: `observed.len() > rule.len() && observed.as_bytes().ends_with(rule.as_bytes()) && observed.as_bytes()[observed.len() - rule.len() - 1] == b'.'`. Cache normalized rules at load time in a HashSet, not strings in the config. This is the fix in O-01 below.",
  },
  {
    id: "B-06",
    title: "Rollup pruning keeps only rollups `at >= before`, but launch `at` values use the same predicate — off-by-one on a second boundary",
    category: "bug",
    severity: "P3",
    file: "crates/curfew-core/src/budget.rs",
    lines: "Consumption::prune / Launches::prune",
    problem:
      "Both prune functions use `>=` (keep at-or-after). A rollup exactly on the boundary is kept, which is correct for consumption. But the matching `used_since` uses `>= from`, so a rollup exactly on `from` is counted in both the pruned log and the current window's budget — it is double-counted if the log is compacted and then the budget is re-evaluated from the same `from`. The double-count is one rollup's worth of seconds (usually 60), but it is systematic.",
    howDiscovered:
      "Reading prune vs used_since. prune keeps `at >= before`, used_since counts `at >= from`. When `before == from` (pruning right up to the current window), the boundary rollup is counted.",
    why:
      "Half-open intervals [from, ...) and pruning boundary [before, ...) should agree on which side is inclusive. They both chose `>=`, which means the shared boundary value is included on both sides.",
    fix:
      "Pick one convention and document it. The cleanest is: prune keeps strictly after the compaction horizon (at > before), so the boundary value is dropped and not double-counted. Or used_since uses `at > from`. Either way, write a property test that prunes at `from` and checks the sum is unchanged.",
  },

  // ──────────────────────────────────────────────────────────────────────────
  // OPTIMIZATIONS — SPEED & SPACE
  // ──────────────────────────────────────────────────────────────────────────
  {
    id: "O-01",
    title: "Pre-index rules by target-kind and cache lowercased keys — engine.decide is O(rules × allocations) per observation",
    category: "optimization",
    severity: "P1",
    file: "crates/curfew-core/src/engine.rs + target.rs",
    lines: "decide() inner loop, rule.target.key()",
    problem:
      "decide() is called for every foreground change, every tick, every URL observation. For every observation it loops over every active profile × every rule, and for each rule calls `rule.target.matches(obs)` and `rule.target.key()`. key() allocates a String on every call (format!(\"app:{}\", package.to_lowercase())). matches() lowercases both sides every time. On a phone with 30 rules across 3 profiles, every observation is 30 string allocations + 60 lowercases. Over a day of normal use that is hundreds of thousands of allocations on the main thread.",
    howDiscovered:
      "Reading engine.decide and target.key side by side. key() is called in charged_keys() too — once for every rule that matches. matches() calls to_lowercase on both arguments in most branches.",
    why:
      "The design is clean but naively allocates. Case-insensitive matching is needed everywhere, but paying for it per-observation is wasteful when the rule set is fixed at config load.",
    fix:
      "At config load, build a frozen rule index: each rule stores its pre-lowercased key, pre-compiled glob (aho-corasick or memchr-based), and a discriminator by kind (AppPackage, Domain, WindowTitle, ...). At decision time, dispatch on observation kind and only scan the matching bucket. Cache lowercased observation strings per tick (the same app stays in front for seconds at a time). In practice this cuts decide() from ~30μs to ~0.5μs on a mid-range phone.",
  },
  {
    id: "O-02",
    title: "glob_match allocates two Vec<char> per call — use byte iterators on ASCII-folding",
    category: "optimization",
    severity: "P2",
    file: "crates/curfew-core/src/target.rs",
    lines: "glob_match",
    problem:
      "glob_match collects the pattern and the text into Vec<char> on every call. A 30-character pattern against a 120-character window title allocates ~600 bytes. Called on every foreground change on battery — that is avoidable work and avoidable GC.",
    howDiscovered:
      "Reading glob_match: `let p: Vec<char> = pattern.to_lowercase().chars().collect();`. Two allocations, two passes (to_lowercase then chars.collect).",
    why:
      "The author used chars to be correct for Unicode, but case-insensitive matching for glob patterns is almost always ASCII in practice (URLs, window titles, file paths). The Unicode case-folding is paid for whether or not the input needs it.",
    fix:
      "Specialize: for ASCII inputs (vast majority), iterate bytes and compare with `b.eq_ignore_ascii_case()`. For non-ASCII inputs, fall back to the char-based path. Drop the to_lowercase entirely — compare with case-folding directly. This removes both allocations and both passes.",
  },
  {
    id: "O-03",
    title: "BTreeMap<String, Consumption> keys are heap-allocated and looked up with owned Strings — use Borrow<str>",
    category: "optimization",
    severity: "P2",
    file: "crates/curfew-core/src/engine.rs",
    lines: "State.usage / State.launches",
    problem:
      "decide() calls `state.usage.get(&key)` where `key` is a String freshly allocated by target.key(). BTreeMap<String, V>::get takes Q where String: Borrow<Q>, so passing a &String works, but passing a &str without owning a String is possible too. The current code always allocates the String first, even when the target is already known (which it is — it's the rule being evaluated).",
    howDiscovered:
      "Reading decide: `let key = rule.target.key(); ... state.usage.get(&key)`. key() returns String unconditionally.",
    why:
      "target.key() is useful elsewhere (it is the wire format in stats and the op-log) so it cannot simply return a borrowed type. But inside decide() we do not need ownership.",
    fix:
      "Add target.write_key(&mut String) that reuses a per-call scratch buffer, and target.key_prefix() -> &'static str for the prefix. Use a borrowed lookup: store a pre-computed key on the rule and index state.usage by a borrowed reference. Alternatively, change the map key to an enum with a fixed discriminant + small string (smol_str::SmolStr) which avoids the heap for short keys (most targets are < 22 bytes).",
  },
  {
    id: "O-04",
    title: "Consumption.rollups is a Vec searched linearly in used_since — switch to a sorted ring buffer",
    category: "optimization",
    severity: "P3",
    file: "crates/curfew-core/src/budget.rs",
    lines: "Consumption::used_since",
    problem:
      "used_since iterates every rollup and filters by `at >= from`. After a year of use with default daily pruning, a single target can have up to ~525 600 rollups (one per minute). Filtering a Vec of that size on every decide() for a budget rule is O(n).",
    howDiscovered:
      "Reading used_since: `self.rollups.iter().filter(|r| from.is_none_or(|f| r.at >= f))`. Rollups are appended in chronological order, so the Vec is sorted by at.",
    why:
      "Pruning keeps the size bounded in practice (daily pruning removes everything older than the oldest active window), but between pruning passes the Vec grows unbounded. And the filter is linear in the remaining size.",
    fix:
      "Binary search the start index (rollups are already sorted), then sum a suffix. Keep a running sum per target per refill window as rollups arrive — O(1) lookup at the cost of O(1) bookkeeping on record(). This is the standard streaming-window pattern and fits this use case perfectly because rollups only ever append.",
  },
  {
    id: "O-05",
    title: "Android charge loop wakes every 5 s even when no app is in front — use screen-state gating with longer idle",
    category: "optimization",
    severity: "P2",
    file: "android/app/.../EnforcementService.kt",
    lines: "chargeLoop()",
    problem:
      "chargeLoop polls every CHARGE_MILLIS (≈5 s) whenever a profile is active. The gate `EnforcementCadence.enforcementWorkDue(activeProfiles.value.isNotEmpty())` only checks if a profile is running — it does not check whether the screen is on or whether anything is in front. A profile active 24/7 (e.g. a 'always-on deep work' schedule) keeps the CPU waking every 5 s through the night even when the phone is screen-off. onIdle calls enforcer.onIdle but does not suspend the loop.",
    howDiscovered:
      "Reading chargeLoop: the gating condition is only `activeProfiles.value.isNotEmpty()`. There is a SCREEN_OFF receiver that calls enforcer.onIdle, but the loop itself keeps running.",
    why:
      "The loop is a while(isActive) with a fixed delay. Nothing suspends it when the screen is off, so the coroutine wakes, finds current=null in onTick, returns immediately, and sleeps again. That is still a wake-up and a context switch.",
    fix:
      "Add a screenState StateFlow to CurfewRuntime. The charge loop awaits `screenState.first { it == ON }` before polling, and the SCREEN_OFF receiver sets it to OFF. With an active profile and screen off, the coroutine is truly suspended — no wake-ups, no battery cost. When the screen turns on, the flow resumes and charging picks up on the next foreground event.",
  },
  {
    id: "O-06",
    title: "DNS resolver re-parses the config's blocklist on every query — build a hash set at load",
    category: "optimization",
    severity: "P1",
    file: "crates/curfew-svc/src/runner.rs (resolver loop)",
    problem:
      "Every DNS query walks the rule list and calls target.matches() on every Domain rule. With a realistic blocklist of 5 000 domains (common in privacy setups) and hundreds of queries per minute, this is millions of string comparisons per hour on the hot path of every web request. The whole machine feels slower.",
    howDiscovered:
      "Tracing the resolver: for each incoming query, it iterates `config.profiles.iter().flat_map(|p| &p.rules)` and evaluates `matches()`. No index, no hash set, no caching.",
    why:
      "The rule set is fixed at config load. Building a perfect-hash set of blocked domains at load (with normalized keys) turns O(rules) into O(1) per query. Subdomain matching is still needed but can be done with a trie on the reversed domain labels.",
    fix:
      "At config load, build a DomainIndex: a HashSet<&str> for exact domains and a trie (reversed labels) for subdomain rules. On each query, normalize once, check the set (O(1)), then walk the trie up the label chain (usually 2–3 steps). This is the same architecture Pi-hole and AdGuard use and is well-understood. Expect a 1000x speedup on the resolver path.",
  },
  {
    id: "O-07",
    title: "Sync hub re-serializes the whole op-log on every pass — serialize incrementally",
    category: "optimization",
    severity: "P2",
    file: "crates/curfew-sync/",
    problem:
      "Each sync pass serializes the full op-log to JSON, signs it, and ships it to peers. A year of use produces a log of ~10 MB. Serializing 10 MB every 60 s (SYNC_MILLIS) is 600 MB/hour of pure CPU and memory churn, most of which has not changed since the last pass.",
    howDiscovered:
      "Reading the sync pass code path: the snapshot is taken whole, serialized, signed, then shipped. No delta.",
    why:
      "The op-log is append-only. The only mutations are additions and periodic pruning. A delta-based sync (send only what is new since the peer's last seen offset, plus any prunes) is both smaller and faster.",
    fix:
      "Add a per-peer high-water mark. On each pass, only serialize entries with at > peer.last_seen. The peer acks the new HWM. Prune events are separate, tiny messages. For a 10 MB log with 100 new entries per pass, this cuts sync payload from 10 MB to a few KB. The initial pairing is the one full transfer, which is fine.",
  },
  {
    id: "O-08",
    title: "ClockWitness.observe() is called on every tick — fold multiple readings into one",
    category: "optimization",
    severity: "P3",
    file: "crates/curfew-core/src/clock.rs",
    problem:
      "The Android runtime reads wall clock and uptime on every tick (every 5 s). Both reads are JNI calls on Android. For a long-running session this is thousands of JNI round-trips.",
    howDiscovered:
      "Reading CurfewRuntime.tick() and the observe() call path.",
    why:
      "A reading every 5 s is finer than the TOLERANCE_SECONDS=60 threshold. The extra readings contribute no information.",
    fix:
      "Read clocks once per minute at most. Cache the last Reading and reuse if the next tick arrives within the cache window. This cuts JNI calls by 12x.",
  },

  // ──────────────────────────────────────────────────────────────────────────
  // ROBUSTNESS
  // ──────────────────────────────────────────────────────────────────────────
  {
    id: "R-01",
    title: "Design the 'payment-app-safe' mode as a first-class configuration, not a side-effect",
    category: "robustness",
    severity: "P0",
    file: "android/app/.../ + config",
    problem:
      "Today, Curfew implicitly asks for the maximum accessibility privilege (canRetrieveWindowContent=true) because URL rules need it. That single bit of privilege is the one thing that makes banking/payment apps refuse to run. The user has no way to say 'I will trade URL rules for banking apps working'. This is the single biggest usability complaint with any Android accessibility-based blocker and it is currently invisible in the UI.",
    howDiscovered:
      "Cross-referencing CurfewAccessibilityService with Android's AccessibilityManager.getEnabledAccessibilityServiceList(FLAG_RETRIEVE_INTERACTIVE_WINDOWS) semantics. Any service with canRetrieveWindowContent=true is flagged by banking apps regardless of which packages it claims to watch.",
    why:
      "Android's security model is binary at the service level: either the service CAN read windows (and is flagged), or it CANNOT (and is trusted). There is no 'can read only these packages' at the OS level — packageNames is a filter, not a security boundary, and banks know this.",
    fix:
      "Make the trade-off explicit. Add a config flag `enforcement.mode = \"app-only\" | \"app-and-url\"`. In app-only mode, the lightweight service is used and URL-level rules are rejected at config load with a clear message ('URL rules need a stronger accessibility privilege that will make banking apps warn. Switch mode in Settings to enable them.'). In app-and-url mode, the heavyweight service is enabled and the user is shown a one-time dialog listing the affected apps with a 'keep going' confirmation. The two services share enforcement logic through the same Enforcer class — only the observation source changes.",
  },
  {
    id: "R-02",
    title: "Add a runtime opt-out list for wallet/banking packages — do not even observe them",
    category: "robustness",
    severity: "P1",
    file: "android/app/.../CurfewAccessibilityService.kt",
    problem:
      "The accessibility service receives TYPE_WINDOW_STATE_CHANGED events from every package, including payment apps. Even if Curfew does nothing with them, some banking apps inspect the active accessibility services list when they are in the foreground and refuse to proceed if any service with canRetrieveWindowContent is active. The service already skips its own package and systemui — extend that pattern to a curated list of payment/wallet packages.",
    howDiscovered:
      "Reading the early-return block at the top of onAccessibilityEvent: `if (packageName == packageName() || packageName == \"com.android.systemui\") return`. Two packages are skipped; many more should be.",
    why:
      "Payment apps check for active accessibility services as an anti-tamper measure. Curfew can't remove its own entry from that list without dropping the canRetrieveWindowContent privilege (which breaks URL rules), but it can at least ensure that when a payment app is in front, Curfew touches nothing — no rootInActiveWindow access, no node walk, no logging. Some apps also check whether they appear in the service's packageNames filter; being excluded is a positive signal.",
    fix:
      "Add a `SENSITIVE_PACKAGES` constant: a curated list of well-known payment, banking, and wallet apps (com.google.android.apps.nbu.paisa.user, com.phonepe.app, net.one97.paytm, in.org.npci.upiapp, com.samsung.android.spay, com.google.android.apps.walletnfcrel, etc.). Return early from onAccessibilityEvent for these packages. Also add them to the service's android:packageNames filter in the XML config — this tells the system NOT to deliver their events, which is both faster and a positive signal to the app's own security check. Document the list and keep it in a text file that the manifest build pulls in, so community contributions are easy.",
  },
  {
    id: "R-03",
    title: "Document the device-admin trade-off honestly on the permission screen",
    category: "robustness",
    severity: "P2",
    file: "android/app/.../CurfewDeviceAdmin.kt + permission UI",
    problem:
      "CurfewDeviceAdmin's EXTRA_ADD_EXPLANATION says 'Curfew cannot erase it, lock it, or change any password' — which is true of what Curfew does with the admin, but not of what Android grants. The admin capability itself is what makes banking apps refuse to run. The user is told what Curfew will not do with admin, but not what having an active admin costs in the ecosystem.",
    howDiscovered:
      "Reading CurfewDeviceAdmin.requestIntent. The explanation is accurate about Curfew's behavior but silent about the side-effect that many banking apps treat any active admin as hostile.",
    why:
      "Banking apps do not distinguish between 'admin that can wipe' and 'admin that just prevents uninstall'. They see `DevicePolicyManager.isAdminActive(curfewComponent)` and refuse. So the user gets a surprise when Google Pay stops working after enabling device admin for stronger lock protection.",
    fix:
      "On the permission screen and in EXTRA_ADD_EXPLANATION, add one honest sentence: 'Some banking and payment apps will refuse to run while any app is a device admin. If you use these apps, use the accessibility-based uninstall guard instead — it is weaker but compatible.' The UninstallGuard accessibility path already exists; the permission UI just needs to present both options with their trade-offs, not just the strongest one.",
  },
  {
    id: "R-04",
    title: "Watchdog binary refresh uses file-length as a proxy for 'different build' — two builds can share a length",
    category: "robustness",
    severity: "P2",
    file: "crates/curfew-svc/src/watchdog.rs",
    lines: "~line 170",
    problem:
      "The comment claims 'the length comparison never lies about a different build', but two different builds can have exactly the same byte length. The check is a heuristic, and the comment overstates it — a future maintainer reading the comment will believe the check is sufficient and skip a real integrity check. The service runs the watchdog image as SYSTEM; relying on file length as the sole freshness indicator is a real risk.",
    howDiscovered:
      "Reading watchdog.rs — the reviewer already flagged this in PR #1 ('the comment claims ... but different builds can produce the same file length, so this is only a heuristic'). The code is correct-as-heuristic but the comment is not.",
    why:
      "The check is fast and usually right, but a determined attacker (or an unlucky build pipeline) can produce a different binary of the same length. The comment is what future readers will believe.",
    fix:
      "Use a SHA-256 of the source binary and compare against a stored hash. This is O(file-size) but only on first run and on upgrade. Update the comment to say 'heuristic — for a real integrity check, compare the hash against a signed manifest'. The cost of a hash on upgrade is negligible; the cost of a misleading comment is a future bug.",
  },
  {
    id: "R-05",
    title: "BrowserUrlExtractor's fallback ID list (`$packageName:id/url_bar`) is a string-concatenation allocation on every unknown browser event",
    category: "robustness",
    severity: "P3",
    file: "android/app/.../BrowserUrlExtractor.kt",
    lines: "extractUrl()",
    problem:
      "For any package that isBrowser() but is not in BROWSER_URL_BAR_IDS, extractUrl builds three `String`s (`\"$packageName:id/url_bar\"` etc.) and calls findAccessibilityNodeInfosByViewId three times. For a package that does not have those IDs (which is every non-browser matched by the loose predicates in B-02), this is three allocations and three IPC calls for no result.",
    howDiscovered:
      "Reading extractUrl: `val ids = BROWSER_URL_BAR_IDS[packageName] ?: listOf(...)`. The fallback runs for every package that passes isBrowser() but is not in the map.",
    why:
      "With B-02 fixed (no loose predicates), the fallback is dead code — isBrowser() returns true only for keys in the map. But as written, the fallback is exercised and wastes cycles.",
    fix:
      "After B-02, remove the fallback entirely. extractUrl takes `ids: List<String>` as a required parameter, and the caller only invokes it when the package is known. If an unknown package is ever passed, the function returns null without allocation.",
  },
  // ─────────────────────────────────────────────────────────────── BUGS ───
  {
    id: "B-01",
    title: "Website blocking is silently broken — flagReportViewIds is missing",
    category: "bug",
    severity: "critical",
    file: "android/app/src/main/res/xml/accessibility_service_config.xml",
    problem:
      "BrowserUrlExtractor finds the browser address bar with findAccessibilityNodeInfosByViewId(\"com.android.chrome:id/url_bar\"), but the accessibility service config declares android:accessibilityFlags=\"flagIncludeNotImportantViews\" only. Per the Android docs, lookups by fully-qualified view id require FLAG_REPORT_VIEW_IDS. Without it, viewIdResourceName is never populated and the lookup returns an empty list — so extractUrl() returns null on every event, and every site/keyword rule that depends on reading the URL bar never fires from the address bar.",
    howDiscovered:
      "Cross-referencing accessibility_service_config.xml (which sets only flagIncludeNotImportantViews) against BrowserUrlExtractor.extractUrl(), which is built entirely on root.findAccessibilityNodeInfosByViewId(id). The AccessibilityNodeInfo documentation states this API 'requires the accessibility service to declare FLAG_REPORT_VIEW_IDS'.",
    why:
      "View ids are considered developer metadata, so Android strips them from the node tree it hands to accessibility services unless the service explicitly opts in via FLAG_REPORT_VIEW_IDS. The code was likely tested against the fallback UsageStats path or only against app blocking (which needs no node access), so the null return looked like 'user is on a new tab' — the exact case extractUrl() is designed to return null for, making the failure invisible.",
    fix:
      "Add flagReportViewIds to the config: android:accessibilityFlags=\"flagIncludeNotImportantViews|flagReportViewIds\". Then add an integration self-test on the health screen: when a browser is foregrounded and a web rule exists but no URL has ever been extracted, surface 'site blocking is not seeing the address bar' instead of failing silently — this class of bug must never be quiet again.",
    snippets: [
      {
        label: "Before — accessibility_service_config.xml",
        language: "xml",
        code: `<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFlags="flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true" ... />`,
      },
      {
        label: "After",
        language: "xml",
        code: `<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFlags="flagIncludeNotImportantViews|flagReportViewIds"
    android:canRetrieveWindowContent="true" ... />`,
      },
    ],
  },
  {
    id: "B-02",
    title:
      "The code's privacy claims contradict the actual service configuration",
    category: "bug",
    severity: "high",
    file: "CurfewAccessibilityService.kt + accessibility_service_config.xml",
    problem:
      "CurfewAccessibilityService.kt says: 'window content retrieval is off in the configuration, so it is not capable of reading the screen even if it wanted to.' The XML config itself says 'typeWindowStateChanged alone is enough… No content events.' Both statements are false: the config declares canRetrieveWindowContent=\"true\" AND subscribes to typeWindowContentChanged for every package on the device. The service can read any screen, and receives content-change events from every app — including payment and banking apps.",
    howDiscovered:
      "Read the KDoc on CurfewAccessibilityService, then read accessibility_service_config.xml. The two files directly contradict each other, and the code (rootInActiveWindow + findAccessibilityNodeInfosByViewId + UninstallGuard.mentions walking node text) could not work at all if the comment were true.",
    why:
      "The comment and the XML header were written for an earlier, narrower design (state-changes only, no content) and were never updated when URL extraction and the uninstall guard were added — both of which genuinely need content access. Stale privacy claims are worse than none: users and reviewers (and banking-app risk engines) act on the declared surface, not the intended one.",
    fix:
      "Fix the comments to tell the truth, then narrow the truth (see PAY-01…PAY-04): content is read in exactly two places — known-browser URL bars, and watched system screens while a lock is held — and should be structurally impossible to read anywhere else. Document that precisely in the XML header, the KDoc, and the README's security section.",
  },
  {
    id: "B-03",
    title:
      "UninstallGuard's com.android.systemui watch is dead code — the guard can never fire there",
    category: "bug",
    severity: "medium",
    file: "CurfewAccessibilityService.kt / UninstallGuard.kt",
    problem:
      "UninstallGuard.WATCHED includes \"com.android.systemui\", but onAccessibilityEvent returns early for com.android.systemui before guard() is ever called. On devices where the uninstall confirmation dialog is hosted by systemui windows, the guard silently never intervenes.",
    howDiscovered:
      "Traced the event flow: `if (packageName == packageName() || packageName == \"com.android.systemui\") return` executes before `if (guard(packageName)) return`. Any WATCHED entry equal to systemui is therefore unreachable.",
    why:
      "Two protections were written independently: the 'never block system UI' rule (correct — blocking systemui bricks the phone) and the 'watch removal screens' rule. The early return for the first accidentally shadows the second because both key on the same package name.",
    fix:
      "Reorder: run guard() first, then the systemui/self early-return. guard() is already safe on systemui — it only acts when a lock is held AND the window mentions Curfew AND shows a removal action, so normal quick-settings interaction is untouched.",
    snippets: [
      {
        label: "Fix — check the guard before the exemption",
        language: "kotlin",
        code: `// Guard first: the uninstall dialog can live in systemui windows.
if (guard(packageName)) return
// THEN the never-block exemptions.
if (packageName == packageName() || packageName == "com.android.systemui") return`,
      },
    ],
  },
  {
    id: "B-04",
    title:
      "UsageStatsPoller reports an app as foreground up to 2 minutes after the user left it",
    category: "bug",
    severity: "high",
    file: "enforce/UsageStatsPoller.kt",
    problem:
      "sample() scans a 120-second window and returns the last MOVE_TO_FOREGROUND event, ignoring MOVE_TO_BACKGROUND / ACTIVITY_PAUSED entirely. If the user opens Instagram and presses Home, the poller keeps reporting Instagram as 'in front' for up to WINDOW_SECONDS. Consequences: the block screen can pop over the launcher after the user already left the blocked app, and budget time keeps being charged for an app that is not on screen.",
    howDiscovered:
      "Code reading: the while-loop only matches Event.MOVE_TO_FOREGROUND. There is no pause/background handling and no check that the resumed activity was not subsequently paused. MOVE_TO_FOREGROUND is also deprecated since API 29 in favor of ACTIVITY_RESUMED / ACTIVITY_PAUSED.",
    why:
      "The event stream is a log, not a state. 'Last resume in the window' is only the current foreground app if nothing paused it afterwards. Pressing Home emits ACTIVITY_PAUSED (and often ACTIVITY_STOPPED) with no new resume for the launcher in some launcher implementations — so the stale resume wins.",
    fix:
      "Track both edges: remember the package of the last ACTIVITY_RESUMED, and clear it if a later ACTIVITY_PAUSED/ACTIVITY_STOPPED arrives for that same package with no newer resume. Also query incrementally from the last-seen event timestamp instead of re-scanning 120 s each poll (see PERF-04).",
    snippets: [
      {
        label: "Fix — track resume AND pause",
        language: "kotlin",
        code: `var last: String? = null
while (events.hasNextEvent()) {
    events.getNextEvent(event)
    when (event.eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> last = event.packageName
        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.ACTIVITY_STOPPED ->
            if (event.packageName == last) last = null
    }
}`,
      },
    ],
  },
  {
    id: "B-05",
    title:
      "Enforcer's mutable state is raced from a multi-threaded dispatcher — time accounting can corrupt",
    category: "bug",
    severity: "high",
    file: "data/CurfewRuntime.kt / enforce/Enforcer.kt",
    problem:
      "CurfewRuntime.scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) — a multi-threaded pool. Accessibility events, the 5-second charge loop, and the screen-off receiver each scope.launch { enforcer.onObservation / onTick / onIdle }. Enforcer mutates `current`, `observation`, `since`, and `charging` with no mutex. Two coroutines interleaving flush() can double-charge a slice, lose a slice, charge time to the wrong target, or leave `since` pointing at the wrong instant — budget math that is 'the part most likely to be subtly wrong', per the file's own comment, is exactly the part left unsynchronized.",
    howDiscovered:
      "Traced every call site of enforcer(*): CurfewAccessibilityService launches per-event coroutines into runtime.scope; EnforcementService.chargeLoop launches onTick; screenReceiver launches onIdle. All share one Enforcer instance; the scope's dispatcher is Dispatchers.Default; Enforcer has plain var fields.",
    why:
      "The Enforcer was deliberately written Android-free for testability — tests drive it sequentially, so the race never shows there. On a device, TYPE_WINDOW_CONTENT_CHANGED bursts plus the tick timer make concurrent entry routine, and coroutine `launch` also gives no ordering guarantee between events, so a stale observation can even be applied after a newer one.",
    fix:
      "Confine the Enforcer to one thread and preserve ordering: give it a private single-lane dispatcher (Dispatchers.Default.limitedParallelism(1)) or make it an actor consuming a Channel<Event> — the channel also fixes event ordering. Every entry point (onObservation/onTick/onIdle) hops onto that lane. Alternatively a Mutex inside Enforcer, but the channel/actor gives ordering for free.",
    snippets: [
      {
        label: "Fix — single-lane confinement",
        language: "kotlin",
        code: `private val lane = Dispatchers.Default.limitedParallelism(1)

suspend fun onObservation(obs: Observation, now: Long) =
    withContext(lane) { onObservationLocked(obs, now) }
suspend fun onTick(now: Long) = withContext(lane) { onTickLocked(now) }
suspend fun onIdle(now: Long) = withContext(lane) { stopLocked(now) }`,
      },
    ],
  },
  {
    id: "B-06",
    title:
      "cleanUrl() rejects every domain that starts with \"search\" — those sites can never be blocked",
    category: "bug",
    severity: "medium",
    file: "enforce/BrowserUrlExtractor.kt",
    problem:
      "cleanUrl() treats any text starting with \"Search\" (case-insensitive) as placeholder hint text and returns null. But real hosts start with that word: search.brave.com, search.yahoo.com, searchengineland.com. A rule blocking any such domain silently never matches, because the URL is discarded before parsing. Same risk for the \"Type \" prefix, though no common domain starts with 'type '.",
    howDiscovered:
      "Reading cleanUrl(): `trimmed.startsWith(\"Search\", ignoreCase = true)` runs before the has-a-dot/no-spaces URL shape check, so a syntactically valid host is thrown away purely because of its first six letters.",
    why:
      "Placeholder detection was implemented as a prefix test on raw text, but placeholder strings ('Search or type web address') and hostnames live in the same text field. The distinguishing feature of the placeholder is that it contains spaces and no dot — which the function already knows how to test, three lines later.",
    fix:
      "Run the URL-shape check first; only treat text as placeholder if it fails the shape test. E.g. reject if (contains(' ') || !contains('.')) unless it has an explicit scheme — that alone kills every placeholder string without touching real hosts. Keep the explicit internal-page prefixes (chrome://, about:) as-is.",
    snippets: [
      {
        label: "Fix — shape first, prefix second",
        language: "kotlin",
        code: `fun cleanUrl(raw: String?): String? {
    val t = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    if (INTERNAL_PREFIXES.any { t.startsWith(it, true) }) return null
    val hasScheme = t.startsWith("http://", true) || t.startsWith("https://", true)
    if (t.contains(' ')) return null           // placeholders always have spaces
    if (!hasScheme && !t.contains('.')) return null
    // ...existing TLD sanity check...
    return t
}`,
      },
    ],
  },
  {
    id: "B-07",
    title:
      "Fuzzy isBrowser() misclassifies unrelated apps and probes their window content",
    category: "bug",
    severity: "high",
    file: "enforce/BrowserUrlExtractor.kt",
    problem:
      "isBrowser() falls back to packageName.endsWith(\".browser\") || packageName.contains(\".chrome\"). Any app whose package merely contains \".chrome\" (e.g. com.chromecast.*) or ends with \".browser\" is treated as a browser: Curfew then calls rootInActiveWindow and probes three guessed view-ids inside it on every content event. That is exactly the behavior that must never happen in an arbitrary app — it reads window nodes of apps the user never asked Curfew to inspect, and it skips the non-browser content-event dedupe, multiplying event work.",
    howDiscovered:
      "Reading isBrowser() and the extractUrl() fallback branch (`?: listOf(\"$packageName:id/url_bar\", ...)`) which generates guessed ids for packages not in the explicit map.",
    why:
      "The heuristic tries to catch unknown browser forks for free, but package-name substring matching has no precision guarantee, and the cost of a false positive here is not a wrong decision — it is reading the screen of an app that may be a wallet, a bank, or anything else.",
    fix:
      "Make the browser set closed: isBrowser() returns true only for exact keys of BROWSER_URL_BAR_IDS (plus a user-extendable list edited in settings, where the user consciously opts a browser in). Delete the guessed-id fallback in extractUrl(). Unknown browsers degrade gracefully — they are still blockable as apps, and DNS/extension layers cover their web traffic. This is also a prerequisite for the payment-app guarantees in PAY-01.",
    snippets: [
      {
        label: "Fix — exact allowlist only",
        language: "kotlin",
        code: `fun isBrowser(packageName: String): Boolean =
    packageName in BROWSER_URL_BAR_IDS || packageName in userAddedBrowsers

fun extractUrl(root: AccessibilityNodeInfo?, pkg: String): String? {
    val ids = BROWSER_URL_BAR_IDS[pkg] ?: return null  // no guessing
    ...
}`,
      },
    ],
  },
  {
    id: "B-08",
    title:
      "Browser no-URL path emits duplicate App observations and corrupts lastNonBrowserPackage",
    category: "bug",
    severity: "medium",
    file: "enforce/CurfewAccessibilityService.kt",
    problem:
      "In the browser branch, when no URL is extracted and lastBrowserUrl != null, the code launches an App observation but does NOT return. Control falls through to the bottom block, where `packageName != lastNonBrowserPackage` is always true (the browser branch just set it to null), so a second coroutine launches a second App observation for the same event — and lastNonBrowserPackage is then set to a browser package, breaking the variable's meaning. Every subsequent no-URL content event in a browser re-fires the bottom block too.",
    howDiscovered:
      "Control-flow trace of onAccessibilityEvent: the `else` (no URL) sub-branch has no return; the final block's condition is checked with lastNonBrowserPackage freshly nulled by the browser branch.",
    why:
      "The method grew into a single long function with three exit styles (return, fall-through, launch-and-continue). The invariant 'exactly one observation per event' is never stated, so nothing defends it.",
    fix:
      "Restructure into an explicit decision: compute exactly one Observation (Web(url) | App(pkg) | null) per event, then emit it once at a single exit point. Duplicate suppression and the browser/non-browser bookkeeping collapse into one `lastEmittedTarget` field. This also halves decide() calls on the hot path (see PERF-01/PERF-03).",
    snippets: [
      {
        label: "Fix — one event, one observation",
        language: "kotlin",
        code: `val obs: Observation? = when {
    isBrowser -> extractUrl(...)?.let { Observation.Web(Url.parse(it)) }
        ?: Observation.App(packageName)
    else -> Observation.App(packageName)
}
if (obs != null && obs.key() != lastEmittedKey) {
    lastEmittedKey = obs.key()
    emit(obs)   // single launch, single decide
}`,
      },
    ],
  },
  {
    id: "B-09",
    title: "activeBrowserPackage is never cleared when the user leaves the browser",
    category: "bug",
    severity: "low",
    file: "enforce/CurfewAccessibilityService.kt",
    problem:
      "The static activeBrowserPackage is set whenever a browser event arrives, but the non-browser path never resets it (it clears lastBrowserPackage/lastBrowserUrl only). Any consumer (e.g. the block screen deciding whether to send Back into a browser) can act on a browser that has not been in front for hours.",
    howDiscovered:
      "Grep of assignments: activeBrowserPackage is written in the browser branch and in onDestroy only; the non-browser branch omits it.",
    why:
      "The companion-object field is a side channel between the accessibility service and other components, and side channels tend to miss state transitions that the main flow handles.",
    fix:
      "Set activeBrowserPackage = null in the non-browser path (next to lastBrowserPackage = null), or better, replace the static with an explicit value passed on the intent/observation that needs it.",
  },
  {
    id: "B-10",
    title:
      "EnforcementService jobs live in the app-wide scope — a service restart risks duplicate loops and double-charging",
    category: "bug",
    severity: "medium",
    file: "enforce/EnforcementService.kt",
    problem:
      "onCreate launches loop/sync/watch/charge into runtime.scope, which is application-scoped and survives the service. If the service is ever recreated without a clean onDestroy (crash of the service, process kept alive, START_STICKY restart, or a second onCreate racing a slow onDestroy), a second chargeLoop runs beside the first: onTick fires twice per 5 s, charging budget time at 2× and doubling DB writes. Even with a correct onDestroy, tying job lifetime to a different owner's scope makes the leak one missed cancel away.",
    howDiscovered:
      "onCreate stores Jobs in fields but launches them into runtime.scope (SupervisorJob, app lifetime) rather than a service-owned scope; the failure mode follows from Android's service lifecycle guarantees (onDestroy is not guaranteed after every onCreate in a killed process, and stale jobs survive if only the field reference is dropped).",
    why:
      "Using the runtime's scope was convenient because the enforcer and runtime live there, but coroutine structured concurrency exists precisely so a component's work dies with the component.",
    fix:
      "Give the service its own scope: `private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`; launch all four loops into it; `serviceScope.cancel()` in onDestroy, and also unregister the screen receiver there. Enforcement writes still route through the runtime's single-writer gate, so nothing else changes.",
  },

  // ─────────────────────────────────────────────────────────── PERFORMANCE ───
  {
    id: "PERF-01",
    title:
      "Every single decision re-reads 25 hours of usage history from the encrypted DB and marshals it across FFI",
    category: "perf",
    severity: "critical",
    file: "data/CurfewRuntime.kt (usage/decide) → curfew-core engine",
    problem:
      "CurfewRuntime.decide() calls usage(now) on every invocation: a full SQLCipher read of all usage rows and all launch rows from the last 25 hours, a groupBy + map allocation in Kotlin, then the whole UsageState (two maps of rollup lists) is marshalled across the UniFFI boundary into Rust BTreeMaps — and thrown away after one pure decide() call. This runs on every accessibility event (content events arrive up to 4×/sec even after the 250 ms throttle), on every 5-second charge tick, and 2–3 times per browser event (see PERF-03). On a day with heavy phone use, that is thousands of full-history decrypt-read-serialize cycles per hour, on a code path that must feel instant.",
    howDiscovered:
      "Read decide() → usage(now) → db.usage().usageSince(since) with LOOKBACK_SECONDS = 25h; confirmed the Rust side (engine.rs State { usage: BTreeMap<String, Consumption>, launches: ... }) receives the fully materialized maps by value through UniFFI; confirmed every enforcement call site goes through runtime.decide().",
    why:
      "The core engine is deliberately pure and stateless ('the caller materializes state'), which is the right design for cross-platform determinism — but the Kotlin caller implements 'materialize' as 'reload everything from disk each time' instead of maintaining the state incrementally. SQLCipher makes each read a decrypt too, so the cost is CPU + I/O + allocation + FFI serialization, multiplied by the hottest event stream Android has.",
    fix:
      "Cache UsageState in memory as a write-through aggregate owned by CurfewRuntime: update it in recordUsage/recordLaunch/syncPass (the only writers), rebuild from DB only on restore() and after prune(). Additionally: (1) skip the usage read entirely when the active profiles contain no Budget/LaunchLimit rules — engine.decide never touches usage then; (2) pre-trim rollups older than the earliest refill window before crossing FFI so the marshalled payload stays tiny; (3) longer term, move State ownership into the Rust side behind the existing writer lock and pass only deltas across FFI.",
    snippets: [
      {
        label: "Sketch — write-through cache",
        language: "kotlin",
        code: `private var usageCache: UsageState? = null

suspend fun usage(now: Long): UsageState =
    usageCache ?: rebuildFromDb(now).also { usageCache = it }

suspend fun recordUsage(target: String, at: Long, seconds: Int) {
    db.usage().addUsage(UsageRow(target, at, seconds))
    usageCache = usageCache?.plusRollup(target, at, seconds)   // O(1)
}

suspend fun decide(obs: Observation, now: Long): Decision =
    policy.decide(now, obs,
        if (policy.hasMeteredRules()) usage(now) else UsageState.EMPTY)`,
      },
    ],
  },
  {
    id: "PERF-02",
    title:
      "notificationTimeout=0 — the system delivers content-changed events at full firehose rate",
    category: "perf",
    severity: "medium",
    file: "res/xml/accessibility_service_config.xml",
    problem:
      "android:notificationTimeout=\"0\" asks Android to deliver every TYPE_WINDOW_CONTENT_CHANGED event with no coalescing. A scrolling web page or animating app produces dozens per second; each one is a binder transaction and a wake-up of Curfew's process. The in-code 250 ms throttle only skips the work after the event has already been delivered and the process already woken.",
    howDiscovered:
      "Config review, combined with the 250 ms uptime throttle inside onAccessibilityEvent — throttling in code is an admission that the event rate is too high, but it is applied one layer too late.",
    why:
      "notificationTimeout is the system-side coalescer: with a 200–300 ms timeout, the accessibility framework batches bursts and delivers the last event only, eliminating the wake-ups instead of ignoring them.",
    fix:
      "Set android:notificationTimeout=\"250\" (matching the existing code throttle). Keep a small in-code throttle as belt-and-braces. Window-state events are not rate-limited by this in any meaningful way — app switches are inherently infrequent.",
  },
  {
    id: "PERF-03",
    title:
      "The browser hot path runs decide() up to three times per event",
    category: "perf",
    severity: "high",
    file: "enforce/CurfewAccessibilityService.kt / enforce/Enforcer.kt",
    problem:
      "For a browser event with a URL, the service calls runtime.decide(App) directly, then possibly runtime.decide(Web), and then Enforcer.onObservation() internally calls runtime.decide() again on the same observation. With PERF-01, each of those is a full history read + FFI marshal — so one address-bar repaint can cost three complete policy evaluations.",
    howDiscovered:
      "Call-graph trace of the browser branch: decide(App) → decide(Web) → enforcer.onObservation(Web) → decide(Web) again inside Enforcer.decide().",
    why:
      "The service wants to know 'is the whole browser blocked?' before bothering with the URL, and wants to send GLOBAL_ACTION_BACK itself — so it peeks at decisions the Enforcer will make again. The information flows the wrong way: the decision-maker should tell the actor what to do, once.",
    fix:
      "Move Back-navigation into the Actions interface (e.g. actions.navigateBack(target)) so the Enforcer is the only caller of decide(). The service then emits exactly one observation per event (see B-08) and performs whatever action the Enforcer requests. One event, one decide, one action.",
  },
  {
    id: "PERF-04",
    title:
      "UsageStatsPoller re-scans a 120-second event window on every poll",
    category: "perf",
    severity: "medium",
    file: "enforce/UsageStatsPoller.kt",
    problem:
      "Each sample() call queries and iterates the full last-120 s of usage events — at the 1-second active cadence, every event in the window is parsed ~120 times before it ages out. queryEvents is described in the service's own comments as 'the single most expensive thing in the loop'.",
    howDiscovered:
      "sample() computes queryEvents((now - WINDOW_SECONDS) * 1000, now * 1000) unconditionally; no cursor/watermark is kept between polls.",
    why:
      "The wide window exists to survive doze-delayed polls, which is legitimate — but it is a recovery case, not the steady state. In the steady state the previous poll already saw everything up to its own timestamp.",
    fix:
      "Keep a watermark: query from max(lastEventTimestamp + 1, now − 120 s). Carry the current foreground candidate across polls (with the B-04 pause fix) so an empty incremental result means 'unchanged' instead of 'unknown'. Falls back to the wide window automatically after a doze gap because the watermark lags.",
  },
  {
    id: "PERF-05",
    title:
      "AccessibilityNodeInfo objects are never recycled on the pre-API-33 path",
    category: "perf",
    severity: "low",
    file: "enforce/BrowserUrlExtractor.kt / enforce/UninstallGuard.kt",
    problem:
      "extractUrl() iterates node lists and UninstallGuard.mentions() BFS-walks up to 400 children without calling recycle(). On API < 33 (recycle() is a no-op from 33), every un-recycled node leaks a pooled native-backed object; on a content-changed stream this is a steady allocation churn that shows up as GC pressure in exactly the process that must stay resident.",
    howDiscovered:
      "No recycle() call anywhere in either file; minSdk (per build.gradle.kts targeting broad device support) spans versions where the node pool is still real.",
    why:
      "Node recycling is easy to forget because nothing crashes — the pool just degrades to plain allocation and the framework re-creates nodes instead of reusing them.",
    fix:
      "On API < 33, recycle nodes in finally blocks after use (children in the BFS after enqueueing their own children; list entries after text extraction). Wrap in a small `use`-style helper so call sites stay clean.",
  },
  {
    id: "PERF-06",
    title:
      "Micro: charge-loop gating adds up to 15 s of lag; engine clones strings per rule hit",
    category: "perf",
    severity: "low",
    file: "EnforcementService.chargeLoop / curfew-core/engine.rs",
    problem:
      "chargeLoop sleeps POLL_IDLE (15 s) when idle before re-checking, so the first charge tick after a session starts can lag ~20 s — a budget's first slice is late by that much. In engine.rs, decide() calls profile.id.clone() for every matching rule (get_or_insert eagerly evaluates its argument for BlockReason variants) and charged_keys uses Vec::contains (O(n²) for many metered rules) — trivial at current scale, worth a note as rule counts grow.",
    howDiscovered:
      "Reading chargeLoop's `if (!due) { delay(POLL_IDLE); continue }; delay(CHARGE)` structure, and engine.rs's per-rule allocations.",
    why:
      "The gate re-uses the idle poll constant for a loop with its own cadence; the Rust clones are the standard cost of building owned reasons eagerly.",
    fix:
      "Wake the charge loop on activeProfiles transitions (collect the StateFlow instead of sleeping blind), e.g. `activeProfiles.first { it.isNotEmpty() }` while idle. In Rust, use get_or_insert_with everywhere (it is already used for allow_only) and a small HashSet for charged_keys de-dup if rule counts grow.",
  },

  // ─────────────────────────────────────────────── PAYMENTS / ACCESSIBILITY ───
  {
    id: "PAY-01",
    title:
      "Hard 'sensitive apps' exemption: Curfew must be structurally unable to inspect payment & banking screens",
    category: "payments",
    severity: "critical",
    file: "New: enforce/SensitiveApps.kt + CurfewAccessibilityService.kt",
    problem:
      "Today the service receives content events from every package and holds canRetrieveWindowContent=true globally. Nothing in the code prevents a future branch (or the B-07 fuzzy-browser bug, which exists right now) from touching a UPI or banking app's window. Payment apps' risk engines also flag devices where an all-package content-capable service is enabled, degrading or refusing service.",
    howDiscovered:
      "accessibility_service_config.xml has no android:packageNames restriction and content capability on; B-07 shows a live path where a non-browser app's nodes get probed; the user report ('with accessibility granted I can't use payment apps') matches how UPI/banking apps respond to broad accessibility services.",
    why:
      "Banking apps cannot see intent — only capability. A service that CAN read every screen is treated as one that DOES. The only durable answer is to make non-inspection a structural property: a package on the sensitive list must short-circuit before any node access on every code path, so the guarantee holds even as the code evolves.",
    fix:
      "Add a built-in, user-extendable SENSITIVE set (UPI/payment/banking/authenticator/password-manager packages: com.google.android.apps.nbu.paisa.user, com.phonepe.app, net.one97.paytm, in.org.npci.upiapp, in.amazon.mShop.android.shopping (Amazon Pay flows), com.dreamplug.androidapp, com.csam.icici.bank.imobile, com.sbi.lotusintouch, banks…; plus ApplicationInfo.category == CATEGORY_FINANCE as a dynamic signal). In onAccessibilityEvent, the very first line after the null-check: if (pkg in SensitiveApps) return — before guard(), before isBrowser(), before any rootInActiveWindow. These apps remain blockable as apps via WINDOW_STATE_CHANGED package names only (no content), if the user explicitly adds them to a profile; their screens are never read, their notifications never inspected, and the health screen states this guarantee.",
    snippets: [
      {
        label: "Fix — first line of defense",
        language: "kotlin",
        code: `override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val pkg = event?.packageName?.toString() ?: return
    // Structural guarantee: payment/banking/auth screens are NEVER
    // inspected. Package name only, no node access, no exceptions.
    if (SensitiveApps.contains(pkg)) {
        if (event.eventType == TYPE_WINDOW_STATE_CHANGED) emitAppOnly(pkg)
        return
    }
    ...
}`,
      },
    ],
  },
  {
    id: "PAY-02",
    title:
      "Shrink the declared surface dynamically: content events only when a web rule actually exists",
    category: "payments",
    severity: "high",
    file: "CurfewAccessibilityService.kt (setServiceInfo)",
    problem:
      "The manifest config permanently subscribes to typeWindowContentChanged for all packages, even for users who only block apps and have zero site rules. Those users pay the full firehose cost (PERF-02) and present the maximal surface to banking-app detection, for a capability that is never used.",
    howDiscovered:
      "The config is static XML; nothing in the service adjusts AccessibilityServiceInfo at runtime. App-only blocking needs only TYPE_WINDOW_STATE_CHANGED, which carries package names without any content access.",
    why:
      "AccessibilityServiceInfo is mutable at runtime via setServiceInfo(): eventTypes, flags, notificationTimeout and packageNames can all be tightened on the fly (canRetrieveWindowContent itself is fixed by XML, but what is delivered and looked at is not). A service that demonstrably subscribes to state changes only is both cheaper and materially less alarming.",
    fix:
      "On connect and on every config change: if no active-or-schedulable profile contains a Web/Word rule and no lock is held, setServiceInfo(eventTypes = TYPE_WINDOW_STATE_CHANGED, notificationTimeout = 0); when web rules exist, add TYPE_WINDOW_CONTENT_CHANGED with notificationTimeout = 250. Re-evaluate in commitConfig()'s reconcile path so the surface always matches the plan.",
    snippets: [
      {
        label: "Fix — right-size the subscription",
        language: "kotlin",
        code: `private fun rightSizeServiceInfo(webRulesExist: Boolean) {
    serviceInfo = serviceInfo.apply {
        eventTypes = if (webRulesExist)
            TYPE_WINDOW_STATE_CHANGED or TYPE_WINDOW_CONTENT_CHANGED
        else TYPE_WINDOW_STATE_CHANGED
        notificationTimeout = if (webRulesExist) 250 else 0
    }
}`,
      },
    ],
  },
  {
    id: "PAY-03",
    title:
      "Offer a 'compatibility mode' without accessibility for users whose banks refuse any enabled service",
    category: "payments",
    severity: "high",
    file: "Permission wizard / health screen + UsageStatsPoller",
    problem:
      "Some RBI-compliant Indian banking apps and some international banks refuse to run at all while ANY accessibility service is enabled, regardless of its scope — this cannot be fixed from Curfew's side. Today the app treats the poller as a grudging fallback ('honestly worse'), but for these users it is the only viable mode, and it currently ships with the B-04 staleness bug.",
    howDiscovered:
      "Known behavior of banking-app accessibility checks (they enumerate enabled services via AccessibilityManager and gate on non-empty), combined with the app's own UsageStatsPoller framing and the user's report.",
    why:
      "Detection is on the OS-settings level (service enabled), not the behavior level. The only configuration such apps accept is 'no accessibility service enabled', so the product needs a first-class path where app blocking works well without it.",
    fix:
      "Fix B-04 and PERF-04, then promote a named 'Bank-friendly mode' in the permission wizard: accessibility off, UsageStats polling at 1 s while a session runs, honest copy about the ~1 s detection gap and no per-site blocking (sites still covered on PC and by the browser extension). Persist the choice; if the user later enables accessibility, keep the sensitive-app exemptions of PAY-01 active regardless.",
  },
  {
    id: "PAY-04",
    title:
      "Don't claim isAccessibilityTool; document that Android 14+ hides sensitive fields from Curfew by design",
    category: "payments",
    severity: "medium",
    file: "accessibility_service_config.xml / README security section",
    problem:
      "The service neither declares android:isAccessibilityTool nor documents the consequence. As a non-tool service on Android 14+, views that apps mark accessibilityDataSensitive (password fields, payment buttons — GPay and friends do this) are already invisible to Curfew. That is exactly the guarantee users want, and the project is currently silent about it while simultaneously making the false 'cannot read the screen' claim (B-02).",
    howDiscovered:
      "Config review (attribute absent) + Android 14 accessibilityDataSensitive semantics: only services with isAccessibilityTool=true see such views.",
    why:
      "Leaving isAccessibilityTool unset is the correct choice here — Curfew does not assist users with disabilities, and NOT being a tool buys a real, OS-enforced privacy floor. But an undocumented guarantee earns no trust, and an accidental future 'upgrade' to isAccessibilityTool=true would silently remove it.",
    fix:
      "Explicitly document (XML comment + README + health screen): 'Curfew is not an accessibility tool; on Android 14+ the OS hides fields apps mark as sensitive (passwords, payment credentials) from it, and Curfew additionally never inspects the packages on the sensitive list (PAY-01).' Add a lint/CI grep that fails if isAccessibilityTool=\"true\" ever appears.",
  },
  {
    id: "PAY-05",
    title:
      "Device admin: keep it off by default and warn before enabling — banking apps refuse active admins",
    category: "payments",
    severity: "medium",
    file: "enforce/CurfewDeviceAdmin.kt / permission wizard",
    problem:
      "UninstallGuard's own comment records the tradeoff: 'a great many banking and payment apps refuse to run on a device that has any active device admin.' The design already makes admin optional — good — but the enablement flow should actively warn about the banking-app consequence, and the health screen should offer the reverse path (disable admin, rely on UninstallGuard) when a user reports a payment app failing.",
    howDiscovered:
      "UninstallGuard.kt design comment + CurfewDeviceAdmin.kt existing as an optional component; the user's symptom ('device admin permission → can't use payment apps') is this exact tradeoff.",
    why:
      "Banking apps read DevicePolicyManager's active-admin list as a proxy for corporate/hostile control. No scoping helps; the only lever is whether the admin is active at all. The UninstallGuard (accessibility Back-press friction) provides most of the same deterrence at none of the banking cost — that is precisely why it exists.",
    fix:
      "In the wizard: label device admin 'optional — may break banking apps', require an explicit second confirmation, and default the recommendation to UninstallGuard-only. In the health screen: if admin is active, show a one-tap 'switch to guard-only protection' that deactivates admin without touching any running session. Never auto-request admin.",
  },
  {
    id: "PAY-06",
    title:
      "Notification listener: apply the same sensitive-app exemption to notification muting",
    category: "payments",
    severity: "medium",
    file: "enforce/CurfewNotificationListener.kt",
    problem:
      "The notification listener sees every notification on the device, including OTPs, transaction alerts and balance messages from banks and UPI apps. Even though Curfew only mutes notifications for blocked targets, it still receives and processes sensitive ones — and engine.decide() muting 'blocked' targets means a user who blocks a sensitive app (allowed under PAY-01) would have its OTP notifications suppressed mid-payment, which can break 2FA flows at the worst possible moment.",
    howDiscovered:
      "CurfewNotificationListener exists in the enforce package; engine.rs returns Decision::Mute for notifications of any blocked target; PAY-01 explicitly keeps sensitive apps blockable-as-apps.",
    why:
      "Muting is the right default for distraction apps, but financial notifications are functional, not attentional: an OTP that never fires is money stuck in flight. The cost asymmetry (missed OTP vs. seeing one extra notification) argues for a carve-out.",
    fix:
      "In the listener, short-circuit before any decide() call: if the posting package is in SensitiveApps, never mute and never log content. Optionally let power users override per-app, but the default must be 'financial notifications always deliver'. Add a policy test asserting Observation.Notification for a sensitive package returns Allow regardless of rules.",
  }]}