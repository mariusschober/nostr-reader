> Historical checkpoint: results and instructions below apply to their recorded source and date. Start with [current status](../../CONTINUE.md) for the installed build and remaining limits.

# TCL testing resumed — 2026-09-08

Historical execution detail. Current candidate status and exact artifact boundaries are in [BETA_TEST_REPORT.md](../../BETA_TEST_REPORT.md).

## Bugs found and corrected

- Ordinary article and RSVP rendering failed with `offset out of bounds`. Inside the grapheme iterator's `apply`, `text` resolved to the iterator's empty text instead of the supplied string. Explicit iterator initialization fixes this. Two regression cases failed before the fix; all three new projection cases pass afterward. RSVP error/loading states also now have a readable themed surface and Back action.
- Native precomputed text produced incorrect horizontal selection positions on the TCL with mixed heading/body styles. The same `deliberate` offset was reported at x=955.84 with precomputed text and x=591.97 with standard native layout. Standard layout selected range 44:54 and saved the exact quote. The selectable view now uses ordinary native layout. Large-part layout performance remains to be measured; background precomputation is no longer claimed.
- Native article drawing is clipped to its Compose viewport so content cannot paint under the bottom controls.

## Evidence

- Android unit tests: **PASS**, 110 tests, no failures/errors/skips (`native-layout-fix-build.log`).
- Production reader UI in isolated `com.reader.app.qa` on physical TCL: **PASS**, native long-press selection saves exactly one matching quote, Undo removes it, Speed opens paused without the rendering error, and Exit returns to the reader (`reader-flow-tcl-native-fix.log`). An earlier standard-layout comparison also passed.
- Physical database migration, large persisted content/reopen, and gzip tests: **PASS**, seven tests (`tcl-storage-migration-tests.log`). These are isolated synthetic tests, not Chrome-to-phone delivery proof.
- Extended physical reader flow: **PASS**, one test in 62.659 seconds (`reader-review-tcl-10.log`). It repeats selection/Undo/Speed, opens the saved quote from the feed, persists importance, opens the source and returns to the same review, cancels the native Sharesheet, delivers exactly `deliberate` to a local test-only receiver as `ACTION_SEND` / `text/plain` without subject or stream, returns to review, and taps Next. The source and Sharesheet screenshots were visually inspected. Nothing was shared to a person or external service.
- Earlier review test failures exposed harness problems: selection-mode Back, repeated identical fixture text, retained feed scroll position, taps during scrolling, asynchronous rendering of importance, synthetic Back-key injection, and a missing Kotlin runtime in the standalone receiver process. The receiver now uses only Android platform classes. These failed attempts are retained as evidence; they are not counted as product failures or passes.
- A follow-up test adds a Unicode/multiline quote retained after actual source deletion, exact local receipt, activity recreation, and an explicit Next-card assertion. **Compiled, not run**: the TCL disconnected before the updated test APK could be installed. Build evidence: `reader-review-build-11.log`. The earlier passing test does not cover these additions.
- After reconnection, the native review/Next case passed in the two-case run (`reader-review-tcl-11.log`). The new Unicode fixture failed before entering the UI because NFC normalization composed its input accent. Changing the fixture to an accent sequence that remains decomposed under NFC fixed that test assumption. The Unicode case then **passed** on TCL in 54.957 seconds (`reader-unicode-tcl-12.log`): exact multiline/Unicode receipt, survival after actual source deletion, and restoration of the same review after activity recreation. The resulting screen was visually inspected. This is activity recreation, not a process-death test.
- Main app update: **installed with data retained**. Startup visibly shows the existing library. No owner highlights were created by the synthetic tests.
- Installed APK SHA-256: `9eae5e90e5f81d060d88b51ce43c12bc6fab0af0dabd1dab99382fed1ebcf19b`.
- Local APK: `artifacts/beta-work/testing-resume/owner-fix-2/reader.apk`. Screenshots stay in ignored artifacts because some include owner library titles or a floating chat overlay.

Logs are under `evidence/beta/resumed/`. Early selection failures included a floating overlay hit at the erroneous coordinate. A timing-only test adjustment did not resolve the mismatch; the standard-layout comparison did.

`review-artifact-checkpoint.json` records the tested QA application and the newer, unrun test APK separately. Their copies are in ignored `artifacts/beta-work/testing-resume/review-checkpoint/`. This checkpoint is not the final frozen candidate.

## Still pending

The first production handle-extension attempt failed its cross-paragraph assertion (`reader-handles-tcl-13.log`, 53.074 seconds). A later screen capture showed another app in the foreground; this does not establish whether it interrupted the gesture or appeared after the test closed. Handle acceptance remains unresolved. The test now checks foreground package before every injected touch and stops if an unrelated app is active.

Follow-up tests isolated the failure: targeting the round end-handle area allowed extension and reversal, but a wrap-content TextView inside a separate ScrollView prevented native edge scrolling. The production reader now gives its bounded text surface its own viewport. The guarded test then passed extension, reversal, autoscroll, one-record exact quote updates, Undo, Speed/return, review source/share return and both swipe directions (`native-viewport-review-tcl-19.log`, 76.176 seconds). Earlier attempts are retained in `reader-handles-tcl-13.log`, `reader-handles-tcl-16.log`, `reader-handles-review-tcl-17.log`, and `native-viewport-tcl-18.log`; the latter reached the review feed after successful viewport checks but its one-scroll test helper could not reach the newest synthetic quote. The helper now searches with bounded scrolling. No failed attempt is counted as a full-flow pass.

A single large-article run passed (`native-large-viewport-tcl-20.log`, 56.427 seconds): 157,939 canonical characters / 166,939 UTF-8 bytes, 5,006 rendered lines, six normal finger scrolls and an advancing semantic cursor. Opening-to-ready took 1,160 ms including test tap waits. FrameMetrics p95 was 9.41 ms; six of 390 frames exceeded 100 ms, maximum 303.97 ms. gfxinfo independently reported p95 9 ms and p99 150 ms. PSS rose from 254,066 to 275,019 KiB in the isolated debug/instrumented process. This single run is not release-build benchmarking or a leak assessment; the slow frames remain a performance limitation. Raw Perfetto trace stays in ignored artifacts; the JSON, gfxinfo and meminfo summaries are under `evidence/beta/resumed/reader-large-*20*`.

On 2026-09-08 the owner requested minimal testing and no repetition of successful checks. Subsequent work reuses scoped passing evidence and performs only still-unverified scenarios. The original repeated 20/50-count campaigns are not claimed complete.

Process-death restoration, remaining theme/accessibility and TTS/lifecycle checks, available provider acceptance and focused end-to-end recovery checks remain open. No final release or reliability acceptance is claimed. The main app and paired owner profile retain their data and keys; destructive faults belong only in the isolated QA app/profile.

## Final focused checks

TTS/theme lifecycle passed (`remaining-lifecycle-tcl-23.log`), followed by one settled system-bar check (`system-bars-tcl-24.log`). Final normal APK/test installation and one read-only integrity test passed with byte-identical owner storage files across installation. Controlled delivery/lost-receipt recovery and the explicit Retry fix are reported in BETA_TEST_REPORT.md. No successful broad tests were repeated. Original count-based campaigns and other unmeasured matrices remain unclaimed.
