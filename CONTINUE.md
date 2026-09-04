# CONTINUE — agent handover for nostr-reader (2026-09-04, must-list done)

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

## BUG #1 (RESOLVED 2026-09-04): row taps dead — was never gestures

- **Symptom was**: tapping an article row in any triage list did nothing.
  Tab switches, "+" dialog, row swipes all worked. No crash, no log.
- **Root cause (found by +/⋮ split test)**: NOT the swipe container.
  `MainActivity` pushed routes and bumped a `tick` state that NO
  composable read, so Compose scheduled no recomposition and navigation
  silently never rendered. Touch handling was fine all along — every
  handler ran, only local-state UI ever visibly updated. One-line fix:
  `val route = remember(tick) { stack.current() }` (`MainActivity.kt`).
- **Dead ends verified on-device first** (screenshots): `draggable`
  `startDragImmediately=false` + `clickable` still dead; hand-rolled
  `awaitEachGesture` loop saw swipes (DB-proven move) but its tap branch
  never observably fired. Final SwipeRow is stock `clickable` +
  `detectHorizontalDragGestures` with a drag-guard.
- **Also fixed alongside**: Archive rows never invoked `onMenu` (overflow
  dialog unreachable) — `ArticleRow` is now tap-to-open with a ⋮ button
  when `onMenu != null`. Debug `Log.d("RowTap")` / `Log.d("ReaderBack")`
  removed.
- **Device quirk that burned two sessions**: `Log.d` is INVISIBLE on the
  TCL (`log -p d` marker never lands). Verify via screenshots + `run-as`
  DB reads only. Recorded in `artifacts/TEST-REPORT.md` v2.
- **Known cosmetic**: swipe snackbar can linger when the row leaves
  composition mid-`showSnackbar` (queue advances on next snackbar
  event; moves always apply — DB-verified). Not fixed, out of scope.

## Other known issues

- System-back from article/RSVP/pairing/settings VERIFIED on the TCL
  2026-09-04 (screenshots/dumps); back on inbox-root exits to launcher
  (verified earlier, not re-run).
- Paste dialog (markdown + plain), Later-tab swipes both directions,
  Archive overflow (Unarchive DB-verified), dark-background system bars
  (list + article screenshots): all VERIFIED on device 2026-09-04.
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

**Must (all DONE 2026-09-04, see `artifacts/TEST-REPORT.md` v2)**
1. ~~Fix OPEN BUG #1~~ FIXED (navigation observability) + verified open
   by tap on the TCL; debug logs removed.
2. ~~Verify system back~~ VERIFIED Reader→list, RSVP→Reader,
   Pairing/Settings→list on device.
3. ~~Exercise paste, Later swipes, Archive menu, dark bars~~ VERIFIED.
4. ~~Update TEST-REPORT + hashes~~ DONE (17 chrome / 42 android / 3
   swift, all re-run; APK `2fc214c5…`).

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
