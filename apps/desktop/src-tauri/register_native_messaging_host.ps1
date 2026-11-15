# Registers the Flowww Native Messaging Host for Chrome and Edge on Windows.
#
# Chrome reads host manifests from:
#   HKCU\Software\Google\Chrome\NativeMessagingHosts\<host-name>
# Edge reads from:
#   HKCU\Software\Microsoft\Edge\NativeMessagingHosts\<host-name>
#
# Each registry value's (default) data points to the host manifest JSON file.
#
# Usage (as Administrator, or per-user without admin for HKCU):
#   .\register_native_messaging_host.ps1 -HostPath "C:\Program Files\Flowww\flowww_native_messaging_host.exe" -ExtensionId "abcdefghijklmnopqrstuvwxyz"

param(
    [Parameter(Mandatory = $true)]
    [string]$HostPath,

    [Parameter(Mandatory = $true)]
    [string]$ExtensionId,

    [Parameter(Mandatory = $true)]
    [string]$ManifestPath
)

$HostName = "com.flowww.native_messaging"

# 1. Write the host manifest JSON with resolved paths.
$manifestContent = @"
{
  "name": "$HostName",
  "description": "Flowww Native Messaging Host for browser extension communication",
  "path": "$HostPath",
  "type": "stdio",
  "allowed_origins": [
    "chrome-extension://$ExtensionId/"
  ]
}
"@

$manifestContent | Out-File -FilePath $ManifestPath -Encoding utf8

Write-Host "Wrote host manifest to $ManifestPath"

# 2. Register for Chrome (per-user).
$chromeKey = "HKCU:\Software\Google\Chrome\NativeMessagingHosts"
if (-not (Test-Path $chromeKey)) {
    New-Item -Path $chromeKey -Force | Out-Null
}
Set-ItemProperty -Path "$chromeKey\$HostName" -Name "" -Value $ManifestPath
Write-Host "Registered Chrome Native Messaging Host at $chromeKey\$HostName"

# 3. Register for Edge (per-user).
$edgeKey = "HKCU:\Software\Microsoft\Edge\NativeMessagingHosts"
if (-not (Test-Path $edgeKey)) {
    New-Item -Path $edgeKey -Force | Out-Null
}
Set-ItemProperty -Path "$edgeKey\$HostName" -Name "" -Value $ManifestPath
Write-Host "Registered Edge Native Messaging Host at $edgeKey\$HostName"

Write-Host "Native Messaging Host registration complete."
Write-Host "Extension ID: $ExtensionId"
Write-Host "Host path: $HostPath"
Write-Host "Manifest path: $ManifestPath"
