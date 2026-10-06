# D10-01 — Design System + App Shell / First UI Pass

Status: IMPLEMENTED / AWAITING REVIEW. Baseline: `dfb9652ad90f89182c08b799e38c6d5a8579e10f`.

## Authority and scope

The maintainer's D10-01 task, [D10-00 freeze](D10_00_PRODUCT_UI_ARCHITECTURE_FREEZE.md),
[D10-00A foundation](D10_00A_ACADEMIC_AUTHORING_FOUNDATION.md), OD-060/061/062,
[ownership](../MODULE_OWNERSHIP.md) and existing D5–D9 services are authoritative.
Implement a thin `:shared:ui`, typed platform shells, entry-file decomposition,
Today/Agenda/Agent first pass and deterministic actual Compose fixtures.

No Domain/Planner/D7/D8/D9 semantics, database migration, wire, crypto, Tool or
permission change. No D10-02 forms/Week/Month, D10-05 Wear redesign, D10-06 final
accessibility acceptance or production-sensitive V3 enablement. OD-012 stays OPEN.

## Module and composition

`dev.agenticscheduler.ui`: Android KMP + Desktop JVM, existing Kotlin/Compose/AGP
pins. Depends only on Compose presentation libraries. Apps consume it; semantic
modules do not. No repository, Room, network, runtime, secret, lifecycle or
navigation authority. Platform composition roots retain existing service wiring.
Feature coordinators own ephemeral selection/drafts and adapt typed service facts;
explicit user intents remain the only command/confirm/apply entry points.

## Presentation contract

Neutral Light/deep neutral Dark, restrained teal accent, crisp hierarchy. Semantic
surface/container/elevated/selected, text/secondary/muted, border/strong/focus,
accent/success/warning/danger/conflict and six entity roles. Labels accompany color.
Ordered spacing, restrained radii/elevation, readable widths, no decorative motion.
Explicit `ActionRole` hierarchy: PRIMARY filled, SECONDARY outlined, TERTIARY
text. Today/Calendar day navigation is tertiary, New Event primary, New Task
secondary. Roles retain shared theme colors, focus and >=48dp targets.
`ElevationRole`: FLAT 0dp, RAISED 2dp, OVERLAY 6dp; layering only.
`MotionRole`: IMMEDIATE 0ms, SHORT 120ms, STANDARD 200ms; explicit no-motion
override returns 0ms. These are presentation tokens, not newly enabled animation.

Desktop destinations: Today, Calendar, Tasks, Courses, Exams, Planner, Agent,
History, Insights, Settings. Android primary: Today, Calendar, Tasks, Agent, More;
More exposes Courses, Exams, Planner, History, Insights, Sync/Security, Provider,
Settings. Desktop destinations and Android's five primary destinations select a
primary position, never accumulated history. Android secondary destinations
have the typed path More -> secondary; Back returns to More. A primary selection
leaves any secondary path. No fake detail route is introduced. Platform enums
own navigation and the secondary back-stack. Global Agent only opens it.

Desktop width classes: <900, 900–1439, >=1440. Android: <600, 600–839, >=840;
height <480 uses one pane. Resize/theme/navigation must retain session drafts,
selection and pending previews and never Send/Confirm/Apply. Calendar keeps the
finite authoritative Agenda/Day projection; no UI recurrence/conflict calculation.
Unavailable later screens say so rather than fabricate data or capability.

## Verification and evidence

Actual Compose synthetic fixtures freeze date `2026-10-06`, UTC, English,
deterministic IDs/text, density, font scale, viewport, theme and no animation.
Capture Desktop and Android-shell Today/Calendar/Agent in Light/Dark; report the
renderer/platform for each capture. Representative narrow/laptop/wide/tablet,
compact-height and 100%/200% font fixtures; boundary and typed-navigation tests.
No production data, network or credentials. Golden acceptance is explicit.

Semantic action names, selected state, keyboard focus and >=48dp targets are
foundations. Test token contrast >=4.5 normal text and >=3 essential controls.
Native screen-reader/physical-device acceptance remains D10-06, not proven by PNGs.
Wear retains its existing platform-local components and D9 behavior.

Missing semantic paths are documented as FOUNDATION_GAP with interaction,
existing API, insufficiency and minimal typed capability; no DAO fallback.

## Implemented structure and behavior

`shared/ui` contains `TemvioTheme`, pure responsive classifiers/presentation DTOs,
and stateless cards, typed entity labels, navigation controls, status and discard
prompts. It uses existing toolchain pins; no framework, service locator or new
semantic dependency. Android and Desktop keep separate typed back-stacks.

The entry files delegate to platform composition roots, app shells, Schedule,
Planner and Agent screen coordinators, and feature components/editors. Existing
Application editing/read/Planner/Agent/Provider/Sync paths are retained. No direct
DAO business write is introduced. Explicit Send/Confirm/Apply remain necessary.
Drafts and pending previews live above destination rendering. Android handles
window/theme/font configuration changes in the existing Activity so session
state survives them; process-death draft restoration is not claimed.

The optional wide-pane shell slot accepts an actual platform detail component;
the first pass does not fabricate an inspector. Desktop uses a sidebar at >=900dp
and a destination menu below it. Android uses a rail at >=600dp, primary bottom
navigation in compact/short-height windows, and horizontally scrollable named
navigation at large font scale. No controls become semantic command triggers on
navigation, theme or layout changes.

Calendar labels explicitly preserve AllDay, DateOnly, Floating and Zoned/display
zone semantics, derived CourseSession and FocusBlock distinctions. Agent roles,
typed calls/results, confirmation and committed mutation counts remain distinct.
The platforms observe the existing `AgentRunService.capabilityProbe` seam to
display the exact selected configuration's last explicit probe result. The same
adapter, request and result are used; no additional probe or retry is scheduled.
Unsupported structured Tools remain chat-only; prose is never a mutation.

## Verification actually executed

Windows host, Microsoft JDK 17.0.5, existing SDK/toolchain. A local ASCII Gradle
user-home junction avoids this host's Unicode worker-classpath issue; it is not
a repository/runtime change. The isolated API 35 Android emulator used 160 dpi.

```powershell
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home build :apps:android:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.agenticscheduler.android.AppShellInstrumentedTest' --no-daemon --console=plain
.\gradlew.bat --gradle-user-home D:\codex\asp-d10-gradle-home :shared:ui:desktopTest :apps:desktop:test --no-daemon --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File test-support/d10-01/capture-android.ps1 -Sdk C:\ProgramData\Android
python test-support/d10-01/compare-captures.py --reference docs/tasks/fixtures/d10-01/screenshots --candidate apps/desktop/build/d10-01/screenshots --platform desktop
python test-support/d10-01/compare-captures.py --reference docs/tasks/fixtures/d10-01/screenshots --candidate build/d10-01/android-captures/d10-01-screenshots --platform android
git diff --check
```

- Initial full local build: SUCCESS on reviewed head `eecdaa6`, 464 tasks.
- Review follow-up targeted JVM: shared UI 3/3; Desktop 10/10 (7 shell/actual Compose tests,
  3 retained Planner/Agent-control tests). Zero failures/skips in these sets.
- Review follow-up Android native instrumentation: 4/4, zero failures/skips. Navigation/draft/theme,
  typed back-stack and actual Android Compose Light/Dark captures executed.
- Primary selection tests cover every current Desktop destination and all five
  Android primary destinations. Every More secondary returns to More, and every
  primary leaves a secondary route. Native KEYCODE_BACK dispatch verifies the
  actual BackHandler path. Desktop has no Back control after primary switching.
  Both themes verify shared day-action roles and >=48dp targets. Token tests
  verify restrained elevation/durations and explicit no-motion behavior.
- The first rerun at 200% font found the test clicking an offscreen navigation
  control. It now scrolls to that control before clicking; the final run passed
  at that same 200% configuration.
- Local existing reports: Domain 42, Planner 67, Sync 55, database 157, Agent 48,
  Wear JVM 60, all zero failures/skips. Application 213 includes 20 skipped
  platform/PostgreSQL-gated cases; server 44 includes 17 skipped provisioning
  PostgreSQL cases. These skips are not presented as local platform acceptance.
- Existing CI still supplies PostgreSQL/platform secure-store/Android/Wear
  regressions. The Windows job additionally runs shared UI/Desktop tests and
  uploads screenshot candidates/XML. Final exact-head results are published in
  the Draft PR's checks and delivery report, not inferred from this local build.

Navigation, theme and live Desktop width changes preserve an unsent draft with
zero Provider requests. Dirty Event cancellation requires an explicit discard
and produces no D7 write. Explicit chat persists its final assistant message;
an advertised structured-Tool configuration whose real synthetic probe returns
Unsupported still requests zero Tool schemas and creates zero ToolCalls,
AgentActions or additional D7 mutations. Existing structured runtime tests are
retained unchanged.

## Screenshot evidence and review contract

[Capture manifest](fixtures/d10-01/screenshots/capture-manifest.json) lists the
32 selected PNGs, SHA256 hashes, fixed inputs and actual platform renderers.
The full generated matrix is 36 Desktop + 36 Android images. Core evidence is
Light/Dark Today/Calendar/Agent at Desktop 1024×900 and Android 360×800.
The focused review follow-up regenerates this same matrix. Desktop and Android
Light/Dark Today and Calendar were each inspected; primary navigation no longer
creates history, and day/creation actions have explicit visual hierarchy.
Repeat rendering matches all 32 selected candidate hashes on the same platforms.
Additional selected Today/Agent images cover Desktop 640×720, 1280×900,
1440×960, 1920×1080 and 640×900 at 200%; Android 480×900, 600×960, 840×900,
800×360 and 360×800 at 200%. Boundary tests cover 599/600, 839/840, 899/900,
1439/1440 and compact height. No manual bitmap mockup is used.

Examples: [Desktop Light Today](fixtures/d10-01/screenshots/desktop-1024x900-font100-light-today.png),
[Desktop Dark Agent](fixtures/d10-01/screenshots/desktop-1024x900-font100-dark-agent.png),
[Android Light Calendar](fixtures/d10-01/screenshots/android-360x800-font100-light-calendar.png),
[Android Dark large-font Agent](fixtures/d10-01/screenshots/android-360x800-font200-dark-agent.png).

Capture tests write only `build/` candidates. The comparison command never
updates source baselines. Human visual approval is pending. Pixel hashes are
compared only on the same renderer/platform; Windows/Linux/font rasterization
are not asserted byte-identical. The Android capture script modifies only the
explicitly selected isolated emulator's size/density/font/animation settings.

Contrast tests cover normal text on four surfaces at >=4.5, focus >=3, and typed
semantic labels >=4.5. Named actions, semantic headings/selection, visible focus,
48dp targets and large-font scrolling are established. Native screen-reader,
keyboard traversal across every form and physical-device final accessibility
acceptance are deferred to D10-06; screenshots do not prove them.

## Scope audit, assumptions and deferred work

No schema/migration. No changes to Wear source or dependencies, Domain,
Application, Planner, Agent runtime, D7/D8/D9, server, wire or crypto implementation.
No Tool/permission/provisioning change. OD-012 remains OPEN. D10 is not complete.

LOCAL_REVERSIBLE: spacing/radii, neutral/teal palette, session-only theme override,
sidebar/rail widths and deterministic screenshot fixture dimensions. No new
semantic/default business choice is made. No FOUNDATION_GAP or
BLOCKED_BY_DECISION was discovered for this slice's existing real paths.

Course/Exam full authoring UI, Week/Month, History/Insights full product views and
later polished Settings are explicitly unavailable first-pass destinations, not
mock production capability. D10-00A's accepted application commands are not
bypassed. Those screens, Wear redesign and final accessibility belong to D10-02+
and are unstarted here. Keep this PR Draft, do not merge, stop for review.
