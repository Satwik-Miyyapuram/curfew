# Versioning and releases

## Version numbers

Semantic versioning, with the pre-1.0 caveat that anything may change before 1.0.

- **Patch** -- fixes, no config or sync-format change.
- **Minor** -- new features; configs remain loadable at the same `CONFIG_SCHEMA_VERSION`.
- **Major** -- breaking change to the config schema, the sync wire format, or the enforcement
  guarantees.

The config document carries its own `CONFIG_SCHEMA_VERSION`, independent of the app version.
Migrations are forward-only. A config from a *newer* version is refused and left untouched rather
than partially loaded (`docs/GAPS.md` E3).

## Sync compatibility

Devices in one group must agree on the op-log format. A release that changes it states the minimum
peer version, and paired devices below it show a "peer needs updating" state rather than silently
failing to sync. A version mismatch never releases a lock.

## Releases

Tagged `vMAJOR.MINOR.PATCH`, built by CI for Windows x86_64 and aarch64 plus the Android APK.
Every artifact is published to GitHub Releases with a SHA-256 checksum. Windows binaries are
unsigned; see `docs/GAPS.md` B1 for why, and for what SmartScreen will say about it.

Channels: GitHub Releases (reference), F-Droid (Android), winget and scoop (Windows).
