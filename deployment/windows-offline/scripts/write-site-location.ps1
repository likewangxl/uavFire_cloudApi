param([string]$InstallRoot = 'C:\UAVFire')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$settings = Get-Content (Join-Path $InstallRoot 'config\settings.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$site = $null
if ($settings.PSObject.Properties['mapLocation'] -and $null -ne $settings.mapLocation) {
    $site = $settings.mapLocation
    if ($site.coordinateSystem -notin @('WGS84', 'GCJ02') -or
        $null -eq $site.longitude -or $null -eq $site.latitude -or
        [double]::IsNaN([double]$site.longitude) -or [double]::IsNaN([double]$site.latitude) -or
        [Math]::Abs([double]$site.longitude) -gt 180 -or [Math]::Abs([double]$site.latitude) -gt 85 -or
        ([double]$site.longitude -eq 0 -and [double]$site.latitude -eq 0)) {
        throw 'Invalid mapLocation. Run SET-MAP-LOCATION.bat with valid coordinates.'
    }
}
$json = 'null'
if ($null -ne $site) { $json = $site | ConvertTo-Json -Compress }
$output = Join-Path $InstallRoot 'app\frontend\site-location.js'
[IO.File]::WriteAllText($output, "window.__UAVFIRE_SITE_LOCATION__ = $json;", (New-Object Text.UTF8Encoding($false)))
Write-Host '[OK] Shared map deployment location written.'
