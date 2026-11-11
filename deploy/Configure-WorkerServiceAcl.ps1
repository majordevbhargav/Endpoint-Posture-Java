<#
.SYNOPSIS
Sets restrictive ACLs on the staged worker binaries, persistent state and logs.
.DESCRIPTION
Run explicitly as an administrator after staging the package and account. It refuses
reparse points and replaces ACLs beneath only the three supplied paths. It does not
create an account or install/configure a Windows service.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)][string]$ServiceAccount,
    [string]$InstallDirectory = "$env:ProgramFiles\EndpointPostureWorker",
    [string]$DataDirectory = "$env:ProgramData\EndpointPostureWorker",
    [string]$LogDirectory = "$env:ProgramData\EndpointPostureWorker\logs"
)

$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run ACL provisioning from an elevated PowerShell session.'
}
if ($ServiceAccount -eq 'svc-posture' -or $ServiceAccount -match '(^|\\)svc-posture$') {
    throw 'The backend svc-posture account is not the worker identity. Use the selected worker account.'
}
$accountSid = ([Security.Principal.NTAccount]$ServiceAccount).Translate([Security.Principal.SecurityIdentifier])
$systemSid = [Security.SecurityIdentifier]::new('S-1-5-18')
$adminsSid = [Security.SecurityIdentifier]::new('S-1-5-32-544')

$paths = @(
    @{ Path = [IO.Path]::GetFullPath($InstallDirectory); Kind = 'Install' },
    @{ Path = [IO.Path]::GetFullPath($DataDirectory); Kind = 'Private' },
    @{ Path = [IO.Path]::GetFullPath($LogDirectory); Kind = 'Private' }
)
if ($paths.Path -contains ([IO.Path]::GetPathRoot($paths[0].Path))) { throw 'Refusing to apply ACLs to a volume root.' }
for ($i = 0; $i -lt $paths.Count; $i++) {
    for ($j = $i + 1; $j -lt $paths.Count; $j++) {
        if ($paths[$i].Path.StartsWith($paths[$j].Path + '\',[StringComparison]::OrdinalIgnoreCase) -or
            $paths[$j].Path.StartsWith($paths[$i].Path + '\',[StringComparison]::OrdinalIgnoreCase)) {
            if ($paths[$i].Kind -ne 'Private' -or $paths[$j].Kind -ne 'Private') {
                throw 'Install directory must not contain or be contained by private data/log directories.'
            }
        }
    }
}

foreach ($entry in $paths) {
    if (-not (Test-Path -LiteralPath $entry.Path)) { throw "Expected staged path does not exist: $($entry.Path)" }
    $objects = @((Get-Item -LiteralPath $entry.Path -Force)) + @(Get-ChildItem -LiteralPath $entry.Path -Force -Recurse)
    if ($objects | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
        throw "Reparse point found under $($entry.Path); review and remove it before ACL provisioning."
    }
    foreach ($item in $objects) {
        if (-not $PSCmdlet.ShouldProcess($item.FullName, 'Replace access control list')) { continue }
        if ($item.PSIsContainer) {
            $acl = [Security.AccessControl.DirectorySecurity]::new()
            $acl.SetAccessRuleProtection($true, $false)
            $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
            $propagation = [Security.AccessControl.PropagationFlags]::None
            $access = [Security.AccessControl.AccessControlType]::Allow
            foreach ($rule in @(
                [Security.AccessControl.FileSystemAccessRule]::new($systemSid,[Security.AccessControl.FileSystemRights]::FullControl,$inherit,$propagation,$access),
                [Security.AccessControl.FileSystemAccessRule]::new($adminsSid,[Security.AccessControl.FileSystemRights]::FullControl,$inherit,$propagation,$access)
            )) { [void]$acl.AddAccessRule($rule) }
            $workerRights = if ($entry.Kind -eq 'Install') { [Security.AccessControl.FileSystemRights]::ReadAndExecute } else { [Security.AccessControl.FileSystemRights]::Modify }
            [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($accountSid,$workerRights,$inherit,$propagation,$access))
            Set-Acl -LiteralPath $item.FullName -AclObject $acl
        } else {
            $acl = [Security.AccessControl.FileSecurity]::new()
            $acl.SetAccessRuleProtection($true, $false)
            $rights = if ($entry.Kind -eq 'Install') { [Security.AccessControl.FileSystemRights]::ReadAndExecute } else { [Security.AccessControl.FileSystemRights]::Modify }
            foreach ($sid in @($systemSid,$adminsSid,$accountSid)) {
                $fileRights = if ($sid -eq $accountSid) { $rights } else { [Security.AccessControl.FileSystemRights]::FullControl }
                [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,$fileRights,[Security.AccessControl.AccessControlType]::Allow))
            }
            Set-Acl -LiteralPath $item.FullName -AclObject $acl
        }
    }
}
Write-Host "Applied restrictive ACLs to install, data, and log trees for $ServiceAccount, SYSTEM, and Administrators. Verify with icacls before service installation."
