# Reader focused implementation — 2026-09-10

User-approved scope supersedes the old D/E/F order and extensive QA in START_HERE/CONTINUE.
Baseline: 15a77ff, clean codex/reliability-finalize. Work stays local; preserve owner data and pairing.

- [x] Foundations: preference fixes, completion receipt/undo, constrained paged search
- [x] Shelf: bottom destinations, four visible shelves, continue, filters, selection
- [x] Reader: compact appearance, contents/find/return, safe focus, links/footnotes
- [x] Highlights: compact actions, six-line previews, detail/search/important, undo
- [x] Finish: explicit end card and atomic Finish & archive/Undo
- [x] Labels/settings/save: management, optional sample, human feedback
- [ ] Focused checks: fast host gates, a small device walkthrough
- [x] Intake: actual extension 50 articles + Android user-facing link saves 50 articles
- [x] Inspect five representative articles; no scale/soak/exhaustive campaigns
- [x] Normal debug upgrade on TCL, installed hash and preserved owner data
- [x] Copy deck/tests/docs and installed trial checkpoint; remaining execution in NEXT_AGENT_PLAN.md

No 1,000 articles, 10,000 highlights, 120-minute session, broad lifecycle or performance campaign.
ADB serial: re-identify TCL; expected ZXKRS4VKGQ8PWGEQ. Destructive fixtures use com.reader.app.qa.

## Continuation pass — 2026-09-10 (commit `99c5cf9`)

- [x] Shared `DestinationHeader` for Shelf/Highlights/Settings/Review — identical TCL title bounds
- [x] Shared compact `ReaderSearchField` (48dp, 20dp inset, focus-once, fixed focus crash)
- [x] Review promoted to the primary Highlights action with a read-only state card
- [x] Review typography (Newsreader 22sp), truthful progress, distinct Source action, completion state
- [x] Quieter highlight previews; Shuffle shows a selected state
- [x] Quieter shelf rows — short translucent site marker, muted progress stroke (commit `fd1b813`)
- [x] Native end-of-text re-checked on TCL (card appears, viewport stable, no completion credit)
- [x] Native selection re-checked on TCL (handle drag → one saved range, no duplicates)
- [x] Enlarged-text 1.4 pass on QA, system value restored to 1.0
- [x] Normal `assembleDebug` + in-place install; installed hash `03cdfb15…` (`fd1b813`); owner data preserved

Still open (see NEXT_AGENT_PLAN.md §9): multiword `#` autocomplete, >50 paging UI, all active-constraint
combinations, one expanded table and one captured local footnote when the corpus supplies them, and
overlapping-highlight reachability / multi-remove confirmation.
