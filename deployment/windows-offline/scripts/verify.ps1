. (Join-Path $PSScriptRoot 'common.ps1')

$checksumFile = Join-Path $script:BundleRoot 'CHECKSUMS.sha256'
if (-not (Test-Path $checksumFile)) { throw "Missing checksum manifest: $checksumFile" }

$failed = 0
$checked = 0
$checksumLines = @(Get-Content $checksumFile)
$total = $checksumLines.Count
Write-Host "[INFO] Verifying $total package files. Large runtime and model files may take several minutes..."
foreach ($line in $checksumLines) {
    if ($line -notmatch '^([0-9a-fA-F]{64})\s+\*?(.+)$') { throw "Invalid checksum line: $line" }
    $expected = $matches[1].ToUpperInvariant()
    $relative = $matches[2]
    if ($relative.StartsWith('./')) { $relative = $relative.Substring(2) }
    $current = $checked + 1
    $progress = [math]::Floor(($current / [math]::Max($total, 1)) * 100)
    Write-Progress -Activity 'Verifying UAVFire package' -Status "$current / $total - $relative" -PercentComplete $progress
    if ($current -eq 1 -or $current % 25 -eq 0 -or $current -eq $total) {
        Write-Host "[INFO] checksum progress: $current / $total"
    }
    $path = Join-Path $script:BundleRoot $relative
    if (-not (Test-Path $path)) {
        Write-Host "[FAIL] missing $relative" -ForegroundColor Red
        $failed++
        continue
    }
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash
    if ($actual -ne $expected) {
        Write-Host "[FAIL] checksum $relative" -ForegroundColor Red
        $failed++
    }
    $checked++
}
Write-Progress -Activity 'Verifying UAVFire package' -Completed

if ($failed -gt 0) { throw "$failed file integrity check(s) failed." }
Write-Host "[OK] verified $checked package files." -ForegroundColor Green
