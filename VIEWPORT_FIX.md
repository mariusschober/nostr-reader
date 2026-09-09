> Historical checkpoint: results and instructions below apply to their recorded source and date. Start with [current status](CONTINUE.md) for the installed build and remaining limits.

# Reading viewport fix — 8 September 2026

The native article TextView retained a 96dp bottom inset intended to leave room for controls. The Compose Scaffold already reserves the reading dock separately, so this inset blanked out an additional strip of the text viewport. Reduced it to 8dp, recovering 88dp (approximately 250 pixels on the TCL) while keeping the controls outside the text.

Verification: debug build passed; the live reported article was inspected on the TCL in normal and Highlight modes. Text now fills the viewport down to the controls. The existing production-view selection instrumentation passed word selection, cross-paragraph handle dragging, hidden Highlight action menu and edge autoscrolling.

The initial selection run did not scroll: its finger target was only 24dp beyond the view, which no longer carried the visible handle across the tightly padded text edge. The test now drags 56dp into its existing 64dp reserved dock region to account for Android's finger-to-handle offset. No custom selection or scroll engine was added.

The installed normal APK hash matches `latestViewportFix` in ARTIFACTS.json. Temporary instrumentation was removed. Before/after screenshots and logs remain local under `artifacts/ui-refresh/viewport/`; the screenshots show the user's current article and are not published. No full regression campaign was repeated. Earlier README screenshots document the preceding build.
