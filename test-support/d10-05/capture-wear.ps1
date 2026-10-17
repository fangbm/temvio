param([string]$Serial='emulator-5556',[string]$Sdk='C:\ProgramData\Android')
$ErrorActionPreference='Stop'
$adb=Join-Path $Sdk 'platform-tools\adb.exe'
function Adb { & $adb -s $Serial @args; if($LASTEXITCODE -ne 0){throw "adb failed: $args"} }
if((Adb shell getprop ro.boot.qemu).Trim() -ne '1'){throw 'Wear emulator required'}
if(-not ((Adb shell pm list features) -match 'android.hardware.type.watch')){throw 'Watch feature required'}
New-Item -ItemType Directory -Force build/d10-05/logs,build/d10-05/wear-captures | Out-Null
$size=(Adb shell wm size) -join "`n"
$density=(Adb shell wm density) -join "`n"
if($size -match 'Override size:' -or $density -match 'Override density:'){throw 'Use a physical round AVD profile without wm overrides'}
$dp=if($size -match '384x384'){192}elseif($size -match '454x454'){227}else{throw 'Expected physical 384 or 454 round profile'}
if($density -notmatch 'Physical density: 320'){throw 'Expected profile density 320'}
$originalFont=(Adb shell settings get system font_scale).Trim()
try {
    foreach($spec in @(@($dp,0,'1.0'),@($dp,0,'2.0'))){
        Adb shell am force-stop dev.agenticscheduler.wear
        Adb shell settings put system font_scale $spec[2]
        Start-Sleep -Seconds 2
        $log=Adb shell am instrument -w -r -e class 'dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#actualWearD10FinalScreenshotCandidates' -e captureWidth $spec[0] -e captureFont $spec[2] dev.agenticscheduler.wear.test/androidx.test.runner.AndroidJUnitRunner
        $log | Set-Content -Encoding utf8 "build/d10-05/logs/wear-$($spec[0])-font$($spec[2]).txt"
        if(-not ($log -match 'OK \(1 test\)')){throw ($log -join "`n")}
        if($spec[2] -eq '2.0') {
            $checks='dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#todayAgentStatusRetryAndOverflowAreReachableAndPassive,dev.agenticscheduler.wear.WearFinalUxInstrumentedTest#exactConfirmationBackThemeAndRecompositionAreNotDecisions'
            $scaled=Adb shell am instrument -w -r -e class $checks dev.agenticscheduler.wear.test/androidx.test.runner.AndroidJUnitRunner
            $scaled | Set-Content -Encoding utf8 "build/d10-05/logs/scaled-actions-$($spec[0]).txt"
            if(-not ($scaled -match 'OK \(2 tests\)')){throw ($scaled -join "`n")}
        }
    }
    Adb pull /sdcard/Android/data/dev.agenticscheduler.wear/files/d10-05-screenshots build/d10-05/wear-captures/
    Adb logcat -d -s D1005Evidence:I | Set-Content -Encoding utf8 "build/d10-05/platform-evidence-$dp.txt"
} finally {
    if($originalFont -eq 'null'){Adb shell settings delete system font_scale}else{Adb shell settings put system font_scale $originalFont}
}
