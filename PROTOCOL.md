# Reader Protocol v2

This is the authoritative wire contract for the repaired Reader transport. The
document protocol is `reader/2`; the pairing protocol is `reader-pair/2`.
Version 1 is intentionally not accepted as version 2.

## Fixed constants

| Item | Value |
|---|---|
| Nostr rumor kind | `30078` |
| NIP-59 seal kind | `13` |
| NIP-59 wrapper kind | `1059` only |
| Pairing-code lifetime | 300 seconds |
| Pairing message lifetime | 600 seconds |
| Document/outbox lifetime | 7 days |
| Receiver look-back | rolling 10 days |
| Compressed maximum | 5 MiB |
| Expanded maximum | 20 MiB |
| Chunk maximum | 512 |
| Chunk payload target | 24 KiB compressed bytes |
| Relay write quorum | 2 matching positive publication results per payload |

All IDs, hashes, public keys, and signatures use lowercase fixed-length hex.
JSON decoders reject duplicate object keys. Message validators reject unknown
fields unless the field is explicitly optional in the matching schema under
`shared/schemas/`.

## Relay configuration

The ordered default set is:

1. `wss://nos.lol`
2. `wss://relay.primal.net`
3. `wss://relay.nostr.net`
4. `wss://nostr.oxtr.dev`
5. `wss://offchain.pub`
6. `wss://nostr-pub.wellorder.net`

Chrome Settings may add at most two custom relays, for a total maximum of
eight. Defaults cannot be removed. Custom relay URLs must be normalized,
unique `wss://` URLs without credentials, query, fragment, control characters,
numeric/localhost/`.local` hosts, ambiguous paths, or default duplicates.

Changing a custom relay preference does not silently mutate an established
channel. The new ordered set takes effect only after re-pairing, when its digest
is authenticated by both endpoints. Android resolves every proposed hostname
before trust and uses a DNS guard on every production WebSocket connection;
any private, loopback, link-local, multicast, carrier-grade-NAT, or IPv6
unique-local answer rejects the connection.

`shared/default-relays.json`, Chrome's `protocol/relays.ts`, and Android's
`PairingProtocol.kt` must stay byte-for-byte equivalent; a contract test checks
them.

## Pairing state machine

### 1. Chrome creates a request

Chrome creates a fresh pairing private key and stores it only in trusted
extension storage. The QR contains no private key. Its exact fields are:

- `protocol`, fixed to `reader-pair/2`;
- random 128-bit `sessionId`;
- fresh `pairingPubkey`;
- stable local `chromeDevicePubkey`;
- random 256-bit `nonce`;
- ordered `relays` and `relaySetDigest`;
- `createdAt`, `expiresAt`, and required `capabilities`.

`relaySetDigest = SHA-256(UTF-8(JSON.stringify(relays)))`.

Chrome probes the six fixed bootstrap relays and does not display a QR unless
at least two can be queried. Custom hostnames are not contacted by Chrome at
this stage. Because replies use durable kind 1059, the pairing tab and MV3
worker need not remain alive.

### 2. Android validates and asks for consent

Before network activity Android enforces the exact schema, size, time, point,
capability, relay-count, URL, uniqueness, and digest rules. It then presents a
human-readable Chrome fingerprint and full relay list. Only the explicit
**Connect** action permits DNS resolution and network activity.

Android first persists a `provisioning` intent, wraps a fresh channel private
key with Android Keystore, then advances to `pending_response`. A process death
therefore leaves a bounded, revocable row rather than an orphaned credential.
As long as any non-expired pairing row remains pending, the one-time
WorkManager owner returns `retry` with a ten-second exponential backoff; waking
before `nextAttemptAt` cannot hand recovery solely to the 30-minute periodic
worker.

### 3. Android sends `pair-response`

The response binds the request protocol, session, nonce, pairing recipient,
Chrome device key, fresh Android channel key, relay digest, app version,
capabilities, and bounded timestamps. The inner NIP-59 sender must own the
returned Android channel key. Android publishes a freshly wrapped copy to each
relay and persists exact per-relay outcomes.

At least one matching positive `OK` is still required before Android advances
to `awaiting_ack`. If none arrives, the non-active channel remains
`pending_response`, the UI reports that confirmation is still pending, and
bounded one-time work retries without requiring another scan. Explicit cancel,
expiry, malformed durable state, or key loss revokes the row and deletes its
wrapped key.

No channel is active at this point.

### 4. Chrome sends `pair-ack`

Chrome catches up from the request's rolling window, decrypts the response with
the pairing key, verifies the seal/rumor chain, and binds the authenticated
inner sender to `androidChannelPubkey`. It sends `pair-ack` from the stable
Chrome device key to that Android key. Before that authenticated response,
Chrome queries only the six fixed bootstrap relays; it does not contact custom
hostnames merely because they appeared in durable state.

The authenticated response transition is persisted without the one-time
pairing secret before Chrome attempts the ACK. Chrome publishes a freshly
wrapped ACK immediately on the next recovery pass and then at most once every
30 seconds while waiting for completion. A completed ACK send attempt enters
completion-reading even if no relay returned a matching positive `OK`, because
missing transport evidence does not prove the event was not stored. Chrome
queries for completion before each retry, so a retained authenticated
completion wins without another publication. ACK retry continues until
authenticated completion, cancellation, supersession, or the session expiry
ceiling.

`acceptedRelays` means the ordered subset Chrome accepts as the channel
configuration. It is not a claim that every listed relay returned `OK=true` for
this ACK; per-message transport outcomes remain separate evidence.

### 5. Android sends `pair-complete`

Android accepts an ACK only from the exact Chrome device key and only when all
session, endpoint, relay-digest, status, and time bindings match. It then sends
`pair-complete` from the Android channel key. Only after at least one relay
accepts that completion does Android transactionally promote the channel and
revoke any previous active channel.

Chrome marks the channel connected only after authenticating the completion.
Because Android has now authenticated the exact relay-set digest and applied
its public-address guard, Chrome queries for this completion on the complete
bound set, including custom relays. A completion accepted only by a custom
relay therefore cannot leave Android active while Chrome waits only on the
defaults. Chrome then supersedes other live pairing sessions. The one-time
pairing secret was already physically removed at authenticated response, not
deferred to this final step. Cancellation, expiry, disconnect, and replacement
also physically remove one-time or obsolete key material.

`shared/test-vectors/pairing-v2.json` is the field-exact cross-runtime
transcript gate: Chrome must reproduce its request and ACK, Android must
reproduce its response and completion, and each receiving runtime must accept
the opposite platform's fixed message only with the recorded authenticated
sender and validation time.

## NIP-59 envelope

Every pairing, manifest, chunk, and endpoint ACK uses a fresh outer key and a
fresh signed kind-1059 wrapper per relay:

1. Create an unsigned kind-30078 rumor with canonical NIP-01 ID.
2. Encrypt the rumor with NIP-44 v2 and sign a kind-13 seal from the stable
   endpoint key. Seal tags are empty.
3. Encrypt the seal with NIP-44 v2 using a fresh outer key.
4. Sign kind 1059 with exactly one `p` tag for the intended recipient and one
   NIP-40 `expiration` tag.
5. Randomize seal and wrapper timestamps into the recent past per NIP-59.

Receivers verify, in order: wrapper kind, lowercase hex, outer signature,
single exact recipient tag, expiry, wrapper decryption, exact seal kind and
empty tags, seal signature, seal timestamp, seal decryption, absent rumor
signature, canonical rumor ID, rumor/seal sender equality, expected trusted
sender where already bound, protocol version, then exact payload schema.

NIP-04 fallback is forbidden. Kind 21059 is rejected in v2.

## Document canonicalization and gzip

Canonical Markdown is produced by:

1. NFC normalization;
2. CRLF/CR to LF;
3. trailing space/tab removal per line;
4. collapse runs to at most two blank lines;
5. remove leading/trailing blank lines;
6. append exactly one LF.

`documentId = SHA-256(UTF-8(canonicalMarkdown))`.

The only v2 compression is deterministic RFC-1952 gzip: exactly one member,
DEFLATE level 6, no optional header fields, `mtime=0`, `XFL=0`, and `OS=3`.
Decoders require this ten-byte header, stream inflation under the 20 MiB
expanded limit, identify the exact end of the first raw DEFLATE stream, verify
that member's CRC32 and ISIZE, and require its eight-byte trailer to end the
input. A second member or even one trailing byte is invalid. Decoders also
reject zlib/raw-DEFLATE framing and truncation. Different conformant DEFLATE
implementations may choose different blocks, so hashes bind the actual
transmitted bytes. Cross-runtime fixtures and malformed-boundary regressions
live in `shared/test-vectors/codec-v2.json` and the four runtime suites.

## Manifest and chunks

Chrome persists the complete outgoing intent before any network send. A random
128-bit `transferId` remains stable across retries. `manifestId` is:

```text
SHA-256(UTF-8(JSON.stringify([
  "reader/2", transferId, documentId, compressedSha256, compressedBytes,
  chunkCount, senderDevicePubkey, recipientChannelPubkey, expiresAt
])))
```

The manifest carries endpoint keys, content and compressed hashes, exact sizes,
chunk count, capture metadata, and expiry. Every chunk binds `transferId`,
`manifestId`, `documentId`, `compressedSha256`, index/count, and the same
expiry. Chunks may arrive before the manifest, out of order, or more than once.

Android commits a document only after all authenticated chunks match the
manifest, contiguous indices are present, gzip size/hash/CRC and expanded size
match, UTF-8 and canonicalization are exact, `documentId` and word count match,
and the Room transaction succeeds. The document hash provides exactly-once
local effect; exact replays produce a `duplicate` ACK rather than another row.

Authenticated wrapper IDs are persisted in the same transaction as their
staging/document effect. When a transfer completes, that transaction also
creates the durable ACK intent. Thus a process crash cannot leave a committed
document without a recoverable ACK, and later rolling-window reads skip wrappers
already applied to Room.

## Endpoint ACK and delivery truth

Only a durable document commit (including a persisted historical transfer outcome after local deletion) permits Android to send an ACK. Historical receipts retain their original receipt time. It binds:

- protocol and type;
- transfer, document, manifest, and content hashes;
- Android sender-channel and Chrome recipient-device keys;
- `stored`, `duplicate`, or `rejected` status;
- bounded receipt and expiry times;
- a constrained reason code only for `rejected`.

Chrome accepts it only inside a fully verified NIP-59 envelope from the paired
Android channel and with every field matching the persisted outbox item.
Before computing a write quorum or opening a connection, Chrome revalidates
that durable channel and item relay state is exactly the six ordered defaults
plus the authenticated custom suffix. Missing or corrupt state never becomes a
zero-relay success and never selects an implicit fallback channel.
`OK=true` from a relay means only `relay_accepted`. Chrome calls an item
`delivered` and deletes its captured payload only after a valid `stored` or
`duplicate` device ACK. Recent delivery receipts retain only opaque transfer ID
and time, plus bounded title/source metadata; they carry no article body.

Android persists positive ACK publication results per relay. It attempts every
configured relay once and requires two unique matching positive results before
marking the receiver ACK lifecycle complete. Below quorum, only missing relays
are retried with bounded backoff. A later wrapper for the same immutable
transfer cannot reset a completed quorum or an exhausted retry ceiling.

User-visible states remain distinct: `queued`, `relay accepted`, `awaiting
device`, `delivered`, and `failed`.

## Retry and recovery

Each retry creates new NIP-59 outer randomness but preserves `transferId` and
manifest identity. Every payload is attempted on every configured relay even
if an earlier payload misses quorum. Backoff is exponential with jitter, capped
at one hour, 168 attempts, and seven days. A ceiling marks an item failed but
does not silently delete its captured text; only authenticated ACK or explicit
user discard removes it.

Chrome alarms re-enter pairing, ACK catch-up, and delivery retries after MV3
suspension or browser restart. Android WorkManager performs rolling-window
catch-up, resumes durable pending ACKs, and activity resume requests an
immediate run. Periodic and foreground-triggered workers are serialized inside
the process. Force-stop cannot be promised until the user opens the app again.

## Compatibility

`reader/1` and `reader-pair/1` channels are not silently upgraded because they
lacked authenticated two-endpoint completion and used incompatible compression
and routing behavior. Migration preserves documents, marks old channels
`legacy_repair_required`, and requires reset/re-pair. The historical v1 vector
is retained only as migration evidence; it is not a v2 acceptance vector.

## Beta 0.9.0 receipt retry clarification

Relay publication checkpoints are not device receipts. Normal resumable publication
reuses an accepted fragment for up to 15 minutes. An explicit Retry while awaiting
the device now sends a freshly encrypted manifest wrapper even when that manifest
has a recent accepted checkpoint; accepted chunks remain reusable. This fresh,
authenticated same-transfer demand lets Android reissue a lost completed receipt
subject to its five-minute cooldown and maximum eight refreshes. It does not mint
a new document identity, bypass ACK binding, or treat relay OK as delivered.

## Local ownership and recovery hardening

Capture IDs deduplicate a browser request independently of its transport payload.
Chrome IndexedDB v3 preserves older plaintext captures as a separately owned recovery
library, exposed in Settings for export and deletion. New captures store only a
lightweight ID record (captureId, transferId, creation time, terminal status)
plus pending transport content. An ACK or pending-transfer discard tombstones the
ID as delivered/discarded and never deletes a separate library copy. Repeating a
capture ID returns its original terminal result without recreating transport; a
deliberate new capture must use a new capture ID. Tombstones are bounded to the
newest 2000 settled IDs; pending transport is never pruned.

Android Room v10 persists completed logical-transfer outcomes in the same transaction
as document/processed-event/ACK effects. Each outcome binds channel, transfer,
manifest, document, recipient, receipt/expiry times, deletion disposition, plus
compressed-byte hash and chunk count (empty/zero for pre-v10 backfill, which skips
that sub-check). Permanent deletion of an archived article marks those outcomes
locally deleted in the same transaction and retains quotations. Fresh authenticated
wrappers for the same transfer cannot reconstruct deleted content; conflicting
payloads with the same IDs but different byte identity are rejected, never ACKed.
A deliberate capture with a new transfer ID may store identical text again. Expired
historical ACK records are not replaced with invented current receipt timestamps;
migration-inferred deletion times (receivedAt*1000 for documents already gone at
migration) are documented as synthetic. Outcomes from intents purged before the
hardening migration have no backfill — a known pre-hardening replay window.

Active Android channel snapshots revalidate the stored relay digest and derived
receiver key before network use. Revocable jobs own sockets; durable intake, history
coverage and ACK result commits recheck the active generation. Recently-revoked IDs
are bounded (128) for instant-cancel of late acquires. Revocation is local
control, not remote ciphertext recall.

Active Android channel snapshots revalidate the stored relay digest and derived
receiver key before network use. Revocable jobs own sockets; durable intake, history
coverage and ACK result commits recheck the active generation. Revocation is local
control, not remote ciphertext recall.

Android history coverage runs beside foreground delivery with independent per-relay
budgets. Its inclusive windows cover the rolling ten-day interval. It consumes
successfully before checkpointing, subdivides capped or byte-limited reads, and
persists saturated single-second buckets as incomplete. A failed relay does not
cancel healthy relays. Coverage cannot prove retention by a relay that silently
withholds events.
