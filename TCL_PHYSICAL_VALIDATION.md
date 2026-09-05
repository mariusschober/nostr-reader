# TCL physical validation

## Overall status

**Physical TCL validation: NOT MEASURED** against the complete required matrix
and statistical gates. Important individual scenarios passed, including the
original-failure reproduction and one complete repaired pairing/delivery/ACK
flow. The missing scenarios and counts are not inferred from those successes.

## Environment

| Item | Evidence |
|---|---|
| Device | TCL T807D (`T807D_EEA`, codename `Frida`) |
| Android | 16; security patch 2026-07-05 |
| Build fingerprint | `TCL/T807D_EEA/Frida:16/BP2A.250605.031.A3/AMD9:user/release-keys` |
| Device idle/battery | device idle enabled; Reader not recorded in the user allowlist; active/charging during baseline capture |
| Device network | connected during captured runs; Wi-Fi/cellular identity not retained |
| Desktop | macOS 26.6.2 arm64, build 25G83 |
| Browser | Google Chrome for Testing 151.0.7922.34; Brave not used for final testing |
| Protocol | `reader-pair/2`, `reader/2`, durable kind 1059 |
| Relay set | six defaults plus custom `wss://relay.mostr.pub`; two-relay write quorum |
| Physical UTC window | original failure 2026-09-04T19:43:14.839Z–19:43:25.860Z; repaired flow 2026-09-05 |
| Earlier E2E APK/ZIP hashes | exact hashes for the original live pair/article were not retained |
| ACK follow-up APK | SHA-256 `7da2e042595b454d29910ad1322bed3d7eb1a447e54ea7eb671a1d12ddaadbac`; installed base APK matched on TCL and S23 |

The exact follow-up artifact was physically installed and its migration/ACK
behavior was exercised, but no new article was sent. The “exact final artifact
physically tested end-to-end with a fresh article” item therefore remains **NOT
MEASURED**, even though installed bytes, pairing persistence, and catch-up were
revalidated.

## Original failure

| Scenario | Expected | Actual | Count | Evidence | Status |
|---|---|---|---:|---|---|
| Scan baseline QR | diagnostic response outcomes | UI error plus per-relay structural results | 1/1 | `evidence/raw/baseline/tcl-original-*` | PASS |
| Matching positive OK | at least one | 0/3 | 0/1 | exact event ID trace | FAIL, reproduced |
| Error truth | distinguish reject/timeout/socket | one `OK_FALSE`, one socket protocol error, one timeout | 1/1 | original trace | PASS after instrumentation; baseline UI FAIL |
| Source-linked runtime cause | matching evidence | nos.lol rejected old kind-21059 as `invalid: ephemeral event expired` | 1/1 | trace + baseline source | PASS |

## Repaired end-to-end observation

| Scenario | Expected | Actual | Count | Evidence | Status |
|---|---|---|---:|---|---|
| Chrome QR readiness | QR only after durable response path/readiness | QR shown; user scanned successfully | 1/1 | Chrome UI/session state | PASS |
| TCL validates QR and shows review | exact v2 schema, fingerprint, seven relays | review shown; six defaults + Mostr visible | 1/1 | physical UI observation | PASS |
| User consents | no network trust before Connect | user completed Connect | 1/1 | UI/state transition | PASS |
| Pair response publication | at least one matching positive relay outcome | handshake advanced | 1/1 aggregate | connected transcript state | PASS |
| Chrome receives/decrypts/authenticates response | exact inner sender/transcript | Chrome advanced to ACK/wait completion | 1/1 | Chrome session state | PASS |
| Chrome pairing ACK | signed/bound ACK | TCL advanced beyond awaiting ACK | 1/1 | both-endpoint completion | PASS |
| TCL shows connected only after ACK | no relay-only success | Android active channel count exactly 1 | 1/1 | DB/log/UI state | PASS |
| Chrome validates completion | connected only after Android completion | `paired=true`, seven exact relays | 1/1 | `chrome-paired-status-*` | PASS |
| Synthetic article | encrypted article arrives | `https://chatgpt.com/robots.txt` displayed | 1/1 | physical UI | PASS |
| Exactly-once document effect | one row despite relay overlap | one visible document; later copies `duplicate` | 1/1 logical transfer | TCL log/UI | PASS |
| Endpoint ACK | bound ACK after durable presence | ACK publication `OK_TRUE` on all seven relays | 1/1 ACK batch | `tcl-seven-relay-transfer-ack-*` | PASS |
| Chrome outbox clearing | only after verified ACK | pending 1→0, delivered 0→1 | 1/1 | Chrome status | PASS |
| Extension reload/restart after connection | pairing/outbox persists | repeated reload and same-profile restart retained paired 7-relay state | 1 observed profile | Chrome status | PASS |
| App force-stop/relaunch after connection | one active channel, catch-up safe | one active channel; duplicate doc not reinserted | 1 observed restart | TCL logs | PASS |
| Room v4 -> v5 preserved-state migration | no data/trust loss; bounded recovery | cold launch retained one active channel and seven relays | 1/1 | `exact-artifact-physical-followup-2026-09-05.txt` | PASS |
| Repeated catch-up after recovery ACK | retained wrappers do not restart completed ACK | first v5 run sent one recovery batch (7 `OK_TRUE`); immediately serialized second run read the events and sent no ACK | 1/1 repeat | same evidence | PASS for observation |

## Required physical scenario matrix

| Required scenario | Executed count | Result |
|---|---:|---|
| clean extension and app state | 0 | NOT MEASURED |
| QR only after Chrome readiness | 1 | PASS |
| TCL scans and validates QR | 1 | PASS |
| each pairing relay individually classified | aggregate only | NOT MEASURED per relay |
| at least one matching positive OK | 1 flow | PASS |
| Chrome receives/decrypts/authenticates response | 1 | PASS |
| Chrome sends pairing ACK | 1 | PASS |
| TCL validates ACK before connected | 1 | PASS |
| synthetic article send | 1 | PASS |
| Android inserts once | 1 | PASS |
| Android ACK clears Chrome outbox | 1 | PASS |
| duplicate wrapper -> no duplicate doc or repeated completed ACK | 1 logical transfer across seven relays + 1 immediate v5 repeat | PASS for observations |
| Android offline then catch-up | 0 | NOT MEASURED |
| terminate Chrome worker before reply | 0 physical | NOT MEASURED |
| restart Chrome before reply | 0 physical | NOT MEASURED |
| background/screen-off Android | 0 controlled runs | NOT MEASURED |
| network switch | 0 | NOT MEASURED |
| one relay unreachable + one explicit rejection + one success | 0 physical; local harness PASS | NOT MEASURED physically |
| expired QR | 0 camera-flow runs; validator PASS in physical instrumentation | NOT MEASURED through camera UI |
| replayed QR | 0 physical; unit logic PASS | NOT MEASURED physically |
| tampered QR | 0 camera-flow runs; validator PASS in physical instrumentation | NOT MEASURED through camera UI |
| prohibited private-network QR | 0 camera-flow runs; DNS boundary PASS in physical instrumentation | NOT MEASURED through camera UI |
| oversized input | 0 camera-flow runs; unit boundary PASS | NOT MEASURED through camera UI |
| unpair | 0 controlled current-flow run | NOT MEASURED |
| old credentials fail | 0 | NOT MEASURED |
| re-pair/new credentials work | one fresh v2 pairing, but not full old-key sequence | NOT MEASURED for required sequence |

## Reliability gates

| Gate | Required | Executed | Status |
|---|---:|---:|---|
| clean pairings | 20/20 | 1 observed v2 pairing, not a 20-run clean-state series | NOT MEASURED |
| worker-termination pairings | 20/20 | 0/20 physical | NOT MEASURED |
| Chrome-restart recovery pairings | 20/20 | 0/20 before-reply; one post-pair restart only | NOT MEASURED |
| normal small deliveries, zero duplicates, verified ACKs | 50/50 | 1/50 | NOT MEASURED |
| offline -> online within retention | 20/20 | 0/20 | NOT MEASURED |
| replay/duplicate attempts safely deduped | 20/20 | 1 logical transfer observed | NOT MEASURED |
| secret-bearing retained logs | zero | zero found in reviewed retained logs/source patterns | PASS |
| “relay rejected” without matching negative OK | zero | zero in captured structural traces/tests | PASS within executed evidence |
| “delivered” without endpoint ACK | zero | zero; one delivered item had verified ACK | PASS within executed evidence |

## Instrumentation and UI evidence

The exact APK/test-APK pair passed all seven instrumentation cases on the physical
TCL, Samsung S23, and API-26 emulator. The cases cover v3 -> v5 database
migration, missing-key
revocation, sole-active replacement, Chrome/normative gzip decode, conflicting
sender/duplicate safety, and reordered/duplicate chunk atomic commit with a
durable bound ACK intent and terminal replay behavior. The seventh case runs
the strict hostile pairing-code and prohibited-DNS boundary in the Android
runtime without contacting those destinations. The current API-26 run
is retained in `evidence/raw/final/exact-artifact-api26-2026-09-05.txt`; the
earlier screenshots remain supplemental UI evidence.

Emulator screenshots:

- `evidence/reader-emulator-api26.png`
- `evidence/reader-emulator-relays-api26.png`
- `evidence/reader-emulator-pairing-api26.png`

The emulator is secondary evidence only; it does not replace missing physical
scenario counts.
