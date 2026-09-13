<#
.SYNOPSIS
    One entry point for the local Paper test server.

.DESCRIPTION
    .\scripts\test-server.ps1 start    build + deploy the plugin, then start detached
    .\scripts\test-server.ps1 stop     stop it, freeing the port and the world lock
    .\scripts\test-server.ps1 status   is it up, and which plugin build is loaded
    .\scripts\test-server.ps1 logs     follow the server log

    Start is detached on purpose. A foreground server killed with Ctrl+C leaves its
    java child alive, still holding the port and the world's session.lock, and the
    next start then dies with a confusing LevelStorageSource error. Starting
    detached and stopping by port avoids that, which is why stop looks up the
    listener rather than trusting a recorded pid.

    If PowerShell refuses to run this because of the execution policy, invoke it as
        powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-server.ps1 start

.PARAMETER Command
    start | stop | status | logs
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'status', 'logs')]
    [string]$Command = 'status'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot  = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'test-server'
$JarName   = 'paper-1.21.4-232.jar'
$Port      = if ($env:MC_PORT) { [int]$env:MC_PORT } else { 25565 }
$LogFile   = Join-Path $ServerDir 'logs\latest.log'
$PluginDir = Join-Path $ServerDir 'plugins'

function Get-JavaExe {
    # Prefer a standalone JDK from ~/.jdks over whatever happens to be on PATH,
    # which on this machine is the Minecraft launcher's bundled runtime.
    $jdks = Join-Path $HOME '.jdks'
    if (Test-Path $jdks) {
        $candidate = Get-ChildItem -Path $jdks -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($candidate) { return $candidate }
    }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw 'No java.exe found, neither in ~/.jdks nor on PATH.'
}

# Returns the listening pids as a space-separated string, never as an array.
# A function that returns an array has it unrolled by the pipeline, so an empty
# result arrives as $null and any .Count on it throws under Set-StrictMode.
function Get-ListenerPids {
    $pids = @()
    try {
        $pids = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique)
    } catch {
        $pids = @()
    }
    if ($pids.Count -eq 0) {
        # Fallback for hosts where the NetTCPIP module is unavailable.
        $pids = @(netstat -ano |
            Select-String ":$Port\s" |
            Select-String 'LISTENING' |
            ForEach-Object { ($_.Line -split '\s+')[-1] } |
            Sort-Object -Unique)
    }
    return ($pids -join ' ')
}

function Test-ServerUp {
    return -not [string]::IsNullOrWhiteSpace((Get-ListenerPids))
}

function Get-SteveHudLogLines {
    if (-not (Test-Path $LogFile)) { return }
    # Paper writes UTF-8; on a non-English Windows the default ANSI codepage would
    # turn every non-ASCII character in the log into mojibake.
    Get-Content $LogFile -Encoding UTF8 |
        Select-String -Pattern '\[SteveHUD\]|Done \(' |
        Select-Object -Last 3 |
        ForEach-Object { $_.Line }
}

switch ($Command) {
    'start' {
        if (-not (Test-Path (Join-Path $ServerDir $JarName))) {
            Write-Host 'server jar missing; running setup first'
            & (Join-Path $PSScriptRoot 'setup-test-server.ps1')
        }

        if (Test-ServerUp) {
            Write-Host "already running on $Port; use 'stop' first"
            exit 0
        }

        Write-Host 'building the plugin'
        # Gradle's output goes to a file rather than to this script's stdout, on
        # purpose. Gradle starts a long-lived daemon, and that daemon inherits
        # whatever handles the build was given. If those handles are the caller's
        # pipe, the pipe stays open for as long as the daemon lives and the caller
        # never sees end-of-stream, so the command appears to hang forever even
        # though the script has finished.
        $gradleLog = Join-Path $ServerDir 'gradle-build.log'
        & (Join-Path $RepoRoot 'gradlew.bat') -PskipMods ':plugin:shadowJar' --console=plain *> $gradleLog
        if ($LASTEXITCODE -ne 0) {
            Write-Host '--- gradle output ---'
            Get-Content $gradleLog -Encoding UTF8 | Select-Object -Last 25 | Write-Host
            throw "gradle failed with exit code $LASTEXITCODE"
        }

        $pluginJar = Get-ChildItem (Join-Path $RepoRoot 'plugin\build\libs') -Filter 'SteveHUD-*-all.jar' |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $pluginJar) { throw 'no plugin jar produced' }

        New-Item -ItemType Directory -Force -Path $PluginDir | Out-Null
        Get-ChildItem $PluginDir -Filter 'SteveHUD-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
        Copy-Item $pluginJar.FullName $PluginDir -Force
        Write-Host "deployed $($pluginJar.Name)"

        # A lock left behind by an ungraceful stop blocks the world from loading.
        $lock = Join-Path $ServerDir 'world\session.lock'
        if (Test-Path $lock) { Remove-Item $lock -Force }

        $java = Get-JavaExe
        Write-Host "starting with $java"
        # Hidden window plus redirected output detaches it properly, so the server
        # outlives this script instead of dying with the parent shell.
        Start-Process -FilePath $java `
            -ArgumentList @('-Xms1G', '-Xmx2G', '-jar', $JarName, 'nogui') `
            -WorkingDirectory $ServerDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $ServerDir 'console.out.log') `
            -RedirectStandardError (Join-Path $ServerDir 'console.err.log') | Out-Null

        for ($i = 1; $i -le 120; $i++) {
            if ((Test-ServerUp) -and (Test-Path $LogFile) -and
                (Select-String -Path $LogFile -Pattern 'Done \(' -Quiet)) {
                Write-Host "ready after ${i}s"
                Get-SteveHudLogLines | ForEach-Object { Write-Host $_ }
                exit 0
            }
            Start-Sleep -Seconds 1
        }
        Write-Warning 'did not become ready in 120s; last log lines:'
        if (Test-Path $LogFile) { Get-Content $LogFile -Tail 20 -Encoding UTF8 | Write-Host }
        exit 1
    }

    'stop' {
        $pidList = Get-ListenerPids
        if ([string]::IsNullOrWhiteSpace($pidList)) {
            Write-Host "nothing is listening on $Port"
            exit 0
        }
        foreach ($serverPid in $pidList.Split(' ')) {
            if ([string]::IsNullOrWhiteSpace($serverPid)) { continue }
            Write-Host "stopping pid $serverPid"
            # /T also takes the child tree, so nothing survives to hold the port.
            & taskkill /PID $serverPid /T /F 2>&1 | Out-Null
        }
        for ($i = 1; $i -le 15; $i++) {
            if (-not (Test-ServerUp)) { Write-Host "port $Port free"; exit 0 }
            Start-Sleep -Seconds 1
        }
        Write-Warning "still listening on $Port"
        exit 1
    }

    'status' {
        if (Test-ServerUp) {
            Write-Host "server: UP on $Port (pid $(Get-ListenerPids))"
        } else {
            Write-Host 'server: DOWN'
        }
        Get-SteveHudLogLines | ForEach-Object { Write-Host $_ }
        $loaded = Get-ChildItem $PluginDir -Filter 'SteveHUD-*.jar' -ErrorAction SilentlyContinue
        Write-Host "plugin jar: $(if ($loaded) { $loaded.Name } else { '(none)' })"
    }

    'logs' {
        if (-not (Test-Path $LogFile)) { throw "no log yet at $LogFile" }
        Get-Content $LogFile -Tail 40 -Wait -Encoding UTF8
    }
}
