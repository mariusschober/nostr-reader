# Current Reader handover — 10 September 2026 (plan execution, paused after Batch C)

Branch: `codex/reliability-finalize`, **ahead 23 of origin, nothing pushed**. Head: `23424ae` ("reader session continuity"). This file replaces the September 9 handover; the boundaries section at the bottom is carried forward verbatim.

This session executes "The Reader's Hours" plan (six workstreams W0–W6 over batches A–F, goal: the world's best read-later app for a reader spending 1–2 h/day — calmer, warmer, faster, offline, private). **Batches A, B, C are complete and green. The pause point is here: Batch D (W4) not started.**

## Commits of this effort (oldest → newest)

| Commit | Batch | What |
|---|---|---|
| `437c5eb` | A | Fixed tab order (no empty-Inbox redirect, QA contract renegotiated), trust hardening (highlight-remove always confirms, archive swipes felt, coach line, undo-window preserved), `ui/theme/Motion.kt` + `ui/Haptics.kt` vocabularies, Later-tint calm, `docs/COPY_DECK.md` |
| `630f54f` | B | Truthful rows: `timeLeft`, Done pill + finished context (DocumentSummary now carries `finishedAt`/`highlightCount`), per-site hue marks, **Archive as peer tab**, re-tap scrolls home, FlowRow select bar, empty-state cleanup, reader header uses `shortDisplaySource` |
| `73fb2dd` + `ce59bf1` | B | Highlight rows link back to source essay (`→ title`, TOCTOU-guarded `Route.Reader(id, highlightId)`); feed-position test updated to the new affordance |
| `23424ae` | C | Reader continuity: finish card "Read another" + first-finish line + confirm haptic; whole-document footer % (part-local jump bug fixed), "Saved" token dropped; immersive tap-to-toggle chrome (150 ms fades, 2 dp progress line); keep-screen-on on Reader/Rsvp; per-part bookmarks survive process death (Saveable); images project as "[Image — tap to view]" links; Custom Tabs (`androidx.browser:browser:1.8.0`); Appearance: bold body (real variable weight), A−/A+ steppers, Sepia background |

Also on the branch before this effort (previous session): Room v13 labels/finishes/stats, capture pipeline, `659ebab` QA-contract reconciliation.

## Verified state

- Host gates green at `23424ae`: **222/222 unit tests, lint, both debug APKs, 16 KiB alignment** (`cd android && ./gradlew --no-daemon test lint assembleDebug assembleDebugAndroidTest verify16KbAlignment`).
- Device gates on TCL T807D, Android 16 (serial `ZXKRS4VKGQ8PWGEQ`, QA package `com.reader.app.qa`):
  - UiCompletionInstrumentedTest 13/16 in full-suite load; **all three failures are the documented injection-timing flake family** — `articleListSettingsReaderAndRecreationKeepPosition` and `archiveBatchBodySelectionAndOneUndo` fail **byte-identically on a pristine HEAD worktree** (proven today by building `HEAD~1` QA pair in `/tmp/reader-pristine`, then reinstalled own build); `highlightsSourceLinkAndSettingsKeepFeedPosition` (renamed from `highlightsReviewSource...`) passes in isolation. Every renamed/updated test is green in isolation or focused pairs.
  - Search 9/9, ArticleMoves 2/2, ReaderFlow+ReaderTransition+HighlightMenuGate 7/7 green after Batch C.
- `KNOWN_LIMITATIONS.md:29` carries the renegotiated tab contract clause (fixed order, Inbox always visible, Archive peer tab). `docs/COPY_DECK.md` freezes the 15 most-seen strings.

## How to continue (conventions an agent must follow)

1. **Worktree hygiene**: `git status` must be clean before editing. Note: `stash@{0}` holds an unowned pre-plan WIP ("welcome-seed extraction refactor" of `MainActivity.kt`) — decide drop-or-adopt before touching MainActivity's welcome-seed block. A stale unowned worktree `nr-pristine2` exists; leave or remove consciously.
2. **Copy changes are contract changes**: all UI copy is hardcoded Kotlin. Before changing any string, `grep -rn '"<old>"' android/app/src/androidTest` and update the asserting test **in the same commit** (`UiCompletionInstrumentedTest.kt` is the frozen QA contract; `node(label)` matches exact text/contentDescription). Update `docs/COPY_DECK.md` too.
3. **Host gate after every task**: the gradle line above. 222+ unit tests must stay green.
4. **Device gate per batch** (device `ZXKRS4VKGQ8PWGEQ` = TCL T807D): build QA pair `./gradlew --no-daemon --max-workers=2 -PreaderQa=true assembleDebug assembleDebugAndroidTest`, install both APKs, then `adb -s ZXKRS4VKGQ8PWGEQ shell am instrument -w -e class com.reader.app.UiCompletionInstrumentedTest com.reader.app.qa.test/com.reader.app.QaTestRunner`. Keep screen awake (`svc power stayon usb`). Several tests refuse to run on the normal package (`isolated()` checks `.qa`).
5. **Prove flaky ≠ regression**: build the suspected-good state (e.g. `git worktree add /tmp/reader-pristine <commit>`) with `-PreaderQa=true`, run the failing test there. Identical failure = environmental (documented pattern); different/absent = your regression — fix.
6. **Commit style**: Conventional (`feat(android): …`), body carries evidence (test counts, device results). One commit per batch. **Nothing pushed/merged/released without the user's word.**
7. **Docs duty**: dated bullet in `KNOWN_LIMITATIONS.md` after device runs; `docs/screenshots/README.md` updates when screenshots change (device, date, source hash, QA APK SHA-256).

## Design decisions already made (do not relitigate without the user)

- Empty-Inbox auto-redirect removed and Inbox tab always visible; QA contract test rewritten in `437c5eb` (the "incoming content never steals the chosen tab" clause survives).
- Archive is a peer tab (may sit beyond the tab-row viewport on narrow screens; `tapTab()` helper scrolls the row). Header Archive icon retired.
- Finished rows: Done pill + full-strength title + "✓ Sep 2 · 3 highlights" meta (no dim).
- Footer: whole-document %, no "Saved" token (failures surface via existing "Save failed — Retry").
- Images: honest "[Image — tap to view]" placeholder; offline image storage explicitly out of scope.
- Highlight notes: deferred (would need Room v14) — decided out of this cycle.

## Remaining work

### Batch D — W4 organization coherence (next up; no schema change)
1. **Kill the silent filter leak** (P0): show active tokens in search mode — "Labeled climate ✕ · Past week ✕" row above results + count line; no-results copy "No results for 'solar' labeled climate from the last 7 days. Clear filters?" (reuse `FilterEmpty` sentence style, `InboxScreen.kt`).
2. **`#tag` in search box**: leading `#token` → label filter (autocomplete dropdown from `labelCounts`); non-degenerate input fallback "Search needs letters or numbers" instead of bare "No results" (`SearchQuery.kt:15` strips `#` today; `SearchRepository.kt:104-107`).
3. **Label manager screen**: Settings → "Labels" — list with counts, rename ("Rename in N articles?"), merge-into picker, delete confirm. DAO methods already exist unused: `Labels.kt:102-106 rename/mergeInto/deleteLabel`.
4. **Multi-select label chips (AND)**: `selectedLabelNorm: String?` → `Set<String>`; intersect in `applySortFilter` (`InboxScreen.kt`); update chip TalkBack semantics (`UiCompletion:289-295` pins `"Filter by label …, N articles"` + stateDescription).
5. **Persist label + scope** in DataStore alongside SORT/AGE (`Prefs.kt:49-50`); add "Unlabeled" pseudo-chip (`labelsByDoc[id].isNullOrEmpty()`).
6. **Discoverability**: seed label "getting-started" on the welcome doc; one line in `WELCOME_MARKDOWN` ("Tag anything — press ⋮ → Edit labels — then filter with the # chips."); ghost chip "Add your first label" when `labelCounts.isEmpty()` and library ≥ 10.
Gates: host line above + SearchInstrumentedTest + UiCompletion on device. Commit: `feat(android): one coherent organization system`.

### Batch E — W5 warmth & delight
1. Day-count line on the shelf: once/day, dismissible, "3-day run · 24m this week" (reuse `runDays` + week minutes from `computeLibraryStats`); reuse anti-streak voice for the 7-day line.
2. Pairing success moment: replace "Connected" toasts (`MainActivity.kt:715,756`) with "Connected. Private channel to Chrome, established." + `Haptics.commit` + one-beat hold before pop.
3. Transient-system unification: route save-confirmations (toasts at `MainActivity.kt:326,492,862,826,883`) through themed snackbars; map error codes to human lines ("That page wouldn't open. The link is saved — try again from the row." replaces `"Link saved — $detail"` at `:889,895`).
4. Motion: `Modifier.animateItem()` on library rows; route push/pop fade+slide (`Motion.Nav`, reduced-motion aware via `rememberReduceMotion()`); tab underline glide; star-badge 180 ms scale-settle in HighlightsFeed.
5. Welcome row "Start here" secondary line until opened (flag beside `welcomeShown`); toast copy names the artifact: "Saved your first piece — Welcome to Reader. It's on your shelf, offline."
6. Empty-state line art (static Flexoki-toned drawing behind EmptyShelf title; no emoji).
7. Milestone re-fire check: verify `takeMilestone` (`Prefs.kt:97`) never consumes-without-showing; pending milestone surfaces on next Library open.
8. First-label nudge: one-time snackbar after first `assignNorm`: "First label made. It'll find every piece with that topic, including ones you save later."
Gates: host + device (UiCompletion + TalkBack arg run `qaTalkBack=true`). Commit: `feat(android): warmth, motion and quiet celebration`.

### Batch F — W6 evidence & truth
1. Screenshot set via existing `qaUiCapture true -e qaCaptureName <tag>` harness (`UiCompletionInstrumentedTest#captureAppearance...` pattern): inbox light/dark, article light/dark (finish card + footer + immersed), search + results, all empty states light/dark, 320dp selection bar, Archive tab. Promote into `docs/screenshots/` (stable filenames) + update `docs/screenshots/README.md` (device, date, source commit, QA APK SHA-256).
2. Full gates: `test lint assembleDebug assembleDebugAndroidTest verify16KbAlignment` + TalkBack traversal + Search (fallback path) + migration suite untouched (no schema change).
3. Docs: `KNOWN_LIMITATIONS.md` dated bullets per batch; add "superseded by 222/222" notes to stale counts in TEST_REPORT/CONTINUE history; ARTIFACTS.json only for new artifact builds. **No CHANGELOG: the repo keeps none — the plan's "if the repo keeps one" condition does not trigger; record that decision.**
4. Traceability: final commit body lists every claim → test/screenshot/TalkBack trace. Stop: nothing pushed without the user's word.

## Open issues / unsolved

1. **TCL load-flakiness** (pre-existing, proven): scroll-restore and batch-notice tests fail under full-suite load, pass isolated; identical on pristine HEAD. Do not "fix" by loosening assertions; re-run isolated for evidence.
2. **Archive tab off-viewport on narrow devices** — standard Material overflow; tests use the `tapTab()` helper (drags inside the tab row's own bounds — the pinned attention label would eat a screen-wide swipe). Real-user implication accepted.
3. **`DocumentSummary.highlightCount` refreshes only on documents-table changes** (Room flow trigger), not on highlight writes — rows may lag a highlight count until library refresh. Cosmetic; document or add a highlights-triggered revision later.
4. **Relative image URLs** in the "[Image — tap to view]" link hit the "web links only" snackbar (no base-URL resolution at projection time). Resolve at capture time or store absolute URLs in extraction — future item.
5. **W3.10 stretch items not built** (part-list/TOC via "2 / 5" pill tap; night dim slider) — optional, only if D/E/F land green with time left.
6. **Room version stays 13** — this effort is migration-free by design. Any new column (e.g. highlight notes) must follow the v14 procedure (schema JSON in `android/app/schemas/`, same-commit migration test, full-chain list at `ReaderDbMigrationInstrumentedTest.kt:229`).
7. **Older reports carry stale test counts** (86/137/196 vs 222) — Batch F adds superseded-notes; do not rewrite history.

## Preserve these boundaries (carried forward verbatim)

Do not clear owner app data, uninstall the normal app, rotate keys, or alter the paired browser profile to make tests pass. Re-identify devices and always specify an ADB serial. Synthetic/fault work uses isolated QA packages/profiles. Generated artifacts and private storage/screenshots stay local; only synthetic screenshots and sanitized summaries are tracked.

Do not infer full beta, public-relay, tablet/foldable, other-device, store-signing, acoustic speech or exhaustive process-death acceptance from these focused checks. Already-flattened imports require reimport; do not rewrite immutable article text or saved quote anchors speculatively. Exports are one-way and exclude pairing keys.
