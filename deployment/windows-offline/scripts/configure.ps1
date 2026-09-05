param(
    [string]$PublicHost = '',
    [int]$WebPort = 0
)

. (Join-Path $PSScriptRoot 'common.ps1')

function New-RandomSecret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    ([Convert]::ToBase64String($bytes)).Replace('+','A').Replace('/','B').TrimEnd('=')
}

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8)
}

function Expand-Template([string]$TemplatePath, [string]$OutputPath, [hashtable]$Values) {
    $content = Get-Content $TemplatePath -Raw -Encoding UTF8
    foreach ($key in $Values.Keys) { $content = $content.Replace("{{$key}}", [string]$Values[$key]) }
    Write-Utf8NoBom $OutputPath $content
}

$settingsPath = Join-Path $script:ConfigDir 'settings.json'
$settings = Get-UavfireSettings
if ($PublicHost) { $settings.publicHost = $PublicHost }
if ($WebPort -gt 0) { $settings.webPort = $WebPort }
if ($settings.publicHost -eq 'AUTO' -or -not $settings.publicHost) {
    $settings.publicHost = Get-PrimaryIPv4
}
$portProperties = @($settings.PSObject.Properties | Where-Object { $_.Name -like '*Port' })
foreach ($property in $portProperties) {
    $port = [int]$property.Value
    if ($port -lt 1 -or $port -gt 65535) { throw "Invalid port $port in setting $($property.Name)." }
}
$duplicatePorts = @($portProperties | Group-Object { [int]$_.Value } | Where-Object { $_.Count -gt 1 })
if ($duplicatePorts.Count -gt 0) {
    $details = $duplicatePorts | ForEach-Object { "$($_.Name): $((@($_.Group.Name) -join ', '))" }
    throw "Duplicate ports in config\settings.json: $($details -join '; ')"
}
Write-Utf8NoBom $settingsPath ($settings | ConvertTo-Json)

$secretsPath = Join-Path $script:ConfigDir 'secrets.json'
if (-not (Test-Path $secretsPath)) {
    $agentBootstrapPath = Join-Path $script:ConfigDir 'agent-bootstrap-secrets.json'
    $agentBootstrap = $null
    if (Test-Path $agentBootstrapPath) {
        $agentBootstrap = Get-Content $agentBootstrapPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($propertyName in @('mqttPassword', 'waylineAgentSharedSecret')) {
            if (-not $agentBootstrap.PSObject.Properties[$propertyName] -or
                [string]::IsNullOrWhiteSpace([string]$agentBootstrap.$propertyName)) {
                throw "Invalid Agent bootstrap secrets: missing $propertyName."
            }
        }
    }
    $generatedSecrets = [ordered]@{
        mysqlRootPassword = (New-RandomSecret)
        mysqlAppPassword = (New-RandomSecret)
        minioUser = 'uavfire'
        minioPassword = (New-RandomSecret)
        mqttPassword = $(if ($agentBootstrap) { [string]$agentBootstrap.mqttPassword } else { New-RandomSecret })
        waylineAgentSharedSecret = $(if ($agentBootstrap) { [string]$agentBootstrap.waylineAgentSharedSecret } else { New-RandomSecret })
    }
    Write-Utf8NoBom $secretsPath ($generatedSecrets | ConvertTo-Json)
}
$secrets = Get-UavfireSecrets
if (-not $secrets.PSObject.Properties['waylineAgentSharedSecret']) {
    $secrets | Add-Member -NotePropertyName 'waylineAgentSharedSecret' -NotePropertyValue (New-RandomSecret)
    Write-Utf8NoBom $secretsPath ($secrets | ConvertTo-Json)
}
if (-not $secrets.PSObject.Properties['mqttPassword']) {
    $secrets | Add-Member -NotePropertyName 'mqttPassword' -NotePropertyValue (New-RandomSecret)
    Write-Utf8NoBom $secretsPath ($secrets | ConvertTo-Json)
}

$rootForward = $script:BundleRoot.Replace('\','/')
$mysqlRoot = (Join-Path $script:BundleRoot 'runtime\mysql').Replace('\','/')
$values = @{
    'ROOT_FORWARD' = $rootForward
    'MYSQL_ROOT_FORWARD' = $mysqlRoot
    'PUBLIC_HOST' = $settings.publicHost
    'WEB_PORT' = $settings.webPort
    'BACKEND_PORT' = $settings.backendPort
    'MYSQL_PORT' = $settings.mysqlPort
    'REDIS_PORT' = $settings.redisPort
    'MQTT_PORT' = $settings.mqttPort
    'MQTT_WS_PORT' = $settings.mqttWebSocketPort
    'ZLM_RTMP_PORT' = $settings.zlmRtmpPort
    'ZLM_HTTP_PORT' = $settings.zlmHttpPort
    'ZLM_RTSP_PORT' = $settings.zlmRtspPort
    'ZLM_RTC_PORT' = $settings.zlmRtcPort
    'AI_PORT' = $settings.aiPort
    'MINIO_API_PORT' = $settings.minioApiPort
    'MINIO_CONSOLE_PORT' = $settings.minioConsolePort
    'MYSQL_APP_PASSWORD' = $secrets.mysqlAppPassword
    'MINIO_USER' = $secrets.minioUser
    'MINIO_PASSWORD' = $secrets.minioPassword
    'MQTT_PASSWORD' = $secrets.mqttPassword
    'WAYLINE_AGENT_SHARED_SECRET' = $secrets.waylineAgentSharedSecret
}

Expand-Template (Join-Path $script:ConfigDir 'backend-override.yml.template') (Join-Path $script:ConfigDir 'backend-override.yml') $values
Expand-Template (Join-Path $script:ConfigDir 'nginx.conf.template') (Join-Path $script:ConfigDir 'nginx.conf') $values
Expand-Template (Join-Path $script:ConfigDir 'mysql.ini.template') (Join-Path $script:ConfigDir 'mysql.ini') $values
Expand-Template (Join-Path $script:ConfigDir 'redis.conf.template') (Join-Path $script:ConfigDir 'redis.conf') $values
Expand-Template (Join-Path $script:ConfigDir 'mosquitto.conf.template') (Join-Path $script:ConfigDir 'mosquitto.conf') $values
Expand-Template (Join-Path $script:ConfigDir 'ai.env.template') (Join-Path $script:BundleRoot 'app\ai-service\.env') $values
Expand-Template (Join-Path $script:ConfigDir 'runtime-config.js.template') (Join-Path $script:BundleRoot 'app\frontend\runtime-config.js') $values
& (Join-Path $PSScriptRoot 'write-site-location.ps1') -InstallRoot $script:BundleRoot

$zlmSource = Join-Path $script:BundleRoot 'runtime\zlmediakit\config.ini'
if (-not (Test-Path $zlmSource)) { throw "Missing ZLMediaKit configuration: $zlmSource" }
$zlmConfig = Join-Path $script:ConfigDir 'zlmediakit.ini'
$zlm = Get-Content $zlmSource -Raw -Encoding UTF8
$zlm = Set-IniValue $zlm 'http' 'port' ([string]$settings.zlmHttpPort)
$zlm = Set-IniValue $zlm 'rtmp' 'port' ([string]$settings.zlmRtmpPort)
$zlm = Set-IniValue $zlm 'rtsp' 'port' ([string]$settings.zlmRtspPort)
$zlm = Set-IniValue $zlm 'rtc' 'externIP' ([string]$settings.publicHost)
$zlm = Set-IniValue $zlm 'rtc' 'port' ([string]$settings.zlmRtcPort)
$zlm = Set-IniValue $zlm 'rtc' 'tcpPort' ([string]$settings.zlmRtcPort)
$zlm = Set-IniValue $zlm 'shell' 'port' '0'
Write-Utf8NoBom $zlmConfig $zlm

$mosquittoPasswd = Join-Path $script:BundleRoot 'runtime\mosquitto\mosquitto_passwd.exe'
& $mosquittoPasswd -b -c (Join-Path $script:ConfigDir 'mosquitto.passwd') JavaServer $secrets.mqttPassword
if ($LASTEXITCODE -ne 0) { throw 'Failed to create Mosquitto password file.' }

Write-Host "[OK] configuration generated for http://$($settings.publicHost):$($settings.webPort)"
