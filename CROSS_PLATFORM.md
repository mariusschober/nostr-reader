# Cross-platform basis (Android + Chrome now, Mac next, iOS after)

## Decision

`rust-core` is the normative algorithm owner: canonicalization, hashing,
gzip/chunk-plan, NIP-44/59 codec checklist, narration + RSVP token policy,
limits, rolling-window rule. Thin native shells own only UI, OS services,
and key storage.

| Layer | Android (now) | Chrome (now) | Mac (next) | iOS (after) |
|---|---|---|---|---|
| Core algo | Kotlin port, vector-gated | TS wrapper, vector-gated | Rust via FFI | Rust via FFI |
| Crypto | BC secp256k1+NIP-44, no NDK | nostr-tools | rust-core + Keychain | rust-core + Keychain |
| UI | Compose | vanilla DOM | SwiftUI | SwiftUI |
| Keys | Keystore-GCM wrap | storage.local | Keychain wrap | Keychain wrap |
| TTS | Android TTS+Media3 | — | AVSpeechSynthesizer | AVSpeechSynthesizer |
| Store | Room/DataStore | IndexedDB | SwiftData | SwiftData |

## Binding contract

- Rust crate `reader-core` exposes: `canonicalize`, `document_id`,
  `pack(manifest+chunks)`, `unwrap_verify`, `rsvp_tokens`, `narrate`,
  `word_count`, `check_limits`. UniFFI generates Kotlin + Swift bindings;
  TS validates against the same vectors in CI.
- `shared/test-vectors/pairing-v2.json`, `codec-v2.json`,
  `nip44-official.json`, and `bip340-official.csv` are release gates for every
  applicable platform. The pairing vector fixes the exact Chrome-produced
  request/ACK and Android-produced response/completion transcript. The
  historical `golden-v1.json` is migration evidence only. No platform may
  "fix" vectors locally.
- Every codec port must stream one raw DEFLATE stream under the shared expanded
  limit, verify its gzip CRC32/ISIZE, and require that member's trailer to end
  the input. Concatenated members and trailing bytes are cross-runtime
  rejection gates, not implementation-defined behavior.
- Design tokens: one Flexoki table per platform generated from the same hex
  source (`shared/`); article fonts bundled, never fetched.
- No platform may add protocol fields unilaterally; a breaking change requires
  a version after `reader/2` and an explicit compatibility decision.

## iOS readiness checklist

Core has no Android imports; all OS effects behind traits; SwiftData schema
mirrors Room tables 1:1; Share-extension sender reuses the Mac sender flow.
