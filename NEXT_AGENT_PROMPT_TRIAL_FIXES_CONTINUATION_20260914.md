# Continuation prompt - trial-fix pass, one open defect (14 September 2026)

Read this first, then [IMPLEMENTATION_HANDOFF_20260914.md](IMPLEMENTATION_HANDOFF_20260914.md)
and [artifacts/trial-fixes-20260914/EVIDENCE.md](artifacts/trial-fixes-20260914/EVIDENCE.md).
[START_HERE.md](START_HERE.md) and [CONTINUE.md](CONTINUE.md) carry the same state.

## State

- Branch `codex/reliability-finalize`, pushed to `origin/codex/reliability-finalize`
  at the owner's request. Tip is the docs commit that adds this prompt.
- The brief [NEXT_AGENT_PROMPT_DEEPSEEK_TRIAL_FIXES_20260914.md](NEXT_AGENT_PROMPT_DEEPSEEK_TRIAL_FIXES_20260914.md)
  is implemented: packages A-E (separate display treatment with monochrome
  light/dark and one window-appearance source; focus-compatible hold/drag/release
  highlighting with a compact dock; article highlights plus article-scoped
  Review; label colours and compact badges; service-owned background listening)
  plus the lint fix and the pen-cancel repair.
- Frozen source for the installed build is `0ca22ac`. Host gates pass on it:
  `lintDebug` 0 errors, `testDebugUnitTest` 268 tests / 0 failures, normal and
  QA `assembleDebug`, `verify16KbAlignment` clean.
- TCL `ZXKRS4VKGQ8PWGEQ` (T807D, Android 16) has the normal owner package
  `com.reader.app` from `0ca22ac`, installed in place, launched once, owner data
  verified byte-identical across all eight Room tables with the key-file digest
  unchanged and only the one-time `displayMode = STANDARD` materialization.
  Installed/archived normal APK SHA-256:
  `003b4863a61aec09d7d4fbc2b4fad82cece9d224ebc89b180834cd77b7d2b0de`.
- The isolated QA package `com.reader.app.qa` is also installed on the TCL and
  holds the built-in sample article plus two test highlights.

## The one open defect

**The native selection lingers after a settled pen highlight.**

The brief's required outcome is "One gesture, one saved highlight, no lingering
selection". Data is correct (exactly one row, one half-open range, no
duplicate), but on the TCL the platform's selection handles stay painted after
a successful save while highlighting mode is active; they clear on the next
gesture or after leaving highlighting. The app's own monochrome edge drawing is
only an underline, so the handles are the platform selection, not decoration.

`NativeArticleView.settlePenGesture()` (in
`android/app/src/main/java/com/reader/app/ui/screens/NativeArticleView.kt`)
already tries to clear on the acknowledgement:

```kotlin
onSettle(session, range) { ok ->
  post {
    if (settledSession != session) return@post
    settledSession = null
    if (ok && selectionSession == session) clearSelection()
  }
}
```

The `selectionSession == session` guard is the weak point: the session can be
re-minted or nulled by the platform while the finger is up, so the clear is
skipped. A half-finished edit that added a gesture-generation counter was made
and then reverted so the pushed source matches the installed APK. Nothing
partial is committed.

### Planned fix (keep it this small)

1. Add `private var gestureSerial = 0` beside `gestureActive`, and increment it
   in the `ACTION_DOWN` branch of `dispatchTouchEvent` (next to
   `gestureActive = true`).
2. In `settlePenGesture()`, capture `val serial = gestureSerial` before calling
   `onSettle`, then clear on success only while no newer pointer gesture has
   begun: `if (ok && gestureSerial == serial) { clearSelection(); clearIdleTextCursor() }`.
   Dropping the idle cursor/focus stops the Editor from restoring handles.
3. Leave the `settledSession` bookkeeping and the `abandonPenGesture()` path
   untouched.

**Invariant to protect:** a stale acknowledgement must never clear a newer
gesture. `PenSettlementInstrumentedTest.staleAckCannotClearANewerGesture`
claims its newer selection with a real `ACTION_DOWN`, so a generation counter
keeps that test passing. Also re-run
`PenSettlementInstrumentedTest.cancelledPenGesturePersistsNothing` and
`HighlightSessionInstrumentedTest`.

### Verification after the fix (focused, no new campaigns)

1. `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`,
   `:app:assembleDebug -PreaderQa=true`, `:app:verify16KbAlignment`.
2. Build and install the QA test APK on the TCL, then run
   `PenSettlementInstrumentedTest` and `HighlightSessionInstrumentedTest`
   against `com.reader.app.qa` (see `scripts/qa-android.mjs` for the existing
   runner conventions; pass
   `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` so the QA
   package and its data survive).
3. On the TCL QA package, in highlighting mode, hold-drag-release once and
   confirm one new highlight row and **no lingering selection handles**; then
   confirm a second gesture still works and that Back mid-gesture still saves
   nothing (count unchanged) while the same gesture without Back saves one.
   `adb shell input draganddrop x1 y1 x2 y2 1200` and the `motionevent`
   DOWN/hold/MOVE/UP sequences are enough; read row counts from
   `databases/reader.db` through `run-as`.
   To seed QA content on a fresh install, launch the QA package and choose
   "Read a sample introduction"; the Settings screen then exposes **App theme**
   and **Display** for monochrome light/dark.
4. Rebuild both APKs, archive them under
   `artifacts/trial-fixes-20260914/` with new hashes and the source SHA, then
   reinstall the normal package in place on the TCL and re-verify the
   pulled-back hash and owner integrity as in `EVIDENCE.md`.
5. Update `EVIDENCE.md`, this handoff and the START_HERE/CONTINUE state lines;
   commit locally; push only if the owner asks.

## Guardrails

- Preserve owner data. Never uninstall or clear `com.reader.app`; install in
  place only. Use `com.reader.app.qa` for fixtures and destroyable data.
- Never export key contents; use the read-only digest approach in
  `scripts/qa-owner-integrity.py`.
- Keep the device tidy: force-stop the QA package and leave the owner's Reader
  in the foreground when finished.
- No release, tag or merged pull request unless the owner asks.
- Two smaller items are deliberately deferred and belong in the handoff, not an
  extra cycle: the Appearance/Done header scrolling away at full expansion, and
  the Android-16 share-sheet assumption in the opt-in `ReaderFlowInstrumentedTest`.

## Definition of done

The lingering-selection defect is fixed in source, verified on the TCL, the
normal APK is reinstalled with a matching hash and preserved owner data, and the
evidence and handoff name the new source commit and hashes. If the fix cannot be
made without weakening the stale-acknowledgement invariant, stop and report that
trade-off instead of shipping it.
