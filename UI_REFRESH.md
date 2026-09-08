# Reader UI refresh — 8 September 2026

Source commit: `58a84ae751e2e75b94824005c1476859a24183db`. Final APK checksum was verified directly against the installed TCL package; only `com.reader.app` remains installed.

Implements the approved Google speech, supplied logo, blue accent, Highlight dock, and article-swipe plan.

## Delivered behavior

- Reader explicitly requests `com.google.android.tts`; missing/disabled Google speech yields an error with a Speech settings action. The phone’s global speech preference remains unchanged. The engine’s configured voice is retained.
- `logo.svg` is the supplied source. Chrome has explicit toolbar icons at 16/32/48/128 px. Android has adaptive, monochrome, and density-specific launcher assets. The deterministic PNG exporter is `scripts/build-reader-icons.py`.
- Brand blue is `#015A97`; dark surfaces use `#70B5E8`. Selected containers use blue, while errors remain red and existing annotations retain their own colors.
- The reader dock contains Listen, a centered highlighter capsule, and Speed. Highlight has a highlighted label, explicit on/off semantics, and its existing color controls above the dock. The speech player disables duplicate Listen activation.
- Later and Archive are also available in the overflow menu. Interior article swipes reveal their action and commit only on release after 30% travel. Moving back below the threshold cancels. Selection, highlighting, vertical movement, links, and system edges are guarded. A one-time dismissible hint explains the directions.
- Successful moves return to the library with Undo restoring the previous list. Reading progress is flushed first; concurrent moves are guarded. Existing same-list actions are disabled.

## Focused verification

| Check | Evidence / result |
| --- | --- |
| Android build | `assembleDebug` passed; no broad test campaign. |
| Chrome build | Vite build and manifest/standalone-content-script package verifier passed. |
| Google speech on TCL | Reader connected to Google; Google synthesis requests and advancing sentence counter observed. Closing player abandoned audio focus. No acoustic quality claim. |
| Light/dark reader | Device screenshots inspected; selected purple defaults found and corrected to blue. Dock and four-color palette fit the TCL. |
| Highlight | Saved a selection, exercised its color action and Undo, and verified the reader stayed open during a swipe with highlighting/selection active. |
| Article moves | Archive and Later moved correctly; a short swipe cancelled. Later Undo restored Archive membership. The initial archive test was subsequently restored to Inbox through Unarchive. |
| Gesture coexistence and reveal | Final device screenshots show vertical scrolling within the article and the centered release icon/label. Interior reveal was cancelled by dragging back. Android edge Back returned to the library. |
| Chrome real UI | Supplied icon visible in extension menu and pinned toolbar in Chrome for Testing 147. One local fixture capture returned “Saved — connect your phone.” This checks capture, not phone delivery. |
| Android icon | Installed TCL app-drawer icon visually inspected: correct mark, colors, label, and no clipping. |
| Contrast | Brand blue / paper 7.02:1; brand blue / soft 6.31:1; dark blue / ink 7.75:1; selected-container text at least 10.94:1. Undo uses the appropriate inverse-surface blue. |

Final packages and SHA-256 values are recorded under `latestUiRefresh` in `ARTIFACTS.json`. Local builds, speech log, and screenshots are in `artifacts/ui-refresh/` and are excluded from Git. Existing beta reports describe the prior checkpoint and are not replaced by this narrow refresh.

The user also exercised the TCL during verification. Their current articles and annotations were left intact. No new test applications were installed, and no user data or pairing keys were cleared.
