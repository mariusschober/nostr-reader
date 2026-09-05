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
AUTH_SENT  -> AUTH_ERROR / AUTH_REJECTED
malformed or misbound AUTH -> PROTOCOL_ERROR
either publish path -> SOCKET_ERROR / TLS_ERROR / CLOSED
```

Only an `OK` with the exact event ID is terminal acceptance/rejection. NOTICE,
malformed, binary, duplicate, contradictory, and mismatched frames cannot forge
or revise either. A negative `OK` for the AUTH event is authentication failure,
not rejection of the original pairing/article event.
NIP-42 authenticates with the anonymous device/channel key and is bound to the
exact relay URL and challenge. Chrome independently constrains the complete
kind-22242 template before signing it. No real Nostr identity is used.

## Chrome pairing

```text
waiting_response
  -> response_validated
  -> waiting_completion
  -> complete

any active state -> expired | cancelled | superseded
```

Durable session data includes the exact request, relay set, timestamps,
attempt/error metadata, and—only until response authentication—the one-time
pairing key. That transition is durably saved without the secret before any ACK
network attempt. Recovery is re-entered on service-worker evaluation, startup,
install/update, and a one-minute alarm. Chrome queries for completion first and
republishes a freshly wrapped ACK at most once every 30 seconds until completion
or expiry. It enters completion-reading after every completed ACK send attempt,
even without a positive relay `OK`, because Android's authenticated completion
is the stronger endpoint proof. Completing, canceling, expiring, disconnecting,
or superseding a session also removes any obsolete pairing secret rather than
writing `null` or `undefined`.
Response bootstrap queries are restricted to fixed relays. After the response
authenticates Android and the relay digest, completion catch-up uses the whole
bound relay set so a custom-relay-only success cannot split endpoint state.
Every recovery also requires the local Chrome device secret to derive the
public key bound at completion. Key loss, malformed key state, or replacement
cancels an in-flight mismatched session and makes an old completed channel
unpaired; it never silently rotates beneath trusted Android state.

## Android pairing

```text
provisioning
  -> pending_response
  -> awaiting_ack
  -> ack_validated
  -> completion_pending (only when publish must retry)
  -> active

pending/provisioning -> revoked (cancellation, expiry, corrupt state, key loss)
```

`provisioning` is persisted before key wrapping. It is intentionally ignored by
sync and removed if a process died before the wrapped-key commit. An initial
response with no matching positive relay `OK` remains non-active in
`pending_response` with its next bounded retry; it does not force another scan
or create a ghost active channel. The final promotion requires a validated
Chrome ACK and is transactional with revocation of any previous active channel.
A process-wide mutex serializes UI and worker begin/process/cancel operations so
the same pending channel cannot be advanced concurrently.

The durable pairing protocol backoff starts near five seconds, doubles with
jitter, and is capped. Its one-time WorkManager owner uses Android's ten-second
exponential retry floor and returns `retry` whenever a pending pairing remains,
including when it wakes before `nextAttemptAt`. It therefore cannot silently
fall through to the 30-minute periodic catch-up before transcript expiry.
Expiry provides the hard ceiling. A single active v2 channel is enforced after
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
The service worker queries ACKs before republishing after startup. All publish,
ACK, retry, and discard mutations for one transfer share a keyed serial critical
section and reload the durable record inside it, so a late publisher cannot
resurrect content already acknowledged or discarded. The delivered receipt is
persisted before the outbox item is deleted.
Persisted channel and per-item relay lists are revalidated before network use
and before quorum calculation. An incomplete channel keeps a new capture
unbound and queued for a future authenticated re-pair; it cannot silently fall
back to defaults or make a zero-relay quorum vacuously succeed. An already
bound item with inconsistent recipient/relay state becomes a retained local
failure rather than an endless silent retry.
The same gate binds the sender identity. A never-sent item with no manifest
identity can adopt a repaired device key before first send; an already-bound
item with a different sender key becomes a retained local failure.

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
| worker terminates before response | Chrome pairing session | worker evaluation/startup/alarm | PASS in deterministic tests; 20/20 physical before-reply series NOT MEASURED (separate post-pair worker recovery 20/20 PASS) |
| browser restarts | Chrome storage + alarms | startup ACK/pairing/outbox recovery | one paired-profile restart PASS; 20/20 NOT MEASURED |
| Chrome device key is missing/corrupt/replaced | public binding marker + retained outbox | old channel fails closed; create a fresh identity and explicitly re-pair | PASS deterministic tests; deliberate live corruption not performed |
| Android dies during provisioning | Room `provisioning` row | revoke/delete on resumed begin | PASS instrumentation |
| Android dies during pairing ACK/completion | Room pending row + Keystore + retryable one-time work | `processDue()` reports retained ownership; ten-second exponential WorkManager retry | PASS source/unit/instrumentation; physical lifecycle matrix NOT MEASURED |
| Android dies after document commit/before ACK quorum | Room ACK intent + accepted-relay set | next WorkManager run resumes only missing relay attempts | PASS instrumentation/unit; physical interruption scenario NOT MEASURED |
| Chrome dies during article send | IndexedDB outbox | ACK-first startup then bounded retry | PASS tests; physical scenario NOT MEASURED |
| Android offline | relays + WorkManager | rolling 10-day query after network returns | PASS local harness; 20/20 physical NOT MEASURED |
| Android force-stop | OS stopped state | user reopens app; immediate sync scheduled | limitation, not promised |

## Fault-injection coverage

The pinned local WebSocket harness covers exact `OK=true`, explicit
`OK=false`, no/mismatched/delayed/duplicate OK, NOTICE, CLOSED, AUTH and stale
AUTH, close before/after send, malformed/binary/fragmented/oversized frames,
message-size/created-at rejection, store-then-disconnect, subscriber
disconnect, subscription restoration, contradictory matching OKs, duplicate
AUTH replies, authentication-event rejection, malicious AUTH templates,
duplicate/reordered events, and one of three relays succeeding. Chrome and
Android map original-event rejection, authentication failure, protocol error,
timeout, socket/TLS failure, and acceptance to distinct states.

## Evidence status

- **PASS:** deterministic state-transition tests and the single observed
  seven-relay physical transfer.
- **PASS:** zero `delivered` transitions without a verified endpoint ACK in the
  observed Chrome outbox and unit tests.
- **NOT MEASURED:** requested statistical gates. No rate claim is made from one
  successful end-to-end run.
