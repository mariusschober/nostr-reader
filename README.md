# nostr-reader — Decentralized Nostr-based Read-Later app ecosystem

> Send pure text and markdown files encrypted to your devices via the Nostr
> network; then read them — with a simple distraction-free reading experience.

## Vision and intention

Read-later tools today mean accounts, clouds, and subscriptions standing
between you and your own words. nostr-reader removes the middleman: ordinary,
interchangeable Nostr relays carry only ciphertext, and every app in the
ecosystem (Chrome extension, Android reader, planned Mac sender/reader, then
iOS) is a thin native shell over one shared, openly specified protocol.

- **No servers, no accounts, no telemetry.** Relays are dumb encrypted transport.
- **Invisible infrastructure.** Users never see Nostr keys, relays, or Markdown
  source — only Inbox → Priority / Later → read → Archive.
- **One protocol, many shells.** `PROTOCOL.md` plus golden test vectors in
  `shared/test-vectors/` are the contract every platform implements and proves
  against before release. `rust-core/` is the normative algorithm owner;
  UniFFI bindings will carry it to Swift.
- **Reading as a calm activity.** Four bundled article typefaces, exactly four
  appearance controls, native rendering, TTS and single-word RSVP sharing one
  semantic reading position.

Status, open bugs, and next steps for agents and contributors: [`CONTINUE.md`](CONTINUE.md).
Threat model: [`SECURITY.md`](SECURITY.md). Protocol: [`PROTOCOL.md`](PROTOCOL.md).

---
# Reader — private zero-server reading inbox

Chrome / AI answer -> one click -> encrypted Nostr -> Android article.
Inbox -> Consume -> Archive. No backend, no accounts, no telemetry.

## Repo

```text
shared/schemas        normative JSON schemas
shared/test-vectors   cross-platform golden vectors (interop gate)
shared/fixtures       extraction fixtures (generic + 4 AI providers)
rust-core/            normative algorithm owner (canonicalize/hash/chunk/codec/RSVP)
chrome/               Manifest V3 extension (TS, Vite, vanilla DOM)
android/              native reader (Kotlin, Compose, Room)
mac/                  Mac sender + minimal reader plan + Swift skeleton
PROTOCOL.md           normative transport rules
SECURITY.md           threat model (read before claiming anything)
CROSS_PLATFORM.md     binding contract + iOS checklist
MAC.md                Mac sender + reader definition
artifacts/            built APK, extension ZIP, TEST-REPORT.md
```

## Build

Chrome: `cd chrome && npm ci && npm run typecheck && npm test && npm run build`
Android: `cd android && ./gradlew test assembleDebug`
Vectors gate every release: golden-v1 must pass on all platforms.

## Install

- Android: `adb install artifacts/reader-debug.apk` (debug-signed QA build,
  not a store release).
- Chrome: `chrome://extensions` -> Developer mode -> Load unpacked ->
  `chrome/dist`. Or unzip `artifacts/reader-chrome-extension.zip`.
