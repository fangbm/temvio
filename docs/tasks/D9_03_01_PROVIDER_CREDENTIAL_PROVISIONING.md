# D9-03-01 — Provider Credential Provisioning

Implementation evidence: [acceptance record](../D9_03_01_PROVISIONING_ACCEPTANCE_RECORD.md).
Review: [Draft PR #26](https://github.com/fangbm/temvio/pull/26).
Status: implementation submitted for review; C2 post-expiry marker retention
**BLOCKED_BY_DECISION (OD-059)**. The original approved Task Spec follows unchanged.
OD-012 remains OPEN. Do not merge or start the next slice.

---

D9-03-00 已完成最终人工复审并合并。

Baseline / merge commit：

`82f4c62e4ca772e9b1daf192760e2ff835067bcc`

现在开始：

# D9-03-01 — Provider Credential Provisioning

从最新 `feature/d9-02-agent-sync` 创建独立 feature branch 和 Draft PR。

建议 branch：

`codex/d9-03-01-provider-credential-provisioning`

本阶段实现冻结的 **C1–C5**。

不要开始 D9-03-02 capability/network/STT。
不要开始 D9-03-03 Wear Agent runtime/UI。
不要开始 D10。

---

## 0. Authority

严格以这些已冻结合同为准：

- `docs/tasks/D9_03_00_WEAR_AGENT_PROVIDER_FREEZE.md`
- `docs/AGENT_DECISIONS.md` AGT-014
- `docs/SYNC_SECURITY_DECISIONS.md` SYN-018
- `docs/OPEN_DECISIONS.md` OD-042 / OD-058
- SYN-006A / SYN-007A / SYN-009
- existing D8 enrollment/device directory/HPKE contracts

不得自行修改冻结语义。

特别保持：

- DeviceId 是 exact opaque D8 value，不是 UUID；
- ProviderConfigId 是现有 UUIDv7；
- mailbox Option A only；
- relay 不拿 binding metadata 或 binding digest；
- target-owned credentialRevision；
- prepared secret slot 必须 platform-issued / purpose-scoped；
- credential provisioning 不属于 V1/V2/V3 workspace sync；
- OD-012 仍 OPEN。

---

# 1. C1 — 实现 exact ProviderCredential wire / codec / HPKE

在现有 sync/application 边界实现：

- `ProviderCredentialEnvelopeV1`
- `ProviderCredentialPlaintextV1`
- `ProviderCredentialAcknowledgementV1`
- `WearProviderBindingMetadataV1`
- strict codec
- exact HPKE context builder
- provisioning SAS builder

直接把 D9-03-00 已提交的 fixtures 提升为真正的 golden tests。

必须验证：

- exact field set；
- unknown field reject；
- duplicate field reject；
- malformed UTF-8 reject；
- canonical base64url；
- encoded/decoded bounds；
- revision integer rules；
- exact opaque mixed-case DeviceId；
- ProviderConfigId UUIDv7；
- inner/outer target/config/revision equality；
- no trimming/lowercasing/normalization of DeviceId。

HPKE：

- 必须复用现有 Tink X25519/HKDF-SHA256/AES-256-GCM base mode；
- 不添加新 crypto primitive；
- 不创建第二套 device key；
- 不改 D8 workspace Envelope/AAD；
- exact `ProviderCredentialContextV1` 按 freeze packet 实现。

Golden vector 必须真实解密通过。

以下必须失败且零副作用：

- wrong private key；
- ciphertext tamper；
- wrong target context；
- target DeviceId case-normalized；
- wrong providerConfigId；
- wrong revision；
- unknown version。

---

# 2. Provisioning SAS

实现冻结的独立 8 位 comparison code。

SAS 输入必须精确绑定：

- provisioner DeviceId exact bytes；
- target DeviceId exact bytes；
- target ProviderConfigId；
- credentialRevision；
- canonical encrypted envelope SHA-256；
- canonical locally reconstructed target binding metadata SHA-256。

Source 和 target **各自本地计算** binding metadata/hash。

不要通过 server 传：

- baseUrl；
- model；
- maxContextUnits；
- reservedOutputUnits；
- streamingSupported；
- toolCallingSupported；
- credentialRequired；
- canonical binding JSON；
- binding digest。

D8 pairing SAS approval不能复用为 credential approval。

实现 API 可以暴露 comparison code / approval transition，但本阶段不做最终 Wear UI。

---

# 3. Local persistence — Room v15 → v16

当前 local DB schema = 15。

本阶段授权 **non-destructive v15→v16 migration**。

鉴于 D9/D9-02 已经因为 Room validator size 使用 explicit SQL catalog，本阶段继续使用同类 explicit extension schema，不要为了这几个表重新扩大 Room `@Entity` validator。

建议新增独立：

`ProviderCredentialProvisioningSchema`

并接入：

- fresh database callback；
- onOpen validation；
- Android factory；
- Desktop factory；
- v15→v16 migration；
- schema parity tests。

不要 destructive fallback。

## 必须持久化的概念

至少覆盖：

### A. target credential revision state

per:

`(targetDeviceId, providerConfigId)`

持久化：

- highestReservedRevision
- highestAcceptedRevision
- rejectionFloor
- live reservation revision
- selected provisioner DeviceId
- reservation/install state
- accepted envelope digest where frozen semantics require it

初始：

`0 / 0 / 0`

first reservation：

`1`

next：

`max(reserved, accepted, floor) + 1`

禁止 HLC/time/server cursor 参与 revision authority。

### B. target approval/reservation state

必须能 durable 绑定：

- exact target config；
- selected provisioner；
- revision；
- envelope digest；
- local binding digest；
- comparison approval state。

旧的 generic approved boolean 不能授权新的 envelope/binding。

### C. install journal

必须支持 crash recovery：

- prepared slot identity/ref
- config/revision
- prior active ref if replacement
- install lifecycle state
- retired/cleanup state

至少能表达：

- PREPARED_IMPORT
- SECRET_IMPORTED
- METADATA_COMMITTED
- CLEANUP_PENDING
- completed/rejected equivalent terminal state

具体 enum/name 可以按代码风格调整，但语义必须完整。

### D. source delivery outbox

source 在上传前必须 durable 保存：

- target DeviceId
- target ProviderConfigId
- revision
- exact canonical envelope bytes
- envelope digest
- delivery state

网络失败/restart 后必须 retry **exact same envelope bytes**。

不能重新 HPKE encrypt 后把随机不同 ciphertext 当同一次 retry。

---

# 4. C3 — revision / reservation service

实现 target-owned reservation service。

要求：

- target 是唯一 allocator；
- one live reservation per config；
- reservation pins selected source；
- new reservation cancels old reservation；
- source不能自行 mint revision；
- higher unreserved revision reject；
- wrong source reject；
- cancelled reservation reject；
- stale revision reject；
- same revision/same accepted credential idempotent；
- same revision/different credential integrity conflict；
- no LWW。

Same revision / rerandomized HPKE ciphertext：

允许先 decrypt，然后把 credential bytes transiently 与 secure-store 当前 credential 比较。

不要把 plaintext credential hash 持久化到 Room。

## wipe/removal

删除 binding / wipe / observed revocation 时：

1. durable advance rejection floor；
2. cancel live reservation；
3. disable credential use；
4. clear active credential association；
5. durable schedule secure-store deletion。

stale replay 永远不能恢复 binding。

barrier revision 可以跳过，且不代表某个 credential 曾被 installed。

restart 后 counters/floor 不能 reset。

---

# 5. C4 — tracked Provider credential secure-store slot

不要直接用现有：

`PlatformSecretStore.importSecret()`

来声称 crash-safe installation 已完成。

新增一个**专门的 Provider credential tracked-import boundary**。

名字可以按架构风格确定，但必须满足：

### Prepare

platform secure store 自己分配：

- fresh；
- unique；
- Provider-purpose；
- one-install；

prepared slot/reference。

Application 不得传入任意已有 `SecretReference` 让它写进去。

### Journal-before-import

顺序必须是：

1. platform prepare fresh slot；
2. Room durable journal 记录 slot/install identity；
3. transient credential import 到这个 prepared slot；
4. DB transaction recheck reservation/binding/floor；
5. atomically publish SecretRef + accepted revision + journal ownership；
6. binding becomes ACTIVE/installed；
7. cleanup old credential if replacement。

不能声称 secure store + SQLite 原子事务。

## Slot isolation

必须证明 Provider prepared slot 不能覆盖：

- AMK；
- SyncSpace keys；
- pairing HPKE private keys；
- RecoverySecret；
- DeviceCredential；
- active Provider credential；
- unrelated generic secret。

Cleanup 也只能操作 journal-owned Provider prepared/retired slots。

不要 enumerate-and-delete 整个 secure store。

---

# 6. Crash/restart recovery

必须真实实现并测试以下 phase：

### crash before import

journal 有 prepared slot，但没有 installed credential。

restart：

- no active new binding；
- cleanup prepared slot；
- old binding保持。

### crash after import / before DB publish

restart：

- journal 能定位 imported prepared slot；
- metadata未 commit → cleanup；
- old ref/revision保持；
- new ref不可用于 Provider。

### DB commit result unknown

restart 必须先检查 durable metadata/journal。

如果 new ref 已经成为 committed active ref：

- preserve it；
- continue old-ref cleanup；
- 不能因为 caller 没收到成功就删掉 active secret。

否则：

- preserve old binding；
- cleanup prepared new slot。

### DB committed / old deletion failed

new credential 保持 active。

old ref cleanup durable retry。

不能 rollback metadata 回旧 credential。

---

# 7. C5 — WearProviderBinding / existing ProviderConfig

不要创建第二套 Provider abstraction。

复用现有 `ProviderConfig`。

本阶段实现 provisioning 所需的 local binding/approval contract，但**不要把 Wear Agent runtime 接起来**。

Target locally owns：

- providerConfigId
- baseUrl
- model
- context/output capacity
- streamingSupported
- toolCallingSupported
- credentialRequired

`SecretReference` 仍然只在 local ProviderConfig / provisioning state。

canonical `WearProviderBindingMetadataV1` 不包含 SecretRef。

Source 构造 SAS binding metadata 时：

- config ID 使用 **target request 的 providerConfigId**；
- provider/model/capability values 使用用户明确选择的 source local ProviderConfig；
- source ProviderConfig 自己的 ID 不得带过去。

Target 使用已经 locally approved 的 target ProviderConfig/binding 独立重建。

metadata 不匹配 → SAS 不同 → 不允许 install。

本阶段不自动 select Provider，不发 Provider request，不接 Agent runtime。

---

# 8. C2 — dedicated opaque server mailbox

当前 server schema = 9。

本阶段授权 **Server migration V10**，只新增 Provider credential provisioning mailbox/request state。

不要修改：

- encrypted workspace operation tables；
- enrollment key packages；
- rotation package semantics；
- SyncSpace cursor；
- V1/V2/V3。

## Server只允许看 routing metadata + opaque envelope

允许的 server-visible 内容仅限必要 routing/idempotency data，例如：

- account
- target DeviceId
- provisioner DeviceId
- providerConfigId
- credentialRevision
- opaque canonical encrypted envelope bytes
- encrypted-envelope digest
- delivery state/timestamps/expiry
- ACK result metadata

绝不能存：

- provider credential plaintext；
- credential plaintext hash；
- baseUrl；
- model；
- binding JSON；
- binding digest；
- SecretRef；
- ProviderConfig payload。

## Non-secret target request

Watch/target 发布 reservation request。

Server 必须确认 caller：

- authenticated；
- ACTIVE；
- request.targetDeviceId == authenticated device；
- selected provisioner ACTIVE；
- same account。

Request至少绑定：

`targetDeviceId + providerConfigId + credentialRevision + selected provisioner`

Same request repeat idempotent。

同一个 target/config/revision 改 provisioner不得覆盖；必须 conflict / 新 revision。

## Source discovery

Source 只能 fetch：

- same account；
- assigned to itself；
- target still ACTIVE；
- nonexpired requests。

## Upload

Source 上传 exact opaque canonical envelope。

事务内再次确认：

- source ACTIVE；
- target ACTIVE；
- same account；
- source == reserved provisioner；
- request still live；
- envelope routing identity 与 request 一致。

Same delivery identity + same exact bytes：

idempotent。

Same identity + changed bytes：

explicit conflict。

Server不能 decrypt/re-encrypt。

## Target fetch

Target只能读取自己的 mailbox。

fetch 不等于 install。

HTTP success 不更新 local target accepted revision。

## ACK

只有 target authenticated actor 能 ACK。

ACK 仍然只是 informational。

Server ACK 绝不能成为 credential install authority。

ACK 后：

- ciphertext 可删除；
- bounded routing/ACK metadata 可保留至 frozen 7-day deadline。

## Expiry

Frozen TTL：

**7 days**

未 ACK ciphertext 到期后：

`DELIVERY_EXPIRED`

绝不能视作 installation。

重新 provision 必须 target 新建更高 revision。

时间逻辑请做到可 deterministic test；不要写需要真实等 7 天的测试。

---

# 9. Client mailbox transport

不要复用 workspace SyncTransport cursor。

新增独立 credential provisioning transport/repository contract。

必须实现：

- target publish reservation request；
- source fetch assigned request；
- source upload exact envelope；
- target fetch mailbox；
- target ACK。

Credential transport不能：

- 改 V3 consent；
- 改 V2 business compatibility；
- 改 Agent dots；
- 改 D7 DVV；
- 走 Agent history outbox。

---

# 10. End-to-end target install flow

实现到以下边界：

```text
target locally approves binding
→ target reserves revision/source
→ target publishes request

source fetches request
→ user explicitly selects local source ProviderConfig/credential
→ source constructs target binding metadata
→ source HPKE encrypts credential
→ source persists exact ciphertext outbox
→ source uploads opaque envelope

target fetches envelope
→ strict routing validation
→ HPKE decrypt
→ inner/outer validation
→ revision/reservation validation
→ independently computes SAS

NO IMPORT YET

user comparison approval is represented explicitly through service API/test
→ prepared Provider slot
→ journal persist
→ secure-store import
→ DB publish accepted revision/ref/state
→ old credential cleanup if applicable
→ ACK
```

本阶段没有最终 UI，所以 comparison approval 用明确的 application service method / test action 表达。

不能自动批准。

---

# 11. Required tests

至少覆盖：

### Wire / crypto

- committed positive fixture；
- opaque mixed-case DeviceId；
- UUID validator accidentally applied to DeviceId must fail test；
- exact ProviderConfigId validation；
- all committed negative fixtures；
- unknown fields；
- duplicate fields；
- invalid UTF-8；
- padded/noncanonical base64；
- min/max bounds；
- >4096 credential；
- wrong key；
- tamper；
- wrong context；
- normalized DeviceId context failure。

### SAS

changing any of:

- source DeviceId；
- target DeviceId；
- config；
- revision；
- envelope；
- binding baseUrl/model/budget/capabilities

must change comparison result.

Server receives neither binding nor binding digest.

### Revision

- first revision = 1；
- target-only allocation；
- two sources serialize；
- wrong source；
- cancelled reservation；
- future unreserved；
- rollback；
- same revision/same secret；
- same revision/different secret；
- replacement；
- wipe floor；
- stale replay；
- restart；
- overflow；
- retained identity + lost local state fails closed.

### Secure store / journal

- arbitrary SecretReference cannot be import destination；
- wrong-purpose slot reject；
- reused slot reject；
- crash before import；
- crash after import；
- DB rollback；
- unknown commit outcome；
- old cleanup failure；
- replacement；
- wipe race；
- active slot never deleted after lost ACK；
- cleanup doesn't touch D8 key material.

Add real Android/Wear Keystore instrumentation for prepared-slot isolation and recreation/restart behavior.

### Local DB

Current v15 → v16 migration:

- populated migration；
- old D9/D9-02 data byte-for-byte preserved where expected；
- fresh v16 schema parity；
- restart state recovery；
- no credential plaintext in SQLite scan；
- no plaintext credential digest.

### Server V10 / real PostgreSQL

Real PostgreSQL acceptance must cover:

- V9→V10 migration；
- fresh V10；
- active same-account request；
- cross-account reject；
- revoked source reject；
- revoked target reject；
- wrong source reject；
- request retry；
- exact upload retry；
- changed bytes conflict；
- target-only fetch；
- target-only ACK；
- 7-day expiry；
- ACK ciphertext removal；
- server restart；
- concurrent source/upload races；
- public-table canary scan for:
  - credential plaintext；
  - binding baseUrl/model；
  - binding JSON；
  - binding digest；
  - SecretRef.

Server remains opaque.

---

# 12. Acceptance documentation

Create:

`docs/tasks/D9_03_01_PROVIDER_CREDENTIAL_PROVISIONING.md`

and a concise completion/acceptance record if useful.

Record:

- exact local schema migration;
- server migration;
- fixture tests;
- crypto tests;
- revision tests;
- secure-store crash matrix;
- real PostgreSQL matrix;
- Android/Wear Keystore evidence;
- remaining D9-03-02/03 scope.

Update roadmap/task status only to:

`D9-03-01 IMPLEMENTED / AWAITING REVIEW`

until manual review.

Do not mark full D9-03 complete.

---

# 13. Scope fence

Do NOT implement in this PR:

- Wear Agent runtime；
- Agent command UI；
- STT；
- network capability service；
- provider readiness/probe UI；
- permission UI；
- D9-03-02；
- D9-03-03；
- D10；
- nearby/Data Layer credential delivery；
- Phone Agent proxy；
- remote Tool approval；
- new Provider SDK；
- new Agent Tool；
- D7 semantic changes；
- D9-02 V3 changes；
- production-sensitive V3 enablement；
- OD-012 solution。

Do not add credential plaintext logging/debug dumps.

---

# 14. Delivery

When implementation is complete:

1. push Draft PR;
2. keep Draft;
3. do not merge;
4. do not start D9-03-02;
5. return:
   - PR URL;
   - exact head SHA;
   - local DB migration version;
   - server migration version;
   - changed files summary;
   - targeted test counts;
   - PostgreSQL E2E counts;
   - Android/Wear Keystore results;
   - full CI run;
   - any remaining `BLOCKED_BY_DECISION`.

If frozen contracts expose a genuine ambiguity affecting security, persistence, revision authority or mailbox semantics, mark it `BLOCKED_BY_DECISION` rather than guessing.
