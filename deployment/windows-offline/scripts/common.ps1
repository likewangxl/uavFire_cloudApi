Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:BundleRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:ConfigDir = Join-Path $script:BundleRoot 'config'
$script:PidDir = Join-Path $script:BundleRoot 'data\pids'
$script:LogDir = Join-Path $script:BundleRoot 'data\logs'

function Get-UavfireSettings {
    Get-Content (Join-Path $script:ConfigDir 'settings.json') -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-UavfireSecrets {
    $path = Join-Path $script:ConfigDir 'secrets.json'
    if (-not (Test-Path $path)) { throw "Missing $path. Run INSTALL.bat first." }
    Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-PrimaryIPv4 {
    $candidate = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and -not $_.SkipAsSource } |
        Sort-Object InterfaceMetric |
        Select-Object -First 1
    if ($null -eq $candidate) { throw 'No usable LAN IPv4 address was detected.' }
    $candidate.IPAddress
}

function Test-TcpPort([string]$HostName, [int]$Port, [int]$TimeoutMilliseconds = 500) {
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $task = $client.ConnectAsync($HostName, $Port)
        $connected = $task.Wait($TimeoutMilliseconds) -and $client.Connected
        $client.Dispose()
        return $connected
    } catch {
        return $false
    }
}

function Wait-TcpPort([string]$HostName, [int]$Port, [int]$TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort $HostName $Port 800) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Get-TcpListenerProcessIds([int]$Port) {
    @(
        Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
    )
}

function Test-TcpPortOwnedByProcess([int]$Port, [int]$ProcessId) {
    @(Get-TcpListenerProcessIds $Port) -contains $ProcessId
}

function Wait-TcpPortOwnedByProcess([int]$Port, [int]$ProcessId, [int]$TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if ($null -eq $process) { return $false }
        if (Test-TcpPortOwnedByProcess $Port $ProcessId) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Wait-HttpStatus([string]$Uri, [int]$ExpectedStatus = 200, [int]$TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
            if ([int]$response.StatusCode -eq $ExpectedStatus) { return $true }
        } catch {
            # The service may still be starting. Retry until the deadline.
        }
        Start-Sleep -Milliseconds 750
    }
    return $false
}

function Wait-RedisPong([string]$RedisCli, [int]$Port, [int]$TimeoutSeconds = 60) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastResponse = ''
    while ((Get-Date) -lt $deadline) {
        try {
            $response = & $RedisCli '-h' '127.0.0.1' '-p' ([string]$Port) 'PING' 2>&1
            $lastResponse = (@($response) | ForEach-Object { [string]$_ }) -join ' '
            if ($LASTEXITCODE -eq 0 -and $lastResponse.Trim() -eq 'PONG') {
                return $true
            }
        } catch {
            $lastResponse = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 750
    }
    Write-Warning "Redis PING did not become ready on TCP $Port. Last response: $lastResponse"
    return $false
}

function Start-UavfireProcess {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$WorkingDirectory = $script:BundleRoot
    )
    New-Item -ItemType Directory -Force -Path $script:PidDir, $script:LogDir | Out-Null
    $pidFile = Join-Path $script:PidDir "$Name.pid"
    if (Test-Path $pidFile) {
        $oldPid = [int](Get-Content $pidFile -Raw)
        $oldProcess = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
        $expectedProcessName = [System.IO.Path]::GetFileNameWithoutExtension($FilePath)
        if ($oldProcess -and $oldProcess.ProcessName -ieq $expectedProcessName) {
            Write-Host "[OK] $Name already running (PID $oldPid)"
            return $oldProcess
        }
        Remove-Item $pidFile -Force
    }
    if (-not (Test-Path $FilePath)) { throw "Missing executable for ${Name}: $FilePath" }
    $stdoutLog = Join-Path $script:LogDir "$Name-stdout.log"
    $stderrLog = Join-Path $script:LogDir "$Name-stderr.log"
    $startParams = @{
        FilePath = $FilePath
        ArgumentList = $ArgumentList
        WorkingDirectory = $WorkingDirectory
        PassThru = $true
        RedirectStandardOutput = $stdoutLog
        RedirectStandardError = $stderrLog
    }
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $startParams.WindowStyle = 'Hidden'
    }
    $process = Start-Process @startParams
    Set-Content -Path $pidFile -Value $process.Id -Encoding ASCII
    Write-Host "[OK] started $Name (PID $($process.Id))"
    return $process
}

function Stop-UavfireProcess([string]$Name) {
    $pidFile = Join-Path $script:PidDir "$Name.pid"
    if (-not (Test-Path $pidFile)) { return }
    $processId = [int](Get-Content $pidFile -Raw)
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        $process.WaitForExit(5000) | Out-Null
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] stopped $Name"
}

function Find-BundledFile([string]$Pattern) {
    $match = Get-ChildItem (Join-Path $script:BundleRoot 'runtime') -Recurse -File -Filter $Pattern |
        Select-Object -First 1
    if ($null -eq $match) { throw "Bundled file not found: $Pattern" }
    $match.FullName
}

function Set-IniValue([string]$Text, [string]$Section, [string]$Key, [string]$Value) {
    $pattern = '(?ms)(^\[' + [regex]::Escape($Section) + '\]\s*.*?^' + [regex]::Escape($Key) + '=)[^\r\n]*'
    [regex]::Replace($Text, $pattern, ('${1}' + $Value), 1)
}
