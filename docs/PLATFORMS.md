# Platform boundaries

Chrome and Android are the implemented product clients. They use independent TypeScript and Kotlin ports guarded by shared schemas and vectors. `rust-core/` provides reference algorithms; `mac/` contains a Swift mirror package and tests, not a runnable application. There are no generated UniFFI bindings or runtime integration, and iOS is not implemented.

## Future Mac direction

The earlier design direction is one native Swift/SwiftUI app with a minimal reader and Send to Reader entries through the menu bar, Share extension, Finder Quick Action and Services. Proposed OS components are SwiftData, Keychain, AVSpeechSynthesizer, media controls and native WebSockets. No Electron, iCloud sync or proprietary application server is planned. These are future choices, not shipped capabilities or current acceptance requirements.

Sender entries should converge on canonicalization, compression/chunking, authenticated NIP-44/NIP-59 encryption, an authenticated relay set, a durable outbox and clearing only after the receiver receipt. Keychain keys must be device-local; exports exclude keys. A reader should preserve semantic positions across scrolling, speech and Speed, use offline bundled fonts and offer readable exports. Current Android behavior in [COPY_DECK](COPY_DECK.md) supersedes the obsolete five-font/two-shelf Mac mockup.

## Compatibility invariants

- [Protocol](../PROTOCOL.md) is the authoritative v2 wire contract; no platform adds fields unilaterally.
- Shared pairing/codec/NIP-44/BIP-340 vectors gate applicable ports. Legacy v1 vectors are migration evidence.
- One gzip member, exact CRC32/ISIZE and trailer end-of-input are cross-runtime requirements; trailing or concatenated members are rejected.
- Future Rust FFI requires deliberate API expansion, bindings and target verification. Swift mirror tests do not prove transport, OS key storage, native UI or release readiness.
- iOS can reuse a proven Swift/native core later. Its shell, extensions, storage and device behavior still require implementation and independent acceptance.
