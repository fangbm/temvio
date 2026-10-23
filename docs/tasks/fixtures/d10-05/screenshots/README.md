# D10-05 — Wear emulator presentation candidates

Status: **CANDIDATES / AWAITING MAINTAINER VISUAL REVIEW**.
Current slice base: `6f7071239df3addf6fbc2031aa8baf556d3c4f13` (merged D10-05F PR #40).
See [task, scope and acceptance evidence](../../../D10_05_WEAR_FINAL_UX.md).

Actual Compose/Wear rendering on an API35/ext15 x86_64 Wear OS Small Round / Large Round AVDs;
not a manually drawn Watch image or physical Watch claim. Synthetic typed display
inputs exercise final product components. Existing real AgentRunService/Tools/Room/
Android HTTP runtime E2E remains separate execution evidence. No live external
Provider, real account/schedule, credential, SecretRef or envelope in fixtures. Provisioning scenes use public synthetic comparison digits, never a real transfer code.

Logical 192×192 and 227×227 dp use density 320 dpi (384×384 and 454×454 bitmap
pixels), on their actual physical profile sizes; no `wm size` simulation. Normal and 200% font scale, explicit Light/Dark theme, actual round
hardware configuration and capture logs are recorded. Native system rendering
may have small platform raster/clock differences. These are review candidates;
PNG/source hashes and semantic/bounds tests do not grant visual approval.

| Representative state | View |
|---|---|
| 192 Today, Light | [Today](wear-192x192-font100-light-today.png) |
| 192 Agent ready, Dark | [Ready / Send](wear-192x192-font100-dark-ready.png) |
| 192 offline, Light | [Offline](wear-192x192-font100-light-offline.png) |
| 192 confirmation facts, Light | [Proposal](wear-192x192-font100-light-confirmation.png) |
| 192 confirmation actions, Dark | [Confirm](wear-192x192-font100-dark-confirmation-actions.png) |
| 192 Status, Light | [Setup](wear-192x192-font100-light-status.png) |
| 192 chat-only, Dark | [Chat only](wear-192x192-font100-dark-chat-only.png) |
| 192 Sync stopped, Dark | [Explicit Retry](wear-192x192-font100-dark-sync-stopped.png) |
| 227 Today, Dark | [Today](wear-227x227-font100-dark-today.png) |
| 227 conversation, Light | [Conversation](wear-227x227-font100-light-conversation.png) |
| 227 structured Tool result, Dark | [Stale result](wear-227x227-font100-dark-tool-result.png) |
| 227 confirmation, Light | [Proposal](wear-227x227-font100-light-confirmation.png) |
| 227 Status, Dark | [Setup](wear-227x227-font100-dark-status.png) |
| 227 200% confirmation, Dark | [Reachable Confirm](wear-227x227-font200-dark-confirmation-actions.png) |
| 227 200% Status, Dark | [Reachable Enable](wear-227x227-font200-dark-status.png) |

Confirmation facts and actions have separate views because scrolling retains
readability on a round face. All important facts remain reachable; actions retain
>=48dp targets. The automated fixture checks actual unclipped action bounds
against the circular face for Agent, Send, Confirm/Deny, Retry and Enable paths.
Native Back and real pending-state persistence are covered in runtime tests.

Regenerate using `test-support/d10-05/capture-wear.ps1` on both physical round profiles, or the CI shell equivalent,
then explicitly `record-candidates.py`. Review the resulting images before commit.
`capture-manifest.json` records source/PNG hashes, dimensions, density, theme/font
and AVD. Historical D10-01/02/03/04 screenshots/manifests are unchanged.
Speech service absence, if observed, is unavailable-path evidence only; no physical
recognition success is inferred from the deterministic speech mapping tests.

Small-round 200% controls: [Confirm](wear-192x192-font200-dark-confirmation-actions.png), [Enable](wear-192x192-font200-dark-status.png).

## Integrated credential setup candidates

The original 30-scene matrix is retained and regenerated; eight bounded credential
views bring the set to 38. The exact received comparison is displayed separately
from the sender-code input. Typed screenshot drafts are synthetic presentation
fixtures, not an automatic input path. Native UI tests exercise actual user text
entry, wrong-code rejection, separate Install and ACK-only retry.

- [192 choose sender](wear-192x192-font100-light-credential-ready.png)
- [192 Watch comparison](wear-192x192-font100-dark-credential-comparison.png)
- [192 explicit Install](wear-192x192-font100-dark-credential-approved.png)
- [192 installed / ACK pending](wear-192x192-font100-light-credential-installed.png)
- [227 separate sender input](wear-227x227-font100-dark-credential-comparison.png)
- [227 installed / ACK pending](wear-227x227-font100-light-credential-installed.png)
- [192 200% explicit Install](wear-192x192-font200-dark-credential-approved.png)
- [227 200% explicit Install](wear-227x227-font200-dark-credential-approved.png)

At 200% both profiles also execute the existing Today/confirmation controls and
new Request/retry/fetch, separately entered approval, Install/ACK retry, recovery
and Back interactions. All action/input bounds are checked against the actual
round face and >=48dp. No physical Watch acceptance is claimed.
