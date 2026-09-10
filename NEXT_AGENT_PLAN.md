# Reader: remaining implementation plan and TCL handover

> **Superseded where they conflict by [FINAL_USABILITY_SPEC_20260911.md](FINAL_USABILITY_SPEC_20260911.md)** (the user's final usability pass: four reading actions, true fullscreen focus, once-only highlight help, e-ink theme, Inter, complete adaptive highlight cards, focused Review, floating Review banner, Reader rename, text-boundary fix). Keep this document for the file map, isolation commands and verification history; follow the new spec for product behavior.

This is the executable plan for the next agent. **Work packages A–E below are now implemented and committed locally on `codex/reliability-finalize`. The installed normal `com.reader.app` debug candidate is `fd1b813` (shelf refinement) with APK SHA-256 `03cdfb15714568f9d63b51ad6fba94806728978bd6d4b502e3bb36d82d4b7916`; the preceding install was `99c5cf9` / `a7cc78a9…`.** Section 9 records the two previously-missing native checks, which now have TCL evidence. Do not interrupt the seven-day trial with unsolicited changes or repeated testing; when the user reports a trial issue, reproduce that exact path, make the smallest complete fix and repeat only the affected checks.

## 1. Exact starting point

| Item | Verified checkpoint |
|---|---|
| Checkout | `/Users/schober/Projects/Nostr Reader` |
| Branch | `codex/reliability-finalize` — keep work local |
| Prior baseline | `15a77ff647ac39f4e9165e47504d553504a1ac8b` |
| Implemented source / installed APK | **`b14f128c54996515f45218f8d912ef106c174446`**, `Refine reading, shelf, search and highlights for focused trial` |
| Following commits | Handover documents and evidence only; inspect `git log -3` for the documentation commit |
| Normal package | `com.reader.app`, debug, `0.9.0-beta.1`, version code 2 |
| Device | TCL T807D, Android 16, serial `ZXKRS4VKGQ8PWGEQ`; 1080 × 2340, density 456, system font scale 1.0 at handoff |
| Installed and packaged APK SHA-256 | `ee05b86a1e1024a7d0150742392d5349bef94da4c502fe75887f4081070910ca` |
| Local APK | `artifacts/hardening/focused-20260910/reader-debug.apk` — ignored by Git |
| Owner preservation | Exact pre/post-upgrade hashes matched for eight Room tables and preferences; Room v13, 1 article, 0 highlights, 0 channels; no clear/uninstall/key replacement |

The normal app was opened successfully after installation. It retained the owner's **Today** age filter and dark theme. If older articles appear missing during the trial, first inspect the visible constraints and use **Clear filters** if wanted; do not diagnose data loss from a filtered shelf count. The owner preference was deliberately left intact.

Read [FOCUSED_HANDOFF_20260910.md](FOCUSED_HANDOFF_20260910.md) for the implemented scope, test evidence, native position-fix history, isolation commands and file map. Read [docs/COPY_DECK.md](docs/COPY_DECK.md) before changing words or their behavior. [NEXT_AGENT_PROMPT.md](NEXT_AGENT_PROMPT.md) is the ready-to-use starter prompt.

## 2. Product objective and design decisions

The daily experience should make saving, returning to a passage and rediscovering highlights feel immediate and calm. The user now explicitly identifies **Review as a major feature**. This supersedes the earlier plan's treatment of Review as merely a secondary action, while preserving deliberate entry and keeping ordinary quote inspection separate from scheduling.

Keep three bottom destinations: **Shelf · Highlights · Settings**. Make Review the primary action within Highlights; do not add another root tab in this pass. Keep the default Highlights list sorted Newest. Preserve **Inbox · Priority · Later · Archive** without sideways scrolling. Familiar hierarchy and coherent geometry matter more than adding decoration.

Priority order: an observed data-loss/crash/position defect, if reported, takes precedence; otherwise implement **shared headers → compact search → prominent Review → Review reading comfort → restrained list polish → remaining focused checks**. Do not spend the next turn replanning these settled choices.

## 3. Fresh TCL observations that drive the work

The adjacent evidence files are actual screenshots from the QA package built from `b14f128`, with real saved articles/highlights. They are not mockups. The device's visible red/pink cast also appears in the normal app and prior captures; its cause was not established. Do not derive a replacement palette from these screenshots. Preserve Flexoki and check the existing theme values.

| Observation | Evidence / diagnosis | Consequence |
|---|---|---|
| Shelf and Highlights title baselines differ | [Shelf](evidence/focused-20260910/handover-shelf.png), [Highlights](evidence/focused-20260910/handover-highlights.png). Both use `headlineMedium` in `InboxScreen`, but Shelf's 48dp icon buttons determine a taller row; Highlights has no actions and its row collapses to the text height. | Fix the shared row geometry and insets, not independent font-size compensation. |
| Search looks like a large form field | Both live surfaces use full-width default `OutlinedTextField`; Highlights' long placeholder visually dominates the page directly beneath its title. | Use one smaller, quieter search component with consistent inset and interaction behavior. |
| Review is visually indistinguishable from a minor option | Highlights places `Review` in a row with Newest, Important and Shuffle, with no hierarchy or explanation. | Give Review a dedicated, visible primary entry. |
| Search history lacks reading order | [Shelf search](evidence/focused-20260910/handover-shelf-search.png): centered query text, large isolated removal crosses and long guidance. | Left-align history rows, establish a compact heading and reduce explanatory copy. |
| Keyboard leaves little useful result area | [Shelf with keyboard](evidence/focused-20260910/handover-shelf-search-keyboard.png), [Highlights with keyboard](evidence/focused-20260910/handover-highlight-search-keyboard.png). Title/tabs/search/guidance stack consumes much of the available space. | Keep essential context visible, shorten controls/copy and let the results region scroll correctly. Do not solve this by shrinking touch targets. |
| Highlight previews look visually heavy | Large uniformly colored rectangles and repeated source text compete with the quote and next row. | Reduce preview decoration while retaining clear saved color and six-line readability. |
| Review does not feel like the main reading experience | [Review](evidence/focused-20260910/handover-review.png): small right-aligned header, very large Asul quote on a solid color panel, and no visible cycle context. | Give Review a coherent compact header, comfortable quote measure and truthful progress. |
| Saved publisher suffixes consume shelf space | Repeated `| web.dev` appears in article titles and again in metadata; long rows wrap unnecessarily. | Verify the new extraction cleanup once; consider only a narrowly justified display-title treatment for existing articles. Canonical text and provenance remain unchanged. |

## 4. Work package A — consistent destination headers

**Files:** `ui/screens/InboxScreen.kt`, `ui/screens/SettingsScreen.kt`, `ui/theme/Tokens.kt`; a small shared header component under `ui/` is appropriate. Paths below are relative to `android/app/src/main/java/com/reader/app/` unless stated otherwise.

Implementation:

1. Extract `DestinationHeader(title, actions)` with one typography token and one minimum height. Starting specification: 52dp minimum content row, 20dp leading inset, 8–12dp trailing inset, existing Atkinson `headlineMedium`, common vertical alignment. Let height grow for enlarged text; never hard-clip it.
2. Reserve the same row height with no actions. Changing destination must not shift the title baseline. Apply the same status-bar inset ownership exactly once. Keep Shelf's Search and Add targets at least 48 × 48dp.
3. Apply the shared component to Shelf and Highlights first, then align Settings if it uses a different pattern. Selection mode may replace actions/copy but should not cause a sudden header-height jump at default text scale.
4. Keep the four shelves under the header and retain their existing two-row behavior for enlarged text. Keep bottom-navigation label typography identical; the observed discrepancy is in the large screen titles, not evidence that the bottom labels need different font sizes.

Acceptance: switch Shelf → Highlights → Settings → Shelf on TCL. Title font/weight, left edge, baseline and status-bar spacing match. Tap targets remain accessible; no title/action overlap at font scale 1.4. Re-selecting the active destination preserves its scroll position. A single screenshot comparison is enough; do not create pixel-mirroring unit tests.

## 5. Work package B — compact, beautiful search with explicit state

**Files:** active search block in `InboxScreen.kt` around line 212; `HighlightsFeed.kt` around line 96; `MainActivity.kt` search/query/scroll state; shared theme tokens. `InboxScreen.kt` also contains an older private `SearchContent` implementation around line 790: trace actual call sites before editing. The active UI is the inline block; do not polish dead code and mistake it for the current screen.

### Shared visual component

1. Introduce a shared `ReaderSearchField` presentation component, without merging the article and highlight search engines. Both fields use the same 20dp screen inset as the title and content.
2. At default text size use **48dp minimum height**, 12–16dp rounded corners, 12dp internal horizontal padding, a 20dp search icon and 16sp Atkinson text. Use a subtle existing Flexoki surface, a quiet boundary and a clear focus treatment. Avoid the current large empty form outline. Measure the resulting field on TCL; reduce unused decoration/padding rather than clipping the editable text.
3. Use short placeholders: **Search articles** and **Search highlights**. Put field-specific explanation outside the field only in a relevant empty state: highlight search covers quote text and source titles; article `#` autocomplete is an article filter feature.
4. Show a trailing clear action only for nonempty text. Preserve a 48dp touch target and labels **Clear article search** / **Clear highlight search**. A leading search icon is decorative when the field itself has a proper editable accessibility label.
5. For larger system text, allow the field height to expand. Check cursor, descenders, selection, clear action and keyboard insets. Do not lower accessible text size to force 48dp.

### Interaction and information hierarchy

1. Shelf search opens intentionally from its header. The field receives focus and the keyboard once on explicit opening; returning from an article restores the query/result position without reopening the keyboard or focusing repeatedly during recomposition.
2. Use the search IME action in both destinations. Submitting dismisses the keyboard while retaining results, query and constraints. Clear text does not clear label/scope/age constraints. Exiting the search UI retains its last query/result state for deliberate re-entry; normal Shelf ignores an inactive query.
3. Back dismisses keyboard first, then active autocomplete/temporary search UI, before changing destination. Do not silently erase a query because Back was used to hide the keyboard. Preserve article-return behavior.
4. Keep location and field choice explicit but compact: one small context row, e.g. **All saved articles · Title and text**, plus Filter. Show removable active label/age constraints; do not reintroduce a permanent date-chip strip. Current-shelf scope must name the actual shelf.
5. Shorten blank-search guidance to **Use “quotes” for a phrase or # to choose a label.** Use secondary body text and show it only when useful. Put a small **Recent searches** heading above left-aligned history rows. Each row has a history icon, query and a restrained trailing Remove target; query and Remove are separate actions.
6. Retain visible `#label` suggestions, including multiword labels. Selecting a suggestion adds its existing stable ID as a removable constraint and removes only the autocomplete fragment. Do not create or assign a label. Suggestions should not push all results out of view; cap the existing list and keep the region reachable with the keyboard.
7. Keep loading, invalid query, empty results and failure distinct. Do not invent indexing status for the active SQLite implementation. Retain title/text versus titles-only behavior and constraints before paging limits; do not replace this with cosmetic filtering of the first 50.
8. In Highlights, keep Newest as the default order and Important as a filter. Make Shuffle an explicit secondary action with a visible selected order when active; currently the action does not provide the same clear selected-state feedback as Newest. Never imply that changing feed order advances Review.

Acceptance: one existing query in each destination; clear; submit; article/quote visit and Back; one invalid quoted query; one existing multiword label; one Important-empty state; one query with >50 results and **Show 50 more**. Check count/constraints and preserved position. Use the existing 100-article QA corpus, not new intake. Run only the relevant search/state tests if behavior changes.

## 6. Work package C — make Review a primary feature

**Files:** `HighlightsFeed.kt`, `MainActivity.kt`, `ui/screens/ReviewScreen.kt`, `core/ReviewScheduler.kt`, `data/HighlightRepository.kt` (`ReviewRepository`), `data/Highlights.kt`.

### Entry and hierarchy

1. On unfiltered Highlights, place a compact **Review highlights** entry directly below the shared title and before search/list controls. It should be the page's single primary action, using a restrained filled button or a quiet actionable card. Starting layout: approximately 72–88dp at normal text size, title + one short purpose/state line + clear action; grow naturally for large text.
2. Copy: **Revisit your saved passages.** Action: **Start review** when no prior cycle exists; **Continue review** for an unfinished existing cycle; **Review again** for a completed cycle. Use only states the repository can establish. With no highlights, explain **Save a passage while reading to start a review** and offer the existing latest-read route if available.
3. Remove the old equal-weight Review text button beside Shuffle. Do not add multiple competing primary buttons. Once a search is actively focused/nonempty, condense the Review entry to a compact accessible action so search results have room. Returning to unfiltered Highlights restores the full entry.
4. Keep Review entry independent of quote search and Important filters. Label its scope as **All highlights** if necessary. A filtered feed must not silently redefine or reset the persisted review cycle. Filtered review sessions are outside this design pass unless explicitly requested.

### Truthful state and persistence

1. `ReviewScheduler` currently provides a seeded base cycle followed by a bounded Important bonus. It has **no calendar due-date schedule**. Do not write “due today,” “daily goal” or “spaced repetition” claims unsupported by this code.
2. Add a read-only review-summary interface if needed. Do not call `ReviewRepository.resume()` merely to render a card: it writes persisted state. Read enough existing state to distinguish absent/ongoing/done without starting, advancing or repairing a session as a side effect of visiting Highlights.
3. An entry button may deliberately call the existing resume/start behavior. Keep quote inspection read-only with respect to Review. Preserve current serialization/expected-ID guards in `advance`, Important and source-opening operations.
4. For progress, prefer a simple truthful count such as **N remaining in this round**, explicitly derived from the current phase/current item and pending queue. Do not compute a fictitious fixed “X of Y” from total highlights while Important bonus items can follow. Show **Revisiting Important highlights** in the bonus phase if that is the active state. Avoid duplicate counts or a precise duration estimate without a valid basis.
5. Handle removed highlights/source articles through existing repository reconciliation. Saved quote and attribution must remain readable when the source is gone. An unavailable source action should explain that state, not suggest the quote is missing.

Acceptance: Highlights shows Review prominently on first arrival; empty state is actionable; Continue returns to the same quote; inspecting/searching a quote does not advance Review; one Next advances once; leaving and returning preserves the current cycle; Important still affects the existing bounded bonus behavior. Add one meaningful read-only-summary/entry-state test if introducing that interface. Do not add a giant new scheduler campaign.

## 7. Work package D — make Review comfortable to use

1. Replace the current small right-aligned Review title with a compact secondary-screen header: Back at the leading edge, **Review** next to it, coherent shared typography/insets, optional quiet progress beneath. Keep bottom navigation hidden in Review.
2. Reduce the current oversized Asul presentation. Start with the selected reader font (Newsreader by default), approximately 22sp and comfortable line spacing at default settings; respect system text scaling. Long quotes should remain easy to read, with no forced giant heading style. Test one short and one six-plus-line quote from the existing corpus.
3. Use a neutral reading surface with a small saved-color indicator or restrained tint. Preserve each quote's stored color but avoid covering the whole large paragraph with a saturated block. Maintain readable foreground/background contrast in both app themes. Do not mutate saved quote color to achieve the presentation.
4. Keep source attribution visually attached to the quote. Provide a distinct **Open source** action with source title, rather than making every tap on the reading paragraph unexpectedly leave Review. Preserve return-to-same-quote/scroll behavior. The whole quote should be available without competing horizontal/vertical gesture handling.
5. Keep the bottom actions reachable: Important, Share, Next. Text labels remain available as alternatives to swipes. Standardize spacing and button height. Allow wrapping at enlarged text without covering the quote's last lines.
6. Preserve direct manipulation: no animated movement while the finger is down. Honor reduced-motion preferences. Existing card translation/rotation can be toned down; removing flourish is preferable to adding a new animation framework. Check Next/swipe cannot issue duplicate advancement.
7. The completion state should say **Review complete** and offer **Back to highlights**, with **Review again** secondary. Remove unnecessary congratulatory filler. Exiting halfway is normal and resumes later; avoid streak/goal pressure.

Acceptance: one short quote and one long quote, source round-trip, Next once, Important once, Back/continue, and one enlarged-text observation. Keep quote inspection separate; do not use inspection as a replacement for Review.

## 8. Work package E — focused shelf/highlight craft

These follow the three user-named priorities. Implement only the concrete refinements below; do not expand the app's feature surface.

- **Highlight feed:** maintain attribution + at most six preview lines + **Read full highlight**. Use consistent left edge, quieter tint/color marker and spacing between source, quote and actions. Keep each overlapping saved highlight individually reachable through the existing action machinery. Match selection/Important state and single-remove Undo across feed/detail/reader.
- **Shelf (done in `fd1b813`):** the site marker is now a 3 × 22dp 60%-alpha accent aligned to the title line instead of a 3 × 38dp full-opacity block, and the in-progress stroke uses a muted secondary tone rather than full-strength text colour, so the title is the dominant element. Continue reading stays a compact single entry (label + title), source and remaining time stay one meta line, and unread / in-progress / At end / Finished / Link only remain distinguished by text. The marker never carries meaning on its own - the source name sits directly beneath it. Before/after: `handover-shelf.png` → `ux-shelf-refined.png`.
- **Metadata/title duplication:** the new web.dev extraction cleanup already exists and passed its focused host test. Verify one newly saved real source when relevant. Do not rewrite existing canonical Markdown or attribution. Any display-only removal of a redundant publisher suffix must be narrow, reversible and based on a known suffix/source match, not a generic split on `|` that could damage real titles.
- **Empty and filtered states:** show which constraints caused no matches and a working Clear filters action. Include unused labels in management; preserve stable IDs after rename and merge. Distinguish an empty library from an empty selected shelf. At the normal installed checkpoint the Today filter is real owner state, not an invitation to clear it automatically.
- **Time-sensitive constraints:** check Today/age filtering on resume when a reported day-boundary issue occurs. This is a plausible remaining lifecycle edge, not a verified current bug; do not perform overnight/soak testing to investigate it preemptively.

Acceptance: inspect one shelf with Continue reading and one Highlights screen after the above work in light and dark themes. No clipping, inconsistent gutters, oversized low-value decoration or hidden primary action. Keep existing swipe/menu parity and independent scroll state.

## 9. Remaining correctness checks from the prior implementation

Use this bounded list to avoid both false completion claims and a new acceptance campaign:

- [x] **Native final-end fix:** re-checked on the QA package (`c8272619…`) on TCL after the change. Natural flings to the end showed `100% · End of article` with the quiet **Finish & archive** / **Back to shelf** card stable across a 3s re-read; the article's shelf row afterwards read `2 min left` and `At end`, not finished, so reaching the end wrote no completion credit; three downward swipes scrolled back to 79%. Evidence: `ux-end-of-text.png`.
- [x] **Native selection after continuity fix:** press-and-hold on the QA reader produced native selection with handles and the floating toolbar (Copy · Highlight · Share · Select all); one handle was dragged to extend the range; invoking Highlight saved exactly one new record (5 → 6 highlights, no duplicate `(canonicalStart, canonicalEnd)` pairs) whose quote and prefix/suffix context match the selection. Evidence: `ux-native-select-1..5.png`. Note: `adb input tap` did not register on the *floating* toolbar window, so the app's own bottom-dock Highlight action was used — a harness limitation, not a product defect. The wide `canonicalStart/End` on a mid-block selection is by design (`HighlightAnchors.create` stores the block's canonical span; the precise anchor is `startBlockId/startOffset` + quote + context).
- [ ] **Position:** not re-run this pass — the reader machinery was untouched. Still valid to test one middle passage through appearance/bold and Back/reopen, and one Speed return, if that code path changes.
- [x] **One enlarged-text pass:** font scale set to 1.4 on QA, then restored to the original 1.0. Shelf showed the shared header, four shelves wrapping to two rows, Continue reading and wrapped article titles; Highlights showed the Review card (title wrapping, no clipping), the compact search field and the order chips. Evidence: `font14-shelf.png`, `font14-highlights.png`. Not an exhaustive accessibility matrix.
- [ ] **Search UI combinations:** the compact field with the IME open, blank guidance and left-aligned recent-search rows were re-observed after the crash fix. Multiword `#` autocomplete, >50 paging UI and all active-constraint combinations remain unexercised; cover only the small package-B checklist, not every query.
- [ ] **Content targets:** one expanded table and one captured local footnote when the corpus supplies them; otherwise report that specific target unavailable. Do not manufacture coverage by rewriting text or adding a synthetic corpus.
- [ ] **Highlight removal:** overlapping-highlight reachability and multi-remove confirmation remain uninspected combinations. Exercise one relevant pair and one small multi-selection if this surface is changed. Full-metadata Undo has prior device foundation coverage.

Already complete and not to be repeated without a relevant change: **100 distinct real saves — 50 actual Chrome extension + 50 Android user-facing links**, all identities/statuses checked, zero missing/duplicate documents or terminal/link-only failures at intake; five representative articles visually inspected; 226 Android unit tests; 140 Chrome tests; three TCL foundation and two focused UI contracts; brief offline reopen; Listen start/progress/pause/close; normal final lint/build/16KiB alignment and installed hash/owner preservation. Exact boundaries are in the checkpoint document. Public-relay reliability and prolonged comfort are not proven by isolated local-relay intake.

## 10. Execution sequence, validation and final delivery

1. Read this plan/context/copy deck, inspect the branch and installed identity. Use existing screenshots to understand the three observed defects; one fresh TCL reproduction is sufficient if state has changed. Preserve user changes made during the trial.
2. Implement A+B together because they share title/search layout. Commit locally with relevant copy updates. Check the two destinations once on TCL, including the keyboard. Reuse one presentation component; do not rewrite application state/storage.
3. Implement C+D with a small read-only Review summary if necessary. Commit locally. Check entry/resume/Next/source/inspection isolation and one long quote. Keep scheduler semantics unchanged.
4. Implement E only where the fresh screens still show the documented defect. Resolve any failure found in step 9 with a minimal fix; repeat only that affected path.
5. Run existing fast units and lint/build/alignment once for the final candidate, plus focused tests covering changed behavior. Chrome code is outside these UI changes, so do not rerun the 50+50 intake or a full browser campaign. No 1,000 articles, 10,000 highlights, 120-minute mixed session, soak, statistical benchmark or exhaustive device/lifecycle matrix.
6. Keep QA work isolated in `com.reader.app.qa` and the existing isolated Chrome profile. The controller/relay process was stopped at handoff; do not assume old process IDs or expired local TLS credentials remain valid. If restarting the harness is genuinely needed, follow its script, never expose its state file, and do not replace the owner's pairing.
7. After the explicitly requested implementation task is complete, install the normal debug package in place, verify exact installed hash and preserve owner data. Record source SHA/APK SHA, changed features, actual focused checks and known issues. Keep local commits clean; **no push, merge or release**.
8. Update this plan's checkboxes and the copy contract around the actual delivered behavior. Leave the user to assess sustained comfort over seven days. Do not claim “world's best,” full acceptance or exhaustive reliability from these bounded checks.

### Non-negotiable boundaries

Preserve Room v13, canonical article text/projection version/quote anchors, capture security, encryption, existing keys/pairings, explicit preferences and owner articles/highlights/review history. Reuse the existing reading-session and highlight-mutation machinery. The current interfaces are Library search request/result, per-destination scroll/filter state, reader inspection origin/cursor, FinishReceipt and NoticeCoordinator. Do not add a general architecture rewrite.

Offline image storage, highlight notes, pagination mode, background audio, accounts, cloud processing and telemetry remain outside this cycle. Export is not a complete restorable backup. Review cycle improvements in this plan concern presentation and read-only summary, not an unrequested new scheduling system.

The next agent may use a subagent only if the user authorizes it for that task. If so, a bounded read-only design/code review or a disjoint shared-header/search implementation is useful; keep one owner of `MainActivity.kt`/`InboxScreen.kt` and serialize physical TCL interaction. No delegation or new task was started during this handover.
