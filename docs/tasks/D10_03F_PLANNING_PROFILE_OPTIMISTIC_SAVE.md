# D10-03F — Ordinary PlanningProfile optimistic Save

> Status: IMPLEMENTED / AWAITING REVIEW
> Authority: maintainer FG-01 foundation authorization after PR #35 review.
> Base: `feature/d9-02-agent-sync`, `cda86a815d21fe4a501b79a92074962001a54039`.

## Root cause and Application boundary

The former ordinary `save(profile)` read the latest profile inside the write
transaction but accepted no editor snapshot. A draft based on A could overwrite
a subsequently committed B with C. A UI preflight read cannot close this race.

The ordinary User update now requires:

```kotlin
suspend fun save(
    profile: PlanningProfile,
    expectedBefore: PlanningProfile,
): PlanningProfileSettingsResult
```

The unsafe one-argument overload is removed. Inside
`MutationCoordinator.executeIfAny(MutationOrigin.User)`, the service reads
`profiles.get(profile.id)` and checks structural equality against the supplied
snapshot before consulting the existing `SyncConflictWritePolicy` or writing.

| Transaction-local condition | Existing result | Active State / D7 effect |
|---|---|---|
| Missing current row | `NotFound` | No recreation or journal allocation |
| Current differs from expected snapshot | `Stale` | Current value retained; no journal allocation |
| Matching snapshot, blocked semantic groups | `BlockedBySyncConflict` | Current value retained; no journal allocation |
| Matching snapshot, allowed write | `Success(profile)` | Upsert and one User `PlanningProfilePut` |

The successful before-image is the exact transaction-local current value that
matched `expectedBefore`; after-image is the requested new value. `executeIfAny`
already allocates identity/counter/DVV/HLC only after a typed mutation is recorded.
All three non-writing outcomes leave the complete local causal state unchanged.
No new result hierarchy, conflict policy or optimistic force-save path is added.

`createUnconfigured` and `saveAgent` are unchanged. Creation still requires an
explicit name and synthesizes no configuration. Agent Save retains Agent origin,
its expected-before check and the existing Agent-origin authorization gate.

## Minimal caller migration

The maintainer separately authorized migration of the two existing
Android/Desktop `PlannerScreen` calls solely to adapt this signature. The
coordinator stores `editingProfile` when the user opens the editor; that immutable
snapshot is supplied as `expectedBefore`. No Save-time repository read replaces
it. Only `Success` invokes `onSaved`; `Stale`, `NotFound` and conflict blocks show
an error without closing the dialog, clearing the draft or automatically reloading.
Reload requires an explicit user close/reopen. No layout/navigation redesign.

## Verification

- `PlanningProfileSettingsServiceTest`: matching User update and exact journal
  images; A/B/C stale edit; missing row; typed conflict block; transaction-entry
  snapshot change; Agent origin/stale protection; denied Agent authorization;
  explicit Unconfigured creation. Non-writing assertions compare the complete
  journal, replica state and upsert count.
- `PlanningProfileOptimisticSaveIntegrationTest`: actual Room repositories,
  `RoomApplicationTransactionRunner`, `MutationCoordinator` and journal. Commit
  B (including availability child rows), then reject C using A, retaining B and
  leaving the durable journal/counter/frontier/HLC unchanged. No sleeps.
- `PlanningProfileDialogSaveTest`: 3/3 actual Desktop Compose + Room cases:
  Success persists the draft; Stale retains C and committed B; a typed conflict
  block retains the draft and current profile. Neither non-success closes the editor.

Local verification (2026-10-08, JDK 17):

```text
:shared:application:desktopTest
  221 discovered; 201 PASS, 20 environment-conditional SKIPPED, 0 failures.
  PlanningProfileSettingsServiceTest: 8/8 PASS.
:shared:database:desktopTest --tests '*PlanningProfileOptimisticSaveIntegrationTest'
  1/1 PASS.
:apps:desktop:test --tests '*PlanningProfileDialogSaveTest'
  3/3 PASS.
git diff --check
  PASS.
python test-support/d10-02/check-boundaries.py --source-only
  PASS (presentation ownership and Markdown/status checks).
```

Full repository build and all five exact-head CI conclusions are reported in the
Draft PR acceptance evidence. Environment-skipped local tests are not represented
as live PostgreSQL/platform acceptance.

## Scope

Room remains **v16**; no schema/migration/dependency change. Domain fields,
Planner legality/ranking, PlanBranch, Undo matrix, D8/D9, wire, crypto, server
and Wear are unchanged. OD-012 remains OPEN. FG-02/03/04 are deferred and
non-blocking; they are not implemented here. PR #35 and its task/roadmap are
unchanged pending separate foundation review/merge. No D10-04 work.
