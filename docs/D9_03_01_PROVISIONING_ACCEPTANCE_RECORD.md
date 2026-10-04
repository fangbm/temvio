# D9-03-01 Provider credential provisioning acceptance record

Baseline: D9-03-00 merged as `82f4c62e4ca772e9b1daf192760e2ff835067bcc`.
Branch: `codex/d9-03-01-provider-credential-provisioning`.
Review: [Draft PR #26](https://github.com/fangbm/temvio/pull/26), targeting
`feature/d9-02-agent-sync`. Do not merge or start D9-03-02/03 or D10.

Status: **D9-03-01 IMPLEMENTED / AWAITING REVIEW — implementation acceptance
complete for review**. OD-059 is **RESOLVED FOR D9-03-01**; maintainer accepted
C1–C5 otherwise. The minimal-tombstone follow-up passed real PostgreSQL/E2E and
full CI. Keep Draft; no merge or next slice is authorized.
OD-058 remains resolved for C1–C8. This record authorizes no production deployment.
**OD-012 remains OPEN.**

## Implemented boundaries

- C1: strict exact DTOs/codec, decoded duplicate-key/type/UTF-8/base64/bounds
  rejection; exact opaque DeviceIds and existing UUIDv7 config IDs. The eleven
  committed fixtures feed real codec/HPKE/SAS tests. Existing enrolled D8
  X25519/HKDF-SHA256/AES-256-GCM base-mode Tink primitive and device directory
  are reused. No workspace Envelope/AAD or cryptographic primitive changes.
- C2: independent authenticated opaque request/envelope/fetch/ACK routes;
  same-account ACTIVE checks inside the existing account-row transaction lock;
  exact canonical ciphertext retry/conflict; target-only informational ACK and
  ciphertext removal. Exact seven-day clock cutoff is enforced at every request.
  Production `main` also schedules retention cleanup without requiring active
  clients. At expiry OD-059's indefinite anti-replay tombstone retains only
  account/target/config/revision + DELIVERY_EXPIRED. Source/ciphertext/digest/ACK
  and created/expiry timestamps are purged, enforced by SQL CHECK constraints.
  Same expired revision never resets its deadline, even after restart/arbitrary
  elapsed time/source revocation. Lower revisions reject; only an eligible higher
  target-owned reservation replaces the marker with a fresh seven-day window.
- C3: per-target/config Room CAS counters, first reservation 1, one live selected
  source, max(counters)+1, durable floor and explicit approval tied to envelope
  and locally reconstructed binding. No time/server-cursor revision authority.
  Same accepted secret permits transient secure-store equality comparison;
  unequal value rejects. Missing/unreadable accepted secret fails closed; only an
  explicit higher reservation repairs it. Known-state loss cannot reset counters.
  Wire revisions remain positive signed 64-bit values and fail closed at exhaustion.
  Local rejection-floor/CAS generation use exact canonical nonnegative decimal
  counters, so `max + 1` wipe still clears credentials after `Long.MAX_VALUE`;
  that floor is not an installed revision or a new wire value. Restart preserves it.
- C4: platform-issued Provider-purpose one-install prepared capability, durable
  journal before import, Room recheck of ACTIVE enrollment/binding/floor after
  import, atomic reference/counter/journal publication. Recovery reads durable
  ownership before deletion. Unknown commits preserve committed active slots;
  failed cleanup or secure-store reads retain retryable journal ownership.
  Cleanup cannot enumerate/delete generic or D8 secrets. Empty prepared markers
  created before a journal failure contain no credential; cross-store atomicity
  and automatic enumeration of such markers are not claimed.
- C5: existing `ProviderConfig` remains authoritative. Source explicitly selects
  local values but uses the target request's config ID for its local SAS binding;
  target independently reconstructs locally approved values. Binding edits clear
  managed association/approval, advance floor and schedule journal-owned cleanup.
  No Provider run, automatic selection/comparison approval, or Wear UI is wired.
- Source outbox stores exact canonical encrypted bytes and the discovered
  delivery deadline before HTTP. Retry never encrypts again. Explicit cancellation,
  matching informational receipt and observed/local deadline expiry end retry
  retention; an in-flight response cannot recreate a deleted outbox. Receipt and
  expiry never advance target accepted counters or enable a Provider.
- Credential HTTP uses scoped streaming plus header/stream size checks; Ktor's
  automatic saved-response allocation is avoided. Reference:
  [Ktor streaming response API](https://api.ktor.io/ktor-client-core/io.ktor.client.statement/-http-statement/index.html).

## Local schema v15 → v16

Non-destructive explicit SQL extension, fresh callback/onOpen validation and both
platform factories; no new Room `@Entity` list entries or destructive fallback.
The exported Room v16 schema is committed. The extension catalog is validated
separately including exact DDL/constraints/indexes.

| Object | Purpose |
| --- | --- |
| `provider_credential_target_identity` | Enrollment initialization/known-config guard; retained identity with lost known revision state fails closed. |
| `provider_credential_revision` | Target/config counters, floor, selected source, local binding/approval and active ownership. |
| `provider_credential_install_journal` | Immutable slot ownership plus durable install/retired cleanup phases; unique prepared reference. |
| `provider_credential_journal_target_idx` | Target/config recovery lookup. |
| `provider_credential_delivery_outbox` | Source/target/config/revision identity, exact ciphertext/digest, retry state and deadline. |

Migration initializes identity guards for existing v15 enrollments (provisioning
did not exist in v15). New revision/journal/outbox tables start empty. Populated
D9-01 history/action/pending confirmation/provider/policy, D9-02 provenance,
Agent counters/frontier/backfill and D8 cursor are preserved. Legacy generic
secrets are not converted into tracked Provider slots or enumerated for cleanup.

## Server schema V9 → V10

Only `provider_credential_mailbox` and `provider_credential_assigned_idx` are added.
The mailbox has routing/identity/state/deadline, opaque ciphertext and encrypted
envelope digest/informational ACK fields. No binding metadata/hash, SecretRef,
credential plaintext/plaintext digest or ProviderConfig payload is stored.
Existing workspace/enrollment/rotation/recovery tables and cursors are unchanged.
V10 is amended in place before merge/shipping: timestamps are nullable only for
expired rows, which retain exactly the five OD-059 logical fields. Active states
require source/timestamps and their appropriate ciphertext/digest/ACK fields;
SQL requires a 604800-second delivery window. Maintenance is traffic-independent,
idempotent and restart safe. Marker lifetime has no time-based expiry/compaction;
only an eligible higher target reservation or a future explicit permanent account/
device identity lifecycle operation can replace/remove it. Wipe, binding removal
and revocation do not reset the server high-watermark or allocate local revisions.

## Verification actually executed

Windows targeted command (JDK 17, UTF-8, ASCII junction to the same managed
worktree, `GRADLE_USER_HOME=D:\gradle-home-agent`):

```powershell
.\gradlew.bat :shared:sync:desktopTest --tests '*ProviderCredentialWireTest' `
  :shared:application:desktopTest --tests '*ProviderCredential*Test' --tests '*DesktopPlatformSecureStoreTest' `
  :shared:database:desktopTest --tests '*ProviderCredentialMigrationTest' --tests '*AgentSyncPersistenceTest' `
  :server:sync:test --tests '*ProviderCredentialPostgresTest' `
  --no-configuration-cache --console=plain
```

| Targeted suite | Local result |
| --- | --- |
| `ProviderCredentialWireTest` | 6/6 passed |
| `ProviderCredentialCryptoTest` | 5/5 passed; committed HPKE vector actually decrypted; exact SAS `51070555` |
| `ProviderCredentialInstallTest` | 20/20 passed; real Room file reopen/rollback/unknown commit, journal ownership guard and exhausted-revision wipe/restart; fault-injected secure-store backend |
| `ProviderCredentialTransportTest` | 3/3 passed; mocked HTTP auth/exact bytes/stream cap/redaction |
| `ProviderCredentialMigrationTest` | 2/2 passed; populated v15→16 + fresh parity, altered catalog rejection |
| `AgentSyncPersistenceTest` | 20/20 passed; latest-version migration updated to include v16 |
| `DesktopPlatformSecureStoreTest` | 5/5 passed; includes actual Windows DPAPI recreation/isolation |
| `ProviderCredentialPostgresTest` | 17 skipped locally: no PostgreSQL test URL; not local PostgreSQL acceptance |
| `ProviderCredentialPostgresE2ETest` | 1 skipped locally: no PostgreSQL test URL; not local E2E acceptance |

Crash matrix covers before import, after import, actual Room rollback, committed
but lost commit response/ACK, failed old deletion, failed Provider read, and
wipe/enrollment-loss racing publication. Tests prove no active new association on
uncommitted install and no deletion of committed active ownership.

Android local command:

```powershell
.\gradlew.bat :apps:android:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.agenticscheduler.android.ProviderCredentialSlotInstrumentedTest' `
  --no-configuration-cache --console=plain
```

Actual Android API 35 emulator Keystore: 3/3 passed, including corrupted-ciphertext
read failure preserving cleanup ownership. No local Wear emulator result
is claimed; actual Wear Keystore evidence comes from CI's Wear API 35-ext15 AVD.

## CI evidence and limits

[Run 37191578433](https://github.com/fangbm/temvio/actions/runs/37191578433),
accepted follow-up head `a4daebe5ba475daa3c48cd3afa3e24585acd0c21`, all five jobs green:
Linux build/real PostgreSQL/Secret Service, Windows DPAPI, Android Keystore,
Wear Keystore, and existing enrolled Desktop↔Android history/platform relay E2E.
Downloaded XML proves PostgreSQL repository **17/17** and provisioning PostgreSQL
E2E 1/1, both zero skipped; Android/Wear Provider slot tests each 3/3, zero skipped.
Wire 6/6, crypto 5/5, install/recovery 20/20 (including exact local-counter
exhaustion/wipe), HTTP transport 3/3, migration 2/2 and native Linux secure store
5/5 also have zero failures/skips. The public-table canary scan includes raw,
JSON-escaped, base64 and UTF-8 hex representations; local metadata/SQLite scans
include both the credential and its forbidden plaintext digest.
The earlier [run 37182421694](https://github.com/fangbm/temvio/actions/runs/37182421694)
on `4b6df6767ee9e92704246fde366d7354ba614839` passed before the OD-059 amendment.
The evidence-only finalization commit changes documentation only; its full CI
is tracked in [PR #26 checks](https://github.com/fangbm/temvio/pull/26/checks),
and the final delivery reports that exact documentation head/run.

OD-059 regression evidence:

- Expiry at the precise seven-day cutoff leaves only the five permitted non-NULL
  logical fields. Source/ciphertext/digest/ACK/created/expiry timestamps are gone.
  SQL CHECKs reject expired remnants and incomplete/incorrect-window active rows;
  populated V9→V10 and fresh V10 agree including CHECK definitions.
- REQUESTED, DELIVERED and ACKNOWLEDGED all retire to the same minimal marker.
  Repeated maintenance yields byte-identical logical row JSON. Immediate and
  100-years-later/restarted same-revision retries stay DELIVERY_EXPIRED; lower
  revisions reject. Eligible higher revision gets a new exact seven-day window,
  then advances the marker at its own cutoff. Revocation never resets the marker.
- Repository tests use isolated PostgreSQL schemas so far-future maintenance
  cannot retire parallel workers' deliveries. E2E still scans every real public
  table for synthetic credential/binding/ref/ciphertext/digest/delivery-timestamp
  canaries. D8 device directory identity is preserved; expired mailbox source is
  NULL. HTTP returns 410 without manufacturing an expired delivery DTO, while
  committed target credential/binding/accepted counters remain unchanged.

The first follow-up run `37190989354` had repository **17/17** but its build E2E
failed because the fixture injected a whole-second wire deadline, earlier than
the precise SQL cutoff; the superseded run was cancelled. A test-only correction
uses the persisted SQL deadline and tests one microsecond before/at/after it.
Production time/wire semantics were unchanged; the corrected E2E passed 1/1 in
run `37191578433` above. Neither compilation nor that earlier failure is acceptance.

Provisioning PostgreSQL E2E is real SQL/Room/native Linux secure-store/Tink plus
Ktor's in-process HTTP application engine, not external TLS networking or final
Watch UI/runtime. It exercises explicit selection/comparison, exact retry after
Room reopen, committed install/recovery/duplicate, ACK ciphertext purge, and
every-public-table scans for public synthetic plaintext/hash/binding/ref canaries.
Native Android/Wear instrumentation proves slot isolation/recreation, not Wear
Agent or Provider requests. The standalone Android job's old relay-fixture test
reports an AssumptionViolated skip without its fixture; its actual round trip
runs separately in `agent-history-platform-e2e`.

The initial run `37177819193` failed the old v12→latest AgentSync migration test
because its destination remained v15 while fresh schema became v16. The test was
corrected to migrate through v16, rerun locally and passed in run `37178346473`.
Compilation success and PostgreSQL skips are not represented as test acceptance.

## Scope / decisions / review boundary

- `LOCAL_REVERSIBLE`: explicit SQL catalog, CAS/journal implementation and native
  capability tags; minute-scale maintenance cadence is not the exact expiry cutoff
  or credential/revision authority. Test-only synthetic canaries/fault hooks.
- OD-059 is RESOLVED FOR D9-03-01 by maintainer review, with the exact five-field
  indefinite security marker frozen in D9-03-00 section 4.4. No remaining decision
  or implementation blocker in this follow-up; implementation acceptance is
  complete for review with actual PostgreSQL/E2E/full-CI evidence above.
- OD-012 local SQLite encryption remains OPEN; no production-sensitive V3
  enablement is claimed. No changes to D2, D7 semantics, V3 DTOs/consent/frontier,
  workspace Envelope/AAD, SyncTransportWorker, or cryptographic primitives.
- D9-03-02 capability/network/STT, D9-03-03 Wear Agent/runtime/UI, nearby delivery,
  Phone proxy/context bridge, and D10 remain out of scope. Full D9-03 is not complete.
