# Trial-fix pass handoff — 14 September 2026

Executes [NEXT_AGENT_PROMPT_DEEPSEEK_TRIAL_FIXES_20260914.md](NEXT_AGENT_PROMPT_DEEPSEEK_TRIAL_FIXES_20260914.md)
on `codex/reliability-finalize`, starting from documentation commit `e91fdf4`.
All work is local; nothing was pushed, merged or released.

## Source

Freeze commit **`d58067f`** (`fix(android): satisfy lint on the service-owned TTS wiring`),
on top of the implementation packages and the instrumented-fixture alignment:

```
d58067f fix(android): satisfy lint on the service-owned TTS wiring
8515d15 test(android): align instrumented fixtures with the trial-fix contracts
b2fb888 test(android): align pen, appearance and speech device tests with the trial fixes
e90e7f7 feat(android): service-owned background listening
d03f55e feat(android): label colours and compact library badges
1f2654d feat(android): highlight list and article-scoped review
26e4fc3 feat(android): focus-compatible pen dock and release-to-save selection
fa13458 feat(android): separate display mode with monochrome light/dark and one window-appearance source
```

Implemented behavior (packages A–E of the brief):

- **A — appearance.** A separate app-wide **Display** preference (Standard /
  E-ink & NXTPAPER) sits below **App theme** (Follow system / Light / Dark) and
  is also exposed in Reading appearance. Legacy `ThemeMode.EINK` decodes to
  monochrome + System and is materialized once. Monochrome light is white
  ground / black text / dark-gray secondary with inverse selected states;
  monochrome dark is black ground / white text / `#CCCCCC` secondary. Dark
  highlight fills (`#202020 / #323232 / #484848 / #606060`) keep white quote
  text and white patterned edges. One Activity-owned window-appearance source
  drives decor/root and system-bar painting, and the appearance sheet is a
  partially-open draggable list with the 500dp ceiling removed.
- **B — highlighting.** Focus and continuous highlighting are independent; the
  pen control is a compact bottom capsule (current colour, focus toggle, Done).
  Hold-drag-release commits and settles automatically (a long-press released
  without extending saves the word), with a commit/acknowledgement bridge, a
  rotate-once ownership handoff and a quiet retry/cancel path on failure.
- **C — article highlights and review.** `ArticleHighlights(documentId)` and
  `ArticleReview(documentId, startHighlightId?)` routes, passage-ordered quote
  cards, an article-scoped Review round persisted in a versioned JSON envelope
  inside the existing `review_state` storage (legacy bare `ReviewState` decodes
  losslessly), and one-credit-per-advance. The global round is untouched.
- **D — labels.** Neutral/Red/Orange/Green/Blue palette stored by stable label
  ID in a dedicated preferences map, colour editing in Manage labels (action
  renamed **Save label**), destination colour preserved on merge, and compact
  `#label` badges (≤2 names + `+N`) on library/search/archive rows.
- **E — background listening.** `TtsPlaybackService` owns the engine,
  controller, Media3 player/session and playback scope; the Activity observes a
  compact playback bus and sends commands through a MediaController bridge.
  Playback continues through navigation, Home, lock and Recents dismissal, with
  a now-playing row and real media-notification controls.
- **Lint fix (this turn).** Package E's media3 wiring produced one
  `WrongConstant` and eighteen `UnsafeOptInUsageError` findings, so the briefed
  freeze gate could not pass. `TtsPlaybackService` now returns
  `SessionError.ERROR_NOT_SUPPORTED` (the name in the `@SessionResult.Code`
  allow-list) and `TtsPlaybackClient` opts in internally via
  `@androidx.annotation.OptIn(UnstableApi::class)` instead of propagating
  `@UnstableApi` to every MainActivity call site.

## Focused checks actually performed

**PASS (host, frozen `d58067f`):** `:app:lintDebug` 0 errors;
`:app:testDebugUnitTest` 268 tests / 0 failures / 0 errors; normal
`assembleDebug` and QA `assembleDebug -PreaderQa=true`; `:app:verify16KbAlignment`
all native libraries 16 KiB aligned.

**PASS (installation, S23 Ultra `R3CW404GVBL`, SM-S918B, Android 16 / SDK 36):**
normal `com.reader.app` installed in place (never uninstalled/cleared); the
pulled-back `base.apk` is byte-identical to the archived build; launched once
and left usable with no fatal exception. Owner integrity: schema v13, all eight
Room tables byte-identical before/after; preferences changed only by the
specified one-time `displayMode = STANDARD` materialization; the encrypted key
file is absent before and after (0 channels).

**PASS (preliminary, QA package on the S23):** the new **Display** control is
present below App theme; monochrome light and monochrome dark both render with
the specified grounds, text, inverse selected states and a legible dark
highlight (dark fill, white patterned edge). QA preferences were restored
byte-identically. Screenshots and the artifact hashes are in
`artifacts/trial-fixes-20260914/` (see `EVIDENCE.md`).

**BLOCKED:** the TCL `ZXKRS4VKGQ8PWGEQ` is not connected, so the TCL
installation hash and the NXTPAPER physical-appearance checks were not run, and
the brief's QA walkthrough (pen gesture, article-scoped Review, labels,
background-audio continuation) is deferred to that device.

**NOT MEASURED:** `ReaderFlowInstrumentedTest`'s residual failure is the
Android 16 / One UI 8 share-sheet layout assumption, not a product defect; it is
not counted as a product check here. Sustained listening, electrophoretic
ghosting and battery remain owner-trial observations.

## Artifacts

| Artifact | SHA-256 |
|---|---|
| `reader-debug-d58067f.apk` | `7ff086955e9fc4bc63e3668d6a293818cbea910c8f90ee93a651119fb108e61f` |
| `reader-qa-d58067f.apk` | `faeb8c9f8b162b2517f2b0ee28c2d4c9e491545f1f74f4113884bccf21568322` |

Both under `artifacts/trial-fixes-20260914/`; APK binaries are not tracked in
Git. Signing certificate (both): `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`.

## Remaining work / next input

1. When the TCL is connected, install `reader-debug-d58067f.apk` in place, run
   the brief's QA walkthrough on `com.reader.app.qa`, verify the pulled-back TCL
   hash and the NXTPAPER appearance, and confirm owner data before/after.
2. Optional ideas noticed but not implemented: none required by the brief.

No push, merge, release or production-data change occurred.
