# Continue with the final usability pass

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
