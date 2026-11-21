param(
    [string]$RunDir = 'windows-smoke-run',
    [string]$Log = 'windows-smoke-server.log',
    [int]$RconPort = 25575,
    [string]$RconPassword = 'smoke',
    [int]$TimeoutSeconds = 180
)

# Verify that the mod aborts cleanly on Windows: it logs an error, opens no socket,
# creates no config, and leaves the Minecraft server running.
$ErrorActionPreference = 'Stop'

function Fail([string]$Message) { throw $Message }

function Stop-Server {
    param([System.Diagnostics.Process]$Process, [int]$Port, [string]$Password)

    if ($Process.HasExited) { return }
    try {
        & python scripts/smoke-test.py --stop-only --rcon-port $Port --rcon-password $Password 2>$null | Out-Null
    } catch {
        # RCON may not be up yet; the process-tree kill below is the fallback.
    }
    if (-not $Process.WaitForExit(20000)) {
        & taskkill /PID $Process.Id /T /F 2>$null | Out-Null
    }
}

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root

if (-not $env:ComSpec) { $env:ComSpec = Join-Path $env:SystemRoot 'System32\cmd.exe' }

# Fresh run dir on purpose: no config file and no socket directory should appear.
if (Test-Path -LiteralPath $RunDir) { Remove-Item -LiteralPath $RunDir -Recurse -Force }
New-Item -ItemType Directory -Path $RunDir | Out-Null
Set-Content -LiteralPath (Join-Path $RunDir 'eula.txt') -Value 'eula=true' -Encoding ascii
Set-Content -LiteralPath (Join-Path $RunDir 'server.properties') -Value @(
    'online-mode=false'
    'server-port=25565'
    'motd=MC Chat API Windows init check'
    'enable-rcon=true'
    'broadcast-console-to-ops=false'
    "rcon.password=$RconPassword"
    "rcon.port=$RconPort"
) -Encoding ascii
Set-Content -LiteralPath $Log -Value '' -Encoding ascii

# cmd.exe merges stderr into the log; Start-Process gives us a handle to stop the server.
$cmdLine = 'gradlew.bat runServer --console=plain "-PmcChatApiRunDir={0}" > "{1}" 2>&1' -f $RunDir, $Log
$server = Start-Process -FilePath $env:ComSpec -ArgumentList @('/c', $cmdLine) -WorkingDirectory $Root -PassThru

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$guardSeen = $false
$serverReady = $false
$failure = $null

try {
    while ((Get-Date) -lt $deadline) {
        if ($server.HasExited) {
            Fail 'Server exited before the init guard was observed'
        }
        $text = ''
        if (Test-Path -LiteralPath $Log) {
            $text = Get-Content -LiteralPath $Log -Raw -ErrorAction SilentlyContinue
        }
        if ($text) {
            if ($text -match 'Listening on') {
                Fail 'Mod opened a Unix socket on Windows'
            }
            if ($text -match 'OS not supported') { $guardSeen = $true }
            if ($text -match 'Done \(') { $serverReady = $true }
            if ($guardSeen -and $serverReady) { break }
        }
        Start-Sleep -Seconds 1
    }

    if (-not $guardSeen) { Fail "Timed out waiting for 'OS not supported' in $Log" }
    if (-not $serverReady) { Fail 'Server did not finish starting after the mod aborted' }
} catch {
    $failure = $_.Exception.Message
} finally {
    Stop-Server -Process $server -Port $RconPort -Password $RconPassword
}

if (-not $failure) {
    if (Test-Path -LiteralPath (Join-Path $RunDir 'config/mcchatapi.conf')) {
        $failure = 'Mod created a config file on Windows'
    }
    if (Test-Path -LiteralPath (Join-Path $RunDir 'mcchatapi-sockets')) {
        $failure = 'Mod created a socket directory on Windows'
    }
    if (-not (Get-ChildItem -Path (Join-Path $Root 'build/libs') -Filter 'mcchatapi-*.jar' -ErrorAction SilentlyContinue)) {
        $failure = 'Build did not produce a mod jar'
    }
}

if ($failure) {
    [Console]::Error.WriteLine("Windows init check FAILED: $failure")
    if (Test-Path -LiteralPath $Log) {
        [Console]::Error.WriteLine('--- last 100 log lines ---')
        Get-Content -LiteralPath $Log -Tail 100 | ForEach-Object { [Console]::Error.WriteLine($_) }
    }
    exit 1
}

Write-Host 'Windows init check PASSED'
