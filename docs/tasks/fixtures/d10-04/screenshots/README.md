# D10-04 native Agent presentation candidates

Status: **CANDIDATES / AWAITING MAINTAINER VISUAL REVIEW**. No visual approval is
inferred from screenshot generation or CI. See [task/acceptance evidence](../../../D10_04_AGENT_PRODUCT_SURFACE.md).

Captures use actual Compose Desktop/Skia or native Android API35 Google APIs
x86_64 instrumentation. The owning application runtime, typed Tools and Room are
real; HTTP responses and probe results are deterministic synthetic fixtures. No
real credentials, private conversation or live Provider traffic is present.
Native Android clock/chrome is not pixel deterministic. D10-06 retains final
screen-reader/motion/physical-device acceptance.

`capture-manifest.json` records immutable baseline, source hashes, original
capture paths, dimensions and PNG hashes. `test-support/d10-04/check-candidates.py`
checks all six required sizes per platform, Light/Dark, 200% and short-height,
plus Tool result/confirmation/stale/denied/chat-only/thread/permission/provider
states. It does not approve appearance. Historical D10-01/02/03 candidates and
manifests are unchanged.

Representative review views:

| State | Desktop | Android |
|---|---|---|
| Conversation, Light | [1280×800](desktop-1280x800-font100-light-conversation.png) | [360×800](android-360x800-font100-light-conversation.png) |
| Conversation, Dark | [1280×800](desktop-1280x800-font100-dark-conversation.png) | [360×800](android-360x800-font100-dark-conversation.png) |
| Pending action | [Light](desktop-1280x800-font100-light-confirmation.png) | [Light](android-360x800-font100-light-confirmation.png) |
| 200% confirmation | [Dark](desktop-640x720-font200-dark-confirmation.png) | [Dark](android-360x800-font200-dark-confirmation.png) |
| Actual committed Tool | [Light](desktop-1280x800-font100-light-tool-result.png) | [Light](android-360x800-font100-light-tool-result.png) |
| Stale proposal | [Dark](desktop-1280x800-font100-dark-stale.png) | [Dark](android-360x800-font100-dark-stale.png) |
| Chat-only Provider | [Light](desktop-1280x800-font100-light-chat-only.png) | [Light](android-360x800-font100-light-chat-only.png) |
| Short-height reachable Send | [Dark](desktop-800x360-font100-dark-conversation.png) | [Light](android-800x360-font100-light-conversation.png) |

200% detail bodies and short-height content are scrollable. Confirm/Deny and short
Send reachability are asserted in capture/functional tests; closing or scrolling
a preview never executes it. Tool states come from persisted records, not prose.

Regenerate with `AgentWorkspaceUiTest.actualDesktopD10AgentScreenshotCandidates`
and `test-support/d10-04/capture-android.ps1` (or CI shell equivalent), then run
`record-candidates.py` explicitly and inspect the new images before committing.
Do not use an image editor or rewrite a historical manifest to label old captures
as current evidence.
