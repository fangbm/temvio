# D9-03-02 capability acceptance record

Status: IMPLEMENTED / AWAITING REVIEW; not full D9-03 or physical STT acceptance.
Task/contract: [D9_03_02_WEAR_CAPABILITY_NETWORK_STT.md](tasks/D9_03_02_WEAR_CAPABILITY_NETWORK_STT.md).
Baseline `37b6759b1df88a3c6c4d2f55c2a7f6d4f8c717de` (merged PR #26).

## Implementation boundary

Wear-local typed capability/readiness, OFF default preference, exact existing
Provider binding/install/secure-store composition, default network observer,
bounded existing synthetic structured probe and optional on-device STT candidate
lifecycle. No DB migration: Room v16 unchanged. No implementation changes to
Domain, D7, V3, server, crypto, SyncTransportWorker or credential provisioning.
No command/Tool/confirmation UI or automatic Agent replay. Manual composition
is ready for the separate D9-03-03 runtime slice, which is not started here.

## Evidence

Local Windows/JDK17 via ASCII checkout path:

```text
gradlew.bat :apps:wear:testDebugUnitTest --no-configuration-cache --console=plain
gradlew.bat :apps:wear:lintDebug :apps:wear:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=dev.agenticscheduler.wear.WearCapabilityInstrumentedTest
  --no-configuration-cache --console=plain
```

JVM: 50/50, zero failures/errors/skips: readiness 14, binding/secure-store 8,
probe 12, STT adapter 12, network projection 2, synthetic body privacy 2.
Debug compile/APK/instrumentation APK and lint passed. Lint warnings concern
existing backup/icon/Wear activity configuration; no new error/baseline suppression.

Native Android API35 phone AVD `temvio-d90205-ascii`: 6/6, zero failures/errors/skips.
Actual default route INTERNET=true, VALIDATED=true. Native on-device service
exists; actual `en-US` support callback returned AVAILABLE. Explicit native
start/cancel returned Cancelled, no candidate/Agent submission. Permission
NOT_REQUESTED→DENIED mapping, real grant observation and passive zero requests
verified. This is phone emulator adapter/recognizer evidence, not a physical Watch
or successful spoken transcript. The fake matrix separately covers unavailable
API30/service, unsupported language, revocation, stale callbacks and errors.

Full CI and Wear API35-ext15 evidence will be linked after the Draft PR run.

## Decisions and remaining gates

C6/C7 are frozen and used unchanged. Local implementation choices: display-only
reason precedence, SharedPreferences for durable Watch-only preference/exact
credential-free approval, explicit lifecycle composition and generation guards.
No new wire/Provider model, OS fallback or schema semantics. OD-012 remains OPEN.
