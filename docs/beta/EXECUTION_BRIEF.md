# Nostr Reader — autonomous execution brief for Astra / local Mac Codex

**Target:** a trustworthy, feature-complete `0.9.0-beta.1` of the Chrome extension and Android reader.  
**Prepared:** 5 September 2026.  
**Repository:** `https://github.com/mariusschober/nostr-reader`  
**Starting branch:** `fix/pairing-delivery-hardening-982920b4`  
**Audited head:** `0c9884e955558f478d7668b46e9c1411bfe95c5d`  
**Old main / original baseline:** `982920b4e91dd5af4af6046f56df281c7adfe545`.

---

## 1. Your assignment

You are the principal engineer and product craftsperson responsible for executing the next stage of Nostr Reader on this Mac, using the repository, browser, installed extension, Android tooling, and connected Android device available to you.

**Implement, test, red-team, document, and deliver. Do not stop at another review or plan.** Work through this brief autonomously, making the smallest sound product and engineering decisions where details are unspecified. Maintain a concise execution ledger so work survives context compaction. Do not repeatedly ask the owner to make technical choices you can resolve from source, tests, or inspection.

Read this entire brief before editing. Read the companion `nostr-reader-red-team-audit-2026-09-05.md` when supplied, plus the repository’s `AI_CONTINUATION_CONTEXT.md`, `TEST_REPORT.md`, `KNOWN_LIMITATIONS.md`, `PROTOCOL.md`, `THREAT_MODEL.md`, and `RELIABILITY_STATE_MACHINES.md`. Inspect current code; an audit finding is a starting hypothesis to verify, not permission to change something blindly. Preserve any newer valid work discovered after the audited commit.

The product promise is:

> I click once on a selection, an article, or one AI answer. Reader immediately tells me what happened, keeps the capture safe, sends it privately through independent Nostr relays, and lets me read, highlight, share a quote, and revisit important ideas on Android—even offline after receipt.

The main deliverables are a tested installable Android APK, matching instrumentation APK, tested loadable Chrome extension ZIP, committed source, migration and recovery evidence, a readable test report, and an exact artifact manifest. A version label is not the result; the tested product is.

### Scope boundaries

Keep the native Android reader and Chrome MV3 extension. Preserve random private device keys, NIP-44/NIP-59, authenticated pairing, durable queues, authenticated phone acknowledgments, local-first content, Flexoki, and bundled fonts.

Do not add a proprietary backend, FCM dependency, account system, telemetry, cloud extraction, paid API, LLM extraction service, a new ratchet, multi-device highlights synchronization, an iOS app, or a Mac reader in this stage. Existing Rust/Swift reference contracts should remain compatible and tested; that is not an instruction to build those products.

Do not replace the entire app architecture to implement a small feature. Focused refactoring of orchestration, data projections, renderer semantics, and provider adapters is justified. Generic frameworks, extensive plugin systems, and speculative features are not.

### Safe autonomy

Inspect Git status, remotes, branch ancestry, existing Chrome profiles, signing configuration, and `adb devices -l` first. Create a dedicated continuation branch descended from the repaired work, such as `feat/reader-0.9-beta`. Never start from old main and accidentally discard the repair. Do not reset, clean, overwrite, or stash unrelated user changes without preserving them and recording what you did.

Preserve the owner’s installed app data, keys, articles, highlights, paired Chrome profile, and other connected devices. Use explicit device serials on every ADB operation. Prefer an isolated QA app/profile/identity for destructive tests and public synthetic campaigns. Do not uninstall or clear the owner’s app to make a migration pass. If signing prevents an in-place update, resolve through the existing signing setup or use a separate QA installation; do not destroy the installed data.

Run deterministic fault/load tests on local relays. For live interoperability and delivery tests, use only bounded, non-sensitive synthetic content and disposable test identities; no private browser content, account messages, passwords, production notes, or real secrets as probes. Do not send messages to other people or publish unencrypted content. Do not put QR bearers, keys, complete AUTH challenges, private URLs, article bodies, or signer responses in logs or screenshots committed to Git.

Normal local builds, device installs that preserve data, test-profile browser actions, and synthetic test transfers are part of this assignment. Public releases, store submissions, purchases, personal-identity signatures, destructive production changes, force-pushes, and merging to main are not. Commit checkpointed work. Push the dedicated branch only through an already authorized writable remote; otherwise leave complete local commits and report their location. Never bypass an actual permission or credential boundary.

When a live site, signer, emulator image, or device capability is unavailable, continue all independent work and mark that exact gate `BLOCKED` or `NOT MEASURED`. Do not substitute a mock result for a live claim. Do not claim completion if required functionality or a release-blocking test remains unresolved.

## 2. Establish the baseline and execution ledger

Before changing application behavior:

1. Record repository URL, starting SHA, branch ancestry, clean/dirty state, toolchain versions, browser version, device model/Android version, current app version/signature, and available emulator targets. Redact personal device identifiers in shareable reports.
2. Read and run the repository’s existing build scripts in an isolated working setup. The previous commands included:

   ```sh
   (cd chrome && npm ci && npm run typecheck && npm test && npm run build)
   (cd android && ./gradlew clean test lint assembleDebug assembleDebugAndroidTest)
   (cd rust-core && cargo test)
   (cd mac && swift test)
   ```

   Inspect `scripts/build-audit-artifacts.sh` before using it; it expects a clean tracked tree and packages reproducibly. Do not assume cached dependencies or old outputs are current.
3. Retain baseline failures as evidence. Distinguish environment/setup failures from app regressions. Do not quietly remove a failing test.
4. Create `BETA_EXECUTION_LEDGER.md`, a requirements-to-tests matrix, and a small architecture decision log. Use `TODO`, `IN PROGRESS`, `PASS`, `FAIL`, `BLOCKED`, and `NOT MEASURED` precisely. Every finding below needs a final disposition: fixed with a behavioral regression test, disproved with evidence, or explicitly unresolved.
5. Build a deterministic local relay harness supporting storage, positive/negative/missing OKs, AUTH, CLOSED/NOTICE, delayed delivery, duplicate/reordered fragments, selective deletion, caps/truncation, and connection interruption. Keep test-only local-network allowances out of production.

The retained handoff proves one earlier synthetic live path and installation of the final TCL app. It does not prove the full reliability campaign or final-hash instrumentation. The previous 107-task Android gate is a task count, not a test count. Several Chrome recovery tests inspect source strings; retain them only as supplementary checks.

## 3. Source findings you must verify and close

Paths below refer to the audited head. Android paths are relative to `android/app/src/main/java/com/reader/app/`.

| ID | Starting point | Required investigation / correction |
|---|---|---|
| R01 | `chrome/src/background/service-worker.ts`, top-level alarms | Same-name 15-minute retry alarm is recreated at every worker evaluation; one-minute pairing wakeups can repeatedly postpone it. Replace with durable, idempotent due-time scheduling. |
| R02 | `chrome/src/content/capture.ts`, worker message/action handlers | Inline button does not inspect `response.ok`; toolbar ignores outcomes; `reader-low-confidence` lacks a consumer; injection errors disappear. Unify capture outcomes and truthful feedback. |
| R03 | Android `nostr/NostrCodec.kt`, `sync/SyncWorker.kt`; Chrome query accumulation | Receive lists have no aggregate event/byte budget; failures can become empty success; cancellation/connection cleanup needs proof. Bound and stream intake. |
| R04 | Worker `queryPairingEvents` / ACK polling | Fixed `limit:64` has no continuation. Recover retained ACKs behind larger histories with safe overlapping coverage and cap handling. |
| R05 | Android `sync/TransferManager.kt::queueAckIntent` | A completed ACK quorum never reopens on a later retransmission; code assumes relay retention until expiry. Add bounded demand-triggered ACK recovery after relay loss. |
| R06 | `chrome/src/extraction/pipeline.ts`, context-menu handler | An explicit short selection can be ignored; different routes escape/capture literal text differently. Honor every nonblank deliberate selection. |
| R07 | `chrome/src/providers/adapters.ts`, content observer, manifest | Broad host/response selectors, first-button mount, stale mounted flag, whole-page rescans, missing requested sites. Implement resilient adapters and real DOM tests. |
| R08 | Worker publishing loop / `publishFreshPayload` | Per-payload connection churn, sequential slow-relay waits, no durable fragment-progress checkpoints, network held inside transfer serialization. Make resumable and bounded without resurrecting delivered work. |
| R09 | Android sync / visible activity lifecycle | Events are processed after collection windows; visible app does not remain ready after the initial window. Add an arrival-driven visible-app fast path and honest background catch-up. |
| R10 | Android `ui/screens/ReaderScreen.kt` | Header causes block/list index mismatch; character restoration offset ignored; nested block mapping incomplete. Fix before building highlights. |
| R11 | Android `core/ArticleModel.kt`; provider converter | Table renderer types exist but normal parser flattens tables; conversion also loses code/list/format structure. Add end-to-end fidelity fixtures. |
| R12 | Android `data/ReaderDb.kt`, `ui/MainActivity.kt` | Lists fetch full bodies; progress triggers broad reloads; composition parses content. Use lightweight projections, targeted flows, background parsing, and measured limits. |
| R13 | `chrome/manifest.json`, `src/ui/options.html` | Options not registered in manifest; recovery UI is mostly counts/delete-all. Add discoverable settings and per-item recovery/history. |
| R14 | Android MainActivity TTS callback and `tts/TtsController.kt` | Speed preference changes do not update the active controller. Implement actual playback-speed transition and stale-callback tests. |
| R15 | Android prefs/theme/inbox/database | Complete Light/Dark/System, empty-Inbox behavior, persistent highlights, quote sharing, and review exactly as specified below. |
| R16 | Both unwrap paths / key and crypto helpers | Tighten Reader rumor-profile validation, early unknown-sender rejection, uniform key generation, bounded caching, and dependency-hook assurance. No speculative cipher redesign. |
| R17 | `chrome/tests/service-worker-recovery.test.ts` | Replace source-shape confidence with execution of real orchestration, persistence, alarms, crashes, and interleavings. |
| R18 | Parser/renderer, privacy docs, compatibility/release scripts | Reconcile reachable image/link behavior, dependency/SDK/page-size claims, exact-package tests, supported-site claims, and versions. |

Fix core transport/capture defects before adding the review feature. Commit focused changes with failure-first tests where feasible.

## 4. Transport, lifecycle, and durability contract

### 4.1 One truthful capture transaction

Introduce one typed capture request and result contract for toolbar, inline buttons, context menu, and keyboard shortcut. A request includes a client-generated capture ID, source scope/type, title/URL where available, content, and enough tab/frame/document identity to route feedback safely. Validate sizes and types in the trusted worker; content scripts are not a key-bearing trust boundary.

Use distinct identities for a UI capture action, immutable document content, and a transfer. Repeated delivery of the same runtime message must not create multiple transfers. A deliberate later resend must remain possible. Do not deduplicate every future intentional action merely because its document hash matches an old capture.

Commit the local capture before saying it was saved. Persist enough content to recover after a lost message response, worker crash, device repair, or retry expiry. Surface quota/full-disk failures immediately. Do not delete retained content merely because a delivery attempt failed.

Keep a compact durable per-item history with states such as:

`capturing → queued → publishing → relay_accepted / awaiting_device → delivered`

and explicit `needs_pairing`, `retry_wait`, `failed`, `expired`, and user-cancelled outcomes where appropriate. State transitions must be tested, not inferred from UI timers. Publicly displayed success wording is specified in section 6.

### 4.2 Durable scheduler and concurrency

Make alarms a wakeup mechanism for persisted work, not the source of truth. Persist due times, attempt state, identity binding, and relevant progress before yielding. On worker evaluation/startup, inspect existing alarms and restore missing or earlier-needed wakes without postponing a valid earlier wake. Schedule no pairing polling when there is no live pairing session.

Respect the supported Chrome version’s alarm semantics. Current Chrome documents `persistAcrossSessions` from version 150; that does not eliminate update/reload recovery or justify replacing alarms on every boot. Support the chosen minimum browser version explicitly, rather than assuming every API exists.

Use a single bounded scheduler owner per worker instance plus durable version/terminal-state checks. On every asynchronous commit, re-read or compare the transfer generation so a late publisher cannot recreate a delivered/deleted/cancelled item. Persist the terminal receipt before payload cleanup. Recover an interrupted cleanup from that receipt without republishing.

Do not hold a transfer lock or database transaction across an entire multi-relay network loop. Keep short state transitions serialized; perform network work outside them with cancellation and generation checks. Limit concurrent transfers, per-relay operations, and in-flight bytes. Do not replace one race with unbounded `Promise.all` over the whole outbox.

Check for ACKs before redundant resend when useful, but a failed ACK query must not prevent all publishing. Reconnect and browser/device startup must resume work even when no popup/options page is open. Closing DevTools must not change correctness.

### 4.3 Fragment progress and relay reuse

Reuse a bounded relay connection set for a work session rather than opening fresh pools for every fragment. Preserve authenticated relay-set binding and the current two-relay per-payload acceptance policy unless a documented compatibility decision explicitly changes it. Do not silently reduce the requirement because a relay is down.

Maintain durable progress at fragment/relay granularity sufficient to resume a partially completed attempt. Record accepted/rejected/timeout/AUTH outcomes without retaining sensitive challenges. Revalidate assumptions after sufficiently old acceptance; historical OK is not permanent retention proof. Retransmit missing/stale coverage within budgets, not necessarily every already accepted fragment every time.

Maintain appropriate encrypted frame-size limits after actual wrapping/padding—not just uncompressed or gzip size. Include relay limitations when available, without trusting remote policy documents as security controls. Keep gzip-before-encryption and the strict single-member codec contract.

A validated phone storage ACK outranks unfinished redundant relay publication. Cancel or safely abandon unnecessary work and settle promptly. Never lose the final receipt or resurrect its payload through a stale callback.

### 4.4 Android arrival-driven intake

Create one shared authenticated intake/assembly component used by both foreground receiving and WorkManager. Process events on arrival through a bounded queue; do not wait for the slowest relay’s full collection timeout before storing a complete document.

Use a lifecycle-scoped receiving session while the application is visible, including the reader/review screens. Reuse the app-owned HTTP client and bound connection count. Stop or yield appropriately when the app is no longer visible. Avoid duplicate foreground and worker owners processing/publishing the same transfer concurrently.

Retain constrained WorkManager catch-up for background operation and app reopen/network recovery. Expose manual Sync and last successful/degraded sync state. A network failure must not be rendered as a healthy empty inbox. Do not promise instant delivery while Android is force-stopped, offline, or withholding background execution. Do not enable a perpetual foreground service or aggressive battery exemption by default to conceal that limitation.

Use cancellation-aware IO, close sockets in all exit paths, propagate coroutine cancellation rather than swallowing it, and bound DNS/probe/body reads as well as WebSocket processing. A per-frame limit alone is insufficient: set and document aggregate bytes, event count, tag count, JSON depth, concurrent partial transfers, staged bytes, and crypto-work budgets. Limits must accommodate the supported maximum document through streaming, not by retaining the whole relay history in memory.

Prioritize fair progress from healthy relays under malicious traffic. Reject untrusted seal senders as early as authenticated structure allows. Skip already committed authenticated event IDs before repeating expensive work where safe. Never treat an unverified claimed event ID as proof that new content is authentic.

### 4.5 ACK correctness and recovery

A delivered receipt must bind the expected phone/channel, authenticated inner sender, transfer ID, manifest identity, document hash/ID, and valid status/time/expiry. Receiving a relay OK or a forged acknowledgment must never mark a document delivered.

Commit a complete validated document and its ACK intent atomically where storage architecture permits. Where content is in a file/object outside Room, implement explicit staged-file/finalization/crash recovery so the acknowledgment cannot race ahead of durable readable content. “Stored” means the supported reader can actually retrieve that content after process death. Keep exactly-once document effects under duplicate/reordered fragments and repeated transfers; preserve existing progress, triage, and highlights.

Fix R05 without returning to ACK storms: a new authenticated retransmission of a still-valid immutable transfer may request a bounded ACK refresh after a durable cooldown. Identical retained wrapper replays do not continually restart it. Bound total retry generations and TTL, and document the recovery tradeoff. Test completed-quorum ACK loss, not only failure before the first OK.

If extending the existing payload profile is necessary, negotiate/version it explicitly; do not change `reader/2` semantics incompatibly and call it the same protocol. Prefer a correction that remains compatible with current valid transfers.

### 4.6 Catch-up coverage

Replace the fixed 64-result assumption with resumable, bounded scanning. Preserve an overlap that covers randomized past timestamps and all still-valid transfers. Never advance a simple outer-event high-watermark as though NIP-59 timestamps were monotonic.

Handle result caps, equal timestamps, duplicate pages, time boundaries, EOSE, and interruptions explicitly. Inclusive time overlap plus ID deduplication is necessary; blindly using `oldestTimestamp - 1` skips ties. Request larger bounded pages or narrower windows when supported, and retain scan coverage/state across work budgets.

NIP-01 cannot force a relay with an opaque cap to expose every event in a saturated timestamp bucket. Detect and report incomplete coverage, fall back to other authenticated relays and recovery, and never claim exhaustive scanning when the protocol did not establish it. Tests must include both recoverable capped histories and deliberately unrecoverable relay behavior.

### 4.7 Pairing and channel changes

Preserve authenticated NIP-59 pairing, the relay-set digest, exact recipient checks, one-time QR secrets, expiry, identity binding, no early active channel, and safe retirement of prior channels. Re-pairing must not silently trust an old or partially provisioned identity.

Test lost QR bootstrap state, Chrome restart before response/ACK/completion, Android death between key provisioning and DB state, clock skew, duplicate scans, cancelled pairing, pending expiry, and custom-relay-only success paths. Privileged extension messages—key access, relay changes, disconnect, signing, diagnostics—must be restricted to intended trusted extension pages; web content receives only the minimal capture interface.

Never-sent unbound captures may bind to the new authenticated device. Already published transfers keep their immutable binding. Offer an explicit recovery action that creates a new transfer from retained local content, rather than mutating the old transfer’s recipient/hash. Relay-set changes remain explicit and authenticated; health information must not silently alter the agreed set.

## 5. Cryptography, dependencies, and privacy

Preserve tested algorithms and protocol layering. Do not invent “optimized encryption.” Use current official NIP-01/40/42/44/59 text and official BIP-340 vectors; record the referenced revision/date when updating conformance tests.

Complete the Reader envelope/profile validator on both platforms: exact expected kind/profile, sane integer timestamps, expected tags, strict field types, bounded sizes, proper event IDs/signatures, correct recipient, trusted inner sender, supported payload schema, and manifest/chunk/ACK identity. Maintain hostile tests for malformed keys, wrong kind, duplicate fields, signed rumors, mixed recipients, tampered ciphertext, wrong MAC/padding, unsupported versions, expired data, invalid UTF-8, and gzip bombs/concatenation/trailing bytes.

Do not mistakenly reject NIP-44’s current extended-length format just because older implementations used only a two-byte length prefix. Keep Reader’s smaller documented resource cap and interoperable fixtures.

Review Android’s BouncyCastle/BigInteger signing/ECDH implementation against maintained alternatives, licensing, side-channel properties, Android compatibility, and actual performance. Replace it only if the migration is justified and safely testable. Passing vectors is not proof of constant-time behavior. Uniform secret sampling should use a vetted rejection-sampling helper. Optimize redundant curve work, allocation-heavy formatting, and repeated stable conversation-key derivation only after profiling and differential vectors; never reuse nonces/ephemeral secrets or log intermediate keys. Clear bounded caches on channel revocation.

Preserve content-script isolation from key-bearing storage and the precise NIP-42 signing template restrictions. Audit `_onauth`/other private dependency hooks under the pinned version and isolate them behind a tested adapter. Upgrade security-sensitive dependencies deliberately with lockfiles, advisory scans, regression tests, and reproducibility checks—not an indiscriminate “upgrade everything” pass.

Run current production and development dependency scans for Chrome and Android, inventory native/transitive libraries, and record tool/database timestamps and any unscanned components. Check Android SDK/target changes against current official requirements and compatibility behavior. Store publication is not part of this assignment. Validate 16-KiB loading on an appropriate emulator/target when native libraries exist; APK inspection alone does not equal runtime validation.

Trace actual remote-media reachability. The reviewed parser generally produces image alt text, while the renderer contains a remote-image branch and the documentation describes direct loading. Make code and documentation agree. Default to text-only/no background remote image fetch; add an explicit per-article load action only if image rendering is retained. Enforce safe schemes, destination/redirect rules, decoded image limits, and offline fallback. Keep existing HTTP/HTTPS link sanitization and require deliberate user action to open links.

State privacy precisely: no proprietary backend; encrypted content through independently operated relay servers; relay-visible network/routing metadata; no forward-secrecy claim; no deletion guarantee for remote copies; endpoint compromise outside that encryption boundary. Local highlights never leave the device except through an explicit share/export action in this release.

## 6. Chrome capture and feedback experience

### 6.1 Keep one click; make its result visible

Do not add a mandatory popup to the toolbar action. One click captures the explicit selection first; otherwise the scoped provider response or confidently detected article. If the page is unsupported/restricted, explain that instead of doing nothing.

Use a restrained inline/status chip or small toast and a toolbar fallback badge/title. Feedback must not steal focus, shift page layout, cover the content being read, capture keyboard input, or inject into a newly navigated unrelated document. Use accessible live-region announcements, keyboard-operable buttons, reduced-motion support, and CSS isolation from host pages.

Suggested state language:

| Actual state | Primary wording |
|---|---|
| Click accepted, work not yet durable | `Saving…` |
| Outbox committed; device offline/unconfirmed | `Saved — waiting for your phone` |
| Not paired but capture retained | `Saved — connect your phone` |
| Positive relay acceptance without phone ACK | `Sent to relays — waiting for your phone` in details; do not call this delivered |
| Validated phone storage ACK | `On your phone` |
| Retriable network problem | `Saved — will retry` |
| Extraction/storage failure before commit | `Couldn’t save` with an actionable reason |
| Transfer expired, local copy retained | `Not delivered — retry or export` |

Aim for visible click acknowledgment within 100 ms and a durable local receipt within 500 ms for an ordinary <=100-KiB text capture on the reference Mac, measured rather than assumed. Large captures may take longer and must show honest progress. These are product budgets, not claims about the current build.

A quiet optional sound can accompany a durable save, disabled by default. Do not make sound essential and do not create an offscreen/audio subsystem merely for a chime. Avoid celebrating every relay response. No animation or tone may imply phone delivery before its ACK.

### 6.2 Settings and recovery

Register the options page in the manifest and expose it through the extension action’s context menu and normal Chrome options entry. Add clear keyboard/selection instructions, connection state, and a recent-transfer list. Keep normal settings small: Device, Capture/site access, Feedback, and recent sends; hide relay diagnostics and signer provenance under Advanced.

For each retained transfer, show title/source only in the user’s own UI, current state, time, and a useful action: inspect error, check receipt, retry, resend after identity/expiry repair, export captured text, or discard with confirmation. Use safe DOM text assignment, not untrusted HTML. Do not make “delete all failed” the only recovery action. Keep receipts/history bounded without discarding undelivered content silently; expose storage pressure and cleanup choices.

Make options and pairing pages follow system light/dark styling with shared Flexoki tokens. Eliminate dead signing placeholders or clearly label optional provenance features as unconfigured. NIP-07/Amber are not required for the private-device-key transport path; validate live signers when available before advertising those optional paths as tested.

### 6.3 Extraction contract

An explicit nonblank selection always wins, however short. Capture literal selected text consistently across toolbar, context menu, shortcut, and supported frame cases. Keep source URL/title where safe. Do not include text from password fields, unrelated hidden DOM, collapsed private panels, neighboring feed posts, or other chat answers.

Use structured extraction for an individual provider response, and a shared sanitized DOM-to-Markdown pipeline preserving headings, paragraphs, links, citations, lists, blockquotes, fenced code, tables, Unicode, and visible mathematical text. Preserve literal text when that is what the user selected. Do not send extraction controls, injected Reader buttons, tracking UI, or host “copy/regenerate” labels.

When general article extraction is low confidence, retain a safe explicit-selection fallback and offer a small preview/capture choice rather than silently dropping the event or guessing at the entire page. Do not auto-scroll pages, bypass paywalls, fetch hidden authenticated APIs, or expand collapsed content without a user action. Clearly label a partial capture when only currently expanded content is available.

## 7. Provider and article support

Use a small adapter registry—not a generalized plugin framework. Each adapter defines exact host boundaries, supported routes, stable item identity, role/scoping logic, content extraction root, action mount, readiness/streaming state, and cleanup. Keep extraction independent from mounting so both are testable.

Observe relevant mutations incrementally. Reconcile actual buttons, not a permanent mounted flag. Debounce bursts, clean up stale handlers and SPA navigation state, and avoid full-document scans on every mutation. Virtualized nodes must not retain closures pointing to the previous post. Each real content item gets at most one Reader control; injection never creates a duplicate listener or observer after extension reinjection. Page-generated synthetic clicks or arbitrary page `postMessage` events must not authorize a capture or privileged operation; require a genuine user interaction for injected controls, while preserving legitimate toolbar and keyboard flows.

Required matrix:

| Surface | Required capture scope and behavior |
|---|---|
| ChatGPT (`chatgpt.com`, verified legacy alias where applicable) | Button in/adjacent to each assistant response’s own action row. Captures that answer only; handles streaming completion, edits, regeneration, code blocks, long threads, navigation, and action rows that appear on hover. Toolbar must not accidentally capture another response through a broad `.markdown` fallback. |
| Claude | Same answer-level contract; validate actual current DOM rather than assuming ChatGPT selectors transfer. |
| Gemini | Same answer-level contract, including SPA updates and rendered rich content. |
| Perplexity | Preserve existing support, response scope, and readable citation links. |
| Google Notebook, specifically `https://notebook.google.com/` | Inspect the actual authenticated product and route; support relevant visible chat answers/text notes with scoped controls. Verify canonical/legacy aliases such as `notebooklm.google.com` only when justified. Do not capture the whole notebook/source corpus or audio. Do not grant `*.google.com` merely for convenience. |
| `grok.com` | Per-answer control and exact scope, streaming/rerender handling, code and citation fidelity. Embedded Grok on X is a separately tested route, not automatically claimed. |
| `substack.com/home/post…` | Detail/modal long-form post support without including the surrounding Home feed, comments, or recommendations. Support normal Substack publication article pages; custom publication domains use explicit per-site permission/toolbar fallback. |
| `x.com` posts and articles | Per-post control and dedicated long-post/article detail extraction. Exclude replies, timelines, quoted neighboring content unless structurally part of the selected target, counters, and action labels. A collapsed post produces an honest partial result or asks to open/expand it; do not claim to have captured an unseen article. |
| General web articles | One-click article extraction; optional subtle detected-article icon only on permitted sites with sufficient confidence. Selection/context-menu fallback remains available. |

Match hostnames with exact equality or intended subdomain boundaries, never `.includes()` against arbitrary URLs. Test lookalikes and unexpected protocols. Separate required and optional host access. Explain “Enable Reader buttons on this site” when permission is absent; request it from a user gesture and dynamically register only necessary scripts. Do not require blanket browsing access for manual one-click capture.

Adding provider names also requires checking source-type validators, manifest fields, shared schemas, Android handling, metadata display, and reference fixtures. Do not add a button that silently labels every new provider as a different source or sends data the receiver rejects.

For each supported surface, save a sanitized representative DOM fixture, expected extraction, screenshot of the mounted packaged-extension control, and the exact source text received on Android. Use synthetic content for private AI/notebook pages. A mock fixture is not a live compatibility claim. Record inaccessible logged-in providers separately and leave the universal explicit-selection fallback working.

## 8. Android reader foundation

### 8.1 Data, navigation, and performance

Split screen summaries from full document content. Lists should query lightweight projections and indexed list/time fields; the currently open document should have its own observable state. Avoid rebuilding every list, loading all article bodies, or rereading preferences on every progress update or TTS range callback. Parse off the main thread with a bounded cache keyed by immutable document ID and parser/projection version.

Use lifecycle-aware observable state and saved navigation. Rotation, process death, background return, theme changes, and returning from Share must preserve the active destination, article cursor, review session, and essential settings. A navigation route should not depend on finding a full document in a transient list snapshot. Confirm how hardware/system Back, predictive Back if applicable, nested reader/review navigation, and TTS interact.

Debounce progress writes while guaranteeing a final durable checkpoint at meaningful lifecycle transitions. Use targeted updates rather than copying an old whole document row over newer triage/progress state. Test concurrent sync, highlight creation, list moves, and progress changes for lost updates.

Enforce the same safe content limits through extraction, encryption, assembly, storage, parsing, and rendering. Test large content against CursorWindow/heap limits on supported devices; do not acknowledge an item that cannot subsequently be read. Prefer an appropriate content-storage change over retaining an obviously unsafe advertised limit.

### 8.2 Correct semantic text and rendering

Fix the header/list indexing and within-block restoration. Define one versioned **rendered text projection** mapping every selectable character to stable document/block anchors. Keep a separate narration projection where code/footnotes are intentionally skipped. Store/cache prefix lengths so progress calculations do not repeatedly scan the whole article.

Cover paragraphs, headings, nested lists/quotes, code, tables, link labels, line breaks, Unicode, and repeated text. Preserve the original immutable canonical content; highlights must not alter its hash. If parser output changes, migrate/re-resolve anchors using their saved version and quote context, never silently attach a highlight to the first matching phrase.

Actually parse and render tables with row/cell boundaries. Preserve fenced code/ordered-list numbering. Use safe overflow behavior on narrow screens. The app must render an article, not show raw Markdown syntax as its primary reading experience.

### 8.3 Theme contract

Add `ThemeMode = SYSTEM | LIGHT | DARK`, default SYSTEM for new installations. Provide a coherent Material theme and system-bar appearance across library, reader, pairing, settings, dialogs, sheets, selection, highlights, review, empty/error states, TTS, and RSVP.

Preserve existing explicit PAPER/SOFT/INK/BLACK reader appearance preferences through migration. A simple product model is app theme plus `Reader background: Follow app / Paper / Soft / Ink / Black`; avoid accidentally forcing bright paper at night for a user who selected Follow app. Store preferences reactively, avoid a light startup flash, and do not reset the current reading position when the system theme changes.

Continue the existing typography. Derive surfaces, text, secondary text, controls, highlights, focus rings, and disabled/error states from shared Flexoki semantic tokens. Verify actual contrast, font scaling, reduced motion, keyboard/TalkBack semantics, and touch targets. No color-only success or selection state.

### 8.4 Empty inbox

Once loaded data establishes that Inbox is empty, hide that destination and default the library to Priority. Do not treat the initial loading state as an empty library. Use stable destination keys, not changing numeric indices.

When the last inbox item is moved, preserve the existing undo affordance and transition calmly to Priority. Undo must restore the original item/list even after the selected destination changes. When a new item arrives, Inbox reappears with a subtle count/indicator; do not steal focus from Priority, Later, Highlights, an open article, or review. Add Highlights after Later, before Archive in the destination order. Returning from a child screen restores the user’s prior destination rather than always selecting Inbox.

### 8.5 TTS and RSVP

Implement an actual active-playback speed update, not just a preference save. Preserve sentence/cursor position and apply the speed predictably. Guard against late utterance callbacks after pause, next, previous, closing, or changing documents. Verify audio focus, phone interruptions, headphones, notification/media controls where already supported, and app lifecycle behavior on the physical device.

Do not let per-word/range narration callbacks reload the entire library. TTS/RSVP transitions use the same semantic cursor; verify return to the correct article position and existing speed/typography controls. Do not promise background audio behavior that the current service/lifecycle does not implement.

## 9. Highlight mode and quote-only sharing

### 9.1 Interaction contract

Provide an obvious but quiet highlighter/pen toggle in the reader. In normal reading mode, text selection and existing links remain conventional. In **Highlight mode**, selecting text creates a highlight immediately when a meaningful selection gesture settles, without requiring a second menu choice or confirmation.

Do not save a dozen highlights while the user drags selection handles. Model one selection-edit session: create/update its pending highlight as the range is finalized, commit consistently, and provide one Undo result. Subsequent deliberate selections create new highlights. Preserve word/line selection, cross-paragraph ranges, and scrolling while selecting. Tapping ordinary page chrome must not steal or accidentally commit an unrelated selection.

On tapping a persisted highlight, show a compact contextual sheet/popover with exactly four color choices, Share, and a restrained Remove action. Color selection applies immediately and persists. Include readable labels and a selected indicator, not just colored circles. Keep remove/undo easy without creating a confirmation dialog for every edit.

Suppress link navigation and page-chrome toggling when a highlight gesture owns the event. In normal mode, tapping highlighted linked text should first expose highlight actions; provide a clear route to the link/source without ambiguous double actions. Physical testing must cover Android gesture navigation and large fonts.

### 9.2 Native selection implementation

Implement a short integration spike before committing to the selection approach. Prefer maintained public native APIs compatible with the chosen Compose version. Current official Compose documentation describes selection-state APIs and explicitly warns that uncomposed lazy-layout text is not covered normally. Do not assume wrapping the existing `LazyColumn` in `SelectionContainer` automatically provides reliable cross-document selection.

Choose and record the smallest viable renderer/selection integration that preserves native typography, links, lazy performance, and cross-block highlights. Test real selection offsets and handle movement on the physical phone, including a range spanning recycled/scrolling content. Do not solve selection by secretly reading the system clipboard or rendering the entire library into one huge editable text field. Do not replace the whole app with a WebView merely to avoid understanding selection. If a focused renderer change is necessary, prove its memory and accessibility behavior first.

### 9.3 Persistent model and migration

Introduce a proper Room migration from the existing schema; no destructive migration. Export Room schemas and test the historically supported upgrade paths, especially the installed v4/v5-era data. At minimum persist:

- a unique highlight ID and immutable source document ID;
- exact selected quote text, source title/URL snapshot when available, and creation/update times;
- start and end semantic anchors, rendered-projection/parser version, local offsets with explicitly documented units, and prefix/suffix context for re-resolution;
- color enum, important flag, review metadata, and sufficient state to preserve a review session separately from highlight content.

Use UTF-16 offsets where required by native text layout, but ensure slicing never splits surrogate pairs or grapheme sequences. Keep a mapping to canonical content rather than confusing offsets in Markdown with offsets in rendered text. Cross-paragraph highlights are one logical record with multiple rendered spans if necessary.

Anchor the exact occurrence when text repeats. Resolve by document/version/anchors first and quote/context only as a checked fallback. If resolution becomes ambiguous, retain the quote and show “Source position unavailable”; do not highlight the wrong passage silently.

Define overlap handling deterministically. Recommended beta behavior: identical ranges update/reuse the existing highlight; intersecting/adjacent ranges of the same color may merge only under a tested explicit rule, while differently colored overlaps use a documented non-ambiguous display/action rule. Prefer preserving user intent over clever automatic merging. Test all changes and Undo transactionally.

Deleting an article must not silently destroy saved quotes. Default to preserving quote/source snapshots, and make any cascade deletion an explicit user choice. A highlight with a removed source remains readable/shareable and says the source is unavailable. An explicit “delete everything” action may remove both after clear confirmation. Archive/list moves must not delete or re-anchor highlights.

Extend the existing local export to include highlights and review metadata in documented JSON plus human-readable Markdown where useful. Exports contain no transport keys/QR tokens. Verify round-trip import/restore for any exported recovery format claimed to be restorable; do not label a one-way export a backup without restore support.

### 9.4 Four Flexoki highlight colors

Use **Yellow, Green, Cyan, and Purple** from the authoritative Flexoki palette. Store semantic names, not theme-dependent hex values. Use light tinted backgrounds with dark text in light mode and deep tinted backgrounds with light text in dark mode; do not use saturated accent text colors as opaque highlight fills without checking contrast.

Source the exact token values from `kepano/flexoki`, preserve its attribution/license, and calculate contrast after alpha compositing if transparency is used. Require at least 4.5:1 for ordinary highlighted text and verify meaningful differentiation on both themes. Share the same color semantics between in-article spans, the feed, and review; do not create another palette.

### 9.5 Share contract

Use Android’s native Sharesheet via an appropriate `ACTION_SEND` intent with `text/plain`. `EXTRA_TEXT` is **only the exact selected quote text**: no title, source URL, app advertisement, markup escapes, enclosing quotation marks added by the app, whole article, or surrounding paragraph. Do not auto-send into WhatsApp, Telegram, or any social account; the user chooses the target and confirms within it.

Verify with a test receiver that the payload is exact, including Unicode and line breaks, and verify the Sharesheet visually. A user-cancelled share does not count as a completed external share or discard the highlight. Consider Android binder/intent limits: bound or gracefully handle unusually huge selected quotes without crashing or silently truncating them.

## 10. Highlights feed and focused review

### 10.1 Feed and navigation

Add **Highlights** after Later. This is a feed of saved quote cards, not a document list. Each card shows the quote, a small source attribution, and an important indicator; the quote remains visually primary. Preserve long text without unreadable shrinking. Use lazy loading/projections and stable keys.

Offer `Shuffle` and `Newest` ordering. Default Shuffle is stable for the current session; recomposition, theme changes, returning from an article, or another incoming document must not reshuffle everything. Newest uses a stable time/ID tie-break. A visible Review action resumes the existing session or starts one; tapping a card opens that card in the full-screen text review experience.

### 10.2 Review gestures

Display one quote at a time on a calm full-screen text card. “Reels/Stories” means focused one-item navigation—not video, autoplay, attention tricks, or social metrics.

- **Swipe right to left:** advance to the next quote.
- **Swipe left to right:** toggle Important on the current quote; do not navigate backward or advance. Repeating the gesture unmarks it. Give a restrained visible/haptic confirmation that respects device settings.
- **Tap the quote:** open the source article at the exact highlight, briefly emphasizing that span. Back returns to the same review card/session.
- Provide explicit accessible Next, Important, Open source, Share, and Close actions. Gestures are accelerators, not the only way to use the screen.

Avoid reserving screen-edge swipes that conflict with Android Back. Long quotes scroll vertically; horizontal navigation must not fire from ordinary vertical reading movement. Test RTL text/direction, TalkBack, large text, rotation, 0/1/2 quotes, missing source, deleted quote, and rapid alternating gestures.

Do not mark a quote reviewed because its offscreen card was composed or prefetched. Record review on an intentional advance/open-source from the actually presented card; closing on a card leaves it resumable. An Important toggle alone must not pretend the user reviewed another item.

### 10.3 Deterministic local ordering algorithm

Do not build a machine-learning recommendation system. Implement this exact transparent beta policy as a pure, seeded, property-tested scheduler with durable state:

1. A cycle begins with a snapshot of eligible highlight IDs and a Fisher–Yates shuffle. Every eligible quote receives one **base-pass** presentation before any importance bonus is scheduled. Persist the seed, membership/remaining IDs, current ID, phase, and last presented ID.
2. New unreviewed highlights arriving during a cycle are inserted into the remaining base pass at seeded positions, without replacing the current card or reshuffling already reviewed items. Deduplicate membership. If the base pass is already finished, queue new items before remaining bonuses or for the next cycle under one documented deterministic rule; choose the former for beta.
3. Once base coverage is complete, schedule an **importance bonus pass**, containing at most one additional presentation of each still-important highlight. Thus an important item may appear twice per cycle, an ordinary one once. No item may appear twice consecutively when there is another eligible item.
4. Reorder or defer a bonus that would equal the last presented item; if no different candidate exists, show “You’re caught up” instead of immediately repeating the same card. Handle a single-highlight collection explicitly. At a new cycle boundary, also avoid repeating the previous last item when another item exists.
5. Marking Important updates the database immediately and can add one still-unconsumed bonus for this cycle after base coverage. Unmarking removes any pending bonus. Toggling repeatedly cannot mint unlimited bonus tickets. Never duplicate a base entry.
6. Deleted highlights are pruned safely. Article deletion does not remove a retained quote. Resume across process death without replaying the same completed transition; commit scheduler advancement and review metadata consistently.
7. At the end, show a quiet completion state with an explicit Continue/Shuffle again option. No streaks, artificial urgency, infinite automatic replay, or opaque engagement scoring.

**Interpretation:** universal “all other quotes before every repeat” would force equal frequencies and conflict with “important more often.” The base pass guarantees full coverage; the bounded important bonus is the explicit exception. This preserves the user’s learning objective without hiding that tradeoff. Feed ordering and review scheduling are distinct: Newest determines browsing order, while the review mode uses the above policy.

Property tests must prove base coverage, no unintended duplicate membership, no adjacent repeats when avoidable, bounded importance weighting, non-starvation for a finite collection, deterministic restoration, and correct updates under new/deleted/toggled highlights. No backend or clock-dependent engagement model is needed.

## 11. Verification and red-team campaigns

Tests must use the **real final application paths**, not only hand-built payload constructors or source-regex assertions. Use the local harness for hostile load and controlled faults; use cooperative public relays only for bounded synthetic interoperability. Record all attempts, including failures and retests. A count such as 50/50 is an observed campaign result, not proof of 99.9% long-term reliability.

### 11.1 Required behavioral regressions

| Area | Minimum scenarios and required outcome |
|---|---|
| Durable capture | Message duplicate/lost response, worker killed around commit, quota failure, page navigation, unpaired device: no false saved claim and no loss after a committed capture. |
| Scheduling | Repeated worker restarts/unrelated one-minute wakes before a due retry, sleep/wake, browser restart, extension reload/update, no open DevTools: pending work advances and earlier deadlines are not continually moved. |
| End-to-end identity | Tampered/fake/wrong-device ACK, wrong manifest/document/recipient, expired payload: never delivered; retained content remains recoverable. |
| Fragment recovery | Manifest first/last/missing, reversed/mixed/duplicate chunks, interrupted assembly/publish, slow redundant relay, conflicting metadata: correct single document or explicit pending/failure, never corruption. |
| Lost ACK after quorum | Relay accepts ACK then deletes/loses it before Chrome sees it: authenticated retransmission yields a bounded fresh receipt, not permanent limbo or a storm. |
| Catch-up | >64 and >256 events, equal timestamps, randomized timestamps, relay caps, repeated pages, restart mid-scan: recover available valid ACKs; expose incomplete coverage instead of silently skipping. |
| Resource attacks | Large individual frames, many small frames, deep JSON, tag floods, invalid signatures, unknown trusted-sender candidates, repeated valid wrappers, gzip bombs, expired staging: bounded memory/CPU/disk, fair healthy-relay progress, no crash. |
| Connectivity | Zero/one/quorum relays, OK false, no OK, AUTH success/failure/changed challenge, CLOSED, TLS/DNS failure, network switch/captive portal/offline: truthful typed outcomes and recovery. |
| Pairing lifecycle | Crash before/after response authentication, ACK, completion, key provisioning, database promotion; cancelled/expired QR; wrong relay digest; replaced/lost identity: no ghost connected state or trust downgrade. |
| Source capture | One-word/Unicode/literal-code selection; each provider; restricted page; low confidence; streaming; SPA/virtualized node reuse; permission denial: exact intended scope and useful feedback. |
| Reader fidelity | Titles/header indexing, long paragraph offsets, nested lists, table structure, code, links, repeated text, font change, TTS/RSVP transition: correct reading/anchor location. |
| Highlight behavior | Drag/adjust selection, cross-paragraph selection, recolor, overlap, undo/remove, source deletion, rotation/process death, quote-only share: exact durable quote/range and no accidental side actions. |
| Review | Pure scheduler properties plus real gestures, long quote scrolling, Back, new items, importance toggles, return from source/share: durable correct ordering and navigation. |
| Migration | Installed-era schema through current; preserved articles/keys/preferences/progress/triage; new highlights and exports: no destructive reset, no silent data loss. |
| Security surfaces | Host lookalikes, untrusted runtime messages, content-script key reads, unsafe URLs/redirects, remote media disabled, secret-bearing logs: permission/identity boundaries hold. |

### 11.2 Physical/browser reliability counts

After deterministic defects are fixed, run and retain the following campaign outcomes on the final candidate. Automate safe repetition in isolated QA contexts; do not repeatedly destroy the owner’s actual pairing/library.

- **20/20 authenticated pairings**, with both endpoints agreeing, no phantom active device, and an explicitly recorded mix of default/custom-relay conditions.
- **50/50 synthetic deliveries** through the actual packaged extension capture path to Android, including short selection, article, provider-style rich response, and multi-chunk documents. Verify content hash and single-copy storage, not just title/UI presence or relay OK.
- **20/20 offline/recovery cases**, with deliberately interrupted connectivity and resumed access while the transfer remains valid; record which endpoint was offline and the recovery condition.
- **20/20 replay/duplicate cases**, preserving the document, highlights, and reading state and respecting ACK refresh budgets.
- **20/20 in-flight worker terminations**, with non-empty queues and interruption points before/after local commit and during publication/ACK handling—not just 20 empty-outbox restarts.
- **20/20 same-profile browser restart cases** with retained pending work, separate from worker-only termination. Include Android process interruption around document commit/ACK intent.

Use public relays only for a modest, paced representative portion of these campaigns; run the remainder against controlled relays and label the environments separately. Do not represent 50 controlled-relay cases as 50 public-network successes. Preserve failed attempts; a later 50-success rerun does not erase earlier failures.

Physically cover screen-off, battery saver, Doze, Wi-Fi/mobile switching where available, ordinary process death, rotation, reboot, and user force-stop followed by reopen. Restore changed device/network settings. A force-stopped app not receiving until reopen is a documented platform state, not a reason to fake a wake guarantee. A missing transport/status recovery after reopen is a bug.

Test in-place upgrade on the owner’s preserved installation only after isolated migration tests pass. Test minimum-supported Android, a current target, and 16-KiB runtime where relevant. No final-hash instrumentation claim without installing the matching app/test artifacts on the named target and recording the result.

### 11.3 UI and performance evidence

Capture real screenshots and focused short recordings of the packaged extension and installed app: paired/unpaired, saved/pending/delivered/error, each requested site button, System/Light/Dark, empty-Inbox transition/undo, article selection and recoloring, quote Sharesheet, feed, review gestures, and return-to-source. Inspect the images; do not merely generate screenshot files.

Use controlled synthetic corpora: ordinary ~100-KiB text, multi-chunk content, upper supported-size content, a 1,000-document summary library, and 10,000 lightweight highlights. Keep these local. Measure smaller and larger corpora separately so loading a deliberately extreme fixture does not obscure everyday behavior.

Report before/after click acknowledgment, durable-save latency, capture-to-phone-store latency, ACK settlement latency, cold/warm app start, article-open time, frame/jank data during reading/review, peak/steady memory, connection count, bytes, and idle/background wake behavior. Segment foreground delivery from OS-scheduled background delivery. Use p50/p95 and sample counts when justified; use explicit single-run labels otherwise.

Use Perfetto/gfxinfo for UI timing, Simpleperf for CPU where available, and meminfo/heap analysis for retention. CPU samples do not measure network wait. Emulator timings are not physical-device battery/performance claims. A heap snapshot is not permission to commit private article text. Retain redacted summaries or synthetic-only evidence.

Working product budgets: ordinary click feedback <=100 ms, local save <=500 ms on the reference Mac; foreground controlled-relay delivery p95 <=5 seconds for ordinary content; no repeated >100-ms main-thread work for ordinary article interaction; smooth review at the device refresh budget. Investigate misses and report actual results. Do not weaken correctness or omit difficult samples to hit a number. Larger documents get explicit progress and measured limits rather than the same latency promise.

## 12. Release discipline and final deliverables

Keep package versions aligned for the beta and increment Android versionCode for safe upgrade. Default to `0.9.0-beta.1`; adapt Chrome’s numeric manifest version appropriately and use a display/version-name suffix where supported. Do not market an unverified release as 1.0.

Add or maintain reproducible CI checks for static analysis, unit/fault tests, extraction fixtures, Android JVM/lint/build, schema migrations, and reference-contract tests. Physical and login-gated jobs are separately documented, not silently pretended in CI. Preserve lockfiles, licenses, attribution, and a clean build from a fresh checkout. No generated secrets or local profiles enter the repository.

Produce:

1. **Android app APK and matching test APK**, with exact SHA-256 hashes, package/version/signature identity, source commit, tested device/OS, installation verification, and final-hash instrumentation results. Preserve the owner’s data on the final installation.
2. **Chrome extension ZIP and unpacked directory**, verified to contain the same built worker/content/UI bytes that were tested. Test the packaged output, not only the dev source tree. Include installation/update instructions and minimum browser version.
3. **`BETA_TEST_REPORT.md`**, requirement-by-requirement outcomes, counts including failures, exact environments, final artifact hashes, evidence paths, performance measurements, and live-site coverage.
4. **`BETA_AUDIT_RESOLUTION.md`**, disposition of R01–R18 plus new defects found, their fixes/tests, and any residual risk. Separate confirmed fixes from disproved suspicions and unmeasured behavior.
5. **Updated `KNOWN_LIMITATIONS.md`, `PROTOCOL.md`, security/privacy notes, source-site support matrix, migration/export documentation, and `AI_CONTINUATION_CONTEXT.md`.** Do not copy forward resolved historical failures as current limitations or remove untested caveats just because code compiles.
6. **`ARTIFACTS.json`** with source/build identifiers, hashes, build/tool versions, app/test package relationship, and links/paths to reports. Avoid the impossible requirement that a commit contain its own future hash; separate tested source commit from later documentation-only commits explicitly.
7. **A short nontechnical handoff** explaining what now works, how capture status behaves, how to highlight/review/share, where the tested downloads are, and what remains genuinely blocked.

Freeze application code before the final candidate gate. Rebuild reproducibly, install the exact app/test pair, exercise real capture-to-phone-to-ACK on that candidate, verify the extension bytes, and repeat any affected gates after a subsequent application change. Installing the app alone is not instrumentation; an earlier APK test is not a final-hash test; relay acceptance is not phone delivery.

A beta-ready conclusion requires no unresolved P0 core-path defect, all requested main features working, safe non-destructive migration, truthful states under failure, and evidence for the tested artifact. Inaccessible optional signers/sites or device modes must remain explicitly scoped. Deliver completed work even when an external gate is blocked, but label the result a candidate with that limitation rather than declaring the whole assignment passed.

## 13. Authoritative references and update rule

Repository source of truth starts at the pinned commit above. Read the current branch and shared schemas before adopting any change. For external technical decisions consult primary references, pin dependencies, and record the relevant date/revision:

- Chrome alarms: `https://developer.chrome.com/docs/extensions/reference/api/alarms`
- MV3 lifecycle: `https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle`
- Nostr specifications: `https://github.com/nostr-protocol/nips` — NIP-01, NIP-40, NIP-42, NIP-44, NIP-59; use signer NIPs only for their optional provenance paths.
- Android work requests: `https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work`
- Compose selection: `https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/selection/SelectionContainer.composable`
- Android page-size compatibility: `https://developer.android.com/guide/practices/page-sizes`
- Flexoki: `https://github.com/kepano/flexoki`

Do not optimize for producing the largest diff or the most impressive test count. Optimize for a small, coherent product that a person can trust without thinking about relays, worker lifetimes, or encryption internals. Start with the source and baseline, then execute this brief through tested delivery.
