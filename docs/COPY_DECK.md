# Reader copy and behavior contract

Updated 2026-09-10 for the user-approved focused implementation. This replaces the previous P0 deck, milestone messaging and gesture coaching. UI copy and its assertions change together.

## Voice

Use clear, quiet language. Describe the stored outcome accurately and give a useful next action. Never claim an article is available offline before its text is stored. No streak pressure, automatic tutorial labels or unsolicited celebrations.

## Main surfaces

| Surface | Copy | Behavioral contract |
|---|---|---|
| Bottom navigation | Shelf · Highlights · Settings | Hidden in the reader, quote detail and Review. Re-selecting preserves position. |
| Shelves | Inbox · Priority · Later · Archive | All visible, wrapping at enlarged text; counts outside the tabs. |
| Resume | Continue reading | Actual reading activity, unfinished and non-archived; hidden while searching, filtering or selecting. |
| First save | Add your first article | Opens saving choices; sample reading is optional. |
| Save sheet | Paste a link or text · Import a file · Connect Chrome | Android sharing also works without pairing. |
| Capture | Link saved — fetching article | Link accepted, extraction unfinished. Ready feedback follows stored text. |
| Link fallback | Link only | Does not imply offline article text. Retry uses the existing capture request. |
| Article state | Unread · At end · Finished | Finished depends on explicit completion, never a scroll percentage alone. |
| Reading | Back · Appearance · Reading tools · Highlight · Contents | Native selectable text and system selection actions remain available. |
| Appearance | Text size · Spacing · Background · Font, margins & bold | Bottom sheet; advanced controls scroll; changes apply immediately and retain the passage. |
| Theme | Follow app theme | Inherits the app theme, which can itself follow the system. |
| Navigation | Find in article · Return to reading position | Offline across stored parts; inspection does not overwrite the original resume location. |
| End | Finish & archive · Back to shelf | Reaching the end does not choose either action automatically. |
| Finish feedback | Finished and archived · Undo | Article remains open; Undo restores prior list/completion and new credit, unless a later deliberate change supersedes it. |
| Highlight | Important · Copy · Share · Remove | Compact sheet. Single removal is immediate with Undo; multiple removals are confirmed. |
| Quote inspection | Read full highlight · Open source at this passage | Inspection does not advance Review scheduling. Missing sources retain quote and attribution. |
| Highlights | Newest · Important · Shuffle · Review | Newest default; local quote/source-title search; preview at most six lines. |
| Search | All saved articles · Current shelf | Location separate from fields; all saved includes Archive. |
| Search fields | Title and text · Titles only | Literal ordinary terms combine with AND; quoted phrases stay contiguous. Same local SQLite search on all devices; no FTS ranking or stemming claims. |
| Search feedback | Searching saved articles… · Check your search. | Distinguish pending, invalid, no matches and failed results. The active search does not depend on FTS indexing. |
| Filters | Sort · Filter · Clear filters | Visible removable constraints; labels combine with AND; Unlabeled excludes selected labels. |
| Labels | New label · Create label · Edit label · Save name · Merge labels? | Stable identities survive rename; collision merge explicit; unused labels retained. Deleting a label never deletes articles. |
| Settings | Reading appearance · Labels · Connected devices · Export · About Reader · Advanced | Technical diagnostics under Advanced; disconnect confirmed. |
| Export | Export articles & highlights | ZIP of articles/highlights; not a full backup of settings, keys or pairings. |
| Statistics | estimated reading minutes | Estimates from article length, not measured reading duration. |

## Verification ownership

- `UiCompletionInstrumentedTest`: the small current UI contract for appearance/navigation, quote inspection and reversible removal. Supersedes its historical extensive campaign.
- `ReaderUxFoundationsInstrumentedTest`: three bounded checks for explicit completion and guarded Undo, combined search/labels, and full quote metadata restoration.
- `SearchTermsTest`: phrases, ordinary terms and invalid input.
- `evidence/focused-20260910/walkthrough.jsonl`: actual TCL control actions and observed labels from the short walkthrough. Intake results are separate in `intake-final.json`.

Not every copy string has an automated assertion. Device observations and tests are reported separately in the handoff. Sustained comfort belongs to the seven-day user trial.
