<#
.SYNOPSIS
    Downloads the Paper server jar used for local verification of the plugin.

.DESCRIPTION
    The SHA-256 is pinned and checked, so a corrupted or substituted download fails
    loudly here instead of producing a confusing server error later.

    Bump $JarName and $JarSha256 together when moving to another Paper build.

    If PowerShell refuses to run this because of the execution policy:
        powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-test-server.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot  = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'test-server'

$JarName   = 'paper-1.21.4-232.jar'
$JarSha256 = '5EE4F542F628A14C644410B08C94EA42E772EF4D29FE92973636B6813D4EAFFC'
$JarUrl    = "https://fill-data.papermc.io/v1/objects/$($JarSha256.ToLower())/$JarName"

New-Item -ItemType Directory -Force -Path (Join-Path $ServerDir 'plugins') | Out-Null
$jarPath = Join-Path $ServerDir $JarName

if (Test-Path $jarPath) {
    Write-Host "already present: $JarName"
} else {
    Write-Host "downloading $JarName ..."
    $progress = $ProgressPreference
    # The progress bar makes Invoke-WebRequest an order of magnitude slower.
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -Uri $JarUrl -OutFile $jarPath -UseBasicParsing
    } finally {
        $ProgressPreference = $progress
    }
}

Write-Host -NoNewline 'verifying sha256 ... '
$actual = (Get-FileHash -Path $jarPath -Algorithm SHA256).Hash
if ($actual -ne $JarSha256) {
    Write-Host 'MISMATCH'
    Write-Host "  expected $JarSha256"
    Write-Host "  actual   $actual"
    Remove-Item $jarPath -Force
    throw 'download failed verification'
}
Write-Host "ok ($([math]::Round((Get-Item $jarPath).Length / 1MB, 1)) MiB)"

$eulaPath = Join-Path $ServerDir 'eula.txt'
if (-not (Test-Path $eulaPath)) {
    'eula=true' | Set-Content -Path $eulaPath -Encoding ascii
    Write-Host 'wrote eula.txt (agreeing to https://aka.ms/MinecraftEULA for this throwaway server)'
}

Write-Host ''
Write-Host 'ready. start it with: .\scripts\test-server.ps1 start'
