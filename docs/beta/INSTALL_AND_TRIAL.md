# Install and try the beta candidate

The normal Android beta is already installed on the connected TCL with existing
data retained. The downloadable APK, test APK and Chrome ZIP are in
`artifacts/beta-0.9.0-beta.1/`; verify hashes with `ARTIFACTS.json`.

For another Android installation, open `reader-0.9.0-beta.1.apk` and allow the
ordinary Android installation prompt. It is debug-signed; do not uninstall an
existing Reader just to bypass a signature mismatch. Uninstalling deletes local
data and keys. The test APK and `reader-qa-*` files are for isolated QA, not normal
reading. Downgrading over the current v8 database is unsupported.

For Chrome, unzip `reader-chrome-0.9.0-beta.1.zip` into a stable folder. In Extensions,
enable Developer mode and choose Load unpacked for that folder. If updating an
existing unpacked installation, retain its original folder and browser profile,
replace the built files there and use Reload. The owner's existing paired profile
was not updated automatically. Chrome for Testing 151.0.7922.34 was exercised;
the minimum supported browser release has not been qualified.

Pair from Reader settings and Android Settings → Connected devices. Capture selected
text with the Reader toolbar action or Alt+Shift+R. Provider buttons require explicit
site access. Uncertain article extraction asks for preview confirmation.

“Saved” confirms local durable capture. Relay acceptance is intermediate. “Delivered”
requires Android's authenticated storage receipt. Use the pending item's Retry or
Export text action if recovery needs attention.

In an article, enable Highlight and select text with the native handles. Adjust the
range, choose a color, or Undo. Open Highlights to review saved quotes. A left swipe
advances; a right swipe toggles importance. Source and Share return to the same
review card. Share sends only the quote. Exports are readable files, not restorable
backups.

This is a candidate for a scoped trial. See `KNOWN_LIMITATIONS.md` for live-provider,
performance, reproducibility and unmeasured reliability/device conditions. Do not
resume repeated campaigns without a new user request.
