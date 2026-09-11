# GPT Astra audit — Reader review, performance, UI and UX

You are a rigorous, independent reviewer and auditor for an Android reader app. Perform a **code review** plus a **performance**, **UI** and **UX audit** of one completed local change pass. Report findings before you change anything; you may read, build and use the device to verify claims. Findings first, ordered by severity.

## Checkout and exact revision

- Repo: `/Users/schober/Projects/Nostr Reader`
- Branch: `codex/reliability-finalize` — work stays local. Do not push, merge or release.
- Audited revision (HEAD): **`18c9ac203c1a11e216417c8a7cef160a246dd70b`** — `feat(android): review from a tapped quote without losing the round`.
- Feature commits in scope, newest first:
  - `18c9ac2` review from a tapped quote without losing the round
  - `9fa220d` floating Review banner replaces the top entry card
  - `bdf17fc` fullscreen focus, search-only up button, native menu suppression while highlighting
  - `5dbb98a` final-pass stage 1 — Reader rename, four reading actions, warmer Paper, one-time highlight help
  - `99c5cf9` shared destination headers, compact search, prominent Review
  - `fd1b813` quieter shelf markers and progress stroke
- Base chain: `b14f128c54996515f45218f8d912ef106c174446` on `15a77ff647ac39f4e9165e47504d553504a1ac8b`.
- Diff to audit: `git diff 15a77ff..9fa220d -- android/ docs/` (use `git show` per commit for exact hunks).
- Installed candidate: normal `com.reader.app`, debug `0.9.0-beta.1` / code 2, APK SHA-256 `8052b8a1d1d43a4188a727f69fc57b2fbe3120ce301b214ee507838b3c6680d3`, source commit `18c9ac2`, installed in place on TCL T807D / Android 16 / serial `ZXKRS4VKGQ8PWGEQ` with the installed file hash verified identical and owner data preserved (`evidence/focused-20260910/installation-20260911.json`). The QA package is `com.reader.app.qa`.

## Read first

- `FINAL_USABILITY_SPEC_20260911.md` — the current specification; it supersedes conflicting older requirements.
- `FOCUSED_HANDOFF_20260910.md` — implemented scope, evidence, state/code map, trial boundaries.
- `CONTINUE.md` — running stage-by-stage progress of the final usability pass.
- `NEXT_AGENT_PLAN.md` — the earlier plan (work packages A–E); superseded where it conflicts with the new spec.
- `docs/COPY_DECK.md` — the copy/behavior contract.
- `evidence/focused-20260910/installation.json` and `final-verification-20260910b.txt` — installed build, hashes, checks actually run.
- `evidence/focused-20260910/*.png` and `walkthrough.jsonl` — real TCL observations (not mockups).

## Scope note

The working tree is clean at the audited revision; review only committed code. The two native checks already recorded for this revision are the end-of-article finish state and the single adjusted native selection (`installation-20260911.json`); do not repeat the 100-article intake.

## What changed (audit targets)

- `ui/DestinationHeader.kt` — shared 52dp header row for Reader/Shelf, Highlights, Settings and Review.
- `ui/ReaderSearchField.kt` — compact 48dp search surface.
- `ui/screens/PreparedReaderScreen.kt`, `ui/screens/NativeArticleView.kt` — four reading actions (Highlight · Contents · Listen · Speed), fullscreen focus (immersive bars), native text-action menu suppression while highlighting.
- `ui/screens/HighlightsFeed.kt` — full-quote adaptive cards, Important badge, floating Review banner, and the card tap that enters Review.
- `ui/screens/ReviewScreen.kt` — compact header, truthful phase progress, Newsreader measure, Source action, completion state.
- `core/ReviewScheduler.kt` — focused presentation (`focusedId` / `focusedReviewed`, `presentedId`, `focus`) that preserves an unfinished round.
- `data/HighlightRepository.kt`, `data/Highlights.kt` — read-only `observeSummary()` / `ReviewDao.observeParts()`.
- `ui/screens/InboxScreen.kt`, `ui/screens/SettingsScreen.kt`, `ui/MainActivity.kt` — wiring, one-time search focus, Back-closes-search, Reader rename, warmer Paper surface, one-time highlight help.
- `ReaderFlowInstrumentedTest.kt`, `UiCompletionInstrumentedTest.kt` — updated completion-copy expectations.

## Audit axes and required questions

**1. Correctness / code review**

- Does `DestinationHeader` actually guarantee identical title geometry across Reader, Highlights, Settings and Review, or can content/actions still shift the baseline or reserve height differently?
- Is the Review summary genuinely read-only (never starts, advances or repairs a cycle just to render)? Look for any write path, side effect, race or re-subscription.
- Do the four reading actions preserve existing speech/session state (Listen pause/resume, Speed pause + flush) and stay ≥48dp at font scale 1.0–1.4?
- Is fullscreen focus mode enterable and exitable without stranding the system bars or overlapping content, and does native menu suppression restore correctly?
- Is the search field's one-time focus and Back-closes-search behavior correct across config change, process death, returning from an article, and empty → typed → cleared?
- Is the phase progress count correct in every phase, including when Important bonus items follow the current queue?
- Any regression in the gated instrumented tests' completion-copy contract?
- Do the claims in `FOCUSED_HANDOFF_20260910.md`, `CONTINUE.md` and `docs/COPY_DECK.md` match the actual code?

**2. Performance**

- Compose recomposition/measure cost of the shared header, floating Review banner and search field; unstable lambdas/keys, allocations during item composition, whole-list recomposition on scroll?
- The floating banner appears/hides on scroll direction — does that cause per-frame recomposition or layout thrash?
- Search: is the query bounded/debounced? Do `observeSummary()` / `observeParts()` leak, re-subscribe per recomposition, or run work on the main thread?
- Reader/Review: any new main-thread database or layout work? Any regression against the pre-pass baseline in the touched files?
- State your measurement method (Compose trace, `dumpsys gfxinfo`, profiler) or mark **NOT MEASURED**. Do not imply a benchmark you did not run.

**3. UI**

- Does the shared header hold a stable baseline at font scale 1.0 and 1.4 with no clipping or overlap? Verify on the TCL device where possible.
- Does the compact search field replace the oversized outlined field consistently on Reader and Highlights, with a reachable clear action and a correct editable accessibility label?
- Do the four reading actions render as one coherent ≥48dp row (icon above a 12sp label) and wrap cleanly above font scale 1.3?
- Is Review visually primary within Highlights, and are the card typography, 3dp colour marker, Important badge and completion states coherent?
- Contrast, ≥48dp touch targets, focus order, colour-only signalling, overlap and truncation.

**4. UX**

- Inspection stays separate from deliberate Review; inspection must never advance a cycle. Confirm from code and, if possible, device.
- Review entry states (`Revisit your saved passages.` / `N remaining in this round` / `Revisiting Important highlights` / `Round complete.` / save-first) correct and non-pressuring?
- Source round-trip preserves the pending cycle, and Back from Review returns to Highlights with position intact?
- Does fullscreen focus mode remove distractions without hiding controls the reader still needs, and is exit discoverable?
- Search with the keyboard open, quoted phrases, `#` label autocomplete, and constraints beyond the first 50 matches.
- Any copy that overclaims (offline availability, streaks, ranking) versus `docs/COPY_DECK.md`.

## Guardrails

- Local only: no push, merge or release. Do not modify or clear the normal `com.reader.app`; use `com.reader.app.qa` for any device work.
- Preserve Room v13, canonical article text, quote anchors, the native selectable TextView and its 8dp inset, existing preferences, owner data, highlights, Review history, keys and pairings.
- Do not repeat the completed 100-article intake, generate scale data, or run a 120-minute / soak / exhaustive-matrix campaign.
- `adb` and Gradle require escalated permissions on this host. The isolated QA browser/controller is stopped — do not assume old process IDs or credentials are live.
- If you propose or make a fix, keep it the smallest complete change, run only the affected focused tests, and leave the installed candidate's hash and owner-integrity evidence intact.

## Output

Findings first, most severe first. For each: severity (P0–P3), a one-line title, `file:line`, why it matters, and the smallest concrete fix. Then a short per-axis summary (Review / Performance / UI / UX) using **PASS**, **FAIL**, **NOT MEASURED** or **BLOCKED: exact reason**. End with residual risk and what you did not check. Do not restate the handover as if it were your own verification.
