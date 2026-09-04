# Reader Threat Model v1

## Boundary

Encryption boundary: sender device key -> receiver channel key (NIP-44 +
NIP-59). Device-compromised endpoints and their local stores are OUTSIDE the
boundary. Relays are untrusted transport: they may log, retain, reorder,
duplicate, or drop anything.

## True claims

- Relay cannot read article plaintext (assumes correct NIP-44 + key secrecy).
- Tampering is detectable (event id/sig, NIP-44 MAC, document/chunk hashes).
- Default identity is an anonymous per-app device key; the user's real Nostr
  identity is never required and never touches relays automatically.
- External-signer proofs (NIP-07 / Amber) stay INSIDE transport encryption.
- Expiry (NIP-40 + inner expiresAt) is storage hygiene only. Assume relays keep
  ciphertext forever; security must hold anyway.

## Relay-visible metadata (accepted, documented)

IP/connectivity, timing, recipient pubkey routing, ciphertext size buckets,
subscription patterns. No perfect anonymity is claimed.

## No forward secrecy

NIP-44 is not a ratchet. A later channel-key compromise can decrypt retained
historical ciphertext. No Signal-level claim is made. Mitigations: per-channel
keys (one per pairing), revocation/re-pair, outbox deletion after E2E ACK,
no keys in backups/exports.

## Endpoint rules

- Chrome: private key only in service-worker scope, never in content/MAIN
  world; MAIN-world NIP-07 bridge signs only exact prebuilt proof templates
  and re-validates kind/content/tags/pubkey/id/sig; strict CSP, no eval,
  schema-validate all content-script messages, size-cap before crypto.
- Android: secp256k1 channel key wrapped by Keystore AES-256-GCM; keys, Room
  DB, and sensitive prefs excluded from auto-backup; every received doc is
  hostile input (auth sender first, enforce limits, bounded inflate, sanitize
  HTML, http(s) images only, no javascript:/embeds).
- Mac: same contract via Keychain (see MAC.md).

## Abuse limits (all platforms)

5 MiB compressed / 20 MiB expanded / 512 chunks / bounded titles, URLs,
authors, Base64 bodies / capped concurrent incomplete transfers + transfer age.
Failures are bounded, deterministic, and tested (see chrome/tests,
android tests). Remote images are loaded directly and therefore reveal the
reader IP to the image host — stated in UI-adjacent docs, no proxy in v1.
