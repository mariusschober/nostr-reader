# START HERE — continuation prompt for the next agent

Copy-paste this whole file as the first message to the next agent. It is
self-contained; everything else it points to lives in this repo.

---

## Your mission

You are continuing the execution of "The Reader's Hours" plan in this repo
(`/Users/schober/Projects/Nostr Reader`, branch `codex/reliability-finalize`):
making Reader the world's best read-later app for a human who reads 1–2 hours
here every day. Every change must make those hours calmer, warmer, or faster —
never noisier. Offline, private, no account, nothing leaves the device.

Batches A, B, C (workstreams W0–W3) are DONE and committed. Your work is
**Batch D → Batch E → Batch F**, in that strict order, then stop for the user.

## Step 1 — Read these, in this order (mandatory)

1. `CONTINUE.md` — the living handover: current state, commits, gates,
   conventions, the full remaining task lists (Batch D/E/F), open issues,
   and carried-forward boundaries. **This is your primary source of truth.**
2. `docs/COPY_DECK.md` — the frozen copy contract; changing any UI string
   requires updating it and the asserting test in the same commit.
3. `KNOWN_LIMITATIONS.md` (at least line 29 area) — the behavioral contract
   clauses you must keep truthful, plus device-evidence conventions.
4. `docs/COPY_DECK.md`'s "Accepted deviations" section — decisions already
   made; do not relitigate them.
5. Skim, for Batch D specifically: `android/app/src/main/java/com/reader/app/ui/screens/InboxScreen.kt`
   (filter bar ~:282-333, search UI ~:940-1050, `applySortFilter`, `FilterEmpty`),
   `android/app/src/main/java/com/reader/app/data/Labels.kt` (DAO:
   `rename`/`mergeInto`/`deleteLabel` exist unused),
   `android/app/src/main/java/com/reader/app/core/SearchQuery.kt`,
   `android/app/src/main/java/com/reader/app/prefs/Prefs.kt`.
6. Test conventions: `android/app/src/androidTest/java/com/reader/app/UiCompletionInstrumentedTest.kt`
   (this file IS the frozen QA contract — `node(label)` matches exact
   text/contentDescription) and `android/app/src/test/java/com/reader/app/ReaderCoreTest.kt`
   (pure unit style, golden vectors).

## Step 2 — Verify the world before touching anything

```sh
git status                      # must be clean; branch codex/reliability-finalize, ahead ~24
git log --oneline -8            # expect head 0598c1a (handover docs) on top of 23424ae (Batch C)
cd android && ./gradlew --no-daemon test lint assembleDebug assembleDebugAndroidTest verify16KbAlignment
```
Expect: 222/222 unit tests, lint green, both APKs, 16 KiB alignment. If
anything differs, stop and report instead of improvising.

Device (if attached): `adb devices` → TCL T807D is serial `ZXKRS4VKGQ8PWGEQ`.
Keep it awake: `adb -s ZXKRS4VKGQ8PWGEQ shell svc power stayon usb`.

## Step 3 — Execute Batch D (W4: one coherent organization system)

Tasks (details + file:line evidence in `CONTINUE.md` "Remaining work"):
1. Kill the silent filter leak: show active label/age tokens above search
   results with a count line; no-results copy that names the constraints.
2. `#tag` in the search box routes to label filtering (autocomplete from
   `labelCounts`); "Search needs letters or numbers" fallback for degenerate
   queries; preserve user quotes as FTS phrases.
3. Label manager screen (Settings → "Labels"): list + counts, rename confirm,
   merge-into picker, delete confirm — DAO methods already exist in
   `data/Labels.kt`, zero UI today.
4. Multi-select label chips, AND-combined (`selectedLabelNorm` → `Set<String>`,
   intersect in `applySortFilter`).
5. Persist label + search scope in DataStore next to SORT/AGE; add an
   "Unlabeled" pseudo-chip.
6. Discoverability: seed "getting-started" label on the welcome doc, one
   teaching line in `WELCOME_MARKDOWN`, ghost chip "Add your first label"
   when `labelCounts.isEmpty()` and library ≥ 10.

Then Batch E (warmth: day-count line, pairing moment, toast/snackbar
unification + error-code copy mapping, motion on state change, "Start here"
welcome row, empty-state art, milestone re-fire check, first-label nudge),
then Batch F (screenshot set via the `qaUiCapture` harness, full gates,
`KNOWN_LIMITATIONS.md` dated bullets, stale-count notes, final traceability
commit body). Full task text: `CONTINUE.md`.

## Rules that bind you (all from the handover)

- **Copy change = contract change**: grep the string in `android/app/src/androidTest`
  and update the asserting test **in the same commit**; update `docs/COPY_DECK.md`.
- **Host gate after every task**; device gate per batch (QA pair via
  `-PreaderQa=true`; several tests refuse to run on the normal package).
- **Prove flaky ≠ regression**: rebuild the suspected-good commit in a
  `/tmp/reader-pristine` worktree and run the failing test there; identical
  failure = documented environmental flake (scroll-restore/batch-notice tests
  on the TCL), different = your regression, fix it.
- Room stays v13; no schema changes in D/E/F (highlight notes would need v14
  and are explicitly deferred).
- Commit style: `feat(android): …` with evidence in the body, one commit per
  batch, docs bullet in `KNOWN_LIMITATIONS.md` after each device run.
- **Nothing pushed, merged, or released without the user's word.** The work
  stays local on `codex/reliability-finalize`.
- Preserve the boundaries section at the bottom of `CONTINUE.md` verbatim
  (owner data, keys, paired profile, ADB serial always specified).

## Open issues to respect (do not regress, do not silently "fix")

- TCL load-flakiness on scroll-restore/batch-notice tests: pre-existing,
  proven identical on pristine HEAD, pass in isolation.
- Archive tab may sit beyond the tab-row viewport on narrow screens; tests
  must use the `tapTab()` helper (drags inside the tab row's own bounds).
- `DocumentSummary.highlightCount` refreshes on documents-table changes only.
- Relative image URLs hit the "web links only" snackbar (no base-URL
  resolution at projection time) — future item, not Batch D/E/F.
- `stash@{0}` holds unowned pre-plan WIP (welcome-seed refactor) — leave
  unless the user says otherwise.

## When you're done (or the user pauses you)

Update `CONTINUE.md` to the new state (committed gates, new commits, what
remains), commit it, leave the tree clean, and hand back a short summary:
commits, gates, evidence, and anything unresolved.
