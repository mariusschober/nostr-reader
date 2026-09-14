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
