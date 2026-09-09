# Reader UI completion — 9 September 2026

Completed and installed on TCL T807D, Android 16/API 36. Source: `ca06d1d3ca4005550f1b8cc7a6922c0b34f93ee0`, branch `codex/reliability-finalize`. The completed source is on `codex/reliability-finalize`; the subsequent September 9 documentation/push update is described in [current status](CONTINUE.md). No main merge or binary release is implied.

## Delivered scope

- Move notices explicitly expire after the standard long duration (normally about ten seconds), offer dismiss and Undo, and replace older notices. A grouped move commits atomically and has one grouped Undo; failed writes do not announce success.
- Cancelled article/highlight drags perform no action. Archive body taps work during multi-selection. Selection drops deleted or moved-away items.
- Library and Highlights positions survive source/settings/review round trips and recreation. Review preserves the current quote's scroll position and resets when advancing.
- Active highlighting uses one row: pen off, four 48 dp color targets, Reading tools menu. Listen/Speed remain available there. The normal inactive dock retains its existing actions.
- Saved quotation text uses Asul in the Highlights feed and Review. Shuffle changes its seed on every request and avoids repeating the same order when there are multiple quotes. Review follows the finger, tilts/fades, returns on cancellation, and animates offscreen before advancing exactly once. Next uses the same transition; right swipe retains importance behavior.
- Appearance, editing, review, filters and selection controls remain reachable through wrapping or scrolling at enlarged text/display sizes. The Black appearance now explicitly colors its More icon and swipe hint. Duplicate-title suppression handles a matching first heading at any level without changing canonical text.
- Native selection retains its single TextView and handle ownership. Post-release momentum is added to that same viewport and stops on touch, selection, pen mode, cancellation or navigation.
- Pasted/shared Markdown is detected from parsed structure, including compact tables and inline formatting. HTML import preserves headings, paragraphs, links, emphasis, code, lists and ordinary tables. File import uses provider display names/MIME types instead of assuming the URI contains an extension. Native table spans align columns and draw rules without inserting padding into selectable text. Wide tables can be inspected at reading size in a horizontally scrollable view.

## Verification

- **PASS:** 137 Android debug and 137 release unit tests, zero failures/errors/skips; lint and debug/release builds. Evidence: `artifacts/ui-completion/final-host.log` and Android test-result XML files.
- **PASS:** Chrome 140 tests, typecheck, build and packaging. No Chrome source changed during this UI work; this does not replace the earlier real-browser qualification.
- **PASS:** TCL core regression run on source `19c8937`: 35 reported tests, 34 executed and one TalkBack assumption skipped. Covers UI moves/Undo, cancellation, scroll restoration, review, exact Unicode sharing, native selection/handles/autoscroll, storage and migrations. Evidence: `artifacts/ui-completion/final/core-pair/device.log`.
- **PASS:** Final source/package pair: two portrait/import checks, one strict TalkBack check, and three configuration checks. TalkBack traversal used the actual service and asserted each activated color became checked. The three configurations were landscape at density 456/font 1, portrait at density 540/font 2, and landscape at density 540/font 2. Normal portrait also used density 456/font 1. These correspond to approximately 379 dp and 320 dp portrait widths.
- **PASS:** Manual final-package checks at density 540/font 2: all highlight editing controls visible; physical OS edge-back returned to Inbox with the synthetic article still there. Evidence: `manual-checks.json`, `manual-edit-top.png/xml`, `manual-edge-back.png/xml` in the final artifact directory.
- **PASS:** Installed normal APK hash equals the delivered APK hash. Database/files/preferences hashes were byte-identical immediately before and after the in-place install. Installed database integrity test passed. QA and instrumentation packages removed; only `com.reader.app` remains. Original font, display, rotation and accessibility-service settings verified restored; owner library visually opened successfully.

The core run predates only the final inline-HTML word-boundary fix and its unit test. Changed import behavior, final visual configurations and TalkBack were rerun on the final bytes; the entire core suite was not repeated after that narrow change. The first final portrait/import attempt failed its QA foreground guard; an unchanged-package retry passed both tests. Cause was not established. The failed attempt is retained in `final-device-first-attempt.log`; it is not silently counted as a pass.

## Measured native scrolling

One controlled TCL debug/instrumented sample continued 3,758 px after release (baseline: 0 px), with finger travel about 1,200 px and approximately 1,089 px of in-touch scrolling. Measured velocity was approximately 8,965 px/s. Across 162 captured frames, p95 frame duration was 12.27 ms and maximum 14.38 ms. This establishes post-release momentum in that fixture, not a general jank, battery or other-device benchmark. Evidence: `artifacts/ui-completion/final/core-pair/native-scroll-measurement.json`.

## Artifacts and visual evidence

Final APK: [reader.apk](artifacts/ui-completion/final/reader.apk). SHA-256: `4e8c9c843b2981d03361be39cc2afe3e68dadd51343d2afb7aa9030dba055048`.

[ARTIFACTS.json](ARTIFACTS.json) records final app/test hashes and source boundaries. The local `artifacts/ui-completion/final/manifest.json` inventories evidence with hashes. Generated APKs, screenshots and owner-storage evidence remain local and ignored by Git.

Final synthetic screenshots are under `artifacts/ui-completion/final/device/files/qa/`: portrait and landscape Appearance, reader, compact highlighting, Highlights and Review; enlarged text/display variants; Markdown/HTML tables; expanded table and last-column access. Core-only shuffle/animation/selection evidence remains under `core-pair/device/`. Manual editing and edge-back captures are separate. Screenshots were inspected for reachable controls, readable text, table alignment and compact dock placement. Palette calculation gives a minimum text/check contrast of 7.78:1 in the recorded tested combinations; this is not a blanket contrast audit of every UI element.

## Boundaries

The seven original reference images were not supplied; current synthetic TCL screenshots support functional and visual verification, not a seven-image fidelity comparison. This is a scoped Android UI completion, not certification of all beta/public-relay campaigns, other devices, tablet/foldable layouts, process-death timings or acoustic TalkBack/TTS quality. HTML is imported as semantic reading content, not an executing browser/CSS layout. Complex merged/nested tables are not claimed as browser-identical. A previously imported article whose formatting was already discarded needs reimport from its source; immutable existing article text and quotation anchors are not guessed or rewritten.

## GitHub evidence

Compact sanitized device result logs, configuration records, package hashes and install summary are tracked under [evidence/ui-completion](evidence/ui-completion/). Current synthetic screenshots are in [docs/screenshots](docs/screenshots/README.md). Detailed raw screenshots, owner storage fingerprints and generated APKs stay local and ignored. Local artifact paths above are provenance references, not GitHub downloads.
