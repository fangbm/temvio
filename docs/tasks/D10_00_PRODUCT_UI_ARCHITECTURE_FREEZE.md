# D10-00 — Product / Information Architecture / UI Architecture Freeze

Status: **FROZEN / MAINTAINER APPROVED / MERGED**.
Task: **D10-00 architecture review passed**; [PR #30](https://github.com/fangbm/temvio/pull/30)
merged as `f8efbde97e185cbcaf704f5a487538cf147b034d`.
Date: 2026-10-06.
Baseline: `feature/d9-02-agent-sync`, `378630cb63447443b02cae4599845b06c9353b5b`
([D9 final closure PR #29](https://github.com/fangbm/temvio/pull/29)).
Delivery branch: `docs/d10-00-product-ui-architecture-freeze`.

This packet records the maintainer-approved presentation target and final
D10-00 review decisions. It implements no UI. Execution order is
**D10-00 -> D10-00A -> D10-01 -> D10-02...**. D10-00A was unstarted at this
freeze; its separately reviewed [authoring task](D10_00A_ACADEMIC_AUTHORING_FOUNDATION.md)
now owns that implementation. D10-01 remains unstarted.
Passing CI is documentation
validation and predecessor regression evidence, not final product/visual acceptance.

## 1. Goal, authority and scope

Freeze product IA, platform navigation, a canonical design system, adaptive rules,
state ownership, application boundaries, accessibility and visual regression
strategy **before** a broad UI rewrite. [Capability inventory](../D10_CAPABILITY_INVENTORY.md)
records actual source evidence and missing surfaces rather than deriving capability
from roadmap aspirations or demo boards.

Binding authorities, in addition to the current task instruction:

- [READ_FIRST](../READ_FIRST.md), [Domain invariants](../DOMAIN_INVARIANTS.md),
  [open decisions](../OPEN_DECISIONS.md), [implementation contract](../IMPLEMENTATION_CONTRACT.md),
  [architecture diagrams](../ARCHITECTURE_DIAGRAMS.md), [module ownership](../MODULE_OWNERSHIP.md),
  [dependency policy](../DEPENDENCY_POLICY.md), [vocabulary](../UBIQUITOUS_LANGUAGE.md),
  [coding policy](../CODING_AGENT_POLICY.md), [roadmap](../ROADMAP_D5_D9.md).
- [Calendar decisions](../CALENDAR_DECISIONS.md), [academic invariants](../ACADEMIC_INVARIANTS.md),
  [academic decisions](../ACADEMIC_DECISIONS.md), [Planner decisions](../PLANNER_DECISIONS.md),
  [Planner rewrite decisions](../PLANNER_REWRITE_DECISIONS.md),
  [History/Sync decisions](../HISTORY_SYNC_DECISIONS.md),
  [Sync/security decisions](../SYNC_SECURITY_DECISIONS.md), [Agent decisions](../AGENT_DECISIONS.md).
- D5–D9 task and acceptance evidence linked in the inventory, including the
  [D9-03 freeze](D9_03_00_WEAR_AGENT_PROVIDER_FREEZE.md),
  [capability contract](D9_03_02_WEAR_CAPABILITY_NETWORK_STT.md) and
  [Watch runtime contract](D9_03_03_WEAR_AGENT_RUNTIME.md).

D10 changes presentation, navigation and interaction. D2–D9 remain authoritative
for entity meaning, time, Planner, mutation/audit/Undo, Sync/conflicts, security,
Agent history, Tools, permissions and provisioning. Historical slice exclusions
do not prohibit the current task's expressly authorized presentation architecture.

## 2. Baseline inspection conclusion

- Android's 1,276-line MainActivity and Desktop's 1,140-line Main.kt currently
  combine composition, multiple remembered feature states, forms, parsers and
  services in one Agenda/Day lazy-list surface. No final shell or shared theme exists.
- Wear's 305-line activity has Agenda/Agent switching and extracted runtime,
  session, readiness, network and speech adapters. It is a local replica, not a
  Phone display or remote Agent. Full mobile/desktop functionality is not its IA.
- Existing Compose/manual composition and typed Application/Planner/Agent services
  support the proposed architecture. Dependency inspection found no unavoidable
  state/navigation/DI framework. No architecture blocker was found for OD-060.
- Persistence and typed Sync vocabulary for Courses/Exams exist. Ordinary local
  create/edit commands do not. The inventory distinguishes this from legitimate
  Sync receive and explicit conflict-resolution application paths.

No new app/device run is claimed by this audit. Existing native/desktop/relay
acceptance and the real STT-service-absence limitation remain preserved.

## 3. OD-060 — approved target architecture

**ACCEPTED / RESOLVED FOR D10** by maintainer review; record synchronized in
[OPEN_DECISIONS](../OPEN_DECISIONS.md).

CD-007's `PENDING` wording records the D5 acceptance-time OD-060 baseline;
this later D10 resolution supersedes that status for D10 without rewriting
the historical D5 architecture decision or implementation.

Use project-owned typed presentation architecture, Compose, explicit screen
coordinators, typed destinations and existing manual/platform composition. Do not
introduce project-wide Redux, MVI/MVVM framework, third-party navigation, DI,
service locator or a generic shared application store.

```mermaid
flowchart TB
    FACTS[Domain / Application authoritative state]
    COORD[Platform-owned typed screen coordinator]
    SCREEN[Compose screen / presentation primitives]
    INTENT[Explicit typed user intent]
    SERVICES[Existing Application / Planner / D7 / D8 / D9 service]
    ROOT[Platform composition root]
    FACTS --> COORD
    COORD --> SCREEN
    SCREEN --> INTENT
    INTENT --> COORD
    COORD --> SERVICES
    SERVICES --> FACTS
    ROOT --> COORD
    ROOT --> SERVICES
```

Screen coordinator contracts are feature-specific: immutable observable display
state, typed user intents, explicit operation results, loading/error handling and
lifecycle-owned jobs. They project facts and delegate commands; they do not own
Domain rules, permission decisions, Planner results, causal state or transactions.
Compose collects state and sends intents. Repository implementations and platform
handles are constructed only at the composition root, never discovered globally.

No screen or shared presentation module writes a DAO, uses a Room record as a
screen model, performs an unjournaled business upsert, manufactures a ToolResult,
or maintains competing committed state. Read-only repository ports, where needed,
remain behind application query/projection composition; source-conflict facts
cannot be bypassed for a cleaner list.

## 4. Canonical design-system ownership and module review

**Approved canonical design-system target: thin `:shared:ui`**. Creation,
dependency and target details remain D10-01 implementation work. No module,
Gradle or dependency change in D10-00.

Inspection justifies one location: Android/Desktop duplicate feature presentation,
both use Compose Material3, all three platforms need a single semantic token
vocabulary, and the current settings/module graph has no canonical design-system
owner. Existing Domain/Application/Database/Agent modules should not acquire
Compose rendering merely to avoid a thin module.

| Owner | May own | Must not own |
| --- | --- | --- |
| Future `shared:ui` common presentation sources | Canonical tokens, theme primitives, semantic palettes, typography/spacing/shape/elevation/motion roles, immutable display values, formatting with explicit locale/timezone, accessibility helpers, Android/Desktop Compose primitives | Repositories/DAO/Room, network/crypto, Provider/AgentRunService, Planner execution, business mutations, secret storage, lifecycle, app navigation/back-stack or global state |
| Platform app | Shell, typed destinations/back-stack, screen coordinators, component adaptations, keyboard/touch/rotary integration, system accessibility/theme/motion inputs and lifecycle | Redefining shared business/permission/causal semantics |
| Existing Application/Planner/Agent/Sync | Current semantic queries, commands, truth and typed results | Depending on `shared:ui` or a platform screen |

Dependency direction is `apps -> shared:ui -> Compose/presentation primitives`,
with apps separately consuming existing services. Presentation-only models carry
stable source references and formatted display values, not repository handles.
Avoid a dependency on Database/Agent/network; map their typed results at the app
coordinator. Common formatting preserves Zoned/AllDay/Floating/DateOnly distinctions.
Wear maps the same semantic tokens onto Wear Material3 components; it is not forced
to render Android/Desktop primitives.

Duplicating token registries or replacing the approved ownership target requires
a separately reviewed amendment. Dependency additions, targets and exact pinned
versions remain subject to the existing policy in D10-01. No new library is
selected in this packet.

## 5. Presentation state and lifetime

| State | Authority / lifetime | Presentation responsibility |
| --- | --- | --- |
| Current Event/Task/academic facts, mutation history and causal/conflict state | Existing Application/persistence services | Subscribe/query, show typed facts and refresh after results; no UI-owned replicas |
| PlanBranch | Existing session-local Planner/application contract | Retain the exact preview within its session; Apply delegates revalidation. After process loss obtain a fresh preview; do not persist it as active Calendar |
| Agent thread, pending Tool/preview/result/action | Existing Agent state/runtime | Render persisted truth and confirm the exact pending identity/preview; no inferred success or repeated write on navigation/restoration |
| Local Provider/credential/permission/consent/preference | Existing independent local/security services | Explicit user controls call the owning service; credentials never enter navigation, UI snapshots, screenshots or logs |
| Selected destination/entity, Calendar viewport, filter, scroll, open panel and form/command draft | Platform coordinator/session | Ephemeral presentation state. A dirty draft survives a local layout change, but is not autosaved as a business entity or synchronized. Process loss may clear a draft with an honest notice; no new retention promise |
| ContextAnchor | Device/session-local selection | Pass a stable typed reference to existing context assembly. Never synchronize it or treat another device's selection as local intent |
| In-flight jobs / generations | Lifecycle-owned coordinator | Single-flight where required; discard stale screen/input generations. Loss of a UI listener is not a business rollback or permission to replay |

Navigation/resizing/theme changes never submit a form, Send a command, Confirm a
Tool or Apply a PlanBranch. On returning to a feature, refresh authoritative facts.
Do not keep a stale before-image as truth. Viewport timezone/locale/clock inputs
are explicit; a platform setting may supply them, not a hidden pure-logic read.

## 6. Final platform information architecture

Typed destination concepts below are presentation vocabulary, not new persisted
wire IDs or an implementation API. Each platform owns its graph and back-stack.
Do not use raw screen-name strings as navigation authority. Display labels may be
localized; routes carry typed IDs, viewport/mode and source references as needed.

### Desktop — Windows / Linux

Persistent sidebar order: **Today, Calendar, Tasks, Courses, Exams, Planner,
Agent, History, Insights, Settings**. No low-frequency primary item is hidden in
an unspecified grouping. Settings subdestinations: General/Appearance,
PlanningProfile, Sync/Security/Devices, Provider, local Agent permissions and
conversation sync/export. Conflict notices open the corresponding typed detail.

Standard/wide windows show main content plus optional selected-item detail/context
panel. Selection does not edit a fact. Narrow windows use the same destination
graph with a drawer/compact navigation control and detail as a separate route.
There is one selected primary destination, including when a detail panel is open.
The global Agent/Universal Command entry opens/focuses the existing Agent surface
with local ContextAnchor; it never implicitly Sends. Keyboard navigation reaches
sidebar, main content, details and explicit actions with visible focus; Escape
closes the top transient surface, not a committed operation or pending truth.

### Android

Primary order: **Today, Calendar, Tasks, Agent, More**. More gives explicit access
to **Courses, Exams, Planner, History, Insights, Sync/Security, Provider, Settings**.
Settings contains appearance, PlanningProfile, local Agent policy and distinct
V2 business/V3 conversation controls. No separate competing Provider/consent store.

Compact and wider-phone windows use bottom navigation and a single content pane;
forms/details push a route or an appropriate sheet with clear Back/Cancel. Medium
windows use a compact rail plus list/detail when height/space permits. Expanded
windows use a rail and bounded two-pane content. This is the Android graph with
the same five primary destinations, not Desktop's ten-item sidebar transplanted
to a phone. System Back unwinds the top dialog/detail and then the platform stack;
explicit discard protects dirty input. Resize retains route, selection and draft.

### Wear

Small graph: **Today/Upcoming, Agent, required status/setup, compact confirmation**.
Confirmation is tied to the actual pending Tool, not an arbitrary launcher action.
No full Courses/Exams/Planner/History/settings mirror. Show concise next-item and
material sync/readiness status, with details/confirmation reachable on-device.
Back/swipe closes the current presentation and cancels foreground continuation
as the existing lifecycle contract requires; it does not re-execute a command.

Text Agent entry remains independent of STT. With supported text input,
`aiEntrySupported` / `effectiveAiEntryEnabled` govern entry; ordinary C6
`providerReady` / `requestReady` still govern an actual request. Offline preserves
input capability and preference. Setup may remain accessible when entry is OFF.
Optional on-device STT produces only a draft candidate. `und` means no selected
language; unsupported/unverified/permission facts remain separate. Only voice/mic
availability changes when accepted speech conditions are unmet. No cloud/Phone
speech fallback, automatic mic permission, model download or speech-to-Send.

## 7. Adaptive/responsive freeze and acceptance sizes

Classify **available app-window dp**, not device names or physical pixels. Recompute
on resize, multi-window, orientation, insets and text scaling. Preserve typed
selection/draft state across layout changes. Pane count is presentation only.

| Platform class | Proposed frozen width | Layout / acceptance fixture sizes (dp) |
| --- | --- | --- |
| Desktop narrow | `< 900` | Single pane; drawer/compact nav; detail route. Test 640×720 and 800×600 |
| Desktop standard | `900 <= width < 1440` | Persistent sidebar, main content, optional detail if minimum readable widths fit. Test 1024×768 and 1280×800 |
| Desktop wide | `>= 1440` | Sidebar + main + selected detail/context; bounded content widths and no stretched paragraphs. Test 1440×900 and 1920×1080 |
| Android compact | `< 600` | Bottom nav, one pane. Test 360×800 and wider phone 480×900; landscape 800×360 forces single content pane |
| Android medium | `600 <= width < 840` | Rail; conditional list/detail, otherwise single pane. Test 600×960 and 720×960 |
| Android expanded | `>= 840` | Rail + two-pane where usable. Test 840×900, 1024×768 and 1280×800; larger windows keep readable maximums |
| Wear small round | 192×192 round fixture | Scroll/rotary-safe concise content, no clipped outer actions; actual emulator/device density recorded separately |
| Wear larger round | 227×227 round fixture | Same graph/semantics, more content without desktop density; test scaled text and round safe area |

At compact height `< 480dp`, suppress optional simultaneous panes and use scrollable
detail/confirmation routes. Test both sides of every width boundary, including
599/600, 839/840, 899/900 and 1439/1440, plus live resize with a dirty draft or
pending confirmation. Narrower-than-fixture windows still expose actions by scroll,
not hidden feature removal. Exact pane padding/max widths remain token tuning in
D10-01; they cannot weaken readable controls or the graph.

Android thresholds follow [Android window-size guidance](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes).
Desktop thresholds and Wear fixture dimensions are this packet's presentation
proposal, not platform capability assertions or new library requirements.

## 8. Core screen inventory and interaction contracts

The [current capability matrix](../D10_CAPABILITY_INVENTORY.md) is the baseline.
The following is the target screen matrix; it does not claim those screens exist.

| Screen | Desktop / Android target | Existing authority / acceptance boundary |
| --- | --- | --- |
| Today | Schedule/upcoming, open or deadline-relevant Tasks, next FocusBlock, Planner/Agent entry and material conflict/sync notice | Compose existing read projections with explicit date/zone/status. No invented urgency score, background suggestions or duplicate Today database |
| Calendar Day/Week/Month | Mode/viewport selection, distinct source kinds, selected-item detail/context | Calendar application projection; no UI recurrence expansion, timezone coercion or new overlap policy |
| Tasks / Task detail-edit / Event detail-edit | Clear semantic fields, validation, explicit Save/Cancel and stale/conflict results | Existing Event/Task editing commands. No newly invented priority/effort/deadline/reminder defaults or delete support |
| Courses / Course detail / Exams / Exam detail | Final-quality list/detail; authoring controls are REQUIRES_FOUNDATION until separately reviewed D10-00A is accepted, per resolved OD-062 Option B | Academic source facts, read projection, structured issues; all Exam schedule states visible in detail/list |
| Planner / PlanBranch preview | Full Replan and Local Reflow distinct; changes, reasons/issues, Apply/Cancel, Stale/Infeasible and fresh preview | D6 session-local branch, atomic Apply, exact base-state revalidation; no UI-generated schedule |
| History / Mutation detail | Typed origin, before/after changes, linked AgentAction where available, supported/unsupported/conflicting Undo | HistoryQueryService/UndoService. Undo availability derives from existing semantics; compensation keeps original evidence |
| Sync / Devices / recovery/setup | Actual offline/active/enrollment/epoch/catch-up/stopped reason and explicit retry/setup | Existing D8 lifecycle/security services; server reachability, upload acknowledgement or a held queue alone is not “synced” |
| Conflict resolution | Full component/candidates/provisional display/status; explicit valid user resolution | Existing D8 typed scope/DVV and D9-02 D2 boundary; no LWW/dismiss-as-resolved. Agent title resolution is NOT_IMPLEMENTED; do not invent DTO/clear path |
| Agent / Agent confirmation | Current thread, command draft, real ToolCall/ToolResult, permission, preview/confirm, stale/conflict/failed/success | Existing D9 runtime; unsupported structured Tools is chat-only with zero schemas; prose never commits |
| Provider | Explicit local config/profile/credential status and approved provisioning selection/comparison/setup | D9 Provider/provisioning/secure-store authority; no raw secret in saved presentation/navigation state; credentialed HTTP rejects, explicit non-loopback HTTP risk remains visible |
| Settings | Appearance, existing profiles/local policy/AI preference and independent Sync/consent controls | Existing owning services; UI state cannot silently enable consent, permission, provisioning or background execution |
| Insights | READ_ONLY explanation/history-derived views when authoritative input exists; otherwise honest NOT_IMPLEMENTED state | A new proactive/analytics service is REQUIRES_FOUNDATION, not implied by an empty card or mock board |

Wear final surfaces use the smaller graph in section 6, preserving existing local
confirmation, Tool truth, chat-only degradation, setup/readiness and no replay.

Calendar contextual actions may offer Edit, Discuss with Agent, Find a better
time, Reschedule, Create related task and Explain conflict only when the existing
typed application/Tool/Planner path supports them. Selection can prepare a draft
or ContextAnchor; it does not automatically run a Tool, move an item or claim a
Domain relationship that does not exist. Proposals remain visibly uncommitted.

### Accepted D10 Calendar rendering extension (OD-061)

D5 Agenda/Day remains authoritative and implemented; Week/Month are absent.
For D10 use explicit finite `CalendarViewport`s with date/zone; Week at most seven visible
dates with lazy visible time rows; a separate AllDay/DateOnly band and explicitly
Floating labels; Month at most 42 visible date cells, bounded per-cell summaries
with an overflow detail route. Range intersections and source ordering consume
the application projection, never UI-recomputed recurrence/conflict truth.
Only visible rows/cells/details compose; no infinite calendar canvas or lifetime
history query. Each mode has a list/semantic accessible alternative and tests for
large fixture counts, long titles, cross-midnight/DST and explicit type labels.

Maintainer review accepted this presentation-only extension. OD-061 is
**RESOLVED FOR D5 AGENDA/DAY + D10 WEEK/MONTH RENDERING** in the register.
The D5 decision remains intact; no Calendar Domain/Application semantic change
is authorized. Production rendering remains D10-02 work, not this docs-only slice.

## 9. Design system and entity visual language

Direction: clean, modern, calm, crisp hierarchy, restrained elevation, rounded
without playful decoration. Agent is reachable globally but does not dominate
every schedule/entity screen. Existing [promo](../../promo/README.md) and approved
stills are a north star, not a pixel copy or proof of current UI. Keep brand assets
as reference; do not freeze many new hex/pixel values before theme review.

| Token family | Canonical semantic roles / constraint |
| --- | --- |
| Color | `surface`, `surfaceContainer`, `surfaceElevated`, `textPrimary`, `textSecondary`, `accent`, `success`, `warning`, `danger`, `conflict`, `agent`, `calendarEvent`, `task`, `course`, `exam`, `focusBlock`; corresponding on-color/border/focus/disabled roles must meet contrast in Light and Dark |
| Typography | Display, screenTitle, sectionTitle, body, secondary, label, numeric/time; support scaling/localization, no truncated critical action or result |
| Spacing | Ordered compact/regular/comfortable layout gaps and content insets; density may adapt without shrinking interaction targets |
| Shape / elevation | Control/card/panel/dialog roles; elevation represents layering, never factual authority or implicit success |
| Motion | Enter/exit, focus/selection and state-change roles; reduced motion, deterministic screenshot mode, no motion-only success/conflict signal |
| Icon sizing / target | Small/standard/emphasis visual icons separated from hit target; labels/semantics for unlabeled controls |
| Content width | Form, reading, list and inspector readable limits; adaptive panes honor them rather than stretching text |
| Focus | Visible outline/highlight in both themes; selected versus keyboard-focused versus disabled are distinct |

Light **and** Dark are formal acceptance. Platform-local OS theme preference feeds
one canonical theme mapping; no semantic differences across color modes.

| Entity | Primary label / type affordance | Required secondary and state distinction |
| --- | --- | --- |
| Event | Title + Event label/icon | Zoned zone/time or AllDay dates or Floating wall-time; flexibility/pin and overlap indication where applicable |
| Task | Title + Task label/icon | Explicit status, remaining/unknown effort, priority and exact/date-only deadline; unscheduled is not a missing Event |
| Course / CourseSession | Course title + Course label/icon | Semester/rule/week/occurrence identity in detail; base versus effective schedule, room and cancellation/exception/issue facts |
| Exam | Title + Exam label/icon | Unscheduled / DateOnly / Exact, optional Course association; never a generic Event time substitution |
| FocusBlock | Task-related label + FocusBlock type marker | Planned duration/time, Task reference, flexibility/pin; does not claim actual WorkLog or Task completion |

Each also has explicit selected/focused state and textual/icon conflict indication.
Color is supplementary. Danger/destruction, conflict and uncommitted Agent/Planner
proposal all require labels/icons/state text; an Assistant narrative cannot restyle
a failed Tool as a successful committed entity.

### External frontend design and implementation handoff

D10 frontend visual design **and frontend implementation** are delegated to an
external frontend implementation agent. Agent/vendor selection is a delivery
choice, not part of product architecture; this contract is implementation-agent
and vendor neutral. Delegation follows the accepted slice order and does not
start D10-00A or D10-01 in this docs-only PR.

Within the separately reviewed D10 implementation slices, the frontend agent may
implement:

- `:shared:ui`, the design system/Light-Dark themes, Compose presentation
  components and typed presentation models;
- Desktop/Android/Wear app shells, explicit screen coordinators, platform-owned
  typed navigation/back-stacks, adaptive/responsive layouts and all D10 product
  screens within their frozen capability and foundation boundaries;
- accessibility presentation work, screenshot/visual regression fixtures and
  tests, and decomposition of oversized Android/Desktop UI entry files.

It may consume existing Application/Planner/D7/D8/D9 services and invoke them
through reviewed typed interfaces. It must consume the frozen platform IA,
screen/capability matrix, semantic entity vocabulary, Light/Dark requirement,
responsive classes, accessibility requirements and Agent/Planner/Sync state
vocabulary. Existing composition-root infrastructure wiring does not authorize
frontend DAO/Room writes.

The frontend agent must not write DAO/Room directly, invent missing application
commands, alter Domain semantics or Planner legality/scoring, alter D7/D8/D9
semantics, add/change Agent Tool schemas, bypass permission/confirmation, invent
Sync/conflict truth, change Provider/security/crypto behavior, or silently add
Course/Exam authoring before accepted D10-00A. If implementation discovers a
missing semantic/application capability, **stop that path and report the missing
foundation**; do not implement around it. Independent approved presentation work
may continue within its slice.

Visual-design artifacts remain **non-authoritative presentation proposals**.
Use synthetic fixtures in design prompts; no real user data, credentials,
secrets, SAS/envelopes or private transcripts are required. Accepted visual
boards become D10-01+ implementation input, never a replacement for these
contracts. External frontend implementation PRs are subject to normal repository
CI and maintainer review; delegation does not bypass either acceptance gate.

## 10. Typed display/error vocabulary

Applicable feature states: **loading, empty, ready, offline, error, conflict,
stale, permission denied, unavailable**. Retain typed payload/reason/action and
last-known-data provenance where applicable. Do not force every screen into every
state or map all failures to one generic snackbar.

Distinguish calendar overlap from Sync conflict, chat-only from unavailable,
capability from user enablement, network route from Provider health, held outbound
from failed outbound, and pending Agent confirmation from committed success.
Only the owning service's result can claim success/undo/resolution/synced.
Show actionable redacted retry/setup reasons; never raw credential-bearing errors.
Network/provider/readiness restoration refreshes display only, never Agent replay.

## 11. Accessibility acceptance

Formal product gates for Light/Dark and all representative widths:

- Interactive elements have semantic names, role/state and logical traversal;
  icon-only actions are named, entity type/conflict/proposal is readable without color.
- Keyboard focus remains visible/unobscured. Sidebar/content/detail/modal order
  is predictable, modal focus returns to its trigger, and confirmations are reachable
  without a mouse. Inspect target-platform accessibility exposure, not screenshots alone.
- Android/Wear touch targets at least **48×48dp**, with non-overlapping hit areas.
  Desktop click targets also use at least 48×48 logical dp as this project's initial
  accessible control baseline; visual icons may be smaller. Dense read rows are not
  themselves targets unless actionable. [Android/Wear guidance](https://developer.android.com/training/wearables/accessibility).
- Project contrast targets: normal text at least **4.5:1**, essential component/focus
  cues **3:1** against adjacent surfaces. Focus/selection cannot rely on color alone.
  These adopt applicable [WCAG 2.2](https://www.w3.org/TR/WCAG22/) criteria as review
  targets, not an automatic claim of whole native-app WCAG conformance.
- Test 100% and 200% font scaling / equivalent platform settings, long localized
  labels and display scaling. Content/confirmation text remains scrollable; controls
  and status do not clip or overlap. Respect OS reduced-motion/animation settings.

| Platform | Required eventual evidence |
| --- | --- |
| Android | Compose semantics assertions plus TalkBack traversal/read/write-preview/Back, compact/medium/expanded, large font and target/contrast checks |
| Desktop Windows/Linux | Keyboard-only shell/form/preview/confirmation workflows; focus/resize tests; platform screen-reader names and reading order on each supported OS. Unsupported native accessibility exposure must be reported, not inferred from Compose semantics |
| Wear | Actual round Watch/emulator safe-area/scroll/rotary traversal, TalkBack/semantic names, large font, compact confirmation and optional speech-denied/unsupported paths. Absent recognizer is reported as unavailable, never physical STT success |

D10-00 runs none of this new platform acceptance; D10-06 owns final evidence.
If a platform cannot meet a required path, surface a release/acceptance limitation
for review rather than declaring the gate green from a bitmap.

## 12. Screenshot / visual regression strategy

Use deterministic **screen/component presentation fixtures**, not flaky whole-app
screenshots as the only gate. Existing Compose UI-test infrastructure is the first
candidate; D10-01 chooses any exact pinned capture/diff dependency under the policy
before adding it. This packet adds no screenshot tool or dependency.

Fixture inputs: fixed typed IDs, clock, date/timezone, locale, fonts, width/density,
theme, animation frame, state/proposal/Tool result and navigation/selection. No live
network/Provider, secure-store credential, private user DB or random ordering.
Fakes stop at presentation inputs; they never count as business execution evidence.

Minimum owned baseline set:

- Today populated/empty/offline; Calendar Day/Week/Month with every supported time
  kind, overlap/conflict/issue and selected detail; Task list/detail/edit validation.
- Courses/Exams read-only + unavailable authoring before D10-00A, then validated
  authoring presentation under resolved OD-062 Option B;
  Planner feasible preview/Stale/Infeasible; History diff/unsupported Undo/conflict;
  Sync stopped/held/offline and security setup; Provider configured/unavailable.
- Agent transcript with true ToolResult, exact confirmation, denied/conflict/stale,
  and chat-only; Wear Today/Upcoming, text entry with `und`/unsupported STT,
  offline/readiness/setup and confirmation on both round sizes.

Light/Dark × representative width classes are required for each applicable key
screen; boundary sizes/font scaling/keyboard focus add targeted fixtures rather
than an unbounded Cartesian whole-app suite. Golden files are platform-specific
with recorded renderer/OS/font/density/tool versions. Do not demand identical
Android/Desktop/Wear raster bytes from different native renderers.

CI stores expected/actual/diff and metadata. Changes require reviewed baseline
updates; never auto-accept snapshots. Text/geometry/semantic changes fail review;
only documented small rasterization tolerance/masks may be calibrated in D10-01,
with no masking of warnings, confirmations or failed results. Keep semantic-tree,
interaction and accessibility assertions alongside images. Business validity stays
in existing unit/integration/E2E suites; visual diffs cannot approve D2–D9 changes.

## 13. OD-062 — accepted academic authoring foundation

Status: **RESOLVED FOR D10 / OPTION B** by maintainer review.
Audit evidence is in [inventory section 3](../D10_CAPABILITY_INVENTORY.md).

Option B selects a separately reviewed **D10-00A Academic Authoring Foundation**
before D10-01 and before product Academic UI authoring. Option A was the
read-only alternative and was not selected. UI may only call legitimate
application authoring services; no AcademicRepository upsert shortcut.

D10-00A must first audit the minimum prerequisite academic graph required for
usable Course/Exam authoring, including as applicable AcademicYear/Semester,
Course, CourseScheduleRule/schedule authoring, Exam and required PeriodTemplate
relationships, with explicit validation and cross-entity constraints. Never
silently synthesize Semester, AcademicYear or default timetable facts to make
a form work; require legitimate explicit authoring/input where prerequisites
are absent.

Its commands/services must use production ID generation, deterministic
validation, atomic MutationCoordinator, typed D7 mutations/ChangeLog and the
existing D8 write/conflict policy, verified by real persistence/integration
tests. This establishes an application boundary before UI consumes it.

The decision does not automatically authorize academic deletion, new Undo
support, new Agent Tools, wire DTOs, merge semantics or Domain semantic changes.
Escalate those separately if implementation proves they are required.
**D10-00A remains unstarted in this PR**; its task and implementation receive
separate review.

## 14. Planned decomposition and execution dependencies

Entry roots stop growing as all-feature screens. Later decomposition:

```text
platform composition root
    -> app shell + typed destination/back-stack
    -> feature screen coordinator
    -> immutable presentation state + Compose screen
    -> feature dialogs/sheets
```

Extract by feature while retaining existing semantic service composition and
lifecycle ownership. No generic global store or DAO-writing coordinator. This
packet performs no extraction.

| Slice | Scope / dependency gate |
| --- | --- |
| D10-00 | This docs-only freeze; FROZEN / MAINTAINER APPROVED / MERGED PR #30 `f8efbde` |
| D10-00A | Separately reviewed Academic Authoring Foundation selected by OD-062 Option B; unstarted at freeze, current status in its task, precedes D10-01 |
| D10-01 | Design System + App Shell; after D10-00 and accepted D10-00A; implement approved thin shared UI target, dependency/target/capture details |
| D10-02 | Today / Calendar / Tasks / Academic; after shell and academic foundation; follow accepted OD-061 Week/Month rendering extension |
| D10-03 | Planner / History / Sync / Settings; after shell and relevant projections/navigation, preserving earlier semantic services |
| D10-04 | Agent Product Surface; after shell and required detail/preview/context surfaces; shared D9 execution unchanged |
| D10-05 | Wear Final UX; after canonical tokens and reviewed Wear graph; reuse accepted readiness/runtime/confirmation |
| D10-06 | Accessibility / Visual Regression / Final Acceptance; after all accepted product slices, with fixture/accessibility checks developed during each slice |

```mermaid
flowchart LR
    FREEZE[D10-00 approved freeze]
    FOUNDATION[D10-00A separately reviewed]
    SHELL[D10-01]
    CORE[D10-02]
    SERVICES[D10-03]
    AGENT[D10-04]
    WEAR[D10-05]
    ACCEPT[D10-06]
    FREEZE --> FOUNDATION
    FOUNDATION --> SHELL
    SHELL --> CORE
    SHELL --> SERVICES
    CORE --> AGENT
    SERVICES --> AGENT
    SHELL --> WEAR
    CORE --> ACCEPT
    SERVICES --> ACCEPT
    AGENT --> ACCEPT
    WEAR --> ACCEPT
```

The graph expresses review dependencies, not authorization to start another slice.
No D10-01, D10-00A, D10 production UI or post-project fork starts in D10-00.

## 15. Validation and review acceptance

Required docs-only checks: `git diff --check`, local Markdown links/path existence,
current status/decision/scope consistency, reviewed current UI inventory and
source references. Confirm the PR contains Markdown only; no Kotlin/Gradle,
module/dependency, test, schema/migration, server, crypto or production change.
If repository CI triggers, wait and report its exact head/run. No additional
Android/Wear emulator acceptance is required for this packet.

Maintainer architecture review passed: OD-060 and the thin shared-UI target
accepted, OD-061 D10 rendering extension accepted, OD-062 resolved Option B.
D10-00 is **FROZEN / MAINTAINER APPROVED / MERGED**. No remaining
decision blocker was identified for D10-00. D10-00A and D10-01 were unstarted at
freeze; their separately reviewed tasks own implementation details and acceptance.

Scope fences: no Domain/Planner/D7/D8/D9/provisioning/Tool/policy changes, no
V3/Envelope/AAD/server/crypto change, no Room migration, no automatic Agent replay,
no academic upserts from UI, no cloud/Phone speech or new capability promised.
**OD-012 remains OPEN**, independent of D10 UI development/acceptance. This packet
does not claim production-sensitive-data readiness, all security gates closed,
or production release approval. Keep the delivery PR Draft; do not merge;
await final review of this docs-only follow-up.
