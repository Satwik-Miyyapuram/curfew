<#
.SYNOPSIS
    Builds the Curfew MSI.

.DESCRIPTION
    Stages the three programs, the licence in both the forms an installer needs, and hands the lot
    to WiX. The staging directory is outside the repository on purpose: release binaries are not
    something to leave lying next to their own source.

    WiX 5 specifically. Version 6 and later are behind the Open Source Maintenance Fee, and Curfew
    has no revenue to pay it with:

        dotnet tool install --global wix --version 5.0.2
        wix extension add -g WixToolset.UI.wixext/5.0.2
        wix extension add -g WixToolset.Util.wixext/5.0.2

.EXAMPLE
    .\build.ps1 -Version 0.1.0
    .\build.ps1 -Version 0.1.0 -Arch arm64 -BinDir ..\..\target\aarch64-pc-windows-msvc\release
#>
[CmdletBinding()]
param(
    # The product version, three or four numbers. MSI compares these to decide what an upgrade is,
    # and it reads no more than four fields, so a `-rc1` suffix would be silently ignored.
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(\.\d+)?$')]
    [string] $Version,

    [ValidateSet('x64', 'arm64')]
    [string] $Arch = 'x64',

    # Where the release binaries are. Defaults to a plain `cargo build --release` for this machine.
    [string] $BinDir = (Join-Path $PSScriptRoot '..\..\target\release'),

    [string] $OutFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
if (-not $OutFile) { $OutFile = Join-Path $PSScriptRoot "curfew-$Arch.msi" }

$wix = Get-Command wix -ErrorAction SilentlyContinue
if (-not $wix) {
    $candidate = Join-Path $env:USERPROFILE '.dotnet\tools\wix.exe'
    if (Test-Path $candidate) { $wix = $candidate } else { throw "wix is not on PATH. See the help in this file for how to install it." }
} else {
    $wix = $wix.Source
}

# One staging directory per run, removed afterwards: a stale exe from a previous build shipping
# inside an MSI is the kind of mistake nobody notices until a user reports it.
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("curfew-msi-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null

try {
    foreach ($exe in 'curfew.exe', 'curfew-app.exe', 'curfew-tray.exe') {
        $source = Join-Path $BinDir $exe
        if (-not (Test-Path $source)) {
            throw "$source is missing. Build it first: cargo build --release -p curfew-svc -p curfew-app -p curfew-tray"
        }
        Copy-Item $source (Join-Path $stage $exe)
    }

    $licence = Get-Content (Join-Path $root 'LICENSE') -Raw
    Set-Content -Path (Join-Path $stage 'LICENSE.txt') -Value $licence -Encoding UTF8

    # The licence page of the wizard is an RTF control, so the plain text has to become RTF. Only
    # three characters carry meaning in RTF, and every line break has to be said out loud.
    $rtfBody = $licence -replace '\\', '\\' -replace '\{', '\{' -replace '\}', '\}'
    $rtfBody = ($rtfBody -split "`r?`n") -join '\par' + "`r`n"
    $rtf = "{\rtf1\ansi\deff0{\fonttbl{\f0\fnil\fcharset0 Segoe UI;}}\fs16`r`n" + $rtfBody + '}'
    Set-Content -Path (Join-Path $stage 'license.rtf') -Value $rtf -Encoding ASCII

    # The util extension ships one custom-action library per architecture, and an MSI may only call
    # the one matching its own. Getting this wrong fails at install time, not at build time.
    $utilCA = if ($Arch -eq 'arm64') { 'Wix4UtilCA_A64' } else { 'Wix4UtilCA_X64' }
    # Both shipped libraries are 64-bit, so both export the 64-bit entry point.
    $quietExec = 'WixQuietExec64'

    & $wix build (Join-Path $PSScriptRoot 'curfew.wxs') `
        -arch $Arch `
        -bindpath $PSScriptRoot `
        -d Version=$Version `
        -d Stage=$stage `
        -d UtilCA=$utilCA `
        -d QuietExec=$quietExec `
        -ext WixToolset.UI.wixext `
        -ext WixToolset.Util.wixext `
        -o $OutFile
    if ($LASTEXITCODE -ne 0) { throw "wix build failed with exit code $LASTEXITCODE" }

    $hash = (Get-FileHash $OutFile -Algorithm SHA256).Hash.ToLower()
    Write-Host "$OutFile"
    Write-Host "sha256 $hash"
} finally {
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}
