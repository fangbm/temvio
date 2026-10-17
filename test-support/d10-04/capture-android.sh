#!/usr/bin/env bash
set -euo pipefail
# Actual Compose/native capture, isolated synthetic emulator; no historical rewrite.
output=build/d10-04/android-captures
mkdir -p "$output"
trap 'adb shell wm size 360x800; adb shell wm density 160; adb shell settings put system font_scale 1' EXIT
for variant in '360 800 1' '480 900 1' '600 960 1' '840 900 1' '1024 768 1' '800 360 1' '360 800 2'; do
  read -r width height font <<< "$variant"
  adb shell wm size "${width}x${height}"
  adb shell wm density 160
  adb shell settings put system font_scale "$font"
  result="$output/${width}x${height}-font${font}.txt"
  adb shell am instrument -w -r -e captureWidth "$width" -e captureHeight "$height" -e captureFont "$font" -e class 'dev.agenticscheduler.android.AgentWorkspaceInstrumentedTest#actualAndroidD10AgentScreenshotCandidates' 'dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner' | tee "$result"
  grep -q 'OK (1 test)' "$result"
done
adb pull /sdcard/Android/data/dev.agenticscheduler.android/files/d10-04-screenshots "$output"
