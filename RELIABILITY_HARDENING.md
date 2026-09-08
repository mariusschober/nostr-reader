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
