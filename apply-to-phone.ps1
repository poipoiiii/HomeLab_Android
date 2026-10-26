# Build the current source and install it to the connected Android device.
$root = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($root)) {
    $root = Split-Path -Parent $PSCommandPath
}
if ([string]::IsNullOrWhiteSpace($root)) {
    Write-Error "Cannot determine script directory."
    exit 1
}

$env:JAVA_HOME = Join-Path $root ".cache\toolchain\jdk\jdk-21.0.13.0.2+1"
$env:ANDROID_HOME = Join-Path $root ".cache\toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $root ".cache\android-user"
$env:GRADLE_USER_HOME = Join-Path $root ".cache\toolchain\gradle-home"
$env:TEMP = Join-Path $root ".cache\tmp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME, $env:TEMP | Out-Null

$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$gradle = Join-Path $root "HomelabApp\gradlew.bat"

& $adb start-server | Out-Null
$devices = & $adb devices | Select-String "device$"
if ($devices.Count -eq 0) {
    Write-Error "No Android device connected. Enable USB debugging and try again."
    exit 1
}
if ($devices.Count -gt 1) {
    Write-Warning "Multiple devices connected; installDebug may pick one. Disconnect extra devices if needed."
}

Push-Location (Join-Path $root "HomelabApp")
& $gradle installDebug --no-daemon
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) {
    Write-Error "Gradle installDebug failed with exit code $code"
    exit $code
}

& $adb shell am force-stop com.poi.homelab
& $adb shell am start -n com.poi.homelab/.MainActivity
Write-Output "Installed and launched com.poi.homelab"
