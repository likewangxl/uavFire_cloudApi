. (Join-Path $PSScriptRoot 'common.ps1')

$fixedPublicHost = '192.168.1.2'
$settingsPath = Join-Path $script:ConfigDir 'settings.json'
$settings = Get-UavfireSettings

& (Join-Path $PSScriptRoot 'stop.ps1')

# Port 9000 is already serving another HTTP application on the target machine:
# it accepts TCP but returns 404 for this bundle's /healthz route. Keep that
# application untouched and move UAVFire AI to the first free port from 9002.
$aiPort = 9002
while ($aiPort -le 9010 -and (Test-TcpPort '127.0.0.1' $aiPort 300)) {
    $aiPort++
}
if ($aiPort -gt 9010) { throw 'No free AI service port was found from 9002 through 9010.' }

$settings.publicHost = $fixedPublicHost
$settings.aiPort = $aiPort
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($settingsPath, ($settings | ConvertTo-Json), $utf8)
Write-Host "[OK] publicHost=$fixedPublicHost, aiPort=$aiPort"

$mimeSource = Join-Path $script:BundleRoot 'runtime\nginx\conf\mime.types'
$mimeTarget = Join-Path $script:ConfigDir 'mime.types'
if (-not (Test-Path $mimeSource)) { throw "Missing bundled Nginx MIME types: $mimeSource" }
Copy-Item $mimeSource $mimeTarget -Force

& (Join-Path $PSScriptRoot 'configure.ps1') -PublicHost $fixedPublicHost
& (Join-Path $PSScriptRoot 'start.ps1')

if (-not (Wait-HttpStatus "http://127.0.0.1:$($settings.backendPort)/manage/api/v1/captcha" 200 120)) {
    throw 'Backend HTTP endpoint did not restart.'
}
if (-not (Wait-TcpPort '127.0.0.1' $settings.webPort 60)) { throw 'Nginx did not restart.' }
if (-not (Wait-TcpPort '127.0.0.1' $aiPort 300)) { throw "AI service did not start on port $aiPort." }

try {
    $health = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$aiPort/healthz" -TimeoutSec 10
    if ($health.StatusCode -ne 200) { throw "Unexpected AI health status $($health.StatusCode)." }
} catch {
    throw "AI health check failed on port $aiPort. $($_.Exception.Message)"
}

& (Join-Path $PSScriptRoot 'status.ps1')
