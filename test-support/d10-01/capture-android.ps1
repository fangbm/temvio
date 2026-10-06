param([string]$Serial = 'emulator-5554', [string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
if (!$Serial.StartsWith('emulator-')) { throw 'Use an isolated emulator for synthetic capture fixtures.' }
if (!$Sdk) { throw 'Pass the installed Android SDK path with -Sdk.' }
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$taskOutput = Join-Path $taskRoot 'build/d10-01/android-captures'
New-Item -ItemType Directory -Force $taskOutput | Out-Null
& $adb -s $Serial install -r (Join-Path $taskRoot 'apps/android/build/outputs/apk/debug/android-debug.apk')
if ($LASTEXITCODE) { throw 'App install failed.' }
& $adb -s $Serial install -r (Join-Path $taskRoot 'apps/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk')
if ($LASTEXITCODE) { throw 'Test install failed.' }
$variants = @(
    @(360,800,1), @(480,900,1), @(600,960,1), @(840,900,1), @(800,360,1), @(360,800,2)
)
foreach ($variant in $variants) {
    $width,$height,$font = $variant
    & $adb -s $Serial shell wm size "${width}x${height}"
    & $adb -s $Serial shell wm density 160
    & $adb -s $Serial shell settings put system font_scale $font
    foreach ($setting in @('window_animation_scale','transition_animation_scale','animator_duration_scale')) {
        & $adb -s $Serial shell settings put global $setting 0
    }
    $result = & $adb -s $Serial shell am instrument -w -r -e class 'dev.agenticscheduler.android.AppShellInstrumentedTest#actualAndroidComposeScreenshotCandidates' 'dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner'
    $result | Set-Content (Join-Path $taskOutput "${width}x${height}-font$font.txt")
    if ($LASTEXITCODE -or ($result -join "`n") -notmatch 'OK \(1 test\)') { throw "Capture failed at ${width}x${height}, font $font." }
}
& $adb -s $Serial pull '/sdcard/Android/data/dev.agenticscheduler.android/files/d10-01-screenshots' $taskOutput
if ($LASTEXITCODE) { throw 'Capture retrieval failed.' }
# This script changes only the explicitly selected synthetic emulator's display settings.
# PNGs in build/ are candidates; this script never updates repository baselines.
