# Reader Protocol v1 (`reader/1`)

Private, zero-server reading transport over ordinary Nostr relays. Nostr carries
only NIP-44-encrypted, NIP-59 gift-wrapped ciphertext. This file is normative for
Chrome, Android, Mac, and future iOS. JSON schemas live in `shared/schemas/`;
the golden vector in `shared/test-vectors/golden-v1.json` is the interop gate.

## Constants

```text
READER_PROTOCOL = "reader/1"
Rumor kind      = 30078 (private inner rumor, never published in clear)
Seal kind       = 13
Wrap kind       = 1059 (async docs), 21059 (live pairing only)
Outer expiry    = 7 days (NIP-40 `expiration` tag on wrap)
Sync window     = last 10 days (see below)
Limits          = 5 MiB compressed / 20 MiB expanded / 512 chunks
```

## Canonical Markdown

Before hashing or sending: Unicode NFC, LF endings, trailing-whitespace trim,
collapse 3+ blank lines to 2, resolve relative links against source URL,
drop a body H1 that duplicates the title, escape literal plain-text imports.

`documentId = hex(sha256(utf8(canonicalMarkdown)))` — exact-content dedupe key.
`transferId` — random 128-bit hex per send attempt (re-send = new transferId).

## Message shapes

- `DocumentManifestV1` (`type="manifest"`): title, sourceType, capturedAt,
  `mime="text/markdown"`, wordCount, byte counts, `compressedSha256`,
  `documentSha256`, chunkCount, senderDevicePubkey, optional identityProof,
  expiresAt. Sent as chunk index -1 (first wrap) or alongside chunk 0.
- `DocumentChunkV1` (`type="chunk"`): transferId, documentId, index, count,
  dataBase64 (slice of gzip(canonical)). count <= 512, index < count.
- `AckV1` (`type="ack"`): transferId, documentId, status
  `stored|duplicate|rejected`, receivedAt, optional reason. Sent only after
  full assembly + hash verify + bounded inflate + parse + durable commit.

## NIP-59 layering (normative check order)

```text
rumor -> NIP-44 -> seal(kind 13, stable device key) -> NIP-44 -> wrap(kind 1059, fresh key) -> relay
```

Receiver MUST: verify outer id/sig; decrypt wrap; parse seal; verify seal
id/sig/kind/NIP-59 constraints; decrypt rumor; check rumor.pubkey ==
seal.pubkey == paired trusted device pubkey; validate schema + limits; then act.
No high-level unwrap helper may skip a step.

## CRITICAL sync rule (randomized timestamps)

NIP-59 randomizes wrap `created_at` into the past. NEVER sync with
`since = lastSuccessfulSync`. Always query a rolling window (10 days for a 7-day
TTL) and dedupe locally by transferId+index. A permanent regression test pins
this: a wrap created after T with `created_at < T` must still be found.

## Chunking

gzip once, hash, then size REAL serialized `["EVENT",event]` frames against the
healthiest quorum relays' NIP-11 limits (binary search + safety margin). Re-wrap
each chunk per relay with fresh outer randomness. Receiver dedupes post-decrypt,
rejects conflicting count/hash/index, assembles in order, verifies
`documentSha256`, bounded-inflates, parses, commits, then ACKs.

## Pairing (`reader-pair/1`)

QR: `{pairingPubkey (ephemeral), chromeDevicePubkey, nonce>=128bit,
relays[], expiresAt~5min}`. Receiver mints a fresh channel keypair, stores the
sender as trusted, replies NIP-59 (21059 live, else 1059 + short expiry) with
nonce echo + channel pubkey + app version. Sender verifies nonce, destroys the
ephemeral key, probes relays bidirectionally, needs 2-of-3 quorum.

## Relay policy

Public relays only in v1. 3 healthy per channel, quorum 2. Health = connect +
NIP-11 + subscribe + 1059 write + roundtrip read + size probe. NIP-42 AUTH only
with the anonymous device/channel key, never the user's real identity.
Payment-gated relays are marked incompatible.
