# Cross-platform basis (Android + Chrome now, Mac next, iOS after)

## Decision

`rust-core` is the normative algorithm owner: canonicalization, hashing,
gzip/chunk-plan, NIP-44/59 codec checklist, narration + RSVP token policy,
limits, rolling-window rule. Thin native shells own only UI, OS services,
and key storage.

| Layer | Android (now) | Chrome (now) | Mac (next) | iOS (after) |
|---|---|---|---|---|
| Core algo | Kotlin port, vector-gated | TS port, vector-gated | Swift mirror library only; Rust FFI planned | not implemented; Rust FFI planned |
| Crypto | BC secp256k1+NIP-44, no NDK | nostr-tools | Swift mirror only; Rust + Keychain planned | not implemented; Rust + Keychain planned |
| UI | Compose | vanilla DOM | not implemented; SwiftUI planned | not implemented; SwiftUI planned |
| Keys | Keystore-GCM wrap | storage.local | not implemented; Keychain wrap planned | not implemented; Keychain wrap planned |
| TTS | Android TTS+Media3 | — | not implemented; AVSpeechSynthesizer planned | not implemented; AVSpeechSynthesizer planned |
| Store | Room/DataStore | `chrome.storage.local` + IndexedDB outbox | not implemented; SwiftData planned | not implemented; SwiftData planned |

## Current implementation boundary

`rust-core` is a tested normative/reference implementation, but it has no
UniFFI dependency, generated Kotlin/Swift bindings, or runtime integration yet.
The shipping Chrome and Android paths use independent TypeScript and Kotlin
ports guarded by the same schemas and vectors. `mac/` is a Swift package with a
matching core mirror and tests; it is not a runnable Mac application. iOS is
not present.

## Future binding contract

- Extend `reader-core` from its current canonicalize/hash/gzip/chunk,
  rolling-window, word-count, narration, and RSVP functions to the complete
  protocol surface. Add UniFFI deliberately, then generate Kotlin and Swift
  bindings. TypeScript continues to validate against the same vectors.
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
