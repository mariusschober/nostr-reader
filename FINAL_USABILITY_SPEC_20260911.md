# Reader: final usability fixes before the seven-day trial

## 1. Starting point, decisions and delivery

Implement this as one coordinated Android pass on **`codex/reliability-finalize`**.

- Current implementation: **`fd1b813`**, following `99c5cf9`.
- Current documentation commit: **`f98aa9f`**.
- The installed TCL APK matches DeepSeek’s handover: SHA-256 **`03cdfb15714568f9d63b51ad6fba94806728978bd6d4b502e3bb36d82d4b7916`**.
- The working tree is clean. This planning turn has not changed application code or installed another build.
- Preserve Room v13, existing article text, quote anchors, owner data, preferences, keys and pairings.

The agreed design decisions are:

| Area | Decision |
|---|---|
| Reading controls | One row of four icons with labels: **Highlight · Contents · Listen · Speed** |
| Focus mode | Hide all app chrome and Android system bars; reclaim their space |
| Highlight cards | Full quote, adaptive typography, compact footer actions |
| Tapping a quote | Enter Review at that quote while preserving an unfinished round |
| Review entry | Floating bottom banner; hides when scrolling down and returns when scrolling up |
| E-ink option | Monochrome, reduced-motion presentation across the entire app |
| Extra fix | Diagnose the merged chart labels/headings visible in the current TCL article |
| Naming | Rename the visible **Shelf** destination to **Reader** |

After implementation begins, assign the whole task to **“DeepSeek Finalization of Nostr Reader V0.9”**, task ID `01a08d24-7a4b-7f51-b7ef-5c5d4b892168`. This is the agreed alternative to a child agent: DeepSeek is available through that existing task, but not through this session’s child-agent model selector.

DeepSeek owns implementation, focused tests and candidate preparation. The supervising agent reviews the changes and evidence, coordinates TCL access and verifies final installation. Only one agent edits the checkout or operates the phone at a time.

The new specification supersedes conflicting older requirements: six-line highlight previews, tapping cards to inspect rather than Review, the top Review card, two reading actions, and a general-purpose “Back to top” button.

## 2. Reading experience

### A. Four directly accessible reading actions

Replace the two-action row with four equal-width controls:

| Control | Icon and behavior |
|---|---|
| Highlight | Highlighter icon; enters continuous highlighting |
| Contents | List icon; opens the existing heading navigation |
| Listen | Speaker icon; starts or resumes listening at the current passage |
| Speed | Speed-reading icon; opens the existing speed reader at the current passage |

Implementation requirements:

- Use icons above short labels, 20–22dp icons and approximately 12sp interface labels.
- Each action has a minimum 48dp touch target. The normal row should occupy approximately 56dp.
- At enlarged text sizes, allow a two-by-two layout when labels no longer fit. Never horizontally scroll the controls or shrink accessibility text.
- Retain the restrained percentage/time line above the actions.
- While speech is playing, the Listen action becomes **Pause**. Resuming uses the existing speech session rather than restarting the article.
- Entering Speed pauses speech and flushes pending reading/selection changes. Exiting returns to the actual resulting passage.
- Continuous highlighting replaces this row with its color controls and **Done**; the four actions return immediately on exit.
- Keep menu equivalents for accessibility and discoverability.

Reuse existing playback and transition machinery. Do not introduce another audio controller.

### B. True fullscreen focus mode

The present implementation leaves an explicit bottom “Show reading controls” row. Inspection and completion rows can also remain outside the focus-mode condition. These must stop reserving space.

In focus mode:

- Remove the entire app top bar and bottom dock from layout.
- Hide time remaining, playback controls, inspection controls, completion cards and floating buttons.
- Preserve their state so they return correctly when focus mode ends.
- Hide Android status/navigation bars using `WindowInsetsControllerCompat`, with transient bars revealed by an edge swipe. Restore normal bars when leaving the reader. This follows Android’s supported immersive-mode mechanism. [Android immersive-mode documentation](https://developer.android.com/develop/ui/views/layout/immersive)
- Retain only the native text’s existing 8dp inset and necessary display-cutout protection.
- Do not wrap the native text view in another scrolling container.

Interaction rules:

- A clean tap on ordinary article text toggles controls.
- Links, saved highlights, selections, long presses and drags never toggle focus.
- Provide an accessibility action on the reading surface named **Show reading controls**. It must not require a visible button or reserved layout row.
- Back dismisses an active sheet/selection first; otherwise it exits focus before leaving the article.
- Entering and leaving focus preserves the same semantic passage. End-of-article positioning must remain stable when the available height changes.

Opening a tool temporarily restores the controls necessary for that interaction. Returning to focus restores fullscreen behavior without losing the reading position.

### C. Highlighting: explain once, save immediately, suppress the native menu only in this mode

The bottom **Highlight** action should enter continuous highlighting directly after the initial explanation.

**One-time explanation**

- Add a durable `highlightCoachSeen` preference.
- New installations show **Keep a passage** once, on their first attempt to enter highlighting.
- Mark the explanation seen when presented, including if dismissed.
- Existing installations initialize this flag as seen, preventing another explanation for the user who has already encountered it.
- Do not store this flag only in Compose state.
- Subsequent entries go straight into highlighting; article changes, restarts and recreation do not show the explanation again.

New explanation copy:

> Press and hold a word, then adjust the handles. Your selection is highlighted and saved automatically. Choose a color below; tap Done to leave highlighting.

Primary action: **Start highlighting**.

**Native selection behavior**

- Outside continuous highlighting, preserve Android’s normal Copy, Highlight, Share and available system actions.
- Inside continuous highlighting, preserve native handles, magnifier and edge autoscroll, but suppress the floating text-action menu.
- Restore the conditional menu-clearing approach found in historical commit `66d8527`: clear the menu in both creation and preparation when highlighting is active, while allowing the action mode itself to exist.
- Do not restore that commit’s old viewport padding or unrelated code.
- Do not use repeatedly scheduled `ActionMode.hide()` calls as the primary solution: Android limits how long that method hides the toolbar. [Android ActionMode documentation](https://developer.android.com/reference/android/view/ActionMode#hide(long))

**Saving and feedback**

- Immediately render the selected highlight and submit it through the existing serialized highlight mutation machinery.
- Keep the existing approximately 100ms coalescing for handle movement; it is a write-efficiency measure, not an explicit Save step.
- One continuous selection session updates one highlight. Moving a handle must not create another saved record.
- Flush the final range on release, Done, navigation and backgrounding.
- Preserve Undo for the entire selection change.
- Use quiet confirmation after persistence; do not cover the article repeatedly.
- Surface a real persistence failure with Retry. Do not claim the highlight is saved while its write has failed.

### D. Restrict the floating “up” button to search landings

Introduce explicit reader-entry provenance rather than inferring eligibility from scroll distance.

The button is eligible only when:

1. The article was opened from a library search result at a matched passage.
2. The article opening is not already visible.
3. The reader is not in focus mode, selecting, highlighting, transitioning or showing a sheet.

It must not appear after ordinary scrolling, reopening an article, following a quote to its source, using Contents, or using Find within an already-open article.

Behavior:

- Label it **Go to article beginning**.
- Jump to the beginning of **part zero**, not the beginning of the current part.
- Consume the affordance after using it or reaching the article opening.
- Preserve eligibility temporarily when focus mode hides it; returning to normal mode may show it again if still relevant.
- Preserve the existing return-to-reading-position machinery.

Existing restored routes with a search cursor remain compatible by deriving search provenance from their old `at` field when the new origin field is absent.

### E. Refine Find in article

Replace the large outlined field and numbered text-button list with a coherent reader tool.

**Sheet layout**

- Compact header: **Find in article**, with a clearly labeled Close action.
- Reuse the compact search-field presentation, accepting the current reader colors rather than assuming the app background.
- Field placeholder: **Word or phrase**.
- Normally occupy about 55% of available height. Allow expansion to 90% when the keyboard or enlarged text needs it.
- Keep the header and field stable; make results independently scrollable within the sheet.
- Do not introduce another scroller around article text.

**Results and navigation**

- Show explicit states: empty query, searching, no matches, results and failure.
- Show **3 of 12** for an active result, with Previous and Next controls.
- Each result has a two- or three-line context snippet, the matched phrase emphasized, and part information only for multi-part articles.
- Use full-width result rows with clear selected-state styling, rather than centered text buttons.
- Preserve the existing case-insensitive, literal word/phrase matching across stored article parts.
- Cancel stale queries; a completed older query must not overwrite a newer one.

Selecting a result or submitting Search dismisses the keyboard and sheet, lands at the match, and exposes a compact match-navigation strip with **Return to reading position**. All temporary controls disappear in focus mode without discarding their state.

## 3. Themes, typography and content fidelity

### A. Warmer Paper background

Change the **Paper** surface to **`#FFF1E5`**, the Financial Times paper tone referenced by its own color system. [Financial Times color-system reference](https://github.com/Financial-Times/o-colors/issues/198)

- Separate this reader/app Paper token from the original Flexoki palette constant; do not globally replace `Flexoki.Paper`, which is also used as light text in dark themes.
- Apply the new Paper tone consistently to the Paper reading surface and the app’s corresponding light surface.
- Use coordinated warm secondary surfaces and separators.
- Retain dark ink text and verify contrast for ordinary text, metadata, controls, highlights and focus outlines.
- Leave Soft, Sepia, Ink and Black as distinct choices.

### B. Whole-app E-ink / NXTPAPER theme

Add **E-ink / NXTPAPER** to the app theme choices.

Presentation:

- White main background: `#FFFFFF`.
- Black primary text and actions: `#000000`.
- Dark secondary text: `#333333`.
- White secondary surfaces with visible borders; no reliance on faint tinted fills.
- Selected controls use black/white inversion or a strong outline.
- Links are underlined.
- Important is indicated by a star badge, not color alone.
- Error/success states include text or icons, not color alone.
- Highlight colors remain stored unchanged; render them with monochrome indicators and accessible color names in this theme.
- Avoid gradients, shadows, animated color transitions, shimmer, decorative card rotation and fading text.
- Disable decorative motion, smooth programmatic jumps and added kinetic flings in this theme. Preserve direct touch scrolling and native selection behavior.
- Use static loading indicators with text instead of continuously animated decorative indicators.

Implementation:

- Add an explicit theme mode and a shared display-policy context, including monochrome and reduced-motion flags.
- Ensure nested reader themes, dialogs, sheets, native spans, Review, Speed, search fields and system-bar colors all honor it.
- Preserve the user’s saved reading background and font choices underneath the override.
- While active, Appearance clearly states that the app-wide theme controls the background and links to the theme setting. Do not display Paper as though it were the effective background.
- Leaving this theme restores the previously saved reading appearance; it does not reset preferences.

This is an application-level optimization. NXTPAPER and electrophoretic e-ink are different display technologies; do not claim that TCL testing certifies refresh behavior on every e-ink device. No undocumented OEM refresh APIs or forced full-screen flashes are part of this pass. [TCL NXTPAPER information](https://www.tcl.com/global/en/tcl-nxtpaper-technology)

### C. Add Inter

Bundle **Inter**, including its license, as an additional reading font. Use the official distribution; it is available under the SIL Open Font License. [Inter’s official site](https://rsms.me/inter/)

- Add `ArticleFont.INTER` without renaming existing stored enum values.
- Support normal and bold weights in both Compose and the native reader.
- Include it in Appearance previews, article reading, adaptive highlight cards, Review and Speed.
- Preserve semantic position when changing fonts.
- Keep Atkinson for interface controls and Newsreader as the new-install reading default.
- Do not download fonts during normal app use.

### D. Diagnose merged labels and headings

The current TCL screen visibly includes joined text such as **“workersAll”** and **“shareThe.”** The visible defect is confirmed; its origin is not yet established.

Investigation and fix:

1. Identify the current article’s source URL.
2. Compare its source HTML, stored Markdown and rendered projection at the affected locations.
3. If conversion loses structural boundaries, correct the shared HTML-to-Markdown boundary handling for future captures.
4. Preserve inline words, punctuation and intentionally adjacent formatting; do not insert spaces around every HTML element.
5. If the issue is purely visual styling over intact text, fix rendering without changing the projection or offsets.
6. If existing stored text has already lost the boundary, leave it intact. Do not guess missing structure, rewrite quote anchors or silently replace the owner’s article.

Add focused positive and counterexample tests, then inspect one corrected capture in the QA package. This is not another intake campaign.

## 4. Highlights and Review

### A. Full-width, complete highlight cards

Remove the permanent right-side menu column and the six-line preview limit.

Each card contains:

1. The full quote.
2. An Important badge positioned over the card’s upper border when applicable.
3. A compact footer containing attribution, **Open source** and **More**.

Rules:

- Quote text uses the full card width, with approximately 16dp horizontal content padding.
- Remove **Read full highlight** because the full highlight is already shown.
- Do not truncate either by line count or the existing 800-character summary preview.
- Load full quote text only for visible and nearby cards. Retain lightweight summaries for ordering/filtering.
- Use stable item IDs and bounded full-text caching; do not load every complete quote into a single list.
- Keep long quotes in the feed’s existing vertical scroll. Do not add nested card scrollers.
- Loading or refreshing a card must preserve the visible item and scroll anchor.

**Adaptive typography**

Use the selected reading font and the user’s reading size as the base:

| Quote length, counted as visible characters | Size |
|---|---|
| Up to 160 characters | Base + 6sp |
| 161–450 characters | Base + 3sp |
| More than 450 characters | Base |

Count grapheme clusters, not UTF-16 code units. System text scaling still applies. Do not shrink long quotes below the user’s chosen reading size merely to fit a viewport.

Use comfortable line spacing and a restrained surface. Font size is stable for a quote; it must not animate or change while scrolling.

**Important badge**

- Place a 24dp star badge at the upper trailing edge, partly above the card.
- Reserve space above the quote so neither badge nor text overlaps.
- Remove the side-by-side star layout that narrows the quote.
- In the monochrome theme, use a clear black/white badge.
- Announce **Important highlight** through accessibility.
- Important remains changeable through More and the existing gesture action.

**Footer and actions**

- Keep attribution subordinate to the quote.
- More opens the compact existing action sheet.
- Preserve Copy, Share, color, Important and Remove.
- Long-press still enters multi-selection.
- Single removal remains reversible; multiple removals remain confirmed.
- Source deletion never removes the saved quote or attribution.

### B. Tapping a highlight starts Review without losing the unfinished round

Card taps now mean **Review from this highlight**. Footer controls must not trigger the parent card tap.

Do not call the current `resume(chosen = id)` unchanged: it starts a new cycle and would discard unfinished ordering.

Add a focused-review operation with these semantics:

- If there is no unfinished cycle, start an ordinary cycle with the tapped quote first.
- If the tapped quote is already the current quote, resume normally.
- Otherwise, present it as a persisted focused quote while retaining the unfinished cycle’s current quote, queue, random state, phase and Important-bonus history.
- Merely entering Review does not record a review.
- On Next, record the focused quote once, remove its corresponding pending occurrence if present, then resume the preserved current quote.
- Do not remove a legitimate later Important-bonus appearance simply because the quote was reviewed during the base round.
- Leaving Review before Next preserves the focused quote for Continue.
- Opening its source and returning preserves the same Review passage and the existing once-only review-credit behavior.
- Tapping another quote replaces the unconsumed focused presentation without resetting the underlying round.
- If the selected quote disappears, resume the preserved cycle safely.

Implement the focused quote as optional, backward-readable fields in the existing serialized Review state, with defaults for older data. No Room schema migration is needed. Keep expected-ID guards and transaction boundaries around advancement and credit.

Feed filters do not redefine the Review cycle: tapping a filtered result starts at that quote, then continues the existing all-highlights round.

Returning from Review restores the originating Highlights query, filters, ordering and scroll position.

### C. Floating Review banner

Remove the current top Review entry card and its condensed duplicate.

Place one floating banner **above the bottom navigation**:

- Primary text: **Review highlights**.
- Secondary state: Start a round, Continue, or Review again, with a truthful remaining count when available.
- Minimum height approximately 64dp, with 16–20dp side margins.
- It is an overlay; showing or hiding it must not relayout the quote list.
- Give the list enough bottom padding for its final quote and footer to remain reachable.

Visibility:

- Visible on initial arrival and at the top.
- Hide after approximately 16dp of deliberate downward scrolling.
- Reappear after approximately 16dp of upward scrolling.
- Ignore tiny direction reversals and programmatic position restoration.
- Hide during keyboard entry, multi-selection, modal actions and active Undo feedback.
- Preserve its visibility state across quote/Review visits.
- Disable movement/fades during active touch; use immediate visibility changes in the e-ink theme.
- With no highlights, show the actionable empty state instead of a disabled floating banner.

Its action always applies to all highlights. Search and Important filtering must not silently narrow the persisted review round.

### D. Refine Highlights search

Retain the shared compact search component, with a 48dp minimum interactive height.

- Keep the title, field and card content aligned to the same 20dp outer gutter.
- Use a quiet surface, visible focused boundary and a short **Search highlights** placeholder.
- Preserve clear and Search keyboard actions.
- Avoid empty trailing-icon space and unnecessary explanatory rows.
- Keep Newest/Shuffle ordering distinct from the Important filter.
- Typing must not start Review or change review history.
- Search submission dismisses the keyboard while retaining results.
- Returning from Review restores results without automatically reopening the keyboard.
- Back hides the keyboard before clearing or leaving the search context.

### E. Rename Shelf to Reader

Change visible destination names and related action copy:

- Bottom navigation: **Reader · Highlights · Settings**.
- Destination header: **Reader**.
- **Back to shelf** becomes **Back to Reader**.
- Search location wording uses **Current list** with the actual list name.

Keep internal destination IDs, persisted navigation values and route compatibility where possible. Do not rename storage or perform an unrelated internal refactor just to change visible terminology.

## 5. Implementation contract, checks and handoff

### Focused interface changes

| Interface | Responsibility |
|---|---|
| Reader preferences | Durable one-time help flag, Inter font, whole-app monochrome theme |
| Reader chrome state | Focus, highlighting, overlays, effective insets and system-bar ownership |
| Reader entry origin | Distinguish normal, library-search and quote-source openings |
| Find result model | Exact location plus snippet and match range for styled results |
| Highlight feed loader | Full text for visible/nearby stable IDs with bounded caching |
| Focused Review state | Present a chosen quote without resetting the unfinished cycle |
| Shared display policy | Effective colors, monochrome presentation and reduced motion |

Primary implementation areas are the existing reader/native selection screens, theme/preferences, Highlights/Review and navigation. Reuse current reading-session, completion and highlight-mutation machinery.

### Implementation order

1. Record the new specification and correct stale handover pointers.
2. Implement four actions, fullscreen focus, once-only help, native-menu suppression and search-only up-button behavior.
3. Implement Paper, Inter and the app-wide e-ink display policy.
4. Refine Find using the shared search presentation.
5. Implement complete adaptive quote cards, focused Review entry and the floating banner.
6. Rename visible Shelf copy and fix the diagnosed text-boundary defect.
7. Review integration, run focused checks, build and install the final normal debug candidate.

DeepSeek should commit coherent stages locally and report any failed acceptance criterion precisely. The supervising agent should inspect native-selection behavior and Review-state preservation before accepting the candidate.

### Efficient verification

Use the existing isolated QA package and corpus. **Do not repeat the completed 50+50 intake.**

| Check | Required outcome |
|---|---|
| Four reading actions | All visible and usable; Listen/Pause and Speed preserve passage |
| Focus toggle | System bars and all app chrome disappear; no reserved dock gap; same passage remains |
| Focus exit | Clean tap, accessibility action and Back restore controls; links/drags do not toggle |
| Highlight onboarding | Once on a fresh QA install; never again; existing install skips it |
| Continuous highlighting | No native action menu; handles/magnifier/autoscroll work; one adjusted selection creates one record |
| Ordinary selection | Native Copy/Highlight/Share actions still work outside highlighting mode |
| Up button | Present only after library-search landing; absent for ordinary reading, quote sources and focus |
| Find | Query, clear, no matches, result jump, next/previous and Return across one multi-part article |
| Appearance | Paper, Inter normal/bold and e-ink theme persist without losing the passage |
| Highlight cards | Short, medium and long quotes shown completely; badge/footer do not overlap text |
| Quote → Review | Selected quote first; unfinished round retained; Next credits once; Back restores feed |
| Floating banner | Hides down, returns up, does not shift content or cover final actions |
| Search | Keyboard/Back behavior, Important-empty result and return from Review |
| Text boundaries | Corrected source example plus an inline-formatting counterexample |
| Final install | Normal package upgraded in place; installed hash matches candidate; fresh owner-data comparison passes |

Run the existing fast Android unit tests, lint, debug build and alignment check once for the final candidate. Add focused tests for preference migration, Review-state preservation and content boundaries. Run the existing native menu/handle gate after updating it to assert both normal and continuous modes.

Inspect the changed screens in light, dark and e-ink presentation, plus one font-scale-1.4 pass. Repeat only failures or paths affected by subsequent fixes.

No scale corpus, soak testing, 120-minute session, exhaustive matrix or statistical performance campaign. True e-ink hardware refresh quality remains unverified unless such a device is available.

### Final delivery

- Install the normal debug APK on the TCL in place.
- Take fresh preservation snapshots immediately before and after installation; earlier article/highlight counts are no longer authoritative after the user’s recent activity.
- Record source commit, packaged and installed hashes, actual checks and specific remaining limitations.
- Update the copy deck, current-state document and continuation instructions.
- Leave a clean local branch.
- Do not push, merge or release.
- Hand the installed app back for the seven-day trial without further optional feature expansion.
