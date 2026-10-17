#!/usr/bin/env bash
set -euo pipefail
serial="${ANDROID_SERIAL:-emulator-5554}"
adb_cmd() { adb -s "$serial" "$@"; }
[[ "$(adb_cmd shell getprop ro.boot.qemu | tr -d '\r')" == 1 ]] || { echo 'Wear emulator required'; exit 1; }
adb_cmd shell pm list features | grep android.hardware.type.watch >/dev/null
mkdir -p build/d10-05/logs build/d10-05/wear-captures
size="$(adb_cmd shell wm size | tr -d '\r')"
density="$(adb_cmd shell wm density | tr -d '\r')"
[[ "$size" != *'Override size:'* && "$density" != *'Override density:'* ]] || { echo 'Use a physical round AVD without wm overrides'; exit 1; }
case "$size" in *384x384*) dp=192 ;; *454x454*) dp=227 ;; *) echo 'Expected physical 384 or 454 round profile'; exit 1 ;; esac
[[ "$density" == *'Physical density: 320'* ]] || { echo 'Expected profile density 320'; exit 1; }
original_font="$(adb_cmd shell settings get system font_scale | tr -d '\r')"
restore() {
  if [[ "$original_font" == null ]]; then adb_cmd shell settings delete system font_scale
  else adb_cmd shell settings put system font_scale "${original_font:-1.0}"; fi
}
trap restore EXIT
for font in 1.0 2.0; do
  adb_cmd shell am force-stop dev.agenticscheduler.wear
  adb_cmd shell settings put system font_scale "$font"
  sleep 2
  log="build/d10-05/logs/wear-${dp}-font${font}.txt"
  adb_cmd shell am instrument -w -r -e class dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#actualWearD10FinalScreenshotCandidates \
    -e captureWidth "$dp" -e captureFont "$font" dev.agenticscheduler.wear.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  grep -q 'OK (1 test)' "$log"
  if [[ "$font" == 2.0 ]]; then
    checks='dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#todayAgentStatusRetryAndOverflowAreReachableAndPassive,dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#exactConfirmationBackThemeAndRecompositionAreNotDecisions'
    scaled="build/d10-05/logs/scaled-actions-${dp}.txt"
    adb_cmd shell am instrument -w -r -e class "$checks" dev.agenticscheduler.wear.test/androidx.test.runner.AndroidJUnitRunner | tee "$scaled"
    grep -q 'OK (2 tests)' "$scaled"
  fi
done
adb_cmd pull /sdcard/Android/data/dev.agenticscheduler.wear/files/d10-05-screenshots build/d10-05/wear-captures/
adb_cmd logcat -d -s D1005Evidence:I > "build/d10-05/platform-evidence-${dp}.txt"
