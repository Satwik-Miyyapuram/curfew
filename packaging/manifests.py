#!/usr/bin/env python3
"""Print the winget and scoop manifests for a release.

Both package managers want the same three facts — a version, a URL, and a SHA-256 — and both refuse
the package if the hash is wrong. Generating them from the artifacts that were actually built is the
only way to be sure the hash in the manifest is the hash of the file people download.

    python3 packaging/manifests.py 0.3.0 release/
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO = "curfew/curfew"
ARCHES = {"x64": "x64", "arm64": "arm64"}


def checksum(directory: Path, arch: str) -> str:
    """The SHA-256 of one architecture's archive, read from the file the build wrote."""
    line = (directory / f"curfew-{arch}.zip.sha256").read_text().strip()
    # `sha256sum` writes "<hash>  <name>". Winget wants it upper case; scoop does not care.
    return line.split()[0].upper()


def url(version: str, arch: str) -> str:
    return f"https://github.com/{REPO}/releases/download/v{version}/curfew-{arch}.zip"


def winget(version: str, directory: Path) -> str:
    installers = "\n".join(
        f"""- Architecture: {ARCHES[arch]}
  InstallerType: zip
  InstallerUrl: {url(version, arch)}
  InstallerSha256: {checksum(directory, arch)}
  NestedInstallerType: portable
  NestedInstallerFiles:
  - RelativeFilePath: curfew-{arch}\\curfew.exe
    PortableCommandAlias: curfew
  - RelativeFilePath: curfew-{arch}\\curfew-tray.exe
    PortableCommandAlias: curfew-tray"""
        for arch in ARCHES
    )
    return f"""# Curfew.Curfew.installer.yaml
PackageIdentifier: Curfew.Curfew
PackageVersion: {version}
MinimumOSVersion: 10.0.17763.0
Installers:
{installers}
ManifestType: installer
ManifestVersion: 1.6.0
"""


def winget_locale(version: str) -> str:
    """The default-locale manifest. winget-pkgs rejects a submission without one."""
    return f"""# Curfew.Curfew.locale.en-US.yaml
PackageIdentifier: Curfew.Curfew
PackageVersion: {version}
PackageLocale: en-US
Publisher: Curfew
PackageName: Curfew
License: AGPL-3.0-or-later
LicenseUrl: https://github.com/{REPO}/blob/main/LICENSE
ShortDescription: Distraction blocking that keeps its promises.
Description: |-
  Curfew blocks apps and websites on a schedule your calendar can drive, and syncs between your
  phone and your PC without a server in the middle. A block that is running cannot be ended early
  by anyone, including you; the way out is a release that takes 24 hours.
Tags:
- blocker
- focus
- productivity
ReleaseNotesUrl: https://github.com/{REPO}/releases/tag/v{version}
ManifestType: defaultLocale
ManifestVersion: 1.6.0
"""


def winget_version(version: str) -> str:
    return f"""# Curfew.Curfew.yaml
PackageIdentifier: Curfew.Curfew
PackageVersion: {version}
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.6.0
"""


def scoop(version: str, directory: Path) -> str:
    architecture = ",\n".join(
        f"""        "{'64bit' if arch == 'x64' else 'arm64'}": {{
            "url": "{url(version, arch)}",
            "hash": "{checksum(directory, arch).lower()}",
            "extract_dir": "curfew-{arch}"
        }}"""
        for arch in ARCHES
    )
    return f"""{{
    "version": "{version}",
    "description": "Distraction blocking that keeps its promises. Calendar-driven, syncs without a server.",
    "homepage": "https://github.com/{REPO}",
    "license": "AGPL-3.0-or-later",
    "architecture": {{
{architecture}
    }},
    "bin": ["curfew.exe", "curfew-tray.exe"],
    "notes": [
        "Curfew enforces from a Windows service, which has to be registered once:",
        "    curfew install        (from a terminal opened as administrator)",
        "Uninstalling is refused while a lock is running. That is the point of it;",
        "`curfew release <id>` starts the 24-hour way out."
    ],
    "checkver": {{
        "github": "https://github.com/{REPO}"
    }},
    "autoupdate": {{
        "architecture": {{
            "64bit": {{ "url": "https://github.com/{REPO}/releases/download/v$version/curfew-x64.zip" }},
            "arm64": {{ "url": "https://github.com/{REPO}/releases/download/v$version/curfew-arm64.zip" }}
        }},
        "hash": {{ "url": "$baseurl/SHA256SUMS" }}
    }}
}}
"""


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    version, directory = sys.argv[1], Path(sys.argv[2])

    print("# Package manifests\n")
    print("Copy these into the winget-pkgs and scoop-bucket pull requests. The hashes are of the")
    print("archives in this release and nothing else.\n")
    print("## winget\n")
    for manifest in (winget_version(version), winget_locale(version), winget(version, directory)):
        print("```yaml")
        print(manifest.rstrip())
        print("```\n")
    print("## scoop\n\n```json")
    print(scoop(version, directory).rstrip())
    print("```")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
