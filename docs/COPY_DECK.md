# Copy deck P0 — the most-seen strings

Frozen 2026-09-10. This is the voice contract for Reader's highest-frequency
strings. Every string here is asserted somewhere (QA instrumented tests read
exact `text`/`contentDescription` values), so changing any line requires
updating the asserting test **in the same commit** (see
`UiCompletionInstrumentedTest.kt`) and refreshing this table.

## Tone rules

1. Quiet, honest, warm. No exclamation marks. No emoji.
2. Empty-state titles carry no trailing period; sentences do.
3. Never blame the user. State what happened, what survived, and the one next
   step. "Try again" only when retrying is the action.
4. Errors say what happened in human words, never developer codes
   (`fetch_failed`), never a bare status word (`Connected`).
5. The user owns their data; copy says so ("It stays on this device.").

## The fifteen strings

| # | String (verbatim) | Surface | Pinned by |
|---|---|---|---|
| 1 | `Saved. Find it in Inbox.` | share-sheet save (real package) | — (toast) |
| 2 | `Ready to read.` | capture completed | — (toast) |
| 3 | `That link didn't open, so we kept the text.` | broken-link fallback | — (toast) |
| 4 | `Link saved — fetching article` | URL capture started | — |
| 5 | `Added to Reader` | paste/import saved | — |
| 6 | `Moved to Priority` / `Saved for later` / `Moved to Inbox` / `Archived` | swipe + batch moves, with `Undo` | `UiCompletionInstrumentedTest` ("Moved to Priority", "Saved for later", "Undo") |
| 7 | `Finished "{title}" · {N} min` | finish notice, `View Archive` action | — (snackbar) |
| 8 | `Highlight saved` / `Undo` | highlight write | `ReaderFlowInstrumentedTest` / `UiCompletion` |
| 9 | `"Your quiet shelf awaits"` / `"Inbox zero. Nice."` / `"Nothing prioritized."` / `"Nothing saved for later."` / `"No archived articles."` | empty states (titles, no trailing period; last one test-pinned `:259`) | `UiCompletionInstrumentedTest.kt:259` |
| 10 | `Delete forever?` / `"{title}" will be permanently deleted. Highlights you saved stay in Highlights. This cannot be undone.` | delete dialog | `UiCompletionInstrumentedTest` ("Remove highlight?" family nearby) |
| 11 | `Swipe right to unarchive. Swiping far left starts a delete — you'll always confirm.` | one-time Archive coach line (W1.4) | — (new) |
| 12 | `Import, paste, or pair` | Add sheet a11y label | `UiCompletionInstrumentedTest:240` |
| 13 | `Search library` / `Close search` | search a11y labels | `UiCompletionInstrumentedTest` |
| 14 | `Connected. Private channel to Chrome, established.` | pairing success (W5.3 supersedes bare `Connected`) | — (toast) |
| 15 | `Quiet milestone — {N} articles finished. Your shelf is working.` (10/50/100 variants; 100: `Remarkable.`) | milestones | — (snackbar) |

## Accepted deviations from the original goal doc

- `Add a first piece` (button) retained instead of `Save your first article…`
  — the existing empty-state prose was judged warmer and is test-pinned.
- Welcome seed title is `Welcome to Reader` (derived from the first heading),
  not the longer planned title.
- Age bands render as `5m ago`, `3h ago`, `3d ago` (not bare `Nm`/`Nh`/`Nd`).
- Footer renders `Part 2 / 5` with spaces; share attribution puts the em-dash
  attribution on its own line (`"quote"\n— Title (url)`).

## Naming

- The product is **Reader**. The home is the **shelf** (Library, Inbox,
  Priority, Later, Archive, Highlights). "Library" may name the whole space;
  never call it a list, feed, or manager.
- Labels are topics, not folders (existing `LabelsDialog` philosophy line).
