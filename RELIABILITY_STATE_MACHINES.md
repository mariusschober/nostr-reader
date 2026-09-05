# Reliability state machines

## Invariant

Transport acceptance and endpoint completion are deliberately separate.
`OK_TRUE` can advance a relay state, but only an authenticated endpoint message
can advance pairing to connected or delivery to delivered.

## Relay publication

```text
NORMALIZED -> CONNECTING -> OPEN -> EVENT_QUEUED -> EVENT_SENT
                         \-> AUTH_CHALLENGE -> AUTH_SENT -> EVENT_SENT

EVENT_SENT -> OK_TRUE
           -> OK_FALSE(reason-prefix)
           -> NO_OK_TIMEOUT
           -> SOCKET_ERROR
           -> TLS_ERROR
           -> CLOSED
```

Only an `OK` with the exact event ID is terminal acceptance/rejection. NOTICE,
malformed, binary, duplicate, and mismatched frames cannot forge either.
NIP-42 authenticates with the anonymous device/channel key and is bound to the
exact relay URL and challenge. No real Nostr identity is used.

## Chrome pairing

```text
waiting_response
  -> response_validated
  -> waiting_completion
  -> complete

any active state -> expired | cancelled | superseded
```

Durable session data includes the exact request, relay set, timestamps,
attempt/error metadata, and—only while needed—the one-time pairing key. Recovery
is re-entered on service-worker evaluation, startup, install/update, and a
one-minute alarm. Completing, canceling, expiring, disconnecting, or superseding
a session removes its pairing secret rather than writing `null` or `undefined`.

## Android pairing

```text
provisioning
  -> pending_response
  -> awaiting_ack
  -> ack_validated
  -> completion_pending (only when publish must retry)
  -> active

pending/provisioning -> revoked (failure, cancellation, expiry, key loss)
```

`provisioning` is persisted before key wrapping. It is intentionally ignored by
sync and removed if a process died before the wrapped-key commit. A failed
initial response revokes the row and deletes the Keystore material. The final
promotion requires a validated Chrome ACK and is transactional with revocation
of any previous active channel.

Backoff starts near five seconds, doubles with jitter, and is capped. Expiry
provides the hard ceiling. A single active v2 channel is enforced after
replacement.

## Chrome delivery

```text
queued
  -> relay_accepted
  -> awaiting_device
  -> delivered (verified ACK; outbox deleted)

queued | relay_accepted | awaiting_device -> retryable
retryable -> failed (168 attempts or 7 days; payload retained)
failed -> explicit user discard | a later valid ACK
```

The full outgoing intent is in IndexedDB before network activity. Stable
`transferId`, `documentId`, `manifestId`, and compressed hash survive retries;
each Nostr envelope is freshly wrapped. Backoff has jitter and a one-hour cap.
The service worker queries ACKs before republishing after startup so a late
publisher cannot resurrect content already acknowledged.

## Android receive/ACK

```text
authenticated manifest/chunk
  -> bounded staging
  -> complete bound set
  -> hash + gzip + UTF-8 + canonical checks
  -> atomic stored | duplicate + processed-wrapper row + ACK intent
  -> ACK pending
  -> quorum complete | bounded retry | failed/expired
```

Wrong sender, recipient, version, kind, signature, MAC, expiry, hash, size,
index, or field set is a no-op/failure before document mutation. Event IDs are
deduplicated across relays within a worker run and persisted after every
authenticated manifest/chunk mutation; `documentId` provides the persistent
exactly-once document effect. Document commit, processed-wrapper recording, and
ACK-intent creation share one Room transaction, so process death cannot leave a
committed document without a recoverable ACK path.

Each ACK is built only from a durable `stored` or `duplicate` intent. All
configured relays are attempted once, each matching positive result is saved,
and two accepted relays complete the ACK lifecycle. Below quorum, WorkManager
retries with deterministic jitter/backoff until 168 attempts or transfer
expiry. A delayed wrapper or Chrome retry for that immutable transfer cannot
reset a completed quorum or exhausted ceiling. A process-local mutex serializes
periodic and foreground-triggered workers.

## Restart ownership

| Failure point | Durable owner | Recovery | Status |
|---|---|---|---|
| pairing tab closes | Chrome pairing session | worker/alarm query of durable kind 1059 | PASS in design/unit; full quota NOT MEASURED |
| worker terminates before response | Chrome pairing session | worker evaluation/startup/alarm | PASS in deterministic tests; 20/20 physical NOT MEASURED |
| browser restarts | Chrome storage + alarms | startup ACK/pairing/outbox recovery | one paired-profile restart PASS; 20/20 NOT MEASURED |
| Android dies during provisioning | Room `provisioning` row | revoke/delete on resumed begin | PASS instrumentation |
| Android dies during pairing ACK/completion | Room pending row + Keystore | WorkManager `processDue()` | PASS source/unit; physical lifecycle matrix NOT MEASURED |
| Android dies after document commit/before ACK quorum | Room ACK intent + accepted-relay set | next WorkManager run resumes only missing relay attempts | PASS instrumentation/unit; physical interruption scenario NOT MEASURED |
| Chrome dies during article send | IndexedDB outbox | ACK-first startup then bounded retry | PASS tests; physical scenario NOT MEASURED |
| Android offline | relays + WorkManager | rolling 10-day query after network returns | PASS local harness; 20/20 physical NOT MEASURED |
| Android force-stop | OS stopped state | user reopens app; immediate sync scheduled | limitation, not promised |

## Fault-injection coverage

The pinned local WebSocket harness covers exact `OK=true`, explicit
`OK=false`, no/mismatched/delayed/duplicate OK, NOTICE, CLOSED, AUTH and stale
AUTH, close before/after send, malformed/binary/fragmented/oversized frames,
message-size/created-at rejection, store-then-disconnect, subscriber
disconnect, subscription restoration, duplicate/reordered events, and one of
three relays succeeding. Chrome and Android map rejection, timeout, socket/TLS,
and acceptance to distinct states.

## Evidence status

- **PASS:** deterministic state-transition tests and the single observed
  seven-relay physical transfer.
- **PASS:** zero `delivered` transitions without a verified endpoint ACK in the
  observed Chrome outbox and unit tests.
- **NOT MEASURED:** requested statistical gates. No rate claim is made from one
  successful end-to-end run.
