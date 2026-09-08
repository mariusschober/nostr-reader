# Reliability and data integrity hardening

Work starts from `1e55b7d4021637520402cb1773ac4e1ffd3b43cf` on
`codex/reliability-data-integrity`. This document tracks an unfinished implementation
cycle; earlier artifact results do not certify these changes.

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

Verification so far: Chrome typecheck and 16 production worker tests PASS,
including migration/export/delete, duplicate capture ID, quota failure and
receipt cleanup with late publication. Final packages and real-browser acceptance
remain NOT MEASURED.

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

Intermediate verification: six TCL transfer tests PASS, including paused-intake
revocation and permanent-delete/fresh-wrapper replay. Full Android host/lint/build
gates passed before the final follow-ups. Final artifact qualification is pending.
