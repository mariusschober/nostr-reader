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
- `chrome.storage.local.setAccessLevel({accessLevel:'TRUSTED_CONTEXTS'})` is
  reasserted on every worker evaluation. A real Chrome content-script test
  confirmed that `chrome.storage.local` was not exposed in Reader's isolated
  content-script world.
- Secrets are not stored in sync storage, page localStorage, the DOM, reports,
  or logs. Pairing material is removed on completion, cancellation, expiry,
  disconnect, and supersession.
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

Evidence: `evidence/raw/final/chrome-content-storage-isolation-2026-09-05.txt`.

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
- Relay `OK=true` is shown only as relay acceptance. `Delivered` requires the
  exact authenticated Android ACK.
- Outer expiry is checked before decryption; application expiry, recipient,
  sender, protocol, kind, signature, MAC, hash, and resource limits are checked
  before state mutation.
- There is no NIP-04 downgrade or plaintext transport fallback.

## Logs and retained evidence

Production diagnostics retain normalized public relay URLs, event IDs or short
prefixes, counts, state names, wall/monotonic time, and sanitized reason
prefixes. They do not retain private keys, QR payloads, ciphertext bodies,
plaintext articles, full AUTH challenges, or signer responses. Source and
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
