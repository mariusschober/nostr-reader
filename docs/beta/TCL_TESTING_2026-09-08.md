# TCL testing resumed — 2026-09-08

Testing resumed at the owner's request after the pre-campaign pause. This is an interim report, not beta acceptance.

## Bugs found and corrected

- Ordinary article and RSVP rendering failed with `offset out of bounds`. Inside the grapheme iterator's `apply`, `text` resolved to the iterator's empty text instead of the supplied string. Explicit iterator initialization fixes this. Two regression cases failed before the fix; all three new projection cases pass afterward. RSVP error/loading states also now have a readable themed surface and Back action.
- Native precomputed text produced incorrect horizontal selection positions on the TCL with mixed heading/body styles. The same `deliberate` offset was reported at x=955.84 with precomputed text and x=591.97 with standard native layout. Standard layout selected range 44:54 and saved the exact quote. The selectable view now uses ordinary native layout. Large-part layout performance remains to be measured; background precomputation is no longer claimed.
- Native article drawing is clipped to its Compose viewport so content cannot paint under the bottom controls.

## Evidence

- Android unit tests: **PASS**, 110 tests, no failures/errors/skips (`native-layout-fix-build.log`).
- Production reader UI in isolated `com.reader.app.qa` on physical TCL: **PASS**, native long-press selection saves exactly one matching quote, Undo removes it, Speed opens paused without the rendering error, and Exit returns to the reader (`reader-flow-tcl-native-fix.log`). An earlier standard-layout comparison also passed.
- Physical database migration, large persisted content/reopen, and gzip tests: **PASS**, seven tests (`tcl-storage-migration-tests.log`). These are isolated synthetic tests, not Chrome-to-phone delivery proof.
- Main app update: **installed with data retained**. Startup visibly shows the existing library. No owner highlights were created by the synthetic tests.
- Installed APK SHA-256: `9eae5e90e5f81d060d88b51ce43c12bc6fab0af0dabd1dab99382fed1ebcf19b`.
- Local APK: `artifacts/beta-work/testing-resume/owner-fix-2/reader.apk`. Screenshots stay in ignored artifacts because some include owner library titles or a floating chat overlay.

Logs are under `evidence/beta/resumed/`. Early selection failures included a floating overlay hit at the erroneous coordinate. A timing-only test adjustment did not resolve the mismatch; the standard-layout comparison did.

## Still pending

Handle extension/reversal, review/source/share flows, themes and accessibility, large-part rendering performance, TTS and lifecycle behavior, packaged Chrome permission/provider acceptance, and final exact-candidate delivery/fault campaigns remain open. No final release or reliability acceptance is claimed. The main app and paired owner profile must retain their data and keys; destructive fault injection belongs in the isolated QA app/profile.
