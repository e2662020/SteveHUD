<#
.SYNOPSIS
    End-to-end test of the server half of SteveHUD, without launching Minecraft.

.DESCRIPTION
    Brings the test server up if it is not already running, runs the headless
    protocol probe against it, checks the server log for the delivery evidence,
    and reports PASS or FAIL. Exits non-zero on failure, so it can be wired into
    anything that runs commands.

    This covers: plugin load, channel registration, message construction,
    fragmentation framing, the transport itself, and the envelope plus greeting
    body the client is expected to parse.

    It does NOT cover the Fabric-side receiver or anything drawn on screen. Those
    need a real client; see TESTING.md.

.PARAMETER KeepRunning
    Leave the server running afterwards instead of stopping it.

.EXAMPLE
    .\scripts\smoke-test.ps1
    .\scripts\smoke-test.ps1 -KeepRunning
#>
[CmdletBinding()]
param(
    [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Without this, output captured from a child process is decoded with the console's
# ANSI code page and any non-ASCII character in it arrives as mojibake.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$RepoRoot  = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'test-server'
$LogFile   = Join-Path $ServerDir 'logs\latest.log'
$ProbeDir  = Join-Path $RepoRoot 'tools\protocol-probe'
$Port      = if ($env:MC_PORT) { [int]$env:MC_PORT } else { 25565 }

$failures = @()

function Test-Up {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Invoke-TestServer {
    param([string]$Command)
    & (Join-Path $PSScriptRoot 'test-server.ps1') $Command | Out-Host
}

function Read-LogFrom {
    param([int]$Skip)
    if (-not (Test-Path $LogFile)) { return @() }
    return @(Get-Content $LogFile -Encoding UTF8 | Select-Object -Skip $Skip)
}

Write-Host '=== 1. server ==='
if (Test-Up) {
    Write-Host 'already running'
} else {
    Invoke-TestServer 'start'
    if (-not (Test-Up)) { throw 'server did not come up' }
}

# The log accumulates across runs, so remember where it ended. Without this, a
# failure from an earlier run is re-reported forever and a real regression is
# indistinguishable from stale evidence.
$linesBefore = if (Test-Path $LogFile) { @(Get-Content $LogFile -Encoding UTF8).Count } else { 0 }

Write-Host ''
Write-Host '=== 2. protocol probe ==='
Push-Location $ProbeDir
try {
    $probeOutput = & node probe.mjs 2>&1
    $probeExit = $LASTEXITCODE
} finally {
    Pop-Location
}
$probeOutput |
    Where-Object { $_ -match 'PASS|FAIL|RESULT|checks:|received|^\{' } |
    ForEach-Object { Write-Host $_ }
if ($probeExit -ne 0) { $failures += "protocol probe exited $probeExit" }

Write-Host ''
Write-Host '=== 3. server-side evidence (this run only) ==='
$newLines = Read-LogFrom -Skip $linesBefore

$greeting = $newLines | Select-String -Pattern 'Greeting .*client accepts' | Select-Object -Last 1
if ($greeting) {
    Write-Host $greeting.Line
    if ($greeting.Line -match [regex]::Escape('stevehud:main')) {
        Write-Host 'PASS  the client registered stevehud:main'
    } else {
        Write-Host 'FAIL  the client did not register stevehud:main'
        $failures += 'channel not registered'
    }
} else {
    Write-Host 'FAIL  the plugin logged no greeting (did it load?)'
    $failures += 'no greeting logged'
}

# The plugin warns when it decides a recipient cannot receive. That warning must
# not appear for a client that just registered, or delivery was skipped.
$notDelivered = $newLines | Select-String -Pattern 'has not registered the' | Select-Object -Last 1
if ($notDelivered) {
    Write-Host "FAIL  $($notDelivered.Line)"
    $failures += 'delivery was skipped'
} else {
    Write-Host 'PASS  no delivery was skipped'
}

Write-Host ''
if (-not $KeepRunning) {
    Write-Host '=== 4. stopping the server ==='
    Invoke-TestServer 'stop'
}

Write-Host ''
if ($failures.Count -eq 0) {
    Write-Host 'SMOKE TEST: PASS'
    exit 0
}
Write-Host "SMOKE TEST: FAIL ($($failures -join '; '))"
exit 1
