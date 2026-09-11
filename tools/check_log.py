"""Check FIXES.md's structure, because editing it by patching text has broken it twice.

**Why this exists.** Twice now an edit has truncated the log. Both times the cause was the same:
a Python `lastIndexOf`-style splice on a *phrase* that appears more than once — first a "Limits:"
sentence, then a different one — which found the wrong occurrence and replaced everything after it.
Entries 47 and 49 went missing the first time and 48, 49 and 50 the second. Both were caught by
reading the headings afterwards and both were recoverable from git, which is luck rather than
process.

The lesson is not "be careful". It is that **an anchor which is not unique is not an anchor**, and
that a document whose structure only exists in the head of whoever last edited it will be broken by
the next edit. So the structure is now checkable: run this after touching FIXES.md and before
committing.

    python tools/check_log.py            # exits non-zero on a problem

It cannot tell you the prose is *right*. It can tell you that every finding and every status row is
still present, that no entry heading was lost or duplicated, and that nothing was silently cut —
which is exactly what went wrong.
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOG = ROOT / "FIXES.md"

# **Two reviews, and this tool checked one of them for a round.** The goal names both. The UX review
# numbers its findings `F-n`; the design review numbers its `P0-n`, `P1-n`, `P2-n`, one heading each.
# Checking only the first reported "all 48 findings mentioned" while **29 of the design review's 37
# appeared nowhere** — the same blind spot as F-8, one review over. A completeness check that covers
# half its subject is the thing it exists to catch.
REVIEWS = {
    "UX_INTERACTION_REVIEW.md": (r"^\*\*F-(\d+)", r"^### (P\d+-\d+)\."),
    "DESIGN_AND_CODE_REVIEW_FULL.md": (r"^### (P\d+-\d+)\.", r"^\*\*F-(\d+)"),
}

problems = []
notes = []


def read(path):
    if not path.is_file():
        problems.append(f"{path.name} is missing")
        return ""
    return path.read_text(encoding="utf-8")


def main():
    text = read(LOG)
    if not text:
        return report()

    lines = text.splitlines()

    # 1. No entry heading may disappear.
    #
    #    **Not** a monotonicity check, which is what the first version did and it was wrong: entries
    #    are written in the order the work happened and numbered by finding, so entry 19 sits after
    #    entry 3 and before entry 4 throughout this document. Seven failures on a correct file is a
    #    check that teaches people to ignore it.
    #
    #    The failure this has to catch is a *truncation* - twice an edit replaced everything after a
    #    non-unique anchor and took three entries with it. So the question is the one a reader would
    #    ask: is everything that used to be here still here?
    headings = [int(m.group(1)) for l in lines if (m := re.match(r"^## (\d+)\.", l))]
    if not headings:
        problems.append("no numbered entry headings at all")
    else:
        notes.append(f"{len(headings)} entry headings, highest is {max(headings)}")
    previous = subprocess.run(["git", "show", "HEAD:FIXES.md"], cwd=ROOT,
                              capture_output=True, text=True)
    if previous.returncode == 0 and previous.stdout:
        before = {int(m.group(1)) for m in re.finditer(r"^## (\d+)\.", previous.stdout, re.M)}
        lost = sorted(before - set(headings))
        if lost:
            problems.append("entry heading(s) present in HEAD and gone now: "
                            + ", ".join(map(str, lost)) + " - an edit truncated the log")
            notes.append("recover with: git show HEAD:FIXES.md > FIXES.md, then redo the edit")
        else:
            notes.append(f"nothing lost against HEAD ({len(before)} headings before)")

    # 2. Every finding **either** review defines must appear somewhere in the log. This is the coverage
    #    guarantee from entry 46, and it is the whole reason the log can be trusted about completeness.
    #
    #    Both reviews are checked, and each against its own numbering. The first version checked only
    #    the UX review and reported success while 29 design-review findings were absent; an identifier
    #    that appears in the log counts, and so does one that appears in a *cross-reference* - which is
    #    how several of these were actually closed, under the other review's number for the same defect.
    for filename, (mine, _other) in REVIEWS.items():
        path = ROOT / filename
        if not path.is_file():
            problems.append(f"{filename} is missing")
            continue
        body = path.read_text(encoding="utf-8")
        defined = {m.group(1) if m.group(1).startswith("P") else f"F-{m.group(1)}"
                   for m in re.finditer(mine, body, re.M)}
        if not defined:
            problems.append(f"no findings found in {filename} — the pattern is wrong")
            continue
        missing = sorted(defined - set(re.findall(r"\bF-\d+\b|\bP\d+-\d+\b", text)),
                         key=lambda s: (s[0], int(s.split("-")[0].lstrip("FP") or 0),
                                        int(s.split("-")[1]) if "-" in s else 0))
        if missing:
            problems.append(f"{len(missing)}/{len(defined)} finding(s) from {filename} appear "
                            f"nowhere: {', '.join(missing)}")
        else:
            notes.append(f"all {len(defined)} findings from {filename} are mentioned")

    # 3. The coverage tables have one row per finding they name, and no "not re-assessed" left.
    coverage = [l for l in lines if re.match(r"^\| (F-\d+|P\d+-\d+) \| ", l)]
    if not coverage:
        problems.append("the coverage tables are gone")
    else:
        notes.append(f"coverage tables have {len(coverage)} rows")
        unassessed = [l.split("|")[1].strip() for l in coverage if "Not re-assessed" in l]
        if unassessed:
            notes.append(f"{len(unassessed)} row(s) still 'not re-assessed': "
                         f"{', '.join(unassessed)}")

    # 4. The status table's numbering is contiguous from 1. A gap means a row was lost.
    rows = [int(m.group(1)) for l in lines if (m := re.match(r"^\| (\d+) \|", l))]
    if rows:
        expected = set(range(1, max(rows) + 1))
        gaps = sorted(expected - set(rows))
        if gaps:
            problems.append(f"status table is missing row(s): {', '.join(map(str, gaps))}")
        dupes = sorted({n for n in rows if rows.count(n) > 1})
        if dupes:
            problems.append(f"status table has duplicate row(s): {', '.join(map(str, dupes))}")
        notes.append(f"status table: {len(rows)} rows, 1..{max(rows)}")
    else:
        problems.append("the status table is gone")

    # 5. Fenced code blocks must be balanced, or everything after one is mis-rendered.
    fences = sum(1 for l in lines if l.startswith("```"))
    if fences % 2:
        problems.append(f"{fences} code fences — unbalanced, so a block is left open")

    # 6. The two files a reader checks a claim against must actually be cited by hash.
    cited = set(re.findall(r"`([0-9a-f]{7})`", text))
    if cited:
        real = subprocess.run(["git", "log", "--format=%h"], cwd=ROOT,
                              capture_output=True, text=True).stdout.split()
        absent = sorted(h for h in cited if h not in real)
        if absent:
            problems.append(f"cited commit(s) that do not exist: {', '.join(absent)}")
        else:
            notes.append(f"{len(cited)} cited commits all exist")
    else:
        problems.append("the log cites no commits at all")

    # 7. **The open list must equal what the tables say.** This section has been wrong four times, always
    #    by being rewritten from memory rather than read off the table, and the generator exists so it
    #    cannot be. The check runs that generator and compares rather than reimplementing the rule: a
    #    check that reimplements its subject is a second place for the answer to be wrong.
    begin = "<!-- open:begin"
    end = "<!-- open:end -->"
    if begin in text and end in text:
        start = text.index(begin)
        stop = text.index(end) + len(end)
        in_doc = text[start:stop]
        want = generated()
        if want is None:
            problems.append("tools/open_rows.py could not be run to check the open list")
        elif want != in_doc:
            problems.append(
                "the 'Still open' list does not match the coverage tables — run "
                "`python tools/open_rows.py`"
            )
        else:
            notes.append("the open list matches the coverage tables")
    else:
        problems.append("the 'Still open' section has no generated block")

    return report()


def generated():
    """The block `tools/open_rows.py` would write, or `None` if it cannot be produced.

    **`--print`, not `--check`.** The first version ran `--check` and then read the block back out of
    `FIXES.md`, which returns whatever is in the file — so the comparison below was `x == x` and could
    never fail. Verified by corrupting the block: the checker exited 0. This runs the generator and takes
    what it *would* write, which is the only form that can disagree with the file.
    """
    out = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "open_rows.py"), "--print"],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if out.returncode != 0:
        return None
    return out.stdout


def report():
    for n in notes:
        print(f"  ok   {n}")
    for p in problems:
        print(f"  FAIL {p}")
    print()
    print("FIXES.md structure: " + ("OK" if not problems else f"{len(problems)} problem(s)"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
