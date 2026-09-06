Curfew is a distraction blocker that keeps its promises: a lock that is running cannot be talked
out of ending early, by you or by anyone with administrator rights on the machine.

## Downloads

| Machine | File |
| --- | --- |
| Ordinary 64-bit PC (Intel, AMD) | `curfew-x64.zip` |
| Windows on ARM (Snapdragon, Surface Pro X) | `curfew-arm64.zip` |
| Android phone or tablet | `curfew-android-unsigned.apk` |

Unzip it, then from an administrator terminal:

```
curfew install
```

`INSTALL.txt` in the archive says the rest, including how to get out.

The Android build is a plain APK, installed by opening the file. It is unsigned for the same
reason the Windows binaries are, so Android will ask you to allow installation from this source;
check the hash before you do. The phone and the PC pair with each other directly, by QR code —
there is no account to make.

## Verify what you downloaded

```
certutil -hashfile curfew-x64.zip SHA256
```

Compare it with `SHA256SUMS` below. Those hashes are of the archives this release's public
GitHub Actions run built from the tagged source — nothing was uploaded from a laptop.

## Your antivirus may complain, and it is not wrong to

Curfew installs a self-restarting service, closes other programs, edits the hosts file, and can
take over DNS. That is the blocking mechanism, and it is also what a heuristic scanner is
looking for. These builds are unsigned — an EV code-signing certificate costs a few hundred
dollars a year and this project has no revenue — so expect SmartScreen on first run and the
occasional generic `Trojan:Win32/Wacatac` flag.

Check the hash rather than clicking through the warning. If the hash matches and the scanner
still objects, it is a false positive; you can report it at
<https://www.microsoft.com/en-us/wdsi/filesubmission>. If the hash does *not* match, please open
an issue — that is the case worth hearing about.

Or build it yourself, which is why the licence is what it is:

```
cargo build --release -p curfew-svc -p curfew-tray
```

## No account, no server, no telemetry

Nothing leaves the machine. Sync, when you enable it, is device to device.
