. (Join-Path $PSScriptRoot 'common.ps1')

$settings = Get-UavfireSettings
$secrets = Get-UavfireSecrets
$mysqlData = Join-Path $script:BundleRoot 'data\mysql'
$mysqlSystemDatabase = Join-Path $mysqlData 'mysql'
$mysqlInitializationMarker = Join-Path $mysqlData '.uavfire-mysql-initialized'
$mysqlMarker = Join-Path $mysqlData '.uavfire-initialized'

if (Test-Path $mysqlMarker) {
    Write-Host '[OK] MySQL account initialization was already completed.' -ForegroundColor Green
    exit 0
}
if (-not (Test-Path $mysqlSystemDatabase)) {
    throw 'MySQL system database is missing. This recovery only applies after MySQL initialization has completed.'
}

$mysqld = Join-Path $script:BundleRoot 'runtime\mysql\bin\mysqld.exe'
$mysql = Join-Path $script:BundleRoot 'runtime\mysql\bin\mysql.exe'
$mysqlIni = Join-Path $script:ConfigDir 'mysql.ini'
Start-UavfireProcess -Name 'mysql' -FilePath $mysqld `
    -ArgumentList @("--defaults-file=`"$mysqlIni`"", '--console') `
    -WorkingDirectory (Split-Path $mysqld)
if (-not (Wait-TcpPort '127.0.0.1' $settings.mysqlPort 60)) {
    throw 'MySQL did not open its configured port during recovery.'
}

$schemaCount = & $mysql '--protocol=tcp' '-h127.0.0.1' "--port=$($settings.mysqlPort)" '-uroot' '-N' '-B' `
    '-e' "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='cloud_sample';"
if ($LASTEXITCODE -ne 0 -or ([string]$schemaCount).Trim() -ne '1') {
    throw 'The cloud_sample schema was not imported before the interrupted installation.'
}

$sql = "CREATE USER IF NOT EXISTS 'uavfire'@'%' IDENTIFIED BY '$($secrets.mysqlAppPassword)'; ALTER USER 'uavfire'@'%' IDENTIFIED BY '$($secrets.mysqlAppPassword)'; GRANT ALL PRIVILEGES ON cloud_sample.* TO 'uavfire'@'%'; ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secrets.mysqlRootPassword)'; FLUSH PRIVILEGES;"
& $mysql '--protocol=tcp' '-h127.0.0.1' "--port=$($settings.mysqlPort)" '-uroot' '-e' $sql
if ($LASTEXITCODE -ne 0) { throw 'Database account initialization failed during recovery.' }

Set-Content $mysqlInitializationMarker 'initialized' -Encoding ASCII
Set-Content $mysqlMarker 'initialized' -Encoding ASCII
Write-Host '[OK] MySQL application account and root password initialized.' -ForegroundColor Green
