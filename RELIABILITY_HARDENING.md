# Reliability and data integrity hardening

Work starts from `1e55b7d4021637520402cb1773ac4e1ffd3b43cf` on
`codex/reliability-data-integrity`. This document describes the implemented candidate.
Exact artifact results and remaining acceptance gaps are in HARDENING_TEST_REPORT.md.

## Chrome capture ownership

IndexedDB version 3 separates three owners:

- `captureIds`: capture ID, transfer ID and creation time only. Repeating a capture
  ID returns its original result even after delivery or discard. A deliberate new
  capture uses a new capture ID and transfer ID, including for identical text.
- `items`: the transport payload, retained while pending/failed. Authenticated
  receipt cleanup and explicit pending-transfer discard delete this payload.
- `captures`: the recovery library retained by earlier versions. Migration preserves
  these rows exactly and creates their lightweight ID records atomically. Settings
  exposes every retained row for export or explicit deletion. ACK, resend and
  pending-transfer discard do not own these separate library copies.

New captures no longer create an additional plaintext library copy. Quota failures
roll back payload and ID registration together. An interrupted migration leaves
version 2 intact. No automatic expiration is applied to legacy library content.

Verification: Chrome typecheck and all 136 Chrome tests PASS,
including migration/export/delete, duplicate capture ID, quota failure and
receipt cleanup with late publication. The final packaged extension also loads without browser-reported errors in an isolated
Chrome for Testing profile; foreground results are recorded in the test report.

## Android logical transfer and trust ownership

Room 8 → 9 adds `transfer_outcomes` and per-channel/per-relay `history_coverage`.
It preserves all existing tables, content, keys and quotations. Existing ACK intents
seed historical commit evidence with their original receipt time; missing source
content marks that historical transfer locally deleted. Staging alone never creates
an outcome or receipt. Completed outcomes do not expire with ACK retry records.

Permanent article deletion marks its completed transfers deleted in the same
transaction. Authenticated fresh wrappers for those transfers can refresh a receipt
of the historical commit but cannot stage or reconstruct the article. A new capture
ID/transfer ID may store identical text again. Content hashes are not global tombstones.
Initial receipt assembly validates bounded gzip, hashes, UTF-8 and word count without
building a full article AST; the reader prepares its bounded section on demand.

A channel lease binds work to the active stored channel, authenticated relay digest,
receiver key and pairing generation. Revocation cancels its running jobs and sockets;
intake and ACK commits recheck the lease. In-flight remote ciphertext cannot be recalled.
Per-relay DNS guards remain in force and one failed relay does not veto healthy ones.

A controlled Android relay reproduced repeated capped-prefix reads. Resumable history
coverage now runs alongside arrival-driven foreground intake. Each relay has its own
bounded coverage budget. Durable consumption precedes checkpoints; inclusive time
windows retain the ten-day randomized-timestamp safety margin. Caps as low as 32 cause
subdivision; byte-budget failures subdivide without claiming coverage. Saturated
single-second buckets remain explicitly incomplete rather than being claimed covered.

Full Android host/lint/build gates PASS for executable source 7dbb869. Device
qualification includes intake/ACK revocation and permanent-delete/fresh-wrapper replay;
see the test report for the exact final-artifact results and scope.

## Reader, speech and export ownership

Selection offsets, projection version, source metadata and color are frozen before
leaving the native view. An application-owned ordered queue journals drafts in private
atomic files independently of Room latency, replays unfinished drafts on startup and
removes each only after commit. Failed writes stay queued and surface a Retry control.
Back, swipe, mode and part changes wait for selection/progress persistence; background
and disposal finalize under the same stable persistence owner. Part and semantic cursor
are saved together for recreation. Native handles and the native scroll owner remain.
There is still an unavoidable process-kill window before a newly frozen draft reaches
the filesystem; this is not a synchronous disk-write guarantee on the UI thread.

Speed pauses speech before route change. Background pauses speech without blocking the
main thread on Room. Natural completion and errors release focus. Google speech retains
its configured voice/language, and the UI uses the engine's network-required capability.
A delayed Important result can no longer replace a newer review quote.

Export uses a user-selected SAF destination. A coherent Room snapshot creates a temporary
ZIP with versioned provenance/progress metadata, retained/orphan quotations, review state
and per-component SHA-256 checksums. Preparation validates every component; copying reads
back the destination and verifies the complete archive hash. Cancellation/failure removes
the temporary file and attempts to delete the incomplete destination, with an explicit
warning if that provider refuses. No pairing keys are included. Export is not a restore
implementation. A coherent large export holds a database transaction and can delay writes.

Final-artifact results and exact acceptance gaps are recorded in
[HARDENING_TEST_REPORT.md](HARDENING_TEST_REPORT.md); historical beta reports are not
certification of this candidate.
