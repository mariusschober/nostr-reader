# Reader v2 repair test report

Date: 2026-09-05

Baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`

Branch: `fix/pairing-delivery-hardening-982920b4`

## 1. Executive result

**Overall status: NOT MEASURED for the complete final acceptance gate.**

The deterministic protocol, crypto, relay-accounting, storage, lifecycle, and
codec blockers are repaired and their executable gates pass. The original TCL
failure has a matching relay trace and source-linked cause. One real v2
Chrome/TCL pairing with seven relays completed; one synthetic article was
stored once; Android's authenticated ACK cleared Chrome's outbox.

The ACK-durability follow-up was installed byte-for-byte on both physical
phones and the API-26 emulator. The final state-race/authentication hardening
adds a seventh hostile-QR runtime case; all seven cases pass in all three
environments. The preserved TCL then
migrated to Room v5, emitted one expected historical recovery ACK batch, and an
immediately serialized second catch-up read the retained events without another
ACK or document mutation.

The full task is not labeled PASS because the requested 20/20 and 50/50
physical reliability series, several lifecycle/network scenarios, and a second
public transfer of the exact final artifact were not executed. See
`TCL_PHYSICAL_VALIDATION.md` and `KNOWN_LIMITATIONS.md`.

Root-cause chain:

```text
old kind-21059 timestamp -> matching relay OK_FALSE “ephemeral event expired”
  + missing recipient p tag -> Chrome #p subscription cannot receive
  + wrong outer-key sender comparison -> valid response rejected
  + no UI completion consumer + field mismatch -> no Chrome promotion path
  + Android early trust -> ghost channel risk
  + false Chrome promise-array accounting -> false relay success
  + zlib/gzip mismatch -> article decode failure
```

The final implementation and documentation commit IDs are recorded by Git and
the generated `artifacts/ARTIFACTS.json`. They are not embedded into their own
commit because a commit cannot contain its own future hash.

## 2. Findings resolved

Each baseline location is relative to immutable commit `982920b4…`; fix
locations are relative to this branch. Rows explicitly marked “repair
follow-up” were defects found during final review of the repaired path rather
than claims about an immutable baseline line.

| Severity/finding | Original file/lines | Evidence | Fix file/lines | Regression/evidence | Status |
|---|---|---|---|---|---|
| CRITICAL C1 missing wrapper `p` | Chrome `transport.ts:64-68`; Android `NostrCodec.kt:100-103` | baseline `#p` subscriptions cannot match | Chrome `transport.ts:180-187`; Android `NostrCodec.kt:129-132` | Chrome transport + Android GiftWrap; baseline logs fail/current pass | PASS |
| CRITICAL C2 inner sender compared to random outer key | Chrome `service-worker.ts:204-218`, `transport.ts:93-128` | valid NIP-59 sender always differs | `pairing.ts:159-194`; `service-worker.ts:522-556` | pairing transcript tests, live pair | PASS |
| CRITICAL C3 pairing UI never completes | `chrome/src/ui/pairing.html:11-22` | QR-only UI, no response consumer | pairing UI durable status loop; worker `643-703` | UI/CFT pairing observed | PASS |
| CRITICAL C4 `channelPubkey`/`androidPubkey` mismatch | Android `MainActivity.kt:343-349`; Chrome `service-worker.ts:224-231` | incompatible fields, no shared response schema | `pairing.ts:31-44`; `shared/schemas/pair-response.json` | TS/Kotlin pairing tests | PASS |
| CRITICAL C5 false Chrome publication accounting | Chrome `service-worker.ts:143-155`; `transport.ts:155-163` | `await Promise[]` resolves immediately | `transport.ts:388-421` | exact nostr-tools return-shape and fault-relay tests | PASS |
| CRITICAL C6 smoke harness false success/no read-back | `chrome/scripts/smoke-relay.mjs:11,25` | historical claim invalid | repaired live script per-relay settlement + exact query | two six-relay rounds + Mostr probe | PASS |
| CRITICAL C7 zlib/gzip drift | Chrome `service-worker.ts:4,102`; Android `TransferManager.kt:18,93` | cross-runtime framing mismatch | `protocol/codec.ts`; `ReaderGzip.kt`; Rust/Swift mirrors | `codec-v2.json`, TS/Kotlin/Swift/Rust tests | PASS |
| HIGH H1 overbroad zero-relay message/fallback | Android `MainActivity.kt:343-365`; `NostrCodec.kt:175-201` | original trace has one negative OK plus two transport errors | durable 1059; structural relay result model `NostrCodec.kt:216-491`; user messages `PairingCoordinator.kt:78-98` | original TCL trace + fault tests | PASS |
| HIGH H2 ghost active channel | Android `MainActivity.kt:331-365` | active row/key existed before send | `PairingCoordinator.kt:106-191,292-321` | DB migration/loss/replacement instrumentation | PASS |
| HIGH H3 Android “Connected” after relay only | `MainActivity.kt:259-261` | no Chrome ACK existed | UI waits for DB `active`; coordinator validates ACK/completion | one physical two-endpoint pair | PASS |
| HIGH H4 unsafe QR/network policy | `PairingScreen.kt:307-316` | permissive relay targets/times/fields | `PairingProtocol.kt:47-267`; consent screen | private DNS, malformed, exact-field tests/emulator | PASS |
| HIGH H5 content-script storage exposure | Chrome `service-worker.ts:47-53,199` | default local-storage access boundary | `service-worker.ts:122-128` | real CFT content-world negative read | PASS |
| HIGH H6 MV3 volatile listeners | `service-worker.ts:181,199-221` | timeout/listener lost on worker death | persisted sessions/outbox + alarms/startup `643-703,816-855` | lifecycle tests + profile reload/restart | PASS for deterministic/one restart; 20/20 NOT MEASURED |
| HIGH H7 relay split brain | `service-worker.ts:224-236` | confirmation substituted defaults | relay list + digest bound in all handshake messages | contract/pairing tests + seven-relay UI | PASS |
| HIGH H8 NIP-42 absent | Android `NostrCodec.kt:175-245`; Chrome pool path | auth-required relay misclassified | Chrome `transport.ts:49-126`; Android relay auth state | publish/subscription AUTH and stale-AUTH tests | PASS local; public AUTH NOT MEASURED |
| HIGH H9 incoming expiry ignored | Chrome `transport.ts:93-128`; Android wrap/manifest receive | stale replay reached payload logic | Chrome `transport.ts:239-244`; Android `NostrCodec.kt:159-168`, `TransferManager.kt:119-125` | expiry tests | PASS |
| HIGH H10 nonstandard BIP-340 nonce | Android `Secp256k1.kt:95-110` | official signing vector fails | exact BIP-340 implementation in same module | official vectors 0–14 | PASS |
| HIGH H11 stale Chrome outbox write after ACK (repair follow-up) | detached publish/retry/ACK handlers mutated one transfer independently | a late publisher could recreate an item after authenticated ACK deletion | keyed serial executor, durable reload inside transfer lock, ACK-first retry, receipt-before-delete | deterministic scheduler + service-worker recovery tests | PASS |
| MEDIUM M1 incomplete NIP-01 escaping | Android `NostrCodec.kt:30-50` | CR/tab/control canonical IDs diverge | serializer-backed `eventId()` `NostrCodec.kt:50-58` | canonical reference test | PASS |
| MEDIUM M2 rumor missing ID | Chrome `transport.ts:42-47`; Android `NostrCodec.kt:84-92` | spec/interoperability drift | Chrome `transport.ts:154-164`; Android `NostrCodec.kt:112-119` | rumor ID/signature tests | PASS |
| MEDIUM M3 `sent` item stranded | Chrome `service-worker.ts:153`, alarm pending-only | no ACK meant permanent nonretry | retryable states/backoff/age ceiling, ACK-only deletion | delivery-state/recovery tests | PASS |
| MEDIUM M4 “instant” background claim | Android periodic WorkManager | OS makes timing inexact | docs now say catch-up; immediate resume work added | source/emulator; broad physical timing NOT MEASURED | PASS documentation/implementation truth |
| MEDIUM M5 documentation drift | `PROTOCOL.md`, `SECURITY.md`, old report | claims exceeded implementation/evidence | regenerated v2 documentation set | manual cross-check + tests | PASS |
| MEDIUM M6 receiver ACK was only deduped in memory | pre-follow-up `SyncWorker` per-run set | a later catch-up could republish a completed ACK; process death after document commit had no durable ACK owner | Room v5 `ack_intents` + `processed_events`; serialized bounded quorum retry | 82 JVM tests, seven-case tests on API-26 and both phones, one TCL installed-state repeat | PASS for executed evidence; physical interruption matrix NOT MEASURED |
| MEDIUM M7 relay AUTH/OK attribution (repair follow-up) | Android relay listener shared terminal bookkeeping for AUTH and original event; Chrome trusted the dependency template shape | negative AUTH could be reported as article rejection; duplicate/contradictory frames could revise effects; a malformed template could reach signing | exact auth-event ID classification, first-terminal compare-and-set, one auth retry, independent Chrome template validator | Chrome/Android malicious, stale, negative, oversized, duplicate, and contradictory AUTH/OK tests | PASS local; public AUTH NOT MEASURED |
| MEDIUM M8 overlapping pairing advancement (repair follow-up) | UI, alarm, and worker entry points could perform concurrent read-modify-write | duplicate sends or stale pairing state writes under rapid/reentrant triggers | serialized Chrome pairing executor and Android process-wide pairing mutex; confirmation UI disables duplicate action | compilation, unit/state review; physical reentrancy series NOT MEASURED | PASS implementation; statistical physical gate NOT MEASURED |

## 3. Test matrix

| Area | Environment | Command/scenario | Expected | Actual | Evidence | Status |
|---|---|---|---|---|---|---|
| evidence boundary | Git | baseline/type/tree/branch/submodules | exact immutable start | HEAD began at `982920b4…`; dedicated branch | `evidence/raw/baseline/repository-boundary.log` | PASS |
| baseline Chrome regressions | Node/Vitest 2 | patched regression tests on baseline | demonstrate defects | 4 expected failures | `regressions-chrome-fail.log` | PASS |
| baseline Android regressions | JVM/Gradle | patched regression tests on baseline | demonstrate defects | 6 expected failures | `regressions-android-fail.log` | PASS |
| original physical failure | TCL | scan baseline QR | capture exact relay outcomes | one matching `OK_FALSE`, two transport failures, UI error | original TCL trace | PASS |
| Chrome dependencies | macOS/Node 22.16 | `npm ci`; `npm audit` | lockfile install; no known npm advisory | install PASS; 0 vulnerabilities | terminal run + lockfile | PASS |
| Chrome static | TypeScript 5.5.4 | `npm run typecheck` | no errors | no errors | terminal run | PASS |
| Chrome unit/fault | Vitest 5.0 | `npm test` | all pass | 16 files, 87 tests pass | terminal run | PASS |
| Chrome package | Vite 8.2.2/CFT | `npm run build` | valid MV3 package, standalone content script | verifier pass; no module/noncharacter packaging defect | build output | PASS |
| Chrome secret boundary | CFT 151 | content script reads local storage keys | access denied/hidden | storage API absent in content world; no values visible | `chrome-content-storage-isolation-*` | PASS |
| Chrome persistence | paired CFT profile | reload/restart + status | pairing/outbox survive | paired, exact seven relays, delivered 1, pending 0 | `chrome-paired-status-*` | PASS |
| Android unit | JDK 17/Gradle 8.7 | `./gradlew testDebugUnitTest` | all pass | 82 tests, 0 failures/errors | XML reports | PASS |
| Android lint/build | Android SDK 34 | `lintDebug assembleDebug assembleDebugAndroidTest` | 0 errors; APKs | success, 0 lint errors, 29 retained warnings | Gradle/lint report | PASS |
| Android instrumentation | API-26 emulator | exact APK + test APK, seven current DB/codec/transfer/hostile-QR cases | all pass | 7/7 | `exact-artifact-state-race-auth-2026-09-05.txt` | PASS |
| Android instrumentation | physical TCL T807D | exact APK + test APK, seven current cases | all pass | 7/7 | `exact-artifact-state-race-auth-2026-09-05.txt` | PASS |
| Android instrumentation | physical Samsung S23 | exact APK + test APK, seven current cases | all pass | 7/7 | same evidence | PASS |
| Android installed-state ACK recovery | preserved paired TCL | v4 -> v5 first catch-up then immediate second serialized catch-up | one bounded recovery ACK; no second ACK | observed exactly | same evidence | PASS for observation |
| Rust normative core | rustc/cargo 1.97.1 | `cargo test` | all pass | 6/6 | terminal run | PASS |
| Swift core mirror | Swift 6.3.3 | `swift test` | all pass | 7/7 | terminal run | PASS |
| public relays | disposable keys | six defaults x2 + Mostr probe | matching OK + exact `#p` read-back | all tested relays pass | `LIVE_RELAY_REPORT.md` | PASS |
| full repaired E2E | CFT + TCL + 7 relays | pair, send synthetic article, ACK | both connected, one doc, outbox clears only on ACK | observed 1/1 | final redacted evidence | PASS for observation |
| physical reliability series | TCL | required 20/20 and 50/50 gates | all quotas | not run | `TCL_PHYSICAL_VALIDATION.md` | NOT MEASURED |
| secret scan | tracked source/evidence filenames/content patterns | credential/private-key pattern review | no secret | none detected; test vectors/golden hashes classified | scan notes | PASS |
| diff hygiene | Git | `git diff --check` | no whitespace errors | pass before commit; rerun in final artifact build | Git | PASS |

## 4. Live relay matrix

See `LIVE_RELAY_REPORT.md`. All six defaults passed two consecutive
matching-OK/read-back rounds; the Mostr custom relay passed its probe and the
seven-relay physical flow. Per-relay end-to-end receipt latency was not retained
and is **NOT MEASURED**.

## 5. Physical TCL matrix

See `TCL_PHYSICAL_VALIDATION.md`. The exact executed counts are one v2 pairing,
one normal synthetic delivery, one logical duplicate/dedupe observation, one
verified ACK/outbox-clear flow, and one v4 -> v5 recovery plus immediate-repeat
check. Required reliability quotas remain **NOT MEASURED**.

## 6. Security invariants

| Invariant | Enforcement | Executed evidence | Status |
|---|---|---|---|
| no relay-only connected/delivered state | two-endpoint handshake + exact ACK | physical pair/delivery + tests | PASS |
| no secret in content-script storage | trusted-context access level | real CFT isolated-world negative test | PASS |
| no real identity | generated device/channel keys only | source and test boundary review | PASS |
| exact recipient/sender/version/expiry | NIP-59 and payload validators | vectors/unit/instrumentation | PASS |
| SSRF/private relay rejection | URL + DNS-every-connect policy | TS/Kotlin/fault tests | PASS |
| exactly-once local effect | document hash + atomic Room commit | instrumentation + physical duplicate | PASS |
| no completed-ACK restart on retained wrappers | Room v5 wrapper ledger + one bounded ACK lifecycle per transfer | unit/instrumentation + one installed-state TCL immediate repeat | PASS for executed evidence; broad physical matrix NOT MEASURED |
| plaintext outbox deletion only on ACK | strict ACK match | Chrome delivery tests + physical state | PASS |
| transport key signs only exact bound AUTH | full template/challenge constraints before signing | malicious-template and duplicate/rejection fault tests | PASS local; public AUTH NOT MEASURED |
| secret-free logs/evidence | structural sanitized diagnostics | retained-log/source scan | PASS |
| no backend/telemetry | direct relay architecture | dependency/source review | PASS |

## 7. Artifacts

The build script refuses a dirty tracked worktree, records `git rev-parse HEAD`,
builds the APK/test APK and Chrome ZIP, and writes SHA-256 plus commands/results
to ignored generated files:

- `artifacts/reader-debug.apk`
- `artifacts/reader-debug-androidTest.apk`
- `artifacts/reader-chrome-extension.zip`
- `artifacts/ARTIFACTS.json`
- `artifacts/SHA256SUMS`

Exact post-commit values belong in `ARTIFACTS.json`; placing a future commit
hash inside this tracked report would create an impossible self-reference.
Artifacts are debug/developer builds with no production signing material.

## 8. Git state

- Baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`.
- Branch: `fix/pairing-delivery-hardening-982920b4`.
- Fix implementation and documentation commits: see `git log` and generated
  artifact manifest.
- Push: not performed.
- Merge: not performed.
- Publication/release: not performed.
- Final dirty/clean state: recorded after artifact generation; ignored generated
  artifacts do not dirty the tracked source tree.

## 9. Remaining risks

Only evidence-based residuals are listed in `KNOWN_LIMITATIONS.md`. Decisive
items are the unexecuted reliability/lifecycle quotas, exact-final-artifact E2E
transmission, the one-time v4 -> v5 historical-ledger boundary, public relay
policy drift, no public AUTH challenge, untested live external signers, and
Android release/16-KiB maintenance.

## 10. Explicit declarations

Production publishing: NOT AUTHORIZED AND NOT PERFORMED

Force-push: NOT AUTHORIZED AND NOT PERFORMED

Real user identities/keys: NOT USED

Secret exposure: NOT DETECTED

Backend introduced: NO

Physical TCL validation: NOT MEASURED

Live public-relay verification: PASS
