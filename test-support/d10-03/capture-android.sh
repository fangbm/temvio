#!/usr/bin/env bash
set -euo pipefail
# CI isolated synthetic emulator. No historic baseline is rewritten.
output=build/d10-03/android-captures
mkdir -p "$output"
trap 'adb shell wm size 360x800; adb shell wm density 160; adb shell settings put system font_scale 1' EXIT
for variant in '360 800 1' '480 900 1' '600 960 1' '840 900 1' '1024 768 1' '800 360 1' '360 800 2'; do
  read -r width height font <<< "$variant"
  adb shell wm size "${width}x${height}"
  adb shell wm density 160
  adb shell settings put system font_scale "$font"
  result="$output/${width}x${height}-font${font}.txt"
  adb shell am instrument -w -r -e class 'dev.agenticscheduler.android.ProductWorkspaceInstrumentedTest#actualAndroidD10ProductScreenshotCandidates' 'dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner' | tee "$result"
  grep -q 'OK (1 test)' "$result"
  profile_result="$output/${width}x${height}-font${font}-profiles.txt"
  adb shell am instrument -w -r -e class 'dev.agenticscheduler.android.ProductWorkspaceInstrumentedTest#actualAndroidPlanningProfileScreenshotCandidates' 'dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner' | tee "$profile_result"
  grep -q 'OK (1 test)' "$profile_result"
done
adb shell wm size 1024x768
adb shell settings put system font_scale 1
adb shell am instrument -w -r -e class 'dev.agenticscheduler.android.ProductWorkspaceInstrumentedTest#nativeMorePlannerPreviewBackDoesNotCommitThenReturnsToMore' 'dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner' | tee "$output/expanded-back.txt"
grep -q 'OK (1 test)' "$output/expanded-back.txt"
adb pull /sdcard/Android/data/dev.agenticscheduler.android/files/d10-03-screenshots "$output"
