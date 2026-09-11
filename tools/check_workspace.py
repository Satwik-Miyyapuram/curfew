"""Report files that will break a build without appearing in `git status`.

**Why this exists.** This repository lives under `Documents`, which **Google Drive Desktop** syncs, and
Drive writes a `desktop.ini` into every folder it manages. `.gitignore` lists the name, so the files
never show up in `git status` and never reach CI — which is exactly the trap, because **git ignores them
and Gradle does not.**

Gradle's resource merger scans the filesystem. A `desktop.ini` under `android/**/res/` fails the whole
Android build:

    ERROR: .../res/values/desktop.ini: Resource and asset merger: The file name must end with .xml

Fifty-three of them appeared in a single sync pass. There is **no AGP-level ignore for this**: both
`androidResources.ignoreAssetsPatterns` (applied by aapt2, which the merge task runs before) and
`sourceSets["main"].res.exclude` were tried and neither stops the merger. So the only fix is deletion,
and the only defence is noticing before Gradle does.

    python tools/check_workspace.py      # exits non-zero when a build-breaker is present

Deliberately narrow: it looks for files in *build input* directories that no build tool will accept,
rather than trying to police the workspace. A file in `design/` is harmless; a file in `res/` is not.
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Directories the build reads, and the one name known to land in them. A cloud-sync client writes
# desktop.ini into every folder it manages, including these.
SOURCE_DIRS = [
    "android/app/src",
    "android/policy/src",
    "crates",
    "docs",
    "design",
]

# Directories whose *extension* rules the Android resource merger enforces. Kept apart from the list
# above on purpose: the first version of this script applied the extension rule to crates/, and
# immediately reported crates/curfew-app/ui/app.html as a build-breaker. **A checker that cries wolf
# on a source file is worse than no checker**, because the next real one gets ignored.
RESOURCE_DIRS = [
    "android/app/src/main/res",
    "android/policy/src/main/res",
]

STRAY_NAMES = {"desktop.ini", ".DS_Store", "Thumbs.db"}

ALLOWED_RESOURCE_SUFFIXES = {".xml", ".png", ".webp", ".jpg", ".jpeg"}

# Where a stray hurts worst, and it is not the source tree. A cloud-sync client writing into `.git`
# puts a file named `desktop.ini` beside git's own, and **git treats every file under `refs/` as a
# ref** — so `git fsck` reports `badRefContent` and every command warns about a broken ref. 276 of
# them accumulated here, in `refs/`, `objects/` and `logs/`.
#
# Nothing under `.git` is a build input, which is exactly why it went unnoticed for so long: the
# damage is to git itself rather than to the build.
GIT_DIRS = [".git/refs", ".git/logs", ".git/objects", ".git/info", ".git/hooks", ".git/worktrees"]

# Scaffolding that must not be committed. `git add -A` has swept throwaway helpers into a commit twice
# on this branch — a patch script that had already failed on an assertion, and a temporary message file.
# Both are one-shot: their content is the change they produced, and the tree keeps only the checkers and
# generators that are meant to be run again.
#
# Named by prefix rather than by an explicit list, because the next one will not be on the list either.
# `tools/` otherwise holds six scripts, all of which are useful to a reader, so anything matching these
# patterns is by construction something somebody wrote to get one commit out.
SCAFFOLDING = ("add_", "fix_", "patch_", "mutate_", "log_", "_", "tmp_")

# The scripts that are supposed to be here. Everything else in `tools/` is reported.
TOOLS_TO_KEEP = {
    "check_extension.py",
    "check_log.py",
    "check_window.py",
    "check_workspace.py",
    "design_rows.py",
    "open_rows.py",
    "publish_rows.py",
}


def main():
    found: dict[str, str] = {}

    for relative in SOURCE_DIRS:
        root = ROOT / relative
        if not root.is_dir():
            continue
        for path in root.rglob("*"):
            if path.is_file() and path.name in STRAY_NAMES:
                # Clutter rather than a break: no Rust or doc tool scans a directory this way. It is
                # reported anyway because the same sync pass that wrote this one also writes them into
                # `res/`, where it *does* break the build.
                found.setdefault(
                    path.relative_to(ROOT).as_posix(),
                    "cloud-sync metadata; clutter here, and a build-breaker one directory over",
                )

    for relative in GIT_DIRS:
        root = ROOT / relative
        if not root.is_dir():
            continue
        for path in root.rglob("*"):
            if path.is_file() and path.name in STRAY_NAMES:
                # The worst of the three, and reported first for that reason. Under `refs/` git reads
                # it as a ref whose content is not a hash, under `logs/` as a reflog it cannot parse,
                # and under `objects/` as an object that is not zlib.
                found.setdefault(
                    path.relative_to(ROOT).as_posix(),
                    "a file git reads as its own data — `git fsck` reports badRefContent",
                )

    for relative in RESOURCE_DIRS:
        root = ROOT / relative
        if not root.is_dir():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix.lower() not in ALLOWED_RESOURCE_SUFFIXES:
                found.setdefault(
                    path.relative_to(ROOT).as_posix(),
                    f"{path.suffix or path.name} is not a resource type the merger accepts",
                )

    # Scaffolding left in `tools/`. Only something already tracked matters — an untracked file shows up
    # in `git status` anyway, and the two that caused this lived in a commit because `git add -A` ran
    # while they were on disk.
    tools = ROOT / "tools"
    if tools.is_dir():
        for path in sorted(tools.iterdir()):
            if not path.is_file() or path.name in TOOLS_TO_KEEP:
                continue
            if path.suffix == ".py" and path.name.startswith(SCAFFOLDING):
                found.setdefault(
                    path.relative_to(ROOT).as_posix(),
                    "throwaway scaffolding in `tools/`, which `git add -A` has swept into a commit twice",
                )
            elif path.suffix == ".py":
                # A new checker is fine; it just has to be a decision rather than an accident, so it is
                # named in `TOOLS_TO_KEEP` if it belongs here.
                found.setdefault(
                    path.relative_to(ROOT).as_posix(),
                    "a script in `tools/` that is not in TOOLS_TO_KEEP — add it there if it belongs",
                )

    if not found:
        print("  ok   no strays in the build's input directories")
        return 0

    print(f"  FAIL {len(found)} file(s) that do not belong in the source tree:")
    for path, why in sorted(found.items())[:25]:
        print(f"         {path}  ({why})")
    if len(found) > 25:
        print(f"         ... and {len(found) - 25} more")
    print()
    print("  Delete them. A cloud-sync client writes desktop.ini back into every folder it manages,")
    print("  and Gradle's resource merger scans the filesystem, so .gitignore does not help:")
    print()
    print("      Get-ChildItem -Recurse android,crates -Filter desktop.ini -Force | Remove-Item -Force")
    return 1


if __name__ == "__main__":
    sys.exit(main())
