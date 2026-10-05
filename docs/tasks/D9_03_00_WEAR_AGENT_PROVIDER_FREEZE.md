# D9-03-00 — Wear Agent / Provider Provisioning Contract Freeze

Status: **FROZEN — maintainer approved 2026-10-04**.
C1–C8 are approved with the DeviceId, relay privacy and prepared-slot amendments
recorded below. OD-058 is **RESOLVED FOR D9-03**. Final human review passed;
[PR #25](https://github.com/fangbm/temvio/pull/25) merged as `82f4c62e4ca772e9b1daf192760e2ff835067bcc`.
Subsequent D9-03 slices completed through PR #28. Freeze-time review/workflow
notes below remain historical; this status update changes no frozen decision,
fixture or protocol language.

Baseline: latest `feature/d9-02-agent-sync`,
`6583e61fc3101e5373d537f6b90675430a7aec10` (PR #24 merged).
Prepared: 2026-10-04. D9-02 implementation/E2E acceptance is complete and merged;
OD-012 remains **OPEN**. No production-sensitive V3 composition is enabled.

## 1. Authority and immutable scope

| Authority | Binding consequence |
| --- | --- |
| AGT-006 | Reuse D9-01 `OpenAiCompatibleProvider`, Ktor/JSON profile, explicit configuration and structured Tool probe. No Provider SDK or second Provider abstraction. |
| AGT-007 | Provider/model selection remains per-device application state. Credentials resolve only through `PlatformSecretStore`; credentialed HTTP is rejected before secret access. No secret in prompts/history/Tools/logs/workspace sync. |
| AGT-014 | Watch is a local-first replica and can originate Provider requests; STT is optional; Watch Tools use the same application/Planner/permission contracts. |
| SYN-009 | Wear uses Android Keystore-backed storage. SQLite holds only SecretRef/non-secret metadata. This does not resolve OD-012. |
| SYN-017 | Nearby/direct workspace routes have identical logical semantics; a phone relay does not rewrite/decrypt a relayed envelope. This does not define credential delivery. |
| SYN-018 / OD-042 | Existing target D8 HPKE identity and fixed Tink suite; bind version/target/config/revision; persist highest accepted revision per config; reject rollback; wipe/revocation removes secret and advances the revision barrier before replacement. |
| SYN-006A / SYN-007A | Raw X25519 public key, canonical base64url, HPKE base mode/NO_PREFIX, strict target identity. Active directory supplies immutable enrolled public identities. |
| `D9_AGENT_RUNTIME.md` / AGT-003 / AGT-008 | Typed Tools, local user permissions/confirmation, D7 audit and application-owned context truth remain authoritative. |
| `UBIQUITOUS_LANGUAGE.md` | Use WearCapabilityService, aiEntrySupported, userEnabledAiEntry, effectiveAiEntryEnabled, providerReady, requestReady, WearProviderBinding and WearProviderRuntimeState. |
| AGT-013 / D9-02 completed specs | Agent/V3 and business/D7 causal streams and consents remain separate. Credential provisioning belongs to neither stream. |

The default request path is `Watch -> Provider`, including when the OS routes
Watch network traffic through a paired phone. Phone does not execute the Watch
Agent, rewrite Tools, authorize writes or retain a credential for the Watch in
place of Watch secure storage. A provisioning source may hold its own credential
or an explicitly supplied transient credential; that is separate from Watch
request-time ownership.

The only write path remains:
`Provider -> typed Tool -> validation -> Permission Engine -> existing
Application/Planner operation -> ToolResult + AgentAction + D7 audit`.
No Wear DAO mutation path or remote Tool approval is authorized.

This slice changes documentation and fixture artifacts only. It excludes
D9-03 runtime, D10, new Tools/Planner meanings, D7 causality changes, D9-02 V3 or
workspace Envelope/AAD changes, server Agent, Phone AI proxy, MCP, embeddings,
new Provider SDKs, production V3 enablement and OD-012 implementation.

## 2. Repository audit: what exists and what does not

Audit performed on the baseline above; file references are repository-relative.

| Existing surface | Evidence / consequence |
| --- | --- |
| D8 device directory, enrollment/package and rotation routes | `server/sync/src/main/kotlin/dev/agenticscheduler/server/sync/Application.kt`: authenticated `GET /v1/devices/active` and `GET /v1/rotations/packages`; pairing routes retain PENDING admission semantics. No provider credential mailbox/route exists. |
| D8 transport | `KtorSyncLifecycleTransport.kt` and `KtorSyncTransport.kt` in `shared/application/.../sync` deliver enrollment/rotation/workspace packages. None is a credential provisioning API; do not reuse a SyncSpace cursor, pairing requestId or rotationId. |
| HPKE | `PairingHpke.kt` and Android/Desktop `TinkPairingHpke` accept recipient public key, plaintext and contextInfo. The suite is X25519/HKDF-SHA256/AES-256-GCM, base mode, NO_PREFIX; 32-byte encapsulated key is split from ciphertext. Reuse this primitive boundary, not pairing DTO/admission. |
| Identity compatibility | `shared/sync/.../D8WireProtocol.kt`: DeviceId requires a nonblank opaque value, not UUIDv7. `shared/agent/.../history/AgentState.kt`: ProviderConfigId reuses the Agent UUIDv7 validator. C1 preserves both existing contracts unchanged. |
| Secret storage | `SecureKeyLifecycle.kt` exposes `importSecret/readSecret/delete`. `AndroidKeystoreSecureStore.android.kt` allocates a random reference inside import and writes protected bytes. It has no pre-reserved import slot/orphan enumeration API. C4 now freezes the safety/recovery contract for a future tracked import; copying pairing cleanup does not implement that contract. |
| Local Provider configuration | `shared/agent/.../history/AgentState.kt`: `ProviderConfig` carries id/baseUrl/model/context/output/streaming/tool flags/SecretReference. `OpenAiCompatibleProvider.kt` resolves the secret at request time and has a synthetic Tool probe. |
| Wear app | `WearMainActivity.kt` composes D8 and local agenda reads. `apps/wear/build.gradle.kts` has minSdk 30, no shared Agent dependency or Data Layer dependency. Manifest has no STT integration. No Wear Agent/Provider binding/capability/provisioning implementation exists. |
| Nearby route evidence | `SyncTransportWorkerTest` compares two replay transports for logical receive equivalence. It is not a physical Data Layer adapter or credential delivery/peer authentication test. No MessageClient/DataClient/credential revision implementation was found in platform/shared source. |
| Old diagrams | The older on-device-STT-only entry gate is superseded by AGT-014 and this task. The old Data Layer sequence is conceptual, not an exact delivery/ACK/retention authorization. |

### Approved sign-off — 2026-10-04

The maintainer approved C1–C8 with the explicit amendments in sections 3–10.
The inherited D8 crypto decisions remain unchanged.

| Decision | Frozen scope | Approved contract |
| --- | --- | --- |
| C1 | Exact wire/secret representation/AAD bytes/bounds and fixtures (section 3) | V1 credential protocol; DeviceIds are exact opaque D8 values, providerConfigId stays UUIDv7. |
| C2 | Delivery, provisioner authentication, ACK/retry/retention (section 4) | Option A only: authenticated opaque mailbox, independent 8-digit comparison, 7-day expiry; no relay binding metadata/digest; nearby deferred. |
| C3 | Revision ownership, reservations, wipe/removal/replay (section 5) | Target-owned counter per (targetDeviceId, providerConfigId). |
| C4 | Tracked secure-store import/DB publish/crash cleanup (section 6) | Platform-issued fresh purpose-scoped prepared slots + durable journal; arbitrary import destinations forbidden; no cross-store atomicity claim. |
| C5 | WearProviderBinding field set/approval/change rules (section 7) | Separate locally approved non-secret binding; envelope carries no ProviderConfig. |
| C6 | Capability/runtime state and network probe contract (section 8) | Entry preference defaults OFF; stable capability separate from readiness; fixed bounded D9-01 synthetic Tool probe. |
| C7 | First-alpha optional STT API/language/permission contract (section 9) | On-device platform path where provable; text input remains available. |
| C8 | First-alpha Watch permission ceiling and context bridge (section 10) | Writes cannot relax to ALLOW_DIRECT; Watch-local confirmation; PhoneContextBridge DEFERRED. |

OD-042 stays RESOLVED for crypto principles. OD-058 records these explicitly
approved D9-03 contracts; this approval does not start an implementation slice.

## 3. C1 — exact wire (APPROVED)

### 3.1 DTOs and identity

`ProviderCredentialEnvelopeV1` has exactly these fields, in emitted order:

```text
providerCredentialEnvelopeVersion: integer = 1
targetDeviceId: exact existing enrolled D8 DeviceId.value
providerConfigId: canonical lowercase UUIDv7
credentialRevision: integer, 1..9223372036854775807
encapsulatedKeyBase64Url: canonical base64url, decoded length 32
ciphertextBase64Url: canonical base64url, HPKE ciphertext including tag
```

`ProviderCredentialPlaintextV1` has exactly:

```text
providerCredentialEnvelopeVersion: integer = 1
targetDeviceId: exact existing enrolled D8 DeviceId.value
providerConfigId: canonical lowercase UUIDv7
credentialRevision: integer, 1..9223372036854775807
credentialSecretBase64Url: canonical base64url of credential UTF-8 bytes
```

`targetDeviceId` and `provisionerDeviceId` reuse the exact existing opaque D8
`DeviceId.value`. No UUID requirement, lowercasing, trimming or normalization is
allowed. HPKE context and SAS use the exact UTF-8 bytes of the stored identity.
Admission requires the exact ACTIVE device identity from existing D8 enrollment/
directory state. D9-03 must neither widen nor narrow D8 DeviceId semantics.
`providerConfigId` remains canonical lowercase UUIDv7 under the existing D9
`ProviderConfigId` contract. Positive fixtures deliberately use non-UUID D8 IDs.

Inner/outer version, target, config and revision must match exactly. Target must
be the current ACTIVE D8 device; config must be the exact target-local approved
binding/reservation. No account master key, SyncSpace key, DeviceCredential,
SecretRef, model, base URL, history, Tool data or provider session is in plaintext.
Account authorization is transport/local enrollment state, not a new key hierarchy.

The v1 secret representation is a non-empty Bearer token of 1..4096 bytes,
ASCII `0x21..0x7e` (thus strict UTF-8), without whitespace/control characters.
No trimming/normalization or implicit secret type conversion is allowed. This is
the approved restriction for the existing Bearer adapter, not a universal Provider
credential claim. Other credential shapes require a later reviewed version.

### 3.2 Encoding and exact HPKE context

Strict UTF-8 JSON without BOM; malformed UTF-8/surrogates, duplicate decoded
object keys, missing/unknown fields, stringified/fractional/exponent revisions,
null identities, invalid existing D8 identities, noncanonical providerConfigId/
base64url and out-of-range integers reject. Do not add a UUID/canonical-case
validator to DeviceId or normalize it while decoding.
Emit compact JSON in the listed field order, decimal integers, no optional field
omission, and standard JSON escaping. V1 fixtures use only ASCII. Receivers may
accept legal whitespace/key order, but canonical re-encoding defines retry bytes.

Base64url uses only `[A-Za-z0-9_-]`, no padding/whitespace; decoded bytes re-encode
exactly to the supplied text. No PEM/DER/JWK/Tink protobuf/keyset/prefix on the
wire. Ciphertext contains the HPKE AEAD tag; no separate IV/nonce/tag field.

With `LP(s) = U32BE(length(UTF8(s))) || UTF8(s)`:

```text
ProviderCredentialContextV1 =
    ASCII("agentic-scheduler-provider-credential")
    || 0x00
    || U32BE(providerCredentialEnvelopeVersion)
    || LP(targetDeviceId)
    || LP(providerConfigId)
    || U64BE(credentialRevision)
```

These exact bytes are supplied to the existing Tink HPKE `contextInfo` argument.
Suite remains SYN-006A's base-mode RAW/NO_PREFIX suite. This is the approved
construction of SYN-018's four bindings, not a change to workspace AAD.

### 3.3 Bounds and rejection behavior

| Frozen boundary | Maximum |
| --- | ---: |
| Decoded credential bytes | 4096 |
| Encoded plaintext UTF-8 bytes | 8192 |
| Decoded HPKE ciphertext bytes (tag included) | 8208 |
| Encapsulated key | Exactly 32 decoded bytes / 43 encoded characters |
| Outer envelope UTF-8 bytes | 16384 |
| Single delivery request/response UTF-8 body | 32768 |

First alpha uses one credential envelope per delivery, no unbounded batch.
Enforce stream/request limit before buffering; base64 limits before allocation;
plaintext cap after authentication and before parsing/import. HPKE ciphertext
must contain at least the 16-byte tag. These frozen C1 values are
independent of D9-02's 256 KiB V3 plaintext cap and D8 deployment limits.

| Input | Required fail-closed result |
| --- | --- |
| Unknown version | `UNSUPPORTED_CREDENTIAL_ENVELOPE_VERSION`; no decrypt/import/revision advance/activation; ordinary sync continues independently. |
| Malformed/bounds violation | `INVALID_CREDENTIAL_ENVELOPE` / `CREDENTIAL_ENVELOPE_TOO_LARGE`; no side effects. |
| Wrong private key, tamper or wrong context | `CREDENTIAL_AUTHENTICATION_FAILED`; redacted, no plaintext diagnostics. |
| Target/config mismatch | `TARGET_MISMATCH` / `PROVIDER_BINDING_MISMATCH`; no secure-store import. |
| Unapproved source/content comparison | `PROVISIONING_APPROVAL_REQUIRED`; no import/activation. |
| Revision/reservation violation | `ROLLBACK_REJECTED` / `UNRESERVED_REVISION` / `CREDENTIAL_INTEGRITY_CONFLICT`, as section 5. |

Fixtures in [fixtures/d9-03-00/](fixtures/d9-03-00/README.md) are approved canonical
JSON plus exact AAD/SAS transcript bytes. Their README separates public synthetic
primitive evidence from protocol/runtime acceptance. Promote them to D9-03-01
codec golden tests only after final human review permits that slice to begin.
No fixture contains a real credential.

## 4. C2 — delivery/source authentication (APPROVED: OPTION A)

### 4.1 Existing crypto does not authenticate the provisioner

Anyone knowing the recipient public key can produce a valid HPKE base-mode
ciphertext. Target/config/revision context binding prevents substitution of those
fields; it does not prove the sender supplied the credential. Bearer auth proves
sender identity to an honest relay, not to the recipient against a malicious relay.
Data Layer node/app identity is also not D8 DeviceId/enrollment approval.

Neither a successful decrypt nor an untrusted relay's sender wrapper may activate
a binding by itself. No second keypair, new signature suite, HPKE suite change or
silent expansion of server trust is authorized as an implementation shortcut.

Approved source confirmation reuses the D8 human-comparison pattern: the user
compares an 8-digit code displayed by the selected provisioner and target Watch,
must confirm locally on Watch that the codes match before import. Frozen transcript:

```text
S = ASCII("agentic-scheduler-provider-provisioning-sas") || 0x00
    || LP(provisionerDeviceId) || LP(targetDeviceId) || LP(providerConfigId)
    || U64BE(credentialRevision)
    || SHA256(canonicalEnvelopeUTF8)
    || SHA256(canonicalWearProviderBindingMetadataUTF8)
```

Apply SYN-006A's exact unbiased `SHA256(S || U32BE(counter))` decimal mapping
and mandatory leading zeros. Comparison binds the encrypted credential, target,
revision, selected source and locally approved endpoint/model/capabilities.
This independently approved credential transcript has a separate domain; the
prior D8 pairing SAS state/approval must not authorize a credential. Its D8-sized
guess/collision bound and dependence on honest user comparison remain explicit.
This provisions a credential; it grants no remote Tool-confirmation authority.

### 4.2 First-alpha delivery — Option A only

Use a dedicated authenticated opaque server mailbox. Source and target must both
be ACTIVE in the same D8 account; existing bearer authentication/authorization
is mandatory. The existing active-device directory supplies the target HPKE key.
This reuses the current infrastructure and queues for an offline Watch; initial
provisioning requires the relay to be reachable. Server never decrypts or rewrites
the credential envelope and never mints revisions or chooses a credential winner.

Nearby/Data Layer credential delivery (the earlier Option B) is **DEFERRED**.
It requires a later explicit decision/sub-slice covering authenticated eligibility,
revocation freshness and durable delivery. SYN-017 workspace route equivalence
does not authorize that credential adapter.

Approved lifecycle:

1. Watch approves a non-secret binding, chooses an ACTIVE provisioner and durably
   reserves a revision. A non-secret request is keyed by
   `(targetDeviceId, providerConfigId, credentialRevision)` and exposes only
   minimum routing/reservation identity, including the selected provisioner.
   It contains no binding contents, canonical binding JSON or binding digest.
2. Source fetches the target request and active directory through existing D8
   bearer authorization. The user selects a local ProviderConfig/credential.
   Source constructs candidate **target** binding metadata locally: use the
   target providerConfigId and the selected local provider/model/budget/capability
   values (not the source config ID). It encrypts to the exact listed D8 key only
   after a local user transfer action. Local outbox persists exact ciphertext
   bytes before send. Source and Watch independently canonicalize their local
   binding metadata and include its SHA-256 in their own SAS transcript.
3. A new dedicated mailbox stores opaque envelope bytes and routing/idempotency
   metadata. Existing workspace envelopes, enrollment packages, rotation storage,
   cursor and protocol remain untouched.
4. Watch fetches only its account/device mailbox while ACTIVE/authenticated.
   It performs strict identity/HPKE validation and compares against its own
   already approved local binding. Endpoint/model/capability differences produce
   a different displayed SAS and cannot be approved. Import requires the user
   to confirm matching codes on Watch, followed by section 6 install.
5. Target may report `INSTALLED`/`DUPLICATE` with target/config/revision and
   SHA-256 digest of canonical encrypted envelope, never secret/plaintext hash.
   A receipt or HTTP 2xx never advances target counters or makes providerReady.
   Relay-reported ACK is informational against a malicious relay; only the
   Watch's committed local state is authoritative installation evidence.

The acknowledgement DTO has exactly
`providerCredentialAcknowledgementVersion=1`, `targetDeviceId`,
`providerConfigId`, `credentialRevision`, `envelopeDigestBase64Url` (32-byte
SHA-256 of canonical outer JSON), and `result=INSTALLED|DUPLICATE`. Rejections are
redacted transport errors, not successful acknowledgement objects. This receipt
is informational; it contains no proof that defeats a malicious relay.

### 4.3 Relay privacy and local binding comparison

Mailbox/request/ACK contain no plaintext binding metadata and need no binding
digest. In particular the relay must not receive baseUrl, model, context capacity,
streaming/tool capability metadata, credentialRequired or canonical binding JSON.
The source and Watch compute binding hashes only locally for the SAS; neither
the binding nor its hash is a ProviderConfig transport through the relay.
Watch already owns/explicitly approves its binding. Selecting a source config
does not transfer metadata authority or automatically alter the target binding.
The canonical binding fixture illustrates local hash input, never a mailbox body.

Persist any target-local approval only against the exact envelope/binding
digests, selected provisioner and live reservation. After restart, missing or
changed approval requires a new user comparison; an old generic "approved"
boolean cannot authorize a different credential or endpoint.

Lost responses retry exact durable ciphertext. Same mailbox key + exact bytes is
idempotent; changed bytes under that key reject at relay as a delivery conflict.
Receiver same-value semantics remain section 5, even if an independently supplied
authenticated envelope was encrypted with different HPKE randomness. Sender
retains encrypted bytes through retry/receipt policy; a failed send is not success.

Frozen retention: unacknowledged mailbox ciphertext expires after 7 days;
acknowledged rows remove ciphertext and retain only bounded delivery metadata
through that expiry. Expiration is explicit `DELIVERY_EXPIRED`, never installation
or counter success. A returning Watch requests a fresh higher reservation if its
delivery is gone. Source keeps encrypted retry bytes until acknowledged/cancelled/
expired. This numeric lifetime and metadata cleanup are frozen C2 decisions, not D8
history/tombstone compaction. The approved minimal post-expiry security exception
is specified in section 4.4; no ordinary delivery metadata survives that cutoff.

Revoked source/target devices cannot publish/fetch/ACK. Both devices' same-account
ACTIVE eligibility is checked transactionally with every publish/fetch/ACK.
A stale cached directory alone authorizes
no transfer. Remote revocation cannot physically erase an offline device's secret:
D8 immediately denies access; target-local revocation handling/wipe performs sections 5/6
cleanup once observed. No claim of retroactive erasure is made.

Dedicated opaque route/storage implementation belongs to D9-03-01 after final
human review; this documentation PR adds no HTTP route or server migration.
Nearby remains deferred. Server receives zero provider plaintext or binding
contents/digest; do not log bodies or secret comparison values.

### 4.4 OD-059 — minimal post-expiry anti-replay tombstone (APPROVED)

PR #26 final review resolved OD-059 for D9-03-01 without reopening C1–C8.
After the frozen seven-day deadline, persist only `accountId`, `targetDeviceId`,
`providerConfigId`, `credentialRevision` and `DELIVERY_EXPIRED`. Account ownership
is authorization/routing metadata, never revision authority. This is a separate
anti-replay high-watermark, not an active delivery or retained delivery history.

Purge provisioner/source assignment, canonical ciphertext, envelope digest, ACK
result, delivery created/expiry timestamps and any other delivery/retry data.
Credential plaintext/hash, binding contents/digest, baseUrl/model, SecretRef and
ProviderConfig payload remain prohibited at the relay.

The marker has **no time-based expiry**. Only a strictly higher target-owned
revision accepted as a valid reservation for the same `(target, config)` replaces
it, or a future explicit permanent account/device identity deletion removes it.
Maintenance, local credential wipe/binding removal and source/target revocation
never reset this high-watermark. No tombstone compaction policy is introduced.

An authorized exact target retry of the expired revision always reports
`DELIVERY_EXPIRED`, even after arbitrary elapsed time/restart or source revocation.
A lower revision rejects as stale/unreserved. Neither recreates REQUESTED/source,
ciphertext, timestamps or a new deadline; neither alters local target counters.
A higher revision must pass all existing exact-target, ACTIVE source/target,
same-account and valid-identity checks, and receives one new seven-day window.
The server continues to allocate no revisions.

Amend unmerged/unshipped Server V10 in place, using nullable delivery timestamps
and CHECK constraints that require appropriate non-expired fields and forbid all
delivery remnants in an expired marker. Independent, idempotent, restart-safe
maintenance clears expired delivery data without mutating an existing marker.
This is the approved exception to the earlier bounded-delivery-metadata wording;
the seven-day ciphertext/source/digest/ACK/active-delivery boundary is unchanged.

## 5. C3 — credentialRevision ownership (APPROVED)

Watch is the sole allocator per
`(targetDeviceId, providerConfigId)`, persisted with the existing D8 identity.
No source-device allocator or authority transfer is authorized.

Target keeps separate durable `highestReservedRevision`,
`highestAcceptedRevision` and `rejectionFloor` plus the current reservation/source.
Initial values are 0; first explicit reservation is 1.
Next reservation is
`max(highestReservedRevision, highestAcceptedRevision, rejectionFloor) + 1`;
exhaustion rejects explicitly.
No wall clock/HLC, DeviceId ordering or server cursor allocates/selects revisions.

Only one reservation is live per config. Reserving a newer one cancels the older
one; the target pins its selected source and locally approved binding metadata.
No source may independently increment, seize or fill another source's reservation.
Multiple enrolled sources therefore serialize through target reservations; an
unrequested credential never replaces an active binding.

| Case | Frozen behavior |
| --- | --- |
| New reserved revision > floor/accepted | Authenticate + local approval + full install; accepted revision advances only in DB publish. |
| Same revision, same canonical credential | Idempotent only if that accepted binding is still active and its secure-store value is available/equal; no new import or counter advance. Exact accepted ciphertext can use its stored digest. Rerandomized ciphertext must decrypt and compare transiently to secure-store bytes. |
| Same revision, different credential | Explicit integrity conflict, preserve accepted value/ref/revision; never LWW. Do not persist a plaintext credential digest in DB. |
| Older than accepted or at/below removal floor | Reject without import; no resurrection after wipe/removal. |
| Higher but unreserved/cancelled/wrong source | Reject; peer/server cannot advance target authority. |
| Missing/corrupted accepted secret on retry | Fail closed `CREDENTIAL_UNAVAILABLE`; require explicit fresh higher-revision replacement, no implicit repair/install. |
| Replacement | Fresh reservation and reference; old ref stays current until successful atomic metadata publish; then durable cleanup of old secret. |
| Delete/binding removal/wipe/revocation observed | Advance floor to `max(counters)+1` and cancel reservations before replacement; disable runtime, clear active reference and schedule secret deletion. Retain revision tombstone even after removing binding metadata. |
| Restart | Restore counters/reservation/journal before any Provider use; never reset to revision 1. |
| Total local state loss | Retaining the same D8 private identity with a silently reset floor is forbidden. Fail closed until a provable floor can be recovered or a fresh D8 enrollment identity is used; no unproven same-identity recovery is authorized. |

A wipe barrier is not falsely described as an accepted credential. Highest accepted
revision remains an audit counter; rejectionFloor invalidates stale accepted bytes.
The skipped revision around a wipe barrier is explicitly approved; a barrier
revision need not correspond to an installed credential. This preserves SYN-018's
monotonic principle. Durable floor retention requires a future non-destructive migration.

## 6. C4 — install/cleanup state machine (APPROVED)

The required order remains:
`authenticate/decrypt -> validate target/config/revision ->
secure-store import -> persist SecretRef + accepted revision ->
activate WearProviderBinding`.
Plaintext is transient within the platform/application security boundary and is
never passed to a Room record, logger, ToolResult, context or provisioning outbox.

Secure-store and SQLite cannot share an atomic commit. Frozen failure atomicity
means no partial/new binding becomes request-visible, with durable orphan cleanup.

```text
RESERVED -> AUTHENTICATED -> USER_APPROVED
         -> PREPARED_IMPORT -> SECRET_IMPORTED -> METADATA_COMMITTED -> ACTIVE
                              \ failure -> CLEANUP_PENDING -> REJECTED
ACTIVE -> replacement -> PREPARED_IMPORT ... -> ACTIVE(new reference)
ACTIVE/RESERVED -> WIPING -> CLEANUP_PENDING -> REMOVED
```

C4 freezes a tracked-import capability at the existing platform secret boundary:
the **platform secure store allocates** a fresh, unique, purpose-scoped Provider
credential slot/reference. Persist only that reference/install identity in a
durable journal before importing into the slot. Application code cannot provide
an arbitrary pre-existing SecretReference as an import destination. A slot has
a one-purpose/one-install lifecycle; it cannot overwrite AMK, SyncSpace keys,
pairing HPKE private keys, RecoverySecret, DeviceCredential, another active
Provider credential or unrelated application secrets. Exact API/class names are
D9-03-01 implementation details; these safety semantics are frozen.
The current importSecret API cannot guarantee recovery for a crash immediately
after import but before its generated reference returns. Do not call an
untracked best-effort delete a complete orphan-cleanup contract.

The target serializes config installs/removals. It rechecks reservation, enrollment,
approved binding and floor inside the publish transaction after the external import.
Publish atomically writes current SecretRef, accepted revision, binding state and
the journal's committed ownership/retired-reference cleanup state. Activation is
derived only from committed metadata plus a readable secret. If a crash makes the
commit result unknown to the caller, restart reads committed metadata/journal
before cleanup: preserve a reference owned by the committed active binding; clean
only a prepared reference proved uncommitted. An absent ACK is not proof of rollback.

| Failure/crash | Required frozen state/recovery |
| --- | --- |
| Decrypt/validation/approval fails | No import/journal publish; old accepted binding untouched. |
| Journal/reference preparation fails | No import; no new active binding. |
| Arbitrary/pre-existing/wrong-purpose/already-used destination | Reject before import; no unrelated or active secret is overwritten. |
| Import fails or crashes | Existing binding remains; restart inspects the prepared slot, removes any uncommitted secret, records completion; retry never guesses installation. |
| Secret stored, DB publish rolls back | Old ref/revision remain current; prepared reference is known and cleaned at restart. Cleanup failure is durable/retryable; new ref is never usable. |
| Crash around DB commit / commit outcome unknown | Read durable publish/journal state first. Committed new ref/revision survive and old-ref cleanup resumes; otherwise preserve the old binding and clean the uncommitted prepared slot. Never delete an active committed slot because an ACK was lost. |
| DB publish succeeds, old-secret deletion fails | New ref stays current; persistent cleanup task deletes old ref later, never rolls metadata back to it. |
| Wipe races replacement/Provider request | Persist floor + disable binding + cancel reservations/request use + cleanup references atomically in metadata; late publish recheck rejects. Complete wipe only after secret deletion; failures stay visibly cleanup-pending. |
| Restart with committed metadata, secret missing | providerReady false, no plaintext fallback, explicit higher-revision repair action. |
| Orphan cleanup | Delete only journal-owned prepared/retired provider slots; never enumerate-and-delete unrelated D8 keys/secrets. |
| Binding removed then stale envelope replayed | Retained floor rejects; no implicit binding recreation. |

Actual journal layout/schema migration, tracked secret-store port and recovery
proof are D9-03-01 work only after final human review of this packet. No SQLite
credential plaintext or exported secure-store keyset is an acceptable fallback.

## 7. C5 — WearProviderBinding (APPROVED)

This is local non-secret binding metadata mapped into the existing
`ProviderConfig`, not a second Provider Adapter/Agent model. Canonical local
metadata DTO `WearProviderBindingMetadataV1` emits:

```text
bindingVersion = 1
providerConfigId
adapterProfile = "OPENAI_COMPATIBLE_CHAT_TOOLS"
baseUrl
model
maxContextUnits
reservedOutputUnits
streamingSupported
toolCallingSupported
credentialRequired
```

All values are explicit; existing ProviderConfig budget invariants apply. No host/
model/capacity/permission default is invented. The target creates/owns config ID
and explicitly approves this exact metadata. Source constructs its candidate
target metadata locally for independent C2 comparison, using this target config
ID and values from the user's selected local config. Nothing is automatically
authoritative or sent as binding contents/digest through the relay. Binding
changes invalidate approval and cannot silently send an installed credential/
context to a new origin.
Endpoint/credential-affecting changes require a new reservation/revision.

Local binding also holds `credentialReference: SecretReference?` and revision/
approval/install state. SecretRef is absent from the canonical metadata DTO.
`credentialRequired=true` requires the matching approved, installed credential;
false forbids attaching a credential by inference. Credentialed base URLs require
HTTPS before resolving the secret, exactly as AGT-007. Explicit credential-free
HTTP retains the existing local-model allowance and truthful plaintext-risk UI.

The envelope includes only config identity/revision and credential, not this
metadata. Association is through the locally pinned (target, config, reservation)
and C2 content comparison over both canonical artifacts. The relay is not a
ProviderConfig transport and never receives binding contents/digest. Provider
secrets cannot enter Provider prompts; a credential used as an authorization
header remains outside the prompt.

## 8. C6 — capability/network/runtime state (APPROVED)

Derived invariant formulas, preserving the ubiquitous distinctions:

```text
aiEntrySupported = stable supported platform + at least one supported input path
effectiveAiEntryEnabled = aiEntrySupported && userEnabledAiEntry
providerReady = locally approved valid binding
                && supported existing adapter profile
                && !install/wipe blocked
                && (!credentialRequired || matching secure-store secret available)
requestReady = effectiveAiEntryEnabled && providerReady && networkReachable
```

`WearCapabilityService` owns stable OS/input/language facts; local settings own
`userEnabledAiEntry` (frozen default OFF); provider
composition owns binding/credential state; network infrastructure owns transient
reachability; UI observes derived state, never edits it to bypass readiness.
STT unsupported or permission denied does not remove a supported text input path.
Transient network failure never toggles aiEntrySupported or user preference.

`WearProviderRuntimeState` has explicit states:
`ENTRY_UNSUPPORTED, ENTRY_DISABLED, NO_BINDING, BINDING_APPROVAL_REQUIRED,
CREDENTIAL_UNAVAILABLE, INSTALL_BLOCKED, OFFLINE, READY, PROVIDER_UNAVAILABLE`.
providerReady/requestReady remain separate derived values rather than synonyms
for these display reasons. Errors carry redacted codes only; several blockers may
be exposed as typed facts rather than relying on a semantic priority tie-break.

Network contract: observe Android default-network callbacks, including
capability changes/loss. For public HTTPS endpoints, INTERNET + VALIDATED gives
a usable-route signal, not a guarantee that a specific Provider/model is healthy.
An explicitly configured local credential-free endpoint must not require public
internet validation; its route/attempt result is modeled separately. READY means
a request may be attempted, not that a Provider response is guaranteed.
No generic invented `/health` endpoint or business-data connectivity ping.

Reuse D9-01's synthetic structured Tool probe at explicit binding validation and
before write-capable execution; send only its fixed probe instruction/schema,
never Domain facts, user text, conversation/context, microphone audio or secret
in the body. Credential, if required, is resolved only into the HTTPS auth header.
Unsupported structured Tools cannot become prose-based writes.

Frozen bounds: one single-flight probe, 10-second total deadline, no automatic
retry of an Agent request/write. Foreground/network-change readiness refresh may
retry a failed synthetic probe with injected bounded 2/4/8/16/30-second backoff;
auth/config/unsupported errors stop until explicit retry/config change.
Success/usable route restores readiness automatically without submitting an old
Agent command. Implementations preserve these frozen deadline/backoff/stop rules;
platform callback/local-route API details belong to D9-03-02. No background wake
guarantee or D8 sync-loop coupling.

Platform facts are checked against
[Android network-state documentation](https://developer.android.com/develop/connectivity/network-ops/reading-network-state):
VALIDATED describes system-tested internet access and still permits endpoint
failures. C6 policy is approved by the maintainer; this source supports the API distinction.

## 9. C7 — optional STT (APPROVED)

First supported speech path: Watch-local platform on-device recognition, guarded
by API/service/language availability, without changing Wear minSdk 30.

- API 31+: `isOnDeviceRecognitionAvailable` followed by
  `createOnDeviceSpeechRecognizer` only when available.
- API 33+: `checkRecognitionSupport` can inspect the selected intent/language.
  On earlier versions, unproven language support remains unverified rather than
  assuming network implies STT. API 30 has no guaranteed equivalent on-device
  probe; speech may be unsupported while text entry remains supported.
- Capability inspection requests no permission, records no audio and starts no
  recognition/model download. If an API/service requires mic authorization even
  for a richer query, defer that query until a user-triggered grant.
- User taps speech input before requesting RECORD_AUDIO. Denial stays distinct
  from unsupported OS/service/language. Request permission never happens during
  passive capability probe.
- No fallback to generic cloud recognizer/Phone remote microphone service.
  Fail/cancel leaves text and ordinary local calendar/task use available.
  Optional model download requires a separate user action; it is not inferred.

Frozen DTO facts: `onDeviceSttAvailability =
UNSUPPORTED | LANGUAGE_UNVERIFIED | AVAILABLE | TEMPORARILY_UNAVAILABLE`,
`speechPermission = NOT_REQUESTED | GRANTED | DENIED`, explicit selected language
tag, and independent `textInputSupported`. A permission denial is not an
UNSUPPORTED capability fact. Platform adapter implementation/evidence belongs
to D9-03-02; this freeze adds no runtime code.

API levels and on-device methods are verified in the official
[SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer).
Device/emulator recognition behavior must be measured in D9-03-02/03; these docs
do not prove that a specific Watch supplies on-device STT.

## 10. C8 — local permissions and context (APPROVED)

First-alpha default reuses AGT-003's actual typed enum:

| Capability | Existing conservative default |
| --- | --- |
| READ, PLAN_PREVIEW | ALLOW_DIRECT |
| LOW_RISK_CREATE, SOURCE_FACT_UPDATE, PLANNING_PROFILE_CHANGE, SCHEDULE_APPLY, UNDO | REQUIRE_CONFIRMATION |
| BULK_CHANGE, DESTRUCTIVE, EXTERNAL_SIDE_EFFECT | DENY |

Frozen Watch first-alpha ceiling: writes cannot be relaxed to ALLOW_DIRECT;
user may tighten to DENY, and confirmation occurs on Watch for the exact local
pending call/normalized preview. BULK_CHANGE, DESTRUCTIVE and
EXTERNAL_SIDE_EFFECT remain DENY. This ceiling is explicitly approved under C8.
No Phone remote approval, broader capability or new Tool is introduced. D6 stale
PlanBranch revalidation and D7 business audit/origin remain shared contracts.

Watch owns its ContextAnchor and obtains current Domain/Application facts through
existing read Tools; limited screen/hardware does not lower AGT-008 truth priority.
PhoneContextBridge is **DEFERRED** for the first alpha: no current acceptance
path requires supplemental phone context. If later required, only explicitly
typed/source-labelled supplemental non-authoritative facts may be considered in
a separate decision; phone cannot become runtime/context authority.

Watch is an independent Agent replica. Watch-produced completed turns follow the
existing D9-02 per-SyncSpace conversation consent and exact provenance/export
rules. V2 business-write compatibility acknowledgement remains independent;
credential provisioning requires neither consent and changes neither setting.
A successful business Tool still produces ordinary D7 Agent-origin audit and
journal. Credential/SecretRef/config/permission/session data never enters V3.
OD-012 remains OPEN; D9-03 does not activate production-sensitive V3 composition.

## 11. Required adversarial/failure matrix for later implementation

These are required future tests, not claims of executed acceptance in D9-03-00.

| Case | Required observation |
| --- | --- |
| Wrong HPKE private key | Authentication failure, zero import/metadata activation. |
| Tampered ciphertext / wrong exact context | Reject, redact, no counter advance. |
| AAD target mismatch | Reject even when ciphertext is otherwise structurally valid. |
| Opaque D8 IDs / changed case or normalized identity | Non-UUID enrolled values succeed unchanged; context/SAS uses exact stored UTF-8. Changed identity cannot pass ACTIVE admission or authorize the original ciphertext. |
| providerConfigId inner/outer/local mismatch | Reject before import; no substitute binding. |
| Unknown version / malformed JSON/UTF-8/base64 / bounds | Whole credential rejection; independent workspace sync unaffected. |
| Rollback / future unreserved revision | Floor/reservation reject, no LWW/wall-clock authority. |
| Same revision/same value | Idempotent; ref/revision stable across retry/restart and ciphertext re-randomization. |
| Same revision/different value | Explicit integrity failure, accepted secret survives. |
| Unauthorized provisioner / spoofed relay source | HPKE decrypt alone is insufficient; missing/mismatched local comparison prevents install. |
| Source/Watch binding differs | Each constructs its own canonical target binding locally; differing endpoint/model/budget/capabilities change SAS, zero import. No automatic source metadata authority. |
| Revoked target/source / revocation race | Mailbox auth check rejects; observed local revocation disables binding and cleans secret. |
| Replacement | Higher target-owned revision, new ref published atomically, old secret cleanup durable. |
| Crash after import/before metadata commit | Prepared slot recovered/cleaned, no new active binding, no untracked orphan. |
| Crash after DB commit/before ACK | Committed binding/revision/secret survive restart; retry is idempotent and cleanup never deletes the active slot. |
| Caller-selected existing/wrong-purpose/used secret slot | Platform refuses destination reuse/overwrite; AMK, SyncSpace, HPKE, RecoverySecret, DeviceCredential and unrelated/active Provider secrets remain intact. |
| Concurrent replacement/wipe or two provisioners | Target reservation serialization; late publish rejects; no credential winner by HLC. |
| Wipe/removal then stale replay | Durable floor survives restart; secret stays deleted; no binding recreation. |
| Local state loss retaining old HPKE identity | Fail closed until approved floor recovery/new D8 enrollment. |
| Lost send/ACK, duplicate delivery, expired delivery | Exact bytes retry; no false target installation; expiry is explicit. |
| Relay/DB/log canary scan | No provider plaintext in server/public tables/logs or SQLite; no plaintext credential hash in DB. Relay/request/ACK contain no baseUrl/model/budget/capabilities/credentialRequired/canonical binding JSON or binding digest. |
| Network absent / restored | Entry capability/preference stable, request unavailable then restored; no automatic write replay. |
| STT absent / permission denied / failure | Separate states; text Agent remains available; no remote microphone fallback. |
| Stricter Watch policy write | Deny causes zero business writes. |
| Watch read with Phone Agent absent | Real Watch-originated Provider -> typed read round trip succeeds; no Phone proxy. |
| Confirmed Watch business write | Watch-local preview/confirmation -> existing operation -> matching ToolResult/AgentAction/D7 audit; existing V2 gate applies. |
| Watch V3 vs provisioning | Separate consent/frontier/outbox; no credential leak, no automatic consent/history upload. |

All failure phases require file-backed restart tests, schema migration parity and
real platform secure-store evidence where applicable. Nearby is deferred and
cannot be claimed from simulated route equivalence or mailbox acceptance.

## 12. Implementation slices after final human review

| Slice | Minimum implementation scope | Gate |
| --- | --- | --- |
| D9-03-01 | Approved credential DTO/codec/fixtures with opaque D8 IDs, existing HPKE adapter, target revisions/reservations, platform-issued purpose-scoped slots/journal/install/replacement/wipe, non-destructive migration, Option A opaque mailbox with no binding contents/digest. No Agent UI/runtime. | Final human review of this packet; frozen C1–C5; full adversarial/crypto/migration/secure-store tests. |
| D9-03-02 | Map WearProviderBinding to existing ProviderConfig; capability/network/optional on-device STT services; manual platform composition. No new Provider abstraction or Tool. | C6/C7 approved; real Watch/emulator capability and permission behavior, no private probe body. |
| D9-03-03 | Shared Agent runtime/Tool registry composition on Watch, Watch-local confirmation and minimal command UI; real Provider/read/confirmed write, restart/offline/wipe provisioning E2E; optional STT cannot be sole acceptance input. | C8 approved, D9-03-01/02 accepted, AGT/D7/D9-02 boundaries and production OD-012 gate preserved. |

Nearby credential delivery remains deferred and requires a later explicit
decision/sub-slice. Do not hide it inside Agent UI or call replay-transport tests
physical E2E.

No new Gradle module is proposed: wire/crypto adapters stay in the current
sync/application infrastructure boundary, local Provider/Tools in shared Agent,
persistence contracts/implementation in application/database, platform services
in apps:wear (and source UI/transport within the frozen C2 scope), opaque relay
in server:sync under Option A. None is implemented in D9-03-00.

## 13. Maintainer decision record and completion boundary

C1–C8 are **APPROVED — maintainer decision 2026-10-04**; OD-058 is
**RESOLVED FOR D9-03**. The approved amendments preserve opaque D8 DeviceIds,
choose Option A/independent comparison/7-day expiry with no relay binding
contents/digest, and require platform-issued one-install purpose-scoped slots.
AGT-014 and SYN-018 record synchronized approved amendments. OD-042 and the
inherited D8 crypto decisions are unchanged; OD-012 remains OPEN.

D9-03-00 freezes the implementation contracts, fixture design and slice gates.
It implements no runtime. Keep PR #25 Draft and wait for final human review;
do not merge or start D9-03-01. D10 is not started.

## 14. Documentation/fixture verification

Executed 2026-10-04: 11 JSON fixture encodings checked for UTF-8/no BOM, canonical
compact bytes, expected duplicate-key rejection, canonical base64url/lengths,
independent AAD/hash/SAS reconstruction and exact one-byte ciphertext tampering.
The regenerated positive vector uses opaque mixed-case D8 identities rather
than UUID DeviceIds; exact-byte/case-sensitive context and independent local
binding construction/mismatch checks guard the approved compatibility/privacy
amendments. ProviderConfigId remains UUIDv7. Binding contents/digest are absent
from the envelope/plaintext/ACK; the vector catalog's binding hash is local test
material, not mailbox data.
New relative Markdown links resolve. The persisted public HPKE vector decrypts
through existing Tink 1.23.0/JDK17; its stored tampered ciphertext, wrong-target
AAD, a case-normalized target context and a wrong recipient key fail
authentication. No runtime/codec or platform
implementation was added to perform these checks; temporary fixture utilities
were outside the repository. These checks are not Wear/provider/install acceptance
or runtime acceptance. Protocol approval comes from the maintainer's C1–C8
decision, not these checks. Repository CI is tracked on this Draft PR separately.
