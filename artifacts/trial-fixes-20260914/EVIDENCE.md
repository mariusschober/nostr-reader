# Trial-fix pass evidence — 14 September 2026

Source frozen at `d58067f` on `codex/reliability-finalize` (documentation
baseline `e91fdf4`). Implementation packages A–E were authored earlier in this
pass; this record is the freeze, installation and preliminary-device evidence.

## Artifacts

| Artifact | SHA-256 | Notes |
|---|---|---|
| `reader-debug-d58067f.apk` | `7ff086955e9fc4bc63e3668d6a293818cbea910c8f90ee93a651119fb108e61f` | Normal package `com.reader.app`, installed on S23 `R3CW404GVBL`. |
| `reader-qa-d58067f.apk` | `faeb8c9f8b162b2517f2b0ee28c2d4c9e491545f1f74f4113884bccf21568322` | QA package `com.reader.app.qa`, installed on S23 for the preliminary check. |

`reader-qa-debug.apk` and `reader-qa-debug-androidTest.apk` (13:16) were built
from the intermediate working tree before `d58067f`; they are superseded by the
two artifacts above and were not used for the checks below.

Signing certificate (both packages): `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`.

## Host freeze gates — PASS

| Gate | Result |
|---|---|
| `:app:lintDebug` | PASS — 0 errors (was 19: one `WrongConstant` + eighteen `UnsafeOptInUsageError`). |
| `:app:testDebugUnitTest` | PASS — 268 tests, 0 failures, 0 errors. |
| `:app:assembleDebug` (normal) | PASS — package `com.reader.app`. |
| `:app:assembleDebug -PreaderQa=true` (QA) | PASS — package `com.reader.app.qa`. |
| `:app:verify16KbAlignment` | PASS — all native libraries 16 KiB aligned. |

The lint fix replaced `SessionResult.RESULT_ERROR_NOT_SUPPORTED` with
`SessionError.ERROR_NOT_SUPPORTED` (the name in the `@SessionResult.Code`
allow-list; identical code) and moved `TtsPlaybackClient` from `@UnstableApi`
(which propagated the opt-in requirement to all eighteen MainActivity call
sites) to an internal `@androidx.annotation.OptIn(UnstableApi::class)`.

## Normal installation — PASS

- S23 Ultra `R3CW404GVBL` (SM-S918B), Android 16 / SDK 36, build
  `BP4A.251205.006.S918BXXSAFZH3`.
- Installed in place with `adb install -r`; never uninstalled or cleared.
- Pulled-back `base.apk` is byte-identical to `reader-debug-d58067f.apk`
  (`cmp` clean, identical SHA-256).
- Launched once and left usable; `topResumedActivity` was
  `com.reader.app/.ui.MainActivity`, no FATAL EXCEPTION.

### Owner integrity

`scripts/qa-owner-integrity.py R3CW404GVBL {before,after} d58067f-`
(`evidence/focused-20260910/owner-d58067f-{before,after}.json`):

- Room schema v13, all eight compared tables byte-identical before/after:
  `documents` (1), `document_content` (1), `highlights` (0), `labels` (0),
  `document_labels` (0), `channels` (0), `review_state` (0), `reading_days` (0).
- Preferences changed only by the specified one-time display migration: the
  DataStore file gained a materialized `displayMode = STANDARD` alongside the
  preserved `themeMode = SYSTEM`, `welcomeShown` and `highlightCoachSeen`. No
  preference was reset.
- Encrypted key file `shared_prefs/reader_keys.xml` is absent before and after
  (0 channels, so no channel key was ever sealed); no key contents exist to
  export. `shared_prefs/` holds only `android.app.ActivityThread.IDS.xml`.

## S23 preliminary appearance check — PASS (preliminary)

Run on the isolated QA package; the QA preferences were restored
byte-identically afterwards (`themeMode = LIGHT`, `displayMode = STANDARD`).

| Screenshot | Observation |
|---|---|
| `s23-qa-2-settings-display-control.png` | Settings shows **Display: Standard · E-ink & NXTPAPER** immediately below **App theme: Follow system · Light · Dark**. |
| `s23-qa-3-monochrome-light.png` | Monochrome light: white ground, black text/edges, dark-gray secondary, inverse-filled selected controls, thin outlines. |
| `s23-qa-4-monochrome-dark-reader.png` | Monochrome dark library list. |
| `s23-qa-5-monochrome-dark-article-highlight.png` | Monochrome dark article: black ground, white text, saved highlight filled dark with a white patterned edge — legible. |
| `s23-normal-launch.png` | Normal `com.reader.app` after install: dark app chrome, owner Inbox clear (the single owner article is archived), bottom navigation intact. |

This is preliminary: the S23 is an OLED panel and **does not** establish
NXTPAPER/e-ink appearance.

## BLOCKED / NOT MEASURED

- **TCL `ZXKRS4VKGQ8PWGEQ` is not connected**, so the TCL installation hash and
  the NXTPAPER physical-appearance checks are **BLOCKED** (not run). The brief's
  QA walkthrough (pen gesture, article-scoped Review, labels, background audio
  continuation) is deferred to the TCL pass.
- `ReaderFlowInstrumentedTest` remains opt-in and its residual failure is the
  Android 16 / One UI 8 share-sheet layout assumption, not a product defect; it
  is **NOT MEASURED** as a product check on this device.

## S23 walkthrough — pen, appearance and ordinary selection

Added after the freeze commit, from a later run on the same frozen build
(`d58067f`, QA package `com.reader.app.qa`) on S23 Ultra `R3CW404GVBL`
(Android 16 / SDK 36). The QA database was read through `run-as` together with
its WAL before and after each check, so row counts and committed ranges are
measured, not inferred. The QA preferences were left at their prior values
(App theme Light, Display Standard, Text size 19, Spacing Comfort, Margins
Default, Font Newsreader, Bold text off).

| Brief row | Result | Evidence |
|---|---|---|
| Pen: hold/drag/release settles automatically, no native menu, exactly one row | **PASS** | Long-press + drag across a wrapped line persisted exactly one new row: total `highlights` 9 → 10, document `f051e679…` 1 → 2, quote ". A saved quote remains a single record while its handles move", block `b2` offsets 13 → 75, colour `YELLOW`. No native Copy/Share menu appeared in pen mode; a quiet "Undo highlight" was offered. `s23-qa-6-pen-dock.png`, `s23-qa-7-pen-settled-highlight.png` |
| Pen: Undo reverses the gesture | **PASS** | "Undo highlight" returned the total to 9 and document `f051e679…` to 1, leaving the pre-existing "deliberate" highlight intact. |
| Pen: short drag still scrolls | **PASS** | A 300 ms drag scrolled the article (footer 2% → 5%) and added no row (total stayed 9). |
| Pen: cancellation saves nothing | **PASS** | `ACTION_CANCEL` after long-press and drag left the total at 9. |
| Pen: cancellation visual | minor deviation | The native selection highlight is not cleared by `ACTION_CANCEL` and stayed painted after leaving pen mode (`s23-qa-8-pen-off-residual-selection.png`); it cleared as soon as the next selection replaced it. Cosmetic only, no data effect. |
| Ordinary mode: native handles and actions | **PASS** | Outside pen mode a long-press produced native handles and the system menu "Highlight · Kopieren · Übersetzen · ⋮" (device locale German). `s23-qa-9-ordinary-selection-actions.png` |
| Appearance sheet: partially open, one hierarchy, no expand button | **PASS** | It opens partially expanded with a pinned Appearance/Done header and a usable passage visible; pulling up reveals Text size, Spacing, App theme, Display, Background (Standard only), Font, Margins and Bold text with no "More options" control. `s23-qa-10-appearance-partial.png`, `s23-qa-11-appearance-expanded-bottom.png` |
| Appearance sheet: pinned header | minor deviation | The Appearance/Done header is pinned while the sheet is partially open but scrolls away once it is fully expanded; the drag handle remains for dismissal. |
| Appearance sheet: change applies immediately, passage preserved | **PASS** | "+" changed "Text size · 19" → "Text size · 20" with no confirmation step and "−" restored 19; closing the sheet left the footer at 5% (same passage). |
| Back cancels an active selection gesture | **FAIL (deviation, not repaired)** | With the finger still down, Back cleared the native selection but the release still committed a highlight: total 9 → 10, quote "remains exact." (document `f051e679…`, offsets 93–107). Reproduced with a single-process 3 s drag and Back at 1.6 s, and contrasted with a run that held the finger down without releasing (total stayed 9), which shows the release is the commit point. Cause: `PreparedReaderScreen.leave()` calls `NativeArticleView.clearSelection()`, which clears the native selection but not the pen gesture session; later MOVE events re-establish the native selection, so `settlePenGesture()` commits on release. `setPenMode(false)` and the `ACTION_CANCEL` branch both reset that session (`selectionSession`, `sessionRange`, `settledSession`); `clearSelection()` does not. **Not repaired in this pass** — changing the frozen source would invalidate the archived APK hashes and the S23 install/owner-integrity evidence, so it needs a deliberate re-freeze. |

**NOT MEASURED on the S23:** entering/leaving fullscreen focus and a *focused*
monochrome-dark capture. The archived monochrome set (light, dark reader, dark
article highlight) was captured earlier in the pass and re-checked here, but it
contains no focused view, so the owner-reported light outer border in dark mode
is still not device-verified. TCL `ZXKRS4VKGQ8PWGEQ` remains BLOCKED.
