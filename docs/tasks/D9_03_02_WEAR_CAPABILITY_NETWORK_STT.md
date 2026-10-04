# D9-03-02 — Wear Capability / Network Readiness / Optional STT

Status: IMPLEMENTED / AWAITING REVIEW; acceptance evidence in
[D9_03_02_CAPABILITY_ACCEPTANCE_RECORD.md](../D9_03_02_CAPABILITY_ACCEPTANCE_RECORD.md).
Baseline: PR #26 merged, `37b6759b1df88a3c6c4d2f55c2a7f6d4f8c717de`.
Authority: [D9-03-00](D9_03_00_WEAR_AGENT_PROVIDER_FREEZE.md) §8 C6 / §9 C7,
[AGT-014](../AGENT_DECISIONS.md), [vocabulary](../UBIQUITOUS_LANGUAGE.md),
merged [D9-03-01](D9_03_01_PROVIDER_CREDENTIAL_PROVISIONING.md).

## Scope

Only C6/C7 and manual readiness composition. No Wear Agent runtime/command or
conversation UI, Provider→Tool execution, confirmation UI, automatic speech
submission, D9-03-03, D10, nearby provisioning, Phone proxy/context/approval,
new Tool/Provider SDK, D7/V3/wire/crypto/server change or OD-012 resolution.

## Exact facts and derived values

`WearCapabilityFacts`: platformSupported, textInputSupported,
onDeviceSttAvailability (`UNSUPPORTED`, `LANGUAGE_UNVERIFIED`, `AVAILABLE`,
`TEMPORARILY_UNAVAILABLE`), speechPermission (`NOT_REQUESTED`, `GRANTED`,
`DENIED`), explicit selectedLanguageTag, derived aiEntrySupported.

```text
aiEntrySupported = platformSupported && at least one supported input path
effectiveAiEntryEnabled = aiEntrySupported && userEnabledAiEntry
providerReady = exact locally approved valid binding && supported adapter
                && !install/wipe blocked
                && (!credentialRequired || matching secure-store secret available)
requestReady = effectiveAiEntryEnabled && providerReady && networkReachable
```

Text support is independent of mic permission, STT and internet. Stable language
evidence remains distinct from permission, including permission revocation.
Network/probe failure never edits capability, user preference or Provider binding.
READY permits an attempt, not guaranteed Provider health. Probe failure is an
additional runtime reason; it does not rewrite the four frozen boolean formulas.

All blockers remain observable. Display-only precedence is ENTRY_UNSUPPORTED,
ENTRY_DISABLED, NO_BINDING, INSTALL_BLOCKED, BINDING_APPROVAL_REQUIRED,
CREDENTIAL_UNAVAILABLE, OFFLINE, PROVIDER_UNAVAILABLE; otherwise READY.
This ordering has no permission/transaction/semantic effect.

## Local settings and Provider composition

`userEnabledAiEntry` defaults OFF in Watch-private SharedPreferences and only
`setAiEntryFromExplicitUserAction` changes it. Provisioning, probe success,
foreground, network and STT never enable it. No Room schema bump (v16 retained).
Settings are not part of workspace V2/V3 consent or replication.

Existing credentialed approval remains owned by D9-03-01 revision/journal state.
Require ACTIVE exact target/config/binding/ref/install ownership, accepted revision
above rejection floor, active enrollment and METADATA_COMMITTED owning journal;
unfinished replacement/recovery/cleanup blocks use. Read actual purpose-scoped
secure-store bytes, validate availability, wipe transient copies, then recheck
config/revision/journal/enrollment after asynchronous reads. Missing/corrupt store
has no plaintext fallback. Invalid config cannot cause a request/secret read.

Credential-free configurations do not have a credential install/SAS transaction;
explicit local approval pins existing canonical metadata and route in Watch-local
settings. Exact edited metadata/route invalidates approval. No credential is
attached by inference. `WearProviderBinding` is local composition of frozen
metadata and explicit route selection, not a new wire DTO or Provider model.

`WearReadinessComposition.create` reuses Room repositories, Android Keystore and
existing OpenAI-compatible adapter. Caller owns client/scope and main-thread
speech lifecycle. `start` inspects capability/registers network; `bindingChanged`
must be called on config/credential/install/wipe change; `foregroundChanged`
controls foreground refresh. `close` unregisters/destroys services. This manual
service is deliberately not installed into the Wear command/D8 runtime yet.

## Network and synthetic probe

Default callbacks observe available/capability changes/loss, unregister on close,
ignore stale old-route callbacks and dedupe repeated availability. Public HTTPS
requires INTERNET + VALIDATED; this is route evidence, not Provider health.
Explicit credential-free local endpoint uses actual default/local route evidence
to permit an endpoint attempt without public validation. No host-based approval,
public ping, `/health` or `/models` endpoint. Credentialed endpoints require HTTPS
before secret resolution; local HTTP retains the frozen plaintext-risk boundary
for subsequent D9-03-03 presentation.

Existing `OpenAiCompatibleProvider.probe` sends its fixed synthetic instruction
and structured schema only; no Domain/user/history/summary/audio/SecretRef/
provisioning/binding digest in body. Credential is only HTTPS Authorization.
Redirects are disabled in the composition's client factory. Errors are typed
redacted classes, never response text or secrets.

One probe flight including cancellation/retirement, immutable input/generation,
10,000 ms total coroutine deadline. Ignore old generation completion. Foreground
retryable refresh uses 2/4/8/16/30/30… seconds, injected monotonic clock and
coroutine timing. Background/network refresh permits one eligible attempt, not an
unbounded background loop. Authentication, invalid config/response, unavailable
credential or unsupported Tools stop automatic probing until explicit retry or
config/credential/binding input change. Timeout/network/transient HTTP failure
allows backoff. Success restores probe state; no old command/write exists in
the probe service and none is automatically replayed.

## Optional on-device STT

minSdk remains 30. API30/no service: UNSUPPORTED, text independent. API31/32:
check isOnDeviceRecognitionAvailable, only createOnDeviceSpeechRecognizer;
selected language stays LANGUAGE_UNVERIFIED absent richer evidence. API33+:
after granted permission inspect checkRecognitionSupport; installed selected
language AVAILABLE, downloadable/pending language TEMPORARILY_UNAVAILABLE,
unsupported language UNSUPPORTED, unproven query LANGUAGE_UNVERIFIED.
Permission denial is not unsupported service/language. Selected tag is explicit;
no system-language or network-based fallback. All framework speech work is main
thread confined. Android docs require RECORD_AUDIO for SpeechRecognizer, so
richer passive inspection defers until a grant; passive probes never request it.

User speech action owns permission request/start. Adapter supports candidate,
failure/unavailable/denial, busy, cancellation, destruction, permission revocation,
and discards callbacks after cancel/language change. Known unsupported or
download-pending language cannot start. No cloud recognizer, Phone microphone, audio upload or automatic
model download; model download UI/action implementation is outside this slice.
Unverified language can be tried only by explicit user speech action; it is never
reported AVAILABLE without support evidence. Candidate never invokes Agent/Tool.

API evidence: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer),
[RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport),
[network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).
These references support API boundaries, not physical recognition acceptance.

## Required verification and delivery

- JVM: all readiness reasons/formulas and independence; exact approval/secure-store
  missing/corrupt/wipe/recovery races; route combinations/stale loss; probe
  single-flight/deadline/backoff/stop/change/stale result/no replay; body canaries.
- STT deterministic adapter: API30/31/32/33, service/language availability,
  passive zero requests, permission denied/granted/revoked, busy/error/cancel,
  stale recognition/support/permission callbacks, no command submission.
- Real Android/Wear instrumentation: native callback registration/unregistration,
  actual capability mapping, local preference restart/exact approval, passive
  speech inspection, actual NOT_REQUESTED/DENIED/GRANTED permission. Record service
  availability truthfully. If service absent, that is unavailable-path evidence,
  not physical STT success; remaining mappings use deterministic fake tests.
- Targeted Gradle tests, full repository CI, scoped diff; independent Draft PR to
  feature/d9-02-agent-sync, head/files/counts/platform/CI/decision evidence. No merge.

OD-012 remains OPEN. D9-03-03 owns real Watch Agent/read/confirmed-write and
command surface; this task does not mark full D9-03 complete.
