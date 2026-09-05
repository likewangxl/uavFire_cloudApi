. (Join-Path $PSScriptRoot 'common.ps1')

$settings = Get-UavfireSettings
$runtime = Join-Path $script:BundleRoot 'runtime'
$config = Join-Path $script:BundleRoot 'config'

$nginx = Join-Path $runtime 'nginx\nginx.exe'
if (Test-Path $nginx) {
    $nginxPrefix = (Join-Path $runtime 'nginx').Replace('\','/') + '/'
    $nginxConfig = (Join-Path $config 'nginx.conf').Replace('\','/')
    try {
        # nginx -s reports a NativeCommandError when its recorded Windows PID
        # is stale. The managed PID cleanup below is authoritative, so this
        # best-effort graceful signal must never abort a repair or restart.
        $stopProcess = Start-Process -FilePath $nginx `
            -ArgumentList @('-p', $nginxPrefix, '-c', $nginxConfig, '-s', 'quit') `
            -WorkingDirectory (Split-Path $nginx) `
            -WindowStyle Hidden -Wait -PassThru
        if ($stopProcess.ExitCode -eq 0) { Start-Sleep -Seconds 1 }
    } catch {
        Write-Warning 'Nginx graceful stop was unavailable; cleaning up the recorded process instead.'
    }
}
Stop-UavfireProcess 'nginx'
Stop-UavfireProcess 'ai-service'
Stop-UavfireProcess 'backend'
Stop-UavfireProcess 'zlmediakit'
Stop-UavfireProcess 'minio'
Stop-UavfireProcess 'mosquitto'
Stop-UavfireProcess 'redis'

$mysqlAdmin = Join-Path $runtime 'mysql\bin\mysqladmin.exe'
if (Test-Path $mysqlAdmin) {
    try {
        $secrets = Get-UavfireSecrets
        & $mysqlAdmin '--protocol=tcp' '-h127.0.0.1' "--port=$($settings.mysqlPort)" '-uroot' "--password=$($secrets.mysqlRootPassword)" shutdown 2>$null
        Start-Sleep -Seconds 2
    } catch {
        Write-Warning "Graceful MySQL shutdown failed; the recorded process will be stopped. $($_.Exception.Message)"
    }
}
Stop-UavfireProcess 'mysql'
Write-Host '[OK] UAVFire stopped.'
