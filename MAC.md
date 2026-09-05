# Mac app (next goal): sender + minimal reader

Kindle-grade ease is the bar: push from anywhere on macOS in one gesture,
read in a quiet window that matches Android.

## App shape (one app, two entries)

1. **Send to Reader** (sender): menu-bar item + Share extension + Finder Quick
   Action + Services entry. Accepts txt/md/selection/HTML, canonicalizes with
   reader-core, encrypts to the paired Android channel (or a paired Mac
   channel), sends via the same authenticated relay set and two-relay write
   quorum + IndexedDB-equivalent outbox (SwiftData) + E2E ACK clearing. Mirrors Amazon's "Send to Kindle"
   placement, not its cloud.
2. **Reader** (minimal reader): SwiftUI article window mirroring Android —
   Inbox (attention time, not counts) / Archive, title+source+time header,
   5 bundled fonts, exactly Font/Size/Margins/Background(Paper,Soft,Ink,Black),
   shared semantic cursor across scroll + AVSpeechSynthesizer TTS + RSVP with
   the same dwell policy object, export ZIP (md+JSON, never keys).

## Language and infrastructure (locked)

Swift + SwiftUI, SwiftData, AVSpeechSynthesizer + MediaPlayer remote-command
equivalent for background TTS, CryptoKit + Keychain (Keychain-wrapped channel
keys, backup-excluded), Network.framework WebSockets (or URLSessionWebSocket),
reader-core via Swift UniFFI bindings. No Electron, no Catalyst port of an
iPad app as v1, no server, no iCloud sync in v1.

## Why this sets up iOS

Same Rust core, same schemas/vectors, same SwiftUI reading components, same
Keychain pattern, same SwiftData table shapes. iOS then = new shell (share
extension + reader view) on a proven core, not a rewrite. Skeleton + binding
contract live in `mac/`; full Mac build follows Chrome+Android verification.
