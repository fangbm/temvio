# D9-03-03 — Wear Agent acceptance record

Status: IMPLEMENTED / AWAITING REVIEW. Draft only; D9/D9-03 final approval remains with the maintainer. OD-012 remains OPEN. No D10 work or production-sensitive V3 enablement.

Baseline: `feature/d9-02-agent-sync`, D9-03-02 merge `dcd3e3c04e5eef622f3cef6a5e4d8eda365a5af5`.

## Runtime and authority

Watch foreground explicit text Send calls the existing `AgentRunService`, `OpenAiCompatibleProvider`, context assembler, typed Tools, application editors/Planner, transaction coordinator and Room state. The Watch coordinator owns navigation, draft, busy state and an ephemeral request lease; it is not another Agent engine. D9-01 persisted thread/call/preview/result/action and prospective local provenance are reused. Room stays at v16; no migration, new table or schema change.

The C8 effective policy preserves READ/PLAN_PREVIEW local DENY or confirmation, clamps every supported write to at most REQUIRE_CONFIRMATION, and retains deny-only capabilities. The original persisted policy is unchanged. V2 business acknowledgement is checked by the existing write gate and also supplied to the existing D8 outbound composition. Conversation V3 consent and Provider readiness do not authorize a business write.

Provider host authorization is checked before resolving credentials, after resolution, and in Ktor `HttpSend` immediately before the engine send. Checks reread exact selected config, binding/revision/journal ownership, purpose-scoped secure-store availability, install/wipe state, default route, local selection generation and foreground ownership. Current handled thread tombstone/conflict metadata is checked before Send and at the actual-send boundary; no remote conversation content enters Provider context. A command pins its immutable lease. The C6 single-flight probe result is reused only for that exact config/binding generation. No redirects, new Adapter, plaintext credential fallback, Phone proxy or background Agent replay.

Confirm/Deny uses the exact persisted call ID and normalized displayed preview. Shared runtime revalidates permission, source facts, Planner branch and business write gates. Restart restores the same pending action rather than asking the Provider for another proposal. Offline/wipe can stop subsequent Provider continuation; committed Task/ToolResult/AgentAction/MutationId/ChangeLog success remains authoritative, and it is not replayed. Planner branches retain existing session-local ownership and stale semantics.

## Minimal UI and language decision

Agenda exposes Agent setup while entry is disabled and the Agent route when enabled. AI entry remains device-local default OFF. The command surface shows independent capability/readiness facts, typed runtime reason, recent real thread, text draft/Send, New conversation, persisted transcript/Tool truth, exact Watch Confirm/Deny, readiness retry and local binding approval/selection. It displays `请求内容可能通过未加密 HTTP 传输` for non-loopback HTTP. Credentialed HTTP remains rejected before secret resolution. No credentials/SecretRef/SAS/envelopes are exposed in UI.

Maintainer decision in this task: before an explicit speech-language selection, `und` denotes unspecified, text remains usable, and STT is disabled. There is no system-language fallback or support assertion for `und`. Passive inspection does not query language or request/recognize audio in that state. Explicit language selection is a local preference, without Room or sync. Speech produces only a draft candidate; Send/confirmation require separate explicit actions.

## Executed local evidence

Windows JDK 17, ASCII worktree `D:\codex\ASP-d9-03-03`, `GRADLE_USER_HOME=D:\gradle-home-agent`; commands include `--no-configuration-cache --console=plain`.

| Command / suite | Executed result |
| --- | --- |
| `:apps:wear:testDebugUnitTest` | 60/60; C8 ceiling, C6 proof reuse/generation/single-flight/backoff, language initialization and existing capability/privacy tests |
| `:shared:agent:desktopTest` | 48/48; guarded normal/streaming actual-send boundary, pre-secret rejection/races, unchanged shared adapter and typed Tools/Planner semantics |
| `:shared:database:desktopTest --tests '*AgentRunIntegrationTest' --tests '*AgentHistoryExplicitExportTest'` | 17/17 (14 shared runtime, 3 export/privacy); real desktop Room, shared stale/Planner/Undo/context/audit regressions |
| `ANDROID_SERIAL=emulator-5556 :apps:wear:connectedDebugAndroidTest` | 34/34 (20 runtime E2E + 3 UI + 11 predecessor platform/security), 0 skipped; required core tests assert FEATURE_WATCH |
| `:apps:wear:lintDebug` | Passed, including Compose StateFlow subscription checks |

The Watch job runs the entire native suite; the phone job keeps the three predecessor Wear platform suites and does not mislabel phone execution as Watch runtime acceptance. Native core uses the production Android Ktor engine, file-backed Room, actual AgentRunService/Tools/application transaction paths and production composable Confirm/Deny. Only the local HTTP Provider responses are deterministic fixture responses with real structured calls; no fake AgentRunService, ToolResult or HTTP engine.

Native coverage: structured read/final response/zero mutation and history restart; write + persisted WAITING_CONFIRMATION + exact preview after file close/reopen + Watch Confirm + one Task and linked AgentAction/D7 operation/diff; Watch Deny after restart; duplicate confirm/send and send while pending/running; local DENY; source-fact stale; displayed-preview mismatch; local confirmation with absent binding/offline; real Keystore + existing provisioning service install/wipe before continuation; consent ON cannot replace V2 gate; local consent OFF read/write keeps zero V3 outbox; UI blocker/runtime mappings; non-loopback HTTP warning/real request; redirect blocked with zero request at the redirect target; foreground loss rejects queued actions; tombstone before Send and during an in-flight response blocks continuation without revival or mutation. Native runtime fixtures inject deterministic IDs and clocks. No business/network replay on readiness recovery.

## Evidence limits and CI

No live external commercial Provider or physical Watch acceptance is claimed. The deterministic local structured fixture satisfies the task's real-engine/runtime Watch E2E requirement. On the actual local Wear API35/ext15 AVD, FEATURE_WATCH and text input support were true, default route had INTERNET + VALIDATED, on-device recognizer service was absent, language support returned UNSUPPORTED, and an explicit speech attempt returned ServiceUnavailable. No physical recognition success is inferred from a fake or absent service.

Implementation head `118a5ecc690808711c437fa76ae24072749d626d`: full CI [37214217488](https://github.com/fangbm/temvio/actions/runs/37214217488) passed all five jobs: build, desktop-windows, android-keystore, wear-keystore, agent-history-platform-e2e. Downloaded Wear XML confirms 31/31, zero failures/errors/skips. Actual Wear platform logs confirm absent on-device service and ServiceUnavailable rather than speech success. The earlier run 37212624712 failed Compose lint on StateFlow.value in composition; the subscription fix passed local lint and this full run.

The generic Android job's relay test explicitly skips without the enrolled fixture (its assumption is represented as a failure in exported XML); the required actual relay round trip passed the independent agent-history-platform-e2e job. The phone job's comma-separated Wear class filter executed only the first class on this runner. Finalization changes only CI selection to three individual class invocations with separate preserved reports; the Watch job already executed all three classes plus the real Watch runtime/UI tests. Finalization head `59d29ec9c14780a1f67917cb734ca58e3b4a1db9` passed full CI [37215176238](https://github.com/fangbm/temvio/actions/runs/37215176238); downloaded reports confirm phone Keystore 1/1, Provider slot 3/3 and capability 7/7. No physical Watch, external Provider or actual speech-recognition acceptance is claimed.

## Scope / remaining decisions

### Final-review follow-up: unsupported structured Tools

AGT-006 chat-only degradation is preserved. `UNSUPPORTED_TOOLS` stays terminal for automatic probe refresh, with no backoff or hidden retry; explicit retry or config/binding change resets the proof. It is a capability limitation rather than whole-Provider unavailability. Ordinary C6 facts and runtime blockers remain independent: a valid approved/current binding with readable required credential and usable route can authorize an explicit chat-only lease. `structuredCapability` passes the cached `ProviderProbeResult.Unsupported` to the unchanged shared `AgentRunService`, which supplies an empty Tool set. The existing Adapter omits `tools` for that empty set. No prose parsing or write fallback is added.

The UI displays `Chat-only · structured Tools unavailable` and permits Send only with ordinary readiness and no blocking runtime reason. Authentication, invalid configuration, missing credential, install/wipe, network and Provider failures still block. Regression evidence: Wear JVM 60/60; actual Wear instrumentation 34/34 (20 runtime, 3 UI, 11 predecessor), zero skipped; lint and AndroidTest compilation passed. New real-engine/Room tests prove ordinary chat and create/update prompts persist final assistant text with zero Tool schemas, ToolCall, AgentAction or D7 mutation, and HTTP 401/config rejection/network loss still produce zero command request. The full native suite preserves existing supported structured read/write/confirmation behavior. Full CI is rerun on this follow-up's exact head; its final head/run is recorded in the Draft PR delivery.

No new Tool/module, Domain rule, Room schema/migration, D7/V3 DTO or causal semantics, provisioning wire, Envelope/AAD/crypto/server implementation was changed. No D9-02 production receive/storage/upload composition, remote context ingestion, PhoneContextBridge, nearby delivery, remote approval, background Agent/notification or D10 UI redesign.

No new BLOCKED_BY_DECISION for this slice. OD-012 remains an independent OPEN release gate; D9-03 implementation does not resolve or bypass it. Draft PR must not be merged automatically.

## Changed files (24)

- `.github/workflows/ci.yml`
- `apps/wear/build.gradle.kts`
- `apps/wear/src/androidTest/kotlin/dev/agenticscheduler/wear/WearAgentRuntimeInstrumentedTest.kt`
- `apps/wear/src/androidTest/kotlin/dev/agenticscheduler/wear/WearAgentUiInstrumentedTest.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/WearMainActivity.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/agent/WatchPermissionPolicy.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/agent/WearAgentRuntimeComposition.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/agent/WearAgentScreen.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/agent/WearAgentSessionController.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearCapabilityService.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearProviderBindingSource.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearProviderProbe.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearReadiness.kt`
- `apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/WearReadinessComposition.kt`
- `apps/wear/src/test/kotlin/dev/agenticscheduler/wear/agent/WatchPermissionPolicyTest.kt`
- `apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearCapabilityServiceTest.kt`
- `apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearProviderProbeTest.kt`
- `apps/wear/src/test/kotlin/dev/agenticscheduler/wear/capability/WearReadinessTest.kt`
- `docs/D9_03_03_WEAR_AGENT_ACCEPTANCE_RECORD.md`
- `docs/ROADMAP_D5_D9.md`
- `docs/tasks/D9_03_03_WEAR_AGENT_RUNTIME.md`
- `shared/agent/src/commonMain/kotlin/dev/agenticscheduler/agent/provider/OpenAiCompatibleProvider.kt`
- `shared/agent/src/commonMain/kotlin/dev/agenticscheduler/agent/runtime/AgentRunService.kt`
- `shared/agent/src/commonTest/kotlin/dev/agenticscheduler/agent/provider/OpenAiCompatibleProviderTest.kt`
