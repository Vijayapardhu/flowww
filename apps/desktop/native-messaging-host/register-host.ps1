# PowerShell script to register Flowww Native Messaging host with Chrome and Edge.
# Run as Administrator for HKLM, or without for HKCU.

$hostName = "com.flowww.desktop"
$manifestPath = "C:\Program Files\Flowww\native-messaging-host\com.flowww.desktop.json"
$extensionId = "<REPLACE_WITH_ACTUAL_EXTENSION_ID>"

# Replace extension ID placeholder in manifest
$manifestContent = Get-Content $manifestPath -Raw
$manifestContent = $manifestContent.Replace("__EXTENSION_ID__", $extensionId)
$manifestContent | Set-Content $manifestPath

# Chrome Native Messaging host registry keys
$chromePaths = @(
    "HKCU:\Software\Google\Chrome\NativeMessagingHosts\$hostName",
    "HKLM:\Software\Google\Chrome\NativeMessagingHosts\$hostName"
)

# Edge Native Messaging host registry keys
$edgePaths = @(
    "HKCU:\Software\Microsoft\Edge\NativeMessagingHosts\$hostName",
    "HKLM:\Software\Microsoft\Edge\NativeMessagingHosts\$hostName"
)

$allPaths = $chromePaths + $edgePaths

foreach ($path in $allPaths) {
    try {
        if (-not (Test-Path $path)) {
            New-Item -Path $path -Force | Out-Null
        }
        Set-ItemProperty -Path $path -Name "" -Value $manifestPath
        Write-Host "Registered Native Messaging host at $path"
    } catch {
        Write-Warning "Failed to register at $path: $_"
    }
}

Write-Host "Native Messaging host registration complete."
Write-Host "Replace <REPLACE_WITH_ACTUAL_EXTENSION_ID> with the actual Chrome extension ID."
Write-Host "Install the flowww-native-host.exe at C:\Program Files\Flowww\flowww-native-host.exe"
