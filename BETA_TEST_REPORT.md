# Reader 0.9.0-beta.1 — candidate test report

Date: 8 September 2026. **Candidate delivered with documented limits; no blanket
beta-ready or field-reliability claim.** The owner's instruction to keep testing
minimal and not repeat successful tests superseded the original repeated 20/50-count
campaigns. Passing evidence is reused only at its recorded scope and artifact.

## Result

The repaired Chrome-to-Android product includes durable capture/recovery, bounded
transport/storage, foreground receiving, structured reading and semantic position,
themes, highlights/Undo, quote-only sharing, feed/review and active TTS controls.
Physical testing found and corrected native text-selection/autoscroll defects.
Packaged Chrome testing found and corrected selection contamination, stale settings,
preview feedback and explicit lost-receipt Retry behavior.

The final owner APK and matching test APK are installed on TCL T807D, Android 16 /
API 36. Pulled-back APK hashes match the delivered files. Database/WAL, key preference
and DataStore fingerprints were byte-identical across the in-place install before
startup. One final-hash read-only instrumentation test passed installed-version,
database-open and SQLite integrity checks. This does not transfer every earlier
physical UI test to the final normal-package hash.

## Source, artifacts and environment

- Android frozen source: `a4b3782`; Chrome final source: `19e8394`. Full hashes,
  signatures, APK relationships and file hashes are in [ARTIFACTS.json](ARTIFACTS.json).
- macOS 26.6.2 arm64; Node 22.16.0; npm 10.9.2; Gradle 8.9; Temurin 17.0.19+10;
  Swift 6.3.3; Rust 1.97.1. Chrome for Testing 151.0.7922.34.
- Controlled end-to-end tests use a separate Chrome profile and `com.reader.app.qa`,
  real WSS/Nostr signing/encryption and Android storage, with locally routed relay
  hosts. They are not public-relay or signed-in provider tests.
- The owner browser profile and live `chrome/dist` were not overwritten. Android
  upgrade did not clear data or replace keys. No store publication or merge.

| Artifact | SHA-256 |
|---|---|
| Normal app APK | `4536ab0387ec9cc2f439cacec4e42c72f7e801a6177c8e40a1efcb12ce4b6b07` |
| Matching normal test APK | `d9fcda548d914c36ed33c61ad9b0f221adc64890dfec64af70297b4c3940e3a1` |
| Final Chrome ZIP | `a2e4ce57587450b92695d080782df524a1a4a2f226540ab0a63aa298a8b66646` |
| QA app used for focused lifecycle/delivery | `4ea56fa0cd9bd488d6c967580c60c91481e616c848840cccfedff216afc46cb1` |
| QA test-24 used for system bars/delivery controller | `ec8e5274acedfe98324abc1e2beef6b05188af089ae21ed47c67debaab1c1d36` |

All are under `artifacts/beta-0.9.0-beta.1/`. APKs are debug-signed. QA test-24 predates
the final normal-package integrity test addition; its source boundary is explicit
in ARTIFACTS.json. Test-23, with the same QA app, passed the TTS/theme lifecycle case.

## Executed checks

Evidence filenames below are relative to `evidence/beta/resumed/`.

| Scope | Outcome and boundary | Evidence |
|---|---|---|
| Baseline reference contracts | PASS: Rust 6 and Swift 7 tests; no later reference-runtime edits | `baseline-rust.log`, `baseline-swift.log` |
| Chrome unit/fixture suite | PASS: 132 tests / 22 files at the selection fix checkpoint; subsequent preview 10 focused checks passed | `chrome-selection-tests-2.log`, `chrome-preview-tests.log` |
| Final Retry regression | FAIL before fix: one new assertion, four unselected tests. PASS after fix: five affected publisher tests; typecheck/build pass | `retry-manifest-before.log`, `retry-manifest-after.log`, `retry-manifest-typecheck.log` |
| Android JVM | PASS: 110 tests, no failures/errors/skips at native layout/viewport checkpoint; later app change is displayed version metadata | `native-layout-fix-build.log`, `native-viewport-build-18.log` |
| Android core instrumentation | PASS: 12 assertion-bearing checks; runner reports 13 including an uninvoked opt-in pairing helper | `core-tcl-instrumentation-1.log` |
| Storage/migration/gzip | PASS: seven physical synthetic checks, including oversized persisted content/reopen | `tcl-storage-migration-tests.log` |
| Native reader/review | PASS: extension, reversal, edge autoscroll, one exact quote record, Undo, Speed/return, feed/source/share return and both swipe meanings; one full flow, 76.176 s | `native-viewport-review-tcl-19.log` |
| Unicode/source deletion/share | PASS: exact multiline/Unicode local text/plain receipt, survival after actual source deletion and activity recreation; one case, 54.957 s | `reader-unicode-tcl-12.log` |
| TTS/theme lifecycle | PASS: real TTS callback advancement, active 1.25x speed, granted transient audio focus pauses, next/previous while paused, background pause/return, reactive theme/cursor and explicit Paper preservation; one case, 57.863 s | `remaining-lifecycle-tcl-23.log` |
| Settled system-bar contrast | PASS: one focused case and inspected screenshot. Earlier dark-icon screenshot was transitional | `system-bars-tcl-24.log` |
| Large reader performance | PASS for rendering/scroll/cursor; observed slow-frame limitation, one 56.427 s case | `native-large-viewport-tcl-20.log`, `reader-large-viewport-20.json` |
| Packaged Chrome controls | PASS for exercised permission/settings, literal selection/export, generic preview Save/Cancel, queued feedback and theme controls | `docs/beta/CHROME_UI_TESTING_2026-09-08.md` |
| System theme | PASS under native DevTools dark/light media emulation; global macOS appearance was not changed | `chrome-system-dark-preference.json`, `chrome-system-light-preference.json` |
| Final owner install/integrity | PASS: exact normal APK/test pair; one read-only test, 0.024 s | `final-owner-instrumentation.log`, `final-installed-verification.json` |
| Dependency audit | PASS: current Chrome production/development npm audit zero vulnerabilities; not an Android CVE claim | `chrome-dependency-audit-current.json` |

Earlier failed physical attempts are retained. The invocation inventory
`instrumentation-invocation-index.json` records runner counts/failures without
inflating opt-in helpers into assertion-bearing passes. The TCL narrative identifies
real rendering/autoscroll defects and separate fixture/navigation/receiver mistakes.
A later successful run does not erase those failed attempts.

## Focused delivery and recovery

1. One fresh authenticated pairing completed on both endpoints using six controlled
   default-named relay hosts. Five previously queued packaged captures then settled:
   five matching endpoint receipts, four unique stored hashes, one duplicate receipt.
   One of those queued inputs was the deliberately retained pre-fix contaminated
   selection; delivery fidelity does not retroactively make that extraction correct.
   Evidence: `focused-pairing-android.json`, `focused-delivery-result.json`.
2. One new selection captured through the beta package while controlled relays were
   offline. Its nonempty durable outbox survived explicit service-worker termination.
   Connectivity restoration let the phone receive it without a manual resend; it was
   the same existing document, stored once. Evidence: `focused-offline-worker.json`,
   `focused-lost-ack-before.json`.
3. The harness accepted but discarded receipts addressed to the synthetic sender.
   Android had completed ACK quorum while Chrome truthfully remained awaiting device.
   After restoring receipt retention and the cooldown, Retry initially sent no fresh
   demand because accepted fragment checkpoints were reused. That failed outcome is
   retained in `focused-lost-ack-final.json`.
4. The narrowly fixed final ZIP was reloaded through native Chrome. Retry refreshed
   only the manifest; Android emitted one refresh (two total attempts, refreshCount 1),
   and Chrome stored the authenticated delivered receipt. Hash/single-copy checks pass.
   Evidence: `focused-lost-ack-fixed.json`. This demonstrates manual demand recovery,
   not a measured automatic-recovery latency. Reading progress changed during the
   foreground interval, so this case does not certify replay preservation of cursor
   or highlight state. The final settings page after Check delivery status showed
   pending 0 / delivered 6 / failed 0 (`final-settings-visible.json`).

The five queued captures were made with earlier package iterations whose final
content/UI bytes were unchanged except for recorded fixes/version metadata. The
sixth capture used the first beta ZIP; its recovery was exercised on the final fixed
ZIP. These are six logical transfers across a development checkpoint, **not six
fresh final-ZIP captures**, and not the requested 50-case statistical campaign.
Earlier controlled/public observations remain historical, separately labeled evidence.

Original 20/50-count gates, same-profile browser restart with pending work,
all pairing/commit crash points, public AUTH, actual provider accounts and broad
physical lifecycle/accessibility matrices remain NOT MEASURED at final candidate.

## Performance and packaging limits

One TCL debug/instrumented article contained 157,939 canonical characters /
166,939 UTF-8 bytes and 5,006 rendered lines. Six finger scrolls advanced the cursor.
Ready time was 1,160 ms including tap waits. FrameMetrics: 390 frames, p95 9.41 ms,
six frames over 100 ms, maximum 303.97 ms. gfxinfo: p95 9 ms, p99 150 ms. PSS rose
from 254,066 to 275,019 KiB. This is neither a release benchmark nor a leak test.
No before/after distribution, 1,000-document/10,000-highlight corpus or reliable
p95 capture-to-phone timing is claimed. The raw Perfetto trace is retained locally;
it was not analyzed for a main-thread root cause.

The final ZIP, unpacked directory and loaded Chrome files match byte-for-byte.
An earlier unchanged Chrome build/ZIP reproduced exactly. The final narrow Retry
change has affected tests/build and packaged recovery proof; the entire suite was
not repeated. A clean export of the frozen Android source **builds successfully**,
but its app differs from the installed incremental APK in `classes7.dex` and
`classes8.dex`; all test-APK entries match. Full app byte reproducibility across
that relocated clean export is **FAIL**, not a claimed pass. The installed/tested
APK remains the delivered artifact; the alternate clean build was not substituted.
See `final-repro-comparison.json` and `final-fresh-source-build.log`.

## Requirement disposition and handoff

[BETA_AUDIT_RESOLUTION.md](BETA_AUDIT_RESOLUTION.md) maps R01–R18 to fixes, evidence
and residual limits. [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) and the
[source matrix](docs/beta/SOURCE_SUPPORT_MATRIX.md) define remaining acceptance.
Manual CI is prepared but not remotely executed. The candidate is suitable for a
scoped owner trial; it is not claimed store-ready or fully statistically qualified.

Capture status: “Saved” means durable local capture; relay acceptance is intermediate;
“Delivered” requires Android's authenticated storage receipt. Pending items retain
Retry/export/discard controls. In the reader, select text in Highlight mode, adjust
the native handles, choose a color or Undo. Open Highlights to review; Next or a
left swipe advances, while a right swipe toggles importance. Share sends only the
quote. Exports are one-way files, not restorable backups.
