param(
    [Parameter(Mandatory=$true)][ValidateRange(1,65535)][int]$BackendPort
)

. (Join-Path $PSScriptRoot 'common.ps1')

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'SET-BACKEND-PORT-6789.bat must be run as Administrator.'
}

$settingsPath = Join-Path $script:ConfigDir 'settings.json'
$settings = Get-UavfireSettings
$oldBackendPort = [int]$settings.backendPort
$jar = Join-Path $script:BundleRoot 'app\backend\uavfire-1.10.0.jar'
$autoStartTaskName = 'UAVFire-AutoStart'
$autoStartTask = Get-ScheduledTask -TaskName $autoStartTaskName -ErrorAction SilentlyContinue
$restoreAutoStart = $null -ne $autoStartTask -and $autoStartTask.State -ne 'Disabled'

Write-Host "[INFO] switching UAVFire backend from TCP $oldBackendPort to TCP $BackendPort"
if ($autoStartTask) {
    Stop-ScheduledTask -TaskName $autoStartTaskName -ErrorAction SilentlyContinue
    Disable-ScheduledTask -TaskName $autoStartTaskName | Out-Null
    Write-Host '[INFO] UAVFire-AutoStart paused during repair'
}

try {
    & (Join-Path $PSScriptRoot 'stop.ps1')

    # Older bundles may launch the JAR with an absolute path, a relative path,
    # forward slashes, or another working directory. The unique JAR filename is
    # the reliable identity; do not terminate unrelated java.exe processes.
    $uavfireBackends = @(
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { [string]$_.CommandLine -like '*uavfire-1.10.0.jar*' }
    )
    foreach ($process in $uavfireBackends) {
        Write-Host "[INFO] stopping old UAVFire backend PID $($process.ProcessId): $($process.CommandLine)"
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    }
    Remove-Item (Join-Path $script:PidDir 'backend.pid') -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2

    $unrelatedOwners = @(Get-TcpListenerProcessIds $BackendPort)
    if ($unrelatedOwners.Count -gt 0) {
        $details = foreach ($ownerPid in $unrelatedOwners) {
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid" -ErrorAction SilentlyContinue
            "PID=$ownerPid Name=$($owner.Name) Executable=$($owner.ExecutablePath) CommandLine=$($owner.CommandLine)"
        }
        throw "TCP $BackendPort is occupied by an unrelated process and was not terminated. $($details -join ' | ')"
    }

    $settings.backendPort = $BackendPort
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($settingsPath, ($settings | ConvertTo-Json), $utf8)

    & (Join-Path $PSScriptRoot 'configure.ps1') -PublicHost ([string]$settings.publicHost) -WebPort ([int]$settings.webPort)
    & (Join-Path $PSScriptRoot 'start.ps1')

    $directCaptcha = "http://127.0.0.1:$BackendPort/manage/api/v1/captcha"
    $proxiedCaptcha = "http://127.0.0.1:$($settings.webPort)/manage/api/v1/captcha"
    if (-not (Wait-HttpStatus $directCaptcha 200 120)) {
        throw "Backend owns TCP $BackendPort but direct captcha HTTP is not healthy. Inspect backend logs."
    }
    if (-not (Wait-HttpStatus $proxiedCaptcha 200 30)) {
        throw "Backend HTTP is healthy on TCP $BackendPort, but Nginx proxy on TCP $($settings.webPort) is not healthy. Inspect nginx-error.log."
    }

    Write-Host "[OK] direct backend captcha: $directCaptcha" -ForegroundColor Green
    Write-Host "[OK] Nginx proxied captcha: $proxiedCaptcha" -ForegroundColor Green
    Write-Host "[OK] Open http://$($settings.publicHost):$($settings.webPort) and press Ctrl+F5." -ForegroundColor Green
} finally {
    if ($restoreAutoStart) {
        Enable-ScheduledTask -TaskName $autoStartTaskName | Out-Null
        Write-Host '[INFO] UAVFire-AutoStart restored'
    }
}
