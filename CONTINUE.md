# Continue from the focused repair and TCL trial checkpoint — 11 September 2026

Read **[IMPLEMENTATION_HANDOFF_20260911.md](IMPLEMENTATION_HANDOFF_20260911.md)** and **[evidence/repairs-20260911/manifest.json](evidence/repairs-20260911/manifest.json)**. Product source is complete through `75b369b`; branch tip `eca253a` is test-only. Normal `com.reader.app` is installed on TCL `ZXKRS4VKGQ8PWGEQ`, and the local/installed APK SHA-256 is `d3bdcdae7dc1dae525d5b04805fe31aaf331eb4cb98420df8163dd8278ea0236`. Owner summaries and the on-device key digest matched before and after the install; the app was launched once after those comparisons. The only focused views still **NOT MEASURED** after the final repair are Green/double wrapped E-ink rendering, Speed in normal/E-ink, and one enlarged-system-text layout spot check. S23 geometry, completion/Undo, ordinary wrapped monochrome highlighting and Review source/Back are recorded as PASS. Do not clear or uninstall the owner package, do not repeat intake or broad testing, and preserve the user's seven-day trial as the next evidence.

## Historical next action — final repairs after Muse review, 11 September

The historical plan is [MUSE_REVIEW_PLAN_20260911.md](MUSE_REVIEW_PLAN_20260911.md) and its starter [NEXT_AGENT_PROMPT_MUSE_REPAIRS.md](NEXT_AGENT_PROMPT_MUSE_REPAIRS.md). It remains the design and implementation record; the current executable state and proof limits are in the focused handoff above.

Reviewed baseline: `80f39c4` on `codex/reliability-finalize`, containing Android `a4140c3` and Chrome `8b89a7f`. Fresh TCL evidence confirms the wrapped e-ink underline defect and the improved full-screen focus, Review CTA, title alignment, compact Highlights search and visible monochrome Speed cue. Code review also found ordinary-menu Highlight → handle-adjust session duplication and normal-theme Speed losing its focal accent. These are the current repair priorities; long-quote source context is the bounded design refinement. Keep all historical records below, but do not read their “all implemented” wording as acceptance of these remaining defects.

Normal installed SHA-256 remains `8dc3d733635189852151f908de4c0b261fc131b0a130c974f0e7df8430c0b6e1`; QA remains `025bdbacfe55cdbe64aac4610abaa3a37f95767f30d2cf0168ec5747da9af5e5`. Both were checked again. New screenshots and notes are under `evidence/muse-review-20260911/`. No repeat intake, whole test suite, owner-library mutation, app reset or install occurred in this audit. Normal-app launch and quantitative performance remain unmeasured here. Leave the user's seven-day trial as the sustained-use check.

Pre-existing untracked captures `evidence/polish-20260911/{21-feed,22-feed,22-reader-tab,23-archive}.xml` are preserved separately. The detailed plan names the current proof limits, precise repair gates and future in-place install requirements.

## Historical next action — independent audit before Muse's implementation

The fresh audit of product source **`f20828b`** found incomplete focus/cutout layout, invisible monochrome Undo and selected navigation icons, indistinguishable highlight choices/focal letters, duplicate pen-selection IDs, weak/conditionally absent Review entry, an incorrect focused Review count, and two capture-boundary counterexamples. See [ASTRA_AUDIT_20260911.md](ASTRA_AUDIT_20260911.md) for severity, source lines and evidence.

The authoritative remaining-work plan is **[READER_POLISH_PLAN_20260911.md](READER_POLISH_PLAN_20260911.md)**; [DeepSeek starter prompt](NEXT_AGENT_PROMPT_DEEPSEEK_POLISH.md). This dated correction supersedes the older “every item implemented” acceptance claim below. Those stages describe prior implementation, not acceptance of the remaining defects. No product source or normal installation changed in the audit.

Normal installed hash remains **`8928ca661fad20f49cd14e3fe8a6c7640826534521bd5674ee08fdbe1347c107`**, read back from TCL. The worktree tip at audit start was `f53bb8c`; product source was identical to `f20828b`. Fresh focused checks:23 Android unit tests and8 existing Chrome extraction tests passed; new helper counterexamples exposed two extraction defects. Performance/physical e-ink quality were not measured. QA now has a sample article and two highlights; no repeated intake. Owner app/data were not used for mutations. Keep the user's seven-day trial and efficient-test boundaries.

## Polish pass implemented — 11 September (evening)

All [READER_POLISH_PLAN_20260911.md](READER_POLISH_PLAN_20260911.md) work packages A–F are implemented on `codex/reliability-finalize`, verified at the focused level, and installed. This supersedes the audit's defect list as remaining work; the seven-day trial is the next input.

- Android A–E: commit **`a4140c3`** — single-identity continuous highlighting (session owns one ID across ActionMode recreation and handle adjustments; pen entry/exit rotates cleanly), one-time coach materialization, monochrome contrast pairs (visible Undo, white selected nav icon), shared `HighlightPresentation` (Y/G/C/P names + neutral fills + solid/double/dashed/dotted edges drawn from native layout geometry), bold+underlined Speed focal with long-word fit, effective motion policy everywhere, edge-to-edge focus with cutout handling and restore, filled Review entry plus persistent header fallback with Undo-aware suppression, pure `presentationCount` used by both summary paths, adaptive quote cards with reserved Important badge, coherent search/Find states.
- Chrome F: commit **`8b89a7f`** — per-element visual-block marking (`markVisualBlocks` + `isVisualBlockElement`) replacing the shared class-token registry; live DOM never mutated, markers stripped by sanitize; shared-class and classless counterexamples added.
- Copy deck (`docs/COPY_DECK.md`) updated to the implemented strings and contracts.
- Checks: the full fast Android unit suite passed (the exact aggregate count belongs to this historical record), `lintDebug` passed, 10/10 Chrome extraction tests passed, typecheck + build passed, `HighlightSessionInstrumentedTest` passed 1/1 on TCL T807D with APKs left installed, and all 12 native `.so` entries were 16KB page-aligned.
- Installed normal `com.reader.app` in place: source commits above, APK SHA-256 **`8dc3d733635189852151f908de4c0b261fc131b0a130c974f0e7df8430c0b6e1`**, pulled-back `base.apk` hash identical, `reader.db` byte-identical before/after, archived at `artifacts/reader-debug.apk` with `SHA256SUMS` updated. QA `com.reader.app.qa` on **`025bdbacfe55cdbe64aac4610abaa3a37f95767f30d2cf0168ec5747da9af5e5`** (installed bytes verified identical, archived in evidence).
- Device proof in `evidence/polish-20260911/manifest.json`: Review entry, mono fills + identifiers, sheet picker, settled Undo notice (white on black, floating entry suppressed), selected nav icon. Count logic proven by unit tests plus a live fresh round.
- NOT MEASURED / deferred: focus letterbox photo, Speed focal glyph photo, pen-dock photo, Find selected-row photo, Undo-tap restore, QA `session-gate-*` test-row cleanup, normal-app launch check. Reason: the owner is on an active call and using the phone (Messages/Phone/LinkedIn foregrounded); all screen-driving work stopped to avoid disturbing them. Nothing was sent or changed outside the QA package. The remaining visual confirmations are quick screenshots for a quiet moment, then the trial.

## Historical implementation record

Execute **[FINAL_USABILITY_SPEC_20260911.md](FINAL_USABILITY_SPEC_20260911.md)** when asked to continue. Start with [START_HERE.md](START_HERE.md), then [FOCUSED_HANDOFF_20260910.md](FOCUSED_HANDOFF_20260910.md), [NEXT_AGENT_PLAN.md](NEXT_AGENT_PLAN.md) and [docs/COPY_DECK.md](docs/COPY_DECK.md). The new spec supersedes the earlier focused plan and the former batch D/E/F plan where they conflict.

The latest user instruction prioritized conserving usage and installing a trial build. The original plan is not fully accepted: the handoff lists remaining focused checks and refinements. The next product input is the user's seven-day trial. The latest remaining design priorities are title/header consistency, compact beautiful search, and Review as a major feature. When a bug is reported, reproduce that exact path, make the smallest complete fix and repeat only affected checks. Do not create a new broad campaign.

Keep work on `codex/reliability-finalize`; preserve the owner's normal `com.reader.app` data and pairing. Use `com.reader.app.qa` and an isolated browser profile for test articles or destructive fixtures. Never clear normal app data, replace its keys, import the QA corpus into it, or remove the owner's Chrome profile.

The relevant state boundaries are `LibrarySearchRequest/Result`, stable label IDs, per-destination list state, reader inspection origin/current cursor, `FinishReceipt`, `HighlightMutation`, and `NoticeCoordinator`. Reuse the existing reading-session journal and bounded article repository. Keep copy and behavior assertions consistent with `docs/COPY_DECK.md`.

A normal debug update is an in-place signed install. Record its source commit and installed APK SHA-256; distinguish code validation, real TCL behavior and unmeasured long-term use. Local commits only unless explicitly told otherwise.

## Final usability pass — progress

Stage 1 is committed on `codex/reliability-finalize` as **`5dbb98a`** ("final-pass stage 1 — Reader rename, four reading actions, warmer Paper, one-time highlight help"), on top of `3e2f0c0`. Implemented so far:

- The new specification is recorded verbatim in `FINAL_USABILITY_SPEC_20260911.md`; `START_HERE.md`, `NEXT_AGENT_PLAN.md` and `docs/COPY_DECK.md` point to it and mark superseded behavior.
- Visible **Shelf** renamed to **Reader** (bottom navigation, destination header, end card **Back to Reader**, search-location **Current list**, loading/empty copy) while internal `shelf` ids and routes stay unchanged. `UiCompletionInstrumentedTest` follows the new label.
- **Paper** surface warmed to `#FFF1E5` (`Flexoki.ReaderPaper`) with coordinated warm surface/divider, kept separate from `Flexoki.Paper` (still the light text on Ink/Black).
- **Four reading actions**: Highlight · Contents · Listen · Speed, equal width, icon above 12sp label, ≥48dp each, ~56dp row, wrapping to 2×2 above font scale 1.3. Listen becomes Pause while speaking and resumes the existing session; Speed pauses speech and flushes pending changes through the existing transition path.
- **One-time highlight help** via durable `highlightCoachSeen`; new installs see it once on the first Highlight tap (marked seen when presented), installs with existing preferences start seen. New copy and **Start highlighting** primary action.

Verified in stage 1: `testDebugUnitTest`, `assembleDebug`, `lintDebug` and `verify16KbAlignment` all **PASS**. Not yet re-run on the TCL device, and the normal `com.reader.app` was **not** reinstalled (still the `fd1b813` build / `03cdfb15…`).

Still to implement from the spec: the Find-in-article sheet refinement; Inter; the app-wide e-ink display policy; and the merged-label/heading boundary diagnosis.

## Stage 2 — Review from a tapped quote, full-quote cards, refreshed install

Commit **`18c9ac2`** (on top of `9fa220d`, `bdf17fc`, `5dbb98a`) implements the full-quote adaptive highlight card and **Review from a tapped quote without losing the unfinished round** (spec §4A/§4B):

- `ReviewState` gains backward-readable `focusedId` / `focusedReviewed` with defaults, so older persisted state still decodes; no Room schema migration.
- New `ReviewScheduler.presentedId()` and `ReviewScheduler.focus()`; `advance` reviews a focused quote once and then resumes the preserved round; a legitimate later Important bonus for the same quote is not consumed. `refresh` drops a focused quote that is no longer eligible.
- `ReviewRepository.focus()`; `advance`/`openedSource` credit the focused presentation once; the Highlights summary counts the focused quote plus the queue.
- Highlight cards show the full quote with adaptive type (base +6sp up to 160 graphemes, base +3sp up to 450, otherwise base), the 24dp over-border Important badge and a compact footer (source title · Open source · More). A card tap enters Review; the footer controls do not.
- Eight new `ReviewSchedulerTest` cases cover focus, resume, single consumption, preservation of later bonuses, refresh, missing quotes, serialization and legacy decode.

Checks for this stage: `testDebugUnitTest` **234 passed, 0 failures**; `assembleDebug` passed for both the normal package and `-PreaderQa=true`.

Installed candidate (in place, `com.reader.app`): source `18c9ac203c1a11e216417c8a7cef160a246dd70b`, APK SHA-256 `8052b8a1d1d43a4188a727f69fc57b2fbe3120ce301b214ee507838b3c6680d3`, installed hash verified identical from the device `base.apk`, updated 2026-09-11 00:51:23. Owner integrity: 8 tables plus preferences identical before and after (`owner-ebefore.json` / `owner-eafter.json`), schema v13. Full detail in `evidence/focused-20260910/installation-20260911.json`.

Two native checks on the refreshed QA package (built from the same revision): end of article reaches `100% · End of article` with the Finish card and a byte-stable viewport; one press-and-hold plus one handle adjustment produces exactly one saved range (7 → 8) with the native Copy/Highlight/Share menu intact outside continuous highlighting.

## Stage 3 — Inter reading font

Commit **`a60c7cd`** bundles Inter Regular/Bold into `android/app/src/main/res/font/` with the SIL OFL text at `LICENSES/INTER-OFL.txt`. `ArticleFont.INTER` is added without renaming existing stored enum values; `ReaderFonts.Inter`, `fontFor`, the Appearance picker label and the native reader all resolve it, and the native reader selects `inter_bold` when bold is on instead of synthesising weight. `LICENSES/README.md` lists it.

## Stage 4 — Find in article refined

Commit **`728b863`** turns Find into the shared compact search surface with explicit states (empty / searching / no matches / results / failure), `N of M` match navigation with Previous/Next, full-width snippet rows with the matched phrase emphasised, and part information for multi-part articles. `data/ArticleNavigation.kt` gains the match model; `ui/ReaderSearchField.kt` is reused; `ArticleNavigationTest.kt` adds focused cases.

## Stage 5 — Text boundary fix (§3D merged labels/headings)

Commit **`0d3c085`** diagnoses and fixes the merged-label defect. Root cause: chart legends and `display:block` eyebrow spans are inline tags whose two text runs touch with no whitespace in the source HTML, and a CSS-blind HTML-to-Markdown converter fuses them ("workersAll", "shareThe"). The fix reads the real computed `display` from the live page (`collectVisualBlockClasses`), carries the visually-block class tokens onto the noise-stripped clone, and inserts a single joining space only where both sides touch word characters with no existing whitespace (`separateVisuallyBlockInline`). Genuine inline formatting, punctuation and fenced code are left untouched. Chrome suite green (124 passed, plus the 18 loopback-relay tests when run with loopback access); two new `tests/extraction.test.ts` cases cover the positive fix and a counterexample. Existing stored text is deliberately left intact.

## Stage 6 — App-wide E-ink / NXTPAPER theme (§3B)

Commit **`6ca2a4d`** adds `ThemeMode.EINK` and a shared `DisplayPolicy` (monochrome + reduced motion) provided by `ReaderTheme` and inherited by nested reader themes, so the reading surface cannot silently drop the override. Commit **`f20828b`** closes two fidelity gaps: Compose article links now underline in the monochrome theme (native links already did), and the highlight colour pickers plus the Review colour marker render as black/white indicators instead of coloured swatches while keeping their accessible colour names.

- Monochrome palette: white ground `#FFFFFF`, black `#000000` primary, `#333333` secondary, white surfaces with a visible border, and selected-container inversion (black fill, white content) instead of hue. Links already carry an underline in both Compose and the native span builder.
- Reduced motion: `chromeFadeSpec()`, `settleSpec()`, `revealSpec()` collapse to `snap()` under the policy, and the native swipe-settle animation drops to 0ms; drag physics and direct touch scrolling are untouched.
- Non-colour state: the Important star badge and the highlight cards render in black/white with an accessible colour name, and the Speed/Review/reader loading spinners become static "Opening…" text.
- System bars follow the background's luminance, so the white e-ink ground gets dark icons.
- Appearance states that the app-wide E-ink theme owns the background and points at Settings › App theme; the saved reading background and font are preserved underneath and restored on leaving the theme.
- Settings labels the mode **E-ink / NXTPAPER**. `PrefsMigrationTest` covers the new stored value.

Checks: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug` and `:app:verify16KbAlignment` all **PASS**. QA package verification: selecting E-ink renders the Settings screen and the Inbox in monochrome, and the choice persists across an in-place reinstall (`qa-eink-settings.png`, `qa-eink-inbox.png`).

## Installed candidate — 2026-09-11c

In-place `com.reader.app` install from **`f20828b3e2b4c255419f5436523d5b5de7d49104`**: APK SHA-256 `8928ca661fad20f49cd14e3fe8a6c7640826534521bd5674ee08fdbe1347c107`, installed hash verified identical from the device `base.apk`, owner data preserved across 8 tables plus preferences (schema v13; documents 2, highlights 2, channels 1 before and after), no FATAL EXCEPTION on launch, and the owner's dark theme and saved article retained (`normal-post-install-eink.png`). Detail in `evidence/focused-20260910/installation-20260911c.json`.

The native menu/handle gate now asserts **both** modes, as §5 required. `HighlightMenuGateInstrumentedTest` drives the exact `ActionMode.Callback` the view installs: ordinary selection keeps Android's text-action menu and adds the app's **Highlight** action, while continuous highlighting clears the menu; it still drives pen-mode handles and edge-autoscroll. It passes on `com.reader.app.qa` (1 test, 0 failed). This is test-only, so the shipped APK is unaffected.

Two honest caveats from the same run:

- A fresh `assembleDebug` of the normal variant is **content-identical** to `8928ca66` (all 1329 zip entries match on name, method, sizes and CRC-32; compressed-size sum identical) but **not byte-identical** — the archived build carries ~636 KB more container padding, so the file hash differs. Byte identity earlier in this pass held only because Gradle skipped re-packaging. Treat entry-level content equality as the reproducibility check here. Canonical installed artifact archived at `artifacts/hardening/focused-20260910/reader-debug-f20828b.apk` (`8928ca66…`).
- The gate run's `connectedDebugAndroidTest` uninstalled `com.reader.app.qa` afterwards (AGP default) and discarded the QA corpus data. A fresh QA APK from `f20828b` (`2ef7bec9…`, `artifacts/hardening/focused-20260910/reader-qa-f20828b.apk`) was reinstalled so the QA target exists; its data is empty. Pass `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` on future connected runs to keep QA data. The owner's `com.reader.app` was not touched (`8928ca66…` before and after).

## Installed candidate — 2026-09-11b

In-place `com.reader.app` install from **`0d3c0859a98548f2d6ad1b4274da4b117c0cef46`**: APK SHA-256 `64a0cf406b730fe858e1805d9233ee7b400f5d50b139a357719b4cf12e6fce16`, installed hash verified identical from the device `base.apk`, owner data preserved across 8 tables plus preferences (schema v13), and no FATAL EXCEPTION on launch. This is the first install containing Inter, the refined Find sheet and the Chrome boundary fix. Detail in `evidence/focused-20260910/installation-20260911b.json`.

The installed artifact itself was opened and checked: the on-device `base.apk` contains `res/font/inter_regular.ttf`, `res/font/inter_bold.ttf` and the refined Find strings (`Word or phrase`, `No matches for`, `Return to reading position`) in its dex, so the shipped binary matches the source, not just the build inputs. The Inter commit's only change to `NativeArticleView.kt` is the typeface selection (`inter_regular` / `inter_bold`); selection handling, the native text-action mode and the 8dp inset are untouched, so the `18c9ac2` native end-of-text and native-selection results still describe this build. The copy contract was extended for Inter and the refined Find sheet in `9c3c58c`.

### Retest of the changed paths (isolated QA package)

The two paths that changed after the last device check were retested on `com.reader.app.qa` (QA APK `0d028a99aae3df0d1c37ee893e83a69a1681e3a49c75355d99130fcaf43f0248`, installed in place with the 98-article corpus preserved; the owner app was not touched):

- **Find** on a multi-part article: the blank state (`Word or phrase`, "Search the article text stored on this device."), the no-match state, and the results state (`document` -> `1 of 42` with Previous match / Next match and full-width snippet rows) all render; Next advances to `2 of 42` and moves the reader; dismissing the sheet leaves the compact match strip with Return to reading position; Return restores the original `2%` position.
- **Inter**: the Appearance font list offers Newsreader, Crimson Pro, Asul, Atkinson Hyperlegible, ABeeZee and Inter; selecting Inter persists `INTER` to the DataStore preferences and the article renders with no FATAL EXCEPTION.

Evidence: `evidence/focused-20260910/qa-retest-20260911.json` and `qa-find-*.png`, `qa-appearance-fonts-scrolled.png`, `qa-inter-article.png`. The normal `app-debug.apk` rebuild is byte-identical to the installed candidate (`64a0cf40…`), so its archived copy `artifacts/hardening/focused-20260910/reader-debug-0d3c085.apk` still matches.

## Remaining spec items — verified against the code (2026-09-11)

Every item in `FINAL_USABILITY_SPEC_20260911.md` is now implemented or explicitly superseded in this file. §3B (E-ink), §3C (Inter), §2E (Find) and §3D (text boundaries) are implemented as stages 3–6 above and shipped in the installed candidate. The one explicitly non-certified claim is true electrophoretic e-ink refresh quality, which TCL NXTPAPER testing does not establish.

## Tip commit and independent audit pointer

The product/code tip (and the installed revision) is **`f20828b3e2b4c255419f5436523d5b5de7d49104`** (`fix(android): underline Compose links and monochrome highlight swatches in E-ink`), on top of `6ca2a4d` (the e-ink theme), `4317e67` (test-only preference-decode extraction) and the `0d3c085` product chain. The branch tip is the docs-only commit `16ed4f2` above it. The installed revision is `com.reader.app` APK SHA-256 `8928ca661fad20f49cd14e3fe8a6c7640826534521bd5674ee08fdbe1347c107`, verified identical from the device `base.apk`, with owner data preserved.

The independent review/performance/UI/UX audit prompt for GPT Astra is [NEXT_AGENT_PROMPT_ASTRA_REVIEW.md](NEXT_AGENT_PROMPT_ASTRA_REVIEW.md), pointing at the tip commit and this handover. It asks for findings first (P0–P3) across review, performance, UI and UX, names the installed candidate and its hashes, and lists the guardrails (local only, use `com.reader.app.qa`, do not repeat the 100-article intake, no soak/matrix campaign).
