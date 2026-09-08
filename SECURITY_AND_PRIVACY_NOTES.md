# Security and privacy notes

## User-facing summary

Reader has no account and no Reader-operated server. Articles are encrypted on
the sending device and decrypted on Android. Public relays carry ciphertext and
can still observe network metadata such as IP address, time, recipient routing
key, and approximate message size.

The transport identity is generated for Reader. The owner's real Nostr key,
Amber identity, browser-signer identity, seed phrase, and private articles were
not used in repair testing.

## Chrome

- Device and one-time pairing keys are generated and used in the MV3 service
  worker.
- A completed channel is bound to the public key derived from the exact Chrome
  device secret that completed pairing. Missing, malformed, replaced, or
  non-curve key state cannot remain visibly paired; Reader removes only the
  unusable channel binding and retains local captures for re-pairing.
- `chrome.storage.local.setAccessLevel({accessLevel:'TRUSTED_CONTEXTS'})` is
  reasserted on every worker evaluation. In the exact current Chrome build,
  Reader's isolated content-script world could see the API namespace but its
  storage read was denied; no device secret or pairing state was visible.
- Secrets are not stored in sync storage, page localStorage, the DOM, reports,
  or logs. The one-time bootstrap secret is removed immediately after the
  Android response authenticates and before ACK publication; cancellation,
  expiry, disconnect, and supersession remove any remaining obsolete material.
- The removed page-world bridge means arbitrary page JavaScript cannot invoke a
  NIP-07 signing surface through Reader.
- Manifest permissions are limited to `activeTab`, `scripting`, `storage`,
  `alarms`, and `contextMenus`, plus the static supported-site content-script
  matches. There are no optional all-sites or remote-code permissions.
- CSP allows scripts only from the extension and forbids objects. The package
  verifier rejects module syntax/noncharacters in the standalone content
  script and checks required manifest/icon assets.
- The Settings site-access text delegates supported-site permission changes to
  Chrome's extension controls; the former inert checkboxes were removed.

Evidence: `evidence/raw/final/chrome-content-storage-isolation-2026-09-05.txt`
and `evidence/raw/final/pairing-relay-state-hardening-2026-09-05.txt`.

## Android

- Channel private keys are sealed by `AndroidKeyStore` AES-256-GCM and are not
  sent to Amber. Missing wrapped-key state revokes a false active channel.
- Room, preferences, files, and external app data are excluded from cloud
  backup and device transfer in both API-31+ and legacy backup-rule formats.
- Only `MainActivity` is intentionally exported for launcher/share/process-text
  intents. AndroidX internal components retain library-controlled export
  semantics; the application defines no exported key or database provider.
- Camera access is requested only for QR scanning. A manual paste path exists.
- Every relay hostname is resolved and checked against local/private address
  classes before pairing and again before production WebSocket connections.
- Incoming plaintext is size/hash/canonicalization validated, parsed as native
  Markdown, and sanitized; Reader does not execute article JavaScript.

## Relay and crypto behavior

- Only `wss://` is permitted in production relay configuration.
- NIP-42 uses an anonymous Reader transport key. A real identity/payment
  requirement is incompatible, not an excuse to deanonymize the user.
- Each operation signs at most one validated AUTH challenge. An identical
  duplicate reuses that result; a changed challenge fails closed without a
  second signature. Chrome and Android exact-redact challenge echoes even when
  they are too short for generic credential-pattern redaction.
- Relay `OK=true` is shown only as relay acceptance. `Delivered` requires the
  exact authenticated Android ACK.
- Chrome reads an unauthenticated pair response only from fixed bootstrap
  relays. After that response authenticates the full relay digest, final
  completion catch-up uses every bound relay, including custom relays.
- Pairing ACK publication is throttled to one fresh attempt per 30 seconds and
  completion is queried first. Missing relay `OK` does not suppress completion
  reads after a finished send attempt; Android's authenticated completion is
  stronger evidence. Android's one-time worker remains retryable while any
  non-expired pairing row exists, so timing cannot silently transfer recovery
  to a later periodic job.
- Durable relay state is treated as untrusted input before connection and
  quorum calculation; missing or reordered state cannot become a zero-relay
  success or an implicit default fallback.
- Never-sent unbound captures may adopt the current device identity at first
  authenticated pairing. A transfer with an existing manifest identity must
  retain its original sender/channel binding or fail locally before network.
- Outer expiry is checked before decryption; application expiry, recipient,
  sender, protocol, kind, signature, MAC, hash, and resource limits are checked
  before state mutation.
- Reader gzip is decoded as one exact member under a streaming expanded-size
  limit. The consumed DEFLATE boundary, CRC32, ISIZE, and end-of-input must all
  agree; concatenated members and trailing bytes are rejected.
- There is no NIP-04 downgrade or plaintext transport fallback.

## Logs and retained evidence

Production diagnostics retain normalized public relay URLs, event IDs or short
prefixes, counts, state names, wall/monotonic time, and sanitized reason
prefixes. They do not retain private keys, QR payloads, ciphertext bodies,
plaintext articles, complete AUTH challenges, or signer responses. Hostile
local relay tests cover challenge echoes through Chrome `NOTICE`/`OK` and
Android `NOTICE`/`OK` traces. Source and
filename-pattern scans found no committed credential-like material. The only
64-hex source match outside tests/vectors was a deterministic Rust unit-test
golden hash inside `#[cfg(test)]`.

Secret exposure: **NOT DETECTED** within the executed source, artifact-name,
Chrome storage-boundary, and retained-log checks. This is not a guarantee
against arbitrary endpoint compromise.

## Testing identities and publication boundary

All live relay operations used generated disposable keys and synthetic public
test text. No release, store upload, deployment, merge, push, production
signing key, or real user identity was used. The APK is debug-signed and the
Chrome ZIP is an unpacked/developer artifact only.

## Beta candidate evidence boundary — 8 September 2026

The current artifacts and source-site acceptance are indexed in `ARTIFACTS.json`
and `docs/beta/SOURCE_SUPPORT_MATRIX.md`. The active native reader renders image descriptions as text without fetching
remote image URLs. No image opt-in control is claimed. Synthetic relay fixtures use
isolated QA identities and local TLS routing; their certificates, profiles and
control tokens are excluded from Git. Committed evidence contains hashes and
non-secret synthetic transport summaries. Owner storage fingerprints and raw
screenshots/traces remain local. The final in-place upgrade retained byte-identical
database/WAL, key preference and DataStore files before first startup.

Current npm audit found zero vulnerabilities including development dependencies.
This does not certify Android dependencies, live signers or provider DOMs. Quote
sharing emits only the quote as text/plain, without subject, URL or stream; exact
local receiver checks cover Unicode and line breaks. Exports exclude channel keys
and are not restorable backups. No telemetry/backend or store publication was added.

## Local retention hardening

Chrome's old retained capture store remains an explicit, separately deletable recovery
library. New captures keep plaintext/compressed transport payload only while pending;
lightweight capture IDs survive payload cleanup as bounded terminal tombstones
(delivered/discarded/retained) and report settled status truthfully. Android saves
frozen pending selections in an app-private atomic journal until Room commits them;
corrupt entries are quarantined without blocking later selections and the journal is
covered by existing app backup exclusions and contains no pairing keys. Article
deletion (archived-only) preserves quotations and durable historical transfer outcomes
(Room v10 binds byte hash + chunk count; synthetic migration deletion times are
documented). Fresh wrappers of a deleted transfer cannot resurrect its content;
conflicting same-ID payloads are rejected; explicit new transfers remain
possible. Revocation cancels local channel work and rechecks commit ownership but
cannot remove ciphertext retained by relays. Export is single-flight to a
user-selected SAF URI, with checksums and readback verification, excludes keys
(filenames and bodies), and remains a plaintext one-way export. Google speech retains
its configured voice/language; Reader inspects whether that voice requires a network
connection (refreshed after engine init) rather than labeling all voices offline;
transient ducking does not pause.
