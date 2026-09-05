. (Join-Path $PSScriptRoot 'common.ps1')

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'REPAIR-REDIS-AOF.bat must be run as Administrator.'
}

$settings = Get-UavfireSettings
$autoStartTaskName = 'UAVFire-AutoStart'
$autoStartTask = Get-ScheduledTask -TaskName $autoStartTaskName -ErrorAction SilentlyContinue
$restoreAutoStart = $null -ne $autoStartTask -and $autoStartTask.State -ne 'Disabled'
$redisTemplate = Join-Path $script:ConfigDir 'redis.conf.template'
$redisData = Join-Path $script:BundleRoot 'data\redis'
$redisCli = Join-Path $script:BundleRoot 'runtime\redis\redis-cli.exe'
$redisServer = Join-Path $script:BundleRoot 'runtime\redis\redis-server.exe'

if ($autoStartTask) {
    Stop-ScheduledTask -TaskName $autoStartTaskName -ErrorAction SilentlyContinue
    Disable-ScheduledTask -TaskName $autoStartTaskName | Out-Null
    Write-Host '[INFO] UAVFire-AutoStart paused during Redis repair'
}

try {
    & (Join-Path $PSScriptRoot 'stop.ps1')

    # Repeated failed installs can leave an orphaned Redis whose PID file was
    # overwritten. Stop only processes launched from this bundle's executable;
    # leave every unrelated Redis installation untouched.
    $bundledRedisProcesses = @(
        Get-CimInstance Win32_Process -Filter "Name='redis-server.exe'" -ErrorAction SilentlyContinue |
            Where-Object {
                [string]$_.ExecutablePath -ieq $redisServer -or
                [string]$_.CommandLine -like "*$script:BundleRoot*redis-server.exe*"
            }
    )
    foreach ($process in $bundledRedisProcesses) {
        Write-Host "[INFO] stopping orphaned bundled Redis PID $($process.ProcessId): $($process.CommandLine)"
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    }
    Remove-Item (Join-Path $script:PidDir 'redis.pid') -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2

    $remainingOwners = @(Get-TcpListenerProcessIds ([int]$settings.redisPort))
    if ($remainingOwners.Count -gt 0) {
        $details = foreach ($ownerPid in $remainingOwners) {
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$ownerPid" -ErrorAction SilentlyContinue
            "PID=$ownerPid Name=$($owner.Name) Executable=$($owner.ExecutablePath) CommandLine=$($owner.CommandLine)"
        }
        throw "Redis TCP $($settings.redisPort) is still occupied by an unrelated process and was not terminated. $($details -join ' | ')"
    }

    $templateText = Get-Content $redisTemplate -Raw -Encoding UTF8
    $templateText = [regex]::Replace($templateText, '(?m)^\s*appendonly\s+\S+\s*$', 'appendonly no')
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($redisTemplate, $templateText, $utf8)

    # Keep the failed AOF untouched for support/recovery. Redis will ignore it
    # while appendonly is disabled and load dump.rdb instead when available.
    $aof = Join-Path $redisData 'appendonly.aof'
    if (Test-Path $aof) {
        Write-Host "[INFO] preserved failed AOF without deleting it: $aof"
    }

    & (Join-Path $PSScriptRoot 'configure.ps1') -PublicHost ([string]$settings.publicHost) -WebPort ([int]$settings.webPort)
    & (Join-Path $PSScriptRoot 'start.ps1')

    $appendOnlyConfig = @(& $redisCli '--raw' '-h' '127.0.0.1' '-p' ([string]$settings.redisPort) 'CONFIG' 'GET' 'appendonly' 2>&1)
    if ($LASTEXITCODE -ne 0 -or -not ($appendOnlyConfig -contains 'no')) {
        throw "Redis runtime config is not appendonly=no. Response: $($appendOnlyConfig -join ' ')"
    }

    $probeKey = 'uavfire:windows-repair:' + [Guid]::NewGuid().ToString('N')
    $setResult = & $redisCli '-h' '127.0.0.1' '-p' ([string]$settings.redisPort) 'SET' $probeKey 'ok' 'EX' '30'
    if ($LASTEXITCODE -ne 0 -or ([string]$setResult).Trim() -ne 'OK') {
        throw "Redis SET probe failed: $setResult"
    }
    $getResult = & $redisCli '-h' '127.0.0.1' '-p' ([string]$settings.redisPort) 'GET' $probeKey
    if ($LASTEXITCODE -ne 0 -or ([string]$getResult).Trim() -ne 'ok') {
        throw "Redis GET probe failed: $getResult"
    }
    & $redisCli '-h' '127.0.0.1' '-p' ([string]$settings.redisPort) 'DEL' $probeKey | Out-Null

    $directCaptcha = "http://127.0.0.1:$($settings.backendPort)/manage/api/v1/captcha"
    $proxiedCaptcha = "http://127.0.0.1:$($settings.webPort)/manage/api/v1/captcha"
    if (-not (Wait-HttpStatus $directCaptcha 200 120)) {
        throw "Redis read/write is healthy, but backend captcha is not healthy. Inspect backend logs."
    }
    if (-not (Wait-HttpStatus $proxiedCaptcha 200 30)) {
        throw "Backend is healthy, but Nginx captcha proxy is not healthy. Inspect nginx-error.log."
    }

    Write-Host '[OK] Redis PING/SET/GET/DEL passed.' -ForegroundColor Green
    Write-Host '[OK] Redis runtime appendonly=no confirmed.' -ForegroundColor Green
    Write-Host "[OK] backend captcha: $directCaptcha" -ForegroundColor Green
    Write-Host "[OK] Nginx captcha proxy: $proxiedCaptcha" -ForegroundColor Green
    Write-Host "[OK] Open http://$($settings.publicHost):$($settings.webPort) and press Ctrl+F5." -ForegroundColor Green
} finally {
    if ($restoreAutoStart) {
        Enable-ScheduledTask -TaskName $autoStartTaskName | Out-Null
        Write-Host '[INFO] UAVFire-AutoStart restored'
    }
}
