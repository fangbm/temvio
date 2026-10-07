# D10-02 — Today / Calendar / Tasks / Academic

Status: **IMPLEMENTED / AWAITING REVIEW**.
Current integrated baseline: `60d2670f063357177a8e222db7ccbcc679bc0d92`
(accepted D10-02F / PR #34). Original D10-01 baseline:
`19d6a0779b27ff2641ff0c9251c8017b256ba774` (PR #32).
Branch: `feature/d10-02-core-scheduling-academic`.
Normal merge/sync commit: `9a6ff734336016434fd96dd7dc7e50c371cf17bc`.
Existing D10-02 implementation history is preserved.

## Contract

The current maintainer task, [D10-00 freeze](D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md),
[D10-00A foundation](D10_00A_ACADEMIC_AUTHORING_FOUNDATION.md) and
[D10-01 foundation](D10_01_DESIGN_SYSTEM_APP_SHELL.md) govern this slice.
Implement Today, bounded Day/Week/Month, Task detail/authoring, academic setup,
Course and separate Rule authoring, and all three Exam schedule states.

Application services remain authoritative. Academic forms call
`AcademicAuthoringService` with explicit inputs and optimistic before snapshots;
`loadFacts()` is an explicit entry/refresh/post-save load, not polling.
Conflict projection is visibly provisional; Unprojectable fails closed.
No repository writes, recurrence/conflict recomputation, schema migration,
new Domain defaults, wire, crypto, Agent/Planner/Sync or Wear behavior changes.
OD-012 stays OPEN. D10-03/04/05 are not started.

## Presentation implementation choices

Android and Desktop compile reusable app-owned feature sources from
`apps/presentation`; this is source reuse, not a new Gradle module or semantic
layer. It consumes Application services. The existing platform composition roots
and typed navigation retain authority. `shared:ui` remains presentation-only.

Calendar has one finite authoritative viewport per selected mode. Week uses Monday
as a LOCAL_REVERSIBLE presentation convention, at most seven dates. Month shows
only the needed leading/trailing dates, at most 42 cells. Date membership calls
`intersectsLocalDate`; per-cell summaries are bounded with overflow opening a
date list. AllDay, DateOnly, Floating and Zoned stay distinct. Calendar overlap
and D8 source conflicts remain separate. Source selection never writes.

AcademicYear/Semester/weeks/PeriodTemplate are explicit setup. Course save commits
one Course; adding/updating a Rule is another explicit save and MutationId.
No inferred academic graph, generated weeks, default timetable, holiday/exception
authoring, deletion or new Undo. Exam is Unscheduled, DateOnly, or Exact; Exact
uses the selected Semester zone and rejects ambiguous/nonexistent wall times.

Forms require explicit Save; dirty Cancel/Back requests discard confirmation.
Stale/conflict/error results retain drafts and cannot silently overwrite.
Layout/theme/navigation never submit. Details use a readable pane when space
permits and an owned modal on narrow/short/large-font windows. Text/actions remain
scrollable, named and at least 48dp. Week/Month expose an accessible list mode.

## Acceptance evidence

D10-02 candidates and capture manifest belong under
`fixtures/d10-02/screenshots/`; D10-01 historical PNGs remain unchanged.
Actual Desktop/Android Compose rendering uses synthetic fixtures, explicit date,
zone/theme/font/viewport, never production data. Candidates are not auto-approved.
Targeted tests, actual native evidence, responsive inspection and exact-head full
CI are recorded here at delivery; none is claimed before execution.

## Foundation dependency — RESOLVED / MERGED

D10-02 originally discovered mixed parent/child Room aggregate snapshots while
saving an explicit Semester or CourseScheduleRule with the authoritative Calendar
subscription active. Separately invalidated queries could reconstruct a newly
committed parent with prior children, terminating Domain mapping despite an
atomic successful write. Initial tests reproduced Semester and Rule failures.
The original UI technical-error/explicit-refresh behavior did not solve that gap.

The maintainer authorized and accepted the separate
[D10-02F persistence correction](D10_02F_ROOM_AGGREGATE_SNAPSHOT_FIX.md):
[PR #34](https://github.com/fangbm/temvio/pull/34), merged as
`60d2670f063357177a8e222db7ccbcc679bc0d92`.
Dependency status: **RESOLVED / MERGED**.
Invalidation is trigger-only; Semester, PeriodTemplate, CourseScheduleRule and
PlanningProfile parent/child reconstruction and corresponding point reads share
one `withReadTransaction`. Persisted corruption still fails visibly.
D10-02 integrates that accepted implementation unchanged through the normal merge
above; it does not reimplement persistence or add a UI workaround.

Post-integration acceptance reruns the real Application + Room Calendar
subscription/authoring update and complete `loadFacts()` regressions, alongside
`AggregateSnapshotConsistencyTest` and `AcademicAuthoringIntegrationTest`.
Presentation mocks and an old green CI run do not close this dependency.

Remaining FOUNDATION_GAP: **none**. Remaining BLOCKED_BY_DECISION for D10-02:
**none**. OD-012 remains OPEN as an independent production gate. Final
accessibility/motion acceptance remains D10-06; later product surfaces remain
outside this slice.

## Core workflows and interaction boundaries

Today shows a compact next exact-time item, next planned FocusBlock, up to three
open/in-progress Tasks, and up to three day items with routes to the complete
views. DateOnly deadlines are compared as dates; Exact deadlines as instants.
No inferred priority, completion, scoring or automatic Agent submission.

Calendar mode, selected source and Task filter are session presentation state.
Each result is associated with its immutable viewport/reload revision, so an old
Day frame cannot be advertised as a new Week/Month result. Seven-column rows
share a horizontal scroll position on narrow windows. Each cell has at most three
summaries and an exact overflow count; date headers/overflow open the complete
accessible date list. The alternative full list uses the same projection. No
recurrence, occupied-time or Sync conflict computation is performed in UI.
CourseSession routes to its owning Course through the typed Rule ID, Exam to its
own academic editor, and FocusBlock to its Task. Event detail is re-read through
the conflict-aware Application service.

Tasks expose Open/Completed/All filters, authoritative status/effort/deadline,
linked planned FocusBlocks, and the existing Task create/update service.
Event/Task updates supply optimistic before snapshots; stale drafts remain until
explicit discard/reload. Unprojectable and technical failures are named, not
silently converted to empty source facts. Saves are single-flight. Field editing,
navigation, filter/theme changes, Cancel and Back never execute a business write.

Academic setup exposes all six reviewed families and explicitly entered ordered
week/period rows (add, remove, reorder). It never synthesizes a parent, timezone,
teaching week, schedule type or timetable. PeriodBased Rule requires an existing
PeriodTemplate; ClockTime does not. An optional Exam Course picker only exposes
Courses of the selected Semester. Deterministic Application validation remains
authoritative for whole-graph constraints. Technical/Invalid/NotFound/
AlreadyExists/Stale/BlockedBySyncConflict results retain draft state. Provisional
facts and Unprojectable are shown distinctly; ordinary Save is not resolution.

## Responsive and accessibility evidence boundaries

Desktop uses the accepted sidebar shell; detail is an owned scrollable pane when
available **content** width is at least 760dp, height at least 480dp, and font scale
below 1.6. Otherwise detail uses an owned scrollable dialog. Android retains the
accepted bottom navigation / rail / sidebar classes. An 840dp window may still
use a dialog after its sidebar consumes width; 1024dp provides the tested expanded
list/detail case. Short windows and 200% font use one-pane/dialog flow.

Day navigation is tertiary, New Event primary and New Task secondary. Neutral
Material surface-container roles match the canonical shared theme, including
Dialog surfaces. This changes presentation only and introduces no dependency.
Named actions, semantic selection/headings, >=48dp targets and list alternatives
remain. Desktop tests exercise keyboard Tab/focus/Enter with an explicit Save;
Android tests exercise native Back including the IME layer and dirty-discard
confirmation. Screenshots do not establish physical screen-reader or all-form
keyboard acceptance; final a11y/motion acceptance remains D10-06.

## Verification commands and local evidence

Commands run from the implementation worktree with JDK 17; an ASCII Gradle-home
junction avoids the known Windows Unicode worker-path issue. This host setup is
not a repository dependency change.

```powershell
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home build --no-daemon
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home :apps:desktop:test :shared:ui:desktopTest :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest --no-daemon
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home :apps:android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.agenticscheduler.android.CoreSchedulingInstrumentedTest --no-daemon
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home :apps:android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.agenticscheduler.android.AppShellInstrumentedTest --no-daemon
.\test-support\d10-02\capture-android.ps1 -Serial emulator-5554 -Sdk C:\ProgramData\Android
python test-support/d10-02/check-boundaries.py
python test-support/d10-02/check-candidates.py --compare-generated
git diff --check
```

Pre-integration local `build` passed. Domain 42, Planner 67, Sync 55, Agent 48, Database 157,
Wear JVM 60, Application 213 cases (193 passed / 20 existing platform-gated skips),
and server 44 cases (27 passed / 17 PostgreSQL-gated skips). Skips are not passes
or platform/PostgreSQL acceptance. Final Desktop/native counts and screenshot
manifest are recorded after their last executions. Exact-head CI must execute
all five existing jobs; cancellation/unallocated jobs do not count as passes.

Earlier real failures are retained in the investigation: academic mixed aggregate
snapshots, cancellation of the post-save refresh when the dialog unmounted, and
Android Dialog PixelCopy capturing the background Activity. The UI lifecycle
issue is fixed by keeping the app-mounted editor scope alive before its early
return. A controllable delayed refresh test proves dialog disposal cannot cancel
that post-commit load or claim a refreshed source prematurely. The latter capture issue is fixed
in test code by asserting the intended semantic surface and capturing the actual
Android display via UiAutomation. Native captures include system bars; their
clock is platform state, not the synthetic application time or a pixel-repeat
claim. No screenshots are manually drawn or bitmap-edited.

## Final targeted evidence

- Desktop: **49/49**, no failure/skip: Calendar presentation 11, Academic
  presentation 14, core scheduling Compose UI 8, core editing Compose UI 6,
  and 10 unchanged accepted tests. The eight scheduling tests include actual
  Year/Semester/Template forms, separate Course/Rule commands, three Exam forms,
  resize/discard and deterministic delayed post-commit refresh survival.
- Shared design system: **3/3**, unchanged contrast/role/layout regressions.
- Actual Android API 35 isolated emulator: **5/5** new core tests and **4/4**
  unchanged shell tests, separately executed. Final core APK was tested again
  directly with AndroidJUnitRunner. No physical-device acceptance claimed.
- PixelCopy/semantics concurrency: new Android tests use the library's v2 rule
  and assert intended form/selection before actual display capture. Existing
  D10-01 test sources/candidate baselines are unchanged.

The latest exact-head CI is published on [Draft PR #33](https://github.com/fangbm/temvio/pull/33/checks)
and in the delivery report after all five jobs execute. This local evidence does
not replace CI's Android/Wear Keystore or real PostgreSQL/platform tests.

The historical foundation gap is resolved by merged D10-02F, not waived by a
green build or UI lifecycle/error handling. The updated-base PR comparison
excludes the accepted foundation code, with no Room/Application bypass.

## Post-integration verification — accepted D10-02F base

The normal merge above integrates PR #34 without modifying its implementation.
The following suites were actually rerun against that integrated source:

- `AggregateSnapshotConsistencyTest`: **13/13**, no failures or skips.
- `AcademicAuthoringIntegrationTest`: **37/37**, no failures or skips.
  This includes the real Application + Room active Calendar subscription during
  legitimate aggregate authoring: subscriber survives, final projection reflects
  the committed replacement, and `loadFacts()` returns complete committed facts.
  These are persistence/integration regressions, not presentation mocks.
- Desktop: **49/49**; shared UI: **3/3**, no failures or skips.
- Android API 35 isolated emulator, direct AndroidJUnitRunner:
  core **5/5**, shell **4/4**, both successful after rebuilding/installing APKs.
  This is real emulator instrumentation, not physical-device acceptance.

```powershell
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home :shared:database:desktopTest --rerun --tests '*AggregateSnapshotConsistencyTest' --tests '*AcademicAuthoringIntegrationTest' :apps:desktop:test --rerun :shared:ui:desktopTest --rerun :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest --no-daemon --console=plain
C:\ProgramData\Android\platform-tools\adb.exe -s emulator-5554 shell am instrument -w -r -e class dev.agenticscheduler.android.CoreSchedulingInstrumentedTest dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner
C:\ProgramData\Android\platform-tools\adb.exe -s emulator-5554 shell am instrument -w -r -e class dev.agenticscheduler.android.AppShellInstrumentedTest dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner
python test-support/d10-02/check-boundaries.py
python test-support/d10-02/check-candidates.py --compare-generated
git diff --check
```

All **52** existing candidate hashes/dimensions and all four normalized manifest
source hashes pass verification. Fresh Desktop test rendering matches the selected
Desktop PNGs byte for byte. Android matrix archives/candidates remain unchanged;
native functional tests reran, but the full Android capture matrix was not
regenerated or claimed pixel-identical to its historical platform clock.
No committed screenshot, capture manifest or D10-01 visual baseline changed.

Comparison against the updated base `60d2670f063357177a8e222db7ccbcc679bc0d92`
contains **80 D10-02-owned files**, excluding the accepted foundation correction.
Presentation/test/fixture sources remain identical to the pre-integration head;
only this task document and the roadmap receive status/evidence closure edits.
The new exact-head full CI is linked in PR #33 checks and the final delivery after
all five jobs actually execute; the pre-integration run is not current evidence.

## Screenshot candidate manifest

[Capture manifest](fixtures/d10-02/screenshots/capture-manifest.json) contains
**52** selected real-platform PNGs, bitmap dimensions, normalized source hashes,
fixture inputs and SHA256. Generated matrix: 50 Desktop + 62 Android candidates.
All eight native capture variants completed with `OK (1 test)` each. Capture and
comparison write/read only the separate D10-02 candidate paths; no D10-01 history
is rewritten and no automatic visual approval is performed.

Representative inspected outputs include Desktop Light Today, Light Week, Dark
Month, Dark wide Course detail, and narrow large-font Semester; Android Light
Today/Week, Dark Month, Dark 1024 Course detail, 200% Semester and short-height
Semester. The grid's dates and timed ranges remain distinct. 200%/short-height
form actions remain visible with a scrollable body; selected detail uses the
expanded pane only when usable. Android test Activity system-bar chrome is
included in native display PNGs and is not product screen-reader acceptance.

Examples: [Desktop Light Today](fixtures/d10-02/screenshots/desktop-1024x900-font100-light-today.png),
[Desktop Dark Month](fixtures/d10-02/screenshots/desktop-1024x900-font100-dark-calendar-month.png),
[Android Light Week](fixtures/d10-02/screenshots/android-360x800-font100-light-calendar-week.png),
[Android expanded detail](fixtures/d10-02/screenshots/android-1024x768-font100-dark-course-detail.png),
[Android 200% form](fixtures/d10-02/screenshots/android-360x800-font200-light-semester-form.png).

## Delivery and remaining gates

Draft [PR #33](https://github.com/fangbm/temvio/pull/33) targets
`feature/d9-02-agent-sync` from the current merged D10-02F baseline. Local complete
build and final targeted suites passed; the post-save refresh regression also
passed a forced repeated Desktop run. Final exact-head CI conclusions are
reported separately after real execution. No migration/new dependency.

**IMPLEMENTED / AWAITING REVIEW**: D10-02F dependency is RESOLVED / MERGED via
PR #34 / `60d2670f063357177a8e222db7ccbcc679bc0d92`. No remaining FOUNDATION_GAP
or D10-02 BLOCKED_BY_DECISION. No new frozen semantic decision or persistence
bypass is added. D10-02 is neither MERGED nor COMPLETE; OD-012 remains OPEN.
D10-03+ views, D10-04 Agent redesign, D10-05 Wear redesign, D10-06 final motion/
accessibility and all new Domain/Planner/Tool/sync/security capabilities remain
outside this slice. Keep Draft; do not merge or begin the next slice.
