# Beta architecture decisions

## 2026-09-08 — continuation and evidence

Continue the clean, fetched `codex/reader-0.9-beta` branch from `fea0bb6`, which
descends from the audited repair. Preserve owner installation, identities,
paired profile and the newer feedback repair. Use the connected TCL and the
existing `com.reader.app.qa` variant for isolated synthetic fault campaigns.

Test core recovery before implementing reader features. Keep protocol
`reader/2`, two-relay publication policy, NIP-44/59, native Android and MV3.
Do not treat earlier instrumentation or source-shape checks as candidate proof.
Build extension output away from the live paired extension directory until an
explicit controlled candidate update. Final artifact provenance distinguishes
the tested source commit from subsequent documentation-only commits.

## 2026-09-08 — bounded independent transport progress

One Chrome publisher owns a transfer, while each of its at-most-eight relays
advances independently through fragments. A redundant relay's slow manifest
must not delay healthy relays' chunks. Accepted coverage still expires after
15 minutes, and the two-relay policy is unchanged. ACK scans consume events
without retaining all pages. Listing/scheduling uses outbox summaries.

Android retains one serialized sync session, but arrival intake and a conflated
ACK dispatcher are separate children. An ACK's attempt reservation precedes
network work; each returned relay result merges into the current durable
generation. Cancellation closes sockets. DNS has two workers, eight queued
lookups and a three-second per-lookup wait; each production connection retains
the public-address guard. Staging budgets reject atomically and never evict an
undelivered capture to disguise storage pressure.

Primary references checked on 8 September 2026: [Chrome alarms](https://developer.chrome.com/docs/extensions/reference/api/alarms)
(page updated 13 August 2026), [MV3 lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle),
and current official [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md),
[40](https://github.com/nostr-protocol/nips/blob/master/40.md),
[42](https://github.com/nostr-protocol/nips/blob/master/42.md),
[44](https://github.com/nostr-protocol/nips/blob/master/44.md),
[59](https://github.com/nostr-protocol/nips/blob/master/59.md).
Chrome minimum remains 120. Persisted work restores alarms without relying on
Chrome-150-only persistence flags. NIP-01 permits incomplete relay responses;
scan completion never promises exhaustive storage or permanent retention.
