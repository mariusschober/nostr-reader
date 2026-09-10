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

- Branch: `codex/reliability-finalize`. Implementation/source commit: **`b14f128c54996515f45218f8d912ef106c174446`**, based on **`15a77ff647ac39f4e9165e47504d553504a1ac8b`**. Subsequent handover commits contain documentation/evidence only.
- Normal debug **`com.reader.app`**, version `0.9.0-beta.1` / code `2`, installed in place on TCL T807D / Android 16 / `ZXKRS4VKGQ8PWGEQ`, 2026-09-10 at 20:57:42 UTC.
- APK: `artifacts/hardening/focused-20260910/reader-debug.apk` (local, ignored by Git).
- Packaged **and installed** SHA-256: **`ee05b86a1e1024a7d0150742392d5349bef94da4c502fe75887f4081070910ca`**.
- Debug signing certificate SHA-256: `3af50cab6e0515f478413e79786958671913b2cae67037defe537bb1e7465069`. In-place install succeeded; signing identity was not replaced.
- Final normal `assembleDebug`, `lintDebug`, and `verify16KbAlignment` **PASS**. App launch **PASS**. See `evidence/focused-20260910/final-normal-build.txt` and `installation.json`.
- Owner integrity **PASS** immediately after installation, before launch: all eight compared Room tables and preferences identical; schema v13; 1 article, 0 highlights, 0 channels. No normal package data was cleared. These counts do not imply a paired owner setup was tested. `owner-earlier.json` preserves the earlier observation; `owner-before.json` / `owner-after.json` are the actual upgrade pair.
- QA APK SHA-256: `379e83f0b81b04c502ea445a8f0a7453b3ac5c3768cb109b67d32c184a674835`. QA corpus remains isolated; its local controller/Chrome/relay process was stopped. Later organization/review actions can change current per-shelf counts; `intake-final.json` remains the completed 100-article intake record.
- The last narrow end-detection change (using the last non-whitespace text line instead of trailing layout space) compiled and passed final lint/build, but was **not re-exercised at the reader end after the user's stop-implementation instruction**. Earlier viewport/Speed/end-card stability checks used the immediately preceding QA build. It remains one targeted check in the plan, not an asserted final-device pass.


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
