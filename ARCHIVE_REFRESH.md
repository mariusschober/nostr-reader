> Historical checkpoint: results and instructions below apply to their recorded source and date. Start with [current status](CONTINUE.md) for the installed build and remaining limits.

# Archive and highlighting refresh — 2026-09-08

Implemented on Android and installed on the TCL T807D (Android 16). Local commit only; not pushed or published.

## Changes

- Archive moved from the main tabs to a dedicated destination. Its icon sits between Add and Settings and uses the same text color as Add, including while Archive is open. Back preserves the previous main destination and list states.
- Archived rows and open archived articles: right swipe unarchives at 30% with Undo; left swipe permanently deletes on release at 60%, with no confirmation or deletion Undo. Partial swipes cancel. Accessible menu alternatives remain available.
- Deletion is restricted to archived articles. It stops matching playback, discards pending progress, removes the local document/content, and invalidates prepared caches after the database operation succeeds. Saved highlights and their review history remain independent of the source. Review shows the retained title and “Source article deleted”; local source opening is disabled while quote sharing remains available.
- Highlight mode hides Android’s floating action menu while keeping native selection. Selection saves after 100 ms and flushes at selection end/navigation. Successful saves and recolors are silent; the existing overflow menu offers Undo highlight change. Errors remain visible.
- Appearance uses explicit Follow system / Paper / Soft / Ink / Black labels and centered, accessible font rows. About includes the GitHub source link and the requested three credit lines.

## Focused verification

PASS: debug build, focused TriageMovesTest and RouteStackTest, and diff whitespace check.

PASS on the physical TCL: HighlightMenuGateInstrumentedTest used the production native article view and verified word selection, handle dragging across paragraphs, edge autoscrolling, and hidden Copy/Share/Select all in Highlight mode. The normal app retained the native Copy / Highlight / Share / Select all menu with Highlight mode off. Successful highlighting showed no persistent saved banner and offered overflow Undo.

PASS on the TCL: ArchiveStorageInstrumentedTest used an isolated in-memory database. It verified removal of archived document/content, pending-progress discard, cache invalidation, exact preservation of highlight/review metadata, and refusal to delete an Inbox article.

PASS in the normal app using two disposable imported articles: Archive navigation; partial left-swipe cancellation in list and reader; right-swipe unarchive and Undo in both; full permanent deletion in both; retained quote/title and deleted-source label in Review. One deliberately retained test quote (“disposable”, source “Reader archive test 20260908-A”) remains as evidence; both test source articles were deleted. Existing user articles were not deleted.

PASS: font alignment and background labels inspected in light and Ink appearances; Follow system restored. About credits inspected; source-link button launched the browser. Final archive icon visually matched Add. Installed APK hash equals the packaged APK. Temporary test package removed; only com.reader.app remains.

## Artifact and limits

APK: `artifacts/ui-refresh/archive/reader-archive-refresh.apk` (debug signing).

SHA-256: `280d470dc4aedd6db2fb6afa4c61760d3bfe4ff7a568a5bb5acd9167b2e50450`

Screenshots, gate JSON and build logs are under `artifacts/ui-refresh/archive/` (ignored local evidence). This was a focused Android check, not a repeated full beta, accessibility, relay, acoustic, or release campaign. Empty Archive was not exercised against user storage. Local article deletion does not erase prior relay copies or exports. Chrome was unchanged.
