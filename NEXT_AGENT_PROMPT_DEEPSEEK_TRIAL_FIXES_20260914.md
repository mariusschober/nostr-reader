# DeepSeek execution brief — improvements from the first reading trial

Prepared 14 September 2026. This is an implementation prompt, not a claim that these changes have been made or tested.

## Mission and starting point

You are DeepSeek, implementing the owner's feedback after an hour of real reading. Deliver all changes below, verify the affected paths efficiently, and install the resulting normal debug APK on the TCL when it is available. Work autonomously within these decisions. Do not start another planning exercise or expand into an unrelated redesign.

- Repository: `/Users/schober/Projects/Nostr Reader`; branch: `codex/reliability-finalize`.
- Inspected baseline: `b0c360a`. Installed product source at the previous handoff: `75b369b`; `eca253a` changed a test and `b0c360a` finalized documentation. Check the actual HEAD before editing; preserve any subsequent work.
- Previous normal APK SHA-256: `d3bdcdae7dc1dae525d5b04805fe31aaf331eb4cb98420df8163dd8278ea0236`, archived as `artifacts/repairs-20260911/reader-debug-75b369b.apk`. This was verified on TCL on 11 September; it has not been reverified for this planning pass.
- Read this brief and `IMPLEMENTATION_HANDOFF_20260911.md`. Consult the relevant current source and `docs/COPY_DECK.md`; do not reread every historical handoff.
- Preserve Room v13, existing preferences, canonical article text, quote anchors, native selectable text, its 8dp bottom inset, bounded article parts, keys, pairings and owner data.
- Preserve these pre-existing untracked files: `evidence/polish-20260911/{21-feed.xml,22-feed.xml,22-reader-tab.xml,23-archive.xml}`.
- Local commits only. No push, merge, release, production-data fixtures, database seeding or Chrome changes.
- Background audio is now explicitly in scope; this supersedes its exclusion from older plans.

Execution estimate: approximately 2–4 hours of implementation/integration, with native selection and service-owned audio the main uncertainties. Target 20 minutes of hands-on acceptance, excluding builds and one failed-path correction. This is an estimate, not permission to omit requested features. If materially exceeded, report the specific cause and eliminate optional work first; never silently claim completion.

Use one implementation worker, with exclusive ownership of builds, Git and the device. Do not create more subagents or copy the entire conversation into another worker. Compile changed code before handoff. After two failed attempts at the same issue, change the diagnostic approach rather than repeating it. Astra reviews the finished change and evidence at a checkpoint.

## Decisions already made

1. Continuous highlighting is optimized for **hold, drag, release**. Release commits and automatically settles the selection; there is no additional confirming tap.
2. Each article gets **a highlight list plus an explicit Review button**. The sequence contains only that article's highlights.
3. Listening continues through navigation, Home, screen lock, app switching and dismissal from Recents. Pause/Stop, audio interruptions and article completion remain meaningful controls.
4. Use a compact **bottom** highlighter dock, not a side rail that narrows the reading column.
5. E-ink is a display treatment independent of Light/Dark/System. Both monochrome variants cover the whole app.
6. Article-review credit was the only unanswered clarification. Adopt this default: opening a list/card/source records nothing; deliberately advancing a Review card records that quote once per article-review round. Keep the global round intact.

## Findings to address

All line references below refer to the inspected baseline and are navigation aids. These are code findings plus the owner's report, not new device reproduction claims.

| Priority | Finding and evidence | Required outcome |
|---|---|---|
| P1 | `ui/theme/Tokens.kt:88,122–142` forces white `EinkColors` and a light Material scheme; `prefs/Prefs.kt:16` combines E-ink with theme choice. | Dark monochrome throughout the app, with a separate display setting. |
| P1 | `ui/theme/EinkHighlight.kt:21` ignores `dark` in monochrome; `ui/screens/NativeArticleView.kt:691` hard-codes black underline paint. | Dark highlights, selection, edges and Speed emphasis stay legible. Merely changing the background would fail. |
| P1 | `ui/screens/PreparedReaderScreen.kt:429,459` renders both bars whenever `pen` is true, even in focus; `NativeArticleView.kt:409` only dispatches a background tap outside pen mode. | Focus and highlighting coexist, with the small dock as the only persistent pen control. |
| P1 | Native selection writes are debounced and retained (`NativeArticleView.kt:637–674`); no final-gesture settlement contract exists. | One gesture, one saved highlight, no lingering selection, no duplicate or lost range. |
| P1 | `ui/MainActivity.kt:1020–1038` pauses/shuts down Activity-owned TTS; `tts/TtsPlaybackService.kt:9–22` only creates a session. | Service owns real playback and survives leaving the Activity. |
| P1 | `tts/TtsMediaPlayer.kt` is an incomplete adapter: empty timeline/metadata, always-ready state and an attachment that is unused by live playback. | Truthful media state and working notification/lock-screen controls. |
| P2 | Owner reports light outer borders in dark mode. Activity system-bar writes (`MainActivity.kt:234–242`) compete with focus restore effects (`PreparedReaderScreen.kt:301–345`). | One current source of window appearance, without restoring stale light colors. Exact device symptom still requires focused verification. |
| P2 | `ReaderScreen.kt:216–280` disables partial expansion, caps content at 500dp and hides options behind an expandable group. | A partially open, draggable sheet containing all controls in one scrollable hierarchy. |
| P2 | `Route.kt` has global Review only; `ReviewRepository` uses one global round and queries every highlight. | Article-scoped list and review without replacing the global round. |
| P2 | `data/Labels.kt:37–47,72–92` has no display color; library rows receive no label presentation. | Persistent palette choices and compact, readable hashtag badges. |

## A. Appearance, monochrome and Android window edges

### Settings and compatibility

- Keep **App theme: System / Light / Dark**. Add **Display: Standard / E-ink & NXTPAPER** immediately below it, and expose these same choices in Reading appearance. They edit the same app-wide preferences.
- Add an independent display-mode preference. Decode legacy `ThemeMode.EINK` as monochrome + System, and materialize that migration once. This deliberately enables following the phone's dark setting for existing E-ink users. Preserve all typography, margin, spacing and explicit standard-mode article-background preferences.
- Existing SYSTEM/LIGHT/DARK installs remain Standard unless they opt in. Missing or unknown display values resolve safely to Standard; no preference reset.
- Resolve app and reader appearance centrally. In Standard, explicit article backgrounds still work. In monochrome, the app's Light/Dark/System choice controls both article and chrome; retain, but temporarily do not apply, the stored standard article background.
- Extend the effective display policy with resolved darkness. Nested reader themes must preserve both monochrome and its darkness; do not infer monochrome darkness from the stored Paper/Ink article preference.

### Visual contract

- Monochrome light: white background/surface, black text and edges, dark gray secondary text. Monochrome dark: black background/surface, white text/edges, `#CCCCCC` secondary text. Use thin outlines and inverse selected states; no elevation-based differentiation or hue-only meaning.
- Cover Reader, Highlights, article highlights, both Review routes, Settings, label editing, menus, sheets, Find, selection actions, notices, Undo, controls and empty/loading/error states. Replace hard-coded light monochrome fills with semantic tokens. Do not invert screenshots or article content wholesale.
- Dark highlight fills: Yellow `#202020`, Green `#323232`, Cyan `#484848`, Purple `#606060`; white quote text and white patterned edges. Retain Y/G/C/P names and solid/double/dashed/dotted identity. Keep the existing light variants and stored highlight colors unchanged.
- Give native underline rendering an explicit edge color from the resolved presentation; retain the repaired Layout/bidi geometry and double-rule spacing. Invalidate color-dependent paint/cache state on theme changes.
- Active selection must remain distinguishable from a saved highlight in both variants. Native handles/magnifier and quote action sheets must stay legible. Preserve the normal native Copy/Share/system-action route.
- Speed uses normal-theme blue as before; both monochrome variants use a bold focal glyph and a contrasting underline. Preserve the corrected baseline, grapheme handling and fitting.
- Keep reduced-motion behavior in both monochrome variants. Do not add animated fades, breathing controls or kinetic continuation. Direct finger scrolling remains responsive.

### Window ownership

- Introduce one Activity-owned window-appearance coordinator driven by resolved route background, icon contrast, focus and any active modal window. Reader focus reports its state; it does not independently restore captured bar colors.
- Paint the decor/root and system-bar regions from the current surface. Use matching status/navigation icon contrast, handle navigation contrast scrims and cutout regions, and recompute after theme changes, route changes and modal dismissal.
- Focus uses transparent hidden bars and paints to available edges; exiting focus recomputes the current appearance. Preserve actual camera/touch-safe insets without reintroducing a black or white strip.
- Theme app-owned dialog/bottom-sheet windows as well. Android's notification shade and other external apps remain OS-controlled; do not promise to recolor them.
- Review already applies status-bar insets in more than one place (`ReviewScreen.kt:76–78`): while making this shared correction, remove duplicate applied insets if the layout confirms double spacing. Do not use arbitrary negative padding.

### Appearance sheet

- Replace the expand/collapse group with one continuous list, initially partially expanded. Keep the drag handle and pinned Appearance/Done header. Enable pull-up expansion and normal nested scrolling; remove the fixed 500dp ceiling.
- Order: text size, spacing, app theme, display treatment, article background when Standard, font, margins, bold. Lower controls exist immediately and are reached by pulling or scrolling—there is no More options button.
- At normal text size the initial sheet should leave a useful passage visible; the expanded sheet fits the available height with all options reachable. At enlarged text, controls wrap and the list scrolls rather than clipping.
- Apply every change immediately and preserve the semantic passage. Opening, resizing and closing the sheet must not reset position or pen/focus state. Include accessible expand/collapse actions; dragging cannot be the only way to reach controls.

## B. Focus-compatible highlighting and automatic settlement

### Controls

- Treat focus and continuous highlighting as independent state. Focus hides the normal top bar, four-action row, reading-time footer and their allocated space, whether pen mode is on or off.
- While pen mode is active, show one bottom-centered capsule with **current color**, **enter/exit focus**, and **Done highlighting**. Use 48dp touch targets, approximately 56dp total dock height, and no persistent rows of color names. Keep the dock just above the safe bottom edge; in focus do not reserve the old control stack's height.
- The color control opens a small transient palette above the dock with all four named colors, selected state and monochrome pattern cues. Close it after choosing; remember the selected color using the existing state contract. Done exits pen mode while retaining focus.
- Lay out the dock so its small reserved area cannot obscure the last line. Preserve the native TextView's 8dp inset separately; do not add a second article scroll container.
- A stationary unconsumed tap on article background toggles focus even in pen mode. Links, saved highlights, tables, selections, drags and dock actions must not accidentally toggle it. The explicit dock focus control is always available.
- Back dismisses the palette/sheet first, then cancels any active selection gesture, then exits focus, then exits pen mode, then follows existing article navigation. A completed selection must not require an extra Back press.

### Gesture and persistence contract

- In pen mode, a short drag remains scrolling. After long-press recognition, the current pointer owns a selection gesture: hold, extend over text, release. Retain native text geometry, selection feedback, magnifier and edge autoscroll; do not replace the article with custom text rendering.
- Preview the current range during the gesture. Commit its final half-open range on release and settle automatically. A long-press released without extending saves the selected word. Cancellation saves nothing from the cancelled gesture. Do not clear on the first `onSelectionChanged` event or an arbitrary idle timer while the finger is still adjusting.
- Reuse the selection-session ID and existing mutation/Undo repository machinery. One gesture produces one logical mutation. Do not deduplicate by deleting overlaps; differently colored or separately intentional overlapping highlights remain independent.
- Add an explicit final-commit/result bridge from native selection through `ReadingSession` to the UI. Clear the native selection only for the acknowledged session after success; stale callbacks must never clear or overwrite a newer gesture. Rotate ownership once after settlement.
- If persistence fails, retain the recoverable draft and show a quiet actionable Retry/Cancel state. Never silently clear an unsaved range. Disable conflicting pen commands while recovery is unresolved.
- Undo reverses the final gesture, including restoring an existing highlight if that is what the mutation changed. Do not cover the article with a snackbar for every selection update; retain a compact Undo opportunity without losing another active Undo.
- Outside pen mode, keep native long-press selection and adjustable handles, with Highlight/Copy/Share and available Android actions. Update pen-specific tests to the new release behavior; do not weaken ordinary-mode adjustment or same-ID regression coverage.
- Keep the coach once-only. Update its copy to explain hold/drag/release, but do not reset the existing seen flag to show it again.

## C. Highlights and Review for one article

- Add **Article highlights · N** to reading tools and **View article highlights** to article row menus and global quote source actions. These open a dedicated article-scoped destination. Keep the four normal reading actions unchanged.
- The screen shows article title/source once, a truthful count, complete quote cards with adaptive size and Important badges, and a prominent **Review this article** CTA when highlights exist. Empty state: “No highlights in this article yet” with Back to article when available.
- Order by source passage: section order from the article index, block order, start offset, then creation time/ID for ties. Do not use creation time or lexical block-ID order as passage order. With missing/unresolvable source, retain all quotes and use canonical position where valid, with deterministic creation-time/ID fallback.
- Tapping a quote starts article Review at that quote. Review this article starts at the first quote. Present the chosen quote, continue to the end, then wrap to earlier members so every member appears once; no shuffle or Important bonus phase in this scoped round.
- Use Previous/Next, “N of M”, Important, Share and Open passage. At the last unreviewed card, the advance action is **Finish review**; completion returns a quiet completed state with Back to article highlights and Review again. Opening or backing out does not mark a card reviewed.
- Advancing credits the presented quote once per scoped round, including after Previous, source visits or repeated rapid taps. A deliberate Review again creates a new round and may earn new credit. Opening source in this scoped route does not credit; leave the existing global Review semantics unchanged in this pass.
- Keep the global round/cursor/remaining count intact. Never implement article Review by replacing the global candidate list and persisting it as the global round.
- Add serialized `ArticleHighlights(documentId)` and `ArticleReview(documentId, startHighlightId?)` routes, retaining existing route decoding. Reuse the existing quote presentation and mutation actions; avoid a second divergent highlight design.
- Persist a separately scoped article round alongside the global round using a versioned JSON envelope in the existing chunked `review_state` storage. Decode legacy bare global `ReviewState` losslessly. Store one resumable article round at a time; all global and article writes preserve the other scope inside the same Room transaction.
- Article advance atomically records quote credit and advances scoped state with an expected-current-ID guard. Global summary readers must select the global state from the envelope. Centralize decoding so legacy compatibility is not reimplemented differently by each caller.
- Snapshot membership at round start; skip deleted members, do not append newly saved highlights mid-round, and handle zero remaining members cleanly. Source deletion never removes quotes or attribution.
- Preserve the article-highlight list scroll anchor and review quote across Open passage → Back. Returning to the originating reader restores its prior reading passage. Reuse remove/Undo and metadata restoration across the new list.

## D. Label colors and library badges

- Use predefined **Neutral, Red, Orange, Green, Blue** choices from the existing Flexoki palette; default existing and new labels to Neutral. No free-form color picker. Use the theme-appropriate existing 600/400 variants, adjusting presentation only where necessary for readable text.
- Store stable palette keys by stable label ID in a dedicated appearance-preferences map. Keep Room v13 unchanged. Expose a reactive label-presentation stream joining Room names/assignments with those preferences; do not issue per-row database queries.
- Add color selection to label creation/editing in Manage labels. Label assignment sheets show the same badge identity and provide a clear Manage labels entry. Rename the editor action from Save name to Save label.
- A rename retains color. Explicit merge retains the destination label's color; say so in the merge confirmation. Delete/merge removes obsolete appearance entries after the Room operation succeeds. Stale entries are harmless and can be pruned from a verified current label set; never prune from an initial/loading empty list.
- Keep preference writes independent from whole-reader-settings saves, preventing a concurrent font/theme change from overwriting label colors. On partial save failure, distinguish “label saved, color couldn't be saved” and retry the color; do not create another label.
- Render compact `#label` badges beneath article metadata on every library/search/archive row. Show at most two names plus `+N`, in case-insensitive name order with stable-ID ties. Long names ellipsize; accessibility exposes full names and remaining labels. The existing row menu gives access to the full label list.
- Badges are descriptive, not extra tiny tap targets: row tap opens the article, long-press selects, swipe remains intact. Existing filters and `#label` autocomplete retain their current behavior.
- In monochrome, show readable outlined hashtag badges with names; selected filters use inverse fill. Color is retained as metadata but is not the sole indication of identity.

## E. Real background listening

- Make `TtsPlaybackService` own `AndroidTtsEngine`, `TtsController`, the Media3 player/session and playback coroutine scope. Remove speech lifetime ownership from the Activity. Its stop/destroy paths only detach UI observation and flush appropriate state.
- Connect UI commands/state through a MediaController-backed bridge. Send document ID, semantic cursor and speed, not full article text or narration arrays through Binder. The service loads the required bounded article section from the existing repository.
- Expose a compact observable playback state: document/source metadata, playing/paused/loading/ended/error, speed and semantic spoken position. Replace the single `onState` ownership conflict with one publisher and independent UI/player observers. Do not send all narration units on each range callback.
- Finish the player adapter's real MediaItem/timeline, metadata, available commands and state/event reporting. Wire play/pause, previous/next sentence, speed and Stop to the controller. Do not advertise commands that do nothing or report READY when empty/ended/error.
- Use the existing MediaSessionService media notification lifecycle and declared media-playback foreground-service permissions/type. Start playback from an explicit user action. Keep the currently declared service exposure no broader than necessary; validate app-specific commands and rely on trusted session controllers for system actions.
- Follow the Android background-playback model: [MediaSessionService documentation](https://developer.android.com/media/media3/session/background-playback). The service survives Activity backgrounding and, while playing, Recents dismissal. Do not confuse this with Android force-stop or guarantee survival after the OS kills the process.
- Continue through bounded section boundaries until the final article section; this also prevents the current section-only controller load from silently ending a long article early. Section load failures pause with a retryable error; never restart from the article beginning without a user command.
- On final completion, stop speech and expose Ended without replaying the final sentence, auto-archiving or claiming deliberate Finish credit. Release foreground resources when stopped/ended; paused sessions retain a usable resume position subject to Android service lifetime rules.
- Continue when the reader navigates back to the library, changes theme, enters focus or highlights text. Starting Listen on another article replaces the current narration; starting Speed pauses speech as a deliberate mode change. Archive/relabel does not stop listening. Deleting the currently narrated source stops it cleanly.
- Add a compact now-playing row above bottom navigation when audio is active/paused away from its article: source title, Play/Pause and Stop; tapping the title opens the spoken passage. In the article, reuse the player controls; in focus, keep full player chrome hidden and use system media controls/the reading tools action. Do not duplicate player rows.
- Notification and lock-screen controls show the article title/source, Play/Pause, previous/next sentence where supported, and Stop. Returning from the notification reconnects to the same session without restarting speech.
- Preserve audio-focus and headphone-disconnect handling. Interruption pauses with a resumable position; do not automatically resume after a call or explicit user pause. Report engine/voice errors honestly, including whether a chosen voice requires network.
- Persist the spoken cursor under the narrated document ID and flush on Pause/Stop/section changes. A late callback from an old document/session must not update another article or move an unrelated visible reader. Service state survives Activity recreation; after process death, restore position without auto-starting unsolicited speech.
- Remove/update the old device test that expects Home to stop audio. Keep existing controller generation guards for stale utterance callbacks.

## F. Ordered implementation and focused acceptance

### Implementation order

1. Preference/display resolution, monochrome colors and unified window appearance.
2. Appearance sheet and focus-compatible pen dock.
3. Release-to-save selection lifecycle and commit acknowledgment.
4. Article highlight list, scoped Review persistence/credit and navigation.
5. Label appearance preferences, editor and library badges.
6. Service-owned background audio and UI bridge.
7. One integrated build, focused device acceptance, final installation and handoff.

Make reviewable local commits by package. The native gesture and TTS packages need a relevant compile/test before they are called complete. Avoid concurrent edits/builds of MainActivity. Do not upgrade the Android SDK, Compose or Media3 merely to follow newer documentation; use the pinned dependencies unless a concrete incompatibility requires a narrow change.

### Minimum host checks

- Focused tests during development: legacy theme migration; light/dark monochrome presentation; final selection session/ack/cancel; article-round scope, credit and legacy JSON decoding; label identity/merge/color persistence; TTS lifecycle/section advance/stale callbacks and player state.
- Test real behavior, not copies of implementation. Include one ordinary native handle-adjustment regression alongside the new pen gesture test.
- At freeze, run the existing fast Android unit suite, lint, normal/QA debug builds and the repository's existing native-library alignment check once. Do not repeat passing checks without a relevant change. No Chrome suite or new intake campaign for this Android-only work.

### One small device walkthrough

Use the connected TCL QA package `com.reader.app.qa`. Enumerate devices and use the exact returned serial; the historical TCL serial is `ZXKRS4VKGQ8PWGEQ` (including the final Q). If the TCL is unavailable, record that and complete independent work; do not claim a device pass or installation. Another explicitly available device may support preliminary checks, but does not prove NXTPAPER appearance.

Use three existing QA articles: a normal article, a wrapped/structurally complex multi-part article, and another article for scope isolation. Add at most one short article through the user-facing save path if needed. Keep at most a dozen test highlights and three labels. Do not touch the owner library for fixtures.

| Check once | Required evidence |
|---|---|
| E-ink light → dark → System; enter/exit focus, open/close Appearance and one modal; visit Reader/Highlights/Settings/Review | Readable controls/selection/Undo; matching app-owned edges; no stale light strips. Capture one light and one dark focused view and a dark highlight/Review view. Include the final Green double-rule and Speed focal checks here, not as another campaign. |
| Pen on → focus → hold/drag across wrapped lines → release → another highlight → Undo; short scroll and cancelled gesture; ordinary selection with handles once | Automatic settlement, no native menu in pen mode, exactly one persisted row/final range per completed gesture, no save on cancellation and no duplicate/late clearing. Use one targeted native integration test for persistence. |
| Appearance partially open → pull up → change font/size/spacing/bold → close | All controls reachable without an expansion button; article passage preserved. One enlarged-system-text spot check, then restore the QA setting. |
| Article highlights → Review from middle → source → Back → Previous/Next → finish; glance at global Review | Only that article is included, all members reachable, credit occurs once on advance, global round remains unchanged, list/reader positions return correctly. Verify single removal/Undo and missing-source treatment using existing QA-only content. |
| Create/color three labels → assign → rename one → merge another → return to Reader/search | Persisted colors, destination merge color, correct names/counts/badges and unchanged AND filtering. No per-row loading stalls. |
| Listen → Home → lock → notification Pause/Play → Recents dismissal → reopen → section boundary → Stop | Audible continuation and one real media session, correct state/position/title on return, no section-end truncation, working Stop. About 2–3 minutes of listening is enough. One audio-focus interruption via a controlled test; do not place calls or operate the owner's other apps. |

Repeat only a failed check or a check affected by a later fix. No 100/1,000-article exercise, 10,000 highlights, long reading simulation, soak test, exhaustive accessibility/device matrix or statistical performance campaign. Physical e-ink ghosting, battery and sustained comfort remain owner-trial observations. Screenshots do not prove physical display behavior or audible playback.

### Installation and handoff

- Freeze the final source before device acceptance. Archive the exact normal and QA APKs, source SHA, hashes and focused evidence under a new dated directory. Keep APK binaries out of Git.
- Before normal installation, use the existing owner-integrity procedure for logical database/preferences summaries and an on-device encrypted-key-file digest. Never export key contents. Preserve the signing certificate and install normal `com.reader.app` in place—never uninstall or clear it.
- Pull/hash the installed APK and compare exact bytes to the archived build. Compare owner data with allowance only for the specified one-time settings/review-envelope migration; article text, highlights, label assignments, completion history, keys and pairings must remain intact. Do not demand byte-identical preference storage after an intentional migration.
- Launch normal Reader once when the device is available for that purpose; leave it usable and tell the owner that installation is complete. Leave QA fixtures isolated.
- Update `START_HERE.md`, `CONTINUE.md`, the copy deck and one dated handoff with source commit, installed hash, implemented behavior, focused PASS/FAIL/NOT MEASURED/BLOCKED evidence and any exact remaining issue. Keep historical artifacts and clarify superseded instructions.
- Stop after delivery. Optional ideas discovered during work belong in the handoff, not an additional implementation/test cycle. A known failing required behavior is not “done”; either repair it within scope or explicitly report it incomplete.

## Required final response

Report what changed, the focused checks actually performed, TCL installation status/hash, local commit(s), and the handoff path. Distinguish implemented source from visually or audibly verified behavior. Be concise; no exhaustive tool transcript, unsupported quality superlatives, push or release claim.
