# Windows-only local secret storage. The credential is encrypted with the current user's DPAPI key.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Set', 'Check', 'Import')]
    [string]$Mode,
    [ValidatePattern('^wx[0-9a-fA-F]{16}$')]
    [string]$AppId,
    [Security.SecureString]$AppSecret
)

$ErrorActionPreference = 'Stop'
$miniappConfigDir = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'UAVFire\miniapp'
$miniappCredentialPath = Join-Path $miniappConfigDir 'wechat.credential.xml'

if ($Mode -eq 'Set') {
    if (-not $AppId) { throw 'AppId is required.' }
    if (-not $AppSecret) { $AppSecret = Read-Host 'WeChat AppSecret (hidden input)' -AsSecureString }
    if ($AppSecret.Length -ne 32) { throw 'AppSecret must contain 32 characters.' }
    if (Test-Path -LiteralPath $miniappCredentialPath) {
        $miniappPrevious = Import-Clixml -LiteralPath $miniappCredentialPath
        if ($miniappPrevious.UserName -cne $AppId) { throw 'A different AppID is already configured; choose the intended account before replacing it.' }
    }
    if (-not (Test-Path -LiteralPath $miniappConfigDir)) { [void][IO.Directory]::CreateDirectory($miniappConfigDir) }
    if ((Get-Item -LiteralPath $miniappConfigDir).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Credential directory must not be a symbolic link or junction.'
    }
    $miniappOwner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $miniappAcl = [Security.AccessControl.DirectorySecurity]::new()
    $miniappAcl.SetOwner($miniappOwner)
    $miniappAcl.SetAccessRuleProtection($true, $false)
    foreach ($miniappSid in @($miniappOwner, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $miniappRule = [Security.AccessControl.FileSystemAccessRule]::new(
            $miniappSid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $miniappAcl.AddAccessRule($miniappRule)
    }
    Set-Acl -LiteralPath $miniappConfigDir -AclObject $miniappAcl
    $miniappCredential = [Management.Automation.PSCredential]::new($AppId, $AppSecret)
    $miniappTempPath = Join-Path $miniappConfigDir ([Guid]::NewGuid().ToString() + '.tmp')
    try {
        $miniappCredential | Export-Clixml -LiteralPath $miniappTempPath -Encoding UTF8
        # Both paths are fixed children of the directory verified above; no recursive moves.
        Move-Item -LiteralPath $miniappTempPath -Destination $miniappCredentialPath -Force
    } finally {
        if (Test-Path -LiteralPath $miniappTempPath) { Remove-Item -LiteralPath $miniappTempPath }
        $miniappCredential = $null
    }
}

if (-not (Test-Path -LiteralPath $miniappCredentialPath)) { throw 'No local WeChat credential has been saved.' }
$miniappSaved = Import-Clixml -LiteralPath $miniappCredentialPath
if ($miniappSaved -isnot [Management.Automation.PSCredential] -or $miniappSaved.UserName -notmatch '^wx[0-9a-fA-F]{16}$' -or $miniappSaved.Password.Length -ne 32) {
    throw 'Invalid local WeChat credential.'
}
if ($AppId -and $miniappSaved.UserName -cne $AppId) { throw 'Stored AppID does not match the requested AppID.' }
if ($Mode -eq 'Import') {
    $env:MINIAPP_WECHAT_APP_ID = $miniappSaved.UserName
    $env:MINIAPP_WECHAT_APP_SECRET = $miniappSaved.GetNetworkCredential().Password
    $env:MINIAPP_WECHAT_ENABLED = 'true'
    # Does not enable MINIAPP_ENABLED, apply SQL, or start/restart a backend.
} else {
    [pscustomobject]@{
        AppId = $miniappSaved.UserName
        SecretConfigured = $true
        Storage = 'Windows DPAPI / current user'
        Path = $miniappCredentialPath
    }
}
$miniappSaved = $null
