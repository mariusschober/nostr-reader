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
| 4 -> 5 | adds durable receiver ACK intents and a processed-wrapper ledger | documents, settings, channels, and authenticated transfer staging preserved | PASS physical-device instrumentation and one preserved-state TCL migration/catch-up observation |

The v3 -> v4 path exists because a diagnostic v3 database was installed on the
physical TCL before final transfer assembly landed. The instrumentation test
opens a v3-shaped database, migrates it through v5, and verifies that existing
documents survive and both new durable ledgers are usable.

Version 5 starts with no historical processed-wrapper rows. Its first catch-up
may therefore create one recovery ACK intent for a still-retained, already
stored v4 transfer. Once that authenticated wrapper and ACK result are written,
later rolling-window catch-ups do not restart the completed ACK lifecycle.
The preserved TCL exhibited exactly that one recovery batch followed by an
immediate second catch-up with no repeated ACK publication.

## Chrome migration

The anonymous Chrome device key and queued captures remain local. Version 1
channel bindings are not silently converted; v2 pairing writes
`protocolVersion=2`, the authenticated channel key, the exact relay list, and
its relay-set digest and Chrome device public-key binding. A compatible
earlier-v2 channel that predates the separate digest or public binding marker
receives a one-time backfill from its already-authenticated canonical relay
list and its still-valid local device key; all new pairings persist the fields
together. A missing, malformed, or mismatched private key is not migrated: the
old channel fails closed and re-pairing is required, while queued captures and
preferences remain local.
Pairing sessions are versioned and bounded. Old ad-hoc pairing keys are removed
after v2 recovery.

The pairing-session persistence hardening is compatible with valid v2
transcripts. A session that has already authenticated Android no longer needs
its bootstrap decryption secret; old `response_validated` or
`waiting_completion` rows have that field stripped on recovery and continue
with the stable Chrome device key.

Adding or removing custom relays changes only the preferred next pairing set.
An established channel keeps its authenticated relays until the user re-pairs.
This prevents relay-set split brain.

## Downgrade safety

Downgrading the Android app over the current Room v10 database is unsupported and must be
blocked. Older builds do not know the v10 schema or v2 trust states. They must
not be allowed to reinterpret v2 channels.

Use a separate device or isolated test package for an older build. Do not uninstall or clear the owner installation as a downgrade procedure. A one-way Markdown/JSONL export is not a restorable database, review queue or pairing backup; there is no supported full restore path.

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

`shared/test-vectors/pairing-v2.json` gates the exact Chrome/Android v2
request, response, ACK, and completion fields. `codec-v2.json` gates
deterministic gzip/hash behavior in Rust, TypeScript, Kotlin, and Swift. The
NIP-44 and BIP-340 corpora gate applicable crypto implementations. Mac code
currently validates the codec/core contract but is not a complete v2 transport
client; UniFFI and release UI work remain future work.

Valid Reader v2 gzip bytes remain compatible. Concatenated gzip members and
arbitrary bytes after a valid member were never part of the one-member wire
contract; inputs that older convenience decoders accidentally accepted now
fail closed on every runtime.

## Historical v8 upgrade (8 September 2026)

That checkpoint used Room v8. The v5→v6 migration adds the bounded ACK refresh
counter. The v6→v7 migration moves article bodies to bounded content parts and adds
a summary index. The v7→v8 migration adds durable highlights/review and sync-health
storage. Existing documents, triage, progress,
channel identity and preferences are preserved; no destructive fallback is used.
Seven physical storage/migration/gzip checks passed on TCL (`tcl-storage-migration-tests.log`).
The final owner upgrade uses versionCode 2 / versionName 0.9.0-beta.1. Before app
startup, database/WAL, key-store preference file and DataStore file fingerprints
were identical across installation. Matching final instrumentation opened the
preserved database and passed SQLite integrity checking. This is scoped upgrade
proof, not a restore-from-backup test. Downgrade to a pre-v8 build is unsupported.

Article Markdown and highlight JSONL exports are one-way user-readable exports.
Quotes include semantic/version/context anchors and review metadata; channel
private keys and pairing bootstrap secrets are excluded. Import/restore of an
export and exact review queue restoration are not implemented.

## Current schema and installed upgrade (9 September 2026)

Room is v10. v8→v9 adds history coverage and durable transfer outcomes, seeding outcomes only from retained ACK intents. Missing pre-migration outcomes cannot be invented; inferred deletion times are synthetic. v9→v10 adds compressed hash, chunk count and a document index. See [reliability implementation](RELIABILITY_HARDENING.md) for compatibility limits on legacy rows. Chrome IndexedDB v3 separates terminal capture tombstones from pending payload and retained recovery copies; malformed legacy rows are skipped and quota failures abort migration for retry.

Final UI work adds no database schema migration. TCL core storage/migration instrumentation passed on `19c8937`; final `ca06d1d` normal installation preserved storage bytes and passed SQLite integrity. See [UI completion](UI_UX_COMPLETION_REPORT.md). These are scoped checks, not proof of every historical upgrade path. Ordinary Markdown/semantic HTML local import is supported; full export restore remains unsupported.
