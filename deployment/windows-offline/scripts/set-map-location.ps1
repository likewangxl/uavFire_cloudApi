param(
    [string]$InstallRoot = 'C:\UAVFire',
    [string]$Longitude = '',
    [string]$Latitude = '',
    [ValidateSet('WGS84', 'GCJ02', '')][string]$CoordinateSystem = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$settingsPath = Join-Path $InstallRoot 'config\settings.json'
if (-not (Test-Path $settingsPath)) { throw "Missing installation: $settingsPath" }
Write-Host 'Set the physical SERVER deployment point. A LAN IP is not a geographic location.'
Write-Host 'Coordinate systems: WGS84 = GPS; GCJ02 = AMap/Gaode. Baidu coordinates are not accepted.'
if (-not $Longitude) { $Longitude = Read-Host 'Longitude (east-west, e.g. 108.x)' }
if (-not $Latitude) { $Latitude = Read-Host 'Latitude (north-south, e.g. 34.x)' }
if (-not $CoordinateSystem) { $CoordinateSystem = (Read-Host 'Coordinate system: WGS84 or GCJ02').Trim().ToUpperInvariant() }
$lng = 0.0
$lat = 0.0
$culture = [Globalization.CultureInfo]::InvariantCulture
$numberStyle = [Globalization.NumberStyles]::Float
if (-not [double]::TryParse($Longitude, $numberStyle, $culture, [ref]$lng) -or
    -not [double]::TryParse($Latitude, $numberStyle, $culture, [ref]$lat) -or
    [double]::IsNaN($lng) -or [double]::IsNaN($lat) -or
    [Math]::Abs($lng) -gt 180 -or [Math]::Abs($lat) -gt 85 -or
    ($lng -eq 0 -and $lat -eq 0) -or $CoordinateSystem -notin @('WGS84', 'GCJ02')) {
    throw 'Invalid coordinates or coordinate system; settings were not changed.'
}
$settings = Get-Content $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
Copy-Item $settingsPath "$settingsPath.map-backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$settings | Add-Member -Force -NotePropertyName mapLocation -NotePropertyValue ([ordered]@{
    longitude = $lng; latitude = $lat; coordinateSystem = $CoordinateSystem; zoom = 17
})
[IO.File]::WriteAllText($settingsPath, ($settings | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
& (Join-Path $PSScriptRoot 'write-site-location.ps1') -InstallRoot $InstallRoot
Write-Host '[OK] Location saved. Refresh TSA and wayline pages with Ctrl+F5.' -ForegroundColor Green
