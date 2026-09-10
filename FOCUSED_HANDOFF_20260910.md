# Reader installed checkpoint and continuation context — 10 September 2026

**The executable handover is [NEXT_AGENT_PLAN.md](NEXT_AGENT_PLAN.md); its starter prompt is [NEXT_AGENT_PROMPT.md](NEXT_AGENT_PROMPT.md).** This document supplies the implementation/evidence context for that plan.

Baseline: clean `15a77ff` on `codex/reliability-finalize`. This is a trial checkpoint of the user-approved implementation with efficient verification. The latest instruction is to conserve usage, install the most important completed work, and leave explicit continuation context; do not describe the entire original acceptance plan as completed. It supersedes earlier extensive D/E/F acceptance plans. Local work only: no push, merge or release.

## Implemented scope

- Shelf / Highlights / Settings bottom navigation; Inbox / Priority / Later / Archive always visible, wrapping at enlarged text. Independent scroll state, compact multi-selection, Move to, stable facets and an activity-based Continue reading entry.
- Native continuous reading retained, with the small 8dp text inset. Compact reading tools and appearance sheet; Newsreader 19sp/Comfort defaults, persistent bold both on and off, existing explicit preferences preserved. Focus controls, headings/Contents, offline Find and return-to-position; relative links and captured footnotes resolve without rewriting saved text.
- Native selection remains the default highlighting path. Continuous highlighting is optional/off by default. Selection adjustment updates one saved range. Compact color/Important/Copy/Share/Remove sheets, guarded full-metadata Undo, six-line previews, local quote/source-title search, standalone quote inspection and explicit Review.
- Completion is deliberate: the actual final viewport reveals Finish & archive / Back to shelf. Atomic completion/archive gives credit once and leaves the article open with Undo. Ordinary Archive does not record a finish. Later deliberate moves invalidate older Undo receipts.
- Search separates location from fields, includes Archive by default, combines ordinary terms with AND and supports quoted phrases and existing multiword-label autocomplete. All facets precede pages of 50. Same local substring contract on OEM SQLite builds with or without FTS5; explicit loading/invalid/empty/failure states.
- Stable label identities, unused-label management, rename, explicit collision merge and deletion without article loss. Sorting, age, labels, Unlabeled, search scope and field choice persist.
- Useful first-save choices, optional sample, truthful capture states/retry, simpler settings, confirmed disconnection, accurate export/statistics copy and technical diagnostics under Advanced. Removed automatic welcome insertion, tutorial labels, streak/milestone interruptions and article-wide organizational swipes.
- Observed defects fixed during this pass: repeated Android shares starting competing activities; failed-capture Retry targeting the wrong request; clipped filter actions; opening-header viewport movement; stale search landing after Speed; native restoration before layout; end-card viewport changes. Narrow web.dev publisher-widget cleanup applies only to new captures. Existing canonical text remains unchanged.

## Continuation pass — shared headers, compact search, prominent Review

Commit **`99c5cf9`** executes work packages A–D of `NEXT_AGENT_PLAN.md` on top of `b14f128`. Scope kept: `Shelf · Highlights · Settings`, `Inbox · Priority · Later · Archive`, Newest default, inspection separate from deliberate Review, cycles + Important bonuses with no calendar due dates. No storage, scheduler, capture-security or native-reader rewrite.

- **A — shared headers.** New `ui/DestinationHeader.kt` gives Shelf, Highlights, Settings and Review one 52dp-minimum row, a 20dp leading inset, a common headline token and common vertical alignment, reserving its height with no trailing actions. TCL bounds for the three destination titles are identical (`top 153`, `bottom 256`, `left 57`); the 1.4 font-scale pass showed no overlap. Evidence: `hdr-shelf.png`, `hdr-highlights.png`, `hdr-settings.png`, `font14-*.png`.
- **B — compact search.** New `ui/ReaderSearchField.kt` replaces the two full-width outlined fields: a quiet 48dp surface, 12dp corners, 20dp decorative icon, 16sp editable text and a clear action only when nonempty. It focuses once per deliberate opening (fixing a `FocusRequester is not initialized` crash found on device during this pass), Back closes search without erasing the query, and the blank state is now a short guidance line plus left-aligned **Recent searches** rows. Evidence: `ux-shelf-search.png`.
- **C — Review is primary.** `HighlightsFeed` gains a Review entry card directly beneath the title with a read-only state line (`Revisit your saved passages.` / `N remaining in this round` / `Revisiting Important highlights` / `Round complete.`) and one of Start review / Continue review / Review again. It condenses while a search is active. `ReviewRepository.observeSummary()` + `ReviewDao.observeParts()` read persisted state without starting, advancing or repairing a session. Shuffle now shows its own selected state.
- **D — Review comfort.** `ReviewScreen` gets a compact Back + Review header, truthful phase progress, a Newsreader 22sp measure on a neutral surface with a 3dp saved-colour marker, a distinct **Source** action, and a **Review complete** / **Back to highlights** / **Review again** completion state. Direct manipulation is preserved: no motion while the finger is down, reduced-motion honoured, and Next cannot advance twice for one gesture.

Checks run this pass (all on the QA package `c8272619…`; no intake, scale, soak or matrix campaign repeated): Android unit tests, `assembleDebug`, `lintDebug` and `verify16KbAlignment`; `UiCompletionInstrumentedTest` (2 tests, OK) and `ReaderUxFoundationsInstrumentedTest` (3 tests, OK); native end-of-text; native selection with one handle drag; enlarged-text 1.4 pass; the normal `assembleDebug` + in-place install with installed-hash and owner-integrity proof. One first combined instrumentation invocation reported a single failure in `appearanceDefaultsBoldPersistenceAndNavigation`; the single-method and full-class re-runs both passed, so it is recorded as an unreproduced first-run harness flake, not a product failure. Full command results are in `evidence/focused-20260910/final-verification-20260910b.txt`; remaining focused checks are tracked in `NEXT_AGENT_PLAN.md` §9.

## Intake: 100 actual articles

`evidence/focused-20260910/intake-final.json` records each URL, article identity, capture/delivery status and stored-text hash.

| Path | Distinct articles | Result |
|---|---:|---|
| Actual Chrome extension, extraction and encrypted transfer to TCL | 50 | All delivered with receipts |
| Android user-facing link sharing, durable capture and extraction | 50 | All completed with stored article text |
| Missing / duplicate documents / terminal source failures / link-only | 0 | No silent source replacement |

The extension was loaded unpacked in an isolated Chrome for Testing profile. The Android exercise used `com.reader.app.qa` on TCL T807D, Android 16, serial `ZXKRS4VKGQ8PWGEQ`. Transfer ran through isolated local QA relays using the real encrypted protocol; it is not proof of current public-relay reliability. Articles were fetched from their real public sources. No article was inserted directly into the database to simulate intake.

Initial rapid Android sharing exposed the activity issue; only missing URLs were retried after its fix. One accepted retry produced a second capture record (51 completed requests for 50 distinct URLs), with one document per URL. Browser driver attempts that lacked a real active-tab gesture were rejected before capture; all 50 successful Chrome sends used the actual extension keyboard shortcut through the browser UI.

Five articles from that corpus were visually inspected: **The Lesson to Unlearn**, **Being a Noob**, **Box Model**, **Selectors**, and the longer **Grid** lesson. The web.dev inspections exposed publisher controls in old extraction; the new cleanup does not rewrite those historical bytes.

## Focused verification

- Android fast unit tests: **226 passed**, no failures/skips.
- Android lint: passed on changed code; debug app/test builds passed.
- Chrome: **140 passed**, typecheck/lint passed, unpacked production build verified. A sandbox-local-server restriction blocked 18 tests on the first invocation; only that file was rerun with local-server permission and all 18 passed.
- TCL: **three** isolated foundation tests passed (completion/Undo, constrained search/labels, full quote metadata restoration) and **two** current UI contract tests passed (appearance/navigation; quote inspection/removal/Undo).
- Actual UI observations and screenshots: `evidence/focused-20260910/walkthrough.jsonl` and adjacent PNG files. Driver errors and observed product failures remain distinguishable; later named checkpoints record targeted rechecks.
- Brief offline reopen passed with Wi-Fi and mobile data temporarily disabled and restored. Listening started, sentence progress advanced and Pause/Close worked; speed reading started. Acoustic quality was not measured.

The current native viewport fix was checked again after these tests: normal scrolling and Back/reopen retained the passage, Speed returned to the later passage, and leaving the end restored the ordinary reading dock. The final end-of-text adjustment is recorded with its limited verification below. Earlier screenshots or failed driver checkpoints do not prove the final bytes.

## Installation record

- Branch: `codex/reliability-finalize`. Current implementation/source commit: **`fd1b813`** (quieter shelf markers and progress stroke), on top of **`99c5cf9`** (shared headers, compact search, prominent Review), **`b14f128c54996515f45218f8d912ef106c174446`** and **`15a77ff647ac39f4e9165e47504d553504a1ac8b`**.
- Normal debug **`com.reader.app`**, version `0.9.0-beta.1` / code `2`, installed in place on TCL T807D / Android 16 (SDK 36) / `ZXKRS4VKGQ8PWGEQ`, 2026-09-10 at 23:30:17 UTC.
- APK: `artifacts/hardening/focused-20260910/reader-debug-next.apk` (local, ignored by Git).
- Packaged **and installed** SHA-256: **`03cdfb15714568f9d63b51ad6fba94806728978bd6d4b502e3bb36d82d4b7916`**; supersedes `a7cc78a9…` (`99c5cf9`) and `ee05b86a…` (the `b14f128` install, preserved in `installation-20260910a.json`).
- Debug signing certificate SHA-256: `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`. In-place install succeeded; signing identity was not replaced.
- Final normal `assembleDebug`, `lintDebug`, and `verify16KbAlignment` **PASS**. App launch **PASS**. See `evidence/focused-20260910/final-normal-build.txt` and `installation.json`.
- Owner integrity **PASS** for the current `fd1b813` install: all eight compared Room tables and preferences identical; schema v13; 1 article, 0 highlights, 0 channels. No normal package data was cleared. These counts do not imply a paired owner setup was tested. `owner-earlier.json` preserves the first observation; `owner-20260910a-*.json`/`owner-*.json` record the `b14f128` upgrade; b/c record the intermediate candidates; `owner-20260910d-before.json` / `owner-20260910d-after.json` are the actual pair for the installed `fd1b813` build. The owner archived the single test article in the app before this install, so Inbox is legitimately empty; that is owner state, not data loss.
- QA APK SHA-256: `379e83f0b81b04c502ea445a8f0a7453b3ac5c3768cb109b67d32c184a674835`. QA corpus remains isolated; its local controller/Chrome/relay process was stopped. Later organization/review actions can change current per-shelf counts; `intake-final.json` remains the completed 100-article intake record.
- The narrow end-detection change (using the last non-whitespace text line instead of trailing layout space) is now **re-exercised on TCL**: the QA build reached `100% · End of article` with a stable **Finish & archive** / **Back to shelf** card, wrote no completion credit, and scrolled back to 79%. Native selection was also re-exercised (handle drag → one saved range, no duplicates). See `NEXT_AGENT_PLAN.md` §9 and `ux-end-of-text.png` / `ux-native-select-*.png`.


## Remaining verification context — next agent

The ordered implementation plan, including the newly requested title/search/Review redesign and additional TCL observations, is in **NEXT_AGENT_PLAN.md**. That plan supersedes the priority ordering below; retain these technical notes as supporting context.

The user explicitly requested the installed trial build now. Stop optional expansion. Begin with reported seven-day trial issues; the items below are remaining focused verification or refinement, not permission to restart extensive campaigns.

1. **Reader continuity is the highest-value follow-up.** The observed reset-to-start around header/end-card resizing and Speed return was traced to Android TextView keeping a collapsed selection at offset zero. `NativeArticleView.clearIdleTextCursor()` removes that idle selection before layout; `doOnPreDraw` restores the semantic passage after layout. Pending viewport anchors preserve focus/header changes. End detection follows the last non-whitespace text line. Normal scrolling, reopen and Speed return have targeted evidence; unusual selection/focus/appearance combinations and cross-part returns remain trial-sensitive. Reproduce an actual failure before changing this machinery. Never wrap selectable text in a second scroller or rewrite quote anchors.
2. **Unperformed small visual checks:** one enlarged system-text pass (suggest 1.4, restore the original value), four shelves wrapping, appearance/filter controls reachable; search with the keyboard open, `#` multiword-label autocomplete, and advancing beyond the first 50 matches with active constraints. Underlying constrained search, stable label rename/merge, and normal appearance/bold have passed their focused tests; these remaining UI combinations have not.
3. **Extraction cleanup recheck:** a narrow web.dev widget/title-suffix cleanup was added after inspecting the five real articles and passed its focused host test. One new user-facing capture of Box Model would verify the final rendering. Do not recapture all 50 or rewrite existing documents; the original corpus remains an immutable intake record.
4. **Small remaining interactions:** visually inspect a captured expanded table and local footnote jump when a trial article exposes one; verify overlapping-highlight reachability and multi-remove confirmation when relevant. Code paths exist, but this cycle did not manually exercise every combination. Native selection, one adjusted highlight, recolor, Important, remove/Undo, source-return, Contents/Find and explicit Finish/Undo were exercised.
5. **Completeness review if requested after the trial:** compare the implemented scope and `docs/COPY_DECK.md` against the approved plan, concentrating on remaining rough edges and user feedback. Long-term comfort, less common lifecycle/accessibility behavior, external relay availability and acoustic quality are unmeasured, not claimed successes.

### State and code map

| Area | Primary files / responsibility |
|---|---|
| Navigation and shelf | `MainActivity.kt`, `InboxScreen.kt`, `ReaderBottomNavigation.kt`, `Route.kt`; per-destination scroll/filter/selection ownership |
| Search | `LibrarySearch.kt`, `SearchTerms.kt`, `SearchRepository.kt`; immutable request/result, constraints before 50-item limits |
| Reader | `PreparedReaderScreen.kt`, `NativeArticleView.kt`, `ArticleNavigation.kt`; semantic cursor, temporary inspection origin, viewport/end, bounded parts |
| Completion | `ArticleCompletion.kt`, `ArticleMoves.kt`, `ProgressWriter.kt`; guarded receipt, one credit, explicit finish only |
| Highlights | `HighlightRepository.kt`, `HighlightsFeed.kt`, `HighlightDetailScreen.kt`, `HighlightActionsSheet.kt`; existing mutation/Undo journal |
| Labels and feedback | `Labels.kt`, `ManageLabelsScreen.kt`, `LabelsDialog.kt`, `NoticeCoordinator.kt` |
| Preferences/settings/capture | `Prefs.kt`, `ReaderScreen.kt`, `SettingsScreen.kt`, `ArticleExtractor.kt`, `CaptureRequest.kt`, manifest single-task sharing |

Android files are under `android/app/src/main/java/com/reader/app/`; no unrelated architecture rewrite or Room migration was introduced.

### Efficient continuation commands and isolation

- Work in this checkout on `codex/reliability-finalize`; inspect `git status` and the installed identity first. Normal Android builds run from `android/`: `./gradlew --no-daemon --max-workers=2 assembleDebug lintDebug verify16KbAlignment`. Add `-PreaderQa=true` only for the isolated QA package, and `assembleDebugAndroidTest` when its controller is needed. Those variants share output paths: copy/hash a candidate before changing variants.
- Focused Android instrumentation classes: `ReaderUxFoundationsInstrumentedTest` (3 tests), `UiCompletionInstrumentedTest` (2 current UI contracts). These already passed; rerun only changes that affect them. The former broad UI copy campaign was replaced by these current contracts, not represented as all old assertions passing.
- Real intake orchestration lives in `scripts/qa-intake-start.mjs`, `qa-android.mjs`, `qa-cdp.mjs`, `qa-complete-intake.mjs` and `qa-walkthrough.mjs`. The successful 50 extension sends used native Chrome UI shortcut **Alt+Shift+R**; CDP `Extensions.triggerAction` lacked a trusted active-tab gesture and must not be used as successful-delivery proof. Do not repeat the intake campaign.
- The local QA profile/state is `/tmp/reader-focused-20260910/`; its `state.json` contains private ephemeral QA credentials. Never print, commit or reuse them as owner credentials. Isolated QA articles remain in `com.reader.app.qa`. Normal `com.reader.app` was never seeded with this corpus.
- `qa-walkthrough.mjs` records read-only viewport details plus screenshots and user-facing interactions. Its Back uses actual adb keyevent 4; the earlier SDK key injection did not navigate. Exact title text may contain nonbreaking spaces. Avoid a query equal to the full result title when driving text-based taps.
- `scripts/qa-owner-integrity.py DEVICE before|after` reads hashes/counts without exposing article or key content. Final before/after evidence is saved; **do not overwrite the pre-upgrade snapshot** during continuation.

## Seven-day trial and boundaries

The user assesses sustained comfort and reliability over seven days. No 1,000-article/10,000-highlight corpus, soak test, timed reading simulation, 120-minute session, statistical benchmark or exhaustive device/lifecycle/accessibility matrix was run. Do not restart those campaigns during the trial.

Room remains v13. Canonical article text, quote anchors, encryption and pairing security are preserved. Offline images, highlight notes, pagination mode, background audio, accounts, cloud processing and telemetry are outside this cycle. Export remains articles/highlights rather than a complete restorable backup. Very large articles use bounded parts; native selection and speech stay within a part.

The code and copy contract are in `docs/COPY_DECK.md`. Start future work from `START_HERE.md`; reproduce reported trial issues and retest only affected paths.
