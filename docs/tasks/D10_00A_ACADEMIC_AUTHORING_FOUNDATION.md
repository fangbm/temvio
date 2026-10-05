# D10-00A — Academic Authoring Foundation

Status: **IMPLEMENTED / AWAITING REVIEW**.
Baseline: `feature/d9-02-agent-sync`, D10-00 merge
`f8efbde97e185cbcaf704f5a487538cf147b034d`
([PR #30](https://github.com/fangbm/temvio/pull/30)).
Branch: `feature/d10-00a-academic-authoring`. Date: 2026-10-06.

## 1. Authority and scope

[D10-00](D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md),
[OD-062 Option B](../OPEN_DECISIONS.md), the maintainer's D10-00A instruction,
[academic invariants](../ACADEMIC_INVARIANTS.md),
[academic decisions](../ACADEMIC_DECISIONS.md),
[D3](D3_ACADEMIC_DOMAIN.md), [D4](D4_PERSISTENCE.md),
[history decisions](../HISTORY_SYNC_DECISIONS.md),
[sync decisions](../SYNC_SECURITY_DECISIONS.md),
[D7](D7_OPERATION_HISTORY_SYNC_FOUNDATION.md) and
[D8](D8_E2EE_SYNC_TRANSPORT.md) govern this slice.
Historical D3/D4 implementation fences do not prohibit the application authoring
expressly authorized here. Domain/time/merge contracts remain unchanged.

This is an Application foundation for later frontend consumers. It does not
implement or claim UI, running-app, visual, accessibility or device acceptance.

## 2. Audited prerequisite graph

The production [academic Domain](../../shared/domain/src/commonMain/kotlin/dev/agenticscheduler/domain/academic/)
already models this graph:

```text
AcademicYear (explicit date range)
  -> Semester (explicit parent, date range, timezone, ordered AcademicWeeks)
       -> Course (explicit name, nullable code)
            -> CourseScheduleRule (weekday, TeachingWeekSet, time, nullable room)
                 -> ClockTime: no PeriodTemplate required
                 -> PeriodBased: existing explicit PeriodTemplate + endpoint periods
                 -> derived CourseSession(ruleId, academicWeekNumber)
       -> Exam (optional Course in the same Semester)
            -> Unscheduled | DateOnly | Exact
```

`AcademicHoliday` and `CourseOccurrenceException` already affect resolution.
They are audited and preserved/validated when an affected graph is edited;
new authoring commands for them are not a minimum Course/Exam prerequisite.
No Year, Semester, week, template, timetable, holiday or exception is synthesized.
A Course without schedule rules is legal. No stored CourseSession/Event is added.

[AcademicRepository](../../shared/application/src/commonMain/kotlin/dev/agenticscheduler/application/persistence/PersistencePorts.kt)
and [RoomAcademicRepository](../../shared/database/src/commonMain/kotlin/dev/agenticscheduler/database/repository/RoomRepositories.kt)
already persist all eight academic families. Semester weeks and template periods
are aggregate children rewritten within existing nested write transactions.
Room remains **v16**, with **no schema/migration change**.

Existing typed semantic images, D7 academic Put variants, V1 codec, receive and
explicit conflict-resolution handlers cover these facts. No wire additions are
needed. Existing conservative whole-aggregate academic merge groups and Exam's
`reference` / `title` / `schedule` groups are reused.

## 3. Exact typed Application surface

Package: `dev.agenticscheduler.application.academic`.

[Contracts](../../shared/application/src/commonMain/kotlin/dev/agenticscheduler/application/academic/AcademicAuthoringContracts.kt)
and [AcademicAuthoringService](../../shared/application/src/commonMain/kotlin/dev/agenticscheduler/application/academic/AcademicAuthoringService.kt)
expose:

| Input | Explicit fields | Commands | Existing D7 mutation |
| --- | --- | --- | --- |
| `AcademicYearInput` | name, startDate, endDateExclusive | createAcademicYear / updateAcademicYear | AcademicYearPut |
| `SemesterInput` | academicYearId, name, start/end, timezone, immutable AcademicWeeks | createSemester / updateSemester | SemesterPut |
| `PeriodTemplateInput` | name, immutable AcademicPeriods | createPeriodTemplate / updatePeriodTemplate | PeriodTemplatePut |
| `CourseInput` | semesterId, name, nullable code | createCourse / updateCourse | CoursePut |
| `CourseScheduleRuleInput` | courseId, weekday, TeachingWeekSet, CourseTimeSpec, nullable room | createRule / updateRule | CourseScheduleRulePut |
| `ExamInput` | semesterId, nullable courseId, title, ExamSchedule | createExam / updateExam | ExamPut |

Create commands generate IDs through an injected `UuidV7Generator`; platform
composition supplies the existing `productionUuidV7Generator()`. Update commands
take the existing typed ID and optional typed `expectedBefore` snapshot. Inputs
have no semantic defaults and preserve nonblank whitespace. They contain no
Compose/UI state, Room record or arbitrary map. Domain value objects retain their
existing construction invariants; form parsing remains a presentation concern.

`AcademicEditingResult<T>` is `Success(value, mutationId)`, `Invalid(issues)`,
`NotFound`, `AlreadyExists`, `Stale`, or `BlockedBySyncConflict(blocks)`.
Issues identify invalid fields, missing parents, Semester membership, existing
CourseSession resolver issues, Exam validator results and affected holiday bounds.
Normal authoring rejection is typed. Persistence faults propagate and roll back.

`loadFacts(): ConflictAwareRead<AcademicAuthoringFacts>` loads immutable typed
Year/Semester/Course/Template/Rule/Exam collections, using the existing D8
collection projection and visible conflict metadata. An undecodable or invalid
provisional candidate is `Unprojectable`; there is no raw repository fallback.
This is an explicit refresh/load API, not a new persistence or subscription model.

Platform composition owns wiring repositories, transactions, production IDs and
the active-SyncSpace write guard/projection (or the existing no-active-space
implementations). Screen consumers invoke commands/load and render typed results;
they do not construct MutationCoordinator, D7 operations or repository upserts.
No new DI/navigation framework, UI module or composition service locator is added.

## 4. Validation and affected graph

- Year/Semester date ranges and nonblank names use existing Domain constraints.
  A Semester requires a real containing Year and explicit timezone.
- Weeks are nonempty, ordered and numbered 1..N, seven calendar days, within the
  Semester and nonoverlapping. Gaps and non-Monday starts remain legal.
- Course requires a valid Semester/Year; code is null or nonblank.
- A Rule requires a real Course and valid teaching weeks. The authoritative
  `resolveCourseSessions` validates existing templates, endpoint periods,
  holidays/exceptions, bounds and DST. No UI recurrence or DST choice is invented.
- Templates have explicit ordered, nonoverlapping periods; gaps/nonconsecutive
  numbers remain legal. PeriodBased requires its start/end endpoint periods,
  not invented intermediate periods.
- Exam uses `validateExamAgainstSemester`: linked Course must be present and in
  the same Semester; Unscheduled stays legal; DateOnly stays a date; Exact keeps
  the Semester timezone and a fully contained instant range.
- Updating Year/Semester/Template/Course/Rule revalidates affected dependents.
  Week/period removal, parent changes or date narrowing cannot strand existing
  referenced facts. Exceptions/holidays are retained, never silently repaired.
- Validation is deterministic over the affected graph with explicitly sorted
  read sets. Unrelated legacy facts/conflicts are not rewritten or invalidated.

## 5. Atomic commands and D8 conflict policy

Each successful call is one explicit HST-001 command, one atomic `MutationId` and
one typed aggregate Put. Course and Rule are separately authored source entities;
no combined Course-plus-schedule user operation is defined in this slice. A
frontend must not present multiple calls as one atomic save. A future combined
command requires a reviewed explicit contract; this API does not decide it.

Inside the existing `MutationCoordinator.executeIfAny(MutationOrigin.User)`
write transaction: read actual before state, check optimistic snapshot, build
candidate, validate affected graph, guard consumed facts, persist candidate,
record the actual typed before/after image, append journal and advance causal
state. Creation uses `before=null`. Updates retain IDs and record the current
durable before image, not a caller's stale snapshot. Creation ID collision is
rejected. Child-table rewrites and journal/causal changes share the outer write
transaction; any fault rolls them all back.

Field/graph/missing/stale/conflict failures produce no DB write, ChangeLog,
MutationId or causal advancement. A rejected create may consume an injectable
entity ID, which is not a committed operation/dot. No DVV/HLC allocation,
overwrite or merge semantics are changed.

The existing `SyncConflictWritePolicy` guards full images for the root and all
facts actually consumed by the whole-form command. These guard probes are never
journaled. Any OPEN conflict in a consumed group blocks authoring, including an
OPEN prerequisite: validation cannot justify writing from raw conflicted facts.
This whole-form contract conservatively reads all Exam groups; it does not
introduce a partial field command or new group policy. Read-only frontend loads
may show the existing provisional projection with conflict refs. Ordinary
authoring never resolves conflicts or modifies evidence.

## 6. Scope and deferred capabilities

No academic deletion, new Undo support, Agent Tool, Planner rule, recurrence,
reminder/default policy, new merge/wire encoding, schema redesign, D7/D8/D9
runtime change, crypto, server change, frontend, Compose, navigation or
`:shared:ui` is implemented. **OD-012 remains OPEN**. No D10-01 starts here.
New holiday/exception authoring, bundled saves and future UI are not claimed as
shipped capabilities. No unresolved semantic decision is selected for this API.

## 7. Verification evidence

Local verification used Microsoft JDK 17 and an ASCII junction to the existing
Gradle user home (`--gradle-user-home D:\codex\asp-d10-gradle-home`). The default
Chinese-path cache made the JVM launcher fail to find `GradleWorkerMain` before
test execution; the junction allowed real test workers to run. No Gradle/JVM
configuration or dependency in the repository was changed for this host fix.

Targeted command (the final added regression is also included in the full suite):

```powershell
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home `
  :shared:application:desktopTest --tests '*AcademicAuthoringInputTest' `
  :shared:database:desktopTest --tests '*AcademicAuthoringIntegrationTest' `
  --no-daemon --console=plain
```

New tests: **41/41 passed, no skips** — six Application input-contract tests and
35 real Room/Application authoring integration tests. The latter cover all six
command families and before/null-creation images, missing/invalid prerequisites,
period endpoints, existing exceptions, DST, parent edits, production UUIDs,
optimistic stale/ID collision, OPEN root/dependency guards, conflict-aware frontend
reads, unrelated legacy facts, child/journal/causal rollback, restart, existing V1
codec/authenticated receive, Calendar and the existing Planner preview path.
Conflicted templates block dependent PeriodBased authoring while independent
ClockTime authoring remains available. The Planner test proves an authored Course
and Exact Exam reserve their existing slots without persisting CourseSessions or
activating preview FocusBlocks.

Broader command:

```powershell
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home `
  :shared:domain:desktopTest :shared:planner:desktopTest :shared:sync:desktopTest `
  :shared:application:desktopTest :shared:database:desktopTest `
  --no-daemon --console=plain
```

| Module | Test cases | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| Domain | 42 | 42 | 0 | 0 |
| Planner | 67 | 67 | 0 | 0 |
| Sync | 55 | 55 | 0 | 0 |
| Application | 213 | 193 | 20 | 0 |
| Database | 157 | 157 | 0 | 0 |
| Total | 534 | 514 | 20 | 0 |

Both runs completed `BUILD SUCCESSFUL`; the final Database rerun includes the
35th regression, while unchanged module results were up-to-date. The 20 local
skips are existing PostgreSQL/platform-relay gated tests (18 Agent-history,
one platform-relay, one Provider provisioning); they are not counted as passes.
Repository full CI supplies its own PostgreSQL and Android/Wear platform jobs.
Its exact delivered-head run/result is linked in the Draft PR acceptance evidence.
`git diff --check` and local Markdown link/status checks are also required before
push. CI is regression evidence, not product academic UI acceptance.

**BLOCKED_BY_DECISION: none for this authoring surface.** Existing OD-012 remains
OPEN and outside this slice. Separate commands, immutable input collections and
one cohesive academic service are local implementation organization choices;
no combined frontend save contract has been invented. The Draft PR remains
subject to maintainer review; no merge or D10-01 work is authorized.
