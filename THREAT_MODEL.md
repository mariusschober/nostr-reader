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
| malicious relay | drop, reorder, replay, correlate, lie about storage, echo AUTH challenges into diagnostics, or create a signing loop | exact matching first-terminal OK semantics; independently constrained NIP-42 template; one challenge/signature attempt per operation; exact challenge redaction in reasons/notices/traces; end-device ACK; NIP-44/NIP-59 verification; expiry/dedupe; multi-relay quorum | hostile AUTH/NOTICE/OK fault harness + physical seven-relay flow | traffic metadata and denial of service remain |
| malicious QR | SSRF/local-network connection, long-lived or attacker-bound channel | 4 KiB cap, exact fields/protocol, near-now expiry, x-only points, nonce/session sizes, 1–8 normalized unique WSS URLs, DNS public-address validation, human review | TS/Kotlin tests + emulator malformed QR | hostname operator may later change DNS; guarded again per connection |
| DNS rebinding | custom relay resolves public during pairing then private later | resolve and reject private/loopback/link-local/multicast/CGNAT/ULA answers before every Android connection | unit/fault tests and source review | Chrome performs only fixed-default readiness probes before QR |
| forged/tampered Nostr event | insert/ack false content | outer/seal signature, NIP-44 MAC, canonical rumor ID, sender/recipient/version/schema/hash binding | official vectors, transport tests, instrumentation | endpoint key compromise defeats authenticity |
| replay/duplicate | duplicate document, stale trust promotion, or repeated ACK traffic | expiry, one-shot session binding, run + persistent wrapper dedupe, persistent document ID, exact transfer identity, one terminal ACK lifecycle per transfer | unit/instrumentation/live duplicate + installed-state repeat observation | first v5 catch-up has no historical v4 wrapper ledger and may emit one recovery ACK; observed once on TCL |
| overlapping Chrome handlers | stale publisher rewrites an outbox item after authenticated ACK deletion | keyed per-transfer serial execution, durable reload inside the critical section, ACK-first retry, receipt-before-delete ordering | deterministic scheduler tests + source/state assertions | browser crash between durable operations is recovered by the retained item or ACK replay |
| corrupt Chrome durable state | false connected state after device-key loss/replacement, wrong-identity sends, zero-relay quorum, implicit fallback, custom-relay completion blind spot, or unintended network target | exact device-secret/public-binding check; x-only point validation; canonical six-default-plus-custom validation before network/quorum; request digest recheck; fixed-only bootstrap then full authenticated completion query | device-binding, relay-contract, and worker recovery regressions | deliberate compromise of the trusted service worker/storage boundary remains out of scope |
| pairing interruption or retry amplification | zero-confirmation response forces a rescan, handshake strands after an ACK stored without returned `OK`, secret retained longer than needed, or UI polling creates repeated publication | keep zero-confirmation Android response non-active/retryable; persist Chrome response authentication without the bootstrap secret; enter completion-reading after every ACK send attempt; throttle retries to 30 s; keep Android one-time work retryable while pending | Chrome transition/timing/source tests + Android JVM/instrumentation ownership tests | required physical in-flight kill/restart quota remains NOT MEASURED |
| hostile article input | memory/CPU exhaustion, ambiguous concatenated gzip, trailing-data polyglot, or script/embed injection | byte/chunk/title/URL caps; streaming one-member gzip with exact consumed boundary, CRC32/ISIZE, and end-of-input; strict UTF-8/canonicalization; sanitized native Markdown; no webview execution | four-runtime codec boundary tests + physical Android rejection case + XSS tests | remote image requests reveal reader IP to image host |
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
9. A relay-supplied AUTH template is validated as an exact, current,
   relay/challenge-bound kind-22242 event before any transport key signs it.
10. One relay operation signs at most one AUTH challenge. Complete challenges
    cannot be retained in diagnostics even when a relay repeats them through a
    short `NOTICE`, rejection, or close reason.
11. A durable relay list is never authoritative merely because it came from
    extension storage; canonical set and transcript bindings are rechecked
    before network use.
12. A durable Chrome channel is paired only while the current private device
    key derives the exact public identity authenticated in its transcript.
13. A pairing bootstrap secret is not retained after Android's response is
    authenticated; ACK recovery uses only the stable Chrome device key.
14. One gzip member consumes the entire compressed field. A second member or
    any trailing byte is rejected before plaintext processing.

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
