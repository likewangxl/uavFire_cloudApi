param(
    [Parameter(Mandatory = $true)]
    [string]$PublicHost,

    [string]$InstallRoot = 'C:\UAVFire'
)

$resolvedInstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$installedScripts = Join-Path $resolvedInstallRoot 'scripts'
$installedCommon = Join-Path $installedScripts 'common.ps1'
$installedSettings = Join-Path $resolvedInstallRoot 'config\settings.json'
if (-not (Test-Path $installedCommon) -or -not (Test-Path $installedSettings)) {
    throw "UAVFire is not installed at $resolvedInstallRoot."
}

# The hotfix can be launched from a USB drive. Install the resilient stop
# script into the real C:\UAVFire tree before touching any running service.
$bundledStop = Join-Path $PSScriptRoot 'stop.ps1'
$installedStop = Join-Path $installedScripts 'stop.ps1'
if (-not (Test-Path $bundledStop)) { throw "Missing hotfix file: $bundledStop" }
if ([System.IO.Path]::GetFullPath($bundledStop) -ine [System.IO.Path]::GetFullPath($installedStop)) {
    Copy-Item $bundledStop $installedStop -Force
}

. $installedCommon

if ([System.IO.Path]::GetFullPath($script:BundleRoot).TrimEnd('\') -ine $resolvedInstallRoot.TrimEnd('\')) {
    throw "Resolved bundle root $script:BundleRoot does not match $resolvedInstallRoot."
}

$parsedAddress = $null
if (-not [System.Net.IPAddress]::TryParse($PublicHost, [ref]$parsedAddress) -or
    $parsedAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw "Invalid IPv4 address: $PublicHost"
}

$settingsPath = Join-Path $script:ConfigDir 'settings.json'
$settings = Get-UavfireSettings
$settings.publicHost = $PublicHost
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($settingsPath, ($settings | ConvertTo-Json), $utf8)
Write-Host "[OK] publicHost updated to $PublicHost"

# Older offline bundles intentionally exposed only the Nginx MQTT WebSocket
# proxy and bound native MQTT to loopback. The RC Plus Agent now uses the
# authenticated native listener, so expose only that listener to the LAN.
$mosquittoTemplatePath = Join-Path $script:ConfigDir 'mosquitto.conf.template'
$mosquittoTemplate = Get-Content $mosquittoTemplatePath -Raw -Encoding UTF8
$nativeListenerPattern = '(?m)^listener\s+\{\{MQTT_PORT\}\}\s+127\.0\.0\.1\s*$'
if ($mosquittoTemplate -match $nativeListenerPattern) {
    $mosquittoTemplate = [regex]::Replace(
        $mosquittoTemplate,
        $nativeListenerPattern,
        'listener {{MQTT_PORT}} 0.0.0.0',
        1
    )
    [System.IO.File]::WriteAllText($mosquittoTemplatePath, $mosquittoTemplate, $utf8)
}
if ((Get-Content $mosquittoTemplatePath -Raw -Encoding UTF8) -notmatch
    '(?m)^listener\s+\{\{MQTT_PORT\}\}\s+0\.0\.0\.0\s*$') {
    throw 'Mosquitto native MQTT listener template was not updated for LAN access.'
}
Write-Host "[OK] MQTT native listener enabled for LAN access"

& (Join-Path $installedScripts 'stop.ps1')
& (Join-Path $installedScripts 'configure.ps1') -PublicHost $PublicHost

$settings = Get-UavfireSettings
$tcpPorts = @(
    [int]$settings.webPort,
    [int]$settings.mqttPort,
    [int]$settings.zlmRtmpPort,
    [int]$settings.zlmRtcPort
) | Sort-Object -Unique

foreach ($port in $tcpPorts) {
    $ruleName = "UAVFire TCP $port"
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
        -Protocol TCP -LocalPort $port -Profile Any | Out-Null
    Write-Host "[OK] firewall TCP $port"
}

$rtcUdpRule = "UAVFire UDP $($settings.zlmRtcPort)"
Get-NetFirewallRule -DisplayName $rtcUdpRule -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName $rtcUdpRule -Direction Inbound -Action Allow `
    -Protocol UDP -LocalPort ([int]$settings.zlmRtcPort) -Profile Any | Out-Null
Write-Host "[OK] firewall UDP $($settings.zlmRtcPort)"

& (Join-Path $installedScripts 'start.ps1')

if (-not (Wait-TcpPort '127.0.0.1' ([int]$settings.webPort) 90)) {
    throw "Nginx did not start on TCP $($settings.webPort)."
}
if (-not (Wait-TcpPort '127.0.0.1' ([int]$settings.zlmRtmpPort) 60)) {
    throw "ZLMediaKit RTMP did not start on TCP $($settings.zlmRtmpPort)."
}
if (-not (Wait-TcpPort '127.0.0.1' ([int]$settings.zlmHttpPort) 60)) {
    throw "ZLMediaKit HTTP did not start on TCP $($settings.zlmHttpPort)."
}
if (-not (Wait-TcpPort $PublicHost ([int]$settings.mqttPort) 60)) {
    throw "Mosquitto is not reachable on ${PublicHost}:$($settings.mqttPort)."
}

$backendOverride = Get-Content (Join-Path $script:ConfigDir 'backend-override.yml') -Raw -Encoding UTF8
$zlmConfig = Get-Content (Join-Path $script:ConfigDir 'zlmediakit.ini') -Raw -Encoding UTF8
if ($backendOverride -notmatch ('webrtc-host:\s*' + [regex]::Escape($PublicHost))) {
    throw 'Generated backend WebRTC host does not match the requested IP.'
}
if ($zlmConfig -notmatch ('(?m)^externIP=' + [regex]::Escape($PublicHost) + '\r?$')) {
    throw 'Generated ZLMediaKit WebRTC external IP does not match the requested IP.'
}

& (Join-Path $installedScripts 'status.ps1')
Write-Host "[OK] UAVFire is ready at http://${PublicHost}:$($settings.webPort)"
