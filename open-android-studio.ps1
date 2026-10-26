# Launch the portable Android Studio from the project toolchain.
$root = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($root)) {
    $root = Split-Path -Parent $PSCommandPath
}
if ([string]::IsNullOrWhiteSpace($root)) {
    Write-Error "Cannot determine script directory. Run it from the android folder."
    exit 1
}

$env:JAVA_HOME = Join-Path $root ".cache\toolchain\jdk\jdk-21.0.13.0.2+1"
$env:STUDIO_JDK = Join-Path $root ".cache\toolchain\android-studio\android-studio\jbr"
$env:ANDROID_HOME = Join-Path $root ".cache\toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $root ".cache\android-user"
$env:GRADLE_USER_HOME = Join-Path $root ".cache\toolchain\gradle-home"
$env:TEMP = Join-Path $root ".cache\tmp"
$env:TMP = $env:TEMP

New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME, $env:TEMP | Out-Null

$studio = Join-Path $root ".cache\toolchain\android-studio\android-studio\bin\studio64.exe"
if (-not (Test-Path $studio)) {
    Write-Error "Android Studio not found: $studio"
    exit 1
}

Start-Process -FilePath $studio -ArgumentList (Join-Path $root "HomelabApp")
