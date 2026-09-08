# Beta execution ledger

Started 2026-09-05. Requested target: 0.9.0-beta.1; not yet beta ready.

## Resumed execution — 8 September 2026

The owner's new execution request supersedes the trial pause below. The complete
supplied brief and audit, all six named handoff documents, and the newer trial
handover have been read. Historical results below are not final-candidate proof.

- Starting source: `fea0bb6713823e9c1838f1b5f871cedafa66736e`, clean
  `codex/reader-0.9-beta`; fetched origin agrees (0 ahead / 0 behind).
- Audited `0c9884e955558f478d7668b46e9c1411bfe95c5d` is an ancestor.
  All newer recovery and capture-feedback work is retained.
- Connected target: TCL T807D, Android 16 / API 36. Owner package remains
  `com.reader.app` 0.1.0 / code 1. No app/profile data reset or key rotation.
- Node 22.16.0, npm 10.9.2, Swift 6.3.3. Default Java installation reports
  18.0.1.1; effective Gradle JVM and Rust version will be recorded with builds.
- Chrome application 152.0.7977.82; the running paired browser/profile is being
  identified separately. Baseline extension output goes to
  `artifacts/beta-work/baseline-chrome`, preserving live `chrome/dist`.
- AVDs available: API 26, API 36, and a 16-KiB target; runtime gates pending.
- Baseline Chrome, Android, Rust and Swift gates IN PROGRESS. Logs:
  `evidence/beta/resumed/baseline-*`. Initial sandbox ADB/socket and Git-fetch
  access failures were environmental; supported elevated access succeeded.
- Order: execute and close core recovery regressions first; then capture and
  reader foundations; then theme/highlight/review; then freeze and qualify the
  exact packages. No earlier package results count toward final acceptance.

Current R01–R05/R08/R09/R16/R17: IN PROGRESS (prior implementations exist;
remaining real orchestration, resource, crash and ACK-loss cases need proof).
R06: earlier short-selection fix retained; final route coverage pending.
R07/R10–R15: TODO. R18: IN PROGRESS. Final campaigns: NOT MEASURED.

### Recovery follow-up checkpoint

- Baseline PASS: Chrome 125 tests, typecheck and isolated build; Android
  clean JVM/lint/app/test build; Rust 6 tests; Swift 7 tests. The Android
  107-task count is build work, not a test count.
- Failure-first tests reproduced blocked healthy-relay fragments, unnecessary
  retained Chrome scan history, a cancelled Android publish leaking its socket,
  and early socket closure being reported as healthy after EOSE. Evidence:
  `core-failure-first-{chrome,android}.log` in `evidence/beta/resumed/`.
- Publication now progresses independently per relay with at most one frame
  in flight per relay. Worker queues retain IDs and list summaries rather than
  every compressed body. A durable receipt cancels the live publisher even if
  cleanup fails; a new worker cleans that payload without needing another ACK.
- ACK alarms now derive from persisted receive-attempt time and pending work;
  reload or an all-relay read failure restores the next receipt check.
- Android ACK sends run independently of arrival intake, reserve attempts
  before IO, and merge each result transactionally. DNS, probe response reads,
  publication frame totals and diagnostic traces are bounded; interrupted
  publication closes its socket. One failed relay DNS no longer vetoes the
  entire active channel's receive session.
- Incoming staging: 32 partial transfers, 64 MiB total decoded compressed
  chunks, 5 MiB per transfer, and 100,000 authenticated wrapper ledger entries.
  Capacity rejection rolls back all incoming effects and permits existing
  transfers to finish. Numeric wire coercion and unknown envelope fields are
  rejected; an already committed wrapper hash can skip repeated decryption.
- Android JVM PASS: 96 tests, 0 failures/errors/skips. QA app/test build PASS.
- TCL QA instrumentation reports `OK (13 tests)`: 12 assertion-bearing tests
  pass; the opt-in pairing UI helper is not invoked without its argument.
  Evidence: `core-tcl-instrumentation-1.log`. This is not a new pairing or
  capture-to-phone campaign. Owner app/profile untouched.
- Tested QA app SHA-256:
  `947c78c6b08d5f1db4bf5a8ab1d1e39a175902e8dea2f1b97b73b77c4cc86e14`.
  Matching QA test APK SHA-256:
  `8aeee718d288dba52533b2529fa4d339aa9d9172c30c5c527e5923afcb33fe53`.
  Local artifacts: `artifacts/beta-work/recovery/`.
- Current Chrome PASS: 130 tests, including real-worker cleanup failure,
  restart recovery and failed-query alarm restoration. Final checkpoint
  typecheck/build is retained separately from these Android hashes.
- Still open: full controlled relay/physical recovery campaigns, persistent
  sync status UI, safe upper-size reader storage, requested features, and final
  candidate qualification. No beta-ready conclusion.

## Baseline

- Repository: https://github.com/mariusschober/nostr-reader
- Clean starting branch: fix/pairing-delivery-hardening-982920b4
- Starting SHA: 0c9884e955558f478d7668b46e9c1411bfe95c5d (audited head).
- Continuation: codex/reader-0.9-beta, descended directly from audited head.
- Node 22.16.0, JDK 17.0.19, Swift 6.3.3; Rust 1.97.1 available outside PATH.
- Connected target: Samsung SM-S918B, Android 16, installed com.reader.app 0.1.0/code 1. TCL absent.
- Available AVDs: circadiano_api36, sprich_review_16k, sprich_review_api26.
- Owner app/profile data unchanged. No keys or private content collected.
- Baseline builds IN PROGRESS. Initial Gradle/ADB sandbox failures are environment failures; elevated access succeeded.

## Requirements to tests / audit dispositions

| ID | Required scope | Status | Evidence |
|---|---|---|---|
| R01 | durable due-time scheduling across worker restarts | IN PROGRESS | repeated top-level alarm replacement confirmed in source |
| R02 | truthful durable capture feedback | TODO | |
| R03 | bounded streaming and typed receive failure | TODO | |
| R04 | bounded resumable ACK history coverage | TODO | |
| R05 | bounded ACK refresh after completed-quorum loss | TODO | |
| R06 | authoritative literal short selection | TODO | |
| R07 | resilient scoped provider adapters/live sites | TODO | |
| R08 | resumable fragment publication, short locks | TODO | |
| R09 | visible-app arrival-driven receiving | TODO | |
| R10 | semantic cursor and restoration | TODO | |
| R11 | end-to-end tables/code/list fidelity | TODO | |
| R12 | lightweight lists/background parsing | TODO | |
| R13 | discoverable settings/per-item recovery | TODO | |
| R14 | active TTS speed and stale callbacks | TODO | |
| R15 | theme/inbox/highlight/share/review/migration | TODO | |
| R16 | profile/crypto/dependency assurance | TODO | |
| R17 | executable orchestration/crash tests | IN PROGRESS | |
| R18 | final package/privacy/compatibility evidence | TODO | |

Final physical campaigns and exact-package E2E: NOT MEASURED. No earlier build result counts toward final acceptance.

## Decisions

1. Preserve the current repaired branch, data, identities, and paired Chrome profile. Use explicit device serials and isolated synthetic QA for destructive campaigns.
2. Fix recovery before reader feature work. Alarms will restore persisted deadlines without postponement; no active pairing means no pairing polling alarm.
3. Existing audit findings are hypotheses until source/behavior confirms them. Only executed scope receives PASS.

## Recovery checkpoint

- Samsung is the user's requested physical target (confirmed 2026-09-05).
- Chrome for Testing 151.0.7922.34 is running the preserved paired profile; its data was not read/copied. It loads chrome/dist; defer rebuilding that directory until controlled update.
- Chrome: 111/111 PASS after alarm/history/receive changes. Earlier sandbox baseline: 83 passed/18 skipped due to localhost listen denial. Subsequent suite failures were obsolete source-shape/empty-success expectations; retained in local logs.
- Android baseline build PASS (106 Gradle tasks; not a test count). ACK recovery JVM gate PASS; streaming build PASS; added cancellation/socket tests still to run.
- ACK policy: newly authenticated unseen retransmission after five-minute cooldown can reopen a completed ACK, at most eight refreshes and 168 lifetime attempts, without extending expiry. Room v5 -> v6 adds a default-zero counter.
- Receive budgets currently 512 KiB/frame; Android 32 MiB/4096 frames per relay streamed into a 16-event queue; collected facade 8 MiB. Chrome 8 MiB/4100 frames before dependency queue. JSON nesting 32, event tags 16 x 4.
- History scanning uses inclusive integer-second partitions, persistent remaining windows, conservative cap detection. Crash-before-consumption coverage needs further validation; R04 is not closed by pure scan tests alone.
- API decision: Chrome alarms official reference consulted 2026-09-05, page revision 2026-08-13, https://developer.chrome.com/docs/extensions/reference/api/alarms . No reliance on Chrome-150 persistence flags; restore missing deadlines on worker evaluation.

- Isolated Samsung QA app `com.reader.app.qa`: instrumentation PASS 10/10 on Android 16. APK SHA-256 24d92cc5ca8c9e9dc6fac78c137d495f0c7acd4545214fc281e2b12627217f53; matching test APK 6580c5992a12cb22984492b1b126d4345067153e13799cefba2956531b548783. This is a recovery checkpoint, not final beta proof. Owner installation unchanged.
- Chrome recovery/capture checkpoint: typecheck/build PASS; 104 tests PASS. Removed obsolete source-pattern tests in favor of executable alarm, publication and scan regressions; four source architecture checks remain supplementary.
- Publication now reserves attempts durably, reuses one relay pool per transfer, records per-fragment/per-relay progress, checks generation at short commits, and lets ACK processing close redundant publishers.
- ACK scan consumes durable receipt effects before coverage checkpoints. A storage failure keeps the window retryable.
- Capture IDs and local content are stored atomically with outbox intent; duplicate messages reuse the transfer. Retained captures survive delivery cleanup. Recovery UI/export still TODO.

## User-requested pause / recovery trial checkpoint

- User switched physical QA from Samsung to TCL, then requested a pause for a 24-hour trial. Remaining feature implementation and full beta campaigns are PAUSED, not complete.
- Real worker runtime regressions now execute capture persistence, lost-response restart, concurrent duplicate messages, quota rollback, privileged message boundaries, authenticated ACK settlement during slow publication, wrong-manifest rejection, and failed relay reads. A failure-first test found and fixed synchronous IndexedDB write setup leaving an orphaned send intent.
- Full Chrome regression checkpoint: 114/114 PASS across 21 files. Production receive queries now expose all-relay failure and persist degraded coverage instead of returning healthy empty success.
- Context-menu capture reports Saving, durable-save, and failure states. Retained-capture recovery UI remains incomplete.
- CameraX 1.4.2 / DataStore 1.1.7 eliminate the observed native alignment problem: all 12 bundled native libraries have PT_LOAD alignment >=16 KiB; APK zip alignment passes. 16 KiB runtime remains NOT MEASURED. Primary release references: https://developer.android.com/jetpack/androidx/releases/camera and https://developer.android.com/jetpack/androidx/releases/datastore (consulted 2026-09-05).
- TCL Android 16 isolated app/test pair: migration/crypto/gzip/transfer instrumentation 10/10 PASS. Opt-in UI pairing test 1/1 PASS through manual native text entry; both endpoints confirmed authenticated completion. Camera permission was declined after automatic review rejected granting it; manual entry completed the test. Keyboard-based automation had altered JSON; that failed attempt is not counted as an app pairing failure.
- Packaged QA Chrome: two deliberate one-word captures, including one retained before pairing, produced two authenticated receipts and one exact single-copy Android document. Expected document SHA-256 c4745b60855edff941e66d266f180b24333e1ccf4607d21d10263436315d97f3. Six default public relays; no custom-relay campaign. See evidence/beta/tcl-recovery-delivery.json.
- This is a recovery trial checkpoint, not 0.9 beta readiness. R01-R05/R08/R09/R16/R17 have implementations and focused evidence but remain open against the full campaign requirements. R06 short selection has physical proof. R07 and R10-R15 are unfinished. R18 has partial compatibility/package evidence only.
- Trial normal package installed in place on TCL; current app/test pair instrumentation 10/10 PASS and pre/post key/settings file bytes identical. Existing paired Chrome profile restored, normal Load unpacked update at original path, Connected · 7 relays preserved. Source freeze e322c300e5b623904626812b53e75bf4949fbd8e; artifact hashes in evidence/beta/RECOVERY_ARTIFACTS.json. See HANDOVER_RECOVERY_TRIAL.md for all current status and CONTINUE_READER_PROMPT.md for resumption.

## 6 September: authorized capture-feedback follow-up

Fixed missing context-menu in-page feedback and stale waiting feedback after authenticated delivery. Per-action UI routing persists with outbox intent; terminal feedback is monotonic and bounded local receipt polling recovers missed pushes. Chrome typecheck and 125/125 tests PASS. Live synthetic selection and toolbar actions on Chrome for Testing/TCL QA showed receipt feedback and timed dismissal. Evidence and exact extension package hashes: `evidence/beta/capture-feedback/`. No Android changes; broader beta execution remains paused.

Owner confirmed the fix appears to work and requested no further extensive testing. Development paused for one week of real-world use; resume only with owner feedback.
