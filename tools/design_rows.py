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
             "**partly fixed** (entries 53, 74 and 85). The bypasses the review names are closed: "
             "`restore_sessions` goes through `restore_without_weakening`, so no payload can end a running "
             "session or shorten a lock whatever the caller sends; all five restore methods refuse an "
             "oversized payload **before parsing it**; and a restored `ClockWitness` can no longer move "
             "trusted time **forward**, which is what let one call expire every timer lock. **What is not "
             "closed**: the *first* restore is the startup adoption, and the FFI has no clock of its own to "
             "check it against — the readings are passed in by the platform because this crate cannot take "
             "them — so a forged baseline, or a forged stored blob, is still believed. The witness must "
             "survive a restart or *stop the app, set the clock, start the app* is a way out of every "
             "timed lock, and authenticating it needs the platform keystore (Android Keystore/StrongBox, "
             "DPAPI on Windows), which is a cross-platform piece of work and cannot be verified on this "
             "host. `Boots`/`BootCounter` evidence of a caller's choosing is likewise still accepted. The "
             "earlier version of this row said none of this was fixable, and the review was right that "
             "part of it was"),
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
    "P1-13": ("fixed",
              "**fixed** (entries 54, 79 and 82). Windows refused a reload that would enforce less than a "
              "running session promised; **Android did not**, and `CurfewRuntime.commitConfig` wrote the "
              "config and reconciled with no check at all. The decision is now "
              "`Config::weakening_a_running_session`, shared so the two cannot disagree, and it guards **a "
              "named set of paths, not \"the config\"**: the three FFI calls that change rules "
              "(`remove_rule`, `upsert_rule`, `set_config`), `remove_profile`, which deletes every rule "
              "behind a lock, the whole-document `commit_config`, and the service's startup path — where a "
              "config that *parses* with the rules deleted was previously adopted **and written over the "
              "good copy**. `upsert_rule`, `remove_profile`, `remove_weekly`, `remove_calendar` and the "
              "startup path were each found by mutation or by adversarial review after the row had already "
              "been called fixed, which is why it now enumerates rather than summarises"),
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
    "P2-8": ("fixed",
             "**fixed** (entry 75) for the three defects the review lists in the code. A bare `YYYYMMDD` "
             "`DTSTART` is now recognised as all-day, so an entry with no `DTEND` is no longer a "
             "zero-length event that the overlap test drops. `BYMONTHDAY` is parsed as `i32` and negatives "
             "resolve against the month they are in. And an unparseable `BYDAY`/`BYMONTHDAY` token now "
             "**refuses the recurrence** rather than being dropped — dropping left the constraint list "
             "empty, and empty means *unconstrained*, so the rule widened. **Not done**: the review's "
             "fourth point, that `schedule.rs` and `budget.rs` resolve `minute == 1440` differently, is in "
             "two other crates; and `UNTIL` with a DATE value, which the review calls \"parsed oddly\" "
             "without saying what the right answer is. **And the fourth point is now done too**: the two `local_instant` helpers disagreed about `1440` — `schedule.rs` rolled to the next day at 00:00 and `budget.rs` clamped to 23:59 — so `budget.rs` delegates to the schedule's, which is one rule rather than two that agree today"),
    "P2-11": ("fixed",
              "**fixed** (entry 76). `curfew remove` now refuses when a running session derives from the "
              "id, mirroring the uninstall refusal: `running_from` in the core is the decision, "
              "`curfew_cli::removal_target` names the id, and the service's dispatcher asks over the pipe "
              "it already uses for `Reload`. A service that is not running makes it a no-op, so the "
              "config-first workflow is untouched, and only a *running* session blocks a removal — "
              "otherwise the plan could not be edited without ending a lock first"),
    "P2-14": ("fixed",
              "**fixed** (entry 71). Each window owns its text through `GWLP_USERDATA`, handed over with "
              "`Box::into_raw` and reclaimed on `WM_NCDESTROY`, instead of one `thread_local` that every "
              "`show()` wrote and every paint read — a second notice overwrote the first before it had "
              "painted. **The executable tests cannot catch a mutation of the fix**: `overlay_proc` is a "
              "Win32 callback, so the wiring is guarded at the source level and the tests pin only the "
              "ownership rule's shape"),
    "P2-15": ("fixed",
              "**fixed** (entry 72), for placement. `MonitorFromPoint(GetCursorPos())` plus "
              "`GetMonitorInfoW().rcWork` puts the card on the monitor the user is looking at and inside "
              "its work area, instead of on the primary monitor minus a guessed 72-pixel taskbar. The "
              "arithmetic is a portable function, so it is tested without a display. **DPI awareness is "
              "deliberately not declared**, and the row says so: the font sizes are fixed points, so "
              "declaring it without scaling every dimension would render the notice at a third of its "
              "size on a 200% display"),
    "P2-16": ("fixed",
              "**fixed** (entry 68), though not the way the review proposed. **Its suggested fix — move "
              "the watch into the service — cannot be done**: a service is in session 0, which has no "
              "interactive desktop, so the user session's foreground window is not addressable from "
              "there. The only process that can answer is the tray, and the tray is what is gone. So the "
              "gap is reported instead: `Rule::needs_foreground` says which rules depend on it, "
              "`foreground_warning` names the profiles that stopped being enforced, `Status` carries both "
              "so a surface can warn *before* the action, and `QUIT_NOTE` no longer claims the service "
              "\"keeps enforcing everything you asked for\""),
    "P2-18": ("fixed",
              "**fixed** (entry 73). `receive` verified each entry once up front and then walked each "
              "author's chain in one ascending pass, instead of calling `accept` on every pending entry "
              "every pass. **Measured rather than argued**: with the old loop restored and a test-only "
              "counter in place, 24 entries delivered backwards cost 300 checks — exactly n(n+1)/2 — "
              "against 24 for the fix. `Log::apply` is the seam: everything `accept` does except verify, "
              "documented as requiring an already-verified entry, so the public entry point keeps its "
              "guarantee"),
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
