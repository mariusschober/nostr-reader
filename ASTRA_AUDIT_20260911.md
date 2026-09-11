# Reader audit — 11 September 2026

**The pass is not ready to call complete.** The core reading flow is usable, but continuous highlighting can duplicate a selection, monochrome Undo is invisible, and the requested focus/Review/e-ink experience remains incomplete. Execute [READER_POLISH_PLAN_20260911.md](READER_POLISH_PLAN_20260911.md) next; do not restart the older broad acceptance campaign.

Audited product source: **`f20828b3e2b4c255419f5436523d5b5de7d49104`**, branch **`codex/reliability-finalize`**. Checkout tip at audit start: **`f53bb8c`**. `git diff f20828b..HEAD -- android/app/src/main chrome/src` was empty, so the current source was inspected without discarding the subsequent test/documentation commits. No product source was edited during this audit.

## Findings, ordered by severity

All source references below are relative to this repository and refer to the audited product revision. P0: **none found within the focused scope**. Device observations use only `com.reader.app.qa`.

### F01 · P1 · Adjusting one continuous selection saves a second highlight

**`android/app/src/main/java/com/reader/app/ui/screens/NativeArticleView.kt:251`**, also `:507`; persistence entry at `PreparedReaderScreen.kt:696`.

On TCL, long-pressing “and” in the sample's highlighting instructions saved a word. Dragging its end handle down extended the visibly active selection, but the feed and QA database retained both “and” and the expanded passage, under different IDs with the same starting anchor. Evidence: `19-long-highlight-start`, `20-long-highlight-end`, and `qa-highlight-records.json` in [the audit evidence](evidence/astra-audit-20260911/manifest.json). Two intended selections produced three rows.

The native callback unconditionally replaces `selectionSession` during ActionMode creation, whereas an earlier pen-selection emission can already allocate it. This is a concrete identity discontinuity to trace; this audit did not instrument the exact callback ordering. **Smallest fix:** own the session ID across one actual selection lifetime, including emissions before ActionMode creation; rotate it only for a genuinely new selection. Preserve the mutation journal and revision guards. Add one integrated test that waits for the initial pen save, drags a handle, and asserts the same row ID and final range. The current native menu gate does not exercise database persistence and cannot certify this behavior.

### F02 · P1 · E-ink makes Undo invisible

**`android/app/src/main/java/com/reader/app/ui/theme/Tokens.kt:147`**; `NoticeCoordinator.kt:35` uses the default Material Snackbar.

The monochrome scheme sets both `inverseSurface` and `inversePrimary` to black. After removing the QA “place” highlight, the accessibility tree contained **Undo**, but the screenshot showed only “Highlight removed” and the white dismiss icon on black. The action's text is black on black. Evidence: [38-remove-notice.png](evidence/astra-audit-20260911/38-remove-notice.png) and its XML. The subsequent automated Undo tap did not restore the row; timing versus expiry was not isolated, so this is **not** a finding that the Undo handler is broken.

**Smallest fix:** pair inverse action text with the inverse surface correctly; explicitly style and verify Snackbar action/focus/disabled states. Retest removal → visible Undo → original metadata restored before the notice expires.

### F03 · P2 · Focus mode retains a full-width cutout letterbox

**`android/app/src/main/java/com/reader/app/ui/screens/PreparedReaderScreen.kt:300`**, `MainActivity.kt:89`, `android/app/src/main/res/values/themes.xml:2`.

The hide/show calls work: status and navigation inset sources become invisible. But the QA window begins at **y=130**, with a black letterbox at **0–130px**, because the window does not opt into drawing around the display cutout. Its content is 1080×2210 on a 1080×2340 display. The reported camera cutout is x=505–575, y=0–130. This is an Android window-layout problem, not a remaining bottom-action row. Evidence: [02-focus.png](evidence/astra-audit-20260911/02-focus.png), [window excerpt](evidence/astra-audit-20260911/window-focus-excerpt.txt).

**Smallest fix:** explicitly own edge-to-edge/cutout layout and visible insets for the reader, paint the reading surface to the edges, and protect the actual camera region. Remove redundant inset reservation, not safe protection that prevents the camera covering words. Preserve/restore the prior window policy on exit. Android documents immersive visibility and cutout layout as separate concerns: [immersive mode](https://developer.android.com/develop/ui/views/layout/immersive), [display cutouts](https://developer.android.com/develop/ui/views/layout/display-cutout).

### F04 · P2 · Monochrome highlighting loses identity and still renders pastel article marks

**`PreparedReaderScreen.kt:466` and `:608`**, **`HighlightActionsSheet.kt:44`**, `HighlightsFeed.kt:413`, `ReviewScreen.kt:169` (all under `android/app/src/main/java/com/reader/app/ui/screens/`).

All four picker options are white circles, with only the selected one checked. Color names exist for accessibility but are not visible. In the article, `nativeMarks` bypasses the monochrome policy and still uses the normal Yellow/Green/Cyan/Purple backgrounds. The screenshots confirm yellow saved text and colored native handles in the white theme. Feed/Review markers collapse every color to the same black stripe. Evidence: `10-eink-highlight-actions`, `17-eink-pen`, `20-long-highlight-end`, `35-large-reader`.

**Smallest complete fix:** one presentation mapping shared by pickers, native spans, feed and Review, retaining stored color IDs. Use visible names/identifiers and distinguishable monochrome marks; separately style active selection so it is distinguishable from a saved mark. Preserve native handles, hit testing and quote anchors.

### F05 · P2 · Speed has no focal-letter distinction in e-ink

**`android/app/src/main/java/com/reader/app/ui/screens/RsvpScreen.kt:145`**, **`ui/theme/Tokens.kt:98`**.

All three word fragments have the same 48sp font weight and black color. In the observed word “Inbox”, the intended focal “n” is indistinguishable. Evidence: [14-eink-speed.png](evidence/astra-audit-20260911/14-eink-speed.png).

**Smallest fix:** give the focal grapheme a real bold face plus a restrained monochrome underline/anchor cue, keeping its optical center fixed. Preserve the existing blue emphasis in normal themes and the selected reading font. Check one long word fits rather than clipping the word fragments.

### F06 · P2 · Review is discoverable only in a fragile, low-contrast banner

**`android/app/src/main/java/com/reader/app/ui/screens/HighlightsFeed.kt:112`**, `:214`, `:265`; header wiring `InboxScreen.kt:196`.

A fresh unfiltered feed does show a tappable “Review highlights / Start a round” panel. It is not literally absent in every state. In monochrome it is white on white, without a border or an action icon. Entering `place` hides the only Review entry, including after the keyboard closes. Scrolling can also hide it; the hidden state is saveable, and no header fallback exists. Evidence: `06-highlights-one`, `09-eink-highlights`, `25-highlight-search` and `36-highlights-return`.

**Smallest fix:** make the floating entry an unmistakable primary button and add a quiet, always-available **Review** header action. Keep its global scope explicit while filters/search are active. Preserve scroll-direction behavior without making access depend on it. Suppress only the floating entry for the keyboard, selection, sheets and active Undo; do not reset rounds on entry.

### F07 · P2 · Focused Review double-counts a queued quote

**`android/app/src/main/java/com/reader/app/data/HighlightRepository.kt:155`**, **`ui/MainActivity.kt:576`**.

With three saved highlights, starting a round reports three. Returning to the feed, searching “place”, and tapping that queued quote reports **four**. Next returns to the preserved long quote and suddenly reports **two**. The focused quote is counted once separately and again inside `baseRemaining`. Evidence: `24-review-start`, `26-focused-review-count`, `27-review-after-next`, and the three QA records.

**Smallest fix:** use one pure presentation-count function for the feed summary and Review screen, excluding a focused quote from the base queue it will consume. Do not blindly deduplicate legitimate Important bonus presentations. Make base-versus-bonus wording truthful; no calendar/due-date claims.

### F08 · P2 · Chrome's block-boundary fix can damage genuine inline words and miss real blocks

**`chrome/src/extraction/pipeline.ts:64`**, `:165`.

The implementation records class names globally, then treats every clone element with any such class as block-level. Contextual CSS can render the same class block in a legend and inline within prose. An execution of the unchanged helper code against a small DOM produced **`microscopes` → `micro scope s`**. Conversely, classless `style="display:block"` spans still produced `workersAll`; no class token carries their computed display. [Counterexample results](evidence/astra-audit-20260911/chrome-boundary-counterexamples.jsonl).

**Smallest fix:** transfer block identity per corresponding element from the live DOM to the extraction clone, before sanitization; do not infer element layout from shared class tokens. Apply both extraction paths and strip any private marker. Preserve code, punctuation, genuine inline adjacency, live DOM and stored articles. The existing eight extraction tests pass despite these counterexamples.

### F09 · P2 · E-ink selected navigation icons disappear

**`android/app/src/main/java/com/reader/app/ui/ReaderBottomNavigation.kt:18`**.

The selected icon uses `c.text` and its indicator uses `c.divider`: both black. Highlights therefore shows a black capsule without its quote icon. This is visible in `09-eink-highlights` and `38-remove-notice`. **Smallest fix:** use white icon content on the black selected indicator; retain black selected-label text on the white page. Check all three destinations and other custom selected surfaces for the same role mismatch.

### F10 · P2 · Reduced-motion mode leaves added kinetic flings and programmatic flings active

**`android/app/src/main/java/com/reader/app/ui/screens/NativeArticleView.kt:107` and `:205`**, `ReviewScreen.kt:61` and `:133`.

`reducedMotion` only changes the now-disabled organizational swipe settle. `smoothScrollToTop()` and post-release kinetic fling never consult it. Review consults the system-only preference for advancement, and still rotates/fades content during drags. **Smallest fix:** use the effective display policy consistently; instant programmatic jumps, no added kinetic continuation in monochrome, no decorative rotation/fading. Preserve direct touch motion, native selection and its required edge autoscroll. This is a code-confirmed specification gap; physical e-ink refresh quality was not measured.

### F11 · P2 · A fresh install can skip its only highlighting explanation

**`android/app/src/main/java/com/reader/app/prefs/Prefs.kt:100`, `:107`, `:156`**.

Reading the optional sample writes `WELCOME`, after which `legacyInstall()` returns true. First highlighting then skips the explanation, as happened on the empty QA install. Saving appearance can cause the same issue because false `highlightCoachSeen` is not persisted. **Smallest fix:** initialize the absent flag once, before new-install preference writes; persist false for genuinely fresh installs and true for existing installs. Thereafter honor the explicit value and mark seen only when the explanation appears. Existing owners must not be retaught. The extracted decoder itself is equivalent to its predecessor; the initialization policy is the defect.

### F12 · P3 · Search and Find need explicit monochrome states; docs overstate completion

**`android/app/src/main/java/com/reader/app/ui/ReaderSearchField.kt:67`**, `PreparedReaderScreen.kt:809` and `:824`; **`CONTINUE.md`**, **`START_HERE.md`**.

The 48dp field geometry is reasonable, but applying 40%-alpha black makes it a heavy gray block. Find's selected result uses the same white surface as unselected rows, and an exception can show both failure and “No matches”. Its keyboard layout, match landing and return did work on TCL. Use white plus a strong boundary/focus outline in monochrome, a visible selected-row marker, and mutually exclusive result states. Preserve the compact geometry and functioning keyboard layout.

Documentation still calls every spec item implemented, contains historical tip/source pointers as though current, and the audit brief refers to “one remaining item” that its handover no longer identifies. Add a dated authoritative correction and clearly label historical checkpoints rather than rewriting old evidence.

## Per-axis result

| Axis | Status | Basis and limit |
|---|---|---|
| Code review | **FAIL** | Selection identity, monochrome action roles, Review counts and capture counterexamples above. Review targeted the changed paths and relevant foundations, not a line-by-line certification of all 51 changed files. |
| Performance | **NOT MEASURED** | No profiler, frame benchmark, battery measurement or statistical run. No visible app stall was observed in the short interactions. The style scan is one live read pass, but recursive `textContent` extraction at nested boundaries prevents a blanket O(n) claim. |
| UI | **FAIL** | Cutout letterbox, invisible Undo/nav icon, indistinguishable swatches/focal letter, weak Review hierarchy. Shared Reader/Highlights/Settings title bounds match at normal size. Four reader actions remain reachable at 1.4 text scale. |
| UX | **FAIL** | Core highlighting and Review trust/discoverability defects. Find → match → return, Review → source → Back, and the compact four-action dock are useful working foundations. |

## Focused walkthrough and evidence

1. **Reader → sample → reading:** useful entry, shared header and four direct actions — PASS for the observed path.
2. **Tap focus → Back:** bottom chrome removed and Back restores it; top letterbox remains — FAIL.
3. **Pen highlighting → handle adjustment:** no floating text-action menu appeared; native handles remained; expanded selection became a second row — FAIL.
4. **Highlights → monochrome → palette:** full quote and attribution accessible; color identity and selected icon fail — FAIL.
5. **Speed:** opens and returns to later article progress; focal emphasis fails — FAIL. Playback cadence/long-word clipping were not measured; the attempted running screenshot was not treated as proof of timing.
6. **Review → focused quote → Next → Important → source → Back:** actions work, query/return preserved; count is wrong — FAIL.
7. **Find with keyboard → result → Return:** first match of three, then return from 12% to the prior 38% — PASS for this one-part article. No fresh multi-part test.
8. **Enlarged text 1.4:** four reading actions wrap to 2×2 with visible labels — PASS for this screen. System font scale restored to 1.0 immediately.
9. **Remove QA highlight:** quiet notice shown; Undo visually absent — FAIL. Restoration NOT MEASURED because the attempted tap may have arrived after expiry.

Fresh host checks: **23/23** selected Android unit tests (ReviewScheduler, PrefsMigration, ArticleNavigation), **8/8** existing Chrome extraction tests. No fresh full lint/build/alignment/device-instrumentation campaign: those remain prior evidence. Current QA-process AndroidRuntime error capture was empty. No TTS acoustic check, physical panel/ghosting check, long session, intake, scale corpus, lifecycle matrix or release certification.

## Installed state and handoff

- Normal installed APK hash read from TCL: **`8928ca661fad20f49cd14e3fe8a6c7640826534521bd5674ee08fdbe1347c107`** — matches the handover. Normal app was not launched, cleared, reinstalled or edited by this audit.
- QA installed APK: **`2ef7bec9b355b6343d53569dd7ae71eb053256751143c54ca9276dba95b1bcbc`**. QA had no corpus; the earlier instrumented run had removed it. The audit used the optional sample, not a replacement intake.
- QA now has the sample article and two highlights, one Important, with an unfinished Review round. The third QA highlight (“place”) was removed during the invisible-Undo check. Owner data was never used for these mutations.
- Device: TCL T807D / Android 16 API36, serial `ZXKRS4VKGQ8PWGEQ`, 1080×2340, density456, font scale restored1.0.
- Screenshots are software captures from the TCL. They do not measure the physical NXTPAPER viewing mode or electrophoretic refresh/ghosting.
- [Evidence manifest](evidence/astra-audit-20260911/manifest.json), [implementation plan](READER_POLISH_PLAN_20260911.md), [DeepSeek starter prompt](NEXT_AGENT_PROMPT_DEEPSEEK_POLISH.md).

Treat “world's best” as the design ambition. Closing these concrete failures earns a better seven-day trial candidate; it does not prove a comparative or sustained-use claim.
