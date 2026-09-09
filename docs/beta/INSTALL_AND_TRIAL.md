# Build, install, and try Reader

Current version: **0.9.0-beta.1**, including the September 9 UI, import and accessibility completion. Chrome → Android is the implemented product path. There is no store release or public binary download attached to this source checkpoint.

## Build and install

Follow the [README build instructions](../../README.md#try-reader). Chrome builds into `chrome/dist`; the ordinary Android debug APK builds into `android/app/build/outputs/apk/debug/app-debug.apk`.

For Chrome, open `chrome://extensions`, enable Developer mode, and Load unpacked from `chrome/dist`. When updating an existing installation, retain its folder and browser profile, update its files, and Reload the extension to preserve its local identity and captures.

Install Android with `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` from the repository root, or open the APK on the phone. It is debug-signed. Do not uninstall an existing Reader to bypass a signature mismatch: uninstalling removes local articles and keys. QA/test packages are not needed for ordinary reading. Downgrading over the current database is unsupported.

The app declares Android API 26+ and the extension declares Chrome 120+. Current device acceptance is scoped to the documented TCL Android 16 runs; declarations do not constitute qualification of all platform versions. Listen uses the system-default Android text-to-speech engine and its configured voice; that engine must be installed and working.

## Connect and capture

1. Open the extension's Settings and choose **Pair a device**.
2. In Android, open **+ → Pair Chrome**. Scan the request or use the manual paste route.
3. Review the device fingerprint and relays, then confirm connection. Allow the authenticated handshake to finish.
4. Select text on a permitted browser page and use the Reader toolbar, context menu, or **Alt+Shift+R**. Without a selection, Reader attempts article extraction. Uncertain extraction asks for preview confirmation.
5. Open Reader on Android and let it catch up. Optional provider capture buttons require site permission; see the [source support matrix](SOURCE_SUPPORT_MATRIX.md).

Saved locally, relay acceptance, and phone delivery are separate states. A phone receipt confirms durable storage. Background arrival depends on Android scheduling, connectivity and relay availability; reopening Reader requests catch-up. Pending captures support Retry and Export text.

## Read and keep useful passages

Use Inbox, Priority and Later for active articles. Appearance controls the font, size, margins and background. Listen and Speed offer speech and word-by-word reading.

Enable Highlight and select text with the native handles. Saving and recoloring are silent. The compact dock’s **Reading tools** menu offers Listen and Speed; the article’s top overflow menu offers **Undo highlight change**. With Highlight off, ordinary native Copy and Share remain available.

Highlights offers Shuffle, Newest and Review. Review supports importance, next-card navigation, source opening where the source still exists, and quote sharing. Saved highlights survive deleting their source.

## Archive and delete

The archive icon beside **+** opens a separate Archive destination. Back returns to the previous main view. Article menus and swipes provide move actions.

In Archive, right swipe returns an article to Inbox with Undo. Left swipe past 60% and release permanently deletes the article **without confirmation or Undo**. This works in the list and in an open archived article. A partial swipe cancels; menu alternatives are available. Deletion removes the local source, not saved quotes, previous exports or remote relay copies.

## Evidence and artifacts

[UI_UX_COMPLETION_REPORT.md](../../UI_UX_COMPLETION_REPORT.md) records the current installed build and exact verification boundaries. [CONTINUE.md](../../CONTINUE.md) is the current handover; [docs/README.md](../README.md) indexes earlier evidence. [ARTIFACTS.json](../../ARTIFACTS.json) retains local build hashes and historical candidate entries. These paths are not downloadable GitHub assets: generated packages are excluded from Git.

Read [KNOWN_LIMITATIONS.md](../../KNOWN_LIMITATIONS.md) for unmeasured provider, reliability, accessibility and platform conditions. Exports are readable Markdown/JSONL, not restorable backups; pairing keys are excluded.
