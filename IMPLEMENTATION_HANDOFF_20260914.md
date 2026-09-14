# Trial-fix pass handoff - 14 September 2026

Executes [NEXT_AGENT_PROMPT_DEEPSEEK_TRIAL_FIXES_20260914.md](NEXT_AGENT_PROMPT_DEEPSEEK_TRIAL_FIXES_20260914.md)
on `codex/reliability-finalize`, starting from documentation commit `e91fdf4`.
All work is local; nothing was pushed, merged or released.

## Source

Freeze commit **`0ca22ac`** (`fix(android): Back cancels an active pen
highlight gesture`), on top of the implementation packages, the
instrumented-fixture alignment and the lint fix:

```
0ca22ac fix(android): Back cancels an active pen highlight gesture
35cf0c6 docs: record the S23 walkthrough and two honest deviations
31d9c85 docs: record the trial-fix freeze, install and focused evidence
d58067f fix(android): satisfy lint on the service-owned TTS wiring
8515d15 test(android): align instrumented fixtures with the trial-fix contracts
b2fb888 test(android): align pen, appearance and speech device tests with the trial fixes
e90e7f7 feat(android): service-owned background listening
d03f55e feat(android): label colours and compact library badges
1f2654d feat(android): article highlight list and article-scoped review
26e4fc3 feat(android): focus-compatible pen dock and release-to-save selection
fa13458 feat(android): separate display mode with monochrome light/dark and one window-appearance source
```

`0ca22ac` is the frozen source because it repairs the one behavioural deviation
the S23 walkthrough found; the earlier freeze at `d58067f` is superseded.

Implemented behaviour (packages A-E of the brief):

- **A - appearance.** A separate app-wide **Display** preference (Standard /
  E-ink & NXTPAPER) sits below **App theme** (Follow system / Light / Dark) and
  is also exposed in Reading appearance. Legacy `ThemeMode.EINK` decodes to
  monochrome plus System and is materialized once. Monochrome light is white
  ground, black text and dark-gray secondary; monochrome dark is black ground,
  white text and `#CCCCCC` secondary, with thin outlines and inverse selected
  states instead of hue or elevation. Dark highlight fills
  (`#202020 / #323232 / #484848 / #606060`) keep white quote text and white
  patterned edges. One Activity-owned window-appearance source drives
  decor/root and system-bar painting, and the appearance sheet is a
  partially-open draggable list with the 500dp ceiling removed.
- **B - highlighting.** Focus and continuous highlighting are independent; the
  pen control is a compact bottom capsule (current colour, focus toggle, Done).
  Hold-drag-release commits and settles automatically (a long-press released
  without extending saves the word), with a commit/acknowledgement bridge, a
  rotate-once ownership handoff, a quiet retry/cancel path on failure, and
  Back cancelling an active gesture.
- **C - article highlights and review.** `ArticleHighlights(documentId)` and
  `ArticleReview(documentId, startHighlightId?)` routes, passage-ordered quote
  cards, an article-scoped Review round persisted in a versioned JSON envelope
  inside the existing `review_state` storage (legacy bare `ReviewState` decodes
  losslessly), and one credit per advance. The global round is untouched.
- **D - labels.** A Neutral/Red/Orange/Green/Blue palette stored by stable
  label ID in a dedicated preferences map, colour editing in Manage labels
  (action renamed **Save label**), destination colour preserved on merge, and
  compact `#label` badges (at most two names plus `+N`) on library, search and
  archive rows.
- **E - background listening.** `TtsPlaybackService` owns the engine,
  controller, Media3 player/session and playback scope; the Activity observes a
  compact playback bus and sends commands through a MediaController bridge.
  Playback continues through navigation, Home, lock and Recents dismissal, with
  a now-playing row and real media-notification controls.

## Focused checks actually performed

**PASS (host, frozen `0ca22ac`, re-run this pass):** `:app:lintDebug` 0 errors
(42 warnings); `:app:testDebugUnitTest` 268 tests, 0 failures, 0 errors;
normal `assembleDebug` and QA `assembleDebug -PreaderQa=true`;
`:app:verify16KbAlignment` reports every native library 16 KiB aligned. Both
APKs were rebuilt from `0ca22ac` and are byte-identical to the archived files.

**PASS (S23 Ultra `R3CW404GVBL`, frozen `d58067f`).** Recorded earlier in this
pass: normal `com.reader.app` installed in place, pulled-back APK
byte-identical, owner data preserved across all eight tables with only the
one-time `displayMode = STANDARD` materialization, and a full QA walkthrough
(pen gesture, Undo, cancellation, ordinary selection, appearance sheet,
article highlights and scoped Review, label colours and badges, background
listening through Home, now-playing row). That walkthrough is what found the
Back-during-gesture deviation. Its screenshots and hashes remain in this
directory; note that the S23 was an OLED panel and could not establish
NXTPAPER appearance or a focused dark view.

**PASS (TCL installation, frozen `0ca22ac`).** TCL `ZXKRS4VKGQ8PWGEQ` (T807D),
Android 16. Its previous install was the 11 September build (`d3bdcdae...`).
`reader-debug-0ca22ac.apk` was installed in place (never uninstalled or
cleared), the pulled-back `base.apk` matches the archive
(`003b4863...`), and the app launched once with no fatal exception.

**PASS (TCL owner integrity).** All eight Room tables byte-identical before and
after (6 articles, 71 highlights, 2 labels, 1 channel, review state, reading
days), schema v13, and the encrypted key-file digest unchanged
(`c076b845...`). Preferences changed only by the specified one-time migration:
the file gained `displayMode = STANDARD` (333 to 360 bytes) and nothing was
reset. Detail in `evidence/focused-20260910/owner-tcl-0ca22ac-{before,after}.json`.

**PASS (NXTPAPER appearance, TCL QA package).** Monochrome dark on the e-ink
panel: the Settings screen carries **App theme** and **Display** with inverse
selected states and black system regions; the article renders black ground,
white text, gray secondary; the pen dock is the compact capsule with a dark
fill and white patterned highlight edge; and **fullscreen focus in monochrome
dark has no light outer border**, which is the owner-reported symptom and the
view the S23 could not measure. Monochrome light was captured too, including a
**focused** light view: white ground, black text, inverse-selected controls and
no dark strip. Screenshots are the
`tcl-qa-*.png` files in this directory.

**PASS (pen-cancel repair, TCL QA package).** With a baseline of 1 highlight,
the sequence DOWN, hold, MOVE, Back, MOVE, UP left the count at 1 and kept the
app in the reader in highlighting mode, so Back cancelled the gesture rather
than navigating. The control, the same sequence without Back on another
passage, moved the count from 1 to 2, confirming the harness commits when it
should.

**NOT MEASURED:** `ReaderFlowInstrumentedTest`'s residual failure is the
Android 16 / One UI 8 share-sheet layout assumption, not a product defect.
Sustained listening, electrophoretic ghosting and battery remain owner-trial
observations. Physical e-ink refresh quality cannot be proven from a
screenshot.

## Deviations

1. **Back during a live pen gesture** - FIXED at `0ca22ac`, verified on the TCL
   (see above). This supersedes the "not repaired in this pass" note in the
   earlier S23 record.
2. **Native selection remnant (cosmetic)** - after a settled highlight the
   native selection handles can stay painted until the next gesture replaces
   them; no data effect. Reproduced on the TCL and the S23. Not repaired.
3. **Appearance/Done header (minor)** - pinned while the sheet is partially
   open, scrolls away once fully expanded, drag handle remains. Unchanged.

## Artifacts

| Artifact | SHA-256 |
|---|---|
| `reader-debug-0ca22ac.apk` | `003b4863a61aec09d7d4fbc2b4fad82cece9d224ebc89b180834cd77b7d2b0de` |
| `reader-qa-0ca22ac.apk` | `506d77d06e8f4f4046105857e703616290c11ed5f48afc6d7456b2b27eacda9f` |

Both under `artifacts/trial-fixes-20260914/`; APK binaries are not tracked in
Git. Signing certificate (both packages):
`3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`.

## Documentation

`START_HERE.md` and `CONTINUE.md` carry a new dated section pointing at this
handoff; `docs/COPY_DECK.md` already documents every string this pass
introduced (it was updated at `31d9c85`) and the `0ca22ac` repair changes
behaviour only, so no copy string changed and the deck is unchanged by the
repair. The copy deck already states that a cancelled gesture saves nothing,
which the repair now makes true.

## Remaining work / next input

The branch was pushed to `origin/codex/reliability-finalize` at the owner's
request on 14 September 2026. There is no release and no merged pull request.

1. **Open defect: the native selection lingers after a settled pen gesture.**
   The brief's required outcome is "One gesture, one saved highlight, no
   lingering selection". The data is correct (one row, one range, no
   duplicates), but on the TCL the platform's selection handles stay painted in
   highlighting mode after a successful save, until the next gesture or until
   highlighting is left. `NativeArticleView.settlePenGesture()` already intends
   to clear on the acknowledged commit:

   ```kotlin
   onSettle(session, range) { ok ->
     post {
       if (settledSession != session) return@post
       settledSession = null
       if (ok && selectionSession == session) clearSelection()
     }
   }
   ```

   The `selectionSession == session` guard is the weak point: the platform can
   re-mint or null that session while the finger is up, so the clear is
   skipped. Planned minimal fix: guard on a gesture generation counter
   (incremented in the `ACTION_DOWN` branch) instead of session identity, and
   drop the idle cursor/focus after the clear (`clearSelection();
   clearIdleTextCursor()`) so the Editor cannot restore handles. The pinned
   invariant must survive: `PenSettlementInstrumentedTest.staleAckCannotClearANewerGesture`
   claims the newer selection with a real `ACTION_DOWN`, so a generation
   counter keeps it passing. A half-finished edit of this kind was made and
   reverted in this pass, so nothing partial is committed and the frozen source
   still matches the installed APK.
2. **Cosmetic:** the Appearance/Done header is pinned while the sheet is
   partially open and scrolls away at full expansion; the drag handle remains
   for dismissal.
3. **Test fixture:** `ReaderFlowInstrumentedTest` fails only at its Android 16
   share-sheet step, an OS-version layout assumption rather than a product
   defect. Making that step version-aware would let the opt-in flow run
   unattended.
4. **Owner trial:** sustained reading, listening and e-ink comfort on the TCL.
   Not a code task.

No release, merge or production-data change occurred.

Continuation prompt for a fresh session:
[NEXT_AGENT_PROMPT_TRIAL_FIXES_CONTINUATION_20260914.md](NEXT_AGENT_PROMPT_TRIAL_FIXES_CONTINUATION_20260914.md).
