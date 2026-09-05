. (Join-Path $PSScriptRoot 'common.ps1')

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'REPAIR-BACKEND-PORT.bat must be run as Administrator.'
}

$settings = Get-UavfireSettings
$backendPort = [int]$settings.backendPort
$jar = Join-Path $script:BundleRoot 'app\backend\uavfire-1.10.0.jar'
$java = Join-Path $script:BundleRoot 'runtime\jre\bin\java.exe'
$backendConfig = (Join-Path $script:ConfigDir 'backend-override.yml').Replace('\','/')
$pidFile = Join-Path $script:PidDir 'backend.pid'

$listeners = @(Get-TcpListenerProcessIds $backendPort)
foreach ($listenerPid in $listeners) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$listenerPid" -ErrorAction SilentlyContinue
    $commandLine = if ($owner) { [string]$owner.CommandLine } else { '' }
    $executable = if ($owner) { [string]$owner.ExecutablePath } else { '' }
    Write-Host "Port $backendPort listener: PID=$listenerPid executable=$executable"

    $isUavfireBackend = $commandLine -like "*$jar*" -or (
        $commandLine -like '*uavfire-1.10.0.jar*' -and
        $commandLine -like "*$script:BundleRoot*"
    )
    if (-not $isUavfireBackend) {
        throw "Port $backendPort is occupied by an unrelated process (PID $listenerPid). It was not terminated. CommandLine=$commandLine"
    }

    Write-Host "[INFO] stopping orphaned UAVFire backend PID $listenerPid"
    Stop-Process -Id $listenerPid -Force
    try { (Get-Process -Id $listenerPid -ErrorAction Stop).WaitForExit(5000) | Out-Null } catch { }
}

Remove-Item $pidFile -Force -ErrorAction SilentlyContinue

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
foreach ($name in @('backend-stdout.log', 'backend-stderr.log')) {
    $path = Join-Path $script:LogDir $name
    if (Test-Path $path) { Move-Item $path "$path.$timestamp.bak" -Force }
}

$backendProcess = Start-UavfireProcess -Name 'backend' -FilePath $java `
    -ArgumentList @('-Xms512m', '-Xmx2048m', '-jar', "`"$jar`"", "--spring.config.additional-location=file:$backendConfig") `
    -WorkingDirectory (Join-Path $script:BundleRoot 'app\backend')

if (-not (Wait-TcpPortOwnedByProcess $backendPort $backendProcess.Id 120)) {
    throw "New backend PID $($backendProcess.Id) did not acquire port $backendPort. Inspect data\logs\backend-stdout.log and backend-stderr.log."
}
if (-not (Wait-HttpStatus "http://127.0.0.1:$backendPort/manage/api/v1/captcha" 200 120)) {
    throw "New backend owns port $backendPort but its captcha endpoint is not healthy. Inspect backend logs."
}

Write-Host "[OK] UAVFire backend PID $($backendProcess.Id) owns TCP $backendPort and captcha HTTP is healthy." -ForegroundColor Green
Write-Host "Open http://$($settings.publicHost):$($settings.webPort) and refresh the login page."
