# Reader TEST-REPORT v1 — 2026-09-04

## Environment

- macOS arm64, Xcode 26.6, Swift (Package Manager), Temurin JDK 17.0.19
- Node v22.16.0, npm 10.9.2, TypeScript 5.5.4, Vite 5.4.8, Vitest 2.1.3
- Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 2.0.20, compileSdk/targetSdk 34, minSdk 26
- Android SDK platforms 34/35/36, build-tools 34/35/36
- Emulator: `sprich_review_api26` (API 26, arm64) — matches minSdk
- No Rust toolchain (no cargo): `rust-core` ships as audited source; its
  algorithms are vector-gated in TS + Kotlin + Swift (see Interop).

## Build commands executed

```text
chrome:  npm install | npx tsc --noEmit | npx vitest run | npm run build
android: gradle :app:testDebugUnitTest :app:assembleDebug | gradle :app:lintDebug
mac:     swift test
```

## Unit tests

- Chrome vitest: **17/17 pass** — golden-v1 hash + gunzip, word count, RSVP
  policy, 10-day rolling window, NIP-59 roundtrip, untrusted-sender / tampered-
  sig / wrong-recipient rejections, rolling-window regression, extraction
  fixtures (generic noise + 4 providers), selection thresholds, NIP-07 proof
  accept/mutation-reject.
- Android: **28/28 pass** — ReaderCore (golden, canonical, counts, window,
  RSVP, limits, escape), Nip44 (decrypts REAL nostr-tools ciphertext,
  conversation-key symmetry, fixed-nonce byte-exact reproduction, tamper/MAC
  and wrong-key failures, reference padding table), GiftWrap (roundtrip +
  untrusted/tampered/wrong-recipient/bad-kind/bad-version + Schnorr
  self-check), ArticleModel (rich doc, XSS/js-link strip, malformed input,
  code exclusion, hard-break mapping), RSVP tokens + drift-free scheduler,
  TTS queue/cursor/narration, pairing-QR validation.
- Swift: **3/3 pass** — golden documentId + word count, sync window, RSVP.
- Lint (Android debug): **0 errors**, 26 warnings, 4 informational.

## Cross-language interop (release gate)

- `shared/test-vectors/golden-v1.json`: real SHA-256 + gzip bytes; TS, Kotlin,
  and Swift all reproduce `documentId 78dad25a…ff2d8` and gunzip roundtrip.
- `shared/test-vectors/nip44-v1.json`: real nostr-tools v2 ciphertext with
  fixed keys + fixed nonce. Kotlin decrypts it exactly and re-encrypts to the
  identical payload bytes (byte-exact NIP-44 incl. reference chunked padding).
- Two real bugs caught by vectors during this build: (1) power-of-two padding
  assumption vs the reference chunked table — fixed in Kotlin; (2) nostr-tools
  `verifyEvent` memoizes trust on the object via symbol (spread copies it) —
  transport now re-parses wire JSON at the verification boundary + test pins it.

## Live public-relay smoke (throwaway keys, harmless fixture, 2026-09-04)

- `relay.damus.io` NIP-11 live: strfry, `max_message_length 1000000`.
- Kind-1059 gift-wrap publish: **damus OK**, **nos.lol OK** (2-relay quorum).
- Retrieval roundtrip: NOT MEASURED (ephemeral smoke events; full
  send→receive→ACK loop is covered by unit vectors + emulator ingest path).

## Emulator QA (API 26, install `adb install reader-debug.apk`)

- PASS install, launch, inbox (Flexoki Paper, Inbox/Archive tabs, `1m`
  attention budget, no counts), ACTION_SEND import (toast + live inbox
  refresh), native article render (Newsreader, title/source/time header,
  paragraphs, hard breaks, end mark, Finished/Archive), appearance sheet
  (5 self-rendered fonts, size slider, 3 margins, 4 backgrounds — Flexoki
  themed, no stock purple), RSVP play (chromeless, red anchored focal,
  progress) + pause (Exit/±10/Play/WPM slider/Finished), TTS bar (queue ran
  4/4 live on emulator). Screenshots in `artifacts/qa/`.
- Visual QA fixes applied from screenshots: plain-text hard breaks, inbox live
  refresh, hard-break node mapping, Flexoki dialog/RSVP theming, chip overflow.
- No `com.reader.app` crashes in logcat (one unrelated OOM in another app).
- 16 KB page-size: emulator shows the debug-build warning naming
  `libdatastore_shared_counter.so` + `libimage_processing_util_jni.so`
  (third-party .so alignment). No NDK code of ours; functional on 4 KB
  devices; release track must re-verify on a 16 KB device.

## NOT MEASURED / limitations (honest)

- No physical device; no tablet/foldable layout proof.
- TTS audio output not listened to (no emulator voice audit); queue, speed,
  fallback paths, and Media3 session code are unit/instrument-tested only.
- Pairing QR live scan not done (camera path untested); protocol validated by
  unit tests + manual-paste path in UI.
- Chrome extension dist validated (manifest parses, relative entry points,
  strict CSP, no remote scripts, no eval) but NOT loaded in a real browser;
  provider inline buttons covered by fixture tests, not live DOM.
- `rust-core` not compiled here (no cargo); UniFFI bindings are a next step —
  Mac/iOS consume the Swift-verified mirror + vectors until then.
- Amber / nos2x live signers not installed; NIP-07 bridge + NIP-55 boundary
  tested with mocks/seams only.
- No Playwright/Chromium extension E2E; no screenshot-golden RSVP anchor test
  (anchor verified visually on-device instead).

## Artifact hashes (SHA-256)

```text
a0b08ea5cc4a4bd787fa690c97f7d16e164d640a7660427ff14d2f8ee042cebc  reader-debug.apk
9a33867a6e64bc74795c92e3132e79fe9826a77b9fdb523c1d49c59611622974  reader-chrome-extension.zip
```

NOTE: hashes are current as of the final build (RSVP scheduler wiring). After any rebuild, re-run
`shasum -a 256 artifacts/*` after any rebuild. APK is debug-signed QA, not a
store release. The emulator test DB holds only fixture imports.
