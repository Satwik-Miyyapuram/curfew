Curfew is a distraction blocker that keeps its promises: a lock that is running cannot be talked
out of ending early, by you or by anyone with administrator rights on the machine.

## Downloads

| Machine | File |
| --- | --- |
| Ordinary 64-bit PC (Intel, AMD) | `curfew-x64.zip` |
| Windows on ARM (Snapdragon, Surface Pro X) | `curfew-arm64.zip` |

Unzip it, then from an administrator terminal:

```
curfew install
```

`INSTALL.txt` in the archive says the rest, including how to get out.

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
