# D9-03-02 capability acceptance record

Status: **COMPLETE / ACCEPTED / MERGED** — [PR #27](https://github.com/fangbm/temvio/pull/27), merge `dcd3e3c04e5eef622f3cef6a5e4d8eda365a5af5`. This slice does not claim physical STT acceptance; full D9-03 subsequently completed through PR #28.
Task/contract: [D9_03_02_WEAR_CAPABILITY_NETWORK_STT.md](tasks/D9_03_02_WEAR_CAPABILITY_NETWORK_STT.md).
Baseline `37b6759b1df88a3c6c4d2f55c2a7f6d4f8c717de` (merged PR #26).

## Implementation boundary

Wear-local typed capability/readiness, OFF default preference, exact existing
Provider binding/install/secure-store composition, default network observer,
bounded existing synthetic structured probe and optional on-device STT candidate
lifecycle. No DB migration: Room v16 unchanged. No implementation changes to
Domain, D7, V3, server, crypto, SyncTransportWorker or credential provisioning.
No command/Tool/confirmation UI or automatic Agent replay. Manual composition
was prepared for the separate D9-03-03 runtime slice without implementing it
in this slice. D9-03-03 subsequently completed; see the
[Wear acceptance record](D9_03_03_WEAR_AGENT_ACCEPTANCE_RECORD.md).

## Evidence

Local Windows/JDK17 via ASCII checkout path:

```text
gradlew.bat :apps:wear:testDebugUnitTest --no-configuration-cache --console=plain
gradlew.bat :shared:agent:desktopTest --tests '*OpenAiCompatibleProviderTest'
  --no-configuration-cache --console=plain
gradlew.bat :apps:wear:lintDebug :apps:wear:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=dev.agenticscheduler.wear.WearCapabilityInstrumentedTest
  --no-configuration-cache --console=plain
```

JVM: 53/53, zero failures/errors/skips: readiness 14, binding/secure-store 10,
probe 12, STT adapter 13, network projection 2, synthetic body privacy 2.
Existing shared Agent Provider adapter regression: 9/9, zero failures/errors/skips.
Debug compile/APK/instrumentation APK and lint passed. Lint warnings concern
existing backup/icon/Wear activity configuration; no new error/baseline suppression.

Native Android API35 phone AVD `temvio-d90205-ascii`: 7/7, zero failures/errors/skips.
Actual default route INTERNET=true, VALIDATED=true. Native on-device service
exists; earlier actual `en-US` support callbacks returned AVAILABLE; the latest
run did not receive its support callback within 5 seconds and truthfully retained
LANGUAGE_UNVERIFIED. Explicit native
start/cancel returned Cancelled, no candidate/Agent submission. Permission
NOT_REQUESTED→DENIED mapping, real grant observation and passive zero requests
verified. This is phone emulator adapter/recognizer evidence, not a physical Watch
or successful spoken transcript. The fake matrix separately covers unavailable
API30/service, unsupported language, revocation, stale callbacks and errors.

Native Android cleartext policy regression: without the manifest allowance the
new loopback test failed its actual OS-policy assertion; with the allowance it
completed a real Android-engine synthetic structured HTTP probe. HTTP with a
credential reference was rejected before any secret read, and no Authorization
header was sent to the fixture. This implements the existing C5/AGT-007 explicit
credential-free HTTP exception, with public/credentialed HTTPS and D8 HTTPS checks
unchanged. Actual AVD route remained INTERNET + VALIDATED; deterministic route
facts cover the local exception without public validation. No physical LAN/no-
public-Internet or spoken transcript success is claimed.

### Full CI / native platform evidence

Implementation head `86744ee1d9d568d578a6302aeb3cc44f6a981acb`:
[CI run 37202711903](https://github.com/fangbm/temvio/actions/runs/37202711903),
all five jobs passed: build, desktop-windows, android-keystore, wear-keystore,
agent-history-platform-e2e. Downloaded result XML confirms the six Wear JVM
suites total 53/53 with zero failures/errors/skips.

| Platform / artifact | New capability tests | Actual STT evidence |
| --- | --- | --- |
| Android API35 `google_apis` phone AVD; `provider-credential-android-reports` | 7/7, zero failures/errors/skips; full Wear-app instrumentation 11/11 | On-device service exists; this run's real `en-US` query returned LANGUAGE_UNVERIFIED. Explicit native start/cancel returned Cancelled. FEATURE_WATCH=false, so this is adapter evidence on Android, not Watch capability acceptance. |
| Wear API35, system image API35-ext15 `android-wear`; `provider-credential-wear-reports` | 7/7, zero failures/errors/skips; full Wear instrumentation 11/11 | FEATURE_WATCH=true, text supported, on-device service absent: UNSUPPORTED and explicit ServiceUnavailable. Permission grant does not fabricate language/service support. |

Both AVDs report actual INTERNET + VALIDATED default route and complete the native
credential-free loopback HTTP synthetic probe. Their non-secret capability logs
are included as `d90302-platform-evidence.txt`. Native recognizer absence is an
unavailable-platform observation; deterministic adapter tests cover the remaining
mapping/lifecycle matrix. No physical Watch/spoken-transcript success is claimed.
The existing enrolled Desktop/Android/PostgreSQL relay-restart acceptance job also
passed; this slice does not change or reclassify D9-02/OD-012 semantics.

The final acceptance-document commit retains this tested implementation evidence;
the PR checks show the resulting documentation head's separate full CI run.

## Decisions and remaining gates

C6/C7 are frozen and used unchanged. Local implementation choices: display-only
reason precedence, SharedPreferences for durable Watch-only preference/exact
credential-free approval, explicit lifecycle composition and generation guards.
No new wire/Provider model, OS fallback or schema semantics. OD-012 remains OPEN.
No new BLOCKED_BY_DECISION item in this slice. Missing on-device service or
unproven language is an observed capability limitation, not permission to fall
back to remote speech. D9-03-03 and physical spoken-transcript acceptance remain
outside this slice.

## Changed files

```text
.github/workflows/ci.yml
apps/wear/build.gradle.kts
apps/wear/src/main/AndroidManifest.xml
apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/AndroidWearSpeechPlatform.kt
apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearCapabilityService.kt
apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearDefaultNetworkState.kt
apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearNetworkObserver.kt
apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearProviderBindingSource.kt
apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearProviderProbe.kt
apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearReadiness.kt
apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearReadinessComposition.kt
apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearCapabilityServiceTest.kt
apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearDefaultNetworkStateTest.kt
apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearProbePrivacyTest.kt
apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearProviderBindingSourceTest.kt
apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearProviderProbeTest.kt
apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearReadinessTest.kt
apps/wear/src/androidTest/kotlin/dev/agenticscheduler/wear/WearCapabilityInstrumentedTest.kt
docs/tasks/D9_03_02_WEAR_CAPABILITY_NETWORK_STT.md
docs/D9_03_02_CAPABILITY_ACCEPTANCE_RECORD.md
docs/ROADMAP_D5_D9.md
gradle/libs.versions.toml
```
