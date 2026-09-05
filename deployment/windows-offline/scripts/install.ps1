param(
    [string]$PublicHost = '',
    [int]$WebPort = 0,
    [switch]$SkipAI,
    [switch]$NoAutoStart
)

. (Join-Path $PSScriptRoot 'common.ps1')

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'INSTALL.bat must be run as Administrator.'
}
if (-not [Environment]::Is64BitOperatingSystem) { throw 'This package requires 64-bit Windows.' }
if ($script:BundleRoot -match '\s' -or $script:BundleRoot -notmatch '^[\x00-\x7F]+$') {
    throw 'Move the extracted package to an ASCII path without spaces, for example C:\UAVFire.'
}

$requiredDirs = @(
    'app\backend',
    'app\frontend',
    'agent',
    'runtime\jre',
    'runtime\mysql',
    'runtime\nginx',
    'runtime\redis',
    'runtime\mosquitto',
    'runtime\zlmediakit',
    'runtime\android-platform-tools'
)
foreach ($relative in $requiredDirs) {
    if (-not (Test-Path (Join-Path $script:BundleRoot $relative))) { throw "Incomplete package: missing $relative" }
}

New-Item -ItemType Directory -Force -Path `
    (Join-Path $script:BundleRoot 'data\mysql'), `
    (Join-Path $script:BundleRoot 'data\redis'), `
    (Join-Path $script:BundleRoot 'data\mosquitto'), `
    (Join-Path $script:BundleRoot 'data\minio'), `
    (Join-Path $script:BundleRoot 'data\agent-fire-evidence'), `
    (Join-Path $script:BundleRoot 'data\fire-snapshots'), `
    (Join-Path $script:BundleRoot 'data\logs'), `
    (Join-Path $script:BundleRoot 'data\pids') | Out-Null

$vcRedist = Join-Path $script:BundleRoot 'runtime\prerequisites\VC_redist.x64.exe'
if (Test-Path $vcRedist) {
    $vc = Start-Process $vcRedist -ArgumentList @('/install','/quiet','/norestart') -Wait -PassThru
    if ($vc.ExitCode -notin @(0,1638,3010)) { throw "VC++ runtime installer failed: $($vc.ExitCode)" }
}

& (Join-Path $PSScriptRoot 'configure.ps1') -PublicHost $PublicHost -WebPort $WebPort
$settings = Get-UavfireSettings
$secrets = Get-UavfireSecrets
$mysqlData = Join-Path $script:BundleRoot 'data\mysql'
$mysqlMarker = Join-Path $mysqlData '.uavfire-initialized'
$mysqlInitializationMarker = Join-Path $mysqlData '.uavfire-mysql-initialized'
$mysqlSystemDatabase = Join-Path $mysqlData 'mysql'
$mysqlPidFile = Join-Path $script:PidDir 'mysql.pid'
$managedMysqlRunning = $false
if (Test-Path $mysqlPidFile) {
    try {
        $managedMysqlPid = [int](Get-Content $mysqlPidFile -Raw)
        $managedMysqlProcess = Get-Process -Id $managedMysqlPid -ErrorAction SilentlyContinue
        $managedMysqlRunning = $null -ne $managedMysqlProcess -and $managedMysqlProcess.ProcessName -ieq 'mysqld'
    } catch {
        $managedMysqlRunning = $false
    }
}

if (-not (Test-Path $mysqlMarker)) {
    $requiredPorts = [ordered]@{
        Web = $settings.webPort
        Backend = $settings.backendPort
        MySQL = $settings.mysqlPort
        Redis = $settings.redisPort
        MQTT = $settings.mqttPort
        'MQTT WebSocket' = $settings.mqttWebSocketPort
        'ZLMediaKit RTMP' = $settings.zlmRtmpPort
        'ZLMediaKit HTTP' = $settings.zlmHttpPort
        'ZLMediaKit RTSP' = $settings.zlmRtspPort
        'ZLMediaKit WebRTC' = $settings.zlmRtcPort
        AI = $settings.aiPort
        'MinIO API' = $settings.minioApiPort
        'MinIO Console' = $settings.minioConsolePort
    }
    foreach ($entry in $requiredPorts.GetEnumerator()) {
        if ($entry.Key -eq 'MySQL' -and $managedMysqlRunning) { continue }
        if (Test-TcpPort '127.0.0.1' ([int]$entry.Value)) {
            throw "Port $($entry.Value) is already occupied ($($entry.Key)). Edit config\settings.json before installing."
        }
    }
}

if (-not $SkipAI) {
    $python = Join-Path $script:BundleRoot 'runtime\python\python.exe'
    if (-not (Test-Path $python)) {
        $pythonInstaller = Find-BundledFile 'python-*-amd64.exe'
        $target = Join-Path $script:BundleRoot 'runtime\python'
        $py = Start-Process $pythonInstaller -ArgumentList @('/quiet','InstallAllUsers=0',"TargetDir=$target",'Include_pip=1','Include_launcher=0','Include_test=0','PrependPath=0') -Wait -PassThru
        if ($py.ExitCode -ne 0) { throw "Python installation failed: $($py.ExitCode)" }
    }
    & $python -m pip install --disable-pip-version-check --no-index `
        --find-links (Join-Path $script:BundleRoot 'runtime\python-wheelhouse') `
        -r (Join-Path $script:BundleRoot 'app\ai-service\requirements-windows.lock.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Offline AI dependencies installation failed.' }
}

$mysqld = Join-Path $script:BundleRoot 'runtime\mysql\bin\mysqld.exe'
$mysqlIni = Join-Path $script:ConfigDir 'mysql.ini'
if (-not (Test-Path $mysqlMarker)) {
    if (-not (Test-Path $mysqlInitializationMarker) -and (Test-Path $mysqlSystemDatabase)) {
        Write-Warning 'Detected a completed MySQL initialization from an interrupted installation; resuming it.'
        Set-Content $mysqlInitializationMarker 'initialized' -Encoding ASCII
    }
    if (-not (Test-Path $mysqlInitializationMarker)) {
        $existingMysqlFiles = @(Get-ChildItem $mysqlData -Force -ErrorAction SilentlyContinue)
        if ($existingMysqlFiles.Count -gt 0) {
            $backupName = 'mysql-incomplete-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
            $backupPath = Join-Path (Split-Path $mysqlData) $backupName
            Move-Item $mysqlData $backupPath
            New-Item -ItemType Directory -Force -Path $mysqlData | Out-Null
            Write-Warning "Moved incomplete MySQL files to $backupPath"
        }
        & $mysqld "--defaults-file=$mysqlIni" --initialize-insecure --console
        if ($LASTEXITCODE -ne 0) { throw 'MySQL data directory initialization failed.' }
        Set-Content $mysqlInitializationMarker 'initialized' -Encoding ASCII
    }
    Start-UavfireProcess -Name 'mysql' -FilePath $mysqld -ArgumentList @("--defaults-file=`"$mysqlIni`"", '--console') -WorkingDirectory (Split-Path $mysqld)
    if (-not (Wait-TcpPort '127.0.0.1' $settings.mysqlPort 90)) { throw 'MySQL did not start during initialization.' }
    $mysql = Join-Path $script:BundleRoot 'runtime\mysql\bin\mysql.exe'
    $schemaImporter = Join-Path $script:BundleRoot 'database\import-schema.cmd'
    $seedImporter = Join-Path $script:BundleRoot 'database\import-seed.cmd'
    & $schemaImporter ([string]$settings.mysqlPort)
    if ($LASTEXITCODE -ne 0) { throw 'Database schema import failed.' }
    & $seedImporter ([string]$settings.mysqlPort)
    if ($LASTEXITCODE -ne 0) { throw 'Database seed import failed.' }
    $sql = "CREATE USER IF NOT EXISTS 'uavfire'@'%' IDENTIFIED BY '$($secrets.mysqlAppPassword)'; ALTER USER 'uavfire'@'%' IDENTIFIED BY '$($secrets.mysqlAppPassword)'; GRANT ALL PRIVILEGES ON cloud_sample.* TO 'uavfire'@'%'; ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secrets.mysqlRootPassword)'; FLUSH PRIVILEGES;"
    & $mysql '--protocol=tcp' '-h127.0.0.1' "--port=$($settings.mysqlPort)" '-uroot' '-e' $sql
    if ($LASTEXITCODE -ne 0) { throw 'Database account initialization failed.' }
    Set-Content $mysqlMarker 'initialized' -Encoding ASCII
}

& (Join-Path $PSScriptRoot 'start.ps1')
if (-not (Wait-TcpPort '127.0.0.1' $settings.minioApiPort 60)) { throw 'MinIO did not start.' }
if (-not (Wait-HttpStatus "http://127.0.0.1:$($settings.backendPort)/manage/api/v1/captcha" 200 120)) {
    throw 'Java backend HTTP endpoint did not become ready. Inspect backend logs.'
}
if (-not (Wait-TcpPort '127.0.0.1' $settings.webPort 60)) { throw 'Nginx unified web entry did not start.' }
if (-not $SkipAI -and -not (Wait-TcpPort '127.0.0.1' $settings.aiPort 300)) {
    throw 'AI compatibility service did not start. See data\logs and retry with scripts\install.ps1 -SkipAI if only Agent-local detection is required.'
}
$mc = Join-Path $script:BundleRoot 'runtime\minio\mc.exe'
& $mc alias set local "http://127.0.0.1:$($settings.minioApiPort)" $secrets.minioUser $secrets.minioPassword | Out-Null
& $mc mb --ignore-existing local/cloud-bucket | Out-Null
& $mc mb --ignore-existing local/fc100-routes | Out-Null

$firewallPorts = @($settings.webPort, $settings.zlmRtmpPort, $settings.zlmRtcPort)
foreach ($port in $firewallPorts) {
    $ruleName = "UAVFire TCP $port"
    if (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port | Out-Null
    }
}
$udpRule = "UAVFire UDP $($settings.zlmRtcPort)"
if (-not (Get-NetFirewallRule -DisplayName $udpRule -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $udpRule -Direction Inbound -Action Allow -Protocol UDP -LocalPort $settings.zlmRtcPort | Out-Null
}

if (-not $NoAutoStart) {
    $startScript = Join-Path $PSScriptRoot 'start.ps1'
    $action = New-ScheduledTaskAction -Execute 'PowerShell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$startScript`""
    $trigger = New-ScheduledTaskTrigger -AtStartup
    Register-ScheduledTask -TaskName 'UAVFire-AutoStart' -Action $action -Trigger $trigger -User 'SYSTEM' -RunLevel Highest -Force | Out-Null
}

Start-Sleep -Seconds 2
& (Join-Path $PSScriptRoot 'status.ps1') -SkipAI:$SkipAI
Write-Host "Installation complete: http://$($settings.publicHost):$($settings.webPort)" -ForegroundColor Green
