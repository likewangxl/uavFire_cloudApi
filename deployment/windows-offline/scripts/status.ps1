param([switch]$SkipAI)

. (Join-Path $PSScriptRoot 'common.ps1')

$settings = Get-UavfireSettings
$checks = @(
    @('MySQL', $settings.mysqlPort),
    @('Redis', $settings.redisPort),
    @('MQTT', $settings.mqttPort),
    @('MQTT-WebSocket', $settings.mqttWebSocketPort),
    @('ZLM-RTMP', $settings.zlmRtmpPort),
    @('ZLM-HTTP', $settings.zlmHttpPort),
    @('ZLM-RTSP', $settings.zlmRtspPort),
    @('ZLM-WebRTC', $settings.zlmRtcPort),
    @('MinIO', $settings.minioApiPort),
    @('Backend', $settings.backendPort),
    @('Web', $settings.webPort)
)
if (-not $SkipAI) { $checks += ,@('AI', $settings.aiPort) }

$failed = 0
foreach ($check in $checks) {
    $ok = Wait-TcpPort '127.0.0.1' ([int]$check[1]) 1
    if ($ok) { Write-Host ("[OK]   {0,-18} TCP {1}" -f $check[0], $check[1]) -ForegroundColor Green }
    else { Write-Host ("[FAIL] {0,-18} TCP {1}" -f $check[0], $check[1]) -ForegroundColor Red; $failed++ }
}

$httpChecks = @(
    @('Frontend', "http://127.0.0.1:$($settings.webPort)/"),
    @('Backend captcha', "http://127.0.0.1:$($settings.backendPort)/manage/api/v1/captcha"),
    @('MinIO health', "http://127.0.0.1:$($settings.minioApiPort)/minio/health/live")
)
if (-not $SkipAI) { $httpChecks += ,@('AI health', "http://127.0.0.1:$($settings.aiPort)/healthz") }
foreach ($check in $httpChecks) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $check[1] -TimeoutSec 5
        Write-Host "[OK]   $($check[0]) HTTP $($response.StatusCode)" -ForegroundColor Green
    } catch {
        Write-Host "[FAIL] $($check[0]) $($_.Exception.Message)" -ForegroundColor Red
        $failed++
    }
}

Write-Host "Access URL: http://$($settings.publicHost):$($settings.webPort)"
if ($failed -gt 0) { throw "$failed UAVFire health check(s) failed. Inspect data\logs for details." }
