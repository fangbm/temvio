> Status: **COMPLETE / MERGED** — [PR #28](https://github.com/fangbm/temvio/pull/28). Maintainer final review passed on 2026-10-05; D9-03 is complete.
> Implementation head: `f511eb42c6f39349f2caab4de6be5403d613ad31`.
> Merge commit: `27092ba2f88e39ba7601848f65b9312abe0be120`.
> Final CI: [37220143338](https://github.com/fangbm/temvio/actions/runs/37220143338), 5/5 jobs green.
> OD-012 remains OPEN, outside this slice as an independent production-sensitive local database at-rest protection/release gate.
> Implementation/acceptance evidence: [Wear acceptance record](../D9_03_03_WEAR_AGENT_ACCEPTANCE_RECORD.md).
> Maintainer-approved initialization: `und` means no selected speech language; text remains usable. No STT language query, permission request or recognition until explicit language selection. This is neither a supported-language assertion nor a system-language fallback.

The original implementation task below is retained as a historical contract.
Its branch/Draft/review workflow and interim status instructions refer to the
implementation phase; the completion header above records the current status.

D9-03-02 已完成最终复审并合并。

Baseline / merge commit：

`dcd3e3c04e5eef622f3cef6a5e4d8eda365a5af5`

现在开始：

# D9-03-03 — Wear Agent Runtime / Local Confirmation / Minimal UI / E2E

从最新：

`feature/d9-02-agent-sync`

创建独立 feature branch 和 Draft PR。

建议：

`codex/d9-03-03-wear-agent-runtime`

这是 D9-03 的最后 implementation slice。

本阶段目标是完成：

```text
Watch explicit user input
→ D9-03-02 readiness
→ existing Provider
→ existing shared AgentRunService
→ existing typed Tool registry
→ Watch-local permission ceiling
→ Watch-local confirmation
→ deterministic application / Planner
→ ToolResult
→ AgentAction
→ D7 audit / mutation journal
→ existing D9-02 Agent history persistence/export rules
```

不要开始 D10 视觉重构。

---

# 0. Authority

严格遵守：

- `docs/tasks/D9_03_00_WEAR_AGENT_PROVIDER_FREEZE.md`
  - C5
  - C6
  - C7
  - C8
- `docs/tasks/D9_03_01_PROVIDER_CREDENTIAL_PROVISIONING.md`
- `docs/tasks/D9_03_02_WEAR_CAPABILITY_NETWORK_STT.md`
- `docs/tasks/D9_AGENT_RUNTIME.md`
- `docs/AGENT_DECISIONS.md`
  - AGT-001…AGT-014
- D6 Planner contracts
- D7 history/audit/origin contracts
- D8 compatibility/write gate
- D9-02 Agent History protocol

D9-03-01/02 已是 predecessor。

不要重新实现：

- Agent runtime；
- Provider abstraction；
- Tool registry；
- Permission Engine；
- Planner；
- history；
- sync；
- credential provisioning。

Wear 是 **existing shared Agent runtime 的新 platform composition**。

---

# 1. Hard runtime boundary

仍然只有这一条合法 write path：

```text
Provider structured ToolCall
→ typed schema decode
→ existing Tool implementation
→ schema/domain validation
→ Watch effective Permission policy
→ normalized preview
→ Watch-local explicit confirmation when required
→ existing deterministic Application/Planner operation
→ ToolResult
→ AgentAction
→ D7 Mutation/ChangeLog/Sync journal
```

绝对禁止：

```text
LLM -> DAO
LLM -> Room
LLM -> raw repository write
LLM prose -> implicit write
Wear UI -> DAO
Wear UI -> raw SyncOperation
Wear-specific ad-hoc mutation handler
```

不要为 Watch 复制一套 Tool implementation。

---

# 2. Reuse existing AgentRunService

Watch 必须组合现有：

`AgentRunService`

以及现有：

- ContextAssembler；
- Agent state repository；
- Tool registry；
- typed Tools；
- ProviderConfig；
- OpenAiCompatibleProvider；
- AgentAction；
- pending confirmation machinery。

不要写：

`WearAgentRunService`

作为第二套 business/runtime engine。

允许做一个 platform composition / coordinator wrapper，例如：

```text
WearAgentRuntimeComposition
WearAgentSessionController
```

但它只能：

- 连接 existing shared services；
- 管理 lifecycle/UI state；
- enforce Watch-specific permission ceiling；
- expose typed UI state。

---

# 3. No Phone Agent proxy

Watch 必须独立发起 Provider call。

禁止：

```text
Watch prompt
→ Phone Agent
→ Provider
```

也禁止：

```text
Watch
→ Phone remote Tool approval
```

PhoneContextBridge 仍：

`DEFERRED`

Watch 必须使用本地：

- DB replica；
- ProviderConfig/binding；
- secure-store credential；
- AgentThread；
- Agent runtime；
- Tool execution；
- confirmation。

OS 自己通过 phone 转发网络流量不等于 Phone Agent proxy，这是允许的 platform networking。

---

# 4. Runtime gate: D9-03-02 readiness

新 Agent request 必须在提交 Provider 前读取当前 D9-03-02：

```text
aiEntrySupported
effectiveAiEntryEnabled
providerReady
requestReady
WearProviderRuntimeState
```

普通新 command：

```text
requestReady == false
=> 不得调用 Provider
```

展示对应 typed blocker。

例如：

- ENTRY_DISABLED
- NO_BINDING
- BINDING_APPROVAL_REQUIRED
- CREDENTIAL_UNAVAILABLE
- INSTALL_BLOCKED
- OFFLINE
- PROVIDER_UNAVAILABLE

不要用一个模糊 “Failed”。

---

# 5. Recheck before Provider call

不要只在 UI 点击时检查一次 readiness。

真正 Provider request 发送前必须重新检查当前：

- binding；
- ProviderConfig；
- credential ownership；
- secure-store availability；
- install/wipe state；
- network route；
- selected provider generation。

D9-03-01/02 state 在点击后发生变化时必须 fail closed。

特别：

```text
credential wipe races command
binding changes
provider config changes
network lost
revision changes
```

旧 snapshot 不能继续拿 credential 发请求。

---

# 6. Structured Tool capability probe

D9-03-02 readiness probe 已存在。

但冻结合同还要求：

> before write-capable execution, structured Tool capability must be valid.

因此 Watch runtime 在进入 write-capable Agent execution 时必须确认当前 binding generation 对应的 structured Tool probe 是 valid/current。

不要：

- 每个 read command 都强制重复无意义 probe；
- 用旧 config/binding 的成功 probe；
- unsupported Tool provider 上靠 prose fallback 做 write。

若 structured Tools unsupported：

- read/chat-only behavior可按 existing Agent runtime contract；
- 所有 write Tool execution必须不可用。

---

# 7. C8 — Watch permission ceiling

必须实现 Watch-specific effective permission clamp。

共享 AGT-003 默认仍为：

```text
READ                         ALLOW_DIRECT
PLAN_PREVIEW                 ALLOW_DIRECT

LOW_RISK_CREATE              REQUIRE_CONFIRMATION
SOURCE_FACT_UPDATE           REQUIRE_CONFIRMATION
PLANNING_PROFILE_CHANGE      REQUIRE_CONFIRMATION
SCHEDULE_APPLY               REQUIRE_CONFIRMATION
UNDO                         REQUIRE_CONFIRMATION

BULK_CHANGE                  DENY
DESTRUCTIVE                  DENY
EXTERNAL_SIDE_EFFECT         DENY
```

Watch first-alpha ceiling：

## Reads/previews

允许：

```text
READ         ALLOW_DIRECT
PLAN_PREVIEW ALLOW_DIRECT
```

如果本地 policy 被用户收紧成 DENY，则 DENY。

## Writes

以下在 Watch 上 **最多只能 REQUIRE_CONFIRMATION**：

```text
LOW_RISK_CREATE
SOURCE_FACT_UPDATE
PLANNING_PROFILE_CHANGE
SCHEDULE_APPLY
UNDO
```

即使共享/local policy 中未来出现：

`ALLOW_DIRECT`

Watch effective policy 仍必须 clamp 为：

`REQUIRE_CONFIRMATION`

用户可以进一步：

`DENY`

但绝不能把 Watch write 放宽为 direct。

## Always deny

```text
BULK_CHANGE
DESTRUCTIVE
EXTERNAL_SIDE_EFFECT
```

保持 DENY。

不要修改 Android/Desktop 的 policy 行为。

建议使用 composition-level：

`WatchPermissionPolicy`

包装现有 local permission source。

---

# 8. Watch-local confirmation only

Write confirmation 必须：

- 在 Watch 上显示；
- 在 Watch 上确认；
- 绑定 exact pending ToolCall；
- 绑定 exact normalized preview；
- 调 existing confirmation API。

禁止：

- Phone 远程批准；
- notification 一键批准；
- generic “Allow all”；
- Agent 自动确认；
- voice/STT candidate 自动确认；
- provider response saying “confirmed”。

确认是 explicit local user action。

---

# 9. Confirmation survives lifecycle/restart

与 Android/Desktop 一样，不能只存 Compose memory。

若已有：

```text
AgentToolCallState.WAITING_CONFIRMATION
previewJson
```

必须从 persisted Agent state 恢复。

至少覆盖：

```text
Provider proposes write
→ WAITING_CONFIRMATION persisted
→ Activity/process recreation/restart
→ Watch reopens thread
→ exact same pending preview visible
→ Confirm or Deny
```

不能：

- restart 后直接 execute；
- restart 后重新问 Provider 获得一个新 ToolCall 代替旧 pending；
- 丢掉 confirmation 导致 orphan pending state。

---

# 10. Confirmation current-state revalidation

确认不能 blind-apply preview。

使用 existing Tool/Planner semantics：

```text
preview
→ time passes / state changes
→ confirm
→ revalidate current state
```

如果 stale：

- return typed `Stale` / conflict equivalent；
- zero write；
- UI 提示重新请求/预览。

PlanBranch 必须直接复用 D6 stale semantics。

不要做 Watch-specific stale override。

---

# 11. Minimal Wear command UI

只做 D9 acceptance 所需最小 UI。

不要进入 D10 visual redesign。

至少需要：

## Entry

一个明确 AI/Agent 入口。

只有：

```text
effectiveAiEntryEnabled
```

时作为 enabled Agent entry。

若 disabled/unsupported：

显示 reason / setup state。

## Command input

必须支持 text。

可选支持 D9-03-02 STT button。

STT：

```text
SpeechCandidateResult.TextCandidate
```

只填入 command input / draft。

**绝不能自动 submit。**

用户必须再明确点 Send/Run。

## Runtime states

至少显示：

```text
idle
thinking
provider/network unavailable
Tool proposed
Tool running
Tool success
Tool failed
confirmation required
permission denied
stale
conflict
infeasible
final assistant text
```

可以紧凑适配 Watch，但不能只显示 LLM prose。

---

# 12. Thread handling

第一 alpha 至少支持：

- new AgentThread；
- 当前 thread conversation；
- send explicit command；
- persisted messages；
- ToolCall / ToolResult truth；
- pending confirmation restore。

不要为了 Wear 做完整 conversation-management redesign。

如果现有 AgentThread picker 在 Watch 上成本太高：

可以只实现：

```text
current/recent thread
+ New conversation
```

但必须使用真实 persisted AgentThread identity。

不要创建 platform-only ephemeral fake thread。

---

# 13. Context authority

Watch ContextAnchor 由 Watch 自己拥有。

所有当前事实继续通过 existing typed read Tools/Application facts 获取。

AGT-008 precedence不变：

```text
Domain/Application state
> committed ToolResult/ChangeLog/AgentAction
> runtime state
> recent raw conversation
> ContextSummary
> retrieved text
```

禁止因为 Watch 屏幕小而：

- 直接相信旧 assistant prose；
- 跳过 Tool read；
- 使用 Phone 作为 truth authority。

PhoneContextBridge 保持 deferred。

---

# 14. Provider configuration / binding UI boundary

D9-03-01/02 已实现 binding/provisioning/readiness。

本阶段只提供 runtime 所需的最小状态/入口，不重新设计 provisioning。

如果：

```text
NO_BINDING
BINDING_APPROVAL_REQUIRED
CREDENTIAL_UNAVAILABLE
INSTALL_BLOCKED
```

可以显示：

- 状态；
- 简短解决入口/说明。

不要复制 Provider secret 到普通 text field。

不要把 SecretRef 展示出来。

---

# 15. Plain HTTP risk UI — REQUIRED

这是本阶段的明确 acceptance item。

AGT-007 允许：

`explicit credential-free HTTP`

但如果 URL 是 non-loopback / non-HTTPS：

最终 Wear runtime UI 必须明确显示 plaintext transport risk。

不能只依赖隐藏配置。

至少在以下位置之一持续/明确显示：

- binding summary；
- readiness card；
- command screen before first use。

文案语义必须明确：

> 请求内容可能通过未加密 HTTP 传输。

不要误导用户称其为 private/secure。

credentialed HTTP 仍必须完全拒绝，并且拒绝发生在 secret resolution 前。

---

# 16. Redirect safety

D9-03-02 HTTP client 已：

`followRedirects = false`

保持。

不要为 runtime 打开 Provider redirect。

特别避免：

```text
HTTPS Provider
→ redirect HTTP
```

或：

```text
local HTTP
→ arbitrary remote endpoint
```

自动跟随。

---

# 17. Text command lifecycle

明确 command ownership。

推荐：

```text
draft
→ explicit submit
→ immutable submitted user message
→ Agent run
```

在 submitted 后：

- network loss不能自动重新 submit；
- foreground恢复不能自动重新 submit；
- probe恢复不能自动重新 submit。

如果 Provider request 已明确失败：

UI 可提供：

`Retry`

但 retry 必须是新的 explicit user action。

不要后台自动 replay command。

---

# 18. STT integration

D9-03-02 SpeechCandidate 只作为输入辅助。

允许：

```text
tap mic
→ local on-device STT
→ candidate text
→ populate draft
```

禁止：

```text
STT result
→ automatic AgentRun
```

也禁止：

```text
STT result
→ automatic confirmation
```

若 Watch AVD 不支持 STT：

text path 必须完整可用。

D9-03-03 acceptance **不能依赖 STT**。

---

# 19. Read acceptance path

必须实现真实 Watch-originated read path：

```text
Watch explicit text input
→ Watch AgentRunService
→ real Provider adapter
→ structured ToolCall
→ typed READ Tool
→ local Domain/Application state
→ ToolResult
→ Provider continuation/final answer
→ persisted Agent history
```

Acceptance Tool 建议使用稳定 read，例如：

```text
task.list
history.timeline
```

必须证明：

- Phone Agent absent；
- no Phone proxy；
- Provider request originates from Watch runtime；
- structured Tool truth displayed；
- zero business mutation。

---

# 20. Write acceptance path

必须实现至少一个真实 confirmation-required business write。

推荐：

`task.create`

路径：

```text
Watch explicit command
→ Provider structured task.create
→ existing Tool validation
→ Watch effective policy REQUIRE_CONFIRMATION
→ persisted WAITING_CONFIRMATION
→ normalized preview rendered
→ user taps Confirm on Watch
→ existing Task application command
→ committed Task
→ ToolResult SUCCESS
→ AgentAction SUCCEEDED
→ D7 MutationRecord
→ ChangeLog
→ sync journal as applicable
→ Provider continuation/final assistant response
```

验证：

- exact MutationId linkage；
- origin为现有 Agent origin；
- no duplicate write；
- no Phone approval。

---

# 21. Deny path

同一个 confirmation-required write 必须覆盖：

```text
proposal
→ Watch preview
→ Deny
```

结果：

- zero business write；
- ToolCall/ToolResult/AgentAction 状态符合 existing Agent semantics；
- restart 后不能重新执行；
- Provider prose不能声称成功覆盖事实。

---

# 22. Watch permission DENY path

至少测试一个 write capability 被 Watch/local policy设为：

`DENY`

Provider 即使提出合法 ToolCall：

```text
zero preview execution
zero mutation
PermissionDenied truth
```

以及：

- model不能通过换 prose 重试绕过；
- ALLOW_DIRECT persisted value若存在也被 Watch ceiling clamp。

---

# 23. Planner path

至少做 deterministic automated regression：

```text
planner.preview...
```

read/preview 可直接。

`planner.applyBranch`

必须：

`REQUIRE_CONFIRMATION`

且确认时复用 existing stale check。

不强制真实 Provider E2E 手工跑完整 Planner，只要 shared runtime + Watch permission composition有 integration coverage。

---

# 24. Undo

`history.undo`

在 Watch：

`REQUIRE_CONFIRMATION`

且继续使用现有：

- D7 compensating mutation；
- existing Agent write gate；
- compatibility policy。

不要让 Undo 绕过 V2 compatibility acknowledgement。

---

# 25. D7 origin / audit

所有 Watch Agent business write 必须产生与 Android/Desktop 同类：

- `AgentAction`
- committed MutationId
- D7 ChangeLog
- mutation journal
- Agent origin linkage

不要新造：

`WEAR_AGENT` business origin

除非现有 type 本来允许 platform metadata。

语义 origin仍是 Agent action。

Platform可作为 UI/runtime diagnostics metadata，但不能改 D7 causal semantics。

---

# 26. Existing V2 business-write compatibility gate

Watch Agent write 继续经过现有：

`all-devices-upgraded / business compatibility acknowledgment`

如果 gate 不允许：

```text
zero business write
```

不得因为：

- Watch local confirmation；
- Provider credential；
- D9-02 conversation consent；
- Watch enrollment

而绕过 V2 gate。

这些是独立设置。

---

# 27. D9-02 Agent history integration

Watch 是真实独立 Agent replica。

本阶段必须验证本地产生：

- AgentThread；
- AgentMessage；
- AgentToolCall；
- AgentToolResult；
- AgentAction；
- TurnFinalized / provenance所需数据；

能进入现有 D9-02 history machinery。

但是：

## Conversation sync consent

继续遵守 per-SyncSpace Agent conversation sync consent。

OFF：

- 本地 history正常；
- 不自动上传 V3。

ON 且其他 gate允许时：

- 使用现有 D9-02 pipeline。

## OD-012

仍 OPEN。

不要在 D9-03-03：

- enable production-sensitive V3；
- 宣称 OD-012 solved；
- 改 V3 crypto/storage policy。

只验证组合边界没有被 Wear runtime破坏。

---

# 28. Credential/provisioning separation from V3

明确测试：

以下绝不能进入 Agent history V3：

- provider credential；
- SecretRef；
- ProviderCredentialEnvelope；
- provisioning SAS；
- binding approval state；
- install journal；
- permission setting；
- readiness/network state；
- speech audio；
- RECORD_AUDIO state。

ProviderConfig secret material同样不能进 transcript/context。

---

# 29. Restart acceptance

至少覆盖真实 file-backed restart：

### Pending confirmation restart

```text
ToolCall WAITING_CONFIRMATION
→ process/runtime recreation
→ restore
→ preview same call
→ confirm/deny
```

### Completed read restart

history仍完整。

### Completed write restart

- Task/Event state remains；
- ToolResult/AgentAction remain；
- no duplicate mutation。

### Credential/binding restart

reuse D9-03-01/02 durable state；

不得要求重新自动 provision。

---

# 30. Offline behavior

测试：

```text
network available
→ command screen READY
→ network lost
```

结果：

- `aiEntrySupported` remains；
- `userEnabledAiEntry` remains；
- existing draft remains；
- existing local history remains；
- new provider submission blocked；
- pending confirmation for already-proposed local Tool保持可解释。

对于 confirmation：

如果真正 execution 不需要 Provider，但 underlying business write需要 compatibility/local gate，则按 existing semantics执行。

确认成功后如果 Provider continuation 因离线失败：

- business write事实仍是成功；
- ToolResult/AgentAction必须 truthful；
- UI不能把 committed write回滚成 failed；
- 后续 Provider continuation不可自动重放 write。

---

# 31. Wipe/replacement race

真实 integration test：

```text
Agent command ready
→ credential wipe/replacement begins
→ Provider call about to resolve secret
```

必须 fail closed。

以及：

```text
pending confirmation exists
→ credential later wiped
→ user confirms local Tool
```

这里不要错误地把 Provider credential readiness当作 local Tool execution authority。

如果 existing pending ToolCall已经经过 Provider proposal并持久化：

- confirmation仍按 Tool/current business state + permission/write gate判断；
- Provider secret只影响后续 Provider continuation；
- credential wipe不能让已确认 business write失真；
- 后续 Provider continuation可以 structured unavailable。

记录并测试这个边界。

---

# 32. Concurrent command protection

Watch 屏幕/UI 不允许同一 thread 并发 root Agent turns绕过 D9-02 fork semantics。

复用 existing Agent runtime serialization。

至少覆盖：

- rapid double tap Send；
- speech candidate + text send race；
- submit while run in progress；
- submit while confirmation pending。

不得产生：

- accidental duplicate write；
- hidden second confirmation；
- continuation across unresolved concurrent fork。

---

# 33. Minimal UI security

不要在 UI/log中显示：

- raw API key；
- SecretRef；
- ProviderCredentialEnvelope；
- HPKE material；
- sync credential；
- recovery secret。

Provider errors只显示 redacted code / human-safe mapped message。

不要把 HTTP response body直接显示为 infrastructure error。

Assistant text本身按普通 conversation显示，但不能覆盖 structured Tool truth。

---

# 34. No notification/background Agent execution

本阶段不增加：

- background Agent wake；
- scheduled autonomous Agent；
- notification-triggered Tool；
- background voice listening；
- always-on mic。

所有 Agent command均 explicit foreground user action。

---

# 35. No new Tool surface

不要添加新 Tool。

继续使用 D9 v1：

Reads:

```text
calendar.list
task.get
task.list
history.timeline
history.getMutation
history.getEntityChanges
planner.previewFullReplan
planner.previewLocalReflow
```

Writes：

```text
event.create
event.update
task.create
task.update
planningProfile.update
planner.applyBranch
history.undo
```

没有 delete Tool。

不要为了 Watch 加：

```text
watch.quickAdd
watch.execute
watch.command
```

之类旁路。

---

# 36. No DB migration by default

当前 Room v16。

优先完全复用现有：

- Agent tables；
- Provider config；
- permission state；
- D9-02 state；
- D9-03 provisioning state。

D9-03-03 不应因为 UI/runtime composition bump schema。

如果发现必须新增 durable state：

先判断是否真的不能由现有 AgentToolCall / settings表达。

非必要不要迁移。

---

# 37. Required JVM/integration tests

至少覆盖：

## Watch permission ceiling

- READ local ALLOW_DIRECT → ALLOW_DIRECT；
- READ local DENY → DENY；
- write local REQUIRE_CONFIRMATION → REQUIRE_CONFIRMATION；
- write local ALLOW_DIRECT → still REQUIRE_CONFIRMATION；
- write local DENY → DENY；
- BULK/DESTRUCTIVE/EXTERNAL always DENY。

## Runtime gate

- requestReady false → zero Provider call；
- binding changes before send → fail；
- credential wiped before secret resolution → fail；
- stale readiness generation → fail；
- current readiness READY → request allowed。

## Read

- Provider structured read Tool；
- correct ToolResult；
- no mutation；
- conversation persistence。

## Write

- proposed Tool；
- confirmation pending；
- Confirm；
- exactly one business write；
- MutationId / AgentAction / ChangeLog linkage。

## Deny

- preview → deny → zero mutation。

## Restart

- pending confirmation restored；
- confirm after restart；
- deny after restart；
- no duplicate writes。

## Stale

- source state changes between preview and confirmation；
- typed Stale/Conflict；
- zero blind apply。

## Concurrency

- double send；
- send during running；
- send during confirmation；
- no hidden duplicate turn/write。

---

# 38. Required Watch UI tests

Compose/instrumentation tests at minimum：

- AI entry disabled state；
- OFFLINE；
- NO_BINDING；
- CREDENTIAL_UNAVAILABLE；
- READY；
- text input/send；
- STT candidate populates draft but does not auto-submit；
- Tool proposed；
- confirmation preview；
- Confirm；
- Deny；
- permission denied；
- Tool succeeded/failed；
- stale；
- provider unavailable；
- plaintext HTTP warning。

不要要求 final D10 styling。

重点是可操作、可读、不会误导。

---

# 39. Real Wear / emulator E2E — REQUIRED

至少在 Wear emulator/AVD 完成：

## E2E-A — text read

必须不用 STT：

```text
explicit text command
→ Provider
→ structured read Tool
→ ToolResult
→ final answer
```

若 CI 不能访问真实公网 Provider：

允许使用真实 Android HTTP engine + deterministic local OpenAI-compatible fixture server。

但必须：

- 走真实 Ktor Android client；
- 走真实 AgentRunService；
- 走真实 Tool；
- 走真实 Room；
- 不是 fake AgentRun result。

这属于 runtime E2E。

## E2E-B — confirmation write

同样用真实 runtime：

```text
text command
→ Provider fixture
→ task.create
→ persisted confirmation
→ Watch UI Confirm
→ actual Task persisted
→ actual D7 audit
```

Provider fixture可以 deterministic，但不能 fake掉 Agent runtime/Tool/application。

## E2E-C — deny

同一 proposal：

→ Deny
→ zero Task write。

---

# 40. Real-provider evidence

如果可安全配置 CI/manual real Provider：

可以额外做 real Provider read。

但不要把真实第三方 API key放进 repo/artifact/log。

不要求 public CI secret。

D9-03-03 merge acceptance的 deterministic local Provider fixture E2E可以作为自动 gate，前提是 runtime/network/Tool/DB全是真实路径。

如果手工 real Provider evidence可获得：

记录但不要阻塞 automated reproducibility。

---

# 41. Physical Watch claim boundary

不要声称 physical Watch acceptance，除非真的在物理设备测了。

Wear AVD acceptance应明确写：

`Wear emulator acceptance`

STT仍不作为 D9-03-03 mandatory success path。

---

# 42. Plain HTTP E2E

对 explicit credential-free HTTP：

至少测试：

- UI显示 plaintext warning；
- zero credential read；
- zero Authorization header；
- redirect disabled；
- command body只包含正常 Agent request/context，不包含 secret。

另测：

credentialed HTTP：

- Provider call前 reject；
- zero secret resolution；
- zero network call。

---

# 43. D9-02 separation E2E

至少测试：

### conversation consent OFF

Watch completed read/write turn：

- local Agent history完整；
- no Agent V3 upload/outbox claim；
- business D7 write semantics独立。

### provisioning state

验证 Provider credential/binding：

- 不进入 V3 payload；
- 不改变 conversation consent。

### V2 business compatibility

确认：

- conversation consent ON不能代替 V2 write gate；
- Provider credential ready不能代替 V2 write gate。

---

# 44. Acceptance record

创建：

`docs/tasks/D9_03_03_WEAR_AGENT_RUNTIME.md`

以及：

`docs/D9_03_03_WEAR_AGENT_ACCEPTANCE_RECORD.md`

记录：

- exact baseline；
- runtime composition；
- Watch permission clamp；
- confirmation lifecycle；
- UI states；
- Provider/read/write E2E；
- D7 evidence；
- D9-02 separation；
- restart/offline/wipe evidence；
- Android/Wear instrumentation；
- CI；
- physical-vs-emulator evidence limits。

---

# 45. D9 completion status

D9-03-03 implementation完成后先写：

```text
D9-03-03 IMPLEMENTED / AWAITING REVIEW
D9-03 IMPLEMENTATION COMPLETE / AWAITING FINAL REVIEW
```

不要自行标：

```text
D9 COMPLETE
```

直到 maintainer final review。

OD-012 仍必须保持：

`OPEN`

D9 implementation completion ≠ production-sensitive V3 release approval。

---

# 46. Scope fence

Do NOT implement：

- D10 full UI redesign；
- final navigation redesign；
- design system rewrite；
- Phone Agent proxy；
- PhoneContextBridge；
- nearby/Data Layer credential provisioning；
- cloud STT fallback；
- always-on voice；
- remote confirmation；
- autonomous/background Agent；
- new Tools；
- new Provider SDK；
- delete Tool；
- D7 semantic changes；
- D9-02 protocol changes；
- ProviderCredential wire changes；
- OD-012 resolution；
- production V3 enablement。

---

# 47. CI / acceptance gate

至少要求最终 CI 继续覆盖现有：

- build；
- desktop-windows；
- android-keystore；
- wear-keystore；
- agent-history-platform-e2e。

把新的 Wear D9-03-03 runtime tests放进可持续 CI。

不得用：

- skipped test；
- compile-only；
- fake “success” fixture；

替代核心 E2E acceptance。

---

# 48. Delivery

完成后：

1. push Draft PR；
2. 保持 Draft；
3. 不 merge；
4. 不开始 D10；
5. 返回：
   - PR URL；
   - exact head SHA；
   - changed files；
   - DB migration：yes/no；
   - Watch permission ceiling test counts；
   - Agent runtime integration counts；
   - Wear UI/instrumentation counts；
   - E2E read/write/deny结果；
   - restart/offline/wipe race结果；
   - D7 audit evidence；
   - D9-02/V2 separation evidence；
   - real Provider evidence（若有）；
   - full CI run；
   - any `BLOCKED_BY_DECISION`。

若冻结合同和现有 runtime之间真的存在无法唯一决定的安全/语义冲突：

标记：

`BLOCKED_BY_DECISION`

不要猜。

PR 保持 Draft，等待最终人工复审。
