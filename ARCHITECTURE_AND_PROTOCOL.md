# Architecture and protocol

## System boundary

Reader is a direct, local-first system. Chrome captures and encrypts; public
Nostr relays store opaque wrappers; Android decrypts and stores the article.
There is no Reader backend, account, hosted queue, telemetry collector, proxy,
push service, or production identity dependency.

```text
Chrome MV3 extension
  trusted service worker + local IndexedDB/storage
        |  NIP-44 v2 inside NIP-59 kind 1059
        v
  6 fixed public relays + 0..2 user custom relays
        |
        v
Android app
  Keystore-wrapped channel key + Room + WorkManager
```

Relays are untrusted transport. A matching NIP-01 `OK=true` proves only that a
specific relay accepted a specific event. It does not prove Chrome received a
pair response, Android stored an article, or Chrome received an Android ACK.

The authoritative field-level contract is `PROTOCOL.md` plus
`shared/schemas/`. This document maps ownership, persistence, and recovery.

## Relay configuration

The default set is six independently addressed public relays:

1. `wss://nos.lol`
2. `wss://relay.primal.net`
3. `wss://relay.nostr.net`
4. `wss://nostr.oxtr.dev`
5. `wss://offchain.pub`
6. `wss://nostr-pub.wellorder.net`

Chrome Settings accepts up to two additional `wss://` relays. Defaults cannot
be removed. Adding or removing a custom relay does not mutate a trusted channel
in place; the user must re-pair, and both devices authenticate the same ordered
relay-set digest. Android shows the exact signed list. The write quorum is two
matching positive relay results per payload.

## Complete protocol map

| Step | Sender | Receiver | Persist before send | Kind | Required tags | Encryption | Signing-key role | Relay success | End-device success | Replay key | Expiry | Retry owner |
|---|---|---|---|---:|---|---|---|---|---|---|---|---|
| Create QR | Chrome | Android camera/manual paste | v2 pairing session including one-time secret | none | none | QR contains public material only | fresh pairing pubkey + stable Chrome device pubkey | at least two default relays queryable before display | none | `sessionId` + nonce | 300 s | Chrome alarm/session recovery |
| Validate request | Chrome | Android | none until explicit Connect | none | none | none | validate both x-only public keys | none | human consent permits next step | `sessionId` | near-now bounded | user may rescan |
| Provision channel | Android | local Android state | `provisioning` row, then Keystore-wrapped fresh key | none | none | Android Keystore AES-GCM wrapping | new Android channel key | none | not connected | channel/session IDs | request + 600 s cleanup window | Android coordinator |
| Pair response | Android | Chrome pairing key | pending state before network send | 1059 | one exact `p`, one `expiration` | NIP-44 rumor -> signed seal -> NIP-44 wrap | Android channel key signs seal; fresh outer key signs wrap | per-relay matching `OK=true`; at least one advances to awaiting ACK | Chrome decrypts and validates full transcript | session, nonce, rumor/event IDs | 600 s | Android WorkManager/backoff |
| Pair ACK | Chrome | Android channel key | response-validated Chrome session | 1059 | one exact `p`, one `expiration` | NIP-44/NIP-59 | stable Chrome device key signs seal; fresh outer per relay | per-relay matching result retained separately | Android validates signer, both keys, session, digest, status, time | session + Android key | 600 s | Chrome pairing alarm |
| Pair complete | Android | Chrome device key | `ack_validated`/`completion_pending` | 1059 | one exact `p`, one `expiration` | NIP-44/NIP-59 | Android channel key signs seal; fresh outer per relay | at least one matching positive publication before Android promotion | Chrome catches up across the full authenticated relay set, validates completion, then stores channel, exact Chrome-device binding, and relay digest and removes the pairing secret | session + both endpoint keys | 600 s | Android coordinator; Chrome catch-up |
| Capture article | Chrome | local outbox | full plaintext intent and immutable transfer identity | none | none | local only | stable Chrome device key selected | none | none | `transferId`, `manifestId`, `documentId` | 7 d | Chrome alarm/manual retry |
| Manifest | Chrome | Android channel | outbox already durable | 1059 | one exact `p`, one `expiration` | NIP-44/NIP-59 | Chrome device seal; fresh outer key per relay | two unique relay `OK=true` results => `relay_accepted` | no delivery claim | stable transfer + manifest identity | outer and inner 7 d | Chrome bounded backoff |
| Chunks | Chrome | Android channel | all compressed bytes retained in outbox | 1059 | one exact `p`, one `expiration` | NIP-44/NIP-59 | Chrome device seal; fresh outer key per relay and retry | two unique positive results per chunk | none until full durable assembly | transfer/manifest/hash/index tuple | 7 d | Chrome bounded backoff |
| Receive/assemble | relays | Android | chunks/manifests staged in Room | subscription filter only | `#p` filter targets channel pubkey | verify outer, decrypt wrap, verify/decrypt seal, validate rumor/payload | trusted Chrome device must own inner sender | relay read is transport evidence only | atomic document insert or existing matching document | persistent wrapper event ID + transfer/document identity | reject outer/inner expiry | Android WorkManager and resume sync |
| Document commit | Android | local Room | one transaction inserts doc, clears staging, records wrapper, and queues ACK intent | none | none | plaintext local at endpoint | n/a | n/a | `stored` or `duplicate` only after durable presence | `documentId` | local document persists until user action | Android transaction |
| Endpoint ACK | Android | Chrome device | durable ACK intent and prior accepted-relay results | 1059 | one exact `p`, one `expiration` | NIP-44/NIP-59 | Android channel seal; fresh outer per relay | each publication classified; two unique `OK=true` results complete the ACK lifecycle | none until Chrome validates it | transfer/manifest/document/key tuple | transfer expiry, at most 7 d | Android retries missing relays below quorum with bounded backoff |
| Delivery completion | relays | Chrome | outbox item still present during validation | subscription filter only | `#p` targets Chrome device pubkey | full NIP-59 and exact ACK schema validation | exact paired Android channel signer | relay read does not equal delivery | valid `stored`/`duplicate` ACK deletes outbox and records opaque receipt | transfer + complete ACK binding | reject stale ACK | Chrome startup/alarm/manual check |

## Pairing ownership and recovery

Chrome owns a durable `PairingSessionV2` state machine in trusted
`storage.local`. It recovers on worker evaluation, extension install/update,
browser startup, and the pairing alarm. The QR uses durable kind 1059, so a
pairing tab or worker need not remain open. At most two live sessions are
allowed; completion supersedes the others. One serial executor owns pairing
session read-modify-write operations within each worker lifetime.
The completed channel also stores the public key derived from the exact local
device secret used in that transcript. Missing, malformed, replaced, or
non-curve key state cannot report paired or send under the old channel. The
channel binding is removed while preferences and captured outbox content are
retained for explicit re-pairing. Earlier v2 state receives a one-time public
binding-marker backfill only when its existing private key is valid.

Android owns durable pairing rows in Room. Workers ignore `provisioning`, can
resume the four pending v2 states, revoke expired/corrupt rows, and delete the
associated Keystore entry on cancellation/failure. Promotion to `active` and
revocation of a prior active channel happen in one Room transaction.
Foreground and WorkManager pairing operations share a process-wide mutex.

## Delivery ownership and recovery

Chrome's IndexedDB outbox is the delivery source of truth. It stores intent
before sending, preserves a stable transfer identity, re-wraps on each attempt,
and remains retryable after relay acceptance. Its states are:

```text
queued -> relay_accepted -> awaiting_device -> delivered
   |             |                |
   +-------------+----------------+-> failed (ceiling; payload retained)
```

Only a verified device ACK enters `delivered` and deletes captured content.
Relay `OK=true` never does. Publish, ACK, retry, and discard operations for the
same transfer are serialized and reload IndexedDB state inside that critical
section. Chrome persists the small delivered receipt before deleting the
outbox item, preventing stale async handlers from recreating acknowledged
content. Before sending, it also requires both stored channel metadata and the
item's relay list to equal the authenticated six-default-plus-custom set; a
missing list cannot reduce the quorum to zero or trigger a guessed fallback.
It also verifies that the current Chrome device key still owns the channel and
the bound manifest. A never-sent, unbound capture can be rebound after local
identity repair; an already authenticated/bound transfer cannot be rewritten.

Android receives through a rolling kind-1059 `#p` query. It deduplicates relay
copies by event ID within a run and persists authenticated wrapper IDs across
runs, binds every chunk to the manifest, and commits exactly one document by
`documentId`. The same transaction creates a durable receiver ACK intent only
after document presence; accepted relays and retry timing then survive process
death. A completed two-relay ACK quorum is terminal for that immutable
transfer. WorkManager provides catch-up; `onResume()` requests an immediate
run. Force-stop is explicitly not promised until the app is reopened.

## Source map

- Chrome pairing protocol and validation: `chrome/src/protocol/pairing.ts`.
- Chrome relay policy/defaults: `chrome/src/protocol/relays.ts`.
- Chrome persistence/recovery/outbox: `chrome/src/background/service-worker.ts`.
- Chrome device/channel identity binding: `chrome/src/protocol/device-binding.ts`.
- Chrome NIP-44/NIP-59 and relay I/O: `chrome/src/nostr/`.
- Android QR/network policy: `android/app/src/main/java/com/reader/app/nostr/PairingProtocol.kt`.
- Android pairing recovery: `android/app/src/main/java/com/reader/app/sync/PairingCoordinator.kt`.
- Android relay state machine: `android/app/src/main/java/com/reader/app/nostr/NostrCodec.kt`.
- Android assembly/ACK: `android/app/src/main/java/com/reader/app/sync/TransferManager.kt`.
- Android durable schema/migrations: `android/app/src/main/java/com/reader/app/data/ReaderDb.kt`.

## Current validation boundary

- **PASS:** one real Chrome/TCL v2 pairing, seven authenticated relays, one
  synthetic article, exactly one stored document, and a verified device ACK
  clearing Chrome's outbox.
- **PASS:** local fault relay, shared field-exact Chrome/Android pairing and
  codec vectors, Chrome restart/reload preservation, Android instrumentation
  on emulator and both authorized physical phones.
- **NOT MEASURED:** the mandatory 20/20 and 50/50 reliability quotas and the
  complete physical lifecycle matrix. See `TCL_PHYSICAL_VALIDATION.md`.
