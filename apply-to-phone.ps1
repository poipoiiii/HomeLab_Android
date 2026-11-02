# Build the current source and install it to the connected Android device.
# 本机有 .cache/toolchain 时优先用它；换机器时回退到系统 JDK / Android SDK。
$root = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($root)) {
    $root = Split-Path -Parent $PSCommandPath
}
if ([string]::IsNullOrWhiteSpace($root)) {
    Write-Error "Cannot determine script directory."
    exit 1
}

$localJdk = Join-Path $root ".cache\toolchain\jdk\jdk-21.0.13.0.2+1"
$localSdk = Join-Path $root ".cache\toolchain\android-sdk"
$localGradleHome = Join-Path $root ".cache\toolchain\gradle-home"
if (Test-Path $localJdk) { $env:JAVA_HOME = $localJdk }
if (Test-Path $localSdk) {
    $env:ANDROID_HOME = $localSdk
    $env:ANDROID_SDK_ROOT = $localSdk
}
if (Test-Path $localGradleHome) { $env:GRADLE_USER_HOME = $localGradleHome }
$env:ANDROID_USER_HOME = Join-Path $root ".cache\android-user"
$env:TEMP = Join-Path $root ".cache\tmp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME, $env:TEMP | Out-Null

if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "No JDK found. Put a JDK 21 in .cache\toolchain\jdk\ or set JAVA_HOME."
    exit 1
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    Write-Error "No Android SDK found. Set ANDROID_HOME or install the SDK into .cache\toolchain\android-sdk."
    exit 1
}

$adb = if (Test-Path $localSdk) { Join-Path $localSdk "platform-tools\adb.exe" } else { "adb" }
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
