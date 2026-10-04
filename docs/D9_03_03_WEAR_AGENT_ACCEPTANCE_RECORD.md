# D9-03-03 — Wear Agent acceptance record

Status: IMPLEMENTED / AWAITING REVIEW. Draft only; D9/D9-03 final approval remains with the maintainer. OD-012 remains OPEN. No D10 work or production-sensitive V3 enablement.

Baseline: `feature/d9-02-agent-sync`, D9-03-02 merge `dcd3e3c04e5eef622f3cef6a5e4d8eda365a5af5`.

## Runtime and authority

Watch foreground explicit text Send calls the existing `AgentRunService`, `OpenAiCompatibleProvider`, context assembler, typed Tools, application editors/Planner, transaction coordinator and Room state. The Watch coordinator owns navigation, draft, busy state and an ephemeral request lease; it is not another Agent engine. D9-01 persisted thread/call/preview/result/action and prospective local provenance are reused. Room stays at v16; no migration, new table or schema change.

The C8 effective policy preserves READ/PLAN_PREVIEW local DENY or confirmation, clamps every supported write to at most REQUIRE_CONFIRMATION, and retains deny-only capabilities. The original persisted policy is unchanged. V2 business acknowledgement is checked by the existing write gate and also supplied to the existing D8 outbound composition. Conversation V3 consent and Provider readiness do not authorize a business write.

Provider host authorization is checked before resolving credentials, after resolution, and in Ktor `HttpSend` immediately before the engine send. Checks reread exact selected config, binding/revision/journal ownership, purpose-scoped secure-store availability, install/wipe state, default route, local selection generation and foreground ownership. A command pins its immutable lease. The C6 single-flight probe result is reused only for that exact config/binding generation. No redirects, new Adapter, plaintext credential fallback, Phone proxy or background Agent replay.

Confirm/Deny uses the exact persisted call ID and normalized displayed preview. Shared runtime revalidates permission, source facts, Planner branch and business write gates. Restart restores the same pending action rather than asking the Provider for another proposal. Offline/wipe can stop subsequent Provider continuation; committed Task/ToolResult/AgentAction/MutationId/ChangeLog success remains authoritative, and it is not replayed. Planner branches retain existing session-local ownership and stale semantics.

## Minimal UI and language decision

Agenda exposes Agent setup while entry is disabled and the Agent route when enabled. AI entry remains device-local default OFF. The command surface shows independent capability/readiness facts, typed runtime reason, recent real thread, text draft/Send, New conversation, persisted transcript/Tool truth, exact Watch Confirm/Deny, readiness retry and local binding approval/selection. It displays `请求内容可能通过未加密 HTTP 传输` for non-loopback HTTP. Credentialed HTTP remains rejected before secret resolution. No credentials/SecretRef/SAS/envelopes are exposed in UI.

Maintainer decision in this task: before an explicit speech-language selection, `und` denotes unspecified, text remains usable, and STT is disabled. There is no system-language fallback or support assertion for `und`. Passive inspection does not query language or request/recognize audio in that state. Explicit language selection is a local preference, without Room or sync. Speech produces only a draft candidate; Send/confirmation require separate explicit actions.

## Executed local evidence

Windows JDK 17, ASCII worktree `D:\codex\ASP-d9-03-03`, `GRADLE_USER_HOME=D:\gradle-home-agent`; commands include `--no-configuration-cache --console=plain`.

| Command / suite | Executed result |
| --- | --- |
| `:apps:wear:testDebugUnitTest` | 57/57; C8 ceiling, C6 proof reuse/generation/single-flight/backoff, language initialization and existing capability/privacy tests |
| `:shared:agent:desktopTest` | 48/48; guarded normal/streaming actual-send boundary, pre-secret rejection/races, unchanged shared adapter and typed Tools/Planner semantics |
| `:shared:database:desktopTest --tests '*AgentRunIntegrationTest' --tests '*AgentHistoryExplicitExportTest'` | 17/17 (14 shared runtime, 3 export/privacy); real desktop Room, shared stale/Planner/Undo/context/audit regressions |
| `ANDROID_SERIAL=emulator-5556 :apps:wear:connectedDebugAndroidTest` | 28/28 (15 runtime E2E + 2 UI + 11 predecessor platform/security), 0 skipped; required core tests assert FEATURE_WATCH |

The Watch job runs the entire native suite; the phone job keeps the three predecessor Wear platform suites and does not mislabel phone execution as Watch runtime acceptance. Native core uses the production Android Ktor engine, file-backed Room, actual AgentRunService/Tools/application transaction paths and production composable Confirm/Deny. Only the local HTTP Provider responses are deterministic fixture responses with real structured calls; no fake AgentRunService, ToolResult or HTTP engine.

Native coverage: structured read/final response/zero mutation and history restart; write + persisted WAITING_CONFIRMATION + exact preview after file close/reopen + Watch Confirm + one Task and linked AgentAction/D7 operation/diff; Watch Deny after restart; duplicate confirm/send and send while pending/running; local DENY; source-fact stale; displayed-preview mismatch; local confirmation with absent binding/offline; real Keystore + existing provisioning service install/wipe before continuation; consent ON cannot replace V2 gate; local consent OFF read/write keeps zero V3 outbox; UI blocker/runtime mappings; non-loopback HTTP warning/real request; redirect blocked with zero request at the redirect target. No business/network replay on readiness recovery.

## Evidence limits and CI

No live external commercial Provider or physical Watch acceptance is claimed. The deterministic local structured fixture satisfies the task's real-engine/runtime Watch E2E requirement. Actual on-device recognizer availability is reported by the existing platform instrumentation; no physical recognition success is inferred from a fake or absent service.

Full CI is pending on the Draft PR head. Required jobs remain build, desktop-windows, android-keystore, wear-keystore, agent-history-platform-e2e. Final run/head and native counts will be recorded after execution.

## Scope / remaining decisions

No new Tool/module, Domain rule, Room schema/migration, D7/V3 DTO or causal semantics, provisioning wire, Envelope/AAD/crypto/server implementation was changed. No D9-02 production receive/storage/upload composition, remote context ingestion, PhoneContextBridge, nearby delivery, remote approval, background Agent/notification or D10 UI redesign.

No new BLOCKED_BY_DECISION for this slice. OD-012 remains an independent OPEN release gate; D9-03 implementation does not resolve or bypass it. Draft PR must not be merged automatically.
