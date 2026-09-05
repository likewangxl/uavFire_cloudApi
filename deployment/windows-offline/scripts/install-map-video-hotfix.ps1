param([string]$InstallRoot = 'C:\UAVFire', [switch]$MapOnly)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($InstallRoot)
$bundle = Split-Path $PSScriptRoot
$frontend = Join-Path $root 'app\frontend'
$payload = Join-Path $bundle 'frontend'
if (-not (Test-Path (Join-Path $frontend 'index.html')) -or
    -not (Test-Path (Join-Path $root 'config\settings.json')) -or
    -not (Test-Path (Join-Path $payload 'index.html'))) { throw 'Installation or frontend payload missing.' }
$backup = Join-Path $root "data\backups\map-video-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Path $backup -Force | Out-Null
Copy-Item $frontend (Join-Path $backup 'frontend') -Recurse
Write-Host "[OK] Frontend backed up to $backup"
foreach ($scriptName in @('set-map-location.ps1', 'write-site-location.ps1')) {
    $source = Join-Path $PSScriptRoot $scriptName
    $target = Join-Path $root "scripts\$scriptName"
    if ([IO.Path]::GetFullPath($source) -ine [IO.Path]::GetFullPath($target)) { Copy-Item $source $target -Force }
}
$batSource = Join-Path $bundle 'SET-MAP-LOCATION.bat'
$batTarget = Join-Path $root 'SET-MAP-LOCATION.bat'
if ([IO.Path]::GetFullPath($batSource) -ine [IO.Path]::GetFullPath($batTarget)) { Copy-Item $batSource $batTarget -Force }
# Upload assets first and publish the new entry last. Preserve server endpoint configuration.
Get-ChildItem $payload | Where-Object { $_.Name -notin @('index.html', 'runtime-config.js', 'site-location.js') } |
    ForEach-Object { Copy-Item $_.FullName $frontend -Recurse -Force }
& (Join-Path $root 'scripts\write-site-location.ps1') -InstallRoot $root
Copy-Item (Join-Path $payload 'index.html') (Join-Path $frontend 'index.html') -Force
Write-Host '[OK] TSA and wayline frontend installed.'
if (-not $MapOnly) {
    & (Join-Path $PSScriptRoot 'fix-webrtc-ip.ps1') -PublicHost '192.168.0.100' -InstallRoot $root
}
$settings = Get-Content (Join-Path $root 'config\settings.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $settings.PSObject.Properties['mapLocation'] -or $null -eq $settings.mapLocation) {
    Write-Host '[ACTION] Server coordinates are required. Enter the actual deployment location below.'
    & (Join-Path $root 'scripts\set-map-location.ps1') -InstallRoot $root
}
Write-Host '[OK] Hotfix completed. Refresh the browser with Ctrl+F5.' -ForegroundColor Green
