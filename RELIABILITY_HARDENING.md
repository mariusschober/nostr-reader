# Reliability and data integrity hardening

Work starts from `1e55b7d4021637520402cb1773ac4e1ffd3b43cf` on
`codex/reliability-data-integrity`, continued on `codex/reliability-finalize`.
This document describes the implemented candidate.
Exact artifact results and remaining acceptance gaps are in HARDENING_TEST_REPORT.md
for the prior candidate; finalization results below supersede its code claims
where noted. Historical beta reports are not certification of any candidate.

## Chrome capture ownership

IndexedDB version 3 separates three owners:

- `captureIds`: capture ID, transfer ID, creation time and terminal status
  (`pending`/`delivered`/`discarded`/`retained`). Repeating a capture ID
  returns its original terminal result without recreating transport — settled
  IDs report `queued:false` plus `settled` status instead of a fake queueing.
  A deliberate new capture uses a new capture ID and transfer ID, including
  for identical text. Tombstones are bounded to the newest 2000 settled IDs;
  pending transport is never pruned.
- `items`: the transport payload, retained while pending/failed. Authenticated
  receipt cleanup and explicit pending-transfer discard delete this payload and
  tombstone the ID (never delete the ID itself).
- `captures`: the recovery library retained by earlier versions. Migration
  validates each legacy row (strict hex/UUID + transfer ID + timestamp),
  skips malformed rows without aborting the upgrade, and creates retained
  tombstones atomically. Quota failure aborts and leaves version 2 intact for
  retry. Settings exposes every retained row for export or explicit deletion
  (unknown-ID delete is idempotent success). ACK, resend and pending-transfer
  discard do not own these separate library copies.

New captures use `add` (not `put`) for the dedupe index so an overlapping
worker restart cannot silently overwrite the winner and orphan its transport;
the loser compensates by deleting its orphan item and returning the winner.
Quota failures on either leg roll back both. Capture IDs require strict
hex-or-UUID; `reader-status` degrades retained-list failures to empty.
Resend creates a new capture first and only then discards the old transport;
a crash between leaves safe duplicates settled by documentId.

Verification: Chrome typecheck and all 140 Chrome tests PASS,
including migration/export/delete, malformed-row skip, duplicate capture ID,
settled-status truthfulness, quota on both legs, strict-ID rejection,
canonical-hash binding, untrusted retained/transfer rejection and
receipt cleanup with late publication.

## Android logical transfer and trust ownership

Room 8 → 9 adds `transfer_outcomes` and per-channel/per-relay `history_coverage`.
Room 9 → 10 binds each outcome to its compressed-byte hash and chunk count plus
a documentId index. It preserves all existing tables, content, keys and quotations.
Existing ACK intents seed historical commit evidence with their original receipt time;
missing source content marks that historical transfer locally deleted with a synthetic
deletion time (receivedAt*1000, documented as inferred). Staging alone never creates
an outcome or receipt. Completed outcomes do not expire with ACK retry records.
Pre-migration purged intents have no backfill (known pre-hardening replay window).

Permanent deletion of an archived article marks its completed transfers deleted in
the same transaction. Authenticated fresh wrappers for those transfers can refresh
a receipt of the historical commit but cannot stage or reconstruct the article.
Conflicting payloads (same IDs, different byte identity) are rejected, never ACKed.
Concurrent live + coverage ingestion that races on the same outcome re-reads the
winner instead of aborting the window. A new capture ID/transfer ID may store
identical text again. Content hashes are not global tombstones.
Initial receipt assembly validates bounded gzip, hashes, UTF-8 and word count without
building a full article AST; the reader prepares its bounded section on demand.

A channel lease binds work to the active stored channel, authenticated relay digest,
receiver key and pairing generation. Recently-revoked IDs are bounded (128) for
instant-cancel. Revocation cancels its running jobs and sockets; intake and ACK
commits recheck the lease, including a post-SQL re-snapshot for revoke-other-active.
In-flight remote ciphertext cannot be recalled.
Per-relay DNS guards remain in force and one failed relay does not veto healthy ones.

A controlled Android relay reproduced repeated capped-prefix reads. Resumable history
coverage now runs alongside arrival-driven foreground intake with budget 4 windows
per relay per run. Each relay has its own bounded coverage budget. Stale windows
below the rolling since are always filtered from both pending and incomplete.
Durable consumption precedes checkpoints; inclusive time windows retain the ten-day
randomized-timestamp safety margin. Caps as low as 32 cause subdivision; byte-budget
failures subdivide without claiming coverage. Saturated single-second buckets remain
explicitly incomplete rather than being claimed covered. Incomplete coverage is
health info resumed on the next periodic run, not a transport-failure retry loop.

Android host/lint/build gates PASS (120 unit tests). Device qualification for the
prior candidate is historical; finalization adds conflicting-payload rejection,
v9→v10 migration, poison-quarantine and review-serialization regressions.

## Reader, speech and export ownership

Selection offsets, projection version, source metadata and color are frozen before
leaving the native view. An application-owned ordered queue journals drafts in private
atomic files independently of Room latency, replays unfinished drafts on startup,
quarantines corrupt entries to `.bad` without blocking later selections, and removes
each only after commit. Enqueue is thread-safe from any thread; cancellation is
never recorded as storage failure; identical retry is idempotent (no revision bump
or duplicate Undo). Failed writes stay queued and surface a Retry control.
Back, swipe, menu, mode and part changes go through one transition owner that flushes
selection then progress (progress always flushed via finally), pauses audio first,
disables UI while saving (survives rotation via Saveable guard, shows “Saving…” on
double-tap instead of dropping silently), and routes Undo through the same owner.
Background and disposal finalize under the same stable persistence owner and persist
the Saveable cursor without per-scroll parcel churn. Part and semantic cursor are
saved together for recreation; stale part closures cannot offer progress for the
wrong section. Native handles and the native scroll owner remain; a stale restore
generation always clears its restoring flag. There is still an unavoidable
process-kill window before a newly frozen draft reaches the filesystem; this is not
a synchronous disk-write guarantee on the UI thread.

Speed pauses speech before route change (openTts also pauses defensively).
Background pauses speech without blocking the main thread on Room. Natural
completion and errors release focus; transient ducking does not pause. Google
speech retains its configured voice/language, refreshes the network-required
capability when engine init completes late, and the UI uses that capability.
Review Important/Next/Source/Restart share one mutex; delayed Next/Important
results cannot replace a newer quote. onStop flushes reading then progress
without blocking, ignoring scope cancellation while surfacing real failures.

Export is single-flight with preparation timing logged. A coherent Room snapshot
creates a temporary ZIP with versioned provenance/progress metadata,
retained/orphan quotations, review state and per-component SHA-256 checksums.
Corrupt articles fail closed with their documentId in the error (no partial
export). Copying reads back the destination and verifies the complete archive
hash. Cancellation deletes temp and destination attempts under NonCancellable,
rethrows cancellation, and warns when the provider refuses deletion (e.g.
MediaStore). No pairing keys are included (filenames and bodies asserted).
Export is not a restore implementation. A coherent large export holds a database
transaction and can delay writes (accepted, single-flight).

Final-artifact results and exact acceptance gaps for the prior candidate are
recorded in [HARDENING_TEST_REPORT.md](HARDENING_TEST_REPORT.md); historical beta
reports are not certification of this candidate. Finalization host gates (Chrome
140, Android 120, Swift 7; Rust unchanged and toolchain-absent here) PASS above.
Device validation of the new packages remains to be run on demand.
