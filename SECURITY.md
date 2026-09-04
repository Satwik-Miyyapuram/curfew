# Security policy

## Reporting

Report vulnerabilities through GitHub's private security advisory form on this repository
("Security" tab → "Report a vulnerability"). Please do not open a public issue first.

Expect an acknowledgement within a week. There is no bounty; this is an unfunded project.

## What counts as a vulnerability

Curfew has an unusual threat model: **the user is the adversary, by their own consent** (see
`docs/ARCHITECTURE.md` §8). That changes what is and is not a bug.

**In scope**

- Any way to shorten or clear an active lock without satisfying its conditions — through the UI,
  the config file, sync, clock manipulation, or a paired peer.
- Sync flaws: forging op-log events, replaying them, joining a device group without pairing, or
  reading another device's data from a shared folder.
- Leaking usage data (the app/URL timeline) off the device, to disk unencrypted, or into backups.
- Anything that lets an *external* party start, stop or observe someone's sessions.
- Privilege escalation via the Windows service, or a path allowing a standard user to affect it.

**Not in scope**

- A user with root, an unlocked bootloader, or Windows administrator rights removing the app.
  Documented and accepted (`docs/DECISIONS.md` D4); we raise the cost, we do not make it
  impossible.
- The 24-hour delayed release (`docs/GAPS.md` D1). It is deliberate and universal, not an oversight.
- SmartScreen warnings or antivirus false positives on unsigned binaries (`docs/GAPS.md` B1).
- Bypasses through a second OS user account or work profile, until we say otherwise
  (`docs/GAPS.md` A5, B3).

## Supported versions

Pre-1.0: only the latest release is supported.
