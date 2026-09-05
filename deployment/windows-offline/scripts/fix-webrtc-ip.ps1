param(
    [Parameter(Mandatory = $true)]
    [string]$PublicHost,

    [string]$InstallRoot = 'C:\UAVFire'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-IPv4([string]$Address) {
    $parsed = $null
    return [System.Net.IPAddress]::TryParse($Address, [ref]$parsed) -and
        $parsed.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork
}

function Set-IniValue([string]$Text, [string]$Section, [string]$Key, [string]$Value) {
    $pattern = '(?ms)(^\[' + [regex]::Escape($Section) + '\]\s*.*?^' +
        [regex]::Escape($Key) + '=)[^\r\n]*'
    $updated = [regex]::Replace($Text, $pattern, ('${1}' + $Value), 1)
    if ($updated -eq $Text -and $Text -notmatch
        ('(?m)^' + [regex]::Escape($Key) + '=' + [regex]::Escape($Value) + '\r?$')) {
        throw "Missing [$Section] $Key in ZLMediaKit configuration."
    }
    return $updated
}

function Get-IniValue([string]$Text, [string]$Section, [string]$Key) {
    $pattern = '(?ms)^\[' + [regex]::Escape($Section) + '\]\s*.*?^' +
        [regex]::Escape($Key) + '=([^\r\n]*)'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value.Trim()
}

function Test-UavfireMediaServerProcess($ProcessInfo, [string]$ResolvedRoot) {
    $expectedSuffix = '\UAVFire\runtime\zlmediakit\MediaServer.exe'
    $executable = [string]$ProcessInfo.ExecutablePath
    $commandLine = [string]$ProcessInfo.CommandLine
    if ($executable) {
        $normalizedExecutable = $executable.Replace('/', '\')
        if ($normalizedExecutable.EndsWith($expectedSuffix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
        $rootPrefix = $ResolvedRoot.TrimEnd('\') + '\'
        if ($normalizedExecutable.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $commandLine -and
        $commandLine.Replace('/', '\').IndexOf($expectedSuffix, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Wait-ProcessPort([int]$Port, [int]$ProcessId, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $owners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique)
        if ($owners -contains $ProcessId) { return $true }
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return $false }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

if (-not (Test-IPv4 $PublicHost)) { throw "Invalid IPv4 address: $PublicHost" }

$resolvedRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$configPath = Join-Path $resolvedRoot 'config\zlmediakit.ini'
$settingsPath = Join-Path $resolvedRoot 'config\settings.json'
$mediaServerPath = Join-Path $resolvedRoot 'runtime\zlmediakit\MediaServer.exe'
$mediaServerDirectory = Split-Path $mediaServerPath
$pidPath = Join-Path $resolvedRoot 'data\pids\zlmediakit.pid'
$stdoutPath = Join-Path $resolvedRoot 'data\logs\zlmediakit-stdout.log'
$stderrPath = Join-Path $resolvedRoot 'data\logs\zlmediakit-stderr.log'

foreach ($required in @($configPath, $settingsPath, $mediaServerPath)) {
    if (-not (Test-Path $required)) { throw "Missing required installed file: $required" }
}

$settings = Get-Content $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$settings.publicHost = $PublicHost
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($settingsPath, ($settings | ConvertTo-Json), $utf8)

$configText = Get-Content $configPath -Raw -Encoding UTF8
$backupPath = "$configPath.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
Copy-Item $configPath $backupPath -Force
$configText = Set-IniValue $configText 'rtc' 'externIP' $PublicHost
[System.IO.File]::WriteAllText($configPath, $configText, $utf8)

$writtenExternIp = Get-IniValue $configText 'rtc' 'externIP'
if ($writtenExternIp -ne $PublicHost) {
    throw "Failed to write rtc.externIP=$PublicHost to $configPath."
}
Write-Host "[OK] wrote rtc.externIP=$PublicHost"
Write-Host "[OK] backup: $backupPath"

# Stop only MediaServer instances that belong to a UAVFire bundle. This also
# removes a stale F:\UAVFire instance left by an earlier USB hotfix.
$mediaProcesses = @(Get-CimInstance Win32_Process -Filter "Name='MediaServer.exe'" -ErrorAction SilentlyContinue)
foreach ($mediaProcess in $mediaProcesses) {
    if (Test-UavfireMediaServerProcess $mediaProcess $resolvedRoot) {
        Stop-Process -Id ([int]$mediaProcess.ProcessId) -Force -ErrorAction SilentlyContinue
        Write-Host "[OK] stopped UAVFire MediaServer PID $($mediaProcess.ProcessId)"
    }
}
Start-Sleep -Seconds 2
Remove-Item $pidPath -Force -ErrorAction SilentlyContinue

$rtcPort = [int]$settings.zlmRtcPort
$httpPort = [int]$settings.zlmHttpPort
$rtmpPort = [int]$settings.zlmRtmpPort
foreach ($port in @($httpPort, $rtmpPort, $rtcPort) | Sort-Object -Unique) {
    $owners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -gt 0) {
        throw "TCP $port is still occupied by PID(s): $($owners -join ', '). Stop the unrelated process and run this script again."
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path $pidPath), (Split-Path $stdoutPath) | Out-Null
$process = Start-Process -FilePath $mediaServerPath `
    -ArgumentList @('-c', "`"$configPath`"") `
    -WorkingDirectory $mediaServerDirectory `
    -PassThru `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath
Set-Content -Path $pidPath -Value $process.Id -Encoding ASCII
Write-Host "[OK] started MediaServer PID $($process.Id)"

if (-not (Wait-ProcessPort $httpPort $process.Id 30)) {
    throw "MediaServer did not open HTTP TCP $httpPort. Inspect $stderrPath."
}
if (-not (Wait-ProcessPort $rtcPort $process.Id 30)) {
    throw "MediaServer did not open WebRTC TCP $rtcPort. Inspect $stderrPath."
}

foreach ($protocol in @('TCP', 'UDP')) {
    $ruleName = "UAVFire $protocol $rtcPort"
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
        -Protocol $protocol -LocalPort $rtcPort -Profile Any | Out-Null
    Write-Host "[OK] firewall $protocol $rtcPort"
}

$apiSecret = Get-IniValue $configText 'api' 'secret'
if (-not $apiSecret) { throw 'Missing [api] secret in ZLMediaKit configuration.' }
$encodedSecret = [System.Uri]::EscapeDataString($apiSecret)
$serverConfigUri = "http://127.0.0.1:${httpPort}/index/api/getServerConfig?secret=$encodedSecret"
$runtimeConfig = Invoke-RestMethod -UseBasicParsing -Uri $serverConfigUri -TimeoutSec 10
if ([int]$runtimeConfig.code -ne 0) {
    throw "ZLMediaKit runtime configuration query failed: $($runtimeConfig.msg)"
}
$runtimeValues = @($runtimeConfig.data)[0]
$runtimeExternIp = [string]$runtimeValues.'rtc.externIP'
if ($runtimeExternIp -ne $PublicHost) {
    throw "Running ZLMediaKit still reports rtc.externIP=$runtimeExternIp instead of $PublicHost."
}

Write-Host "[OK] running ZLMediaKit rtc.externIP=$runtimeExternIp" -ForegroundColor Green
Write-Host "[OK] WebRTC repair complete. Open http://${PublicHost}:$($settings.webPort)/leadership-cockpit and press Ctrl+F5." -ForegroundColor Green
