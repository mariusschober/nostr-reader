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

Still to implement from the spec: true fullscreen focus mode with immersive system bars; native text-action menu suppression inside continuous highlighting; the search-landing-only "up" button provenance; the Find-in-article sheet refinement; Inter; the app-wide e-ink display policy; full-width adaptive highlight cards with the Important badge; Review-from-a-tapped-quote with round preservation; the floating Review banner; and the merged-label/heading boundary diagnosis. Then device verification, the copy-deck/current-state update and the final in-place normal install with fresh owner-preservation evidence.
