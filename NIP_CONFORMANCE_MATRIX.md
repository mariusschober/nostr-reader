# NIP and BIP conformance matrix

Status describes the implemented Reader v2 paths, not every feature in the
referenced specification.

| Standard | Reader use | Enforcement/evidence | Status |
|---|---|---|---|
| NIP-01 | canonical event ID, signatures, `#p`, `OK`, REQ/EVENT/EOSE | complete JSON escaping, lowercase fixed-length wire hex, exact event ID/signature validation; baseline regressions and current TS/Kotlin tests | PASS |
| NIP-11 | relay information document | dated NIP-11 fetches for all six defaults and the tested custom relay; limits/operator metadata retained in `LIVE_RELAY_REPORT.md` | PASS |
| NIP-40 | wrapper expiration | exactly one integer `expiration` tag; receivers reject missing, duplicate, malformed, and expired wrappers before payload use; inner pairing/transfer expiry also enforced | PASS |
| NIP-42 | relay authentication | independently validate exact kind-22242 fields, current timestamp, empty content, one exact relay tag, and one exact bounded challenge before the anonymous transport key signs; publish/subscription retry once after AUTH; stale/negative/misbound/duplicate AUTH tests | PASS in local harness; no tested public relay required AUTH |
| NIP-44 v2 | endpoint encryption | exact conversation key, padding boundaries including extended lengths, MAC-before-plaintext, strict Base64/UTF-8/caps; pinned official positive/negative corpus in TS and Kotlin | PASS |
| NIP-59 | rumors, seals, gift wraps | rumor ID present/no signature; kind-13 seal has empty tags; fresh random wrapper key; kind 1059 has exact `p` and expiration; signatures/sender/recipient/timestamps verified; shared field-exact pairing transcript consumed by Chrome and Android | PASS for Reader v2 tested paths |
| NIP-07 | optional provenance proof | browser signer signs a bounded `device-auth` proof; returned pubkey/event/content are independently verified before use; no page-world key bridge | PASS unit; live Amber/nos2x browser signer NOT MEASURED |
| NIP-55 | optional Android provenance boundary | transport keys never leave Android Keystore path; baseline Amber integration remains a stub/status boundary | NOT MEASURED |
| BIP-340 | x-only secp256k1 Schnorr | exact nonce derivation with `BIP0340/aux`, `/nonce`, `/challenge`; on-curve key validation; official vectors 0–14 | PASS |

## NIP-01 details

- Event IDs hash the exact `[0,pubkey,created_at,kind,tags,content]` JSON form.
- JSON serialization handles quote, backslash, control characters, CR, LF,
  tab, backspace, and form feed through conforming serializers rather than a
  partial manual escape routine.
- Incoming events require lowercase 32-byte IDs/pubkeys and 64-byte signatures;
  uppercase wire hex is rejected.
- A relay publication is accepted only by a matching
  `['OK', exactEventId, true, ...]`. A false OK is rejection; mismatched OK is
  ignored until timeout.
- `#p` subscriptions match the wrapper's exact recipient `p` tag.

## NIP-40 details

NIP-40 is treated as input validation and relay storage hygiene, never as an
assumption that ciphertext was deleted. Outer expiry is checked before NIP-44
decryption. Pairing messages are limited to 600 seconds; documents/ACKs to
seven days. Relays may retain expired ciphertext indefinitely without breaking
the confidentiality claim.

## NIP-42 details

The relay AUTH event is signed by the anonymous pairing, Chrome device, or
Android channel key appropriate to that connection. Reader does not use or ask
for a real social identity. A relay that requires payment or a real identity is
classified incompatible rather than silently deanonymizing the user.

Chrome does not treat the dependency-provided event template as trusted. Before
signing, it requires exactly `kind`, `created_at`, `tags`, and `content`; kind
22242; empty content; a timestamp within ten minutes; one normalized relay tag
for the connected URL; and one exact 1–512-byte challenge tag. Android builds
the same constrained event itself. A matching negative AUTH `OK` is never
reported as rejection of the original Reader event, and duplicate AUTH `OK`s
cannot cause repeated publication/subscription retries.

Local harness evidence covers:

- challenge -> valid AUTH -> publication/subscription retry -> matching OK;
- stale/negative AUTH;
- misbound, malformed, stale, oversized, and expanded AUTH templates/challenges;
- contradictory original-event OKs and duplicate AUTH OKs;
- CLOSED/auth-required classification;
- challenge fingerprint logging without retaining the challenge.

No relay in the dated public run challenged the tested operation, so public
AUTH interoperability is **NOT MEASURED**.

## NIP-44 details

The corpus at `shared/test-vectors/nip44-official.json` is pinned by checksum.
Tests include the current extended two-byte/four-byte length-prefix boundaries,
tamper/wrong-key/invalid-MAC/invalid-padding/invalid-base64 cases, maximum input
caps, and constant-time MAC comparison. There is no NIP-04 fallback.

## NIP-59 details

Reader v2 intentionally uses durable kind 1059 for pairing, content, and ACKs.
It rejects kind 21059 because pairing and mobile catch-up must survive listener
absence. Timestamp randomization follows the NIP-59 recent-past guidance while
inner created/expiry values carry application freshness. Each relay receives a
fresh outer wrapper, reducing cross-relay event-ID correlation.

Pair-response bootstrap trust is not circular: Chrome first verifies the
NIP-59 chain without a pre-known sender, then requires the verified inner sender
to own the returned Android channel key and bind the one-time session, nonce,
both Chrome keys, relay digest, capabilities, and expiry.

The synthetic public-only `shared/test-vectors/pairing-v2.json` transcript is
reproduced field-for-field by the Chrome and Android unit suites and by the
Android runtime instrumentation suite. It contains public test values only.

## Primary references

- NIP-01: https://github.com/nostr-protocol/nips/blob/master/01.md
- NIP-11: https://github.com/nostr-protocol/nips/blob/master/11.md
- NIP-40: https://github.com/nostr-protocol/nips/blob/master/40.md
- NIP-42: https://github.com/nostr-protocol/nips/blob/master/42.md
- NIP-44: https://github.com/nostr-protocol/nips/blob/master/44.md
- NIP-59: https://github.com/nostr-protocol/nips/blob/master/59.md
- BIP-340: https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki

Specifications are time-sensitive. Re-pin upstream vector checksums and review
the primary documents before a release rather than assuming this dated matrix
remains current.
