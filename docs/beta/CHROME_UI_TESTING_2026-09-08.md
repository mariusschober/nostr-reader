# Packaged Chrome UI testing — 2026-09-08

Interim evidence from a fresh isolated Chrome for Testing 151.0.7922.34 profile. The owner profile and `chrome/dist` were preserved. The ChatGPT-shaped page is a local synthetic fixture behind the controlled relay proxy; these results do not establish live provider compatibility.

## Verified through Chrome UI

- Actual **Load unpacked** selected extracted ZIP contents and Chrome displayed **Extension loaded**, enabled, with its service worker registered.
- Dark theme selected with the keyboard and retained after page reload. Light selection also worked, including the pairing page.
- Denying ChatGPT site permission kept access disabled and displayed a clear message. Allowing it added the inline capture button to a freshly loaded fixture. Revoking it removed the button after reload.
- Toolbar capture and Alt+Shift+R both saved synthetic content while unpaired, with **Saved — connect your phone** feedback. The settings API confirmed durable queued state; no relay acceptance was presented as phone delivery.
- After the settings refresh fix, returning to the already-open settings tab displayed both queued captures without a page reload.
- Retry while unpaired displayed **Connect a device first** beside the retained item.
- Export downloaded 279 bytes of Markdown exactly matching the complete fixture, including Unicode. Hash and comparison are in `chrome-export-check.json`; the body remains in ignored artifacts.
- Cancelling discard preserved both queued captures. Confirming discard of the newer disposable capture left the older one intact.
- Pair a device opened a session waiting for Android's encrypted response against the local test relays. Cancel pairing removed the code and offered a new one. This is not a completed pairing campaign.

## Corrections

- Package verification now resolves relative `READER_DIST` consistently with the build.
- Removed the permanent signing placeholder and placed accurate unconfigured-provenance wording under Advanced.
- Added a direct pairing entry in unpaired settings.
- Settings refresh on focus/visibility return; a version counter prevents an older status reply from replacing a newer one.
- Transfer action errors appear beside their item; general delivery messages remain visible outside Advanced.
- Added a readable pairing-page title and viewport metadata. This final title-only change is awaiting packaged UI confirmation.

Most corrected-flow checks used `chrome-testing-v3.zip`, SHA-256 `a9e2af13a6d57a3041ad9f2e772a8bb6c8af0e9748d67b5e9e86a2301dbac636`. Earlier permission/theme checks used the preceding package. These are interim checkpoints, not frozen-candidate acceptance.

Live provider routes, selection priority and preview flows, system-theme switching, paired delivery/recovery controls, final dependency checks and exact-candidate reliability campaigns remain pending.
