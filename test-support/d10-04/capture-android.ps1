param([string]$Serial = 'emulator-5554', [string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
if (!$Serial.StartsWith('emulator-')) { throw 'Use an isolated emulator for synthetic fixtures.' }
if (!$Sdk) { throw 'Pass -Sdk for the installed Android SDK.' }
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$taskOutput = Join-Path $taskRoot 'build/d10-04/android-captures'
New-Item -ItemType Directory -Force $taskOutput | Out-Null
foreach ($apk in @('apps/android/build/outputs/apk/debug/android-debug.apk','apps/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk')) {
    & $adb -s $Serial install -r (Join-Path $taskRoot $apk)
    if ($LASTEXITCODE) { throw 'APK install failed.' }
}
try {
    foreach ($variant in @(@(360,800,1),@(480,900,1),@(600,960,1),@(840,900,1),@(1024,768,1),@(800,360,1),@(360,800,2))) {
        $width,$height,$font = $variant
        & $adb -s $Serial shell wm size "${width}x${height}"
        if ($LASTEXITCODE) { throw 'Display size change failed.' }
        & $adb -s $Serial shell wm density 160
        if ($LASTEXITCODE) { throw 'Display density change failed.' }
        & $adb -s $Serial shell settings put system font_scale $font
        if ($LASTEXITCODE) { throw 'Font scale change failed.' }
        foreach ($setting in @('window_animation_scale','transition_animation_scale','animator_duration_scale')) { & $adb -s $Serial shell settings put global $setting 0 }
        $result = & $adb -s $Serial shell am instrument -w -r -e captureWidth $width -e captureHeight $height -e captureFont $font -e class 'dev.agenticscheduler.android.AgentWorkspaceInstrumentedTest#actualAndroidD10AgentScreenshotCandidates' 'dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner'
        $result | Set-Content (Join-Path $taskOutput "${width}x${height}-font$font.txt")
        if ($LASTEXITCODE -or ($result -join "`n") -notmatch 'OK \(1 test\)') { throw "Capture failed at ${width}x${height}, font $font." }
    }
    & $adb -s $Serial pull '/sdcard/Android/data/dev.agenticscheduler.android/files/d10-04-screenshots' $taskOutput
    if ($LASTEXITCODE) { throw 'Capture retrieval failed.' }
} finally {
    & $adb -s $Serial shell wm size '360x800'
    & $adb -s $Serial shell wm density 160
    & $adb -s $Serial shell settings put system font_scale 1
}
