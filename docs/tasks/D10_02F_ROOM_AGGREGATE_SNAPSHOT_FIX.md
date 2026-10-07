# D10-02F — Room aggregate snapshot consistency

Status: **IMPLEMENTED / AWAITING REVIEW** (separate foundation PR; not D10-02 UI acceptance).
Baseline: `feature/d9-02-agent-sync`, `19d6a0779b27ff2641ff0c9251c8017b256ba774` (merged D10-01).
Branch: `fix/d4-aggregate-observer-atomic-snapshots`. Date: 2026-10-07.

## Authority and correction to D4 evidence

Maintainer authorized this focused persistence correction after D10-02 exposed a
latent gap in the [D4 committed aggregate observation contract](D4_PERSISTENCE.md)
(§26 and §38) and [PD-004/005/006/013](../PERSISTENCE_DECISIONS.md).
Historical D4 acceptance remains historical evidence; its atomic-write tests did
not prove multi-query read consistency under concurrent replacement. This task
records the correction rather than rewriting that acceptance.
[Domain invariants](../DOMAIN_INVARIANTS.md), [module ownership](../MODULE_OWNERSHIP.md),
[academic invariants](../ACADEMIC_INVARIANTS.md) and the existing
[D10-00A authoring boundary](D10_00A_ACADEMIC_AUTHORING_FOUNDATION.md) remain unchanged.

## Root cause and audited aggregates

An atomic aggregate write can commit all parent/child changes correctly while
independent Room parent and child Flows re-query at different times. `combine`
then reconstructs a new parent with old children, or vice versa. Both inputs
may individually be committed reads; their combination is not one snapshot.
This can throw during Domain validation or silently produce a valid hybrid.

| Aggregate | Parent table | Child table | Observation and point read |
| --- | --- | --- | --- |
| Semester | `semesters` | `academic_weeks` | `observeSemesters` / `getSemester` |
| PeriodTemplate | `period_templates` | `academic_periods` | `observePeriodTemplates` / `getPeriodTemplate` |
| CourseScheduleRule | `course_schedule_rules` | `course_rule_teaching_weeks` | `observeCourseScheduleRules` / `getCourseScheduleRule` |
| PlanningProfile | `planning_profiles` | `planning_profile_availability_windows` | `observeAll` / `get` |

PlanningProfile was affected by the same independent parent/window query pattern.
A wrong profile/window combination can remain Domain-valid, so its regression
checks full structural equality, not merely successful construction.
All four point reads also previously used independent suspending DAO reads.
They require coherence even without an enclosing application write transaction.

## Implementation (LOCAL_REVERSIBLE)

Room **3.0.3** already exposes `InvalidationTracker.createFlow` and
`withReadTransaction`. Observe invalidation of either aggregate table, then run
both ordered suspending DAO queries and normal Domain mapping inside one read
transaction. Invalidation is a trigger, not a parent/child value source. Grouping
children preserves the DAO's canonical child order; parents retain ID order.
Initial observation also takes one transaction snapshot. Room may conflate
invalidations; no promise of every historical intermediate revision is added.
Each emitted collection is reconstructed from a coherent committed snapshot.

Wrap each affected point read's parent/child queries and mapping in
`withReadTransaction` too. Room reuses an enclosing transaction, including an
application-owned writer: same-transaction reads see the author's uncommitted
complete value, and rollback still restores the previous aggregate.
Existing atomic writers remain unchanged.

No debounce, delay, sleep/retry, last-good/empty fallback, exception swallowing,
`distinctUntilChanged` masking, observation mutex or UI refresh workaround is
introduced. Domain constructors/factories still reject persisted corruption.

## Real Room regressions

`AggregateSnapshotConsistencyTest` uses a real file-backed Room database and
BundledSQLiteDriver. A test-only delegating driver places a one-shot barrier
immediately after the parent SELECT's SQLite `step()` establishes its snapshot.
The writer must commit a replacement while that reader is paused, before the
reader accesses children. Completion signals/latches coordinate the interleaving;
15-second guards only bound a failing test, with no scheduling sleeps.

- Four Flow regressions: acknowledge initial old state, trigger a committed read,
  pause it, commit the new parent/children with changed child counts, release,
  collect every emission until new state, and require every value to equal the
  complete old or complete new aggregate. Hybrid values must never appear.
- Four point-read regressions: a concurrent committed replacement after parent
  snapshot establishment still yields complete old state, then a new read yields
  complete new state.
- One enclosing-writer/rollback regression covers all four aggregates, including
  collection reads from within the writer.
- Four persisted-corruption regressions verify both Flow and point reads fail
  visibly rather than return plausible state.

Two additional `AcademicAuthoringIntegrationTest` regressions use real Application
services and Room:

- An active `ConflictAwareSourceFactReadService` Calendar subscription observes a
  legitimate `AcademicAuthoringService.updatePeriodTemplate` with changed metadata,
  period times and child count. The original subscriber stays alive, no projection
  issue is emitted, and the new CourseSession range reflects the committed update.
- After authored Semester/PeriodTemplate/CourseScheduleRule replacements,
  `loadFacts()` returns the exact complete facts through the existing conflict-aware
  boundary. No raw fallback is added. Existing OPEN/provisional/unprojectable D8
  regressions remain in the same suite.

## Verification evidence

Negative control: temporarily restoring only the pre-fix repository reads made
all four synchronized point-read tests fail with old-parent/new-child structural
mismatches. The fixed repository was restored before final verification. This
proves the barrier tests exercise the persistence defect, not only a lucky race.

Executed locally on JDK 17, using the existing ASCII Gradle cache:

```powershell
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home :shared:database:desktopTest --tests '*AggregateSnapshotConsistencyTest' --tests '*AcademicAuthoringIntegrationTest' --no-daemon --console=plain
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home :shared:database:desktopTest :shared:application:desktopTest :shared:planner:desktopTest --no-daemon --console=plain
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home build :shared:planner:desktopTest --rerun --no-daemon --console=plain
```

| Evidence | Result |
| --- | --- |
| Aggregate snapshot regressions | 13/13 passed |
| Academic authoring integration suite (including two new regressions) | 37/37 passed |
| New regressions total | 15/15 passed |
| Full database desktop suite | 172/172 passed |
| Full Application desktop suite | 193 passed, 20 existing environment/platform-gated skips, zero failures (213 discovered) |
| Planner desktop suite, explicitly rerun during build | 67/67 passed |
| Complete repository build (Android/Wear/Desktop compile, tests and lint) | Passed |
| `git diff --check`; new task local Markdown links | Passed |

Local gated skips are not platform/PostgreSQL acceptance. Repository CI supplies
those environment-specific paths; its exact-head run and five job conclusions
are delivered with the PR.
Full exact-head CI belongs to this foundation PR; all five required jobs must
actually execute. Cancellation or allocation failure is not acceptance.

## Scope and remaining gates

Room schema remains **v16**. No migration, table/column/index/schema export change,
new dependency, public Application repository API change, Domain/Calendar/Planner
semantic change, D7/D8 mutation/conflict change, wire/crypto/server change, or UI
change. No production sensitive-data enablement; **OD-012 remains OPEN**.
PR #33's presentation changes are excluded. This does not complete PR #33,
merge either PR, start D10-03, or expand beyond the four audited aggregate reads.
No new `BLOCKED_BY_DECISION` is required: maintainer explicitly authorized this
persistence repair and its internal implementation mechanism.
