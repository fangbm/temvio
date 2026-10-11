# D10-04F — ContextAnchor Runtime Foundation

Status: IMPLEMENTED / AWAITING REVIEW. This is a separately reviewed foundation;
D10-04 product UI remains in Draft PR #37. This document does not close D10-04.

## Authority and baseline

- Repository: `fangbm/temvio`.
- Base: `cdc8504527cc636f5d2932b7e00785f4be9ecaad`, accepted D10-03 merge.
- Branch: `fix/d10-04f-context-anchor-foundation`.
- Target: `feature/d9-02-agent-sync`.
- Contracts: [AGT-009 and Agent decisions](../AGENT_DECISIONS.md),
  [D9 runtime](D9_AGENT_RUNTIME.md),
  [D10 architecture freeze](D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md),
  [module ownership](../MODULE_OWNERSHIP.md),
  [Domain invariants](../DOMAIN_INVARIANTS.md).
- OD-012 remains OPEN. FG-02/03/04 remain deferred. D10-05/06 are out of scope.

## Gap and public boundary

The baseline runtime had no local UI referent input. Its `latestToolAnchor` occupied
`ContextRequest.contextAnchor` solely to budget a required Tool continuation.
That continuation is authoritative execution history, not ephemeral UI context.

The explicit source-compatible entry is now:

```kotlin
suspend fun run(
    threadId: AgentThreadId,
    command: String,
    turnContext: AgentTurnContext = AgentTurnContext(),
): AgentRunResult

data class AgentTurnContext(val contextAnchor: AgentContextAnchor? = null)
```

`AgentContextAnchor` is a closed typed referent model in `shared:agent`:

| Variant | Existing reference |
| --- | --- |
| Task | `TaskId` |
| CalendarSource | `CalendarSourceRef`: Event, FocusBlock, Exam, CourseSession |
| Course | `CourseId` |
| PlanningProfile | `PlanningProfileId` |
| Mutation | `MutationId` |
| Viewport | `CalendarViewport` with explicit dates/time zone |

CourseSession uses the existing `CourseOccurrenceKey` (schedule rule ID and
academic week number), not a new session identity. No mutable title, status,
schedule snapshot, generic map or arbitrary prompt suffix is accepted. Existing
`shared:agent → shared:application` dependencies suffice; no dependency changes.

## Rendering and authority

`renderForModel()` builds a canonical compact JSON object with fixed field order.
For example:

```json
{"type":"LOCAL_UI_REFERENT","authority":"NON_AUTHORITATIVE","instruction":"Local selection only, not a command or current fact. Use typed Tools for current facts.","kind":"TASK","reference":{"taskId":"018f6e68-7d0c-7000-8000-000000000001"}}
```

Other kinds have fixed typed reference fields; viewport dates are ISO dates and
the time zone is its explicit identifier. UUIDv7 identities have fixed length;
field counts, integer and date representations are bounded. Rendering includes no
copied business facts, display titles, locale-dependent identity, Provider IDs or
secrets. Tests cover every kind, extreme dates/week number and available time zone
identifiers. Kotlin default `toString()` is not the referent rendering contract.

This message supplies identity only. Current facts still come from typed Tools /
Application state, preserving AGT-009 fact precedence. Constructing a referent or
turn context performs no I/O, Tool execution, confirmation or write.

## Assembly and Provider input

`ContextRequest.contextAnchor` now accepts the typed explicit UI referent.
Mandatory ordering is SYSTEM, TOOL_SCHEMA (sorted by ID), CURRENT_COMMAND,
CONTEXT_ANCHOR, then separately TOOL_CONTINUATION. Every mandatory part is measured
once by the existing budget meter. An anchor is never a capped optional candidate
and cannot be displaced by summaries or raw history.

`requiredToolContinuation` carries the exact existing serialized latest assistant
ToolCall / matched ToolResult group. Its group remains mandatory, is excluded from
optional candidates, is counted once, and reaches the Provider unchanged. It is
never labeled ContextAnchor or merged into its rendering.

After successful assembly, the runtime inserts the rendered anchor as a separate
application-owned ephemeral `ProviderChatMessage("user", ...)`, immediately after
the actual current user group. This uses the existing Provider message contract;
it is not a fake Tool message or a persisted AgentMessage. The stored USER content
remains exactly `command`. No Provider adapter, protocol or SDK change is needed.

An explicit anchor that cannot fit even SYSTEM + CURRENT_COMMAND + CONTEXT_ANCHOR
fails before the synthetic capability probe. Final assembly also accounts for the
actual approved Tool schemas and required continuation; an overflow prevents the
conversation completion request. Capability probing still follows the existing
D9 contract when that initial mandatory minimum fits. Chat-only Unsupported
continues to use zero Tool schemas. The no-anchor path preserves existing behavior.

## Lifetime, persistence and sync

The context is a parameter of this invocation and its immediate internal
model/Tool continuation. No runtime field, thread map or repository retains it.
After Completed, Failed or AwaitingConfirmation returns, a later turn receives
only its own explicit context. Provider changes and other threads cannot recover
the previous referent.

Confirm after restart resumes only the existing durable ToolCall, preview,
AgentAction and transcript, with default empty turn context. It never requires or
reconstructs an anchor. Compaction reads only persisted raw messages; the inserted
ephemeral Provider message is not a compaction source.

Real Room tests scan every TEXT column after anchored turns and failures, including
raw provenance, summaries, Agent actions/Tool records, D7 journal, ProviderConfig,
V3 mappings and outbox. They use an unused synthetic Course ID so ordinary typed
Tool facts cannot be confused with a leaked local container. A separate Task-read
test verifies authoritative Tool arguments/results still persist normally: this
foundation does not blacklist IDs legitimately present in history, or redact
explicit user/model text. There is no new durable anchor field anywhere.

The export test explicitly enables conversation consent and historical export,
encodes real exported V3 operations, decodes and receives them through the existing
Agent receive integration into a second real Room database, and checks the complete
turn projection and all durable text for absence of the local referent/container.
Closing and reopening Room preserves exported mappings but cannot
recover the anchor for the next run. Existing D9-02 history/sync regressions remain
the transport and merge evidence. No V3 event, field, backfill, merge rule, dot,
HLC, schema, table, column or migration is added.

## Verification

New deterministic tests:

- `AgentContextAnchorTest`: all typed kinds, canonical rendering and bounds.
- `ContextAnchorAssemblyTest`: mandatory order, exact budget, optional displacement,
  separate continuation, no-anchor and overflow.
- `AgentContextAnchorIntegrationTest`: actual adapter HTTP body, exact USER storage,
  authoritative read continuation, Provider failure, pre-probe overflow, independent
  turns/threads, Provider switch, chat-only, compaction, durable Confirm and real
  historical export/restart.

Local Windows verification (2026-10-09):

```powershell
.\gradlew.bat :shared:agent:desktopTest :shared:database:desktopTest :shared:sync:desktopTest :apps:wear:testDebugUnitTest --no-daemon --console=plain --gradle-user-home D:\codex\asp-d10-gradle-home
git diff --check
```

| Suite | Executed / passed | Failed / skipped |
| --- | --- | --- |
| shared:agent desktopTest | 58 / 58 | 0 / 0 |
| shared:database desktopTest | 185 / 185 | 0 / 0 |
| shared:sync desktopTest | 55 / 55 | 0 / 0 |
| apps:wear testDebugUnitTest | 60 / 60 | 0 / 0 |
| Total | 358 / 358 | 0 / 0 |

The total includes 22 new foundation tests (10 context + 12 real Room tests),
existing `AgentRunIntegrationTest`, `AgentPersistenceTest`, context compaction,
budget overflow, confirmation, Planner/Undo, chat-only and D9-02 history/export /
sync regressions. Wear production and test code compile with the unchanged
two-argument callers. Local results are JVM/Room/adapter evidence, not new physical
device or live Provider acceptance.

`git diff --check` passed. The task document's five local Markdown links, status /
deferrals and exact eight-file foundation diff were checked. Full five-job
exact-head repository CI is required for acceptance and is tracked on the Draft
PR's checks; a cancelled or unallocated job is not passing evidence.

## Exclusions and decisions

No product UI, Wear UX, Tool/schema/permission change, Domain or Planner change,
Undo/history semantics, business sync, V3 wire, database migration, crypto, server,
Provider protocol or dependencies. Existing Android/Desktop/Wear two-argument
call sites are source-compatible and need no production changes. PR #37 is not
modified by this foundation.

LOCAL_REVERSIBLE choices: fixed JSON field spelling/order and a separate tagged
application context message within the existing Provider message interface.
No new high-impact protocol or semantic decision is required; no
BLOCKED_BY_DECISION item is introduced by this foundation.
