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
- Added a readable pairing-page title and viewport metadata. Confirmed the actual `Pair Reader` tab title after reloading packaged v4 through Chrome's extension details and opening pairing from settings; cancelled the disposable session afterward.

Most corrected-flow checks used `chrome-testing-v3.zip`, SHA-256 `a9e2af13a6d57a3041ad9f2e772a8bb6c8af0e9748d67b5e9e86a2301dbac636`. Earlier permission/theme checks used the preceding package. These are interim checkpoints, not frozen-candidate acceptance.

Live provider routes, selection priority and preview flows, system-theme switching, paired delivery/recovery controls, final dependency checks and exact-candidate reliability campaigns remain pending.

Packaged v4 SHA-256: `b668fd3e6299b26a2267db5d77b70aacbb51b3ef26b296b59f41f9c21e575c78`. A fresh fixture-page Select All followed by the keyboard shortcut correctly took the selection route, but its Markdown export included `Reader` from the inline button mounted during first capture. The source now snapshots selection before content-script installation and reads selection before displaying feedback.

The correction **passes** the same real-browser procedure in packaged v5, SHA-256 `aee880c7830cbfc2df0b21ff6bba0078431eb49f0e75eafb66e92a98da3665f6`. The selection was read before the shortcut; the downloaded Markdown matched it exactly with a terminal newline (327 selected UTF-8 bytes, 328 export bytes; `chrome-selection-v5-check.json`). The two new runtime regression tests pass, covering pre-install snapshots and a one-character selection before feedback. The existing 132-test suite, typecheck and isolated build pass. An initial sandboxed suite attempt could not open its local relay listener; the rerun with local networking passed.

Generic uncertain-preview checks **pass** on the local fixture route: preview text is shown, Save receives keyboard focus, and dismissing the preview leaves the queue unchanged. In v5, confirming the preview saved content but reused the terminal extraction-warning feedback identity, suppressing subsequent status. A new identity for the explicit confirmation fixes this. Packaged v6 (`8c195f881691f242be21b3217066245cb4284dde158b27e7acb9a3319cc0313c`) shows `Saved — connect your phone` after keyboard confirmation; the retained item appears in settings, and its 313-byte export exactly matches the complete preview (`chrome-preview-v6-check.json`). This is local generic extraction, not a live provider acceptance test. Typecheck, build, and focused feedback/selection tests pass.

The current npm advisory audit reports zero known vulnerabilities across production and development dependencies (`chrome-dependency-audit-current.json`). A fresh build into a separate empty output directory followed by deterministic packaging produces a byte-identical v6 ZIP (`chrome-rebuild-v6.log`, `chrome-rebuild-v6-package.log`, successful `cmp`). This does not replace final candidate end-to-end acceptance or establish an absence of undiscovered vulnerabilities.

## Final candidate

The final beta package adds explicit awaiting-device Retry manifest refresh (`19e8394`). The new test failed before the change and five affected publisher tests passed after it. The packaged native Retry action produced exactly one fresh Android ACK and authenticated delivered status without another stored copy. ZIP, unpacked and loaded bytes match; see ARTIFACTS.json and BETA_TEST_REPORT.md. Full passing capture/UI suites were not repeated.
