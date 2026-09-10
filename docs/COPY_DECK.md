# Reader copy and behavior contract

Updated 2026-09-10 for the user-approved focused implementation, then again after the shared-header, compact-search and prominent-Review pass. This replaces the previous P0 deck, milestone messaging and gesture coaching. UI copy and its assertions change together.

## Voice

Use clear, quiet language. Describe the stored outcome accurately and give a useful next action. Never claim an article is available offline before its text is stored. No streak pressure, automatic tutorial labels or unsolicited celebrations.

## Main surfaces

| Surface | Copy | Behavioral contract |
|---|---|---|
| Bottom navigation | Shelf · Highlights · Settings | Hidden in the reader, quote detail and Review. Re-selecting preserves position. |
| Destination header | Shelf · Highlights · Settings · Review | One shared row: 52dp minimum height, 20dp leading inset, common headline typography and vertical alignment. The row reserves its height with no trailing actions, so the title baseline never moves between destinations. Height grows with the system text scale instead of clipping. Shelf's Search and Add stay at least 48 × 48dp. |
| Shelves | Inbox · Priority · Later · Archive | All visible, wrapping at enlarged text; counts outside the tabs. |
| Shelf rows | Title · source · remaining time · Done pill | The title stays dominant: the per-source marker is a short 22dp accent paired with the source name in the meta line, and in-progress progress is a thin muted stroke. Unread, in-progress, At end, Finished and Link only are distinguished by text, never by colour alone. |
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
| Highlights | Newest · Important · Shuffle | Newest default; local quote/source-title search; preview at most six lines. Shuffle shows its own selected state, so it never reads as an unselected action. Changing order never advances Review. |
| Review entry | Review highlights · Start review · Continue review · Review again | Primary action on unfiltered Highlights, directly below the title and before search. One read-only state line: Revisit your saved passages. / N remaining in this round / Revisiting Important highlights / Round complete. / Save a passage while reading to start a review. The card reads persisted state only — it never starts, advances or repairs a cycle just to render. It condenses to a compact action while a search is active or Important is filtered. |
| Review | Review · N remaining in this round · Source · Important · Share · Next | Compact Back + Review header; bottom navigation hidden. Comfortable reading measure on a neutral surface with the saved colour as a 3dp marker. Progress counts the current phase plus its queue, so no fixed "X of Y" is invented while Important bonus items may follow. Exiting halfway resumes later. |
| Review complete | Review complete · Back to highlights · Review again | Reaching the end of the round says Review complete with the primary Back to highlights and secondary Review again. No congratulatory or streak filler. |
| Search | All saved articles · Current shelf | Location separate from fields; all saved includes Archive. Shown as one compact context row, e.g. All saved articles · Title and text, plus Filter. |
| Search field | Search articles · Search highlights | One quiet 48dp surface shared by Shelf and Highlights: 12dp corners, 20dp decorative icon, 16sp editable text, clear action only when nonempty (Clear article search / Clear highlight search). The field carries its own editable accessibility label. It focuses once per deliberate opening, so returning from an article does not reopen the keyboard. |
| Blank search | Use “quotes” for a phrase or # to choose a label. · Recent searches | Guidance and history show only for an unconstrained blank query. History rows are left-aligned with a history icon, the query and a separate restrained Remove target. |
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
- `evidence/focused-20260910/hdr-*.png`, `ux-shelf-search.png`, `ux-review*.png`, `font14-*.png`: actual QA-package screenshots for the shared header geometry, the compact search surface, the prominent Review entry and its completion state, and the enlarged-text pass.
- `ReaderFlowInstrumentedTest` (gated by `qaReaderUi`): its completion-copy expectation follows the new **Review complete** wording. It is not part of the focused run; only the copy string changed.

Not every copy string has an automated assertion. Device observations and tests are reported separately in the handoff. Sustained comfort belongs to the seven-day user trial.
