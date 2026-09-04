# Mac sender: "Send to Reader" (Kindle-grade placement, no cloud)

One app, four entries, one pipeline. All entries converge on:
canonicalize (ReaderCoreSwift) -> gzip -> manifest+chunks ->
NIP-59 seal+wrap (rust-core via UniFFI) -> 2-of-3 relay quorum ->
SwiftData outbox -> E2E ACK clears plaintext.

## Entries

1. Menu-bar item: "Send selection / Send frontmost text" + queue status.
2. Share extension (`com.apple.share-services`): txt/md/selection/HTML.
3. Finder Quick Action: right-click .txt/.md -> Send to Reader.
4. Services entry: selected text in any app -> Send to Reader.

## Key storage

Channel private keys in Keychain (kSecClassKey, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`,
no iCloud sync), wrapped exactly like Android Keystore wrap. Export never
includes keys. Pairing reuses `reader-pair/1` QR: Mac scans with camera or
pastes the code; nonce-echo reply identical to Android.
