# Pairing root cause

## Result

**Status: PASS for reproduction and source-linked diagnosis.** The original
physical TCL failure was reproduced on 2026-09-04 with an instrumented build
before the protocol repair. The visible message was accurate only in the
narrow sense that zero relays returned a matching positive `OK`; it was not a
valid statement that every relay had rejected the event.

The directly observed runtime cause was a kind-21059 pairing reply whose
NIP-59-randomized `created_at` was rejected by `wss://nos.lol` as
`invalid: ephemeral event expired`. The other two configured relays ended in a
WebSocket protocol error and a connection timeout. Positive matching `OK`
count: **0 of 3**.

That relay outcome was only the first visible failure in a deterministic
causal chain. Even if a relay had accepted the response, baseline routing,
authentication, schema, UI, and state transitions independently prevented a
correct two-endpoint pairing.

## Evidence boundary

- Baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`.
- Device: physical TCL T807D, Android 16.
- Diagnostic APK SHA-256:
  `7cdf7f45e7d18efaaededf00376630bf3e827058e9fb013fd02a078cc46b232c`.
- UTC capture window: 2026-09-04T19:43:14.839Z through
  2026-09-04T19:43:25.860Z.
- Original event ID:
  `a05f7352ffb510e03a0ce387485c4a1f21974978a039954059230653b8ba9abb`
  (a public Nostr event identifier, not a secret).
- Raw retained evidence:
  `evidence/raw/baseline/tcl-original-relay-trace-2026-09-04.log` and
  `evidence/raw/baseline/tcl-original-pairing-ui-2026-09-04T19-28Z.txt`.

No QR bearer, private key, ciphertext, decrypted payload, signer response, or
complete authentication challenge is retained.

## Exact reproduction

1. Run the baseline Android build on the TCL.
2. Open the baseline Chrome pairing page and scan its `reader-pair/1` QR.
3. Android generates a channel key, persists an active channel, creates an
   ephemeral kind-21059 pair response, and sends the same wrapper to the three
   QR relays.
4. Observe the structural per-relay sequence below and the UI message:
   “No relay accepted the pairing reply. Check connection and retry.”

Redacted structural trace:

```text
wss://relay.damus.io    NORMALIZED -> CONNECTING -> SOCKET_ERROR ProtocolException
wss://nos.lol           NORMALIZED -> CONNECTING -> OPEN -> EVENT_QUEUED
                        -> EVENT_SENT -> OK_FALSE invalid: ephemeral event expired -> CLOSED
wss://relay.nostr.band  NORMALIZED -> CONNECTING -> SOCKET_ERROR SocketTimeoutException
```

The `OK_FALSE` matched the exact published event ID. No relay produced a
matching `OK_TRUE`.

## Failing state transition

Baseline attempted:

```text
Android active channel
  -> ephemeral pair-response publication
  -> any boolean relay success
  -> Android UI “Connected”
```

The first failed transition was `EVENT_SENT -> OK_TRUE`: it ended in one
explicit `OK_FALSE` and two transport failures. Baseline collapsed all three
classes to a Boolean, so its UI discarded the actual evidence.

The intended v2 transition is:

```text
provisioning -> pending_response -> awaiting_ack -> ack_validated
             -> completion_pending -> active
```

Android becomes active only after validating Chrome's signed, transcript-bound
ACK and publishing the signed completion. Chrome becomes connected only after
validating that completion.

## Source-linked causal chain

| Defect | Baseline source | Effect | Repaired source |
|---|---|---|---|
| Ephemeral response combined with randomized old timestamp | `android/app/src/main/java/com/reader/app/ui/MainActivity.kt:343-365` | The observed relay rejected the kind-21059 response as expired | v2 uses durable kind 1059 only: `PROTOCOL.md`; `PairingCoordinator.kt:57-75` |
| Gift wrap omitted recipient `p` | `chrome/src/nostr/transport.ts:64-68`; `NostrCodec.kt:100-103` | Every `#p` receiver subscription missed pairing, article, and ACK events | `chrome/src/nostr/transport.ts:180-187`; `NostrCodec.kt:129-132` |
| Bootstrap compared the real inner sender to the random outer key | `service-worker.ts:204-218`; `transport.ts:93-128` | Every legitimate response was “untrusted” | transcript validates the verified inner sender: `pairing.ts:159-194`; `service-worker.ts:522-556` |
| Pairing page never consumed or confirmed the reply | `chrome/src/ui/pairing.html:11-22` | No UI path could complete pairing | durable status loop: `chrome/src/ui/pairing.html`; recovery: `service-worker.ts:643-703` |
| Response field mismatch | Android `MainActivity.kt:343-349` emitted `channelPubkey`; Chrome `service-worker.ts:224-231` expected `androidPubkey` | A future direct hand-off still could not bind the channel | authoritative `PairResponseV2`: `pairing.ts:31-44` and shared schema |
| Android trusted too early | `MainActivity.kt:331-365` | Failed scans left ghost active channels and keys | provisional row + rollback + final transactional promotion: `PairingCoordinator.kt:106-191,292-321` |
| Relay outcomes collapsed to Boolean | `NostrCodec.kt:175-201` | Timeout, socket error, and negative `OK` became one misleading message | structural state/result model: `NostrCodec.kt:216-491`; UI mapping: `PairingCoordinator.kt:78-98` |
| Chrome counted an array rather than its promises | `service-worker.ts:143-155`; `transport.ts:155-163` | False relay success and premature pool close | one URL/one promise with exact per-relay settlement: `transport.ts:388-421` |
| Chrome zlib vs Android gzip | `service-worker.ts:4,102`; `TransferManager.kt:18,93` | Article body could not decode after pairing | normative gzip and cross-runtime vectors: `PROTOCOL.md`; `protocol/codec.ts`; `ReaderGzip.kt` |

## Independent event validation

The diagnostic event's structure and outcome were correlated by exact event
ID. Source inspection independently establishes that the baseline response was
kind 21059, randomized its timestamp by up to two days, and lacked the
recipient routing tag. The relay's matching negative `OK` therefore explains
the observed zero-acceptance transition without guessing about NIP-42, payment,
or listener state.

## Why earlier tests missed it

- Baseline unit tests round-tripped sender and receiver implementations that
  shared the same missing-`p` mistake; no real `#p` relay filter sat between
  them.
- The committed smoke harness raced on the returned promise array and never
  performed read-back, so it could report success before any matching `OK`.
- The gzip fixture tested a prebuilt gzip file, not Chrome's actual `deflate()`
  output.
- Pairing tests stopped before authenticated two-endpoint completion.
- MV3 service-worker termination and physical QR-to-ACK lifecycle were absent.

The added baseline regression run retained in
`evidence/raw/baseline/regressions-chrome-fail.log` fails on gzip framing,
recipient routing, expiry, and promise-array accounting. The Android baseline
run in `regressions-android-fail.log` fails routing, expiry, NIP-01 canonical
escaping, BIP-340, and private-network QR checks. Those same gates pass after
the repair.

## Contributing defects

NIP-42 was absent, relay sets could split, expiry was not enforced on receive,
the rumor lacked its canonical ID, secret-bearing Chrome storage was exposed
to content scripts by default, and pairing/ACK listeners were not durable
across MV3 restarts. None is needed to explain the exact negative `OK`, but all
could independently break or weaken a nominally accepted pairing.

## Ruled-out or unproven hypotheses

- **A universal relay outage: ruled out.** One relay opened and returned a
  matching protocol-level negative `OK`.
- **Every relay explicitly rejected: ruled out.** Only one did; the other two
  failed before a matching `OK`.
- **NIP-42 caused the captured failure: not observed.** No AUTH challenge or
  auth-required response appeared in the trace.
- **Payment or real identity required: not observed.** No such policy response
  appeared, and the repair never introduces a real identity.
- **Bad QR camera decode: ruled out for this run.** Android parsed the QR and
  reached relay publication.
- **TLS failure on every relay: ruled out.** `nos.lol` reached OPEN and returned
  a matching `OK_FALSE`.

## Repair verification

- **PASS:** original UI failure reproduced on the physical TCL.
- **PASS:** exact negative-OK and transport outcomes retained separately.
- **PASS:** source-linked runtime cause demonstrated.
- **PASS:** baseline regression gates fail and repaired gates pass.
- **PASS:** one fresh v2 Chrome/TCL pairing completed with six defaults plus
  one custom relay, and both endpoints showed the same authenticated channel.
- **PASS:** one synthetic article was stored exactly once and an authenticated
  Android ACK cleared Chrome's outbox.
- **NOT MEASURED:** the required 20/20 pairing and 50/50 delivery reliability
  quotas; see `TCL_PHYSICAL_VALIDATION.md`.
