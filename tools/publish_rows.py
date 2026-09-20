"""Republish the design-review coverage table in FIXES.md from tools/design_rows.py.

The table is generated rather than typed because 37 rows of status is the kind of thing that gets one
row wrong, and this document has had a coverage claim be wrong three times. Running this after changing
a status keeps the two in step; `tools/check_log.py` would not catch a stale row, so the drift is
checked here instead.

    python tools/publish_rows.py
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOG = ROOT / "FIXES.md"


def main():
    generated = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "design_rows.py")],
        capture_output=True, text=True, cwd=str(ROOT),
    ).stdout
    if generated.startswith("Traceback"):
        print("design_rows.py failed:\n" + generated[:600])
        return 1
    rows = generated.split("<!--")[0].rstrip().splitlines()

    text = LOG.read_text(encoding="utf-8")
    lines = text.splitlines()

    found = [i for i, line in enumerate(lines) if re.match(r"^\| P\d+-\d+ \| ", line)]
    if not found:
        print("no design-review rows in FIXES.md")
        return 1
    head, last = found[0] - 2, found[-1]
    if not lines[head].startswith("| Finding"):
        print(f"expected a table header at line {head + 1}, found: {lines[head][:60]}")
        return 1

    before, after = len(lines[head:last + 1]), len(rows)
    lines[head:last + 1] = rows
    LOG.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  replaced {before} line(s) with {after} — {len(found)} findings")

    # And prove it, because writing a table from a script is exactly how it drifts.
    doc = LOG.read_text(encoding="utf-8")
    stale = [r for r in rows if r.strip() not in doc]
    if stale:
        print(f"  FAIL {len(stale)} row(s) did not land")
        return 1
    print("  ok   every generated row is in the log")
    return 0


if __name__ == "__main__":
    sys.exit(main())
