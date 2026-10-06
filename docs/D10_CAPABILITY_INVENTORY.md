# D10 capability inventory — audited D9 closure baseline

Status: **AUDITED / D10-00 MAINTAINER APPROVED**. Date: 2026-10-06.
Baseline: `feature/d9-02-agent-sync`, `378630cb63447443b02cae4599845b06c9353b5b`
([PR #29](https://github.com/fangbm/temvio/pull/29) merged).
Approved architecture: [D10-00 freeze packet](tasks/D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md),
FROZEN / MAINTAINER APPROVED / MERGED (PR #30, `f8efbde`). This inventory's
tables retain the audited D9 baseline; no implementation was part of D10-00.

Current foundation follow-up: [D10-00A Academic Authoring Foundation](tasks/D10_00A_ACADEMIC_AUTHORING_FOUNDATION.md)
adds typed local Year/Semester/Template/Course/Rule/Exam commands and conflict-aware
loads for separate review. The historical `REQUIRES_FOUNDATION`/unstarted entries
below describe the D10-00 audit, not the current slice's implementation status.
Product academic UI remains unimplemented; frontend consumers must use the
reviewed Application boundary rather than repository writes.

This is a source audit, not a new running-app, screenshot, accessibility or device
acceptance claim. Earlier acceptance remains in its original records. A persisted
entity, typed wire image or promo screen does not prove a user-facing command.

## 1. Current app structure and state ownership

All paths below are repository-relative and describe the audited baseline.

| Platform | Entry/composition | Actual screens/components | Current state ownership / limitations |
| --- | --- | --- | --- |
| Android | [MainActivity.kt](../apps/android/src/main/kotlin/dev/agenticscheduler/android/MainActivity.kt), 1,276 lines | `AndroidScheduler`, `CalendarRow`, Event/Task editor dialogs, `PlannerDogfoodPanel`, profile dialog, `AndroidAgentPanel`, conversation-sync controls and startup/retry status | Activity lazily constructs Room implementations, mutation/editor/Planner/Agent/D8 services and platform secure store. One `LazyColumn` combines Agenda/Day, Tasks, Agent, Planner and consent controls. Screen-local `remember`/mutable state and coroutine callbacks; conflict-aware reads use `collectAsState`. No destination graph or product bottom navigation. |
| Desktop | [Main.kt](../apps/desktop/src/main/kotlin/dev/agenticscheduler/desktop/Main.kt), 1,140 lines | `DesktopScheduler`, `CalendarRow`, Event/Task editor dialogs, `PlannerDogfoodPanel`, profile dialog, `AgentCommandPanel`, Provider dialog, conversation-sync controls and startup/retry status | `application`/`Window` composition constructs database, secure store and services with `remember`. One `LazyColumn`; Agent precedes Agenda/Day. Window focus owns foreground sync polling. Local draft/selection/dialog/preview state; reactive source-fact flows. No persistent sidebar, typed destination graph or width-class shell. |
| Wear | [WearMainActivity.kt](../apps/wear/src/main/kotlin/dev/agenticscheduler/wear/WearMainActivity.kt), 305 lines | `WearAgenda` Today/Upcoming, Agent/setup route and retry status; separate [WearAgentScreen.kt](../apps/wear/src/main/kotlin/dev/agenticscheduler/wear/agent/WearAgentScreen.kt) | Activity owns platform composition/lifecycle and a local `showAgent` switch. Agenda shows up to three Today and three Upcoming titles. `WearAgentRoute` renders `WearAgentSessionController.ui` and readiness flows. Session owns draft/busy/local request lease, not Tool/business truth. Binding, capability, network and STT adapters are already separate. |

Existing platform roots may compose database infrastructure; that permission does
not extend to screen DAO writes. Android/Desktop currently colocate composition,
projection display, forms, parsing and interaction orchestration in their entry
files. D10 decomposition is needed before adding final screens; it is not performed
in D10-00. Wear's extracted session coordinator is a useful ownership example, not
a second shared Agent runtime to copy.

### Dependencies / design-system inspection

- [settings.gradle.kts](../settings.gradle.kts) contains Domain, Application,
  Planner, Sync, Agent, Database, three apps and the server; **no `:shared:ui`**.
- Android uses Android Compose Material3; Desktop uses Compose Multiplatform
  Material3; Wear uses Wear Compose Material3. Each entry calls `MaterialTheme`.
  No canonical project theme/token location or complete Light/Dark design contract
  is wired across all apps.
- The [version catalog](../gradle/libs.versions.toml) and app/shared Gradle
  dependency declarations contain no project-wide Redux/MVI/MVVM/navigation/DI
  framework. No such framework is required by the existing service boundaries.
- Duplicate Android/Desktop editor/profile/Agent/Agenda presentation and future
  common tokens justify the approved thin `:shared:ui` design-system target.
  Creation/dependency/target details remain D10-01 work. Wear keeps its own
  components while consuming compatible semantic tokens. See packet section 4.

## 2. Capability matrix: current UI versus foundation

Legend: **IMPLEMENTED** means a current source path exists, not new visual
acceptance; **READ_ONLY** means source facts/projections can be read;
**NOT_IMPLEMENTED** means the product screen/path is absent;
**REQUIRES_FOUNDATION** means a missing application contract must precede the
requested interaction. `Tool` denotes an explicit existing Agent Tool path,
not an equivalent standalone form.

| Capability | Android current UI | Desktop current UI | Wear current UI | Foundation / D10 limitation |
| --- | --- | --- | --- | --- |
| Today dashboard | NOT_IMPLEMENTED; single-day Agenda | NOT_IMPLEMENTED; single-day Agenda | IMPLEMENTED compact Today/Upcoming | Calendar/Task/FocusBlock reads exist; final Today aggregation is presentation, not a new store. |
| Calendar Agenda/Day | IMPLEMENTED | IMPLEMENTED | READ_ONLY seven-day viewport, compact titles | `ConflictAwareSourceFactReadService` / calendar projection preserve time/source types, issues and conflict refs. |
| Calendar Week grid / Month grid | NOT_IMPLEMENTED | NOT_IMPLEMENTED | Outside final Wear scope | Projection can query a bounded viewport; renderer absent. OD-061 D10 Week/Month presentation extension is accepted alongside preserved D5 Agenda/Day; implementation is D10-02 work. |
| Event create/edit | IMPLEMENTED dialogs; also Tool | IMPLEMENTED dialogs; also Tool | Existing Tool + Watch confirmation; no standalone form | `EventEditingService`, MutationCoordinator and D8 write policy. No new Event delete contract. |
| Task list/create/edit | IMPLEMENTED list/dialogs; also Tools | IMPLEMENTED list/dialogs; also Tools | Agent read/write Tools; no full Task screen | `TaskEditingService`, explicit effort/deadline/status inputs, D7 journal and conflict policy. |
| FocusBlock | READ_ONLY calendar/Planner presentation | READ_ONLY calendar/Planner presentation | READ_ONLY agenda / existing Planner Tools | D6 preview/Apply is the mutation authority. No general new FocusBlock editor assumed. |
| Courses list/detail | NOT_IMPLEMENTED standalone; resolved sessions appear in Calendar | Same | READ_ONLY resolved schedule titles | Course reads and derived projections exist. Product authoring waits for separately reviewed D10-00A, selected by resolved OD-062 Option B. |
| Course create/edit / timetable authoring | REQUIRES_FOUNDATION | REQUIRES_FOUNDATION | Outside Wear UI scope; no Course Tool | Repository upsert + D7 vocabulary + D8 receive/resolution are not a local authoring command. OD-062 resolved Option B; D10-00A unstarted. |
| Exams list/detail | NOT_IMPLEMENTED standalone; scheduled projection only | Same | READ_ONLY scheduled title when projected | Exam reads exist; Unscheduled is absent from calendar, DateOnly has no invented Instant. |
| Exam create/edit | REQUIRES_FOUNDATION | REQUIRES_FOUNDATION | Outside Wear UI scope; no Exam Tool | No dedicated create/edit application service. OD-062 resolved Option B; D10-00A unstarted. |
| Planner Full Replan / Local Reflow | IMPLEMENTED dogfood panel and Tools | Same | Existing Agent preview/Apply Tools | `DogfoodPlannerService`, snapshot assembler, deterministic Planner and session-local PlanBranch. No redesigned Planner algorithm. |
| PlanBranch preview/Apply/Cancel/Stale | IMPLEMENTED dogfood/Agent paths | Same | Existing exact Tool preview/Watch confirmation | Final screen polish absent. Branch is isolated, session-local; losing it requires a fresh preview, not restoration as active state. |
| History / Mutation detail / Undo | NOT_IMPLEMENTED standalone; history/Undo Tools | Same | History/Undo Tools | `HistoryQueryService`, `UndoService` and typed results exist. Academic Undo and Event/Task-create Undo remain unsupported. |
| Business sync / retry reason | IMPLEMENTED startup, counts and stopped-reason/retry | Same | IMPLEMENTED status/retry | D8 runtime/worker exists; held outbound and inbound results are independent. No full status/device console. |
| Devices / pairing / recovery / revocation | NOT_IMPLEMENTED final management UI | Same | Setup/status only | Application lifecycle/security services and acceptance exist. Android/Wear endpoint/account use manifest config; Desktop uses environment config. Do not invent enrollment/security flows. |
| Business conflict resolution | Count/read notices only | Same | Count/read notices; no complete resolution UI | `SyncConflictQueryService` / `SyncConflictResolutionService` exist, typed candidate/group scope and explicit marker required. |
| Agent history semantic conflict UI | NOT_IMPLEMENTED final UI | Same | Continuation blockers observed | D9-02 persistence/projector/user delete-resolution boundary exists. No frozen title-resolution command; fork/title/integrity conflicts cannot be silently cleared. |
| Agent conversation / confirmation / Provider setup | IMPLEMENTED minimal panel with real thread/Tool results, confirmation, config/secret storage and local policy | IMPLEMENTED minimal panel and Provider dialog | IMPLEMENTED extracted session/screen; explicit text Send, exact local confirmation, binding selection | Shared `AgentRunService` owns execution/context/history. Watch clamps write permissions. Chat-only unsupported Tools exposes zero schemas. |
| Agent V2 business gate / V3 consent and explicit export | IMPLEMENTED separate local controls | Same | V2 gate enforced; final V3 controls not present | V3 transport integration is testable; production-sensitive V3 remains gated by OPEN OD-012. Consent does not enable upload or backfill itself. |
| Provider credential provisioning | Services/tests; NOT_IMPLEMENTED final source selection/SAS/setup UI | Same | Services/install/readiness + local binding controls; full mailbox setup UI absent | Dedicated D9-03 secure provisioning, never workspace sync; no nearby delivery or Phone proxy assumed. |
| Optional on-device speech | Outside this slice's final phone input contract | Not a Desktop requirement | IMPLEMENTED capability/language/permission adapter and draft-only action | `und` is unselected; unsupported STT affects voice only. Real AVD service was absent; no physical speech success claimed. |
| Settings | Feature-local Provider/policy/profile/consent controls; no unified product surface | Same | Local AI enable/language/binding/retry | Preferences are distinct application/security state, not navigation state or synced consent by inference. |
| Insights / proactive suggestions | NOT_IMPLEMENTED | NOT_IMPLEMENTED | Outside final Wear scope | No audited dedicated analytics/proactive service. Existing structured facts may be displayed read-only; new inference/background automation requires a foundation decision. |

## 3. Course / Exam command audit — OD-062 evidence

Positive evidence:

- [PersistencePorts.kt](../shared/application/src/commonMain/kotlin/dev/agenticscheduler/application/persistence/PersistencePorts.kt):
  `AcademicRepository.observe/get/upsertCourse`, Exam equivalents and academic
  year/semester/template/rule/holiday/exception ports.
- [RoomRepositories.kt](../shared/database/src/commonMain/kotlin/dev/agenticscheduler/database/repository/RoomRepositories.kt):
  Course/Exam upsert implementations persist mapped source facts. These methods
  do not themselves establish a user command's validation, MutationId or audit.
- [HST-002](HISTORY_SYNC_DECISIONS.md): CoursePut/ExamPut and other academic typed
  mutations; Put explicitly does not authorize a new UI write.
- [SyncEngine.kt](../shared/application/src/commonMain/kotlin/dev/agenticscheduler/application/history/SyncEngine.kt)
  applies authenticated typed academic receives; [SyncConflictResolutionService.kt](../shared/application/src/commonMain/kotlin/dev/agenticscheduler/application/history/SyncConflictResolutionService.kt)
  applies explicitly conflict-scoped academic resolution. Neither is ordinary
  Course/Exam create/edit authoring.
- [Editing.kt](../shared/application/src/commonMain/kotlin/dev/agenticscheduler/application/editing/Editing.kt)
  contains EventEditingService and TaskEditingService. Academic source facts feed
  calendar/Planner projections; [AGT-004](AGENT_DECISIONS.md) adds no academic write Tool.

Audit search across production `apps`, `shared/application`, `shared/agent` and
`shared/database` for `upsertCourse|upsertExam|createCourse|updateCourse|createExam|updateExam|CourseEditing|ExamEditing|AcademicEditing`
finds repository declarations/implementations and SyncEngine/conflict-resolution
application sites, but **no dedicated local Course/Exam authoring service or form**.
This is absence-of-command evidence at the fixed baseline, not proof that storage
cannot represent academic facts.

Therefore D10 must not call `AcademicRepository.upsertCourse/upsertExam` from UI,
wrap those calls in a coordinator owned by UI, or mistake receive/resolution for
authoring. **OD-062 is RESOLVED FOR D10 / OPTION B**: a separately reviewed
D10-00A supplies production-ID application commands, deterministic validation and
atomic D7/D8-aware writes before authoring UI. It first audits required
AcademicYear/Semester, Course, schedule rules, Exam and PeriodTemplate relationships;
no silent prerequisite/default timetable synthesis. Option A was not selected.
D10-00A remains unstarted here. See packet section 13 for tests and scope exclusions.

## 4. Promo and acceptance evidence limits

[promo/README.md](../promo/README.md), [promo.html](../promo/promo.html), existing
Temvio assets and approved still `frame-17-60.png` were inspected. They show calm
light surfaces, a persistent rail, distinct conversation/preview areas, restrained
accent/elevation and explicit confirm-before-write. They are a visual reference,
not production Compose screens, current capability evidence or final layout specs.
The promo's D9-in-progress annotations are historical media metadata; they do not
override the merged D9 completion records and are not changed in this docs slice.

Preserved milestone authority/evidence:

- [D5 projection](tasks/D5_CALENDAR_SURFACE.md), [D5 editing](tasks/D5_02_CREATION_EDITING.md),
  [D6 Planner](tasks/D6_DETERMINISTIC_PLANNER.md), [D6 rewrite](tasks/D6_PLANNER_CORE_REWRITE.md),
  [D7 journal/Undo](tasks/D7_OPERATION_HISTORY_SYNC_FOUNDATION.md).
- [D8 completion](D8_COMPLETION_ACCEPTANCE_RECORD.md),
  [D9 runtime](tasks/D9_AGENT_RUNTIME.md), [D9-02 completion](D9_02_COMPLETION_ACCEPTANCE_RECORD.md),
  [D9-03-01](D9_03_01_PROVISIONING_ACCEPTANCE_RECORD.md),
  [D9-03-02](D9_03_02_CAPABILITY_ACCEPTANCE_RECORD.md),
  [D9-03-03](D9_03_03_WEAR_AGENT_ACCEPTANCE_RECORD.md).

Old task statuses describe their historical acceptance stage. This audit does not
reopen accepted semantics, erase prior evidence or claim final D10 accessibility.
OD-012 remains OPEN; local first/offline capability is not production release approval.
