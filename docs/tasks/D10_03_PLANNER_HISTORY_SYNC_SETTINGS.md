# D10-03 — Planner / History / Sync / Settings

Status: **IN PROGRESS / FOUNDATION_GAP**. Baseline: `cda86a815d21fe4a501b79a92074962001a54039`
(accepted D10-02 / PR #33); D10-02F `60d2670f063357177a8e222db7ccbcc679bc0d92`
is already authoritative. Branch: `feature/d10-03-planner-history-sync-settings`.
Target: `feature/d9-02-agent-sync`. Keep Draft; do not merge or start D10-04/05/06.

## Authority and scope

Current maintainer task governs this slice alongside
[D10-00 freeze](D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md),
[D10-01](D10_01_DESIGN_SYSTEM_APP_SHELL.md),
[D10-02](D10_02_TODAY_CALENDAR_TASKS_ACADEMIC.md),
[D10-02F](D10_02F_ROOM_AGGREGATE_SNAPSHOT_FIX.md),
[Planner decisions](../PLANNER_DECISIONS.md),
[History decisions](../HISTORY_SYNC_DECISIONS.md), and
[Sync/security decisions](../SYNC_SECURITY_DECISIONS.md).

Desktop/Android final Planner, History/Undo, business Sync and Settings consume
existing typed Application services through app-owned screen coordinators.
Existing primary selection, canonical Light/Dark design, responsive shells and
D10-02 scheduling surfaces remain. No Domain/Planner/D7/D8/D9 semantic change,
schema/migration, persisted PlanBranch, wire/crypto/server change, new Tool or
security bypass. OD-012 remains OPEN. No Wear redesign or D10-04+ implementation.

## Application audit and foundation gaps

### FG-01 — ordinary PlanningProfile optimistic Save

FOUNDATION_GAP.

User interaction: save an edited profile without overwriting concurrent changes.
Existing API inspected: `PlanningProfileSettingsService.createUnconfigured`,
`previewSave`, `save`, `saveAgent`; `PlanningProfileSettingsResult`.
Why insufficient: ordinary `save(profile)` unconditionally rebases on the current
row and does not compare the user's before-image inside the write transaction.
Only `saveAgent` accepts `expectedBefore`, and its Agent origin/authorization is
inappropriate for ordinary Settings. A UI preflight read cannot close that race.
Minimal requested foundation: a separately reviewed ordinary-user Save overload
with exact expected-before checked inside MutationCoordinator, preserving typed
Success/Stale/NotFound/BlockedBySyncConflict and D7 User origin. No new schema,
mutation vocabulary, default or Undo support.
Until authorized, configuration drafts cannot use unsafe Save or Agent Save.
Explicit Unconfigured creation and existing profile selection remain independent.

### FG-02 — typed business conflict candidate selection

FOUNDATION_GAP.

User interaction: select/combine candidate semantic values to resolve a D8 conflict.
Existing API inspected: `SyncConflictQueryService.listOpen/get`,
`SyncConflictResolutionService.resolve(conflictId, List<EntityMutation>)`,
`SyncConflictParticipant.candidateValuesJson`.
Why insufficient: candidate values are opaque JSON; there is no reviewed typed
Application read/selection boundary that constructs exact safe resolution input.
UI must not decode the sync/local journal codec or overwrite repositories.
Minimal requested foundation: separately reviewed Application typed candidate
read/selection model that feeds the existing resolution service and preserves
its target/group/current-component validation. Read-only conflict metadata remains
available; no fake Resolve/dismiss or manual status update.

### Maintainer disposition

The maintainer explicitly chose: **record FG-01/FG-02 in this PR; foundation
separately reviewed**. No ordinary profile Save/SaveAgent shortcut and no UI
candidate JSON decoding are authorized. These paths remain unavailable. The
whole D10-03 slice is not marked IMPLEMENTED or COMPLETE.

### FG-03 — complete typed pairing workflow

FOUNDATION_GAP. User interaction: request/admit or approve a new device after
explicit SAS comparison. `LocalEnrollmentRequestService` safely stages identity;
`PairingApprovalService.approve` returns a typed approved envelope;
`PairingRecipientAdmissionService.admit` consumes an authenticated envelope.
However `KtorSyncLifecycleTransport.registerEnrollment/fetchKeyPackage/approveEnrollment`
exposes raw package Base64 strings and does not provide an application-owned
end-to-end typed publication/fetch/admission workflow. This screen cannot complete
pairing without owning package encoding/decoding or partial success orchestration.
Minimal requested foundation: separately reviewed Application orchestration
joining those existing steps with typed Pending/Approved/Admitted/retry outcomes,
exact package identity and explicit SAS acknowledgement. No new pairing/crypto
semantics are proposed. Pairing remains unavailable; recovery and revocation use
the existing complete services independently.

### FG-04 — richer directional progress (optional status capability)

`ActiveSyncCatchUpTrigger` exposes terminal `stoppedReason` and explicit
`retryNow`, but no observable latest per-direction result/held queue snapshot.
The screen reports the real configured trigger and stopped reason only. It does
not derive sync progress from outbox rows or equate held outbound with blocked
inbound. A future reviewed typed read API is needed for richer progress; it is
not required to run the independently available explicit Retry path.

## Implemented independent presentation

- App-owned immutable Planner request draft: explicit selected profile, reference
  Instant, finite horizon; Local Reflow affected blocks/disrupted ranges/search
  window. Existing DogfoodPlannerService builds authoritative snapshots and
  determines all placements, legality and issues. No Provider request or write
  occurs during Preview. Read-only profile configuration and explicit
  Unconfigured creation are shared between Planner and Settings.
- Session PlanBranch, readable proposed FocusBlock changes, issues and criteria;
  wide list/preview panes and narrow/large-font modal detail. Apply delegates to
  the existing atomic applier, consumes success, retains Stale/Conflicted outcomes
  with disabled Apply, and never allows force application. Cancel discards only
  the preview. A new process starts without a branch or implicit request.
- Room-backed HistoryQueryService cursor pages (50 per page), mutation detail,
  typed readable before/after for supported product records, opt-in raw technical
  images/HLC/DVV and a bounded entity history first page. Undo capability is
  shown faithfully; one explicit confirmation invokes one existing all-or-none
  compensating command. No per-child Undo or undo matrix expansion.
- Business conflict identity/entities/groups/participants/provisional identity
  and OPEN/RESOLVED/SUPERSEDED detail remain read-only. Calendar overlaps and Agent
  history conflicts are not conflated with business conflicts.
- Existing enrollment metadata, explicit stopped Retry, V2 business compatibility
  and existing V3 conversation controls remain separate. V3 consent does not
  initiate history export. No claim of queue drain or all-device upgrade.
- Existing RecoveryEnrollmentService and RevocationRotationService are manually
  composed in each platform root. Recovery reuses durable Pending identity;
  activated enrollment reactivates the guarded runtime. Blocked startup mounts
  only restricted recovery, never business screens. Explicit revocation requires
  confirmation; uncertain submit retains the opaque prepared attempt and retries
  it exactly. Self device is excluded from revocation selection. Recovery Secret
  input is transient, masked, cleared on submission/cancel and never logged,
  saved/restored, or used in screenshot fixtures.
- Platform lifecycle scope owns explicit actions across route/activation remount;
  command state uses the UI dispatcher (Android Main / Desktop root Compose
  scope), independently of the D8 transport's Default dispatcher. Closing the
  app cancels it. No UI automatic retry, request replay, background
  security approval or new network/crypto implementation.
- Settings organizes session theme, shared profiles, Sync/Security and existing
  Provider link-out. No new durable preference or fake analytics. OD-012 stays
  OPEN and local SQLite is not represented as encrypted at rest.

## Verification and delivery

### Executed local evidence

On Windows, JDK 17, isolated Gradle home, the following command passed:

```powershell
./gradlew.bat --no-daemon --console=plain --gradle-user-home D:\codex\asp-d10-gradle-home :apps:desktop:test :shared:ui:desktopTest :shared:database:desktopTest --tests '*AggregateSnapshotConsistencyTest' --tests '*AcademicAuthoringIntegrationTest' :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest
```

- Desktop **80/80**: existing 49 plus 17 ProductWorkspacePersistence, 6
  ProductSecurityWorkflow and 8 ProductWorkspaceUi tests. Real Application/Room
  preview/apply/reflow/Undo, retained restart history, read-only conflicts and
  mounted Compose controls are exercised. Task/Academic creation remains
  Unsupported Undo. An external Application resolution leaves the OPEN query
  but retains inspectable RESOLVED identity. Theme/navigation/Provider link-out
  do not Apply a session branch or invoke Provider.
- `shared:ui` **3/3**. Targeted database **50/50**:
  AggregateSnapshotConsistency 13 and AcademicAuthoringIntegration 37. Database
  tests were actually executed with `--rerun`; the final combined invocation
  reused those unchanged task outputs. Android app/test APK assembly passed.
- Android API 35 emulator: native ProductWorkspace **6/6**; test APK installed
  and executed through `am instrument`, not inferred from assembly. Covers
  Planner explicit Apply, Back without commit, History unsupported detail,
  Settings draft/theme, Sync stopped Retry/read-only conflict and capture.
  Existing CoreScheduling/AppShell native regressions passed **9/9** on the same
  APK. Expanded More/preview Back passed separately at 1024×768; all seven capture
  configurations executed successfully.
- Corrected CI-equivalent Android/UTP invocation executed **21/21**, zero
  failures/errors/skips: Product 6, CoreScheduling 5, AppShell 4, consent/export 2,
  Android Keystore 1 and credential-slot 3. Only the real relay fixture class is
  excluded here; its dedicated enrolled-platform job remains required.
- Security tests use the real platform manual composition, Ktor lifecycle
  boundary, RecoveryEnrollmentService/RevocationRotationService and Room. HTTP
  peer and platform secret persistence are deterministic doubles. They prove
  pending-identity retry, failed activation reporting, explicit destructive
  confirmation and exact prepared-rotation retry. They do not certify physical
  Keystore/DPAPI, live relay recovery or live multi-device revocation.

Native command (use the owned test emulator serial):

```powershell
adb -s emulator-5554 shell am instrument -w -r -e class dev.agenticscheduler.android.ProductWorkspaceInstrumentedTest dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner
./test-support/d10-03/capture-android.ps1 -Serial emulator-5554 -Sdk C:\ProgramData\Android
./gradlew.bat :apps:android:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notClass=dev.agenticscheduler.android.D9PlatformRelayInstrumentedTest' --no-daemon
```

### Presentation regression and responsive evidence

The full Desktop rerun exposed an existing Academic LazyColumn interval/count
race (`IndexOutOfBoundsException: Index 5, size 5`). Its deferred structural
inputs now retain one immutable composition frame. New lazy workspaces use the
same rule. The original Academic form and refresh-barrier tests passed again;
an additional History growth/refresh/scrolled-measurement regression passed.
A gated post-commit Planner refresh also verifies root-owned UI actions survive
child remount and a busy repeated Apply produces no second commit. An injected
post-Undo read failure preserves the real committed compensation result and
consumes the submitted detail; retry cannot duplicate it. No Academic
persistence, recurrence or authoring semantics changed.

Actual Compose evidence is kept separately in the bounded **44-candidate**
[D10-03 capture manifest](fixtures/d10-03/screenshots/capture-manifest.json).
The generated matrix contains 50 Desktop and 50 Android candidates: Light/Dark,
Desktop 640/1024/1280/1440/1920 widths; Android 360/480/600/840/1024 widths,
800×360 short height, and 200% font. Wide detail panes become owned scrollable
dialogs at narrow/short/large-font sizes. Native Back closes detail before More.
Planner proposed changes and History diffs have text/list representations;
existing shared roles, 48dp actions and keyboard focus are retained.

Screenshots contain deterministic synthetic business fixtures and blank secret
fields. Revocation confirmation uses a synthetic typed device-directory fixture
with the actual dialog; it executes no rotation. Native screenshots include real
emulator chrome/clock, so their bytes are acceptance snapshots rather than a
claim of clock-independent golden rendering. Candidate hashes and normalized
source hashes are checked. Visual maintainer approval remains pending. Historical
D10-01/02 screenshot files are unchanged.

### Delivery and remaining acceptance

The first CI attempt exposed test-harness failures: an offscreen lazy Planner
input was queried before composition; the no-fixture Android job ran the
enrolled-relay-only test, which UTP classified as failure rather than skipped;
and the historical consent test's old Unconfined Compose rule resumed a frame on
Default instead of Android Main. Product instrumentation now scrolls the lazy
workspace to each input before querying it. The ordinary Android job excludes
only the fixture-dependent relay class, still required in the independent real
`agent-history-platform-e2e` job. The consent test uses the v2 Standard rule; its
assertions and production V3 controls are unchanged. No failure was relabeled
as passing, and full CI must be rerun on the corrected exact head.

[Draft PR #35](https://github.com/fangbm/temvio/pull/35) targets
`feature/d9-02-agent-sync`. Full exact-head CI must execute build,
desktop-windows, android-keystore, wear-keystore and agent-history-platform-e2e;
the accepted final run/head is reported on that PR. A local build or an older
docs-only run is not full CI acceptance for this implementation.

`git diff --check`, Markdown link/status checks, source ownership/diff fences and
candidate hash checks are required before push. No DB migration or new production
dependency. No production changes to Domain/Application/Planner/D7/D8/D9, wire, crypto,
server or Wear. OD-012 remains OPEN. D10-04/05/06 remain unstarted. FG-01/02
require separately reviewed foundation work by explicit maintainer disposition;
FG-03 pairing remains unavailable and FG-04 richer directional status is a future
typed read capability. No new `BLOCKED_BY_DECISION` choice was guessed. The whole
slice remains **IN PROGRESS / FOUNDATION_GAP**, awaiting review of the independent
paths rather than claiming semantic completion.
