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
battery="$(adb_cmd shell dumpsys battery | tr -d '\r')"
case "$(printf '%s\n' "$battery" | sed -n 's/^[[:space:]]*AC powered: //p')" in
  true) original_ac=on ;; false) original_ac=off ;; *) echo 'Cannot snapshot emulator charger state'; exit 1 ;; esac
case "$(printf '%s\n' "$battery" | sed -n 's/^[[:space:]]*status: //p')" in
  1) original_status=unknown ;; 2) original_status=charging ;; 3) original_status=discharging ;;
  4) original_status=not-charging ;; 5) original_status=full ;; *) echo 'Cannot snapshot emulator battery status'; exit 1 ;; esac
restore() {
  status=$?
  if [[ "$status" != 0 ]]; then
    adb_cmd shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' > "build/d10-05/logs/failed-activity-${dp}.txt" || true
    adb_cmd logcat -d -s ActivityTaskManager:I > "build/d10-05/logs/failed-lifecycle-${dp}.txt" || true
  fi
  if [[ "$original_font" == null ]]; then adb_cmd shell settings delete system font_scale
  else adb_cmd shell settings put system font_scale "${original_font:-1.0}"; fi
  adb_cmd emu power status "$original_status"
  adb_cmd emu power ac "$original_ac"
  exit "$status"
}
trap restore EXIT
# Keep the real AVD off its virtual charger so its system charging UI cannot cover the fixture.
adb_cmd emu power ac off
adb_cmd emu power status discharging
adb_cmd shell input keyevent KEYCODE_WAKEUP
drained=false
for _ in $(seq 1 20); do
  top="$(adb_cmd shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' || true)"
  if [[ -n "$top" && ! "$top" =~ Charging(Container)?Activity ]]; then drained=true; break; fi
  sleep 1
done
[[ "$drained" == true ]] || { echo 'Wear charging overlay did not leave the foreground'; exit 1; }
for font in 1.0 2.0; do
  adb_cmd shell am force-stop dev.agenticscheduler.wear
  adb_cmd shell settings put system font_scale "$font"
  sleep 2
  log="build/d10-05/logs/wear-${dp}-font${font}.txt"
  adb_cmd shell am instrument -w -r -e class dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#actualWearD10FinalScreenshotCandidates \
    -e captureWidth "$dp" -e captureFont "$font" dev.agenticscheduler.wear.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  grep -q 'OK (1 test)' "$log"
  if [[ "$font" == 2.0 ]]; then
    checks='dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#todayAgentStatusRetryAndOverflowAreReachableAndPassive,dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#exactConfirmationBackThemeAndRecompositionAreNotDecisions,dev.agenticscheduler.wear.WearCredentialSetupInstrumentedTest#sourceRequestUnconfirmedRetryAndFetchAreOnlyExplicit,dev.agenticscheduler.wear.WearCredentialSetupInstrumentedTest#separateSendingCodeAndExplicitInstallAndAckRetry,dev.agenticscheduler.wear.WearCredentialSetupInstrumentedTest#recoveryAndInstalledCopyHaveReachableSafeActionsWithoutSecretFields'
    scaled="build/d10-05/logs/scaled-actions-${dp}.txt"
    adb_cmd shell am instrument -w -r -e class "$checks" dev.agenticscheduler.wear.test/androidx.test.runner.AndroidJUnitRunner | tee "$scaled"
    grep -q 'OK (5 tests)' "$scaled"
  fi
done
adb_cmd pull /sdcard/Android/data/dev.agenticscheduler.wear/files/d10-05-screenshots build/d10-05/wear-captures/
adb_cmd logcat -d -s D1005Evidence:I > "build/d10-05/platform-evidence-${dp}.txt"
