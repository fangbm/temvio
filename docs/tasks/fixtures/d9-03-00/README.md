# D9-03-00 frozen canonical fixtures

Status: **FROZEN — maintainer approved C1–C8 with amendments 2026-10-04;
OD-058 RESOLVED FOR D9-03**.
These files demonstrate the approved contract in
[the frozen packet](../../D9_03_00_WEAR_AGENT_PROVIDER_FREEZE.md).
Codec/runtime implementation and acceptance belong to later slices after final
human review. These fixture checks alone do not prove those implementations.

All JSON files are UTF-8 without BOM, compact, in the packet's field order, with
no trailing newline. File bytes are canonical transcript/hash inputs. Base64url
is unpadded and canonically re-encodable. No real credential or live device key
is used: the token is `test-credential-not-a-real-secret` and the recipient
private key is deliberately public test material. Never import these keys into
an actual enrolled device or use the example endpoint/model as a default.

The target `WearTarget:opaque-A_01` and provisioner `PhoneSource:opaque-B_02` are
deliberately non-UUID, mixed-case D8 test identities. Preserve exact values/UTF-8
bytes in enrollment admission, HPKE context and SAS: no UUID restriction,
lowercasing, trimming or normalization. `providerConfigId` stays UUIDv7.

| File | Purpose |
| --- | --- |
| `credential-plaintext.v1.json` | Exact C1 inner field set and fake Bearer UTF-8 bytes. |
| `credential-envelope.v1.json` | Actual HPKE encryption of the canonical plaintext with existing Tink 1.23.0 X25519/HKDF-SHA256/AES-256-GCM base-mode NO_PREFIX. |
| `wear-provider-binding.v1.json` | C5 canonical **local** metadata/hash input, no SecretRef/credential. Never a relay/request/ACK body or ProviderConfig transport. |
| `credential-ack.v1.json` | C2 informational receipt; never target installation authority; its digest identifies only the encrypted envelope. |
| `hpke-sas-vector.v1.json` | Public raw test private/public keys, exact contextInfo, hashes, approved confirmation transcript/counter/digest/8-digit code. This is a vector catalog, not a transferred envelope. |
| `invalid-revision.v1.json` | Revision 0 rejects before import. |
| `mismatched-config-plaintext.v1.json` | Individually valid plaintext config differs from the outer/local binding; whole install rejects. It must be re-encrypted by a future test if testing post-authentication inner/outer admission. |
| `tampered-envelope.v1.json` | One decoded ciphertext byte XOR 1; real ciphertext always changes. |
| `wrong-target-envelope.v1.json` | A different opaque outer target replaces the original while ciphertext stays identical; local identity/AAD verification rejects. |
| `unknown-version-envelope.json` | Whole unknown credential version rejects independently from workspace sync. |
| `duplicate-field-envelope.v1.json` | Duplicate decoded root key rejects; intentionally outside canonical positive grammar. |

On 2026-10-04, a temporary JDK17 fixture utility outside the repository called
the existing Tink primitive profile, generated this single archived vector and
verified successful decrypt (including raw-key reconstruction), wrong-key
rejection, a decoded-byte tamper rejection and changed-context rejection
(including lowercasing the target identity).
The vector was regenerated for the approved opaque DeviceId correction.
Plaintext is 237 bytes; ciphertext is 253 bytes plus the separate 32-byte
encapsulated key. The archived SAS is `51070555`, counter 0.

This is **primitive/fixture evidence only**. HPKE ciphertext generation remains
random inside Tink; subsequent tests decrypt these fixed committed bytes rather
than regenerating encryption and expecting byte equality. The SAS/transcript is
approved by the maintainer decision, not by this check. There is no Wear/runtime,
mailbox, install/revision, DB migration or Provider E2E acceptance here.

Source and Watch independently build/canonicalize the target binding locally:
source uses the target config ID with the user's selected local config values;
Watch uses its already approved local binding. Neither canonical binding JSON
nor binding digest enters the relay/request/ACK. Different endpoint/model/budget/
capability values produce a different SAS and cannot authorize an import.

D9-03-01, after final human review, must promote fixtures into codec/crypto tests, add every C1
boundary (including max UTF-8 size, invalid UTF-8, padding/noncanonical encoding,
duplicate keys and unknown fields), and assert import/activation counts stay
zero on rejection. C2–C8 failure/restart/platform tests remain separately required.
