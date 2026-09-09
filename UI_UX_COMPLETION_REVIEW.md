> Historical pre-implementation review. Completed work and final evidence: [UI_UX_COMPLETION_REPORT.md](UI_UX_COMPLETION_REPORT.md).

# Restrained reader refinement: completion review and plan

Reviewed 9 September 2026 against the supplied implementation brief.

## Verdict

**Substantial implementation progress; the full goal is not yet achieved sufficiently for acceptance.** Keep the refinements already made. Complete a focused correctness and accessibility pass, then qualify the exact integrated APK. Another redesign is unnecessary.

This is a source, artifact and test-evidence review, not a new hands-on visual or TalkBack audit. The findings below distinguish code defects from layouts that still need measurement.

## Verified baseline and scope

- Current local candidate: `codex/reliability-finalize`, HEAD `f7b443c`. Working tree was clean at review start.
- Refreshed `origin/main`: `1e55b7d4021637520402cb1773ac4e1ffd3b43cf`, the brief's baseline. The candidate contains subsequent hardening and UI changes; remote main does not yet contain them. Publication or merging is not required by the supplied visual-work brief.
- Core refinement commits: `a202413`, `71c7abe`, `9260dd8`, `1d5ec87`; focused tests: `45be653`.
- Subsequent changes `0018130`, `70c18c1`, `f7b443c` add highlight gestures and quote/article multi-select. These exceed the original deferral of multi-select. Their later authorization was not established by this review. Preserve them while confirming the current scope; do not automatically revert newer work.
- Read README, UI_REFRESH, ARCHIVE_REFRESH, VIEWPORT_FIX, KNOWN_LIMITATIONS, reliability documentation, current screen/native/theme code, regression tests and artifact records.
- The supplied attachment directory contains the brief only, not its seven referenced screenshots. The four repository screenshots explicitly describe an older build (`66d8527`), so they cannot certify the current refinement or substitute for the seven references.
- Connected physical device: TCL T807D. Its installed normal APK and local `android/app/build/outputs/apk/debug/app-debug.apk` both hash to `e49336674eefa01317d4c49e02aa6a951c3e2ce932012be9938c728e18254059`. This is byte identity, not proof of the build's complete source provenance or successful interaction tests. No install, restart, deletion or profile change was performed.
- Fresh current-source check: `./gradlew --offline --no-daemon --max-workers=2 testDebugUnitTest lintDebug` **PASS**; 131 unit tests, zero failures/errors/skips; debug lint task passed. Release tests, full build gates and device instrumentation were not rerun in this review.

## Requirement assessment

| Brief area | Current evidence | Assessment |
|---|---|---|
| Follow newer hardening | Candidate descends from baseline and includes Room v10/transition finalization | Correct source sequence; finalization still lacks current device qualification |
| Duplicate title | Display-only comparison hides metadata for a normalized matching first H1; seven unit tests pass | Implemented conservatively; H2+ matches deliberately remain, narrower than “first rendered heading” in the brief |
| Calmer dock | Inactive Highlight has no elevated filled capsule/yellow label; minimum 48 dp maintained | Implemented; final visual/layout acceptance outstanding |
| Real color swatches | Four logical colors, visible swatches/checks and accessible selected text/state | Implemented; actual TalkBack traversal, contrast and live-selection checks outstanding |
| Appearance | Vertically scrollable content, wrapped margin/background choices, font rows retain touch height | Implemented; narrow landscape and large-text reachability not qualified |
| Archive/navigation | Redundant Archive icon removed; settings/pairing pop instead of resetting; article-list scroll states hoisted | Partial: Highlights scroll state is not preserved equivalently |
| Gestures/accessibility | Native article selection/edge guards and explicit menus remain; archive cancellation separated | Partial: normal rows and highlight rows can commit on cancellation |
| Full-screen review | Existing review model/buttons preserved; quote scrolls and buttons wrap | Implemented structurally; long-quote, large-font and source round-trip behavior need device proof |
| Fling investigation | Earlier hardening measured velocity, distance and frame timing; zero post-release momentum on TCL and S23 | Investigation substantially done; final disposition/fast-navigation decision not recorded |
| Final artifacts and acceptance | Old scoped reports and screenshot provenance exist; installed/local APK match | Incomplete: no current comprehensive interaction/accessibility report and app/test/screenshot manifest |

The 8 dp native bottom padding, selectable TextView ownership, quote-only share payloads and theme-independent stored color names remain in source. Existing storage/review tests provide useful historical coverage, not certification of every final-device acceptance scenario.

## Material findings

### 1. High priority: cancelled gestures can execute actions

`InboxScreen.kt` → `SwipeRow` and `HighlightsFeed.kt` → `HighlightSwipeRow` wire both `onDragCancel` and `onDragEnd` to `settle()`. That function applies an action whenever the recorded offset exceeds the threshold. A cancellation after crossing the threshold can therefore move a normal article or remove a saved quotation. Highlight removal has no Undo. This follows directly from the callback code; a physical interrupted-gesture reproduction was not performed.

`ArchiveSwipeRow` already resets without committing on cancellation. The native article path likewise checks ACTION_UP before committing. Preserve those semantics.

### 2. Medium priority: Highlights loses navigation context

`MainActivity.kt` retains separate `LazyListState`s for Inbox, Priority, Later and Archive. `HighlightsFeed.kt` creates its `LazyColumn` without a passed/hoisted state. Leaving the route removes that local state from composition, so returning from Review, source reading or Settings can reset the feed position. Stable shuffle seed and sort mode do not preserve its visible row/offset.

The added RouteStack test proves destination popping, not actual list or quote scroll restoration. Those need screen-level tests.

### 3. Medium priority: accessibility work is incomplete beyond Appearance

The reader dock is still one unweighted horizontal row. Highlight-edit actions are still in a non-scrollable dialog column. Library headers, Highlights filters and selection toolbars also remain fixed rows. These are specific risk locations for narrow landscape/large font, not visually reproduced clipping findings.

The Highlights hint describes swipes and long press, but rows have no explicit per-quote action menu. Important is reachable through Review; removal relies on swipe or long-press selection. Provide discoverable, named alternatives and verify the complete TalkBack path. The Appearance size slider also needs a verified accessible name/value; its adjacent visual label alone does not establish that.

### 4. New multi-select needs bounded integration checks

Two source issues deserve tests if the newer feature remains in scope:

- Archived row body taps are suppressed by `!selecting`, even though the caller supplies a selection-toggle action. Checkbox toggling remains possible, but body-tap behavior differs from normal selected rows.
- Batch Unarchive calls the single-item callback repeatedly. Each writes one shared `readerMove` slot; this does not represent a reliable batch Undo even though README describes batch moves with Undo.

Also test disappearance of selected items during incoming updates: article selection pruning only keys on tab/archive mode, unlike the quote feed's live-list keyed pruning. Do not expand this into a broad batch-management feature.

### 5. Completion evidence is materially stale

UI_REFRESH, ARCHIVE_REFRESH, VIEWPORT_FIX, ARTIFACTS and repository screenshots describe earlier checkpoints. HARDENING_TEST_REPORT describes `7dbb869`; RELIABILITY_HARDENING explicitly leaves newer finalization device checks unmeasured. Fresh host tests do not close the requested TalkBack, configuration, gesture and exact final APK gates.

## Ordered completion plan

### Step 1 — Freeze the candidate and acceptance contract

1. Start from the reviewed candidate or a newer integrated descendant. Record exact HEAD and worktree state; preserve newer unrelated work.
2. Confirm hardening/capture integration has stopped editing the conflict-prone reader/navigation files before implementation begins. Work sequentially in one integration checkout.
3. Record whether newer multi-select is accepted scope. Until resolved, preserve its code and include its regressions in qualification.
4. Locate the seven original references among the original task's attachments. If unavailable, request them for comparative visual acceptance. Functional fixes and test preparation can proceed independently; never claim a seven-image comparison without them.
5. Create an acceptance matrix with each row marked PASS, FAIL, NOT MEASURED or BLOCKED, and a field for exact evidence/artifact identity.

**Exit:** one named candidate and an explicit, bounded acceptance checklist; no redesign, extraction change or navigation framework replacement.

### Step 2 — Fix cancellation before cosmetic follow-up

1. Separate “reset cancelled drag” from “commit released drag” in normal and highlight rows. Cancellation must never invoke a move, importance or removal callback.
2. Ensure queued offset updates cannot re-arm an already cancelled gesture. Reset displacement and tap suppression consistently.
3. Add input-driven regression tests crossing both thresholds, then cancelling; assert zero callbacks and unchanged stored data. Include release-after-threshold, retreat-below-threshold, vertical takeover and exactly-one action on release.
4. Retest Archive and native article paths without changing their permanent-delete threshold, no-confirmation or no-Undo policy.

**Exit:** cancellation produces zero mutations, including saved-quote removal; deliberate releases retain existing actions.

### Step 3 — Complete navigation and bounded multi-select correctness

1. Hoist/pass Highlights list state alongside existing article-list states. Keep stable item keys and existing sort order; no new routing system.
2. Verify Inbox/Priority/Later/Archive → Settings → Back; Highlights → Review → Source → Back → Back; and list → Reader → Back. Assert destination, selected tab, visible item identity and pixel offset within a small documented tolerance.
3. Preserve position through activity recreation where the brief requires persistence. Define intentional behavior when the anchored item is removed or sort order is changed.
4. Verify incoming articles do not steal Priority/Later/Highlights/Archive and that loaded-empty Inbox follows the existing hide/fallback behavior without flashing or resetting another tab.
5. If multi-select stays: make archived body taps toggle consistently, prune stale selected IDs on list changes, and represent batch Undo as the successful group with each item's prior state. Report partial failure truthfully and do not add batch permanent article deletion.

**Exit:** screen tests prove context preservation, not only route-stack operations; retained batch behavior matches documented Undo.

### Step 4 — Close actual layout and accessibility failures

1. Capture baseline candidate screens using synthetic articles/quotes, then inspect dock, Appearance, highlight-edit dialog, library header, Highlights controls, selection bars and review.
2. Test a narrow portrait viewport, landscape, maximum system font, enlarged display scale and the combined worst case. Record actual dp bounds and scale values, not just device names.
3. Where controls fail, apply wrapping or constrained scrolling locally. Keep primary actions and dialog dismissal reachable; preserve useful article space and the native 8 dp inset. Avoid solving fit by shrinking touch targets or readable labels.
4. Verify Paper/Soft/Ink/Black, light/dark/system changes while reading, all fonts and the immediate appearance preview. Exercise active selection during font/theme/margin changes and verify the selected quote and reading position remain stable.
5. Run TalkBack: Back, Appearance, named font/size/margin/background controls, pen state, all four named color choices, article moves, retained-source state and Important/Next/Share. Add explicit labels/states or row menu actions where traversal reveals gaps.
6. Measure text, selected/check indicators and focus contrast in both light and dark. Test long retained quotations and overlapping marks, including reaching every editing action.
7. For title suppression, decide and document whether only first H1 is intended. If the brief includes matching H2+ first headings, generalize only the display comparison, adding fixtures for different headings, inline formatting, Unicode and later parts. Never edit canonical text, hashes or anchors.

**Exit:** exact APK screenshots and interaction results demonstrate reachable controls; every untested configuration remains explicitly unmeasured.

### Step 5 — Resolve fling with the existing measurements

1. Reuse the valid hardening measurements: TCL −8,460.6 px/s with 1,089 px in-touch scroll and 0 px momentum; S23 −12,331.5 px/s with 1,485 px in-touch scroll and 0 px momentum. Recorded frame samples do not justify calling this jank.
2. On the final native view, reproduce a small controlled sample with pen off/on and selection absent/present. Record touch velocity, in-touch and post-release distance, bounds and frame timing; distinguish native behavior from interception.
3. If deliberate fast navigation is still required, choose the smallest native-selection-compatible behavior supported by the experiment, then test interruption by touch/selection and cursor persistence. Do not wrap the TextView in ScrollView.
4. Otherwise document the measured limitation and obtain an explicit deferral; do not silently count unchanged no-momentum behavior as delivered fast navigation.

**Exit:** a documented explanation and either verified behavior or an explicit accepted deferral.

### Step 6 — Qualify and package once after integration

1. Run full relevant Android unit suites, lint, app and matching test APK builds on final source. Because this candidate includes newer hardening, rerun its affected selection journal, migration, transition and review checks on the final pair.
2. Run Chrome tests/typecheck/build/package checks for the integrated candidate. Preserve one-click capture and truthful feedback; use synthetic data/local controlled transport only where a regression requires it. Public relay campaigns are outside this UI acceptance.
3. Run the device acceptance matrix on an isolated QA package. Check all colors, pen on/off, native explicit Highlight, handles across the viewport, overlapping marks, exact quote-only share contents, retained quotes after synthetic source deletion, gestures/cancellation and Android edge Back.
4. Capture before/after screens from reproducibly identified baseline/final APKs. Record source commit, build command, SHA-256 for app and matching test APKs, package ID, device model/OS, orientation, font/display scales and theme for each group. Separately verify any normal installed artifact; QA results do not automatically certify different normal bytes.
5. Write a concise interaction/accessibility report and update the current artifact index and limitations. Keep historical reports explicitly historical; do not publish private-article screenshots.
6. Keep fixes in small focused commits. Report prepared/installed/merged separately; do not push, merge, publish or alter the normal profile as part of this review plan without the corresponding authorization.

**Done means:** cancellation defects fixed; list/source round trips proven; controls and non-gesture alternatives usable in the required configurations; original reader/storage semantics preserved; final app/test/screenshot evidence tied together; and every remaining acceptance gap explicitly agreed or unmeasured. Green unit tests alone are insufficient.
