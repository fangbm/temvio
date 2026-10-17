# D10-05 — Wear Final UX

Status: **IN PROGRESS / DRAFT**. Target credential provisioning has the FOUNDATION_GAP below.
Authoritative baseline: `93a2aab9714a729ed2f53e60e0077372a237cbf7`, accepted D10-04 / merged PR #37.
Branch: `feature/d10-05-wear-final-ux`; target `feature/d9-02-agent-sync`.
Keep Draft; do not merge or start D10-06.

## Authority and architecture

The maintainer's D10-05 task, [D10-00 freeze](D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md),
[D9-03 freeze](D9_03_00_WEAR_AGENT_PROVIDER_FREEZE.md),
[provisioning](D9_03_01_PROVIDER_CREDENTIAL_PROVISIONING.md),
[capability](D9_03_02_WEAR_CAPABILITY_NETWORK_STT.md),
[runtime](D9_03_03_WEAR_AGENT_RUNTIME.md), [accepted runtime evidence](../D9_03_03_WEAR_AGENT_ACCEPTANCE_RECORD.md),
[Agent decisions](../AGENT_DECISIONS.md), [security decisions](../SYNC_SECURITY_DECISIONS.md),
[invariants](../DOMAIN_INVARIANTS.md), [ownership](../MODULE_OWNERSHIP.md),
[implementation contract](../IMPLEMENTATION_CONTRACT.md), [architecture](../ARCHITECTURE_DIAGRAMS.md),
[vocabulary](../UBIQUITOUS_LANGUAGE.md) and [coding policy](../CODING_AGENT_POLICY.md) govern this slice.
Historical D9/D10 slice exclusions are not the active task scope.

`WearMainActivity` keeps infrastructure/lifecycle composition. App-local `WearNavigation`
owns Today → Agent / Status and a transient exact pending-call confirmation overlay.
Status returns to its actual origin; Agent Back returns to Today. Native Back/swipe
only closes presentation and cancels foreground request/speech through existing APIs.
It never sends, confirms, denies, retries or rolls back a committed business change.

No new engine, framework, Tool, persistence, Provider or Sync abstraction. The only
module dependency addition is the already-approved canonical presentation module
`:shared:ui`, for palette/spacing/target roles. No new third-party dependency or
version/SDK change. Wear renders existing pinned Wear Material3/foundation controls.

## Current-state audit

Baseline `WearMainActivity` mixed a flattened Today string and Agent switch; the
Agent page led with raw enums, booleans, UUID, endpoint, normalized JSON and speech
setup. Confirmation lived at the end of the same long stream. Existing runtime,
readiness, permission, secure-store and D7/D9 boundaries are retained verbatim.

## Today / Upcoming

Existing `ConflictAwareSourceFactReadService` supplies an explicit seven-calendar-day
viewport in the platform display timezone. Presentation preserves projection order,
puts today's items first and shows at most three rows plus a remaining-count notice.
No lifetime list, UI recurrence/overlap computation or new business mutations.
Event/Focus/Course/Exam labels and Zoned/AllDay/Floating/DateOnly formatting remain
distinct. AllDay keeps an exclusive date end; Floating never receives an invented zone.
Calendar overlap, D8 business Sync conflict and projection/read failure are separate
textual facts. Sync stopped shows its existing redacted reason and explicit retry
delegates to the existing trigger. No failure-free display claims full catch-up.

## Agent and confirmation

The recent real thread title is displayed, or New/Current conversation; no primary
UUID. The existing session owns draft/busy/request lease/history. Only explicit Send
submits. Preference, navigation, readiness, STT, theme and foreground changes do not.
Conversation displays recent user/assistant messages, bounded recorded Tool states,
typed results and whether actual mutation links exist. Raw Tool messages/preview/
result JSON and ID arrays are not the normal UI. Earlier authoritative history is
retained; rendering bounds do not delete or compact it.

The dedicated confirmation page captures the exact persisted call and `previewJson`.
Readable normalized before/after/proposed values remain scrollable, followed by
Confirm/Deny/Review later. Confirm/Deny passes original call ID and preview to
`confirmFromUserAction`; its existing identity/current-state checks remain authority.
Back/review later keeps pending truth; return/restart reuses it without a Provider
proposal. Double taps remain protected by the existing mutex/runtime. A successful
committed Tool result remains success even if subsequent Provider continuation fails.

WatchPermissionPolicy is unchanged: reads/previews may be direct, supported writes
at most confirmation, bulk/destructive/external denied. No remote/voice approval.

## Status / Setup, readiness and speech

Status remains accessible when preference is OFF. Explicit enable/disable calls the
existing device-local preference API (default OFF). All C6 formulas and independent
facts remain unchanged. Human copy maps every runtime reason; binding, credential,
network and entry facts are shown without raw booleans as product labels.
Existing exact local Provider selection/credential-free route approval remains;
no host/model/route default is synthesized. Endpoint/model details do not lead Agent.
Non-loopback HTTP clearly warns: **Requests may travel unencrypted over HTTP.**
Credentialed HTTP rejection, redirects disabled, secure-store and ownership checks
remain unchanged. No secret, SecretRef, HPKE key or envelope is displayed or included in captures.

Unsupported structured Tools displays **Chat only. Actions are unavailable with this
Provider.** Existing request readiness permits ordinary Send with zero Tool schemas.
Auth/config/network/credential/install failures remain blocking. Unsupported remains
terminal for automatic probing; explicit retry only refreshes existing readiness.

Speech configuration is in Status. `und` means no explicit language, never a system
locale or English fallback. Language input remains an explicit BCP-47 tag. Passive
Agent/Status entry requests no mic permission. Only a speech action calls the existing
on-device service and permission boundary. Candidate → editable session draft, never
Send/Confirm. Permission/service/language states stay distinct from text capability.
No cloud/Phone speech, model download, background audio or Agent replay.

## Round layout, theme and interaction

Real operation follows platform/system theme; fixtures explicitly select Light/Dark.
Canonical semantic colors map onto Wear Material3, not Android Material3 screen UI.
One native Wear `TransformingLazyColumn` owns scrolling and default rotary support.
An inscribed content rectangle (15% padding each edge) keeps full interactive bounds
inside both round faces. Controls retain >=48dp targets; scaling wraps/scrolls rather
than shrinking actions. Headings, editable fields, state text and labeled actions
provide semantics; no color-only result. No decorative animation or new gesture engine.

Platform API references: [Wear foundation](https://developer.android.com/reference/kotlin/androidx/wear/compose/foundation/package-summary),
[ScreenScaffold](https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/ScreenScaffold.composable).
Pinned local 1.6.2 API/actual emulator execution, rather than latest docs alone,
determine compatibility. These references do not prove physical hardware acceptance.

## FOUNDATION_GAP — Watch target provisioning orchestration

**User interaction:** choose/approve exact Watch-local credentialed binding and source,
request delivery, review independent SAS, explicitly approve/install and recover safely
after navigation/restart; see honest pending/installation result.

**Existing API inspected:**
`ProviderCredentialProvisioningService.reserveForExplicitUser`, `compare`,
`approveComparisonByExplicitLocalUser`, `installApproved`, `recover`;
`ProviderCredentialTransport.publish/fetch/acknowledge` and
`KtorProviderCredentialTransport`; `ProviderCredentialProvisioningRepository`;
`WearAgentRuntimeComposition` and `WearProviderBindingSource`.

**Why insufficient:** the install service consumes envelope/private-key-backed
composition and exposes protocol comparison objects; transport supplies separate
mailbox primitives. The repository has the durable revision/journal facts, but no
reviewed Watch target interaction facade connects current enrollment/selected source,
request publication, fetched comparison, explicit user approval, install/recovery and
informational ACK into sanitized resumable presentation state. Source delivery has
its own service; it is not a target coordinator. Implementing that in Compose would
duplicate protocol/recovery orchestration. The existing Agent composition only selects
already-existing configs and observes binding readiness; it does not own provisioning.

**Minimal requested foundation (proposal, not implemented):** a separately reviewed
Watch target application/platform coordinator over those existing primitives, with
typed sanitized state and explicit reserve/refresh/approve/install/recover intents,
proper enrollment/ACTIVE-directory/config ownership and cancellation/restart behavior.
UI receives necessary comparison digits/status only, no secret/private key/envelope
or SecretRef. No wire/HPKE/revision/approval/ACK/retention semantics change is requested.

This path is visibly unavailable, not fake setup success. Independent Wear UX proceeds.
No source-device provisioning UI or new protocol path is implemented.

## Verification and evidence

Local final verification (2026-10-18): **82/82 Wear JVM tests, 46/46 actual
Wear instrumentation tests, compile/APK packaging and lint PASS**. The JVM set
contains 22 new presentation cases and all 60 prior cases. The native set retains
all 20 prior Agent runtime E2E cases and adds three runtime/navigation cases;
23 runtime + 3 retained UI + 7 C6/C7 + 1 Keystore + 3 credential slot + 9 final-UX
cases = 46. No skipped or failed case counts as passing.

Local evidence logs: `D:\codex\d10-05-final-synchronized-build.log` and
`D:\codex\d10-05-final-native.log` (Small Round: `OK (46 tests)`, 87.216 seconds); final Large Round rerun: `D:\codex\d10-05-accepted-native.log` (`OK (46 tests)`, 91.907 seconds).
Build used JDK 17 and the existing isolated Gradle cache; no toolchain change.
A prior repeated native run found the existing microphone test's initial-denied
precondition was polluted by its own earlier granted-permission case. Final run
reset the emulator permission before the full suite; no old assertion was weakened.
A transient local lint analysis failure during overlapping builds was resolved by
one stable sequential build; final lint passed without suppression.
An extra Large Round suite during simultaneous emulator work reported one Back
injection timeout (45/46). Its isolated rerun passed; the new test now fences the
confirmation page and native queue before injecting Back, retaining every pending/
no-write assertion. Final serial Large Round suite passed 46/46; the failed attempt is not counted
as acceptance.

The local runner installs the final debug/test APKs and directly invokes the real
AndroidJUnitRunner on `emulator-5556`; CI continues using Gradle connected tests.
No fake platform replaces the retained real runtime or secure-store tests.

Commands actually run locally (CI connected runner shown separately):

```text
gradlew :apps:wear:assembleDebug :apps:wear:assembleDebugAndroidTest :apps:wear:testDebugUnitTest :apps:wear:lintDebug --no-daemon --no-configuration-cache --console=plain --gradle-user-home D:\codex\asp-d10-gradle-home
adb -s emulator-5556 install -r apps/wear/build/outputs/apk/debug/wear-debug.apk
adb -s emulator-5556 install -r apps/wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
adb -s emulator-5556 shell pm revoke dev.agenticscheduler.wear android.permission.RECORD_AUDIO
adb -s emulator-5556 shell am instrument -w -r dev.agenticscheduler.wear.test/androidx.test.runner.AndroidJUnitRunner
# CI: gradlew :apps:wear:connectedDebugAndroidTest --no-daemon
test-support/d10-05/capture-wear.ps1 (Windows isolated Wear emulator)
bash test-support/d10-05/capture-wear.sh (Wear CI emulator)
python test-support/d10-05/record-candidates.py
python test-support/d10-05/check-boundaries.py
python test-support/d10-05/check-candidates.py
git diff --check
```

Preserve all prior D9-03 Watch runtime/security/capability tests, adding presentation
navigation, exact confirmation/back/theme, speech passive actions, 192/227 round bounds,
Light/Dark/scaled text and actual renderer fixtures. Real runtime E2E uses production
AgentRunService/Tools/Room/Android HTTP with deterministic local structured responses;
presentation fakes supplement it, never replace execution proof.

Only `docs/tasks/fixtures/d10-05/screenshots` is new screenshot evidence. Record AVD,
API, dimensions, density, font scale, source/PNG hashes and captured state. Historical
D10-01/02/03/04 screenshots/manifests remain unchanged. Candidate images require review.

The PR check suite/run linked in the PR is the authoritative final exact-head CI
evidence; all five jobs must actually execute and pass: build,
desktop-windows, android-keystore, wear-keystore, agent-history-platform-e2e.
Wear job retains real FEATURE_WATCH instrumentation, uses a round profile and uploads
new captures/platform logs. Connected-test UTP uninstalls the fixture APKs at the
end of the suite, so the capture steps explicitly reinstall both built APKs before
invoking the native runner. First CI trial passed 46/46 native XML but failed capture
with missing instrumentation; the workflow-only correction preserves the test suite.
A second real Large Round AVD supplies the 227dp
physical display; the Small Round AVD supplies 192dp. Scripts reject `wm size` or
density overrides, preserve user font scale, and repeat Today/Retry/Confirm/Deny
interaction checks at 200% on both. No cancelled/unallocated job counts.

30 renderer candidates cover normal Light/Dark states at both sizes plus 200%
Confirm/Enable at each size. A first local sizing trial using `wm size` preserved
the small panel mask; those trial images are discarded and are not acceptance
for 227dp. Final evidence uses two actual physical profiles. Screenshot capture
settles real compositor/theme frames in addition to Compose idleness; this avoids
capturing a prior or blank surface immediately after configuration changes.

## Exclusions / decisions

LOCAL_REVERSIBLE: app-local names, concise English copy, three visible schedule rows,
recent transcript rendering bounds, inscribed padding and route layout.
No semantic blocker/decision was selected. Target provisioning FOUNDATION_GAP is above.
No Domain/Application/Planner/D7/D8/D9 semantic change, Tool/permission change, schema,
migration, wire/crypto/server change, Phone bridge, nearby provisioning, new context
authority, conflict resolution or production-sensitive V3 enablement. OD-012 OPEN;
FG-02/03/04 remain DEFERRED / NON-BLOCKING. D10-06 is unstarted.
Actual C6/C7 platform evidence on the API35 Wear image: default route reports
INTERNET + VALIDATED; on-device recognizer is absent (`onDeviceService=false`),
capability remains text-supported with STT UNSUPPORTED. The granted-permission
path and explicit attempt/cancel return ServiceUnavailable without Agent submission.
This proves the unavailable path, not successful physical speech recognition.
Both physical profile sizes pass 200% Today/Agent/Retry/Confirm/Deny interaction
checks (2/2 each, in addition to four successful capture-method executions).

Evidence is Wear emulator acceptance only; no physical Watch, live external Provider
or actual on-device speech recognition success is claimed without measurement.
