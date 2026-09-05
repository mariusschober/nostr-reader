# Migration and compatibility

## Protocol boundary

Reader v2 uses `reader/2` and `reader-pair/2`. It is intentionally not wire
compatible with version 1 because v1 lacked authenticated two-endpoint pairing,
recipient routing tags, durable lifecycle recovery, exact ACK binding, and gzip
agreement. Treating an old channel as v2 would create false trust.

Version 1 documents are preserved. Version 1 channel rows are marked
`legacy_repair_required`, excluded from active sync, and require explicit
disconnect/reset and re-pairing.

## Android Room migrations

| Migration | Effect | Preservation | Status |
|---|---|---|---|
| 1 -> 2 | adds Inbox/Priority/Later/Archive membership | documents and progress preserved; old finished/archived content moves to Archive | PASS historical physical QA |
| 2 -> 3 | adds protocol/pairing lifecycle fields | documents preserved; old channels retained only as `legacy_repair_required` | PASS TCL/emulator instrumentation |
| 3 -> 4 | replaces unauthenticated partial chunk staging and adds bound manifest staging | documents/settings/channels preserved; incomplete diagnostic-era chunks discarded | PASS TCL/emulator instrumentation |

The v3 -> v4 path exists because a diagnostic v3 database was installed on the
physical TCL before final transfer assembly landed. The instrumentation test
opens a v3-shaped database, migrates it, and verifies that existing documents
survive.

## Chrome migration

The anonymous Chrome device key and queued captures remain local. Version 1
channel bindings are not silently converted; v2 pairing writes
`protocolVersion=2`, the authenticated channel key, and the exact relay list.
Pairing sessions are versioned and bounded. Old ad-hoc pairing keys are removed
after v2 recovery.

Adding or removing custom relays changes only the preferred next pairing set.
An established channel keeps its authenticated relays until the user re-pairs.
This prevents relay-set split brain.

## Downgrade safety

Downgrading the Android app over a Room v4 database is unsupported and must be
blocked. Older builds do not know the v4 schema or v2 trust states. They must
not be allowed to reinterpret v2 channels.

If an older build is absolutely required:

1. Export the reading archive from the current app.
2. Record that all pairings will be lost.
3. Clear/uninstall the app before installing the older build.
4. Import only the user-controlled document export.
5. Pair again when returning to v2.

Clearing or uninstalling destroys Room data and Android Keystore entries. It is
not a pairing-preserving downgrade.

Chrome downgrade to a v1 extension likewise must not reuse a v2 channel. Use a
separate test profile or disconnect and re-pair after returning to v2. Do not
copy storage between extension versions.

## Reset and re-pair

1. In Chrome Reader Settings, choose **Disconnect device**.
2. In Android, cancel any visible pending pairing; expired pending rows are
   also revoked automatically.
3. Add/remove any custom relays in Chrome Settings.
4. Create a fresh QR and review the exact relay list/fingerprint on Android.
5. Tap **Connect** and wait until both UIs report authenticated completion.

Queued Chrome articles are intentionally retained through disconnect and can be
sent after a new authenticated pairing.

## Cross-platform compatibility

`shared/test-vectors/codec-v2.json` gates deterministic gzip/hash behavior in
Rust, TypeScript, Kotlin, and Swift. The NIP-44 and BIP-340 corpora gate
applicable crypto implementations. Mac code currently validates the codec/core
contract but is not a complete v2 transport client; UniFFI and release UI work
remain future work.
