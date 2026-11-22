# Build, update and try Reader

Reader is a source-build beta. Follow the [README build steps](../README.md#try-reader), then load `chrome/dist` through Chrome's Developer mode → Load unpacked. Keep the same extension folder and browser profile when updating; reload the extension after rebuilding.

Install the ordinary Android debug APK in place with `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` from the repository root, when the owner authorizes a device update. Do not uninstall or clear an existing Reader to bypass a signature mismatch. It removes local articles and keys. Downgrades over the current Room v13 database are unsupported; see [migration](../MIGRATION_AND_COMPATIBILITY.md).

Android declares API 26+ and Chrome declares 120+; the dated checks in [status](STATUS.md) do not qualify every platform version. Listen explicitly uses Google Text-to-Speech and its configured voice; that engine must be installed and working. A configured voice may require a network connection.

## Pair and capture

1. Open extension Settings and choose Pair a device.
2. Open Android's Add menu and scan or paste the pairing request.
3. Review the fingerprint and relays, confirm connection, and let the authenticated handshake finish.
4. Save selected text using the toolbar, context menu or Alt+Shift+R. With no selection, Reader attempts article extraction and requests confirmation when uncertain.
5. Open Android Reader for catch-up. Optional provider controls require explicit site permission; [provider qualification](SOURCE_SUPPORT.md) is limited.

Saved locally, relay acceptance and phone storage are distinct states. Only a verified phone receipt confirms delivery. Pending captures offer retry/export. Android can also import shared or pasted text, supported files, and article links without pairing. Link-only fallback does not promise offline article text.

## Read and keep passages

Use Inbox, Priority, Later and Archive. Appearance changes the font, size, spacing and background. App theme and Display are separate settings; E-ink & NXTPAPER uses monochrome light/dark. Listen supports service-owned playback with media controls; Speed presents words one at a time.

With Highlight on, hold a word, drag and release to save. The compact dock has current color, focus and Done. Outside highlighting, native Copy/Share selection remains available. The local pen-settlement repair awaits [resulting-build TCL acceptance](STATUS.md#open-defect-and-focused-next-acceptance).

Highlights supports global Review, importance and quote sharing. Article highlights offers a separate source-ordered Review round without replacing the global round. Quotes survive removal of their local source, with honest unavailable-source feedback.

## Move, delete and export

Article move actions offer Undo. Archive is a peer library tab. In Archive, swipe right to return to Inbox; a left swipe past the deletion threshold permanently removes the source **without confirmation or Undo**. Keep saved quotes and previous exports in mind. Partial swipes cancel; menus offer alternatives.

Export creates a readable ZIP with Markdown and metadata, excludes keys, and verifies checksums. It is a one-way export, not a full restore or pairing backup. Read [status and limits](STATUS.md) before relying on an unqualified path.
