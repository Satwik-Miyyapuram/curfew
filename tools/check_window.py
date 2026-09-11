"""Run the window's page against a fake DOM and a fake service, and check the polling fixes.

**Why this exists.** `crates/curfew-app/ui/app.html` is the one substantial piece of logic in this
repository with no executable test. There is no JS test framework and no `package.json`, and the Rust
tests beside it can only assert that strings are *present* in the page — `app.rs` greps `PAGE` for
`type="password"`, which is a good check and a shallow one.

Three findings in `DESIGN_AND_CODE_REVIEW_FULL.md` are all in this file and all one subject: `refresh()`
runs every second and rebuilds the world (P2-1, P2-2, P2-3). The review calls them "cheap,
self-contained", and reading them is not enough. My first attempt at the P2-2 fix **froze the sidebar
countdown**, because the guard it added returned before the live pill was written — a worse bug than the
one it fixed, invisible to a syntax check, and caught only by running the thing.

So this drives the page's own `draw()` and `refresh()` through its real bridge (`window.ipc.postMessage`
in, `window.__curfewReply` out — the same two functions the host uses), and asserts behaviour rather
than the shape of the code.

    python tools/check_window.py      # exits non-zero on a regression

Requires node, which is not otherwise a dependency of this repository; `node --check` was already being
run against this file by hand, and a real harness is strictly better than that.
"""
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAGE = ROOT / "crates" / "curfew-app" / "ui" / "app.html"

PRELUDE = r"""
// ---- a DOM just large enough for draw() -------------------------------------------------------
function makeNode(id) {
  return {
    id, _html: "", rebuilt: 0, dataset: {}, style: {},
    classList: { toggle() {}, add() {}, remove() {} },
    get innerHTML() { return this._html; },
    set innerHTML(v) { this._html = v; this.rebuilt++; },
    querySelector() { return null }, querySelectorAll() { return []; },
    contains() { return false }, addEventListener() {}, focus() {},
  };
}
const nodes = {};
const body = (nodes.body = makeNode("body"));
const pill = (nodes.livepill = makeNode("livepill"));
const side = (nodes.side = makeNode("side"));

global.document = {
  getElementById: (id) => nodes[id] || (nodes[id] = makeNode(id)),
  querySelector: (sel) => (sel === ".side" ? side : null),
  querySelectorAll: () => [],
  addEventListener() {},
  createElement: () => makeNode("tmp"),
  body: makeNode("document.body"),
};

// The page ends with `setInterval(refresh, 1000)`, which would keep node alive for ever. Nothing here
// needs a real timer: every test calls `refresh()` itself, which is exactly what the interval does.
global.setInterval = () => 0;
global.clearInterval = () => {};

// ---- the service, behind the page's own bridge ------------------------------------------------
//
// The page talks to the host through exactly two functions, and they are the two the real host uses:
// `call()` posts `{id, kind, ...}` to `window.ipc.postMessage`, and the host answers by invoking
// `window.__curfewReply(id, json)`. Standing in for those exercises the page's real request path
// rather than a stubbed-out one.
const replies = { configReads: 0, statusCalls: 0, statusSeq: 0, held: [] };
let holdNextStatus = false;
let running = [];

function buildReply(message) {
  if (message.kind === "config") {
    replies.configReads++;
    return { ok: true, value: { profiles: [], weekly: [], calendar_rules: [] }, path: "x.toml" };
  }
  const request = message.payload && message.payload.request;
  if (request === "status") {
    replies.statusCalls++;
    const now = ++replies.statusSeq;
    const reply = { ok: true, value: { response: "status", now, running: running.slice() } };
    if (holdNextStatus) {
      // Held rather than answered, so a test can let a later call overtake this one.
      holdNextStatus = false;
      replies.held.push({ id: message.id, reply });
      return null;
    }
    return reply;
  }
  if (request === "stats") return { ok: true, value: { response: "stats" } };
  return { ok: true, value: { response: "ok" } };
}

global.window = {
  ipc: {
    postMessage(json) {
      const message = JSON.parse(json);
      const reply = buildReply(message);
      if (reply !== null) global.window.__curfewReply(message.id, JSON.stringify(reply));
    },
  },
};

function releaseHeld() {
  for (const h of replies.held.splice(0)) {
    global.window.__curfewReply(h.id, JSON.stringify(h.reply));
  }
}
"""

CHECKS = r"""
// ---- the checks -------------------------------------------------------------------------------
let failures = 0;
function check(name, ok, detail) {
  console.log((ok ? "  ok   " : "  FAIL ") + name + (ok || !detail ? "" : " - " + detail));
  if (!ok) failures++;
}

async function settle() {
  for (let i = 0; i < 30; i++) await Promise.resolve();
}

function reset() {
  drawnHtml = null;
  body.rebuilt = 0;
  state.page = "now";
  state.status = null;
  state.trouble = null;
  state.config = null;
  state.configError = null;
  running = [];
}

// --- P2-2: an unchanged redraw must not rebuild the body, and the pill is still written -------
//
// Two assertions, and the second is the one that matters. The first version of this test advanced
// `state.status.now` and expected the body to change — it does not, because with no running session
// `pageNow()` renders "Nothing is running" and never mentions the clock. **The test was wrong, not the
// page**, and it is recorded because the tempting response to that failure is to go and "fix" the
// guard.
async function testRedraw() {
  reset();
  state.status = { response: "status", now: 1, running: [] };
  draw();
  check("the first draw rebuilds the body", body.rebuilt === 1, `rebuilt ${body.rebuilt}`);
  const pillAfterFirst = pill.rebuilt;
  draw();
  check("an identical draw does not rebuild the body", body.rebuilt === 1, `rebuilt ${body.rebuilt}`);
  check("but the pill is written every time, not guarded",
        pill.rebuilt === pillAfterFirst + 1, `pill written ${pill.rebuilt - pillAfterFirst} times`);

  // A real change to the markup must still get through: a running session is one.
  running = [{ id: "s1", profile: "deep-work", lock: { ends_at: 1 + 600 } }];
  state.status = { response: "status", now: 2, running: running.slice() };
  draw();
  check("a changed page does rebuild the body", body.rebuilt === 2, `rebuilt ${body.rebuilt}`);
}

// --- P2-2's near-miss --------------------------------------------------------------------------
//
// The live pill is regenerated every tick and lives outside `#body`. The first version of the P2-2 fix
// returned before writing it, so the countdown in the sidebar froze — a worse bug than the scrolling
// one it was fixing, and the reason this harness exists at all.
async function testPillStillUpdates() {
  reset();
  const session = { id: "s1", profile: "deep-work", lock: { ends_at: 1001 + 600 } };
  state.status = { response: "status", now: 1001, running: [session] };
  draw();
  const first = pill.innerHTML;
  check("the pill shows a countdown", first.includes("left"), JSON.stringify(first));

  // The session card in the body also carries a countdown, so the body legitimately rebuilds here —
  // the assertion is about the *pill*, which is the thing that lives outside `#body` and that the
  // first version of the fix froze. See the comment above this test.
  state.status = { response: "status", now: 1002, running: [session] };
  draw();
  check("the pill follows the clock", pill.innerHTML !== first, "the countdown froze");
}

// --- P2-3: a slow refresh must not overwrite a newer one ---------------------------------------
async function testRefreshOrdering() {
  reset();
  holdNextStatus = true;
  const stale = refresh();     // starts first, blocks on its held status
  await settle();
  const fresh = refresh();     // starts second, completes first
  await fresh;
  await settle();
  const winner = state.status.now;

  releaseHeld();               // the stale run now finishes
  await stale;
  await settle();
  check("a stale refresh does not overwrite a fresh one", state.status.now === winner,
        `status.now is ${state.status.now}, expected ${winner}`);
}

// --- P2-1: the config is not re-read on every tick ---------------------------------------------
async function testConfigCadence() {
  reset();
  const before = replies.configReads;
  ticksSinceConfig = CONFIG_EVERY;
  await refresh();
  const afterFirst = replies.configReads;
  check("the first refresh reads the config", afterFirst === before + 1,
        `${replies.configReads - before} reads`);

  for (let i = 0; i < CONFIG_EVERY - 1; i++) await refresh();
  check("the next nine ticks do not", replies.configReads === afterFirst,
        `${replies.configReads - afterFirst} extra reads`);

  await refresh();
  check("the tenth does", replies.configReads === afterFirst + 1,
        `${replies.configReads - afterFirst} reads`);

  refreshConfigSoon();
  await refresh();
  check("refreshConfigSoon forces the next read", replies.configReads === afterFirst + 2,
        `${replies.configReads - afterFirst} reads`);
}

(async () => {
  await testRedraw();
  await testPillStillUpdates();
  await testRefreshOrdering();
  await testConfigCadence();
  console.log();
  console.log(failures === 0 ? "window polling: OK" : `window polling: ${failures} problem(s)`);
  process.exit(failures === 0 ? 0 : 1);
})();
"""


def capture():
    """Run the harness and return its combined output without printing it. For `mutate()`, which has
    to inspect the output rather than just its exit code."""
    blocks = re.findall(r"<script>(.*?)</script>", PAGE.read_text(encoding="utf-8"), re.S)
    harness = PRELUDE + blocks[-1] + CHECKS
    # Written to the system temp directory rather than beside this script: it is a build artifact of
    # running the check, and a harness file left in \	ools/\ is one somebody has to wonder about.
    # Nothing reads it after ode\ does.
    path = pathlib.Path(tempfile.gettempdir()) / "curfew_window_harness.js"
    path.write_text(harness, encoding="utf-8")
    try:
        out = subprocess.run(["node", str(path)], capture_output=True, text=True,
                             cwd=str(ROOT), timeout=60)
    except subprocess.TimeoutExpired:
        return "TimeoutExpired: the page is waiting on something"
    return (out.stdout or "") + (out.stderr or "")


def run():
    if not PAGE.is_file():
        print(f"  FAIL {PAGE} is missing")
        return 1
    blocks = re.findall(r"<script>(.*?)</script>", PAGE.read_text(encoding="utf-8"), re.S)
    if not blocks:
        print("  FAIL app.html has no <script> block")
        return 1

    harness = PRELUDE + blocks[-1] + CHECKS
    # Written to the system temp directory rather than beside this script: it is a build artifact of
    # running the check, and a harness file left in \	ools/\ is one somebody has to wonder about.
    # Nothing reads it after ode\ does.
    path = pathlib.Path(tempfile.gettempdir()) / "curfew_window_harness.js"
    path.write_text(harness, encoding="utf-8")
    try:
        out = subprocess.run(["node", str(path)], capture_output=True, text=True,
                             cwd=str(ROOT), timeout=60)
    except subprocess.TimeoutExpired:
        print("  FAIL the harness did not finish in 60s — the page is waiting on something")
        return 1

    if "--quiet" not in sys.argv:
        print(out.stdout.strip())
    if out.returncode != 0 and out.stderr.strip():
        print(out.stderr.strip()[:1500])
    return 1 if out.returncode != 0 else 0

MUTATIONS = [
    (
        "P2-2: rebuild the body on every tick (the original bug)",
        "  if (html !== drawnHtml) {\n    drawnHtml = html;\n    body.innerHTML = html;",
        "  if (true) {\n    drawnHtml = html;\n    body.innerHTML = html;",
    ),
    (
        "P2-2 near-miss: return early, freezing the live pill",
        "  if (html !== drawnHtml) {\n    drawnHtml = html;\n    body.innerHTML = html;\n    // After every redraw, because every redraw replaces the nodes that need it.\n    makeInteractive(body);\n  }",
        "  if (html === drawnHtml) return;\n  drawnHtml = html;\n  body.innerHTML = html;\n  // After every redraw, because every redraw replaces the nodes that need it.\n  makeInteractive(body);",
    ),
    (
        "P2-3: drop the ordering ticket, so a stale refresh wins",
        "  const answer = await askService({ request: \"status\" });\n  if (mine !== refreshTicket) return;",
        "  const answer = await askService({ request: \"status\" });",
    ),
    (
        "P2-1: re-read the config on every tick",
        "  if (ticksSinceConfig >= CONFIG_EVERY) {",
        "  if (true) {",
    ),
]



# ---- mutation mode ----------------------------------------------------------------------------
#
# `python tools/check_window.py --mutate` breaks each fix in the exact way the finding describes and
# requires the harness to notice. A guard that has never been seen to fail is not known to guard
# anything, and the first attempt at this used PowerShell string literals that silently did not apply:
# three of four printed `changed: False` and then "OK", which reads exactly like a guard that cannot
# fail. So every replacement below is verified to have changed the file before the harness runs.
def mutate():
    original = PAGE.read_text(encoding="utf-8")
    bad = 0
    for name, old, new in MUTATIONS:
        if old not in original:
            print(f"  ERROR {name}: the replacement text is not in app.html, so nothing was tested")
            bad += 1
            continue
        PAGE.write_text(original.replace(old, new, 1), encoding="utf-8")
        try:
            output = capture()
        finally:
            PAGE.write_text(original, encoding="utf-8")

        # **A mutation that does not parse is not a caught mutation.** The first version of this
        # runner scored a non-zero exit as CAUGHT, and one of the four mutations left a dangling brace
        # — so it "passed" by making the harness unparseable, which proves nothing at all. That is the
        # same failure as a vacuous assertion, one level up: the guard cannot fail because it never
        # ran. It is treated as an ERROR here, and it is why each mutation is also required to produce
        # at least one FAIL line from the checks themselves.
        if "SyntaxError" in output or "ReferenceError" in output:
            print(f"  ERROR     {name}: the mutation did not parse, so nothing was tested")
            print("            " + output.strip().splitlines()[0][:110])
            bad += 1
            continue

        caught_by_assertion = "FAIL" in output
        print(f"  {'CAUGHT' if caught_by_assertion else 'NOT CAUGHT':9} {name}")
        if not caught_by_assertion:
            bad += 1
    good = run() == 0
    print()
    print("  harness green again: " + ("yes" if good else "NO"))
    if not good:
        bad += 1
    print("mutations: " + ("all caught" if bad == 0 else f"{bad} problem(s)"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(mutate() if "--mutate" in sys.argv else run())
