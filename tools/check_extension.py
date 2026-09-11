"""Run the browser extension against a fake `chrome`, and check the events that enforce rules.

**Why this exists.** `extension/background.js` is the only code in this repository that decides
whether a page loads, and it had no test of any kind — `node --check` proves it parses and nothing
more. P2-12 is entirely about *which events* enforce a rule, which is exactly the kind of thing a
syntax check cannot see and a reader can misread: the two navigation listeners look complete until
you ask what happens to a tab that is already open when the rule starts.

So this stubs the four `chrome` surfaces the extension uses, loads the file, and drives the events.

    python tools/check_extension.py      # exits non-zero on a regression
    python tools/check_extension.py --mutate   # break it each way and require a failure

Requires node, as `tools/check_window.py` does.
"""
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXT = ROOT / "extension" / "background.js"

PRELUDE = r"""
// ---- a `chrome` just large enough for background.js --------------------------------------------
const listeners = { nav: [], history: [], activated: [], focus: [], alarm: [], startup: [], installed: [] };
const calls = [];

function record(name) {
  return (fn) => { listeners[name].push(fn); };
}

const blockedPage = "chrome-extension://curfew/blocked.html";

global.chrome = {
  runtime: { getURL: (p) => "chrome-extension://curfew/" + p, onStartup: { addListener: record("startup") },
             onInstalled: { addListener: record("installed") }, connectNative: () => null },
  alarms: { create() {}, onAlarm: { addListener: record("alarm") } },
  tabs: {
    query: async () => [{ id: 7, url: "https://youtube.com/shorts/abc", windowId: 1 }],
    update: (tabId, opts) => { calls.push(["update", tabId, opts]); },
    onActivated: { addListener: record("activated") },
  },
  windows: {
    WINDOW_ID_NONE: -1,
    get: async () => ({ focused: true }),
    onFocusChanged: { addListener: record("focus") },
  },
  webNavigation: {
    onBeforeNavigate: { addListener: record("nav") },
    onHistoryStateUpdated: { addListener: record("history") },
  },
};

// ---- the native port, replaced ---------------------------------------------------------------
//
// sk() posts the request object and the answer arrives through port.onMessage. Standing in for
// the port means the tests drive the extension through its real request path rather than around it.
let __answer = () => ({ type: "ok" });
let __onMessage = null;
global.__port = {
  postMessage(message) {
    calls.push(["ask", message]);
    const reply = __answer(message);
    // Answered synchronously, in order, as the host's serial relay does.
    if (reply !== null && __onMessage) __onMessage(reply);
  },
  onMessage: { addListener(fn) { __onMessage = fn; } },
  onDisconnect: { addListener() {} },
};
"""

CHECKS = r"""
let failures = 0;
function assertThat(name, ok, detail) {
  console.log((ok ? "  ok   " : "  FAIL ") + name + (ok || !detail ? "" : " - " + detail));
  if (!ok) failures++;
}

const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

(async () => {
  // `beat()` and `check()` run at load; let them finish before inspecting.
  await settle();

  // --- the wiring P2-12 is about ---------------------------------------------------------------
  assertThat("onBeforeNavigate enforces", listeners.nav.length === 1);
  assertThat("onHistoryStateUpdated enforces (SPAs)", listeners.history.length === 1);
  assertThat("activating a tab re-checks it (the live-rule gap)", listeners.activated.length === 1,
        "a rule that starts with the page already open never takes effect");
  assertThat("focusing the browser re-checks it", listeners.focus.length === 1);

  // --- and that a re-check actually blocks -----------------------------------------------------
  calls.length = 0;
  __answer = (m) => (m.type === "check" ? { type: "verdict", blocked: true, reason: "Shorts." } : { type: "ok" });

  listeners.activated[0]({ tabId: 7, windowId: 1 });
  await settle();

  const asked = calls.find((c) => c[0] === "ask" && c[1].type === "check");
  assertThat("the re-check asks the service about the focused tab", !!asked,
        "activating a tab did not produce a check()");
  const redirect = calls.find((c) => c[0] === "update");
  assertThat("a blocked verdict moves the tab to the blocked page", !!redirect,
        "the verdict was not acted on");
  if (redirect) {
    assertThat("and it goes to blocked.html with the URL and reason",
          String(redirect[2].url).startsWith(blockedPage) &&
            String(redirect[2].url).includes("youtube.com"),
          redirect[2].url);
  }

  // A window losing focus to another program is not a moment anybody is reading a blocked page.
  calls.length = 0;
  listeners.focus[0](chrome.windows.WINDOW_ID_NONE);
  await settle();
  assertThat("losing focus does not fire a check", !calls.some((c) => c[0] === "ask" && c[1].type === "check"));

  // --- the heartbeat still carries the focused page, for budgets --------------------------------
  calls.length = 0;
  __answer = () => ({ type: "ok" });
  await beat();
  const beatAsk = calls.find((c) => c[0] === "ask" && c[1].type === "beat");
  // --- P2-13: nothing sent may exceed the host's frame limit -------------------------------------
  //
  // A native message is capped at 64 KiB, and a frame past that used to kill the host — which closed
  // the browser, because a browser whose host has died stops beating. A page can navigate to a URL of
  // any length, so the cap has to be enforced here rather than hoped for.
  calls.length = 0;
  __answer = () => ({ type: "ok" });
  const huge = "https://example.test/" + "a".repeat(200 * 1024);
  await check(7, huge);
  await settle();

  const sent = calls.filter((c) => c[0] === "ask").map((c) => c[1].url || "");
  assertThat("a huge URL is still sent, so the page is checked at all", sent.length > 0,
             "the page was never reported, which is the bypass this avoids");
  assertThat("and it is capped short of the host's frame limit",
             sent.every((u) => u.length <= 8 * 1024),
             `longest was ${Math.max(0, ...sent.map((u) => u.length))} bytes`);
  assertThat("the cap keeps the host and path, so a real rule still matches",
             sent.every((u) => u.startsWith("https://example.test/")),
             "the prefix was lost, so no path rule could match");

  assertThat("a beat reports the focused page", !!beatAsk && beatAsk[1].url === "https://youtube.com/shorts/abc",
        beatAsk ? JSON.stringify(beatAsk[1]) : "no beat");

  console.log();
  console.log(failures === 0 ? "extension: OK" : `extension: ${failures} problem(s)`);
  process.exit(failures === 0 ? 0 : 1);
})();
"""

MUTATIONS = [
    (
        "remove the tab-activation re-check (the P2-12 gap)",
        "chrome.tabs.onActivated.addListener(recheckFocused);",
        "",
    ),
    (
        "remove the window-focus re-check",
        "chrome.windows.onFocusChanged.addListener((windowId) => {\n"
        "  // `WINDOW_ID_NONE` means the browser lost focus to another program, which is not a moment anybody\n"
        "  // is reading a blocked page.\n"
        "  if (windowId !== chrome.windows.WINDOW_ID_NONE) recheckFocused();\n"
        "});",
        "",
    ),
    (
        "P2-13: send the URL uncapped, so a long one kills the host",
        "  return url.length > MAX_URL ? url.slice(0, MAX_URL) : url;",
        "  return url;",
    ),
    (
        "P2-13: drop the URL entirely when it is too long",
        "  return url.length > MAX_URL ? url.slice(0, MAX_URL) : url;",
        "  return url.length > MAX_URL ? null : url;",
    ),
    (
        "stop acting on a blocked verdict",
        "  chrome.tabs.update(tabId, { url: page + query });",
        "  void page; void query;",
    ),
]


def build(source):
    """A harness with the extension's real source, wired to the stubs."""
    # The extension reaches the host through `chrome.runtime.connectNative`; replace the port body so
    # this can answer its own requests.
    wired = source.replace(
        "  port = chrome.runtime.connectNative(HOST);",
        "  port = __port;",
    ).replace(
        "  } catch (e) {\n    port = null;\n    return null;\n  }",
        "  } catch (e) {\n    port = null;\n    return null;\n  }",
    )
    return PRELUDE + wired + CHECKS


def run(mutate=False):
    if not EXT.is_file():
        print(f"  FAIL {EXT} is missing")
        return 1

    original = EXT.read_text(encoding="utf-8")
    if mutate:
        bad = 0
        for name, old, new in MUTATIONS:
            if old not in original:
                print(f"  ERROR {name}: anchor not found, so nothing was tested")
                bad += 1
                continue
            EXT.write_text(original.replace(old, new, 1), encoding="utf-8")
            try:
                rc = run_once()
            finally:
                EXT.write_text(original, encoding="utf-8")
            print(f"  {'NOT CAUGHT' if rc == 0 else 'CAUGHT':9} {name}")
            if rc == 0:
                bad += 1
        rc = run_once()
        print()
        print("  green again: " + ("yes" if rc == 0 else "NO"))
        if rc != 0:
            bad += 1
        print("mutations: " + ("all caught" if bad == 0 else f"{bad} problem(s)"))
        return 1 if bad else 0

    return run_once()


def run_once():
    source = EXT.read_text(encoding="utf-8")
    harness = build(source)
    path = pathlib.Path(tempfile.gettempdir()) / "curfew_extension_harness.js"
    path.write_text(harness, encoding="utf-8")
    try:
        out = subprocess.run(["node", str(path)], capture_output=True, text=True,
                             cwd=str(ROOT), timeout=60)
    except subprocess.TimeoutExpired:
        print("  FAIL the harness did not finish in 60s")
        return 1
    print(out.stdout.strip())
    if out.returncode != 0 and out.stderr.strip():
        print(out.stderr.strip()[:1200])
    return 0 if out.returncode == 0 else 1


if __name__ == "__main__":
    sys.exit(run(mutate="--mutate" in sys.argv))
