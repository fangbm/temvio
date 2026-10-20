# D10-05F — Wear target Provider credential provisioning coordinator

## Scope and authority

Baseline: `93a2aab9714a729ed2f53e60e0077372a237cbf7` on
`feature/d9-02-agent-sync`. This independent foundation supplies the target-side
orchestration boundary exposed by PR #39, without changing that PR or its Compose UI.
The [reviewed D10-05 gap](https://github.com/fangbm/temvio/blob/ce8b6f27ec6392526f7c601637c50096ac8355a4/docs/tasks/D10_05_WEAR_FINAL_UX.md)
remains a UI integration follow-up after foundation review.

Authority remains [C1–C5](D9_03_00_WEAR_AGENT_PROVIDER_FREEZE.md),
[D9-03-01](D9_03_01_PROVIDER_CREDENTIAL_PROVISIONING.md), its
[acceptance record](../D9_03_01_PROVISIONING_ACCEPTANCE_RECORD.md),
[AGT-014](../AGENT_DECISIONS.md), [SYN-018](../SYNC_SECURITY_DECISIONS.md),
[OD-042/058/059](../OPEN_DECISIONS.md) and the
[D10 presentation boundary](D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md).
OD-012 OPEN remains unchanged. D10-06 is unstarted by this slice.

## Ownership and reused primitives

`ProviderCredentialTarget` is the presentation-facing Application interface.
`ProviderCredentialTargetCoordinator` serializes explicit intents for one
lifecycle-owned target/config interaction. The owner cancels and joins outstanding
intents before replacing it; repository CAS remains durable authority. It owns no
scope, polling timer, alternate database, revision allocator or crypto implementation.

The existing `ProviderCredentialProvisioningService` alone reserves revisions,
authenticates/decrypts new deliveries, records exact SAS approval, imports prepared
slots and recovers install journals. The coordinator uses the existing
`ProviderCredentialProvisioningRepository`, reservation/delivery/comparison/binding
DTOs, enrollment repository, private-key/device-credential/Provider secure stores,
authenticated ACTIVE directory and dedicated mailbox transport.

`WearTargetProviderProvisioningComposition` is manual platform-root construction:
existing Room repositories, Android Keystore, Tink, D8 configured HTTPS endpoint,
`KtorProviderCredentialTransport` and `KtorSyncLifecycleTransport`. Existing
ProviderConfig `provisioningBinding(...)` supplies the exact local binding. The
bearer callback rechecks the single ACTIVE enrollment after secure-store access.
No lifecycle, command UI, Agent runtime or readiness wiring is added.

## Sanitized API and states

| Explicit intent | Effect / boundary |
| --- | --- |
| `resume(config)` / `recover()` | Existing service recovery, then durable projection; no publish, approval or install |
| `reserveAndPublish(binding, provisioner)` | Explicit existing binding/source; service reservation, then exact mailbox publish |
| `fetchDelivery()` | Fetch exact target/config/revision/source; comparison only, never user approval |
| `approveComparison(displayed, independentlyComparedCode)` | Exact current ephemeral display identity plus existing eight-digit SAS check; never install |
| `installApproved()` | Existing service install from exact durable approval, then informational ACK |
| `retry()` | Retry publication/fetch/ACK, or recover/project interrupted work; never implicitly approve/import |

Typed states distinguish `NotStarted`, `NotEligible`, `Ready`, `Reserved`
(publication confirmed/unconfirmed and comparison refresh needed), `ComparisonReady`,
`UserApproved`, `Installing`, `Installed`, `Expired`, `RetryableFailure`, `Blocked`
and `RecoveryRequired`. Routing facts retain exact opaque DeviceId/config/revision/source.
Comparison exposes only safe Provider label, routing facts and the eight digits;
its `toString` redacts the code. No public state contains ciphertext, credential,
SecretReference, binding digest, private key, bearer, journal or raw server body.

Errors retain typed protocol rejection reasons. Transport failures distinguish
authorization, conflict, invalid response, retryable network/server and rejection;
messages/bodies are never copied. Local secure-store failure remains distinct.
ACTIVE eligibility is freshly authenticated on security-sensitive progression,
including after fetch; no permanent directory cache or device-ID normalization.

## Durable reservation, restart and cancellation

The returned service revision is the only revision source. A pending same-binding,
same-source reservation is reused after publication failure or repeated clicks.
Recreation cannot know whether HTTP publish completed, so reports unconfirmed
publication and permits exact-request retry; it never allocates a revision on resume.
An observed expired delivery stays explicit; only an explicit new reservation can
allocate the next revision. The frozen server expiry remains authoritative.

Comparison display handles are process-local and identity checked. Source/config/
revision/envelope replacement invalidates the old display handle. AUTHENTICATED
resume requires fetching a new comparison. USER_APPROVED resume preserves durable
approval without calling `compare()` again (that service call would reset phase).
The exact fetched ciphertext/binding digests must match durable approval before
explicit install; the existing SAS helper is reused, never reimplemented.

Cancellation propagates; recovery determines durable truth. Recovery never imports
an already committed credential or creates a fresh reservation. Prepared/imported
uncommitted slots are handled solely by existing journal recovery. Explicit install
after recovery remains a separate user intent.

## Installation versus ACK

The existing local ACTIVE commit is installation truth. `Installed` separately
reports ACK pending/confirmed/expired/blocked. ACK failure cannot undo the install.
Retry sends only the receipt and never calls install again. After process recreation,
accepted revision/digest/source metadata reconstructs an informational DUPLICATE
receipt; no ACK-delivered bit or ciphertext is added to Room. Thus restart may
resend an idempotent informational receipt even if a previous ACK succeeded.

An explicitly fetched duplicate delivery can be checked by explicit install through
the existing service's same-value DUPLICATE branch; unequal value remains an
integrity conflict. This is separate from ACK retry and never reimports a secret.
Wipe floors, enrollment/config CAS ownership and source checks remain unchanged.

## Verification evidence

Targeted coordinator verification: **28/28 passed, zero skipped**, with Wear
`compileDebugKotlin` successful on JDK 17. Tests use
real file-backed Room repositories, the existing service and Tink crypto, plus the
existing Desktop platform secure store over an isolated test backend. Only network
ports are fake. Checkpoint/Deferred barriers exercise concurrency/crashes without
synchronization sleeps. SQLite contains neither credential plaintext, its Base64Url
encoding nor its plaintext hash.

Required coverage: full explicit happy path; wrong code/source/revision; cancellation
after comparison; reservation recreation and exact publication retry; expiry;
USER_APPROVED recreation; each existing install checkpoint; install/ACK separation;
duplicate equal/unequal delivery; wipe/enrollment loss; fresh ACTIVE directory;
rapid reserve/install; stale comparison/binding; sanitized errors; real Room reopen.

Relevant suites actually executed locally: **129 passed, 1 skipped**, zero failures.

| Suite | Actual result |
| --- | --- |
| Application `*ProviderCredential*Test` + `*DesktopPlatformSecureStoreTest` | 61 passed (includes new 28); 1 existing PostgreSQL E2E skipped because local database is not configured |
| `ProviderCredentialWireTest` | 6/6 passed |
| `ProviderCredentialMigrationTest` | 2/2 passed; existing v15→v16/fresh schema unchanged |
| Wear `testDebugUnitTest` | 60/60 passed, including existing readiness/probe/binding/privacy/permission coverage |

Commands (JDK 17, explicit existing Android SDK):

```text
./gradlew :shared:application:desktopTest --tests '*ProviderCredentialTargetCoordinatorTest' :apps:wear:compileDebugKotlin --no-daemon --no-configuration-cache --console=plain
./gradlew :shared:application:desktopTest --tests '*ProviderCredential*Test' --tests '*DesktopPlatformSecureStoreTest' :shared:sync:desktopTest --tests '*ProviderCredentialWireTest' :shared:database:desktopTest --tests '*ProviderCredentialMigrationTest' :apps:wear:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
git diff --check
python test-support/d10-05f/check-boundaries.py
python test-support/d10-04/check-boundaries.py --source-only
python test-support/d10-03/check-boundaries.py --source-only
python test-support/d10-02/check-boundaries.py --source-only
python test-support/d10-04/check-candidates.py
```

All checks above passed. The local PostgreSQL skip is not claimed as acceptance;
the full CI build supplies real PostgreSQL and native Secret Service evidence.
All five existing CI jobs must actually execute: build, desktop-windows,
android-keystore, wear-keystore, agent-history-platform-e2e. Exact-head run links
and conclusions are recorded in the Draft PR, avoiding a commit solely to reference
its own SHA. No real Watch mailbox/UI provisioning success is claimed by the fake
network-port coordinator tests; Android/Wear CI retains the existing native store
and runtime instrumentation without changes.

## Scope outcome

No migration. No Room schema, wire DTO/codec, HPKE/SAS, revision arithmetic,
prepared-slot, server, expiry/tombstone, D7/D8/D9/V3, Agent/Tool, WatchPermissionPolicy,
STT/readiness or Compose changes. Existing provisioning tests remain unchanged.
The CI boundary check retains historical D10-04 source checks in source-only mode
and applies a separate exact-base foundation diff fence.

LOCAL_REVERSIBLE choices: coordinator/type names, lifecycle ownership, explicit
non-polling intents, ephemeral display handles and sanitized failure presentation.
No new protocol decision is made. FOUNDATION_GAP: PR #39 UI consumption remains
deferred pending this foundation's review; this slice does not modify/sync PR #39.
BLOCKED_BY_DECISION: none currently identified.
