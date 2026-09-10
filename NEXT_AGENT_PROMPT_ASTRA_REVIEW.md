# GPT Astra audit — Reader review, performance, UI and UX

You are a rigorous, independent reviewer and auditor for an Android reader app. Perform a **code review** plus a **performance**, **UI** and **UX audit** of one completed local change pass. Report findings before you change anything; you may read, build and use the device to verify claims. Findings first, ordered by severity.

## Checkout and exact revision

- Repo: `/Users/schober/Projects/Nostr Reader`
- Branch: `codex/reliability-finalize` — work stays local. Do not push, merge or release.
- HEAD: `f98aa9f15cb3e7b5e0b383661580f006b141c10e` — handover and install evidence for the pass.
- Implementation commit under review: **`fd1b813`** (`feat(android): quieter shelf markers and progress stroke`), directly on top of **`99c5cf9`** (`feat(android): shared destination headers, compact search, prominent Review`).
- Base chain: `b14f128c54996515f45218f8d912ef106c174446` on `15a77ff647ac39f4e9165e47504d553504a1ac8b`.
- Diff to audit: `git diff 15a77ff..fd1b813 -- android/ docs/` (use `git show` per commit for exact hunks, including `f98aa9f` docs/evidence).
- Installed candidate: normal `com.reader.app`, debug `0.9.0-beta.1` / code 2, APK SHA-256 `03cdfb15714568f9d63b51ad6fba94806728978bd6d4b502e3bb36d82d4b7916`, on TCL T807D / Android 16 / serial `ZXKRS4VKGQ8PWGEQ`. QA package is `com.reader.app.qa`.

## Read first

- `FOCUSED_HANDOFF_20260910.md` — implemented scope, evidence, state/code map, trial boundaries.
- `NEXT_AGENT_PLAN.md` — the plan this pass executed (work packages A–E and §9 remaining checks).
- `docs/COPY_DECK.md` — the copy/behavior contract that changed with this pass.
- `evidence/focused-20260910/installation.json` — installed build and hashes.
- `evidence/focused-20260910/final-verification-20260910b.txt` — host/device checks actually run.
- `evidence/focused-20260910/*.png` and `walkthrough.jsonl` — real TCL observations (not mockups).

## What changed (audit targets)

- `ui/DestinationHeader.kt` — new shared 52dp header row for Shelf / Highlights / Settings / Review.
- `ui/ReaderSearchField.kt` — new compact 48dp search surface.
- `data/HighlightRepository.kt`, `data/Highlights.kt` — read-only `observeSummary()` / `ReviewDao.observeParts()`.
- `ui/screens/HighlightsFeed.kt` — Review entry card plus read-only state line.
- `ui/screens/ReviewScreen.kt` — compact header, truthful phase progress, Newsreader measure, Source action, completion state.
- `ui/screens/InboxScreen.kt`, `SettingsScreen.kt`, `ui/MainActivity.kt` — wiring, one-time focus, Back-closes-search.
- `ReaderFlowInstrumentedTest.kt` — updated completion-copy expectation.

## Audit axes and required questions

**1. Correctness / code review**

- Does `DestinationHeader` actually guarantee identical title geometry across destinations, or can content/actions still shift the baseline or reserve height differently?
- Is the Review summary genuinely read-only (never starts, advances or repairs a cycle just to render)? Look for any write path, side effect, race or re-subscription.
- Is the search field's one-time focus and Back-closes-search behavior correct across config change, process death, returning from an article, and empty → typed → cleared?
- Is the phase progress count correct in every phase, including when Important bonus items follow the current queue?
- Any regression in the gated `ReaderFlowInstrumentedTest` completion-copy contract?
- Do the claims in `FOCUSED_HANDOFF_20260910.md` and `docs/COPY_DECK.md` match the actual code?

**2. Performance**

- Compose recomposition/measure cost of the shared header, Review entry card and search field; unstable lambdas/keys, allocations during item composition, whole-list recomposition on scroll?
- Search: is the query bounded/debounced? Do `observeSummary()` / `observeParts()` leak, re-subscribe per recomposition, or run work on the main thread?
- Reader/Review: any new main-thread database or layout work? Any regression against the pre-pass baseline in the touched files?
- State your measurement method (Compose trace, `dumpsys gfxinfo`, profiler) or mark **NOT MEASURED**. Do not imply a benchmark you did not run.

**3. UI**

- Does the shared header hold a stable baseline at font scale 1.0 and 1.4 with no clipping or overlap? Verify on the TCL device where possible.
- Does the compact search field replace the oversized outlined field consistently on Shelf and Highlights, with a reachable clear action and a correct editable accessibility label?
- Is Review visually primary within Highlights, and are its typography, 3dp colour marker and completion states coherent?
- Contrast, ≥48dp touch targets, focus order, colour-only signalling, overlap and truncation.

**4. UX**

- Inspection stays separate from deliberate Review; inspection must never advance a cycle. Confirm from code and, if possible, device.
- Review entry states (`Revisit your saved passages.` / `N remaining in this round` / `Revisiting Important highlights` / `Round complete.` / save-first) correct and non-pressuring?
- Source round-trip preserves the pending cycle, and Back from Review returns to Highlights with position intact?
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
