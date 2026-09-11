# Reader: final correction and design plan before the seven-day trial

**Status: ready for implementation; not yet implemented.** This plan follows the fresh [Astra audit](ASTRA_AUDIT_20260911.md) of `f20828b`. It supersedes conflicting completion claims and presentation details in `CONTINUE.md`, `NEXT_AGENT_PLAN.md` and `FINAL_USABILITY_SPEC_20260911.md`. Preserve their implemented foundations and historical evidence.

The next candidate should make four everyday actions dependable: settle into a passage, save it once, find it again, and revisit it through Review. The quiet appearance only succeeds if those actions remain obvious and trustworthy. Deliver the corrections below, with brief checks, then let the user conduct the seven-day trial.

## 1. Starting point and ownership

| Item | Verified starting state |
|---|---|
| Checkout | `/Users/schober/Projects/Nostr Reader` |
| Branch | `codex/reliability-finalize`; keep work local |
| Product source under audit | `f20828b3e2b4c255419f5436523d5b5de7d49104` |
| Tip when this audit began | `f53bb8c`; subsequent changes above product source were tests/docs only |
| Normal package | `com.reader.app`, debug0.9.0-beta.1/code2 |
| Installed normal SHA-256 | `8928ca661fad20f49cd14e3fe8a6c7640826534521bd5674ee08fdbe1347c107` |
| QA package/hash | `com.reader.app.qa` / `2ef7bec9b355b6343d53569dd7ae71eb053256751143c54ca9276dba95b1bcbc` |
| Phone | TCL T807D, API36, serial `ZXKRS4VKGQ8PWGEQ`,1080×2340,density456,fontScale1.0 |
| Current QA data | Optional sample article, two highlights, one Important; unfinished Review round. The earlier 100-item corpus was removed by a prior test runner. |
| Historical intake | 50 real Chrome +50 Android links completed previously; do not repeat |

DeepSeek should own the entire implementation and affected checks. The agreed existing task is **“DeepSeek Finalization of Nostr Reader V0.9”**, ID `01a08d24-7a4b-7f51-b7ef-5c5d4b892168`. That is the existing-task alternative to a child agent, not a model to substitute silently in an OpenAI-only child selector. A supervisor reviews its diff and final evidence. Only one agent edits this checkout or operates the TCL at a time.

This audit did not dispatch implementation or modify the normal installation. At implementation start, verify the current tip and worktree, read this plan plus the audit, and preserve newer user/agent work. Do not reset to the old source commit and lose newer tests/docs.

## 2. Non-negotiable boundaries

- Preserve Roomv13, canonical Markdown, projectionv2, semantic cursors, quote IDs/anchors, article/highlight metadata, Review history, signing identity, encryption, keys and pairing.
- Keep the native selectable TextView, its own scrolling/handles/magnifier/autoscroll and existing8dp text inset. No surrounding ScrollView, replacement renderer, pagination mode or unrelated architecture rewrite.
- Keep **Reader · Highlights · Settings**, **Inbox · Priority · Later · Archive**, four direct reading actions, Inter, warmer Paper, optional continuous highlighting, explicit finish/archive and guarded Undo.
- Normal Android selection retains Copy, Highlight, Share and available system actions. Only continuous highlighting suppresses its menu.
- Do not rewrite stored content or globally deduplicate existing overlaps. They may be deliberate. Capture fixes affect future captures only.
- Use QA for test data and destructive checks. Never clear normal app data, import QA articles into it, replace keys/signing, or modify the normal Chrome profile.
- No accounts, cloud services, telemetry, offline image store, highlight notes, new Review scheduling model or feature expansion in this pass.
- No 100-item re-intake, synthetic scale corpus, long reading simulation, soak, broad lifecycle/accessibility matrix or statistical benchmarking. Do not repeat passing checks without a relevant change.

## 3. Locked design decisions

| Surface | Required decision |
|---|---|
| Focus | Edge-to-edge reading background; no black letterbox or hidden-control spacer; protect only real occlusion/cutout needs |
| Review entry | High-contrast floating **Review highlights** button plus quiet persistent **Review** header action |
| Review scope | Global saved-highlights round; feed search/Important filters do not reset or silently narrow it |
| Monochrome highlights | Keep stored colors; show their visible names/identifiers and distinguishable monochrome mark styles |
| Speed | Black word, genuinely bold focal grapheme with restrained underline/anchor cue; blue remains in normal themes |
| Search | Retain the shared compact field; white/bordered in monochrome, subtle warm/dark surface elsewhere |
| Quotes | Entire quote, selected reading font, adaptive readable size, attached attribution and compact footer; tap starts Review there |
| Motion | Direct touch remains direct; monochrome removes decorative motion and added kinetic continuation |
| Feedback | Committed actions get quiet feedback; Undo remains visible, reachable and protected from competing overlays |

Do not respond to this plan with another open-ended redesign. Make the specified decisions concrete and improve only defects observed while checking them.

## 4. Work package A — stop duplicate highlighting and preserve trust

**Priority:** first. Audit F01/F11. Files: `NativeArticleView.kt`, `PreparedReaderScreen.kt`, `data/HighlightRepository.kt`, reading-session machinery, `Prefs.kt`, the small related tests.

### A1. Own one selection identity

1. Reproduce the captured path in QA: enter continuous highlighting; long-press a word; wait for its saved mark/Undo; drag the end handle across a paragraph; tap Done. The current failure retained both original word and expanded quote.
2. Trace only selection creation/destruction, session ID and mutation ID in a debug-only diagnostic or focused test. Avoid logging quote text or owner content. Inspect emissions that precede `onCreateActionMode` and callback recreation during the same visible selection.
3. Establish one selection-session owner. Allocate an ID on the first real selection, reuse it in ActionMode creation if it already belongs to that active selection, and retain it through native handle adjustments. Clear/rotate only after selection is truly cleared or a new deliberate selection starts. Do not fix this with range-wide deletion or a time-based guess.
4. Keep ordered sequence numbers and approximately100ms coalescing. Initial highlight appears immediately; later handles replace its pending/saved range. Flush on release/Done/navigation/background using the existing machinery.
5. Keep normal-mode Highlight explicit. Merely selecting/copying text outside pen mode must not save. Re-entering pen mode must not create a duplicate from a stale selection.
6. Undo for a newly created/adjusted selection removes the one final highlight. Undo for editing a preexisting highlight restores its original range and all metadata. Preserve revision guards against later deliberate changes.
7. Native selection visibility remains separate from saved mark rendering: do not destroy ActionMode to hide its toolbar. Preserve handles, magnifier and edge autoscroll.

**Focused gate:** one integrated QA test with real native selection and repository persistence: word save → one handle drag → same ID/count → final quote equals selected projection range → Done → reopen → one mark → Undo. Add a small ordinary-selection check if callback logic changed. Assert database rows/IDs, not just fake Menu contents or callbacks. Use `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` so instrumentation does not discard QA data again.

### A2. Initialize one-time help once

The decoder currently mistakes any newly written setting or sample-welcome flag for a legacy install. At first initialization, atomically establish the absent coach flag from preexisting preference state: fresh=false; legacy=true. Persist that false value before later new-install writes can change the classification. Once present, the stored flag wins. Showing the explanation marks it seen; dismissing it still counts as shown. Established owners never see it again.

**Focused checks:** fresh initialization → optional sample/settings change → first pen entry shows help; next entry does not. Legacy initialization skips it. Test the transition, not just a manually constructed empty Preferences object.

## 5. Work package B — make monochrome controls legible

**Priority:** alongside A before polish. Audit F02/F04/F05/F09/F10. Files: `ui/theme/Tokens.kt`, `Motion.kt`, `HighlightPalette.kt`, `ReaderBottomNavigation.kt`, `NoticeCoordinator.kt`, `HighlightActionsSheet.kt`, `PreparedReaderScreen.kt`, `NativeArticleView.kt`, `HighlightsFeed.kt`, `ReviewScreen.kt`, `RsvpScreen.kt`.

### B1. Finish the contrast roles

Use white page/sheet surfaces, black primary content and #333 secondary text. Treat selected and inverse containers as complete foreground/background pairs.

- Selected bottom-navigation capsule: black fill, **white icon**, black label on the surrounding page. Unselected icons remain dark and visible.
- Snackbar: black fill, white message, white Undo, white dismiss. Give Undo a recognizable action treatment without relying on hue. Its hit target remains at least48dp.
- Chips/switches/radios: selection has inversion/check/strong outline. Never rely on a gray fill alone, and never render black selected content on black.
- Fields/sheets/dialogs: white with explicit dark boundaries where a boundary carries meaning. Avoid elevation shadows as the only separation; no decorative shadows in monochrome.
- Check disabled controls remain distinguishable without making essential labels disappear. Keep functional error labels/icons, not red-only signaling.
- Use the effective display policy in nested themes and native views. Leaving monochrome restores saved background/font/weight settings exactly.

Prefer shared semantic component roles over a succession of scattered `if (mono)` patches. Do not automatically change normal Flexoki colors while repairing monochrome.

### B2. Give all four highlight choices a visible identity

Preserve YELLOW/GREEN/CYAN/PURPLE in storage and exports. Present them consistently as **Yellow · Green · Cyan · Purple**, with **Y/G/C/P** as compact visible identifiers. Do not invent new meanings such as “Fact” or “Question” for the user's existing colors.

At normal size, the pen dock has four equal controls plus Done. Each control has a visible identifier and small color name; selected has a black fill/white identifier and an unambiguous check/outline. At enlarged text, use two rows rather than shrink labels. The actions sheet uses the same component and visible names.

Monochrome saved-mark mapping:

| Stored color | Neutral fill starting token | Non-color edge style |
|---|---|---|
| Yellow / Y | #E0E0E0 | Solid underline |
| Green / G | #C8C8C8 | Double underline |
| Cyan / C | #B0B0B0 | Dashed underline |
| Purple / P | #989898 | Dotted underline |

All use black text. These are initial neutral tokens, not a claim that four gray tones alone work on every physical panel. The pattern and visible identifier carry identity if shades compress. Use comfortably visible strokes at TCL density, approximately1.5dp; reserve existing line-spacing space and keep strokes below glyph descenders.

Implementation requirements:

1. Resolve a `HighlightPresentation` from saved color, theme and state. Reuse it for picker, native article mark, quote card and Review marker. Name may differ, responsibility must stay the same.
2. Native saved marks receive that presentation rather than `.background(dark)` directly. Include effective policy in recomposition/cache keys so toggling theme restyles existing marks immediately.
3. Render non-color edge styles through drawing-only spans/decoration based on the native Layout's real positions. Do not insert text, substitute a TextView, alter anchor offsets, synthesize glyph positions independently, or use a ReplacementSpan that changes selection geometry. Handle wrapped lines and RTL through native layout geometry. Most-recent overlap rendering may stay as today; all overlapping IDs remain reachable through the action sheet.
4. Feed/Review use the same edge pattern and a small visible color-name/identifier in the metadata region. A color stripe alone is insufficient.
5. Active native selection must be visually different from a saved highlight. Use a stronger monochrome selection fill/handle tint where supported by the existing Android API level; retain OEM native shape/behavior. Do not build custom selection handles just to style them. If a platform handle tint cannot be controlled safely, report that exact limit; still fix saved-mark visibility.
6. Keep Important as a separate star badge, with no text under it. Color identity must not displace attribution or create a permanent side gutter.

**Focused visual check:** use at most four small QA selections to see the four styles, one wrapped range and one overlap. Toggle monochrome → normal and confirm saved colors/anchors unchanged. Check reading at ordinary text size; don't make highlight decoration overpower the prose.

### B3. Restore the focal point in Speed

- Keep surrounding letters at the normal selected-font weight. Focal grapheme uses a real bundled bold weight, black in monochrome, plus a short underline/caret cue that does not rely on color. Existing blue focal style remains in normal themes. Newsreader/Crimson Pro have variable weight mappings and Inter/Asul/Atkinson have bold faces. ABeeZee has no bundled bold face: preserve that font, use the persistent underline cue with supported weight emphasis, and do not silently substitute a different font or claim a real ABeeZee bold face exists.
- Preserve the custom optical-center layout. Measure fragments with their actual final weights and align their baselines; the emphasized character must not hop horizontally between words.
- Keep the whole grapheme together. Do not split a combining character/emoji sequence to emphasize a UTF-16 code unit.
- Fit one long token to the available width as one coordinated layout. Do not let left/right fragments clip at screen edges or wrap independently; use a bounded font reduction for an unusually long word only, without reducing ordinary word size.
- Preserve Play/Pause, ±10, WPM setting, lifecycle pause and semantic return location. In monochrome, update only required word/control state, with no animated count, fades or progress decoration.

**Check:** one short word, one long word and one accented word; brief Play → Pause → Exit. No timed speed-reading session or cadence benchmark.

### B4. Complete the display motion policy

Use the effective policy, not `rememberReduceMotion()` alone. In monochrome: instant programmatic jumps; no added `OverScroller` kinetic continuation after release; no fade/rotation/shadow on Review or feed cards. Direct finger movement and native selection edge autoscroll remain functional. Keep ordinary-theme scrolling behavior. Stop an existing programmatic fling when switching policy.

Check one scroll/release, one Find or beginning jump, and one Review Next. Avoid a broad animation audit.

## 6. Work package C — fix fullscreen at the window boundary

**Audit:** F03. Files: `MainActivity.kt`, `PreparedReaderScreen.kt`, `themes.xml`; a small scoped window-policy helper is appropriate if it clarifies ownership.

1. Reproduce the current black130px top band. Record actual `WindowInsets` visibility, app frame and camera bounds. The app targetsSDK34, so do not assume newer target-SDK edge-to-edge behavior applies.
2. Choose one owner for normal edge-to-edge/insets and one scoped reader-focus state. Explicitly enable drawing into the cutout region with supported SDK-aware window APIs; retain transient system-bar reveal by edge swipe. Do not alter global TCL display settings or use OEM hidden APIs.
3. Paint the article background to the whole window, including around the camera. Transparent system bars must reveal the correct reader background. Keep status icon appearance correct when transient bars are visible.
4. Remove app chrome and its measured space in focus. Set reader content insets intentionally: no hidden status/navigation inset, no bottom menu placeholder, no inspection/footer/finish-card/Up button or part-navigation row. Preserve those states for when controls return.
5. Protect actual physical occlusion. The TCL reports a central camera rectangle x505–575,y0–130. Do not put readable glyphs under it just to gain a larger screenshot. If native full-width text requires a top safe inset, the remaining area must be matching paper/white with only necessary protection, never a black letterbox. Distinguish full-window background recovery from usable text recovery in the evidence. No complex text-flow rewrite around a notch in this pass.
6. Preserve the semantic passage on entry, exit and viewport resize, including at article end. Retain native8dp text inset; no pixel-offset hacks or negative padding.
7. Back dismisses sheet/selection first, then exits focus, then leaves the article. Clean text tap toggles; links/highlights/drag/selection never do. The accessibility action Show reading controls works and also preserves the passage.
8. Restore the prior window policy on leaving the reader, including navigating to Speed, opening tools and returning. Do not rely on an effect disposal that briefly fights the new screen's policy.

**Focused acceptance:** Paper and monochrome screenshots before/in/after focus; no black rectangle; bottom space reclaimed; actual cutout safe; one mid-passage and one end-of-article return; one Back and transient-edge reveal. Verify the normal destination layout/keyboard is still inset correctly. This is a few transitions, not a lifecycle matrix.

References: [Android immersive visibility](https://developer.android.com/develop/ui/views/layout/immersive), [cutout layout](https://developer.android.com/develop/ui/views/layout/display-cutout). Use supported behavior for the current SDK rather than raising SDK/dependencies as an unrelated fix.

## 7. Work package D — make Review a first-class everyday action

**Audit:** F06/F07. Files: `InboxScreen.kt`, `HighlightsFeed.kt`, `ReviewScreen.kt`, `MainActivity.kt`, `HighlightRepository.kt`, `ReviewScheduler.kt` only where needed.

### D1. Make entry unmistakable and always reachable

Keep the shared destination title geometry. Add a trailing **Review** text/icon button to the Highlights header, at least48dp tall, with accessible label Review highlights. At enlarged text, put this action in a short row immediately below the title if it cannot fit without wrapping/clipping the title. It remains a secondary visual treatment, a reliable fallback to the primary floating action.

Replace the passive Review panel with a floating **filled primary button** above bottom navigation: leading review icon, Review highlights, and a short state line. Normal theme uses the existing primary action role; monochrome uses black fill/white content. No shadow-dependent affordance. About64dp tall at standard scale, 16–20dp side gutters; expand for accessible text. Give the whole surface semantic `Role.Button` and one click action.

State copy:

| State | Primary action / supporting text |
|---|---|
| No highlights | Empty-state explanation + Open your latest read / Back to Reader; no enabled empty Review |
| No round | Review highlights / Start with your saved passages |
| Base round active | Review highlights / Continue · N left in this pass |
| Bonus phase | Review highlights / Revisit Important highlights |
| Finished | Review highlights / Start another round |
| State unreadable | Review remains available; show a truthful recovery state instead of pretending no round exists |

Review always means the global saved-highlights round. While search or Important filter is active, the persistent header action says **Review all** if needed to make that scope explicit. Clicking a matching quote still starts at that quote and then resumes the preserved global round.

### D2. Keep scroll behavior without hiding the feature

- On every destination entry, show the floating button once content is ready and no overlay suppresses it, even if scroll position is restored deep in the feed.
- Hide after16dp cumulative downward user scrolling; show after16dp upward scrolling or at list start. Reset accumulated direction when direction reverses. Observe position outside per-frame composition; update visible state only on threshold transitions.
- Do not interpret full-quote asynchronous expansion, result replacement, restoration or layout as a deliberate downward gesture.
- Hide floating action for IME visibility, selection, open actions sheet, confirmation or active Undo. Expose this through a small state/notice interface rather than hardcoded overlay heights or querying arbitrary views. Preserve the header fallback whenever no blocking modal is open.
- Search text alone does not make Review inaccessible after keyboard dismissal. Preserve query and filtered scroll position across Review and source visits.
- Keep overlay layout stable: no card reflow when the button appears/disappears. Add bottom content padding using the measured button height so the last quote/footer can be scrolled fully clear at large text.

### D3. Correct the Review presentation count

Extract one pure function used by both `ReviewRepository.observeSummary()` and `MainActivity`/Review. Define count as **presentations left in the active pass, including the current display**, matching what Next will actually consume.

- Base current A, queue[B,C], focused B: report3, not4. After Next, show A and report2. Preserve queue/order/random state and credit B once.
- Focused quote already consumed in the base pass: count its explicit extra presentation, then resume the unfinished pass. Do not remove a legitimate later Important bonus.
- Bonus phase: use phase wording; do not label a base count as the entire round total when a bonus may follow.
- Missing/deleted highlights: reconcile through existing repository transactions. Read-only feed summary must not start, advance or rewrite the round. If decoding fails, expose unavailable/recovery status rather than silently returning a fresh-round summary.
- Entering Review from a card records no review credit. Next/source opening retain existing expected-ID and once-per-presentation rules. Back is a normal pause, not completion.

**Tests:** small pure-state cases for the duplicated queued focus, focused previously reviewed item and preserved Important bonus; one repository/entry test proving summary reads do not write. Do not build a new scheduler campaign.

### D4. Make the quote itself comfortable

- Retain full quote text. Use one shared quote typography rule for feed and Review: selected reading font, preferred base reading size; base+6sp up to160 graphemes, base+3sp up to450, base otherwise; comfortable approximately1.5 line height. Respect system font scaling. Do not shrink long quotes below the user's base size.
- A short quote may breathe; a long quote starts near the top and scrolls. Avoid a huge empty decorative card or independently scrolling nested quote text inside the feed.
- Keep the Important star in a reserved top-trailing badge area, visible on both backgrounds. Toggling it must not move the currently read sentence merely by adding/removing reserved space.
- Footer: attribution plus a distinct Open source and compact More. At narrow width, source title gets its own line and actions wrap below. At least48dp interactive targets, even when icons look20–24dp. A40dp visual icon need not mean a40dp hit target.
- Retain Next as the Review primary action; Important and Share secondary. Make source navigation clear and preserve Review scroll on Back. A removed source never removes quote/attribution.
- Keep no decorative card rotation, fade or flourish under the finger. No automatic advance while reading.

**Focused walkthrough:** entry on unfiltered feed; entry while searched with keyboard dismissed; scroll down/up; tap queued quote; Next once; Important; source/Back; pause/continue; one short and one long quote. No fresh large corpus.

## 8. Work package E — refine search and coherent supporting states

**Audit:** F12. Reuse `ReaderSearchField` for Reader, Highlights and Find. Keep separate search engines/contracts.

1. Keep48dp minimum visual height at default text scale,20dp decorative icon,16sp Atkinson entry text,12dp radius,20dp outer gutters. Current geometry already measures correctly; reduce visual weight rather than shrinking touch targets. Grow naturally at enlarged text.
2. Monochrome uses white fill,1dp dark boundary and2dp focused boundary. Normal themes keep subtle existing surfaces. Clear has a48dp target and no meaningless empty-state icon.
3. Find header/field remain stable, results scroll below. Use actual available space and IME insets; avoid counting keyboard padding twice. Preserve the observed functional keyboard layout. Normal height around55%, expanded when needed, no empty oversized form.
4. Make state mutually exclusive: empty / searching / results / no matches / failure. Clear stale errors at a new request. Failure offers Retry for the same query; it must not simultaneously say no matches. Preserve cancellation and rethrow cancellation rather than translating it to failure.
5. Selected result has a strong leading marker/outline and selected semantics in monochrome, not white-on-white fill. Match emphasis stays distinguishable from selected-row state. Snippets start/end at safe grapheme boundaries and mark omitted context with ellipses rather than truncated fragments such as “ne.”.
6. Tap/submit dismisses sheet/IME, lands at the match and exposes the compact Previous · N of M · Next controls with Return to reading position. No duplicate counters. At large text, place Return on a second row rather than squeezing navigation.
7. Highlights empty search says which constraint is active. Do not tell the user to remove Important when it is not selected. Offer Clear search and, only if relevant, Remove Important. Keep global Review available.
8. Preserve library scope/fields, quoted terms, #label selection, stable label IDs and paging. Do not touch the dead older search component and mistake that for fixing the active UI.

Check one query per changed field, one clear/submit, one Find return and one selected/no-match/error state. An injected focused error test is enough; do not break network/Room on the owner's app to manufacture failure.

## 9. Work package F — repair future capture boundaries safely

**Audit:** F08. Keep this small and separate from Android UI commits.

1. Replace the class-name registry with per-element correspondence between live document and each clone. Read computed display once per candidate node, associate only that node's clone(s), and retain identity through the existing stripping/extraction order. A temporary clone-only marker or node correspondence is acceptable; never mutate the live page or leak markers into Markdown.
2. Include classless inline styles and context-dependent CSS. An element sharing a class with a block elsewhere must stay inline when its own computed display is inline.
3. Apply to Defuddle and Readability. If computed style is unavailable, fail safely without speculative global changes. Handle explicit inline display when determinable without the live view.
4. Only insert a separator between actual word boundaries without existing whitespace. Preserve pre/code, inline emphasis, punctuation and inline words. Use Unicode code-point/grapheme-safe edge checks, avoiding repeated recursive `textContent` copies of large nested subtrees.
5. Retain the original live DOM and existing saved article bytes. Do not add a bulk-recapture or migration feature.

**Focused checks:** existing extraction test file; shared-class contextual counterexample; classless block counterexample; inline/code/punctuation negative cases. Run Chrome typecheck/build if the source changes. One real future capture through the isolated extension may verify packaging when an available profile is already configured; do not recreate the 50+50 campaign or imply host tests prove browser integration. Record an unavailable browser setup honestly.

## 10. Commit order, verification budget and stopping rule

Recommended local commits:

1. A — selection identity and coach initialization with narrow regression tests.
2. B — monochrome contrast/presentation and Speed/motion corrections.
3. C — reader cutout/window layout, keeping native viewport invariants.
4. D/E — Review entry/count/quote comfort and shared search refinements.
5. F — extraction identity fix and counterexamples.
6. Final evidence/copy/continuation update.

After each behavioral package, build QA and run only affected tests/flows. Reuse the sample and a few deliberate highlights; add one small multi-part QA article only if necessary to exercise an affected path. Keep APKs installed after instrumentation. No database insertion presented as capture proof.

One final acceptance pass must show:

- One pen selection + adjustment = one persisted ID, correct final range and Undo.
- Visible Undo works in monochrome; selected navigation icons remain visible.
- Four distinct named mark choices and readable saved marks; original colors return on leaving monochrome.
- Speed focal glyph clearly emphasized and long word intact; brief pause/return works.
- Focus has matching background to edges, no hidden-control gap/black letterbox, protected camera and preserved passage.
- Review visible on entry and reliably accessible after scroll/search; focused queued count correct; source/Back resumes.
- Shared search/Find coherent and usable with keyboard; return to origin works.
- One light/dark contrast comparison and one enlarged-text check of the changed dock/header/CTA. No exhaustive theme/device matrix.

Run the existing fast Android unit suite, lint, debug build and16KB alignment check once on the final candidate, unless already run after the last relevant change. Chrome focused tests/typecheck/build only if F changed it. A brief crash-log check is sufficient for this pass; no new statistical performance campaign. Re-run only failures and surfaces affected by subsequent fixes.

If a check fails, capture the state, make the smallest complete fix, and repeat that check. Do not declare completion because tests are green while screenshots still show a known defect. Stop optional polish when these criteria pass; sustained comfort belongs to the user's trial.

## 11. Installation and truthful handoff

When implementation and focused checks are complete:

1. Verify the normal package's current signing identity and record owner integrity **immediately before** the install. The user may have added data since earlier counts; never reuse old counts as current.
2. Build the normal debug variant separately from QA. Archive the exact APK and record its source commit/full SHA-256.
3. Install normal `com.reader.app` in place, preserving data/keys/pairing. Never uninstall first. If signatures mismatch, stop the install and resolve identity; do not work around it by deleting the app.
4. Pull/read the installed base APK and compare its full SHA-256 with the exact artifact installed. A rebuild can have different ZIP padding; compare exact installed artifact bytes for installation proof. If assessing rebuild content equality, hash uncompressed entry bytes rather than calling matching CRC32 alone cryptographic equality.
5. Compare owner integrity before/after. Launch once and confirm Reader opens with owner data intact. Do not run the QA corpus or destructive walkthrough in the normal package.
6. Leave QA data isolated, restore any changed device-wide settings, stop temporary controllers and preserve useful evidence. Update the copy deck and behavioral assertions with the final copy.
7. Update `START_HERE.md`, `CONTINUE.md` and the next-agent prompt with one current source/install pointer, completed work packages, actual check results, remaining limits and user-trial instructions. Mark this plan complete only when its required behavior passes.
8. Leave clean local commits. No push, merge, release or public deployment. Hand the user the installed build for seven days, with concise known limitations.

The final report must state what was implemented and **PASS/FAIL/NOT MEASURED/BLOCKED** separately. Actual electrophoretic refresh quality, physical NXTPAPER appearance under changing lighting, long-session comfort and unusual lifecycle cases remain unmeasured unless directly observed. Do not promise that a software monochrome theme certifies all e-ink hardware.

## 12. Deliberate deferrals

Keep source-title/header geometry that already passes; do not restyle it again unnecessarily. Preserve ordinary reading/listening and existing Review mechanics. Defer new fonts, new organizational features, scheduling algorithms, extra tutorials, animations and feature discovery campaigns. The best improvement now is a small set of dependable, legible interactions followed by real daily use.
