$ErrorActionPreference = 'Stop'

$deploymentRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$scriptsDir = Join-Path $deploymentRoot 'scripts'
$parseFailures = @()

Get-ChildItem $scriptsDir -Filter '*.ps1' | ForEach-Object {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    foreach ($error in $errors) {
        $parseFailures += "$($_.Name): $($error.Message)"
    }
}
if ($parseFailures.Count -gt 0) { throw ($parseFailures -join [Environment]::NewLine) }

. (Join-Path $scriptsDir 'common.ps1')

$sampleIni = "[http]`r`nport=80`r`nsslport=443`r`n`r`n[rtc]`r`nport=8000`r`ntcpPort=8000`r`n"
$sampleIni = Set-IniValue $sampleIni 'http' 'port' '8099'
$sampleIni = Set-IniValue $sampleIni 'rtc' 'port' '19586'
$sampleIni = Set-IniValue $sampleIni 'rtc' 'tcpPort' '19586'
if ($sampleIni -notmatch '(?m)^port=8099\r?$') { throw 'HTTP INI port replacement failed.' }
if ($sampleIni -notmatch '(?m)^port=19586\r?$') { throw 'RTC UDP INI port replacement failed.' }
if ($sampleIni -notmatch '(?m)^tcpPort=19586\r?$') { throw 'RTC TCP INI port replacement failed.' }

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
try {
    $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    if (-not (Test-TcpPort '127.0.0.1' $port 1000)) { throw 'Open-port detection failed.' }
} finally {
    $listener.Stop()
}
if (Test-TcpPort '127.0.0.1' $port 200) { throw 'Closed-port detection failed.' }

$testRuntime = Join-Path ([System.IO.Path]::GetTempPath()) ("uavfire-process-test-" + [Guid]::NewGuid().ToString('N'))
$script:PidDir = Join-Path $testRuntime 'pids'
$script:LogDir = Join-Path $testRuntime 'logs'
Start-UavfireProcess -Name 'test-sleep' -FilePath '/bin/sleep' -ArgumentList @('10') -WorkingDirectory $testRuntime
$testPidFile = Join-Path $script:PidDir 'test-sleep.pid'
if (-not (Test-Path $testPidFile)) { throw 'Process PID file was not created.' }
$testPid = [int](Get-Content $testPidFile -Raw)
if (-not (Get-Process -Id $testPid -ErrorAction SilentlyContinue)) { throw 'Started test process is not alive.' }
Start-UavfireProcess -Name 'test-sleep' -FilePath '/bin/sleep' -ArgumentList @('10') -WorkingDirectory $testRuntime
if ([int](Get-Content $testPidFile -Raw) -ne $testPid) { throw 'Duplicate process guard failed.' }
Stop-UavfireProcess 'test-sleep'
if (Test-Path $testPidFile) { throw 'Process PID file was not removed.' }

$installScript = Get-Content (Join-Path $scriptsDir 'install.ps1') -Raw
if ($installScript -match 'Get-Content.+\|\s*&\s*\$mysql') {
    throw 'SQL import must not use a Windows PowerShell native pipeline because it can corrupt UTF-8 data.'
}
if ($installScript.Contains('-P$($settings.mysqlPort)')) {
    throw 'MySQL port argument uses broken adjacent PowerShell interpolation.'
}
if (-not $installScript.Contains('"--port=$($settings.mysqlPort)"')) {
    throw 'MySQL account initialization does not use an explicit long port argument.'
}
if (-not $installScript.Contains('.uavfire-mysql-initialized')) {
    throw 'MySQL installation cannot resume after account initialization is interrupted.'
}
foreach ($importer in @('import-schema.cmd', 'import-seed.cmd')) {
    $importPath = Join-Path (Join-Path $deploymentRoot 'database') $importer
    if (-not (Test-Path $importPath)) { throw "Missing SQL importer $importer." }
    $importContent = Get-Content $importPath -Raw
    if ($importContent -notmatch '<\s*"%~dp0(?:schema|seed)\.sql"') {
        throw "$importer does not use cmd.exe native file redirection."
    }
}

$configureScript = Get-Content (Join-Path $scriptsDir 'configure.ps1') -Raw
if ($configureScript -notmatch 'agent-bootstrap-secrets\.json') {
    throw 'configure.ps1 does not load package-specific Agent bootstrap credentials.'
}
if ($configureScript -notmatch 'mqttPassword\s*=\s*\$\(if \(\$agentBootstrap\)') {
    throw 'MQTT password is not initialized from the Agent bootstrap credentials.'
}
if ($configureScript -notmatch 'waylineAgentSharedSecret\s*=\s*\$\(if \(\$agentBootstrap\)') {
    throw 'Wayline shared secret is not initialized from the Agent bootstrap credentials.'
}
$templateTokens = Get-ChildItem (Join-Path $deploymentRoot 'config') -Filter '*.template' |
    ForEach-Object { [regex]::Matches((Get-Content $_.FullName -Raw), '\{\{([A-Z0-9_]+)\}\}') } |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique
foreach ($token in $templateTokens) {
    if ($configureScript -notmatch ("'" + [regex]::Escape($token) + "'\s*=")) {
        throw "Template token $token is not supplied by configure.ps1."
    }
}

$settings = Get-Content (Join-Path $deploymentRoot 'config/settings.json') -Raw | ConvertFrom-Json
$settingsPublicHost = [string]$settings.publicHost
if ($settingsPublicHost -ne '192.168.1.2') { throw 'Default Windows server address is not fixed to 192.168.1.2.' }
if ([int]$settings.aiPort -ne 9002) { throw 'Default AI port does not avoid the occupied target-machine port 9000.' }
$portProperties = @($settings.PSObject.Properties | Where-Object { $_.Name -like '*Port' })
$duplicatePorts = @($portProperties | Group-Object { [int]$_.Value } | Where-Object { $_.Count -gt 1 })
if ($duplicatePorts.Count -gt 0) { throw 'Default settings contain duplicate ports.' }

$backendTemplate = Get-Content (Join-Path $deploymentRoot 'config/backend-override.yml.template') -Raw
if ($backendTemplate -notmatch '(?m)^\s*address:\s*127\.0\.0\.1\s*$') { throw 'Backend is not restricted to loopback.' }
if ($backendTemplate -notmatch 'WAYLINE_AGENT_SHARED_SECRET') { throw 'Backend Agent shared secret is not configured.' }
if ($backendTemplate -notmatch 'MQTT_PASSWORD') { throw 'Backend MQTT password is not configured.' }

$nginxTemplate = Get-Content (Join-Path $deploymentRoot 'config/nginx.conf.template') -Raw
if ($nginxTemplate -match '(?m)^\s*include\s+mime\.types;') {
    throw 'Nginx resolves a bare mime.types include relative to config instead of its runtime directory.'
}
if ($nginxTemplate -notmatch '\{\{ROOT_FORWARD\}\}/runtime/nginx/conf/mime\.types') {
    throw 'Nginx MIME types include does not point to the bundled Windows runtime.'
}

$agentInstaller = Join-Path $deploymentRoot 'INSTALL-AGENT.bat'
if (-not (Test-Path $agentInstaller)) { throw 'Missing unified MSDK Agent installer.' }
$agentInstallerContent = Get-Content $agentInstaller -Raw
if ($agentInstallerContent -notmatch 'UAVFire-Agent-v0\.1\.24-trial\.apk') {
    throw 'Agent installer does not reference the trial APK.'
}
if ($agentInstallerContent -notmatch 'android-platform-tools\\adb\.exe') {
    throw 'Agent installer does not use the bundled Windows ADB.'
}
if (-not (Test-Path (Join-Path $deploymentRoot 'agent/README-AGENT.md'))) {
    throw 'Missing MSDK Agent deployment instructions.'
}

$mysqlRecoveryBatch = Join-Path $deploymentRoot 'RECOVER-MYSQL.bat'
$mysqlRecoveryScript = Join-Path $scriptsDir 'recover-mysql-account.ps1'
if (-not (Test-Path $mysqlRecoveryBatch) -or -not (Test-Path $mysqlRecoveryScript)) {
    throw 'Missing interrupted MySQL installation recovery utility.'
}
$mysqlRecoveryContent = Get-Content $mysqlRecoveryScript -Raw
if (-not $mysqlRecoveryContent.Contains('"--port=$($settings.mysqlPort)"')) {
    throw 'MySQL recovery utility does not use the safe explicit port argument.'
}

$serverRepairBatch = Join-Path $deploymentRoot 'FIX-WINDOWS-SERVER.bat'
$serverRepairScript = Join-Path $scriptsDir 'fix-windows-server.ps1'
if (-not (Test-Path $serverRepairBatch) -or -not (Test-Path $serverRepairScript)) {
    throw 'Missing Windows server IP and AI repair utility.'
}
$backendRepairBatch = Join-Path $deploymentRoot 'REPAIR-BACKEND-PORT.bat'
$backendRepairScript = Join-Path $scriptsDir 'repair-backend-port.ps1'
if (-not (Test-Path $backendRepairBatch) -or -not (Test-Path $backendRepairScript)) {
    throw 'Missing safe backend port repair utility.'
}
$backendRepairContent = Get-Content $backendRepairScript -Raw
if ($backendRepairContent -notmatch 'Get-CimInstance Win32_Process') {
    throw 'Backend port repair does not inspect the listener command line.'
}
if ($backendRepairContent -notmatch 'unrelated process.+not terminated') {
    throw 'Backend port repair may terminate an unrelated process.'
}
$backendPortBatch = Join-Path $deploymentRoot 'SET-BACKEND-PORT-6789.bat'
$backendPortScript = Join-Path $scriptsDir 'set-backend-port.ps1'
if (-not (Test-Path $backendPortBatch) -or -not (Test-Path $backendPortScript)) {
    throw 'Missing atomic backend port reconfiguration utility.'
}
$backendPortContent = Get-Content $backendPortScript -Raw
foreach ($requiredCheck in @('configure.ps1', 'Wait-HttpStatus $directCaptcha', 'Wait-HttpStatus $proxiedCaptcha')) {
    if (-not $backendPortContent.Contains($requiredCheck)) {
        throw "Backend port reconfiguration is missing $requiredCheck."
    }
}
if ($backendPortContent -notmatch "CommandLine.+-like.+uavfire-1\.10\.0\.jar") {
    throw 'Backend port reconfiguration does not constrain orphan cleanup to the UAVFire JAR.'
}
if ($backendPortContent -notmatch 'Disable-ScheduledTask.+UAVFire-AutoStart|Disable-ScheduledTask.+\$autoStartTaskName') {
    throw 'Backend port reconfiguration does not pause the auto-start task.'
}
$startScript = Get-Content (Join-Path $scriptsDir 'start.ps1') -Raw
if ($startScript -notmatch 'Wait-TcpPortOwnedByProcess') {
    throw 'Backend startup does not verify listener process ownership.'
}
$statusScript = Get-Content (Join-Path $scriptsDir 'status.ps1') -Raw
if ($statusScript -notmatch '/manage/api/v1/captcha') {
    throw 'Status script does not verify backend HTTP readiness.'
}
$redisTemplate = Get-Content (Join-Path $deploymentRoot 'config/redis.conf.template') -Raw
if ($redisTemplate -notmatch '(?m)^appendonly\s+no\s*$') {
    throw 'Windows Redis must not use the AOF mode that caused MISCONF startup failures.'
}
$startScript = Get-Content (Join-Path $scriptsDir 'start.ps1') -Raw
if ($startScript -notmatch 'Wait-RedisPong.+90') {
    throw 'Redis startup uses a one-shot PING instead of a bounded readiness wait.'
}
if ($startScript -notmatch 'Wait-TcpPortOwnedByProcess.+redisPort.+redisProcess') {
    throw 'Redis startup does not verify that the new process owns its TCP port.'
}
if ($startScript -notmatch "CONFIG.+GET.+appendonly") {
    throw 'Redis startup does not verify the effective AOF setting.'
}
$redisRepairBatch = Join-Path $deploymentRoot 'REPAIR-REDIS-AOF.bat'
$redisRepairScript = Join-Path $scriptsDir 'repair-redis-aof.ps1'
if (-not (Test-Path $redisRepairBatch) -or -not (Test-Path $redisRepairScript)) {
    throw 'Missing Redis AOF MISCONF repair utility.'
}
$redisRepairContent = Get-Content $redisRepairScript -Raw
foreach ($probeCommand in @("'SET'", "'GET'", "'DEL'", 'Wait-HttpStatus $directCaptcha', 'Wait-HttpStatus $proxiedCaptcha')) {
    if (-not $redisRepairContent.Contains($probeCommand)) {
        throw "Redis repair is missing verification $probeCommand."
    }
}
if ($redisRepairContent -notmatch "(?s)Name='redis-server\.exe'.+ExecutablePath") {
    throw 'Redis repair does not constrain orphan cleanup to the bundled executable.'
}
if ($redisRepairContent -match 'Remove-Item.+appendonly\.aof') {
    throw 'Redis repair must preserve the failed AOF for recovery.'
}
$duplicateFixBatch = Join-Path $deploymentRoot 'FIX-DUPLICATE-UAVFIRE.bat'
$duplicateFixScript = Join-Path $scriptsDir 'fix-duplicate-uavfire.ps1'
if (-not (Test-Path $duplicateFixBatch) -or -not (Test-Path $duplicateFixScript)) {
    throw 'Missing duplicate-drive UAVFire automatic repair.'
}
$duplicateFixContent = Get-Content $duplicateFixScript -Raw
foreach ($requiredText in @(
    "GetFullPath('C:\UAVFire')",
    "'^[A-Za-z]:\\UAVFire\\'",
    'repair-redis-aof.ps1',
    'Register-ScheduledTask',
    'C:\UAVFire\scripts\start.ps1'
)) {
    if (-not $duplicateFixContent.Contains($requiredText)) {
        throw "Duplicate-drive repair is missing required safety step: $requiredText"
    }
}
if ($duplicateFixContent -match '(?i)Remove-Item.+[A-Za-z]:\\UAVFire') {
    throw 'Duplicate-drive repair must not delete files from another installation.'
}
$stopScript = Get-Content (Join-Path $scriptsDir 'stop.ps1') -Raw
if ($stopScript.Contains('-P$($settings.mysqlPort)')) {
    throw 'MySQL shutdown uses broken adjacent PowerShell port interpolation.'
}
if (-not $stopScript.Contains('"--port=$($settings.mysqlPort)"')) {
    throw 'MySQL shutdown does not use an explicit long port argument.'
}
if ($stopScript -notmatch '(?s)try\s*\{.*?-s quit.*?\}\s*catch\s*\{') {
    throw 'A stale Nginx Windows PID can still abort stop and repair workflows.'
}

Write-Host 'windows_scripts=OK'
