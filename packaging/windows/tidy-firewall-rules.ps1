# Remove the Windows Defender Firewall rules left behind by Curfew's test binaries.
#
# Why they exist: every `cargo test` compiles a new executable whose name carries a fresh content
# hash (`curfew_sync-3f1c9a20b7d4e615.exe`). When one of those binds a socket, Windows treats it as
# a program it has never seen, asks whether to allow it, and writes a rule naming that exact path.
# The next run compiles a different hash, so the rule is dead the moment it is written, and they
# pile up — this repository's own machine had thirty-eight.
#
# The workspace no longer produces them: `.cargo/config.toml` sets CURFEW_LAN_LOOPBACK=1, so test
# binaries bind loopback only and Windows never asks. This script is for the ones already there.
#
# It only ever removes rules whose display name is a Curfew *test* binary — a crate name, a hyphen,
# sixteen hex digits, `.exe`. Rules for `curfew.exe`, `curfew-svc.exe` and `curfew-tray.exe` are
# what an installed Curfew runs on and are left exactly as they are.
#
# Run it in an administrator PowerShell (firewall rules are machine state; nothing here can ask for
# that for you):
#
#     powershell -ExecutionPolicy Bypass -File packaging\windows\tidy-firewall-rules.ps1
#
# It lists what it would remove and asks before removing anything. `-WhatIf` lists and stops.

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param()

$pattern = '^curfew_(sync|ffi|core|win|svc|tray|cli|ics)-[0-9a-f]{16}\.exe'

$stale = Get-NetFirewallRule -DisplayName '*urfew*' -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -match $pattern }

if (-not $stale) {
    Write-Host "Nothing to remove: no test-binary firewall rules found."
    return
}

Write-Host "$($stale.Count) firewall rule(s) name a Curfew test binary that no longer exists:"
$stale | ForEach-Object { Write-Host "  $($_.DisplayName)" }

if ($PSCmdlet.ShouldProcess("$($stale.Count) stale Curfew test-binary firewall rule(s)", "Remove")) {
    $stale | Remove-NetFirewallRule
    Write-Host "Removed. Rules for curfew.exe, curfew-svc.exe and curfew-tray.exe were not touched."
}
