#!/usr/bin/env bash
set -euo pipefail
# CI's isolated emulator only; no production data and no source-baseline writes.
output=build/d10-02/android-captures
mkdir -p "$output"
adb install -r apps/android/build/outputs/apk/debug/android-debug.apk
adb install -r apps/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk
adb shell wm size 360x800
adb shell wm density 160
adb shell settings put system font_scale 1
result="$output/instrumentation.txt"
adb shell am instrument -w -r -e class 'dev.agenticscheduler.android.CoreSchedulingInstrumentedTest#actualAndroidD10CoreScreenshotCandidates' 'dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner' | tee "$result"
grep -q 'OK (1 test)' "$result"
adb pull /sdcard/Android/data/dev.agenticscheduler.android/files/d10-02-screenshots "$output"
