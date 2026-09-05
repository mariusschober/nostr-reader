# Threat model

## Scope and assets

Protected assets are article plaintext, Chrome device secret, one-time pairing
secret, Android channel secret, pairing transcript, and the integrity of local
documents/delivery state. Relays, networks, QR inputs, captured web content,
Nostr frames, and remote images are untrusted.

An endpoint with arbitrary local code execution is outside the cryptographic
boundary. Reader reduces key exposure but cannot protect plaintext from a
fully compromised browser, Android OS, or unlocked user session.

## Adversaries and controls

| Adversary/capability | Primary risk | Enforced control | Evidence | Residual |
|---|---|---|---|---|
| malicious relay | drop, reorder, replay, correlate, lie about storage | exact matching OK semantics; end-device ACK; NIP-44/NIP-59 verification; expiry/dedupe; multi-relay quorum | fault harness + physical seven-relay flow | traffic metadata and denial of service remain |
| malicious QR | SSRF/local-network connection, long-lived or attacker-bound channel | 4 KiB cap, exact fields/protocol, near-now expiry, x-only points, nonce/session sizes, 1–8 normalized unique WSS URLs, DNS public-address validation, human review | TS/Kotlin tests + emulator malformed QR | hostname operator may later change DNS; guarded again per connection |
| DNS rebinding | custom relay resolves public during pairing then private later | resolve and reject private/loopback/link-local/multicast/CGNAT/ULA answers before every Android connection | unit/fault tests and source review | Chrome performs only fixed-default readiness probes before QR |
| forged/tampered Nostr event | insert/ack false content | outer/seal signature, NIP-44 MAC, canonical rumor ID, sender/recipient/version/schema/hash binding | official vectors, transport tests, instrumentation | endpoint key compromise defeats authenticity |
| replay/duplicate | duplicate document, stale trust promotion, or repeated ACK traffic | expiry, one-shot session binding, run + persistent wrapper dedupe, persistent document ID, exact transfer identity, one terminal ACK lifecycle per transfer | unit/instrumentation/live duplicate + installed-state repeat observation | first v5 catch-up has no historical v4 wrapper ledger and may emit one recovery ACK; observed once on TCL |
| hostile article input | memory/CPU exhaustion, script/embed injection | byte/chunk/title/URL caps, bounded gzip, strict UTF-8/canonicalization, sanitized native Markdown, no webview execution | boundary/corruption/XSS tests | remote image requests reveal reader IP to image host |
| compromised content script | steal Chrome keys from extension storage | `storage.local` access level `TRUSTED_CONTEXTS`; no page-world signing bridge | real Chrome isolated-world negative test | a compromised trusted extension page/service worker remains in boundary |
| browser-signer manipulation | swap proof fields/key or expose identity | optional provenance only; exact returned event/pubkey/content verification; proof stays encrypted | NIP-07 mutation tests | live third-party signer compatibility not measured |
| Android backup/migration | export channel key/database | key wrapped by Keystore; cloud/device-transfer backup exclusions for legacy and API 31+ | manifest/XML review, emulator build/lint | OEM backup behavior not physically inspected |
| lost Keystore entry | false “connected” channel | active row with missing wrapped key is revoked and requires repair | TCL/emulator instrumentation | uninstall intentionally destroys pairing |
| supply-chain vulnerability | XSS/crypto/build compromise | lockfiles, exact direct versions, package verifier, audit, SBOM/dependency inventory, vector gates | `npm audit` 0; Gradle inventory; source scans | Android transitive CVE database scan unavailable locally |

## Security invariants

1. QR contains public values only; private pairing material never enters DOM or
   reports.
2. A relay can never cause `connected` or `delivered` by returning OK alone.
3. Pairing binds protocol, session, nonce, both Chrome public keys, Android
   channel key, relay digest, capabilities, and time bounds.
4. Android is not active until it validates Chrome's ACK; Chrome is not
   connected until it validates Android's completion.
5. Every wrapper has one exact recipient tag, one expiry, a fresh outer key,
   and a canonical unsigned rumor inside a signed empty-tag seal.
6. Plaintext outbox deletion requires a verified Android `stored` or
   `duplicate` ACK whose complete identity matches the item.
7. No NIP-04, plaintext relay payload, TLS bypass, signature bypass, analytics,
   backend, or real Nostr identity fallback exists.
8. Expensive decode/inflate/DB work occurs only after cheap format/size/auth
   gates appropriate to the layer.

## Metadata and privacy limits

Relays and network observers can see endpoint IP, connection timing, recipient
pubkey routing tags, subscription cadence, relay set, and ciphertext sizes.
They cannot see article plaintext under the cryptographic assumptions, but
Reader does not claim anonymity, traffic-analysis resistance, or forward
secrecy. NIP-44 is not a ratchet; later channel-key compromise may expose
retained historical ciphertext.

## Denial of service

Relays can always withhold service. The six defaults plus optional custom
relays improve availability but do not make Byzantine consensus. Quorum is a
write-availability policy, not proof of permanent retention. Resource ceilings
bound QR, relay frames, NIP-44 payloads, incomplete transfers, compressed data,
expanded data, chunk count, and timestamps.

## Trust changes

Re-pairing replaces the active channel transactionally. Old keys are removed
after successful replacement; disconnect cancels active bootstrap sessions but
preserves queued local captures and the anonymous Chrome device identity.
Version 1 pairings are marked repair-required, not silently trusted under v2.
