"""Generate the design-review coverage table rows for FIXES.md from what was actually checked.

Kept as a script rather than typed by hand because 37 rows of status is the kind of thing that gets
one row wrong, and this branch has already had a coverage claim be wrong twice. The statuses below are
the ones established by reading the code, with the file and line recorded for each.

    python tools/design_rows.py > /tmp/rows.md

Anything not in `STATUS` is `not re-assessed`, which is a real status: it means nobody has checked, and
saying so is what keeps the table honest.
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Verified statuses. `fixed` and `open` were both established by reading the named code this branch;
# `partial` means part of the finding is closed and the rest is named.
STATUS = {
    "P0-1": ("have", "entry 1 — `Lock::Timer` removed from `claimable`"),
    "P0-2": ("have", "entry 2 — the service judges locks against the trusted clock"),
    "P0-3": ("have", "entry 3 — `Stop` refused while a lock runs; uninstall fails shut"),
    "P1-0": ("have", "entry 25 — an explicit ACL on `%ProgramData%\\Curfew`, and the watchdog image verified by content"),
    "P1-1": ("have", "entry 24 — the read is bounded and `serve` is concurrent"),
    "P1-4": ("have", "entry 11 — the ration is enforced by the type"),
    "P1-5": ("have", "entry 10 — `[emergency]` validated"),
    "P1-3": ("partial",
             "**partly fixed** (entry 53). The lock-removing case is closed: `restore_sessions` can no longer "
             "end a running session. `observe_releases` still assigns `released` wholesale, which *adds* "
             "`PeerRelease` evidence rather than removing locks — the opposite direction, and it needs the "
             "op-log signature checked at that boundary rather than a merge rule"),
    "P1-6": ("fixed",
            "**fixed** (entry 64). `LockSet::offers` in the core is now the only place that decides what "
            "a surface may offer, and `Status.offers` carries it per session — the shared verdict the "
            "review said belonged where the dead `State.lock` field sat. The window used to render **no "
            "release at all** for a `DeviceCredential`, `Token`, `Challenge` or `RestartRequired` lock, "
            "and sent the irrevocable peer release on one click with no confirmation. It now offers the "
            "24-hour release through a confirm sheet, asks before the peer release, and names the "
            "conditions no page can satisfy. The tray reads the same predicate"),
    "P1-7": ("fixed",
             "**fixed** (entry 58). `assembleRelease` now signs when given a key via "
             "`keystore.properties` or `CURFEW_KEYSTORE_*`, and stays unsigned without one, so CI is "
             "unchanged. A key is never generated in CI: Android needs the same key for an in-place "
             "update, so a per-build key would mean no release could ever be upgraded"),
    "P1-9": ("fixed",
             "**fixed** (entry 59). `state.json.locked` is an out-of-band witness whose *existence* means a "
             "lock was running; `load` consults it before answering `Fresh`, so a deletion reports `Lost`, "
             "which keeps the watchdog alive. Written before the state and removed last, so the worst a "
             "crash can do is the safe direction. **Honest limit**: deleting this file too gets the old "
             "behaviour, so it raises the cost by one file rather than preventing it"),
    "P1-10": ("fixed",
              "**fixed** (entry 60). The last config that parsed is kept beside the state as "
              "`curfew.toml.good` and used when the live file is unreadable, so the rules behind a running "
              "lock keep being enforced. An empty config remains the last resort, because a machine "
              "holding a lock must still start, but it is no longer the first answer"),
    "P1-11": ("fixed",
              "**fixed** (entry 61). The fetch is hoisted out of the enforcer lock — taken twice, briefly "
              "for the two values it needs — so a slow subscription cannot stall `serve()` and with it the "
              "24-hour release. And a failing source backs off (30 s doubling to 10 min) instead of being "
              "retried every two seconds against a 20-second timeout. **The mutex half is not covered by a "
              "test**: moving the fetch back under the lock would not fail anything"),
    "P1-12": ("fixed",
              "**fixed** (entry 62). `logging.rs` installs one sink at service startup writing to "
              "`%ProgramData%\\Curfew\\curfew.log` and to stderr, rolling at 2 MB with one previous file "
              "kept; 62 call sites redirected off `eprintln!`. **Not verified by running the service**: the "
              "startup call is guarded textually because that entry point cannot be exercised here"),
    "P1-13": ("partial",
             "**fixed on Windows** (entry 54). `Config::rules_weakened_by` is consulted before a reload is "
             "adopted, so a config that would enforce less than a running session promised is refused. Two "
             "comments that claimed this already worked were false — `Session` has no rules field — and are "
             "corrected. **Android not covered**: `commitConfig` takes a weakening edit without the check"),
    "P2-1": ("fixed", "**fixed** (entry 53) — the config is re-read on a ten-second cadence"),
    "P2-2": ("fixed", "**fixed** (entry 53) — an identical redraw no longer rebuilds the body"),
    "P2-3": ("fixed", "**fixed** (entry 53) — a stale refresh can no longer overwrite a fresh one"),
    "P2-4": ("fixed", "**fixed under F-24** — the `window.__curfewUser` read is gone"),
    "P2-5": ("have", "entry 17 — exit paths chosen by a parsed value, not by comparing display strings"),
    "P2-6": ("fixed",
             "**fixed under F-24** — the window no longer draws a password box, and `app.rs:346` asserts the "
             "page contains no `type=\"password\"`"),
    "P1-2": ("fixed",
             "**fixed** (entry 56). The extension guessed its own identity and fell back to `chrome.exe`, so "
             "a Zen, LibreWolf, Waterfox, Arc, Chromium or Opera GX user was never trusted under "
             "their real name and had the browser closed outright. The host now reads its own parent "
             "process, which *is* the browser, and overrides the message's claim"),
    "P2-10": ("fixed",
              "**fixed** (entry 56) for the CLI. `upsert_weekly` returns `Upserted::{Added, "
              "Replaced, AlreadyPresent}` and `curfew add-window` reports which, instead of printing "
              "`Added` for a window it had discarded. **Android not covered**: the FFI still "
              "discards the outcome"),
    "P2-12": ("fixed",
              "**fixed** (entry 56). `ARCHITECTURE.md` advertised in-page element blocking the "
              "manifest cannot implement (no `content_scripts`, `scripting` or "
              "`declarativeNetRequest`); corrected in both places it appeared. And the functional "
              "half: a rule starting while a matching page was already open never took effect, which "
              "`tabs.onActivated` and `windows.onFocusChanged` now fix"),
    "P2-19": ("fixed",
              "**fixed** (entry 57). Every read from the shared folder went through `std::fs::read` "
              "with no size cap, unlike the LAN path. `read_capped` is now the only reader, sharing "
              "`lan::MAX_FRAME`"),
    "P2-20": ("fixed",
              "**fixed** (entry 56). `total_sessions` was the sum of the per-day session counters, "
              "so a session across midnight counted twice in the number the UI prints as blocks "
              "kept. A test had enshrined the bug as intent; both corrected"),
    "P2-7": ("fixed",
             "**fixed** (entry 63). `deny_unknown_fields` on the ten types a user writes — `Config`, "
             "`Resolver`, `Profile`, `Rule`, `Action`, `Refill`, `WeeklySchedule`, `CalendarSource`, "
             "`CalendarSchedule`, `EmergencyPolicy`. `Action` and `Refill` are internally tagged, so "
             "`refil = \"daily\"` was silently defaulting. Only the config: the state file and op-log are "
             "read by other versions, where refusing an unknown field would break forward compatibility"),
    "P2-9": ("fixed",
              "**fixed** (entry 65). Parse success only ever meant the text carried `BEGIN:VCALENDAR`, so a provider's auth-expiry placeholder or a truncated export replaced the last good copy and released every block it was driving. `curfew_ics::event_count` now tells the two apart, and a document with no events keeps the cache serving. **The false positive is deliberate and tested**: a genuinely emptied subscription keeps serving the old copy once one exists"),
    "P2-13": ("fixed",
              "**fixed** (entry 66). A frame past 64 KiB was an error, and the host treats a read error as an unresynchronisable stream, so one long URL killed the host and the service then closed the browser for having stopped beating. The length is in the header, so `read_message` now consumes and discards the frame instead, and the extension caps the URL at 8 KiB before sending it — truncation rather than omission, because a URL's host and path are at the front"),
    "P2-17": ("fixed",
              "**fixed** (entry 67). `ipc::ask` still has no deadline — the stream type does not support one — so what is bounded is the *count*: the window caps in-flight calls and answers the page at the cap rather than spawning a thread every 500 ms forever. Fixing it also exposed a real leak on the service side, where `serve` released its connection slot with a statement after the handler that a panic skips — under a comment claiming the opposite. Both sides share `capacity` now"),
    "P1-8": ("fixed",
             "**fixed** (entry 69), the Windows half. `Downtime::detect` reads the gap between the last "
             "trusted tick and now, `Enforcer::note_start` records it on the first pass — the one place "
             "`now` is trusted and the boot counter still holds the previous run's numbering — and both "
             "the Now page and the tray report it, cleared by `Request::DismissDowntime` and written to "
             "the log. **Android's half of the finding is untouched**: it already implements this. The "
             "review's second claim — that Android's polling is not adaptive — is **not done**: the "
             "Windows tick is 2 s regardless of whether a session is running"),
    "P2-8": ("open",
              "**verified open.** The ICS parser is the weakest component here and the finding lists distinct gaps: a bare `YYYYMMDD` `DTSTART` is not treated as all-day, so an all-day entry with no `DTEND` fails the overlap test and is dropped entirely; a negative `BYMONTHDAY` is discarded by `filter_map`, after which an empty list means *unconstrained*, which is fail-open; and recurrence is partial generally. **Not done**: each is a separate parser change with its own RFC cases, and doing one badly is worse than leaving all of them named"),
    "P2-11": ("open",
              "**verified open.** `curfew remove` deletes a window or calendar rule with no session-state query, while `README.md` and `INSTALL.txt` tell the user nothing short of the 24-hour release shortens a lock. The blast radius is bounded — a running session keeps its own copy — but the expectation the docs set is not met. **Not done**: `curfew-cli` depends only on `curfew-core`, so mirroring the uninstall refusal means giving the CLI an IPC path and a behaviour for *no service installed*, which is a first-class case rather than an error"),
    "P2-14": ("open",
              "**verified open.** Every overlay's text lives in one `thread_local` and `WM_PAINT` reads that slot, so a second `show()` overwrites it before the first window paints and the earlier notice renders the later text. **Not done**: Win32 window code with no test harness here, and the fix — per-window text rather than a shared slot — restructures the paint path rather than patching it"),
    "P2-15": ("open",
              "**verified open.** The overlay is positioned with `GetSystemMetrics(SM_CXSCREEN/SM_CYSCREEN)`, the primary display in physical pixels, with no `MonitorFromPoint`/`GetMonitorInfoW` and no `WM_DPICHANGED`, so on a multi-monitor or scaled desk the notice can land on the wrong screen or off a scaled one. **Not done**: the same reason as P2-14 — placement that cannot be verified on this host, and a blind change would be worse than a named gap"),
    "P2-16": ("fixed",
              "**fixed** (entry 68), though not the way the review proposed. **Its suggested fix — move "
              "the watch into the service — cannot be done**: a service is in session 0, which has no "
              "interactive desktop, so the user session's foreground window is not addressable from "
              "there. The only process that can answer is the tray, and the tray is what is gone. So the "
              "gap is reported instead: `Rule::needs_foreground` says which rules depend on it, "
              "`foreground_warning` names the profiles that stopped being enforced, `Status` carries both "
              "so a surface can warn *before* the action, and `QUIT_NOTE` no longer claims the service "
              "\"keeps enforcing everything you asked for\""),
    "P2-18": ("open",
              "**verified open.** `wire.rs` retries the whole pending list whenever any entry is accepted and `accept` runs a full Ed25519 verification each time, so a batch delivered in reverse order costs O(n²) verifications. **Not done**: a performance defect with no correctness consequence, bounded by `MAX_FRAME`; the fix — verify once and remember — is a caching change to the accept path that deserves its own tests rather than a rushed one"),
}

NOTES = {}


def main():
    review = (ROOT / "DESIGN_AND_CODE_REVIEW_FULL.md").read_text(encoding="utf-8")
    found = re.findall(r"^### (P\d+-\d+)\. (.+)$", review, re.M)
    if not found:
        raise SystemExit("no findings found — the heading pattern is wrong")

    print("| Finding | Sev | Status, as verified |")
    print("| :--- | :--- | :--- |")
    counts = {"fixed": 0, "partial": 0, "open": 0, "have": 0, "unassessed": 0}
    for fid, title in found:
        sev = fid.split("-")[0]
        if fid in STATUS:
            kind, detail = STATUS[fid]
        else:
            kind, detail = "unassessed", NOTES.get(
                fid, "**Not re-assessed** — nobody has read this one against the code")
        counts[kind] += 1
        print(f"| {fid} | {sev} | {detail} |")

    print()
    total = len(found)
    print(f"<!-- {total} findings: {counts['have']} already recorded, {counts['fixed']} fixed, "
          f"{counts['partial']} partly fixed, {counts['open']} verified open, "
          f"{counts['unassessed']} not re-assessed -->")


if __name__ == "__main__":
    main()
