# Historical Reader test report — superseded

This file preserves the 2026-09-04 triage/camera evidence below. It is not the
acceptance report for the pairing/delivery repair. The current authoritative
report is [`../TEST_REPORT.md`](../TEST_REPORT.md), with live-relay and physical
boundaries in [`../LIVE_RELAY_REPORT.md`](../LIVE_RELAY_REPORT.md) and
[`../TCL_PHYSICAL_VALIDATION.md`](../TCL_PHYSICAL_VALIDATION.md).

The historical relay-success claims below are explicitly superseded because
the old harness did not await the individual `nostr-tools@2.7.1` publication
promises or perform read-back.

---

# Reader TEST-REPORT v2 — 2026-09-04 (triage build, physical TCL)

## Chrome packaging hotfix — 2026-09-04

- Fixed clean Vite builds so `dist/` always receives `manifest.json` and the
  extension icons, and so Chromium-rejected Unicode noncharacters are escaped
  in emitted JavaScript without changing their runtime value.
- PASS TypeScript, **17/17 Chrome tests**, clean production build, Manifest V3
  reference validation, ZIP integrity, and byte-identical `content.js` between
  `dist/` and the packaged ZIP. The user loaded the extension far enough to
  display its pairing QR.
- Current Chrome ZIP:
  `b8a0ca89ec311f566a65fb25446405cd4a1f693828fe98c4043b00d62227fece`.

## Camera pairing hotfix — 2026-09-04 (targeted physical TCL verification)

- Root cause reproduced on TCL T807D `ZXKRS4VKGQ8PWGEQ`: the app declared
  `CAMERA` but never requested the runtime permission. App-op was `ignore` and
  CameraService repeatedly reported `Permission hard denied` for
  `com.reader.app` while the UI silently showed an empty preview.
- Fixed the pairing screen to request permission before binding CameraX, show
  denial/retry/settings recovery UI, start CameraX asynchronously, release it
  with the Compose lifecycle, and decode padded/strided luminance planes.
- PASS debug build + **45/45 Android unit tests** (the prior 42 plus 3 camera
  luminance-plane tests); PASS lint with 0 errors and the same 26 warnings.
- PASS reinstall with `-r`, cold launch, native camera permission prompt,
  `CAMERA: allow` / foreground app-op, CameraService rear-camera connection,
  and visibly live preview (`artifacts/qa/pairing-camera-live.png`).
- NOT MEASURED: physical decoding of the actual Chrome QR and the subsequent
  encrypted pairing reply. The device could not be physically aimed at the
  desktop display during automated verification.
- Current targeted-hotfix APK:
  `e15199022d4d52ec2b40e4538fc89afe2f707670a300bdbf97b72b66b6c44dbb`.

## Environment

- macOS arm64, Temurin JDK 17.0.19, Node v22.16.0, npm 10.9.2,
  TypeScript 5.5.4, Vite 5.4.8, Vitest 2.1.3
- Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 2.0.20,
  compileSdk/targetSdk 34, minSdk 26
- Device: physical TCL T807D (`adb -s ZXKRS4VKGQ8PWGEQ`), 1080x2340
- No Rust toolchain (no cargo): `rust-core` ships as audited source; its
  algorithms are vector-gated in TS + Kotlin + Swift (see Interop).
- NOTE: `Log.d` is invisible on this TCL (a `log -p d` marker never lands
  in logcat; `logcat -d *:D` shows no D-level lines). All device claims
  below are grounded in screenshots (`artifacts/qa/`) and direct Room DB
  reads via `run-as`, never in logcat probes. Do not add `Log.d`
  instrumentation for this device — it cannot be observed.

## Build commands executed (this session, current tree)

```text
chrome:  npm install --no-audit --no-fund | npx tsc --noEmit | npx vitest run
android: gradle :app:testDebugUnitTest :app:assembleDebug | gradle :app:lintDebug
mac:     swift test   (after `rm -rf mac/.build`: stale ModuleCache pointed
         at another checkout path and failed the first run with
         "missing required module 'SwiftShims'" — environment artifact,
         not a code failure)
```

## Unit tests (all re-run on the current tree this session)

- Chrome vitest: **17/17 pass** (5 files) — golden-v1 hash + gunzip,
  word count, RSVP policy, 10-day rolling window, NIP-59 roundtrip,
  untrusted-sender / tampered-sig / wrong-recipient rejections,
  rolling-window regression, extraction fixtures (generic noise +
  4 providers), selection thresholds, NIP-07 proof accept/mutation-reject.
- Android: **42/42 pass, 0 failures, 0 errors** — ReaderCore (golden,
  canonical, counts, window, RSVP, limits, escape), Nip44 (decrypts REAL
  nostr-tools ciphertext, conversation-key symmetry, fixed-nonce
  byte-exact reproduction, tamper/MAC and wrong-key failures, reference
  padding table), GiftWrap (roundtrip + untrusted/tampered/
  wrong-recipient/bad-kind/bad-version + Schnorr self-check),
  ArticleModel (rich doc, XSS/js-link strip, malformed input, code
  exclusion, hard-break mapping), RSVP tokens + drift-free scheduler,
  TTS queue/cursor/narration, pairing-QR validation, triage swipe
  mapping + route-stack logic.
- Swift: **3/3 pass** — golden documentId + word count, sync window, RSVP.
- Lint (Android debug, current build): **0 errors**, 26 warnings,
  3 informational.

## Cross-language interop (carried over from v1, NOT re-run)

- `shared/test-vectors/golden-v1.json`: real SHA-256 + gzip bytes; TS,
  Kotlin, and Swift all reproduce `documentId 78dad25a…ff2d8` and gunzip
  roundtrip.
- `shared/test-vectors/nip44-v1.json`: real nostr-tools v2 ciphertext;
  Kotlin decrypts it exactly and re-encrypts byte-identical.

## Live public-relay smoke (carried over from v1, NOT re-run)

- Kind-1059 gift-wrap publish: **damus OK**, **nos.lol OK** (2-relay
  quorum). Retrieval roundtrip NOT MEASURED.

## Physical-device QA (TCL T807D, install `adb install reader-debug.apk`)

All items exercised on the build hashed below (APK `2fc214c5…`).
Each PASS is backed by a screenshot in `artifacts/qa/` and/or a
direct DB read. Taps use uiautomator bounds; the device serial is
always passed explicitly (`-s ZXKRS4VKGQ8PWGEQ`).

- PASS install (reinstall `-r` over prior build), cold start, empty-Inbox
  hint, 4 tabs render.
- **PASS bug #1 fix — row tap opens article.** Root cause was NOT
  gestures: `MainActivity` pushed routes and bumped a `tick` state that
  no composable read, so Compose scheduled no recomposition and every
  navigation silently never rendered (row taps, ⋮ settings, back arrow,
  system back all "did nothing"; only local-state UI like tab switches
  and the + dialog ever visibly worked). Two gesture rewrites were tried
  first (verified dead ends via screenshots); the one-line fix is
  `val route = remember(tick) { stack.current() }`. Verified 3x:
  Later-tab row tap → article renders (`row-tap-opens-article.png`,
  re-verified on second entry). SwipeRow is now stock `clickable` +
  `detectHorizontalDragGestures` with a drag-guard; debug `Log.d`
  probes removed.
- PASS system back Reader→Inbox list (`system-back-to-list.png`).
- PASS Speed→RSVP (focal word + red anchor + 300 WPM controls,
  `rsvp-speed-entry.png`) and system back RSVP→Reader, no crash
  (`rsvp-back-to-reader.png`). Cursor persistence across the hop is
  covered by unit tests + the `onExit` DB write path (not visually
  asserted beyond correct restore of the same article).
- PASS ⋮→Settings (`settings-screen.png`) and system back
  Settings→list (uiautomator dump).
- PASS +→Pair Chrome screen (`pairing-screen.png`) and system back
  Pairing→list (uiautomator dump). Camera preview black (no QR in
  view) — live scan NOT done.
- PASS paste markdown: heading + paragraph + bullets entered in the
  paste dialog, Save → imported and auto-opened with title/heading/
  bullets rendered (`paste-markdown-reader.png`).
- PASS paste plain text: two hard-break lines render as two lines
  (`paste-plain-reader.png`).
- PASS Later swipes: left-swipe Inbox→Later with Undo snackbar
  (`swipe-left-later-snackbar.png`); Later tab contents verified;
  right-swipe Later→Inbox verified (doc back in Inbox per dump).
- PASS Archive overflow: Archive rows now expose ⋮ (previously
  `onMenu` was never invoked — the dialog was unreachable);
  ⋮→"Article / Unarchive returns it to the inbox" dialog
  (`archive-overflow-dialog.png`) → Unarchive → doc back in Inbox
  (DB-verified `list='inbox'`).
- PASS dark (Ink) background: article (`dark-background-article.png`)
  and list (`dark-background-list.png`) render light-on-dark with
  status bar and gesture pill following the background.

## OBSERVED (not fixed — cosmetic, out of scope)

- Stale snackbar queue: when a swipe moves a doc, its row leaves
  composition while `showSnackbar` is still suspended, so the current
  snackbar can linger until the next snackbar event (saw "Saved for
  later" persist across a later move; queue advanced correctly on the
  next Undo tap). Moves themselves always applied (DB-verified).
- A dialog-button tap missed once because bounds were eyeballed from a
  scaled screenshot — always re-dump bounds after layout changes
  (keyboard/voice bar resize dialogs).

## NOT MEASURED / limitations (honest)

- No tablet/foldable layout proof.
- TTS audio output not listened to (queue/speed/fallback unit-tested
  only); TTS bar play path not exercised on the TCL this session.
- Pairing QR live scan not done; Amber/nos2x live signers not
  installed; Chrome extension not loaded in a real browser (dist
  unchanged); no Playwright extension E2E.
- `rust-core` not compiled (no cargo); UniFFI bindings still ahead.
- 16 KB page-size: debug APK still carries the third-party
  `.so`-alignment warning (`libdatastore_shared_counter.so` +
  `libimage_processing_util_jni.so`); release track must re-verify on
  a 16 KB-page device.
- Interop vectors + live-relay smoke carried over from v1 (not
  re-executed against this tree).

## Artifact hashes (SHA-256)

```text
e15199022d4d52ec2b40e4538fc89afe2f707670a300bdbf97b72b66b6c44dbb  reader-debug.apk (current camera hotfix)
2fc214c5338c593de88504e92be037007753b0c3e7204639d7f7394a0ae64566  superseded v2 triage APK documented above
b8a0ca89ec311f566a65fb25446405cd4a1f693828fe98c4043b00d62227fece  reader-chrome-extension.zip (current packaging hotfix)
9a33867a6e64bc74795c92e3132e79fe9826a77b9fdb523c1d49c59611622974  superseded v2 Chrome ZIP
```

APK is debug-signed QA, not a store release. The on-device test DB
holds fixture/paste imports (Inbox: 2 pastes + 1 unarchived share;
Priority: 1 share; Later: empty; Archive: 5 shares).
