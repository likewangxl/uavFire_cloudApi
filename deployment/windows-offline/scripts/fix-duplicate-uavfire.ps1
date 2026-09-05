. (Join-Path $PSScriptRoot 'common.ps1')

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'FIX-DUPLICATE-UAVFIRE.bat must be run as Administrator.'
}

$canonicalRoot = [System.IO.Path]::GetFullPath('C:\UAVFire')
if (-not $script:BundleRoot.TrimEnd('\').Equals($canonicalRoot.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) {
    throw "Run this script from C:\UAVFire. Current bundle root is $script:BundleRoot"
}

$autoStartTaskName = 'UAVFire-AutoStart'
$knownRuntimeNames = @(
    'java.exe',
    'mysqld.exe',
    'redis-server.exe',
    'mosquitto.exe',
    'minio.exe',
    'MediaServer.exe',
    'nginx.exe',
    'python.exe'
)

Write-Host '[1/5] Pausing every existing UAVFire auto-start task...'
Stop-ScheduledTask -TaskName $autoStartTaskName -ErrorAction SilentlyContinue
Disable-ScheduledTask -TaskName $autoStartTaskName -ErrorAction SilentlyContinue | Out-Null

Write-Host '[2/5] Stopping UAVFire processes launched from other drive letters...'
$foreignProcesses = @(
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $executable = [string]$_.ExecutablePath
            $name = [string]$_.Name
            $isKnownRuntime = $knownRuntimeNames -contains $name
            $isAnyUavfireRoot = $executable -match '^[A-Za-z]:\\UAVFire\\'
            $isCanonicalRoot = $executable.StartsWith($canonicalRoot + '\', [StringComparison]::OrdinalIgnoreCase)
            $isKnownRuntime -and $isAnyUavfireRoot -and -not $isCanonicalRoot
        }
)

if ($foreignProcesses.Count -eq 0) {
    Write-Host '[OK] No foreign-drive UAVFire process is currently running.'
} else {
    foreach ($process in $foreignProcesses) {
        Write-Host "[INFO] stopping duplicate PID=$($process.ProcessId) Path=$($process.ExecutablePath)"
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    }
    Start-Sleep -Seconds 3
}

$remainingForeign = @(
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $executable = [string]$_.ExecutablePath
            ($knownRuntimeNames -contains [string]$_.Name) -and
            $executable -match '^[A-Za-z]:\\UAVFire\\' -and
            -not $executable.StartsWith($canonicalRoot + '\', [StringComparison]::OrdinalIgnoreCase)
        }
)
if ($remainingForeign.Count -gt 0) {
    $details = $remainingForeign | ForEach-Object { "PID=$($_.ProcessId) Path=$($_.ExecutablePath)" }
    throw "Duplicate UAVFire processes are still running: $($details -join ' | ')"
}

Write-Host '[3/5] Repairing Redis persistence and restarting the C:\UAVFire stack...'
& (Join-Path $PSScriptRoot 'repair-redis-aof.ps1')

Write-Host '[4/5] Registering auto-start against C:\UAVFire only...'
$startScript = Join-Path $script:BundleRoot 'scripts\start.ps1'
$action = New-ScheduledTaskAction -Execute 'PowerShell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$startScript`""
$trigger = New-ScheduledTaskTrigger -AtStartup
Register-ScheduledTask -TaskName $autoStartTaskName -Action $action -Trigger $trigger `
    -User 'SYSTEM' -RunLevel Highest -Force | Out-Null

$registeredTask = Get-ScheduledTask -TaskName $autoStartTaskName
$taskCommand = [string]$registeredTask.Actions[0].Arguments
if ($taskCommand -notlike '*C:\UAVFire\scripts\start.ps1*') {
    throw "Auto-start registration does not point to C:\UAVFire: $taskCommand"
}

Write-Host '[5/5] Final verification...'
$settings = Get-UavfireSettings
$foreignRedisOwner = @(
    Get-NetTCPConnection -State Listen -LocalPort ([int]$settings.redisPort) -ErrorAction SilentlyContinue |
        ForEach-Object { Get-CimInstance Win32_Process -Filter "ProcessId=$($_.OwningProcess)" -ErrorAction SilentlyContinue } |
        Where-Object { -not ([string]$_.ExecutablePath).StartsWith($canonicalRoot + '\', [StringComparison]::OrdinalIgnoreCase) }
)
if ($foreignRedisOwner.Count -gt 0) {
    throw "Redis TCP $($settings.redisPort) is not owned by C:\UAVFire."
}

$accessUrl = "http://$($settings.publicHost):$($settings.webPort)"
Write-Host '[OK] Duplicate-drive processes removed without deleting their files.' -ForegroundColor Green
Write-Host '[OK] UAVFire-AutoStart points to C:\UAVFire\scripts\start.ps1.' -ForegroundColor Green
Write-Host "[OK] Open $accessUrl and press Ctrl+F5." -ForegroundColor Green
