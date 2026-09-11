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
    "P1-6": ("open",
             "**verified open.** The window has no route to the 24-hour release: `Request::RequestRelease` "
             "has no caller in `curfew-app` (only `curfew-tray/src/main.rs`). This is the documented "
             "last-resort exit and the primary Windows surface cannot reach it"),
    "P1-7": ("open",
             "**verified open.** No `signingConfig` in `android/app/build.gradle.kts`, so a release APK "
             "built here cannot be installed. Either sign it or correct `ARCHITECTURE.md` §12"),
    "P1-9": ("open",
             "**verified open.** `state.rs:102` reports `Loaded::Fresh` when the main file *and* the backup "
             "are both missing, which is exactly the deliberate-deletion case — a crash leaves a backup, a "
             "deletion does not. The comment asserting the two are 'answered the same way' is wrong"),
    "P1-10": ("open",
             "**verified open.** `runner.rs:140` starts with an empty config when it cannot parse one while "
             "sessions are running — fail open. Locks survive, but every rule behind them stops"),
    "P1-11": ("open",
             "**verified open.** `runner.rs:558` calls `feeds.events(…)` while holding the enforcer mutex, "
             "and that same mutex is what `serve()` needs. `TIMEOUT` is 20s against a 2s tick, so one slow "
             "subscription stalls the control channel — including `Status` and the 24-hour release"),
    "P1-12": ("open",
             "**verified open.** No event-log sink in `curfew-svc`: `git grep EventLog` returns nothing, so "
             "every diagnostic it emits goes to stderr of a service nobody reads"),
    "P1-13": ("open",
             "**verified open, and two comments are false.** `Session` has no rules field (`session.rs:47`), "
             "so editing the config removes enforcement while the lock survives — `config.rs:300` claims "
             "otherwise ('the session holds its own copy of what it blocks') and so does `GAPS.md:169`"),
    "P2-1": ("fixed", "**fixed** (entry 53) — the config is re-read on a ten-second cadence"),
    "P2-2": ("fixed", "**fixed** (entry 53) — an identical redraw no longer rebuilds the body"),
    "P2-3": ("fixed", "**fixed** (entry 53) — a stale refresh can no longer overwrite a fresh one"),
    "P2-4": ("fixed", "**fixed under F-24** — the `window.__curfewUser` read is gone"),
    "P2-5": ("have", "entry 17 — exit paths chosen by a parsed value, not by comparing display strings"),
    "P2-6": ("fixed",
             "**fixed under F-24** — the window no longer draws a password box, and `app.rs:346` asserts the "
             "page contains no `type=\"password\"`"),
    "P2-7": ("open",
             "**verified open.** `git grep deny_unknown_fields` returns nothing, so `lockss = [...]` loads "
             "as no locks at all while `curfew-ffi` promises 'a config we cannot fully understand is refused'"),
}

NOTES = {
    "P1-2": "A wrong browser-name guess makes the service hard-kill the browser. **Partly confirmed**: the "
            "extension can emit six names and the service knows twelve, so a browser whose user-agent does "
            "not match its own name reports as `chrome.exe` and its real executable is never trusted. Not "
            "re-assessed end to end.",
}


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
