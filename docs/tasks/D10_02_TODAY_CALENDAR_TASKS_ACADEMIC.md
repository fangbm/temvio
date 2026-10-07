# D10-02 — Today / Calendar / Tasks / Academic

Status: IN PROGRESS. Exact baseline: `19d6a0779b27ff2641ff0c9251c8017b256ba774`
(merged D10-01 / PR #32). Branch: `feature/d10-02-core-scheduling-academic`.

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

## FOUNDATION_GAP — atomic academic aggregate observation

User interaction: save an explicit Semester or CourseScheduleRule while the
authoritative Calendar subscription remains active.

Existing API inspected: `ConflictAwareSourceFactReadService.observe()` consumes
`RoomAcademicRepository.observeSemesters/observePeriodTemplates/observeCourseScheduleRules`.
The repository combines separately observed parent and child table snapshots.

Why insufficient: real Compose/Room save tests observed a newly committed parent
paired with the prior child list. Domain mapping rejects the mixed snapshot as
missing AcademicWeeks/TeachingWeeks, terminating the Calendar stream. The write
transaction and committed data remain intact. UI must not fabricate children,
relax Domain validation, poll or fall back to raw facts.

Minimal requested foundation: retain the exact existing repository/Application
contracts and emit each academic aggregate from one SQLite read transaction,
driven by parent/child invalidation; add creation/update observation regressions.
No schema, Domain, D7/D8, wire or merge change. Maintainer clarification requested;
this persistence change has not been made. Independent presentation work continues.

The UI surfaces technical read failures and an explicit refresh action. This
contains the crash; it is **not** evidence that aggregate observation is fixed.
Initial tests reproduced both Semester and Rule mapping failures. Acceptance
status remains IN PROGRESS pending this foundation decision; passing UI tests do
not close this persistence gap.

No other FOUNDATION_GAP / BLOCKED_BY_DECISION identified during the current audit.
Final accessibility/motion acceptance remains D10-06. Later product surfaces remain
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

Full local `build` passed. Domain 42, Planner 67, Sync 55, Agent 48, Database 157,
Wear JVM 60, Application 213 cases (193 passed / 20 existing platform-gated skips),
and server 44 cases (27 passed / 17 PostgreSQL-gated skips). Skips are not passes
or platform/PostgreSQL acceptance. Final Desktop/native counts and screenshot
manifest are recorded after their last executions. Exact-head CI must execute
all five existing jobs; cancellation/unallocated jobs do not count as passes.

Earlier real failures are retained in the investigation: academic mixed aggregate
snapshots, asynchronous form/save test synchronization, and Android Dialog
PixelCopy capturing the background Activity. The latter capture issue is fixed
in test code by asserting the intended semantic surface and capturing the actual
Android display via UiAutomation. Native captures include system bars; their
clock is platform state, not the synthetic application time or a pixel-repeat
claim. No screenshots are manually drawn or bitmap-edited.
