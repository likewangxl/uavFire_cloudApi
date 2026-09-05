$ErrorActionPreference = 'Stop'
$scripts = Join-Path (Split-Path $PSScriptRoot) 'scripts'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('uavfire-map-test-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Force -Path (Join-Path $testRoot 'config'), (Join-Path $testRoot 'app/frontend') | Out-Null
$settingsPath = Join-Path $testRoot 'config/settings.json'
Set-Content $settingsPath '{"publicHost":"192.168.0.100","backendPort":6789}'
& (Join-Path $scripts 'write-site-location.ps1') -InstallRoot $testRoot
$output = Join-Path $testRoot 'app/frontend/site-location.js'
if ((Get-Content $output -Raw) -notmatch '= null;') { throw 'Unset location must stay null.' }
& (Join-Path $scripts 'set-map-location.ps1') -InstallRoot $testRoot -Longitude '110.123' -Latitude '35.456' -CoordinateSystem WGS84
$settings = Get-Content $settingsPath -Raw | ConvertFrom-Json
if ($settings.backendPort -ne 6789 -or $settings.mapLocation.longitude -ne 110.123) { throw 'Location write changed other settings or lost coordinates.' }
$before = Get-Content $settingsPath -Raw
$rejected = $false
try { & (Join-Path $scripts 'set-map-location.ps1') -InstallRoot $testRoot -Longitude 'NaN' -Latitude '35' -CoordinateSystem WGS84 } catch { $rejected = $true }
if (-not $rejected -or (Get-Content $settingsPath -Raw) -ne $before) { throw 'Invalid input must be rejected without mutation.' }
& (Join-Path $scripts 'write-site-location.ps1') -InstallRoot $testRoot
if ((Get-Content $output -Raw) -notmatch '110.123') { throw 'Regeneration lost saved coordinates.' }
$packageRoot = Join-Path $testRoot 'hotfix'
New-Item -ItemType Directory -Force -Path (Join-Path $testRoot 'scripts'), (Join-Path $packageRoot 'scripts'), (Join-Path $packageRoot 'frontend/assets') | Out-Null
foreach ($name in @('install-map-video-hotfix.ps1', 'set-map-location.ps1', 'write-site-location.ps1')) {
    Copy-Item (Join-Path $scripts $name) (Join-Path $packageRoot "scripts/$name")
}
Copy-Item (Join-Path (Split-Path $scripts) 'SET-MAP-LOCATION.bat') $packageRoot
Set-Content (Join-Path $testRoot 'app/frontend/index.html') 'old entry'
Set-Content (Join-Path $testRoot 'app/frontend/runtime-config.js') 'preserve endpoints'
Set-Content (Join-Path $packageRoot 'frontend/index.html') 'new entry'
Set-Content (Join-Path $packageRoot 'frontend/runtime-config.js') 'must not overwrite'
Set-Content (Join-Path $packageRoot 'frontend/assets/map.js') 'new asset'
& (Join-Path $packageRoot 'scripts/install-map-video-hotfix.ps1') -InstallRoot $testRoot -MapOnly
if ((Get-Content (Join-Path $testRoot 'app/frontend/runtime-config.js') -Raw).Trim() -ne 'preserve endpoints') { throw 'Installer overwrote endpoint configuration.' }
if ((Get-Content (Join-Path $testRoot 'app/frontend/index.html') -Raw).Trim() -ne 'new entry') { throw 'Installer did not publish the new entry.' }
if (-not (Test-Path (Join-Path $testRoot 'app/frontend/assets/map.js'))) { throw 'Installer missed assets.' }
$backup = Get-ChildItem (Join-Path $testRoot 'data/backups') -Directory | Select-Object -First 1
if ((Get-Content (Join-Path $backup.FullName 'frontend/index.html') -Raw).Trim() -ne 'old entry') { throw 'Frontend backup missing.' }
Write-Host "[PASS] Map location persistence and validation. Test files: $testRoot"
