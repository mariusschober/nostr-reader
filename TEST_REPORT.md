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

The final runtime checkpoint was installed byte-for-byte on both physical
phones and the API-26 emulator. Two additional cases exercise pending-pairing
worker ownership and strict rejection of concatenated/trailing gzip input. All
ten instrumentation cases pass in all three environments for the
runtime-checkpoint APK pair. The preserved TCL still exposes the authenticated
Chrome device after that exact install. Chrome
loaded the exact ZIP worker bytes, preserved the seven-relay binding through a
full same-profile restart, and recovered 20/20 distinct post-pair worker
terminations without outbox mutation.

The later Gradle 8.9/AGP 8.7.2 rebuild made the app and instrumentation APKs
byte-reproducible without changing app source. On 2026-09-05 the exact final
app APK `4c555d6d…ea5e` was installed in place on the TCL with app data
preserved, pulled back from the package manager, compared byte-for-byte, and
cold-started successfully. The matching test APK `244c4bd0…563` was not
installed and final-hash instrumentation was not run; that narrower result
remains **NOT MEASURED**. The generated `artifacts/ARTIFACTS.json` records the
same boundary.

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
  + one-time worker could finish before next pairing retry -> recovery gap
  + decoders accepted a second gzip member/trailing bytes -> ambiguous frame
```

The final implementation and documentation commit IDs are recorded by Git and
the generated `artifacts/ARTIFACTS.json`. They are not embedded into their own
commit because a commit cannot contain its own future hash.

Final runtime fix commit:
`6ea80ee6f3732a192307661fd9cd5c485a4dd1dc`. The later audited artifact and
documentation commit is the `sourceCommit` in generated `ARTIFACTS.json`.

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
| HIGH H12 Chrome channel not bound to retained device key (repair follow-up) | Chrome `service-worker.ts:129-182,365-425`; completed state stored the Android channel and relays but not the authenticated Chrome device public key | missing/malformed storage could display an unrecoverable old channel as connected; a replacement valid key could sign under stale trust; an unbound old capture could retain the wrong sender identity | `device-binding.ts:1-62`; service worker now validates both x-only keys, persists/migrates the public device binding, cancels mismatched sessions, strips unusable channel state, rebinds only never-sent captures, and fails already-bound sender drift before network | device-binding unit tests + worker recovery assertions + preserved-profile migration/integrity check | PASS implementation/local/profile; deliberate live key corruption not performed |
| HIGH H13 pairing retry ownership and bootstrap-secret lifetime (repair follow-up) | Android discarded a newly provisioned transcript when the first response got no positive `OK`, and one-time sync could return success while a future pending pairing remained; Chrome ACK was attempted on each 2.5-second UI poll, never repeated after first acceptance, and retained the bootstrap secret beyond response authentication | transient/missing relay confirmation could force another scan; Android could fall back to a 30-minute periodic run after the transcript expired; Chrome could flood retries or strand Android after an ACK stored without a returned `OK`; a crash extended one-time secret retention | zero-confirmation Android responses remain non-active/retryable; `processDue()` returns pending ownership and WorkManager uses 10-second exponential retry; Chrome durably strips the secret immediately after response authentication, enters completion-reading after every send attempt, and republishes a fresh authenticated ACK at most every 30 seconds until completion/expiry | JVM/instrumentation retry regressions; Chrome transition/timing and recovery-source tests; 10/10 exact-device instrumentation | PASS implementation/deterministic; physical in-flight kill quota NOT MEASURED |
| HIGH H14 gzip decoders accepted extra members/trailing bytes (repair follow-up) | runtime convenience decoders stopped after or transparently joined a valid first stream | concatenated members and arbitrary suffixes violated the documented one-member wire contract and created cross-runtime ambiguity | TypeScript/Kotlin/Swift stream raw DEFLATE to an exact consumed boundary and verify CRC32/ISIZE; Rust requires complete one-member cursor consumption; all enforce the expanded limit during decode | pre-fix rejection tests failed on all four runtimes; post-fix Chrome/Kotlin/Rust/Swift tests and Android physical runtime case pass | PASS |
| MEDIUM M1 incomplete NIP-01 escaping | Android `NostrCodec.kt:30-50` | CR/tab/control canonical IDs diverge | serializer-backed `eventId()` `NostrCodec.kt:50-58` | canonical reference test | PASS |
| MEDIUM M2 rumor missing ID | Chrome `transport.ts:42-47`; Android `NostrCodec.kt:84-92` | spec/interoperability drift | Chrome `transport.ts:154-164`; Android `NostrCodec.kt:112-119` | rumor ID/signature tests | PASS |
| MEDIUM M3 `sent` item stranded | Chrome `service-worker.ts:153`, alarm pending-only | no ACK meant permanent nonretry | retryable states/backoff/age ceiling, ACK-only deletion | delivery-state/recovery tests | PASS |
| MEDIUM M4 “instant” background claim | Android periodic WorkManager | OS makes timing inexact | docs now say catch-up; immediate resume work added | source/emulator; broad physical timing NOT MEASURED | PASS documentation/implementation truth |
| MEDIUM M5 documentation drift | `PROTOCOL.md`, `SECURITY.md`, old report | claims exceeded implementation/evidence | regenerated v2 documentation set | manual cross-check + tests | PASS |
| MEDIUM M6 receiver ACK was only deduped in memory | pre-follow-up `SyncWorker` per-run set | a later catch-up could republish a completed ACK; process death after document commit had no durable ACK owner | Room v5 `ack_intents` + `processed_events`; serialized bounded quorum retry | 86 JVM tests, ten-case tests on API-26 and both phones, one TCL installed-state repeat | PASS for executed evidence; physical interruption matrix NOT MEASURED |
| MEDIUM M7 relay AUTH/OK attribution and challenge retention (repair follow-up) | Android relay listener shared terminal bookkeeping for AUTH and original event; Chrome trusted the dependency template shape and dependency NOTICE logger | negative AUTH could be reported as article rejection; duplicate/changed challenges could create repeated signatures; a relay could echo a complete short challenge into retained diagnostics | exact auth-event ID classification, first-terminal compare-and-set, one challenge/signature per operation, independent Chrome template validator, exact challenge redaction in NOTICE/OK/CLOSED/error paths | Chrome/Android malicious, stale, negative, oversized, duplicate, changed, echoed, and contradictory AUTH/OK tests | PASS local; public AUTH NOT MEASURED |
| MEDIUM M8 overlapping pairing advancement (repair follow-up) | UI, alarm, and worker entry points could perform concurrent read-modify-write | duplicate sends or stale pairing state writes under rapid/reentrant triggers | serialized Chrome pairing executor and Android process-wide pairing mutex; confirmation UI disables duplicate action | compilation, unit/state review; physical reentrancy series NOT MEASURED | PASS implementation; statistical physical gate NOT MEASURED |
| MEDIUM M9 custom-relay completion and corrupt relay state (repair follow-up) | Chrome final-completion catch-up filtered to defaults; paired capture had a default fallback; quorum used the durable item-list length | Android could become active after custom-only completion acceptance while Chrome kept waiting; corrupt state could change the channel set or make required quorum zero | fixed-only unauthenticated bootstrap, full authenticated completion read, relay-digest recheck, canonical durable-set validation before network/quorum, unbound queue on incomplete channel; removed unused connectivity-only probe | relay-contract and service-worker recovery regressions | PASS implementation/local; public custom-only completion failover NOT MEASURED |

## 3. Test matrix

| Area | Environment | Command/scenario | Expected | Actual | Evidence | Status |
|---|---|---|---|---|---|---|
| evidence boundary | Git | baseline/type/tree/branch/submodules | exact immutable start | HEAD began at `982920b4…`; dedicated branch | `evidence/raw/baseline/repository-boundary.log` | PASS |
| baseline Chrome regressions | Node/Vitest 2 | patched regression tests on baseline | demonstrate defects | 4 expected failures | `regressions-chrome-fail.log` | PASS |
| baseline Android regressions | JVM/Gradle | patched regression tests on baseline | demonstrate defects | 6 expected failures | `regressions-android-fail.log` | PASS |
| original physical failure | TCL | scan baseline QR | capture exact relay outcomes | one matching `OK_FALSE`, two transport failures, UI error | original TCL trace | PASS |
| Chrome dependencies | macOS/Node 22.16 | `npm ci --offline`; `npm audit --omit=dev --json`; `npm audit --json` | locked local install; current registry response has no known advisory | 183 packages installed; both dated online audits returned 0 vulnerabilities | `chrome-npm-audit-online-2026-09-05.txt` + terminal run | PASS at the recorded UTC time |
| Chrome static | TypeScript 5.5.4 | `npm run typecheck` | no errors | no errors | terminal run | PASS |
| Chrome unit/fault | Vitest 5.0 | `npm test` | all pass | 17 files, 101 tests pass | `pairing-retry-gzip-boundary-hardening-2026-09-05.txt` | PASS |
| Chrome package | Vite 8.2.2/CFT | `npm run build` | valid MV3 package, standalone content script | verifier pass; no module/noncharacter packaging defect | build output | PASS |
| Chrome package reproducibility | macOS `zip` + fresh Vite rebuild | normalize staged timestamps/order/metadata; package twice; rebuild and package again | all three ZIPs byte-identical | common SHA-256 `01b4d5b7…744c92`; all entries fixed to 1980-01-01 00:00 | `artifact-reproducibility-2026-09-05.txt` | PASS |
| Fresh GitHub-clone handoff rebuild | clean clone of pushed branch at `097f808` | run complete audit build with no generated artifacts present initially; compare outputs to primary checkout | all gates pass and installable artifacts match | Chrome 101/101; Android 107-task gate; Rust 6/6; Swift 7/7; APK, test APK, Chrome ZIP, and Android inventory exact matches | `evidence/raw/fresh-clone-handoff-rebuild-2026-09-05.txt` | PASS |
| Chrome secret boundary | CFT 151 | content script reads local storage keys | access denied/hidden | API namespace present in exact current isolated world, but read denied; no values visible | `chrome-content-storage-isolation-*`, `pairing-relay-state-hardening-*` | PASS |
| Chrome persistence | paired CFT profile | reload/restart + status + device/relay binding integrity | pairing/outbox survive with exact bound state | paired, exact seven relays, device binding present/verified, relay digest present/matched, delivered 1, pending 0 | `chrome-paired-status-*`, `chrome-device-binding-hardening-*` | PASS |
| Chrome post-pair worker recovery | paired CFT profile | terminate 20 distinct service-worker targets and request read-only status after each | every new worker revalidates the same bound channel without outbox mutation | 20/20 recovered; paired 7, pending 0, delivered 1, failed 0 | `chrome-device-binding-hardening-*` | PASS for post-pair recovery; required before-reply pairing quota NOT MEASURED |
| Android unit | JDK 17/Gradle 8.9/AGP 8.7.2 | `./gradlew testDebugUnitTest` | all pass | 86 tests, 0 failures/errors | XML reports + `pairing-retry-gzip-boundary-hardening-2026-09-05.txt` | PASS |
| Android lint/build | Android SDK 34 | `lintDebug assembleDebug assembleDebugAndroidTest` | 0 errors; APKs | success, 0 lint errors, 17 retained warnings | Gradle/lint report | PASS |
| Android debug APK reproducibility | Gradle 8.9 / AGP 8.7.2 / D8 8.7.18 | two clean same-source single-worker builds after diagnosing four failures on AGP 8.5.2 | byte-identical app and test APKs | both app APKs `4c555d6d…ea5e`; both test APKs `244c4bd0…563`; exact `cmp` PASS | `artifact-reproducibility-2026-09-05.txt` | PASS |
| shared pairing transcript | Chrome + Android JVM + Android runtime | one fixed request/response/ACK/completion across both implementations | exact field equality and opposite-end validation | unit PASS; runtime PASS | `shared-pairing-vector-2026-09-05.txt` | PASS |
| Android instrumentation | API-26 emulator | runtime-checkpoint APK `0768111d…d74` + test APK `0dc99230…821`, ten DB/codec/transfer/QR/pairing/recovery cases | all pass | 10/10 | `exact-artifact-api26-2026-09-05.txt` + final evidence | PASS for the runtime checkpoint |
| Android instrumentation | physical TCL T807D | same byte-matched runtime-checkpoint APK pair, ten current cases | all pass | 10/10 | `pairing-retry-gzip-boundary-hardening-2026-09-05.txt` | PASS for the runtime checkpoint |
| Android instrumentation | physical Samsung S23 | same byte-matched runtime-checkpoint APK pair, ten current cases | all pass | 10/10 | same evidence | PASS for the runtime checkpoint |
| Final reproducible Android app APK | physical TCL T807D | `adb install -r` app APK `4c555d6d…ea5e`; pull installed `base.apk`; compare bytes; cold-start declared activity | exact final app installed with data preserved and starts | install `Success`; pulled SHA-256 exact; `cmp` PASS; `Status: ok`, `LaunchState: COLD`, visible resumed activity | `evidence/raw/final-tcl-app-install-2026-09-05.txt` + generated manifest | PASS for exact app install/startup on TCL |
| Final reproducible Android instrumentation | TCL/S23/API-26 | install test APK `244c4bd0…563`; run ten-case instrumentation | exact final test hash installed and all cases pass | not run after toolchain rebuild | generated `artifacts/ARTIFACTS.json` | NOT MEASURED |
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
| no ghost Chrome channel after device-key loss/replacement | persisted public identity marker + current private-key derivation check before status/network | deterministic key classes + worker assertions + compatible live-profile marker migration | PASS; deliberate live key corruption not performed |
| exact one-member gzip framing | streaming expanded limit + exact DEFLATE boundary + CRC32/ISIZE + no suffix | four runtime rejection tests + physical Android case | PASS |
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

The clean build uses the lockfile with `npm ci --offline` and offline Gradle,
followed by the complete local test, lint, package, SBOM, and
dependency-inventory gates. Separate dated online `npm audit --omit=dev` and
full `npm audit` registry queries both returned zero vulnerabilities. This is
a time-bounded advisory result, not a permanent claim about future advisories.

Chrome packaging now normalizes timestamps and file order and requires two
independent packages of the same fresh dist to compare byte-for-byte. A third
package after another Vite build matched as well. Android's prior AGP
8.5.2/D8 8.5.35 toolchain changed debug-only synthetic-class checksum metadata
between clean builds despite identical normalized DEX semantics. The pinned,
officially compatible Gradle 8.9/AGP 8.7.2/D8 8.7.18 update closes that gap:
two clean app builds and two clean test-APK builds compare byte-for-byte.
Exact final APK hashes remain authoritative. The earlier runtime-checkpoint
pair was installed/tested on all three targets. The final reproducible app APK
is now byte-verified and startup-verified on the TCL; its matching final test
APK and instrumentation remain **NOT MEASURED**.

Exact post-commit values belong in `ARTIFACTS.json`; placing a future commit
hash inside this tracked report would create an impossible self-reference.
Artifacts are debug/developer builds with no production signing material.

## 8. Git state

- Baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`.
- Branch: `fix/pairing-delivery-hardening-982920b4`.
- Fix implementation and documentation commits: see `git log` and generated
  artifact manifest.
- Push: dedicated repair branch pushed to `origin` under explicit user
  authorization for this handoff; verify the remote ref before relying on it.
- Merge: not performed.
- Binary publication/release: not performed.
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

Full physical TCL reliability quotas: NOT MEASURED

Live public-relay verification: PASS
