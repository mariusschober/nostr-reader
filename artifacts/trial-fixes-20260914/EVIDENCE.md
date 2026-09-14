# Trial-fix pass evidence - 14 September 2026

Source frozen at `0ca22ac` on `codex/reliability-finalize` (documentation
baseline `e91fdf4`). Implementation packages A-E were authored earlier in this
pass; `0ca22ac` repaired the one behavioural deviation the S23 walkthrough
found, so the frozen source is `0ca22ac`, not `d58067f`. This record covers the
freeze gates, the TCL installation and owner integrity, the NXTPAPER
appearance checks and the device verification of the repair.

## Artifacts

| Artifact | SHA-256 | Notes |
|---|---|---|
| `reader-debug-0ca22ac.apk` | `003b4863a61aec09d7d4fbc2b4fad82cece9d224ebc89b180834cd77b7d2b0de` | Normal `com.reader.app`; installed in place on TCL `ZXKRS4VKGQ8PWGEQ`. |
| `reader-qa-0ca22ac.apk` | `506d77d06e8f4f4046105857e703616290c11ed5f48afc6d7456b2b27eacda9f` | QA `com.reader.app.qa`; installed on the TCL for the NXTPAPER check. |

Both APKs were rebuilt from `0ca22ac` after the freeze and are byte-identical to
the archived files, so the archive is the frozen source. APK binaries are not
tracked in Git.

Signing certificate (both packages):
`3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`.

## Host freeze gates - PASS

| Gate | Result |
|---|---|
| `:app:lintDebug` | PASS - 0 errors, 42 warnings. |
| `:app:testDebugUnitTest` | PASS - 268 tests, 0 failures, 0 errors. |
| `:app:assembleDebug` (normal) | PASS - package `com.reader.app`. |
| `:app:assembleDebug -PreaderQa=true` (QA) | PASS - package `com.reader.app.qa`. |
| `:app:verify16KbAlignment` | PASS - all native libraries 16 KiB aligned. |

Re-run on the frozen source this pass. The earlier `d58067f` lint repair (one
`WrongConstant`, eighteen `UnsafeOptInUsageError`) still holds at `0ca22ac`.

## TCL installation - PASS

- TCL `ZXKRS4VKGQ8PWGEQ` (T807D), Android 16. The previously installed
  `com.reader.app` was the 11 September build, pulled hash
  `d3bdcdae7dc1dae525d5b04805fe31aaf331eb4cb98420df8163dd8278ea0236`, which
  matches the brief.
- Installed `reader-debug-0ca22ac.apk` in place with `adb install -r`; never
  uninstalled or cleared.
- The pulled-back `base.apk` is byte-identical to the archive
  (`003b4863...`).
- Launched once and left usable; `topResumedActivity` was
  `com.reader.app/.ui.MainActivity` and the crash buffer held no FATAL
  EXCEPTION.

### Owner integrity

`scripts/qa-owner-integrity.py ZXKRS4VKGQ8PWGEQ {before,after} tcl-0ca22ac-`
(`evidence/focused-20260910/owner-tcl-0ca22ac-{before,after}.json`). Before the
install: 6 articles, 71 highlights, 2 labels, 1 channel.

- Room schema v13 before and after; all eight compared tables byte-identical:
  `documents` 6, `document_content` 7, `highlights` 71, `labels` 2,
  `document_labels` 2, `channels` 1, `review_state` 1, `reading_days` 1.
- Preferences changed only by the specified one-time migration: the DataStore
  file gained `displayMode = STANDARD` (333 to 360 bytes). Decoding the current
  file shows `displayMode` as the only new key; `themeMode = SYSTEM`,
  `welcomeShown`, `highlightCoachSeen`, `font`, `fontSize`, `margin`,
  `lineSpacing`, `sort`, `age`, `boldText` and `tts` are all retained. No
  preference was reset. The previous build predates `displayMode`, so this is
  the documented materialization, not a data change.
- The encrypted key file `shared_prefs/reader_keys.xml` is present and its
  SHA-256 is unchanged before and after:
  `c076b845a4eab1d06b2797df4c946c54a59c396b90d72aab8df59118b4596203`. Key
  contents were never exported; only a stream digest was taken. The owner had
  1 channel before and after.

## NXTPAPER appearance check - PASS (TCL QA package)

Run on the isolated `com.reader.app.qa` package installed on the TCL, with
**Display: E-ink & NXTPAPER**. Screenshots are in this directory.

| Screenshot | Observation |
|---|---|
| `tcl-qa-1-settings-monochrome-dark.png` | Settings shows **App theme: Follow system / Light / Dark** with **Display: Standard / E-ink & NXTPAPER** directly below it; the selected Dark and E-ink controls are inverse-filled; the status and navigation regions are black with no light strip. |
| `tcl-qa-2-article-monochrome-dark.png` | Monochrome-dark article: black ground, white text, gray secondary, thin outlined reading actions. |
| `tcl-qa-3-focus-monochrome-dark.png` | Fullscreen focus in monochrome dark: text runs to the top edge with no top bar, footer or action row, and **no light outer border**. This settles the owner-reported dark-mode edge symptom and is the view the S23 could not measure. |
| `tcl-qa-4-pen-dock-monochrome-dark.png` | Highlighting dock: a compact bottom capsule with the current colour, the focus toggle and Done. |
| `tcl-qa-5-highlight-settled-monochrome-dark.png` | A single hold-drag-release saved exactly one highlight, drawn with a dark fill and a white patterned edge, with a quiet "Undo highlight" offered. |
| `tcl-qa-6-monochrome-light-settings.png`, `tcl-qa-7-monochrome-light-library.png`, `tcl-qa-9-focus-monochrome-light.png` | Monochrome light on the NXTPAPER panel: white ground, black text, dark-gray secondary, inverse-selected controls and a white status bar with dark icons. `tcl-qa-9` is a **focused** light view: no top bar, footer or action row and no dark strip, with both stored highlights drawn as a gray fill and dark edge. |
| `tcl-qa-8-pen-control-second-highlight.png` | The control gesture from the cancel test below produced a second highlight. |

## Pen-cancel repair - PASS (TCL QA package, source `0ca22ac`)

The S23 walkthrough recorded that pressing Back while a pen highlight drag was
still held did not cancel it. `0ca22ac` resets the pen gesture session inside
`NativeArticleView.clearSelection()`. Verified on the TCL with injected
`motionevent` sequences and a read of the QA Room rows:

- Baseline `highlights` = 1.
- Cancel gesture `DOWN`, hold, `MOVE`, `keyevent 4` (Back), `MOVE`, `UP`: the
  row count stayed at 1 and the app remained in the reader in highlighting
  mode, so Back cancelled the gesture instead of navigating away.
- Control, the same sequence without the Back press, on a different passage:
  the row count went 1 to 2. The harness therefore commits when it should, so
  the cancel result is not a no-op artefact.

## One gesture, one row - PASS (TCL QA package)

A `draganddrop` hold-drag-release over a wrapped line produced exactly one new
`highlights` row (0 to 1) with no duplicate, and the dock offered a single
quiet "Undo highlight".

## Deviations

1. **Back during a live pen gesture** - FIXED at `0ca22ac` and verified on the
   TCL above. Supersedes the "not repaired" note in the earlier S23 record.
2. **Native selection remnant (cosmetic)** - after a settled highlight the
   native selection handles can remain painted until the next gesture replaces
   them. There is no data effect; the stored row and quoted range are correct.
   Reproduced on the TCL as well as the S23. Not repaired in this pass.
3. **Appearance/Done header (minor)** - the header is pinned while the sheet is
   partially open and scrolls away once it is fully expanded; the drag handle
   remains for dismissal. Unchanged from the S23 record.

## NOT MEASURED / BLOCKED

- `ReaderFlowInstrumentedTest` remains opt-in; its residual failure is the
  Android 16 / One UI 8 share-sheet layout assumption, not a product defect.
  It is not counted as a product check.
- Sustained listening, electrophoretic ghosting and battery remain owner-trial
  observations rather than device-verified properties.
- Physical e-ink refresh quality cannot be established from a screenshot.

## Left state

- TCL owner package: normal `com.reader.app` from `0ca22ac` installed in place,
  launched once, owner data intact. QA package `com.reader.app.qa` is also
  installed (isolated, holding the built-in sample article plus two test
  highlights) with its display restored to E-ink & NXTPAPER and App theme
  Dark. Remove it with
  `adb -s ZXKRS4VKGQ8PWGEQ uninstall com.reader.app.qa` if unwanted.
- The four pre-existing untracked captures
  `evidence/polish-20260911/{21-feed,22-feed,22-reader-tab,23-archive}.xml` are
  untouched.
