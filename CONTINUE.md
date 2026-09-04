# CONTINUE — agent handover for nostr-reader (2026-09-04, work paused mid-debug)

## Vision (why this exists)

A private, zero-server read-later ecosystem. Capture text/markdown anywhere
(Chrome, AI chats, Android share, paste), move it encrypted over ordinary
public Nostr relays (NIP-44 + NIP-59 gift wrap), and read it in a quiet
native app: Inbox → triage → read → archive. No backend, no accounts, no
telemetry. Nostr and Markdown are invisible infrastructure, never UI.
See `README.md`, `PROTOCOL.md`, `SECURITY.md`, `CROSS_PLATFORM.md`, `MAC.md`.

## Where things stand

- **Chrome MV3 extension** (`chrome/`): builds, 17/17 vitest green, packaged
  (`artifacts/reader-chrome-extension.zip`). Extraction (Defuddle→Readability),
  4 AI-provider adapters, outbox + E2E ACK, pairing, NIP-07 bridge.
- **Android app** (`android/`): builds, **42/42 unit tests green**, lint
  0 errors, APK in `artifacts/reader-debug.apk` (debug-signed QA).
- **Interop proven**: `shared/test-vectors/golden-v1.json` passes on
  TS/Kotlin/Swift; `nip44-v1.json` (real nostr-tools ciphertext) is decrypted
  by Kotlin and re-encrypted byte-identical. Live relay publishes accepted by
  `relay.damus.io` and `nos.lol` (2-relay quorum).
- **Four-list triage landed**: Room v2 (`list` column + `MIGRATION_1_2`),
  Inbox/Priority/Later/Archive tabs, per-tab attention time, end-of-article
  "Read Later"/"Archive" (no more "Finished"), system bars follow background,
  paste-with-autodetect in "+" menu, system back on Reader/RSVP/Pairing/Settings.
- **Verified on the physical TCL T807D** (`adb -s ZXKRS4VKGQ8PWGEQ`):
  install, launch, v1→v2 migration (old docs landed in Inbox), 4 tabs,
  swipe-right→Priority with Undo snackbar, Priority tab contents, share-import,
  line-break rendering, TTS bar, RSVP play/pause, themed appearance sheet.
  Screenshots: `artifacts/qa/`.

## OPEN BUG #1 (where work stopped): row taps dead after triage rewrite

- **Symptom**: tapping an article row in any triage list does nothing.
  Tab switches, "+" dialog, row swipes all work. No crash, no log, no navigation.
- **What was ruled out**: not navigation (go()/stack verified by code read);
  not a stale install; not coordinates (uiautomator bounds used, taps land).
- **Prime suspect**: the Material3 `SwipeToDismissBox` container swallowing
  taps (content clickables never fire — instrumented with Log.d, zero output).
  A move of the tap handler onto the box itself also never fired.
- **Current code state**: `SwipeRow` in
  `android/app/src/main/java/com/reader/app/ui/screens/InboxScreen.kt` was
  just rewritten to a hand-rolled `Modifier.draggable` + `clickable`
  implementation, rebuilt green, reinstalled — **NOT yet tap-tested**.
  Debug `Log.d("RowTap", …)` lines are still in `InboxScreen.kt`; remove them
  once taps are confirmed working.
- **First action on resume**: tap a row on the TCL and check logcat for
  `RowTap`. If taps fire, delete the logs and continue below. If not, replace
  `draggable` with a manual `awaitEachGesture` loop or split tap vs swipe
  targets (e.g. tap opens, swipe anywhere moves).

## Other known issues

- Debug `Log.d("ReaderBack", …)` in `ReaderScreen.kt` (system-back probe);
  keep until back-from-article is verified on device, then remove.
- System-back from article/RSVP/pairing/settings is implemented
  (`BackHandler` in each screen) but the article case is unverified for the
  same reason as #1 (couldn't reach it by tap). Back on inbox-root correctly
  exits to launcher (verified).
- Paste dialog, dark-background system bars, Later-tab swipes, Archive
  overflow menu: implemented, not yet exercised on device.
- Debug APK shows Android's 16 KB `.so`-alignment warning naming
  `libdatastore_shared_counter.so` + `libimage_processing_util_jni.so`
  (third-party libs; no NDK code of ours). Harmless on 4 KB devices; release
  track must re-verify on a 16 KB-page device.
- `rust-core/` is audited source only (no cargo toolchain here); TS/Kotlin/
  Swift mirrors are the verified implementations. UniFFI bindings still ahead.
- Chrome `dist/` validated (manifest, CSP, no remote code) but never loaded
  in a real browser; provider buttons covered by fixtures only.
- TTS audio never ear-checked; Amber/nos2x signers never live-tested.

## Next: MUST / SHOULD / COULD

**Must**
1. Fix OPEN BUG #1 (row taps), remove debug logs, reinstall, verify open by
   tap on the TCL.
2. Verify system back Reader→list, RSVP→Reader (cursor preserved),
   Pairing/Settings→list on device.
3. Exercise paste (markdown + plain), Later swipes, Archive menu, dark bars.
4. Update `artifacts/TEST-REPORT.md` (currently covers the pre-triage build:
   17 chrome / 28 android tests; now 17 / 42) and refresh artifact hashes.

**Should**
5. Real Chrome→Android end-to-end (pair, send, receive, ACK clears outbox).
6. Load the extension unpacked in desktop Chrome; test toolbar + AI buttons.
7. RSVP focal-anchor screenshot check across fonts; TTS listen-through.
8. Decide 16 KB story (bump DataStore/Camera deps or document release risk).

**Could**
9. UniFFI bindings for `rust-core`; Mac app build; widget/cover polish;
   export/import roundtrip; tablet layout proof.

## Working notes (learned the hard way)

- **Always target the TCL explicitly**: `adb -s ZXKRS4VKGQ8PWGEQ`. The
  emulator died mid-session once and `adb` silently fell through to the TCL.
- **Background processes die** when the exec session ends — except the Gradle
  daemon. Keep foreground runs short; poll files (`test-results/*.xml`,
  APK outputs, screenshots), never sessions.
- Gradle lives at `/tmp/reader-dl/gradle-8.7/bin/gradle` (no system gradle).
  `android/local.properties` points at the SDK (gitignored; recreate it).
- `zsh`: never use bare `===` in `echo`; quote heredocs (`<<'EOF'`) when the
  payload contains Kotlin/TS regexes.
- Build: `cd chrome && npm install && npx tsc --noEmit && npx vitest run && npm run build`
- Build: `cd android && gradle :app:testDebugUnitTest :app:assembleDebug` (+ `:app:lintDebug`)
- Mac: `cd mac && swift test` (3/3).
- Install: `adb -s ZXKRS4VKGQ8PWGEQ install -r artifacts/reader-debug.apk`
- Screenshots: `adb -s … exec-out screencap -p > /tmp/shot.png`, then view.
  Get tap bounds via `uiautomator dump /sdcard/ui.xml` + grep.
- Row taps currently suspect — prefer hierarchy bounds over guesses.
