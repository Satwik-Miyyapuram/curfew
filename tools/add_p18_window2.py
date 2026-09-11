"""Assert the downtime banner in the window harness, and add its mutations (P1-8)."""
import pathlib

P = pathlib.Path(__file__).resolve().parent.parent / "tools" / "check_window.py"
text = P.read_text(encoding="utf-8")

if "testDowntimeNotice" in text:
    raise SystemExit("already applied")

OLD = """let offers = {};
"""
NEW = """let offers = {};
// The window enforcement was down before this run (P1-8). Null for an ordinary start.
let downtime = null;
"""
assert OLD in text, "the prelude anchor is missing"
text = text.replace(OLD, NEW, 1)

OLD_REPLY = "running: running.slice(), offers } };"
NEW_REPLY = "running: running.slice(), offers, downtime } };"
assert OLD_REPLY in text, "the status reply anchor is missing"
text = text.replace(OLD_REPLY, NEW_REPLY, 1)

CHECKS = '''
// --- P1-8: the window enforcement was down -----------------------------------------------------
//
// `ARCHITECTURE.md` §10 promises that a service which was killed, crashed or never started reports the
// exact window it was down. Android has done this since `Downtime.kt`; Windows had nothing, so a service
// killed during a timer lock stopped enforcing everything behind it and left no record the user could
// see. The notice goes at the top of the Now page, because it is a statement about the trustworthiness
// of everything below it.

async function testDowntimeNotice() {
  // Nothing to report: no banner, which is the ordinary case and must not regress.
  reset();
  running = [];
  downtime = null;
  await refresh();
  assertThat("an ordinary start shows no downtime notice",
             !body.innerHTML.includes("was not running") && !body.innerHTML.includes("was restarted"),
             "a notice appeared with nothing to report");

  // A gap with no reboot: the case nothing caught before.
  reset();
  running = [];
  downtime = { from: 1000, to: 1000 + 7200, rebooted: false, sessions: 1 };
  await refresh();
  const shown = body.innerHTML;
  assertThat("a gap is reported on the Now page", shown.includes("Curfew was not running"),
             "the service was unenforced for two hours and the window said nothing");
  assertThat("and the length is in the sentence", shown.includes("2 hours"),
             "the window did not say how long");
  assertThat("and what it cost", shown.includes("One lock was running"),
             "the notice did not say a lock went unenforced");
  assertThat("and it offers a way to clear it", shown.includes('data-act="dismiss-downtime"'),
             "there is no way to acknowledge it");

  // A reboot is described as one, because the two lead a user to different conclusions.
  reset();
  running = [];
  downtime = { from: 1000, to: 1000 + 7200, rebooted: true, sessions: 0 };
  await refresh();
  const rebooted = body.innerHTML;
  assertThat("a restart is described as a restart", rebooted.includes("was restarted"),
             "a machine restart and a service stop read the same");
  assertThat("and says nothing was locked", rebooted.includes("Nothing was locked"),
             "the notice did not distinguish the harmless case");

  // It sits above the sessions, because it is about the record rather than about one block.
  reset();
  running = [{ id: "s1", profile: "deep-work", lock: { ends_at: null, conditions: [] } }];
  offers = { s1: { ends_on_request: true, credential: false, confirm: false, elsewhere: [],
                   peer_release: false, peer_released: false, delayed_release: false,
                   delayed_release_at: null } };
  downtime = { from: 1000, to: 1000 + 7200, rebooted: false, sessions: 1 };
  await refresh();
  const both = body.innerHTML;
  assertThat("the notice is above the session it is about",
             both.indexOf("Curfew was not running") < both.indexOf("is running"),
             "the notice was placed after the thing it qualifies");
}

'''

MARKER = 'console.log(failures === 0 ? "window polling: OK"'
index = text.index(MARKER)
insert_at = text.rfind("\n// ---", 0, index)
assert insert_at != -1, "could not find where to insert the test"
text = text[:insert_at] + "\n" + CHECKS + text[insert_at:]

last = text.rfind("await test")
end_of_line = text.index("\n", last)
text = text[:end_of_line] + "\n  await testDowntimeNotice();" + text[end_of_line:]

ANCHOR = """    (
        "P2-13: send the URL uncapped, so a long one kills the host","""
NEW_MUT = """    (
        "P1-8: drop the downtime notice from the Now page",
        "  const downtime = s.downtime",
        "  const downtime = null && s.downtime",
    ),
    (
        "P1-8: describe a restart as an ordinary stop",
        '${s.downtime.rebooted ? "This machine was restarted" : "Curfew was not running"}',
        '"Curfew was not running"',
    ),
    (
        "P1-8: forget to say what the gap cost",
        '". " + cost + " Treat that stretch as time this machine was not held to.";',
        '". Treat that stretch as time this machine was not held to.";',
    ),
    (
        "P2-13: send the URL uncapped, so a long one kills the host","""
assert ANCHOR in text, "the mutation anchor is missing"
text = text.replace(ANCHOR, NEW_MUT, 1)

P.write_text(text, encoding="utf-8")
print("P1-8 window assertions and mutations added")
