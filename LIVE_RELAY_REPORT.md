# Live public-relay report

## Result and boundary

**Live public-relay verification: PASS** for dated disposable-key
interoperability. This status means the repaired harness obtained real matching
publication results and exact read-back on more than three public relays from
multiple independently identified operators. It does not mean permanent relay
health, and it is not the unexecuted physical reliability quota.

All written events used freshly generated test keys and synthetic non-sensitive
payloads. No owner Nostr identity, signer identity, article, private URL,
credential, or production key was used. No new article was transmitted during
the final documentation/security pass.

## Dated probes

On 2026-09-05, the repaired live harness performed two consecutive kind-1059
rounds against the six fixed defaults. Each round required:

1. TLS/WebSocket connection;
2. a syntactically valid NIP-59 wrapper with a disposable recipient;
3. the relay-specific publication promise to settle with the exact matching
   positive `OK`;
4. a `#p` subscription read-back of that exact event ID;
5. re-query/retention evidence within the test window.

All six defaults met that criterion in both rounds. `wss://relay.mostr.pub`
also passed a dedicated probe and was then included as the custom seventh relay
in the successful physical pairing/article flow.

NIP-11 was fetched again on 2026-09-05 with
`Accept: application/nostr+json`; all seven endpoints returned valid JSON.

## Relay matrix

`AUTH none` means no challenge was observed; it does not claim that the relay
never uses NIP-42. `Aggregate` means the end-to-end receiver was proven but the
retained trace cannot attribute the first endpoint receipt to one particular
relay.

| Relay | Operator evidence | NIP-11 | Connect | AUTH | EVENT | Matching OK | Read-back | Chrome receipt | Device ACK | Latency | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `wss://nos.lol` | name `nos.lol`, pubkey `c5fade…47cd9`, strfry | valid; max message 131072; NIPs 1/11/40 | PASS | none observed | two probe writes PASS; physical flow PASS | PASS exact | PASS exact | aggregate pairing/ACK PASS; per-relay source NOT MEASURED | ACK publish `OK_TRUE` observed | not retained | PASS |
| `wss://relay.primal.net` | `Primal Public Relay`, contact `primal.net`, pubkey `dd9b98…5f302` | valid; max 1000000; NIPs 1/11/40 | PASS | none observed | two probe writes PASS; physical flow PASS | PASS exact | PASS exact | aggregate PASS; per-relay source NOT MEASURED | `OK_TRUE` | not retained | PASS |
| `wss://relay.nostr.net` | contact `nostr.net`, operator text `aljaz@nostr.si`, pubkey `efe5d1…51981` | valid; max 262144; NIPs 1/11/40 | PASS | none observed | two probe writes PASS; physical flow PASS | PASS exact | PASS exact | aggregate PASS; per-relay source NOT MEASURED | `OK_TRUE` | not retained | PASS |
| `wss://nostr.oxtr.dev` | `0xtr relay`, distinct contact and pubkey `b2d670…f9d4a` | valid; max 131072; NIPs 1/11/40 | PASS | none observed | two probe writes PASS; physical flow PASS | PASS exact | PASS exact | aggregate PASS; per-relay source NOT MEASURED | `OK_TRUE` | not retained | PASS |
| `wss://offchain.pub` | name/contact `offchain.pub`, pubkey `ece331…a5d59b` | valid; max 131072; NIPs 1/11/40 | PASS | none observed | two probe writes PASS; physical flow PASS | PASS exact | PASS exact | aggregate PASS; per-relay source NOT MEASURED | `OK_TRUE` | not retained | PASS |
| `wss://nostr-pub.wellorder.net` | `wellorder-relay`, distinct pubkey `35d26e…fd50f`, nostr-rs-relay | valid; payment/restricted writes false; NIPs 1/11/40 | PASS | none observed | two probe writes PASS; physical flow PASS | PASS exact | PASS exact | aggregate PASS; per-relay source NOT MEASURED | `OK_TRUE` | not retained | PASS |
| `wss://relay.mostr.pub` | `Mostr relay`, ActivityPub bridge, strfry | valid; max 131072; NIPs 1/11/40 | PASS | none observed | dedicated probe PASS; physical custom-relay flow PASS | PASS exact | PASS exact | aggregate PASS; per-relay source NOT MEASURED | `OK_TRUE` | not retained | PASS |

The NIP-11 names, contacts, public keys, and software records demonstrate at
least Primal, nostr.net, 0xtr, offchain, wellorder, and Mostr as independently
identified endpoints/operators; the acceptance gate requires only two.

## Physical aggregate evidence

The fresh TCL pairing authenticated the ordered six-default-plus-Mostr relay
set. On restart/catch-up, Android reported one active channel with seven relays,
received the synthetic transfer from every configured relay, kept one document,
and obtained a matching positive ACK publication result from all seven. Chrome
then changed the outbox from pending 1 to 0 and delivered 0 to 1 only after
authenticating the Android ACK.

Retained evidence:

- `evidence/raw/final/tcl-seven-relay-transfer-ack-2026-09-05.txt`
- `evidence/raw/final/chrome-paired-status-2026-09-05.txt`

## Original failure contrast

The baseline physical trace is not relabeled as a relay outage. It recorded:

- `relay.damus.io`: socket protocol error;
- `nos.lol`: exact `OK_FALSE`, `invalid: ephemeral event expired`;
- `relay.nostr.band`: socket timeout;
- positive matching OK: 0/3.

The repaired default list and protocol were chosen only after fixing the local
wire/state bugs and running actual matching-OK/read-back probes. More relays
improve availability; they do not substitute for endpoint ACKs.

## Unmeasured fields

- Per-relay end-to-end receipt attribution and publication/read-back latency
  were not retained in a stable report: **NOT MEASURED**.
- Public NIP-42 challenge handling was not exercised because none appeared:
  **NOT MEASURED**.
- Long-term retention, future policy, uptime, and future operator identity are
  not guaranteed: **NOT MEASURED** beyond the dated windows.
