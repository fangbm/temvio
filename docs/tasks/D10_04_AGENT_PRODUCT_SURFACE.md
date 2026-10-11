# D10-04 — Agent Product Surface

Status: **IMPLEMENTED / AWAITING REVIEW**. ContextAnchor foundation RESOLVED via merged PR #38.
Authoritative baseline: `17fd6e7ef117cdb899b7e2f28d3a1a8bf13a58ed`
([merged D10-04F PR #38](https://github.com/fangbm/temvio/pull/38)), including accepted D10-03.
Normal baseline merge into this implementation branch: `2dd0ddb493153e5b69d8199b1dd96a4dd4710259`; no rebase or history rewrite.
Branch: `feature/d10-04-agent-product-surface`.
Target: `feature/d9-02-agent-sync`. Keep Draft; no merge or D10-05/06.

## Authority and ownership

The current maintainer task, [D10-00 freeze](D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md),
[D9 runtime](D9_AGENT_RUNTIME.md), [Agent decisions](../AGENT_DECISIONS.md),
[Domain invariants](../DOMAIN_INVARIANTS.md), [ownership](../MODULE_OWNERSHIP.md),
[implementation contract](../IMPLEMENTATION_CONTRACT.md),
[vocabulary](../UBIQUITOUS_LANGUAGE.md) and accepted D10-01/02/03 govern this slice.
Historical slice fences remain historical. This slice changes Desktop/Android
presentation; existing D9 runtime, typed Tools, permissions and audit own truth.

App-owned reusable `apps/presentation` sources compile in Android/Desktop without
a new module. `AgentWorkspaceCoordinator` projects immutable persisted collections
and delegates only explicit `createThread`, `run`, `confirm`, `deleteThread` and
local policy setting actions. `shared:ui` remains presentation-only; the platform
roots still own services, lifecycle, typed primary navigation and secondary routes.
No framework, service locator, dependency or schema migration is introduced.

## Threads, drafts and single-flight

Loading lists existing application-owned threads; mounting Agent never creates a
thread or Sends. New conversation is explicit. Existing title facts are used;
absent titles display **New conversation**, never UUID fragments or generated titles.
Provider/model selection preserves thread/history and never submits a command.

Command drafts are per-thread session presentation values, retained across ordinary
navigation, theme and layout changes. They are not messages/history/sync; process
loss may clear them. Send is explicit, disabled for blank draft, invalid/missing
thread, unavailable local Provider, pending confirmation or in-flight action.
Multiline Enter never Sends; no new keyboard submission shortcut.
The coordinator rejects duplicate in-flight intents; existing runtime thread locks
remain authoritative. Pending confirmation disables switching/new conversation to
avoid identity ambiguity. No background command replay or UI runtime is added.

## Conversation and structured truth

Prose messages are primary. Tool events are attached to their real source message,
with exact separate PROPOSED / WAITING_CONFIRMATION / RUNNING / COMPLETED / FAILED /
DENIED labels, and the complete distinct ToolResult status vocabulary. Actual
recorded mutation links and AgentAction audit explain committed business effects;
assistant prose never proves execution. No optimistic UI result is manufactured.

Existing normalized snapshot JSON is read only for display labels/facts. The mapper
does not decode sync operations, construct Tool inputs, compute legality, infer
missing before facts or add a second DTO contract. Technical arguments/preview/
result JSON and IDs are expandable. Planner snapshots remain proposals, not Active
Calendar. Apply delegates to the existing session branch and runtime revalidation;
after process loss a fresh preview is required. Undo remains an existing trusted
compensating mutation, not evidence deletion.

## Confirmation restoration and outcomes

The coordinator reloads the exact persisted pending AgentToolCall with thread ID,
call ID and preview JSON. No UI-only pending identity. Remount restores it without
Send/Confirm/Deny. Review later, Back, detail close, resize and theme changes only
close presentation; the call remains WAITING_CONFIRMATION. Explicit review reopens
the same persisted preview. Confirm/Deny use that exact call ID; duplicate or stale
UI identities are not submitted.

Readable before/after facts, semantic type labels, permission requirement and a
runtime revalidation explanation precede expandable technical detail. All preview
facts remain scrollable. Success, Denied, Stale, Conflict, InvalidInput, NotFound,
Infeasible and InfrastructureFailure display persisted typed results distinctly.
There is no force apply, ignore changes or stale overwrite. A stale action requires
a new proposal. Presentation changes never execute business actions.

## Provider separation and security

Agent shows model/status, capability observation and a Provider settings route.
Configuration and credentials belong only to existing platform local Provider
settings. No duplicated Provider or V2/V3 storage. Credential input is masked,
transient and confined to the explicit settings editor; it never enters navigation,
Agent coordinator display values, messages, screenshot fixtures or logs.
The secret store is checked for availability without copying a secret into display.
Credentialed HTTP is rejected; explicit credential-free HTTP remains supported
with a truthful prompt/schedule plaintext-in-transit warning. No endpoint rewriting.

The existing explicit capability probe is observed, never additionally scheduled.
Unsupported structured Tools means **Chat only — structured actions unavailable**,
ordinary Send with zero Tool schemas. Prose cannot execute mutations. A real probe
Unavailable result blocks Send; an explicit retry restores eligibility for the next
explicit Send, without sending anything itself. No implicit retry or Wear readiness
semantics are introduced. Existing runtime/adapter still validate every request.

## Permissions, deletion and Sync ownership

Existing Settings gains the complete device-local Agent permissions editor. Options
are accepted only if the existing `AgentPermissionPolicy` constructor permits them;
bulk/destructive/external remain DENY-only. Edits read and save the owning repository
policy. No permission Tool/sync/ceiling change. Future evaluation uses the existing
Permission Engine; opening/editing Settings never Sends or writes business facts.

Deletion requires explicit confirmation through `deleteThread`. Local conversation
rows/summaries/raw provenance are removed according to D9; committed AgentAction,
D7 ChangeLog and business changes remain. Already synchronized/historical encrypted
copies are not promised erased. No Undo, audit deletion or operation identity rewrite.

V2 Agent-origin business acknowledgement and V3 history consent/export remain
independent in D10-03 Sync/Security. Agent links out; no duplicate toggle, export,
catch-up or all-history-uploaded claim. OD-012 remains OPEN.

## ContextAnchor foundation — RESOLVED

The historical D10-04 audit found no approved typed public runtime entry. That gap
was independently resolved by [D10-04F](D10_04F_CONTEXT_ANCHOR_FOUNDATION.md),
[PR #38](https://github.com/fangbm/temvio/pull/38), merge
`17fd6e7ef117cdb899b7e2f28d3a1a8bf13a58ed`. This presentation follow-up consumes
`AgentRunService.run(threadId, command, AgentTurnContext(contextAnchor = anchor))`
without changing the accepted foundation, Tools, Provider or persistence.

`AttachedAgentContext(anchor: AgentContextAnchor, label: String)` is ephemeral
app-owned coordinator state. The readable label is display-only; runtime receives
only the typed anchor. It is not a command, message, thread attribute, summary,
provenance, Tool result or V3 field. New coordinator/process starts without it;
history is never consulted to reconstruct it. Context is visibly shown near the
composer with **Local context for this session** and an explicit **Clear context**.
Clear/replace are local presentation operations with no Provider/repository I/O.

Contextual open attaches/replaces then selects Agent, permitting command drafting;
it neither creates a thread nor Sends. Task detail passes its exact TaskId;
Event/FocusBlock/CourseSession/Exam detail passes the existing CalendarSourceRef.
Today/Calendar broad open passes the exact finite current CalendarViewport
(date range and display time zone). A concrete selection takes precedence. No
cached titles/status/calendar facts are sent as anchor authority. Existing edit,
Open Course/Open Exam and linked-Task actions retain their semantics.

Desktop's global Open Agent callback is supplied by the root, which owns current
screen selection truth: Task, CalendarSource, visible Today/Calendar viewport,
then an already-selected supported reference. Shell/sidebar navigation owns no
business selection. Selected Planner/Settings profile, exact History mutation,
Course and Exam expose small contextual actions. AcademicYear, Semester,
PeriodTemplate, schedule rules, unselected Planner/History and unsupported screens
remain unanchored; no synthetic selection or fallback reference is invented.
Android's primary Agent tab is ordinary navigation, never stale context inference.

Attachment remains visible through theme/resize/recomposition, Tool detail,
confirmation, ordinary navigation and explicit thread switch/new conversation,
until explicit Clear or contextual replacement. Ordinary navigation preserves an
existing visible session attachment; it creates no new anchor. Busy actions reject
attachment/draft/thread edits. Explicit Send captures one immutable frame of
selected thread, exact command and current anchor before suspension; it never
rereads another screen or concatenates a label/context into the user command.
Stored USER content remains the exact typed command. The accepted runtime's
existing context budget and authoritative typed Tool reads remain unchanged.

Confirm/Deny still use the exact original persisted ThreadId/ToolCallId/preview.
A newly attached context cannot alter that proposal or enter its continuation.
After process restart pending confirmation restores but the attachment does not.
Model responses cannot replace/clear attachment. Passive navigation/Back/theme,
resize/open/clear never Send, Confirm, Preview, Apply, Undo or write history.

## Responsive and accessibility contract

Desktop remains a typed primary destination; Android retains Today/Calendar/Tasks/
Agent/More. The shared workspace uses an optional thread pane only when its available
content width >=860dp, height >=480dp and font scale <1.6; otherwise a single
conversation and thread selector. Desktop shell may consume width independently;
Android does not receive Desktop primary navigation. Conversation/composer text is
bounded; narrow, short and 200% draft/detail bodies scroll. The attachment label
and Clear action stay outside the draft scroll/IME region, visible before Send.
Labels keep their full accessibility text while limiting visual overflow to two
lines; only the displayed hint may truncate, never the typed anchor. Send stays in its own
48dp-or-larger action row outside the focused draft's scroll/IME region; short
composer allocation is bounded by the actual available height. Tool/confirmation detail is an owned
scrollable dialog. Android Back closes transient detail before shell navigation,
never executes a pending call. Semantic authors/headings/state labels, focusable
named actions, shared role contrast and >=48dp controls remain. Final physical
screen-reader/motion acceptance belongs to D10-06.

Required actual Compose candidate matrix: Desktop 640x720, 800x600, 1024x768,
1280x800, 1440x900, 1920x1080; Android 360x800, 480x900, 600x960, 840x900,
1024x768, 800x360; Light/Dark plus 200% text. New candidates live only under
`fixtures/d10-04/screenshots/`. Historical D10-01/02/03 PNGs/manifests stay unchanged.
Synthetic HTTP fixtures are not live Provider/physical-device acceptance.

## Verification evidence

Historical pre-foundation evidence below (PR #37 head `ff30f217`; run `37776983345`):
Windows / JDK17 / Android API35 Google APIs x86_64. Current follow-up evidence is recorded separately below.

| Evidence | Result |
|---|---|
| New Agent coordinator / actual runtime / Room | 26/26 |
| New mounted Desktop Agent Compose tests | 8/8 |
| Complete Desktop suite, including D10-01/02/03 regressions | 140/140 |
| Canonical shared-ui tests | 3/3 |
| Existing AgentPersistenceTest + AgentRunIntegrationTest | 9/9 + 14/14 |
| Native Android AgentWorkspaceInstrumentedTest | 7/7 |
| Combined native ProductWorkspace + AgentWorkspace at CI default phone dimensions/density | 10/10 + 7/7 |
| Complete Android CI instrumentation set (relay fixture excluded as in CI) | 32/32 |

Commands:

```powershell
.\gradlew.bat :apps:desktop:test --tests '*AgentWorkspace*' :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest --no-daemon
.\gradlew.bat :apps:desktop:test :shared:ui:desktopTest :shared:database:desktopTest --tests '*AgentPersistenceTest*' --tests '*AgentRunIntegrationTest*' --no-daemon
adb -s emulator-5562 shell am instrument -w -r -e class dev.agenticscheduler.android.AgentWorkspaceInstrumentedTest dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner
.\test-support\d10-04\capture-android.ps1 -Serial emulator-5562 -Sdk C:\ProgramData\Android
python test-support/d10-04/check-boundaries.py
python test-support/d10-04/check-candidates.py --compare-generated
git diff --check
```

Local Gradle uses the isolated `D:\codex\asp-d10-gradle-home`; Android SDK is
`C:\ProgramData\Android`. UI fixtures exercise existing AgentRunService/typed Tools/
Application/Room; the Provider HTTP peer and structured capability result are
synthetic. Native Back/callback/layout behavior is actual Android instrumentation;
no live Provider or physical Wear evidence is claimed.

The retained Planner/provider link regression now verifies the separate Settings
Provider panel, retaining its unchanged branch, zero business mutation and zero
Provider-request assertions. Initial dialog refresh is covered by mounted tests:
Confirm/Deny must become enabled after the completed refresh, and use the same
persisted pending identity. Short-height Send and 200% confirmation remain reachable.

Historical D10-03 candidates/manifests are untouched and checked against accepted
source revision `cdc8504527cc636f5d2932b7e00785f4be9ecaad`; their PNG/hash checks remain
mandatory. New candidate/source/matrix checks are independent. The native capture
runner verifies requested display/font settings before accepting an image.

[New candidate manifest](fixtures/d10-04/screenshots/capture-manifest.json):
74 actual captures (38 Desktop + 36 Android), covering the full required size/theme
matrix, 200% text and short-height; [representative gallery](fixtures/d10-04/screenshots/README.md).
Inspected Desktop narrow/wide, Light/Dark, actual Tool result, 200% confirmation
and short-height; Android narrow/expanded, Light/Dark, stale, 200% confirmation
and short-height. Dialog detail and short composer bodies scroll; Confirm/Deny/Send
reachability is asserted. No new screenshots were produced by image editing.

First CI on `7ec131e241b5439b5788462fa82e3fdcefc9f7ea` (run `37770045756`)
completed build/Windows/Wear/platform E2E successfully, but exposed an existing
History native Back harness race. Local repetition reproduced 3 failures in 5;
the test now settles the restored UI frame and awaits native Back dispatch with
all original compensation/audit/detail/route assertions retained, plus visible
detail assertion. Five successive repetitions passed. Combined instrumentation
then exposed Send outside the visible composer at default phone density/IME:
Send is now anchored outside the scrolling draft; tests assert it is displayed
before clicking, retaining zero-schema/zero-Tool/zero-action checks in chat-only.
The final combined suite passed 17/17 and complete Desktop rerun passed 140/140.
The superseded run is not final acceptance evidence; new exact-head full CI is
mandatory. No History/Undo production semantics were changed.

Run `37774776480` on `1962fc6f195fe1a4c8180c20c2e2147f26b6a8ef`
passed build/Windows/Wear/platform E2E, but exposed a second existing screenshot
harness race: opening the PlanningProfile editor during theme-remount refresh
was rejected by its existing busy guard. The test now settles refresh before
opening the editor and asserts command acceptance; all six Light/Dark scenes,
policy scroll and zero-Provider-request assertions remain. Five consecutive
native repetitions passed, followed by the complete Android CI instrumentation
set at default phone dimensions/density: 32/32. No Planner production behavior
or historical screenshot/manifest changed. This superseded run is not final
acceptance evidence.

Exact-head full CI remains required before delivery: all five jobs must actually
execute/pass (build, desktop-windows, android-keystore, wear-keystore,
agent-history-platform-e2e). The Draft PR check suite carries the authoritative
final head/run link; no pre-change or cancelled/unallocated run counts.

## Contextual follow-up verification

Current follow-up executed locally on Windows / JDK17 and an isolated Android
API35 Google APIs x86_64 emulator:

| Evidence | Result |
|---|---|
| Agent coordinator/runtime/Room set (26 retained + 13 attachment) | 39/39 |
| Mounted Desktop Agent Compose (8 retained + 5 contextual-entry/layout) | 13/13 |
| Complete Desktop, including all D10-01/02/03 regressions | 158/158 |
| shared-ui | 3/3 |
| Complete shared Agent, including the 10 accepted anchor/assembly tests | 58/58 |
| AgentPersistence / AgentRunIntegration / accepted ContextAnchor integration | 9/9 + 14/14 + 12/12 |
| Native Android AgentWorkspace (7 retained + 2 attachment) | 9/9 |
| Complete Android CI instrumentation set, same relay-fixture exclusion | 34/34; no failures/skips |
| Native capture matrix | 7/7 variants; 52 Desktop + 44 Android = 96 PNGs |

Commands actually used (isolated Gradle user home as above):

```powershell
.\gradlew.bat :apps:desktop:test :shared:ui:desktopTest :shared:agent:desktopTest :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest --no-daemon --console=plain --gradle-user-home D:\codex\asp-d10-gradle-home
.\gradlew.bat :shared:database:desktopTest --tests '*AgentPersistenceTest*' --tests '*AgentRunIntegrationTest*' --tests '*AgentContextAnchorIntegrationTest*' --no-daemon --console=plain --gradle-user-home D:\codex\asp-d10-gradle-home
adb -s emulator-5554 shell am instrument -w -r -e class dev.agenticscheduler.android.AgentWorkspaceInstrumentedTest dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -r -e notClass dev.agenticscheduler.android.D9PlatformRelayInstrumentedTest dev.agenticscheduler.android.test/androidx.test.runner.AndroidJUnitRunner
.\test-support\d10-04\capture-android.ps1 -Serial emulator-5554 -Sdk C:\ProgramData\Android
python test-support/d10-04/record-candidates.py
python test-support/d10-04/check-candidates.py --compare-generated
python test-support/d10-04/check-boundaries.py
git diff --check
```

Real runtime HTTP input proves exact typed anchor once, zero display label,
original USER text, replacement A→B and later Clear→no anchor. In-flight edits
are rejected; explicit thread switch keeps the visible attachment. Reopened Room
with a fresh composition restores the exact pending ToolCall but no attachment.
Agent rows/provenance and real historical export source contain no local referent
or display canary; accepted foundation integration independently exercises actual
V3 export/outbox and restart. Existing confirmation/read/write/Planner/Undo and
chat-only regressions remain in the complete suites.

Actual UI controls cover Task, all four CalendarSource variants, Today/Calendar
finite viewports and Desktop global selection precedence / ordinary fallback.
The existing Planner/History/Academic selection mappers preserve typed identities
and leave unsupported academic records without a synthetic anchor. Android tests
exercise contextual Task open, theme/navigation retention, exact explicit Send,
Clear, primary-tab no-inference, native Back and reachable 200% controls.

The first new Android Back check pressed Back before the selected-detail dialog
settled and exited the test Activity. The test now asserts the visible dialog and
waits for actual dismissal; the full Agent 9/9 and complete Android 34/34 passed.
No navigation/runtime workaround or removal of the no-Send assertions was made.

Only D10-04 captures were regenerated. The manifest/source hashes and all required
sizes/Light-Dark/200% states pass; historical D10-01/02/03 PNGs/manifests are unchanged.
Task / CalendarSource / viewport labels, Clear and Send were visually inspected in
Desktop 1280×800 and 640×720 Light/Dark and Android 360×800 Light/Dark, including
200% Task attachment. These remain candidates awaiting maintainer visual review,
not live Provider or physical Wear acceptance.

The pre-foundation run `37776983345` is historical only. The final Draft PR head's
check suite/run URL is authoritative: all five jobs must actually execute/pass
(build, desktop-windows, android-keystore, wear-keystore, agent-history-platform-e2e).
No superseded head or cancelled/unallocated job counts toward final acceptance.

## Exclusions and remaining gates

No new Agent engine/Tool/schema/capability/permission ceiling; no Domain/Application/
Planner/D7/D8/D9 semantic change, migration, wire/crypto/server or Wear behavior.
No context summary authority, Provider SDK/protocol or provider-owned memory.
FG-02 typed D8 candidate selection, FG-03 pairing orchestration and FG-04 directional
progress remain deferred/non-blocking. ContextAnchor foundation is accepted/merged
and consumed through its reviewed typed API; no hidden workaround. D10-05/06 are not started. OD-012 stays OPEN.
LOCAL_REVERSIBLE: presentation copy, layout spacing, pane threshold and session
overlay visibility. No new business defaults or high-impact decisions selected.
