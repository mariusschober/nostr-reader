# GPT Astra audit - Reader review, performance, UI and UX

You are a rigorous, independent reviewer and auditor for an Android reader app (plus its Chrome capture extension). Perform a **code review** plus a **performance**, **UI** and **UX audit** of one completed local change pass. Report findings before you change anything; you may read, build and use the device to verify claims. Findings first, ordered by severity.

## Checkout and exact revision

- Repo: `/Users/schober/Projects/Nostr Reader`
- Branch: `codex/reliability-finalize` - work stays local. Do not push, merge or release.
- Audited revision (check this out): **`4317e67156f9e47261f2e66bc16d5ed9b8acdbb8`** - `test(prefs): extract decodeSettings and cover preference migration`. This is the last code+test commit: it carries every reviewed Android/Chrome change plus one test-only, behavior-preserving preference refactor. The last product-behavior commit is `0d3c085`. The branch tip is a docs-only commit above it that adds this prompt and the handover update, so check out `4317e67` for the code under audit and read this prompt from the working tree.
- Feature and test commits in scope, newest first:
  - `4317e67` test(prefs): extract decodeSettings and cover preference migration (test-only; no product behavior change)
  - `0d3c085` fix(chrome): keep word boundary between visually-block inline boxes
  - `728b863` feat(android): refine Find in article with compact field, states and match navigation
  - `a60c7cd` feat(android): bundle Inter reading font with normal and bold faces
  - `18c9ac2` feat(android): review from a tapped quote without losing the round
  - `9fa220d` feat(android): floating Review banner replaces the top entry card
  - `bdf17fc` feat(android): fullscreen focus, search-only up button, native menu suppression while highlighting
  - `5dbb98a` feat(android): final-pass stage 1 - Reader rename, four reading actions, warmer Paper, one-time highlight help
  - `99c5cf9` feat(android): shared destination headers, compact search, prominent Review
  - `fd1b813` feat(android): quieter shelf markers and progress stroke
- Base chain: `b14f128c54996515f45218f8d912ef106c174446` on `15a77ff647ac39f4e9165e47504d553504a1ac8b`.
- Diff to audit: `git diff 15a77ff..4317e67 -- android/ chrome/` (use `git show` per commit for exact hunks; the only post-`0d3c085` code change is the test-only Prefs extraction in `4317e67`).
- Handover: `CONTINUE.md` (running stage-by-stage status, including the one item still unimplemented) and `FOCUSED_HANDOFF_20260910.md` (scope, evidence, state/code map, trial boundaries).
- Installed candidate: normal `com.reader.app`, debug `0.9.0-beta.1` / code 2, APK SHA-256 `64a0cf406b730fe858e1805d9233ee7b400f5d50b139a357719b4cf12e6fce16`, source commit `0d3c085`, installed in place on TCL T807D / Android 16 / serial `ZXKRS4VKGQ8PWGEQ` with the installed file hash verified identical and owner data preserved across 8 tables plus preferences (`evidence/focused-20260910/installation-20260911b.json`). The QA package is `com.reader.app.qa`. The installed build does **not** include `4317e67`; that commit only extracts a pure decode function and adds a unit test, so device product behavior is unchanged from `0d3c085`.

## Read first

- `FINAL_USABILITY_SPEC_20260911.md` - the current specification; it supersedes conflicting older requirements.
- `CONTINUE.md` - the primary handover: per-stage status, the native checks and the one remaining item.
- `FOCUSED_HANDOFF_20260910.md` - implemented scope, evidence, state/code map, trial boundaries.
- `NEXT_AGENT_PLAN.md` - the earlier plan (work packages A-E); superseded where it conflicts with the new spec.
- `docs/COPY_DECK.md` - the copy/behavior contract.
- `evidence/focused-20260910/installation-20260911b.json` - the current installed build, hashes and the checks actually run.
- `evidence/focused-20260910/qa-retest-20260911.json` - the isolated-QA retest of the two paths changed after the last device check: the refined Find sheet and the Inter reading font.
- `evidence/focused-20260910/hdr-*.png`, `qa-*.png`, `ux-*.png`, `normal-post-install-0d3c085.png`, `walkthrough.jsonl` - real TCL observations (not mockups).
- `android/app/src/test/java/com/reader/app/PrefsMigrationTest.kt` - the test added by the tip commit.

## Scope note

Review only committed code at the audited revision. The worktree at handoff is **clean**.

Do not repeat the completed 100-article intake. The only native checks recorded for the app are the end-of-article finish state and one adjusted native selection (`installation-20260911.json`, taken at `18c9ac2`). They were intentionally not re-run: `a60c7cd` changes only the typeface selection inside `NativeArticleView.kt` (not the selection handling, the native text-action mode or the 8dp inset), and `728b863` / `0d3c085` do not touch those paths. Treat them as prior evidence for this build, not as a fresh pass. Do not restage the 100-article exercise, generate scale data, or run a 120-minute / soak / exhaustive-matrix campaign.

The Chrome boundary fix (`0d3c085`) is **not** part of the APK. It changes future captures at extraction time and deliberately leaves the owner's already-stored text intact.

## What changed (audit targets)

Android (all in the audited revision):

- `ui/DestinationHeader.kt` - shared 52dp header row for Reader/Shelf, Highlights, Settings and Review.
- `ui/ReaderSearchField.kt` - compact 48dp search surface, now reused by both Shelf and Find.
- `ui/screens/PreparedReaderScreen.kt` - the refined Find-in-article sheet (states, `N of M` navigation, Previous/Next, snippet rows) plus the four reading actions wiring.
- `ui/screens/NativeArticleView.kt` - native rendering, four reading actions, fullscreen focus, native text-action menu suppression, Inter bold face selection.
- `ui/screens/HighlightsFeed.kt` - full-quote adaptive cards, Important badge, floating Review banner, card tap that enters Review.
- `ui/screens/ReviewScreen.kt` - compact header, truthful phase progress, Newsreader measure, Source action, completion state.
- `core/ReviewScheduler.kt` - focused presentation (`focusedId` / `focusedReviewed`, `presentedId`, `focus`) that preserves an unfinished round.
- `data/HighlightRepository.kt`, `data/Highlights.kt` - read-only `observeSummary()` / `ReviewDao.observeParts()`.
- `data/ArticleNavigation.kt` - the Find match model (parts, matches, selection, `N of M`).
- `prefs/Prefs.kt` - `ArticleFont.INTER`; `ui/theme/Tokens.kt`, `ReaderScreen.kt` - font and colour tokens; `res/font/inter_regular.ttf`, `inter_bold.ttf`; `LICENSES/INTER-OFL.txt`, `LICENSES/README.md`.
- `prefs/Prefs.kt` (tip commit `4317e67`) - pure extraction of the preference decode into a top-level `internal fun decodeSettings(Preferences)`, with `Prefs.K` widened to `internal`. `Prefs.decode` now delegates to it. **No stored key renamed, no default changed, no behavior change intended**; it exists so the migration rules can be unit-tested without a `Context`.
- `ui/screens/InboxScreen.kt`, `ui/screens/SettingsScreen.kt`, `ui/MainActivity.kt` - wiring, one-time search focus, Back-closes-search, Reader rename, warmer Paper surface, one-time highlight help.
- Tests: `ReviewSchedulerTest.kt`, `ArticleNavigationTest.kt`, `ReaderFlowInstrumentedTest.kt`, `UiCompletionInstrumentedTest.kt`, and `PrefsMigrationTest.kt` (new: fresh install sees the highlight explanation once, an existing install is treated as already seen, an explicit flag beats the legacy-install guess, unknown stored values fall back to defaults).

Chrome extension:

- `chrome/src/extraction/pipeline.ts` - `collectVisualBlockClasses`, `separateVisuallyBlockInline`, and their wiring into `extractGeneric` on both the Defuddle and Readability clones.
- `chrome/tests/extraction.test.ts` - the positive boundary case and an inline-formatting counterexample.

## Audit axes and required questions

**1. Correctness / code review**

- Text boundary fix (`chrome/src/extraction/pipeline.ts`): are `collectVisualBlockClasses` and `separateVisuallyBlockInline` actually applied on both extraction clones (not just defined)? Is the guard correct - inserts only when both adjacent characters are word characters, no existing whitespace, and never inside `pre`/fenced code? Can it insert a spurious space into genuinely adjacent inline content, tables or punctuation? Is it bounded and side-effect free on the live DOM, and does it fail safe when `defaultView`/`getComputedStyle` is unavailable?
- Preference decode extraction (`prefs/Prefs.kt` tip commit): is `decodeSettings` byte-for-byte equivalent to the previous private `decode` for every key (including the `legacyInstall` guess and the explicit `highlightCoachSeen` flag winning over it)? Does widening `K` to `internal` leak any key that should stay private? Does the extraction change the compiled debug APK bytes versus the installed `0d3c085` build, and if so is the behavior provably identical?
- Inter (`prefs/Prefs.kt`, `ui/theme/Tokens.kt`, `NativeArticleView.kt`, `ReaderScreen.kt`): is `ArticleFont.INTER` wired for both Compose and the native reader, and does bold use the bundled `inter_bold` face rather than a synthetic weight? Is the enum added without renaming existing stored values, and is the license shipped?
- Find (`ui/screens/PreparedReaderScreen.kt`, `data/ArticleNavigation.kt`): are the explicit states correct; is stale-query cancellation real (a completed older query cannot overwrite a newer one); does the sheet keep the header and field stable while results scroll independently; does selecting a result land at the match and expose Return to reading position; is there any new extra scroller around the article text?
- Does `DestinationHeader` actually guarantee identical title geometry across Reader, Highlights, Settings and Review, or can content/actions still shift the baseline or reserve height differently?
- Is the Review summary genuinely read-only (never starts, advances or repairs a cycle just to render)?
- Do the four reading actions preserve existing speech/session state (Listen pause/resume, Speed pause + flush) and stay at least 48dp at font scale 1.0-1.4?
- Is fullscreen focus mode enterable and exitable without stranding the system bars, and does native menu suppression restore correctly?
- Is the search field's one-time focus and Back-closes-search behavior correct across config change, process death, returning from an article, and empty -> typed -> cleared?
- Is the phase progress count correct in every phase, including when Important bonus items follow the current queue?
- Any regression in the gated instrumented tests' completion-copy contract?
- Do the claims in `FOCUSED_HANDOFF_20260910.md`, `CONTINUE.md` and `docs/COPY_DECK.md` match the actual code?

**2. Performance**

- Boundary fix: `collectVisualBlockClasses` runs `getComputedStyle` over every inline tag on the live page, and `separateVisuallyBlockInline` runs `querySelectorAll` over inline tags on each clone. Is that O(n) and acceptable on a large article, and is it skipped cheaply when there is nothing to do? Any repeated layout/paint cost?
- Inter: is the font loaded through a cached provider (`ResourcesCompat`) rather than re-read per bind, and is there any main-thread font load on the reading path?
- Find: recomposition and snippet-row cost, query bounding/debouncing, and whether stale queries cancel their work; do the new match objects allocate per keystroke or per row?
- Compose recomposition/measure cost of the shared header, floating Review banner and search field; unstable lambdas/keys, allocations during item composition, whole-list recomposition on scroll.
- The floating banner appears/hides on scroll direction - does that cause per-frame recomposition or layout thrash?
- Reader/Review: any new main-thread database or layout work? Any regression against the pre-pass baseline in the touched files?
- State your measurement method (Compose trace, `dumpsys gfxinfo`, profiler) or mark **NOT MEASURED**. Do not imply a benchmark you did not run.

**3. UI**

- Does the shared header hold a stable baseline at font scale 1.0 and 1.4 with no clipping or overlap? Verify on the TCL device where possible.
- Inter: does it render correctly in Compose previews, the article, adaptive highlight cards, Review and Speed, and is bold a real bold face rather than a smeared synthetic one? Is the Appearance label present and correct?
- Find: does the compact shared field and the new states/navigation read as one coherent tool aligned to the same gutter as the rest of the app? Do the snippet rows show a clear selected state, and does the match-navigation strip fit without overlap?
- Does the compact search field replace the oversized outlined field consistently on Reader and Highlights, with a reachable clear action and a correct editable accessibility label?
- Do the four reading actions render as one coherent 48dp row (icon above a 12sp label) and wrap cleanly above font scale 1.3?
- Is Review visually primary within Highlights, and are the card typography, 3dp colour marker, Important badge and completion states coherent?
- Contrast, 48dp touch targets, focus order, colour-only signalling, overlap and truncation.

**4. UX**

- Find: keyboard-open layout, empty/searching/no-match/failure states, Previous/Next and `N of M`, quoted phrases, `#` label behaviour, constraints beyond the first 50 matches, and Return to reading position.
- Text boundaries: does a corrected future capture read naturally, does the inline counterexample stay intact, and is the owner's already-stored text untouched?
- Inspection stays separate from deliberate Review; inspection must never advance a cycle. Confirm from code and, if possible, device.
- Review entry states (`Revisit your saved passages.` / `N remaining in this round` / `Revisiting Important highlights` / `Round complete.` / save-first) correct and non-pressuring?
- Source round-trip preserves the pending cycle, and Back from Review returns to Highlights with position intact?
- Does fullscreen focus mode remove distractions without hiding controls the reader still needs, and is exit discoverable?
- Any copy that overclaims (offline availability, streaks, ranking) versus `docs/COPY_DECK.md`.
- Note: the e-ink / NXTPAPER theme (spec 3B) is the one remaining unimplemented item; do not treat its absence as a regression, but flag any place that would block adding it cleanly.

## Guardrails

- Local only: no push, merge or release. Do not modify or clear the normal `com.reader.app`; use `com.reader.app.qa` for any device work.
- Preserve Room v13, canonical article text, quote anchors, the native selectable TextView and its 8dp inset, existing preferences, owner data, highlights, Review history, keys and pairings.
- Do not repeat the completed 100-article intake, generate scale data, or run a 120-minute / soak / exhaustive-matrix campaign.
- `adb` and Gradle require escalated permissions on this host. The isolated QA browser/controller is stopped - do not assume old process IDs or credentials are live.
- Chrome suite: `cd chrome && npx vitest run` (the `fault-relay` file needs loopback access; run it where binding `127.0.0.1` is permitted).
- If you propose or make a fix, keep it the smallest complete change, run only the affected focused tests, and leave the installed candidate's hash and owner-integrity evidence intact.

## Output

Findings first, most severe first. For each: severity (P0-P3), a one-line title, `file:line`, why it matters, and the smallest concrete fix. Then a short per-axis summary (Review / Performance / UI / UX) using **PASS**, **FAIL**, **NOT MEASURED** or **BLOCKED: exact reason**. End with residual risk and what you did not check. Do not restate the handover as if it were your own verification.
