# D10-03 — Planner / History / Sync / Settings

Status: **IN PROGRESS**. Baseline: `cda86a815d21fe4a501b79a92074962001a54039`
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

## Verification and delivery

Targeted real Application + Room integration, Desktop Compose, Android native
instrumentation, bounded synthetic Light/Dark/adaptive/200% captures and full
exact-head five-job CI are required. Tests/capture/CI results are pending and
will be recorded after actual execution. Historic D10-01/02 screenshots remain.
No implementation or acceptance completion is claimed by this initial packet.
