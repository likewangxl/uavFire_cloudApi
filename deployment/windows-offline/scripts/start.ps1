. (Join-Path $PSScriptRoot 'common.ps1')

$settings = Get-UavfireSettings
$secrets = Get-UavfireSecrets
$runtime = Join-Path $script:BundleRoot 'runtime'
$config = Join-Path $script:BundleRoot 'config'

$mysql = Join-Path $runtime 'mysql\bin\mysqld.exe'
$mysqlIni = Join-Path $config 'mysql.ini'
Start-UavfireProcess -Name 'mysql' -FilePath $mysql `
    -ArgumentList @("--defaults-file=`"$mysqlIni`"", '--console') `
    -WorkingDirectory (Split-Path $mysql)
if (-not (Wait-TcpPort '127.0.0.1' $settings.mysqlPort 60)) { throw 'MySQL did not open its port.' }

$redis = Join-Path $runtime 'redis\redis-server.exe'
$redisProcess = Start-UavfireProcess -Name 'redis' -FilePath $redis `
    -ArgumentList @("`"$(Join-Path $config 'redis.conf')`"") `
    -WorkingDirectory (Split-Path $redis)
if (-not (Wait-TcpPortOwnedByProcess $settings.redisPort $redisProcess.Id 30)) {
    $owners = @(Get-TcpListenerProcessIds $settings.redisPort)
    if ($owners.Count -gt 0) {
        throw "Redis port $($settings.redisPort) is owned by PID(s) $($owners -join ', '), not the newly started Redis PID $($redisProcess.Id)."
    }
    throw "Redis PID $($redisProcess.Id) exited or did not open TCP $($settings.redisPort). Inspect data\logs\redis.log."
}
$redisCli = Join-Path $runtime 'redis\redis-cli.exe'
if (-not (Wait-RedisPong $redisCli $settings.redisPort 90)) {
    throw "Redis did not become ready on TCP $($settings.redisPort) within 90 seconds. Inspect data\logs\redis.log."
}
$appendOnlyConfig = @(& $redisCli '--raw' '-h' '127.0.0.1' '-p' ([string]$settings.redisPort) 'CONFIG' 'GET' 'appendonly' 2>&1)
if ($LASTEXITCODE -ne 0 -or -not ($appendOnlyConfig -contains 'no')) {
    throw "Redis is not using the repaired appendonly=no configuration. Response: $($appendOnlyConfig -join ' ')"
}

$mosquitto = Join-Path $runtime 'mosquitto\mosquitto.exe'
Start-UavfireProcess -Name 'mosquitto' -FilePath $mosquitto `
    -ArgumentList @('-c', "`"$(Join-Path $config 'mosquitto.conf')`"") `
    -WorkingDirectory (Split-Path $mosquitto)

$env:MINIO_ROOT_USER = [string]$secrets.minioUser
$env:MINIO_ROOT_PASSWORD = [string]$secrets.minioPassword
$minio = Join-Path $runtime 'minio\minio.exe'
$minioAddress = "127.0.0.1:$($settings.minioApiPort)"
$minioConsole = "127.0.0.1:$($settings.minioConsolePort)"
Start-UavfireProcess -Name 'minio' -FilePath $minio `
    -ArgumentList @('server', "`"$(Join-Path $script:BundleRoot 'data\minio')`"", '--address', $minioAddress, '--console-address', $minioConsole) `
    -WorkingDirectory (Split-Path $minio)

$mediaServer = Join-Path $runtime 'zlmediakit\MediaServer.exe'
Start-UavfireProcess -Name 'zlmediakit' -FilePath $mediaServer `
    -ArgumentList @('-c', "`"$(Join-Path $config 'zlmediakit.ini')`"") `
    -WorkingDirectory (Split-Path $mediaServer)

$java = Join-Path $runtime 'jre\bin\java.exe'
$jar = Join-Path $script:BundleRoot 'app\backend\uavfire-1.10.0.jar'
$backendConfig = (Join-Path $config 'backend-override.yml').Replace('\','/')
$backendProcess = Start-UavfireProcess -Name 'backend' -FilePath $java `
    -ArgumentList @('-Xms512m', '-Xmx2048m', '-jar', "`"$jar`"", "--spring.config.additional-location=file:$backendConfig") `
    -WorkingDirectory (Join-Path $script:BundleRoot 'app\backend')
if (-not (Wait-TcpPortOwnedByProcess $settings.backendPort $backendProcess.Id 120)) {
    $owners = @(Get-TcpListenerProcessIds $settings.backendPort)
    if ($owners.Count -gt 0) {
        throw "Backend port $($settings.backendPort) is owned by PID(s) $($owners -join ', '), not the UAVFire backend PID $($backendProcess.Id)."
    }
    throw "UAVFire backend PID $($backendProcess.Id) exited or did not open port $($settings.backendPort). Inspect data\logs\backend-stdout.log and backend-stderr.log."
}

$python = Join-Path $runtime 'python\python.exe'
if (Test-Path $python) {
    Start-UavfireProcess -Name 'ai-service' -FilePath $python `
        -ArgumentList @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', [string]$settings.aiPort) `
        -WorkingDirectory (Join-Path $script:BundleRoot 'app\ai-service')
} else {
    Write-Warning 'Python runtime is not installed; AI compatibility service was skipped.'
}

$nginx = Join-Path $runtime 'nginx\nginx.exe'
$nginxPrefix = (Join-Path $runtime 'nginx').Replace('\','/') + '/'
$nginxConfig = (Join-Path $config 'nginx.conf').Replace('\','/')
Start-UavfireProcess -Name 'nginx' -FilePath $nginx `
    -ArgumentList @('-p', $nginxPrefix, '-c', $nginxConfig) `
    -WorkingDirectory (Split-Path $nginx)

Write-Host "UAVFire startup commands issued. Open http://$($settings.publicHost):$($settings.webPort)"
