# Nostr Reader — independent red-team audit

**Review date:** 5 September 2026  
**Repository:** `mariusschober/nostr-reader`  
**Reviewed branch:** `fix/pairing-delivery-hardening-982920b4`  
**Reviewed commit:** `0c9884e955558f478d7668b46e9c1411bfe95c5d`  
**Baseline / main at review:** `982920b4e91dd5af4af6046f56df281c7adfe545`  
**Recommended next target:** `0.9.0-beta.1`, subject to the acceptance gates in the companion execution prompt.

## Executive judgment

The core idea is technically viable, and the repair branch contains a working Chrome-to-Android path rather than just a prototype interface. The previous repair substantially improved authentication, encryption framing, durable storage, and the distinction between relay acceptance and an authenticated phone acknowledgment. Preserve that work. Do not replace Nostr or redesign the application around a proprietary backend.

However, this is not yet a release-proven beta. The remaining problems are not merely cosmetic. The code contains a retry-scheduling defect that can postpone queued work repeatedly, incomplete acknowledgment recovery, unbounded receive buffering, silent capture failures, and incorrect reading-position mapping. These affect the central promise: “I saved this; it will be on my phone; I can trust where I left off.” Adding attractive highlights before correcting those foundations would make the product look more finished than it is.

The most valuable UX improvement is truthful, immediate feedback: acknowledge the click immediately, confirm a durable local save, and reserve “On your phone” for a verified phone storage acknowledgment. Keep the toolbar’s one-click behavior. Put settings, transfer history, retry, and repair in a discoverable options page rather than adding an obligatory popup to every capture.

The requested highlights and review experience belong in this next stage. They should be local, offline, and grounded in stable text anchors, not a new synchronization or recommendation platform. A small, deterministic shuffle-and-importance scheduler is enough. Dark mode, state restoration, proper tables, and working TTS speed control also materially improve daily use.

**Release decision:** pursue the 0.9 beta. Do not label it 1.0 based on unit-test totals or one successful transfer. The exact packaged extension and installed APK must pass recovery, capture, migration, and physical-device tests together.

## Method and evidence boundary

This was a read-only review through the GitHub connector, pinned to the commit above. The branch pointer and main pointer were resolved independently. Inspection covered Chrome capture, extraction, provider adapters, manifest/options, the service worker, transport, protocol helpers, Android transport/sync/assembly/storage, article parsing/rendering, reader navigation, preferences/themes, TTS control, and selected tests. The continuation document, test report, known limitations, and selected retained installation/transfer evidence were also read.

**No new builds, automated tests, browser interactions, physical-device tests, network fault experiments, benchmarks, or screenshot-based UI inspection were performed in this audit. No repository changes were made.** The code findings below identify concrete mechanisms; their actual incidence and measured cost require the local execution stage. This is not an exhaustive security certification or a claim that no additional defects exist.

Evidence labels:

- **Confirmed source defect:** the problematic code or missing path is directly visible. This does not imply a fresh runtime reproduction.
- **Source-supported failure scenario:** the mechanism follows from code and a stated lifecycle/network condition; reproduce before changing it.
- **Verification gap / improvement:** a missing assurance, requested capability, or performance opportunity—not a demonstrated exploit.

Priority is product-release priority, not a CVSS score. **P0** blocks a trustworthy beta of the core transfer path. **P1** must be fixed or explicitly bounded before the requested feature-complete beta. **P2** is a measured optimization or secondary assurance item.

## What the existing evidence actually establishes

The retained seven-relay transfer record establishes one observed synthetic-document path, duplicate suppression during that run, phone ACK publication, and Chrome’s outbox changing from one pending item to zero. It explicitly does not establish the 50-transfer campaign. Its note about repeat ACK publication is historical and was subsequently addressed by the durable ACK ledger; it should not be misreported as the current implementation. [E2]

The final TCL installation record establishes that the installed APK matched SHA-256 `4c555d6d8e53784b3d7a5c00a7e85df671f3f5abede1923da5766573616cea5e`, was installed without clearing app data, and cold-started. It explicitly excludes matching final-test-APK instrumentation and a new public transfer of that final build. [E1]

The repository reports 101 Chrome tests, an Android 107-task build/test/lint gate, reference Rust/Swift tests, and reproducible rebuilds. **107 Gradle tasks is not 107 Android tests.** These are retained results, not tests rerun in this audit. Larger physical pairing, transfer, offline/replay, and lifecycle campaigns remain unmeasured in the handoff. [E3, E4]

## Finding register

| ID | Priority | Finding | Evidence type |
|---|---|---|---|
| R01 | P0 | Worker startup replaces retry alarms and can starve automatic retries | Source-supported failure scenario |
| R02 | P0 | Capture can fail silently or show a success check on an error response | Confirmed source defect |
| R03 | P0 | Receive buffering is unbounded across events; relay failure can resemble an empty healthy sync | Confirmed source weakness |
| R04 | P0 | ACK catch-up uses a fixed 64-event query without pagination | Source-supported failure scenario |
| R05 | P0 | Completed ACK publication is treated as retained until expiry; later ACK loss has no recovery | Source-supported failure scenario |
| R06 | P1 | Short-selection and literal-text behavior differs by capture entry point | Confirmed source defect |
| R07 | P1 | Provider buttons are fragile under SPA replacement and capture scopes are too broad | Confirmed mechanisms; live-site root cause unverified |
| R08 | P1 | Transfer loops reopen relay connections and lack durable fragment-level progress | Confirmed architecture; performance unmeasured |
| R09 | P1 | Android delays ingestion until collection windows finish and lacks a sustained visible-app receive path | Confirmed source behavior |
| R10 | P1 | Reading-position mapping is off by one and ignores restoration offsets | Confirmed source defect |
| R11 | P1 | Tables lose structure in Android parsing; provider conversion also loses formatting | Confirmed source defect |
| R12 | P1 | Library/body reloads and main-thread parsing scale poorly | Confirmed architecture; impact unmeasured |
| R13 | P1 | Extension options and recovery actions are insufficiently discoverable | Confirmed source gap |
| R14 | P1 | TTS speed changes do not reach the active controller | Confirmed source defect |
| R15 | P1 | Requested theme, highlight, review, and empty-inbox behaviors are not complete | Feature gap |
| R16 | P1/P2 | Crypto/profile validation and key handling need targeted assurance—not a new cipher | Mixed hardening and optimization |
| R17 | P1 | Several recovery tests inspect source strings rather than execute recovery | Confirmed assurance gap |
| R18 | P1 | Documentation, privacy behavior, compatibility, and release evidence need reconciliation | Verification gap |

### R01 — Retry alarm starvation

**Where:** `chrome/src/background/service-worker.ts`, top-level alarm creation and startup/retry handlers. [S1]

The worker creates the same named 15-minute retry alarm and one-minute pairing alarm at every evaluation. Chrome documents that creating an alarm with an existing name replaces it; without another initial delay, a repeating alarm’s period supplies the first delay. A sleeping worker repeatedly awakened by the one-minute alarm can therefore keep moving the 15-minute retry deadline into the future. The code’s shorter exponential retry timestamps do not themselves schedule execution. [P1]

This is a strong liveness defect, not proof that every currently observed send fails: immediate publishing still works, and a worker kept alive behaves differently. Reproduce the ordinary sleeping-worker case with a real queued transfer and no open DevTools. Fix scheduling from durable due times, never postponing an earlier valid wake, and stop unnecessary pairing wakes when no pairing is active. Browser startup must recover pending outbound work, not only pairing and ACK reads.

**Acceptance:** repeatedly terminate the worker and generate unrelated wakeups before the retry deadline. The persisted transfer still receives a retry within the permitted OS scheduling window. Sleep, restart, and extension update must preserve the same eventual recovery behavior.

### R02 — False and missing capture feedback

**Where:** `chrome/src/content/capture.ts`; worker capture/action/message handlers. [S2, S1]

Toolbar capture fires messages without consuming their outcome. The inline button awaits a response but changes to a green check without testing `response.ok`. A resolved `{ok:false, error:...}` therefore looks successful. The content script also emits `reader-low-confidence`, for which the inspected worker has no corresponding handler. Restricted-page/injection failures are swallowed rather than exposed to the user.

These are different states that must not be collapsed: click accepted, extraction succeeded, local outbox committed, relay accepted, phone stored, retryable failure, and unrecoverable/expired transfer. A tiny visual acknowledgment can be immediate; a storage claim cannot precede the storage transaction.

**Acceptance:** force extraction failure, quota failure, a resolved error response, an unpaired device, zero reachable relays, partial acceptance, and a delayed ACK. Each has the correct visible state, with no false “delivered” check and no silently dropped capture. Navigating away must not cancel an already committed capture.

### R03 — Unbounded receiving and misleading empty results

**Where:** `RelayClient.subscribe` in Android `nostr/NostrCodec.kt`; `sync/SyncWorker.kt`; Chrome query accumulation in `nostr/transport.ts`. [S3, S4, S5]

Android collects events in a synchronized list until the collection timeout. A 512-KiB individual-frame check does not bound the number of frames, total resident bytes, or crypto work. A hostile relay can send many individually acceptable frames. Chrome’s event-size filtering happens after its query has accumulated results, so this also needs inspection at the actual receive boundary.

Android subscription failure can return an empty list without a typed failure outcome. If no pairing/ACK work remains, the worker can report success even though it did not establish a healthy receive session. Blocking waits and broad exception handling also require cancellation tests to ensure sockets and pending callbacks are released.

Use bounded streaming intake, per-relay fairness, early structural limits, explicit timeout/AUTH/CLOSED/failure outcomes, and cancellable connection ownership. One malicious relay must not monopolize all memory or exclude healthy relays. Persist partial work before yielding; do not mistake a resource cap for complete history.

**Acceptance:** locally flood duplicate, malformed, oversized, and valid-looking untrusted events; terminate the worker while blocked; fail every relay. Memory and work remain bounded, valid traffic on another relay still progresses, and the UI reports failed/degraded sync rather than “nothing new.”

### R04 — ACKs can fall outside the catch-up result window

**Where:** worker `queryPairingEvents` reused by ACK catch-up. [S1]

The query has `limit:64` and no continuation. A sufficiently busy retained history can hide a valid ACK behind newer matching events. Repeating the same bounded query does not necessarily reveal it. NIP-01’s historical result limit and randomized NIP-59 timestamps make a naïve “last event time” cursor unsafe. [P2, P3]

Implement bounded, resumable coverage with inclusive time-window overlap, deduplication, and explicit handling of equal timestamps and relay-imposed caps. If a relay cannot supply a complete saturated timestamp bucket, mark the result incomplete and use other relays/recovery—not a claim of complete enumeration. The protocol cannot force an uncooperative relay to provide missing data.

**Acceptance:** place the desired ACK behind more than 64 and more than 256 matches, with random and identical timestamp groups, relay truncation, and restarted scans. Recover the ACK when an authorized healthy relay retains it. Otherwise preserve the outbox and expose the unresolved state.

### R05 — The “ACK accepted forever” assumption breaks recovery

**Where:** `TransferManager.queueAckIntent`; ACK DAO due queries and ACK publication state. [S6, S7]

After the configured ACK publication quorum completes, later authenticated copies of the same transfer do not reopen ACK publication. The comment assumes that an accepted ACK remains relay-retained until the transfer expires. That is not a safe availability assumption: accepted events can become unavailable through eviction, relay loss, or changed policy. Expiration is not a storage service-level guarantee. [P4]

A failure scenario is: phone stores the document; relays accept its ACK; Chrome is offline; the ACK disappears; Chrome retries the same immutable transfer; Android recognizes the document but declines to send another ACK because publication was already completed. The phone has the article while Chrome can remain pending until its retry ceiling.

Keep duplicate suppression. Add a bounded, demand-triggered ACK refresh on an authenticated retransmission, with a durable cooldown, transfer expiry, and explicit overall budget. Identical retained wrappers must not restart an ACK storm. A new authenticated demand must not be ignored forever merely because an earlier relay once returned OK.

**Acceptance:** delete only the accepted ACKs from local test relays before Chrome receives them; keep the document and transfer valid. A sender retransmission causes one bounded recovery acknowledgment, Chrome settles, and the document remains single-copy with its reading state unchanged.

### R06 — Explicit selections are not consistently authoritative

**Where:** `extraction/pipeline.ts`, content capture, worker context-menu handler. [S8, S2, S1]

Toolbar selection is considered meaningful only at 80 characters or 12 words. A shorter deliberate selection can fall through to capturing an article or AI answer. The context-menu route rejects very short text and does not use the same literal escaping/source metadata path as the toolbar.

Honor every non-whitespace explicit selection, including one word. Use one literal-text conversion function across entry points. Preserve the source URL/title without silently expanding the selection. Keep literal selected code distinguishable from an intentionally imported Markdown document.

**Acceptance:** one word, punctuation, emoji, CJK text, multiline code, Markdown symbols, and whitespace-only input behave consistently across toolbar, context menu, and shortcut. No extra page content enters an explicit selection capture.

### R07 — Fragile provider adapters and missing platforms

**Where:** `providers/adapters.ts`, content-script mounting and observer, manifest host declarations. [S9, S2, S10]

Only the existing four AI provider families are implemented. Substring hostname matching can classify lookalike hosts incorrectly. Broad fallback `.markdown` matching is not an assistant-role guarantee. Mounting beside the first found button can select a code-copy toolbar rather than the response action row. A persistent `data-reader-mounted` flag can suppress reinsertion after a SPA removes the injected button but retains the response node. Repeated whole-document scans and handler lifetime also need attention.

These mechanisms explain credible missing-button cases, but this review did not inspect the current logged-in ChatGPT DOM and cannot identify which one caused the user’s exact observation.

Build small adapters with separate host, response-root, content-root, mount-target, readiness, and extraction responsibilities. Reconcile actual mounted elements, not just a flag. Use exact host boundaries, stable response identities, incremental observation, and cleanup. Preserve visible text, code blocks, lists, tables, links, and citations through a shared converter.

Add verified support for Notebook, Grok, Substack post/detail pages and publication domains, and X post/article detail views. `notebook.google.com` currently reaches Google sign-in in this review; its authenticated DOM was not available. Verify final hosts and any aliases locally rather than assuming the supplied address is wrong or granting all Google domains. [P5]

**Acceptance:** each adapter has realistic sanitized DOM fixtures, a packaged-extension test, streaming/re-render/navigation cases, and a live visual check when access exists. The send icon captures exactly its associated answer/post—not the feed, adjacent replies, hidden account content, or a different assistant response. Login-gated tests remain explicitly unverified when access is absent.

### R08 — Connection and retry amplification

**Where:** worker `queueCapture`, `publishFreshPayload`, `publishTransferInternal`; transport publishing. [S1, S5]

The sender starts with 24-KiB compressed chunks, creates new relay pools for individual payload publication, and processes payloads sequentially. At the allowed 5-MiB compressed maximum, there are 214 chunks plus one manifest. With six relays, the code structure can lead to **1,290 relay connection attempts per complete attempt**, before retransmissions. This is a source-derived upper-size model, not a measured typical article cost.

Progress is not durably checkpointed at the fragment granularity throughout the loop. A worker repeatedly interrupted during a long transfer can redo early work instead of progressing to later chunks. The per-transfer serialization lock spans network operations, delaying settlement of a valid ACK behind slow redundant sends. Detailed transport outcomes are not fully retained in user-facing per-transfer state.

Reuse bounded connections within a work session, checkpoint fragment/relay coverage, enforce global concurrency budgets, and yield resumably. Keep network work outside long critical sections while preserving the anti-resurrection guarantee through durable state/version checks. A validated phone ACK may settle delivery even when a redundant relay is failing.

**Acceptance:** kill the worker halfway through a multi-chunk transfer; later attempts progress rather than indefinitely replaying its prefix. Slow relays do not hold a completed transfer hostage. Measure connections, bytes, crypto CPU, wakeups, and delivery latency before/after.

### R09 — Android receive latency is avoidably high

**Where:** `SyncWorker.kt`, `RelayClient.subscribe`, `MainActivity.onResume`. [S4, S3, S11]

The worker waits for parallel collection windows, then processes returned events. Fast arrivals wait for batch collection to finish rather than being committed immediately. Periodic background work is configured at 30 minutes; opening/resuming triggers work, but the inspected UI does not maintain a receive subscription for the entire time the app stays visible.

Separate a visible-app fast path from background catch-up, sharing one authenticated intake pipeline and connection owner. Ingest on arrival. Retain WorkManager for battery-respecting eventual background recovery, with a manual sync action and truthful status. WorkManager is inexact; it does not provide guaranteed immediate delivery while the app is closed or force-stopped. [P6]

**Acceptance:** leave Reader visibly open after its initial sync window and send another article. It arrives without navigating away/back. Test foreground, background, screen-off, Doze, and user force-stop as separate categories; never blend their latency claims.

### R10 — Position bugs must be fixed before highlights

**Where:** `ui/screens/ReaderScreen.kt`, `ArticleRenderer.kt`. [S12, S13]

The lazy list includes a title/header item before the article blocks. Cursor reporting indexes directly into `blocks` using the lazy-list item index; restoration uses the block index as the lazy-list index. Both omit the header offset. Saved character offsets are not used to restore the within-block position. Nested lists/quotes also do not propagate the same layout callbacks as ordinary paragraphs.

Introduce an explicit mapping between rendered items and semantic text ranges. Store parser/projection versions and stable anchors. Restore the position after layout, including character offset; compute progress from a precomputed rendered-text map. Avoid treating narrative text, Markdown source, UTF-8 bytes, UTF-16 offsets, and grapheme boundaries as interchangeable.

**Acceptance:** every block type, long paragraphs, nested lists, repeated quotations, emoji, combining marks, rotation, font changes, TTS/RSVP switching, process death, and “open source from highlight” all map to the correct text.

### R11 — Formatting fidelity is incomplete

**Where:** `core/ArticleModel.kt`, provider HTML conversion. [S14, S9]

Although the Android model and renderer define table types, the parser does not construct an `ArticleBlock.Table` for a parsed table block. The fallback recursively concatenates text without preserving cell/row structure, which can make a comparison table misleading. Provider extraction separately flattens code/list/emphasis semantics instead of using a complete shared HTML-to-Markdown pipeline.

Fix extraction and rendering together, with end-to-end golden documents. Render actual tables, preserve fenced code and ordered lists, and distinguish the reading-text projection from the narration projection that intentionally skips code. The highlight anchor map must target what is rendered, not the reduced narration text.

**Acceptance:** a synthetic AI answer containing a table, code, nested lists, mathematical notation as visible text, citations, and emphasis remains intelligible and correctly scoped on Android. Do not claim full mathematical typesetting unless implemented and tested.

### R12 — Whole-library work is coupled to reading progress

**Where:** `ReaderDb.kt` document DAO; `MainActivity.refresh` and reader composition. [S7, S11]

Lists load full document rows including Markdown. Refresh fetches all list bodies, counts, channels, and preferences. Progress/TTS updates can cause more refresh work; article parsing is also invoked from composition. This creates avoidable memory and UI-thread pressure as the library grows. A transport allowance of 20 MiB expanded text is not evidence that large SQLite rows and their rendered UI work within every supported device’s limits.

Use lightweight list projections, indexed queries, individual-document observation, background parsing with bounded caching, and debounced/targeted progress updates. Validate storage and renderer limits end to end. If the existing row design cannot safely handle the advertised limit, change the content storage or enforce an honest supported bound before confirming storage to the sender.

**Acceptance:** compare a small library with 1,000 documents/10,000 highlights using lightweight fixtures, and test upper-size documents separately. Record memory, query volume, frame timing, opening latency, and progress persistence. No numerical performance claim is established by this audit.

### R13 — Settings and recovery are not a coherent user path

**Where:** manifest and options HTML. [S10, S15]

An options document exists, but the inspected manifest does not declare `options_ui` or `options_page`. Options shows aggregate delivery counts rather than a useful item-level recovery history. Removing all failed transfers is not a substitute for retrying/exporting one valuable capture. Disconnect wording also needs to distinguish never-sent captures from transfers immutably bound to an old device.

Register and expose options, keep the action one-click, add context-menu/keyboard discoverability, and show recent captures with explicit states and actionable errors. Recover a failed/expired capture as a new authenticated transfer where necessary; do not silently rewrite a previously published transfer’s recipient or hashes.

**Acceptance:** a new user can find pairing, settings, permissions, recent sends, and retry without knowing an internal extension URL. A device repair preserves saved content and clearly explains which items need an explicit resend.

### R14 — TTS speed UI does not change active playback

**Where:** `MainActivity` TTS speed callback; `tts/TtsController.kt`. [S11, S16]

The callback saves the new preference and rereads the existing controller state. The controller exposes no speed-update method and continues using its state’s old speed until another load. Add a real speed transition, keep cursor/queue position, and verify audibly on the phone. Also test pause/next/previous against delayed old utterance callbacks; that race is an additional test target, not a reproduced defect here.

### R15 — Requested reader capabilities need a shared foundation

**Where:** preferences, tokens, inbox, reader, database. [S17, S18, S19, S7]

Manual PAPER/SOFT/INK/BLACK colors already exist. What is missing is a coherent system-following theme contract, not all dark colors. Preserve those reader choices while adding application Light/Dark/System behavior across screens and system bars.

Inbox is always rendered and initially selected. Use stable destination identifiers when hiding it; never switch a person away from Priority/Highlights just because a new item arrives. Capture the previous list in undo actions rather than reading a mutable current tab later.

Highlights must be separate records referencing immutable document content. Auto-highlight is a distinct reading mode; normal selection still works. Store the exact selected text and robust anchors, offer four Flexoki hues, and share only the quote. Review must be offline, persistent, accessible, and non-repetitive. The companion prompt specifies the data model, gestures, ordering rules, deletion/export policy, and edge cases.

### R16 — Targeted cryptographic assurance

**Where:** transport/unwrapping on both platforms; Android `Nip44.kt`, `Secp256k1.kt`; Chrome `randomSeckey`. [S3, S5, S20, S21]

The inspected code uses the right basic layering: NIP-44 encryption, kind-13 seals, kind-1059 gift wraps, signed inner-sender verification, and durable authenticated delivery acknowledgment. No practical cipher break was demonstrated in this review.

Several bounded improvements are justified:

- Enforce the Reader rumor profile consistently: expected kind, tags, timestamp and field types, not only a valid hash/sender and protocol string. Reject an unexpected authenticated seal sender before doing unnecessary inner decryption where safe.
- Treat Android’s custom BIP-340 orchestration over BouncyCastle/BigInteger as a maintenance and side-channel-assurance question. Test vectors do not prove constant-time behavior. Compare a maintained library route before changing the implementation; do not migrate merely to appear more secure.
- Remove redundant point multiplications only under cross-runtime vectors and benchmarks. Cache stable conversation keys only within bounded, revocation-aware lifetimes; never reuse encryption nonces or one-time wrapper keys.
- Replace Chrome’s masked 255-bit secret sampling with the vetted uniform key-generation helper. The existing bias is not evidence of a practical key-recovery attack.
- Avoid repeated expensive decryption of already durably authenticated wrapper IDs. Preserve full verification for anything not in that committed ledger.
- Isolate and contract-test private dependency hooks such as nostr-tools `_onauth` before any dependency upgrade.

The current NIP-44 specification includes the extended length prefix beyond 65,535 bytes. The Android implementation’s extended prefix is therefore **not** automatically a protocol defect; older recollections of the size limit would give the wrong audit result. Keep platform resource caps. NIP-44 does not provide forward secrecy. [P7]

### R17 — Recovery assurance is overstated by source-shape tests

**Where:** `chrome/tests/service-worker-recovery.test.ts`. [S22]

This file reads the service-worker source as text and asserts the presence/order of strings and regex matches. Such tests can verify intended structure but cannot prove alarms, real storage, crashes, asynchronous interleavings, or message handling. Other tests and retained live evidence are real; it would be equally wrong to dismiss all 101 tests as fake.

Add behavioral tests around the actual worker orchestration and real browser restart tests with a non-empty outbox. Record failure-first regressions for the bugs fixed. Preserve source-shape tests only as secondary architecture checks, not recovery acceptance evidence.

### R18 — Reconcile claims with reachable code and final artifacts

**Where:** known limitations, parser/renderer, packaging and retained reports. [E3, E4, S13, S14]

The limitations document says remote article images are fetched directly. The renderer does contain an `AsyncImage` branch. However, the inspected parser turns inline images into alt text and does not construct image blocks in its normal parse path. Therefore this audit does **not** claim that opening every currently imported article fetches images. Trace actual reachability before repeating that privacy claim. Existing link parsing already restricts links to HTTP/HTTPS; do not misreport its lower-level click handler as a demonstrated arbitrary-URI exploit.

Keep text-only reading private by default. If image rendering becomes reachable during formatting fixes, make remote loading explicit and test redirects, unsafe/local targets, and offline fallback. Encryption protects transferred text, not relay-visible IP/timing/recipient metadata or a compromised endpoint. [P3, P7]

Target/compile SDK, dependency advisories, native transitive libraries, and 16-KiB runtime compatibility require current checks. Do not infer native compatibility from “we wrote no NDK code.” Test the actual packaged dependencies where relevant. [P8]

Finally, distinguish application-source equivalence, a reproducible package, installing that package, instrumentation of that exact package, and end-to-end transfer with that package. One does not imply the next. Version numbers, test claims, provider support claims, and artifact hashes must describe the same final candidate.

## Product decisions for the implementation stage

**Keep:** one-click capture; native, beautifully rendered reading; local-first data; private device keys; independent Nostr relays; deterministic content identity; authenticated device delivery; Flexoki and bundled typography.

**Add now:** truthful capture feedback and history; dependable recovery; current provider buttons and requested sites; system dark mode; correct reading anchors; immediate highlight mode; quote-only sharing; highlight feed and focused review; useful TTS behavior; local export that includes highlights.

**Do not add to this release:** proprietary push/backend, user accounts, cloud/LLM extraction, a new cryptographic ratchet, multi-device highlight sync, a generalized agent/recommendation framework, iOS/Mac product work, or store publication. The supplied local Mac is an execution environment, not a new product target.

The review algorithm needs one explicit interpretation: strict “every item before any repeat” gives equal frequency to all items; important items cannot simultaneously appear more often under that same universal rule. The proposed beta uses a shuffled full-coverage pass, followed by a bounded important-only bonus pass, with no adjacent repetition and no starvation. This exception is visible and deterministic, not an opaque engagement algorithm.

## Sources

All repository links below are pinned to the reviewed commit. External references were consulted on 5 September 2026 and should be checked again at implementation time when API/package decisions depend on them.

- **E1** — [evidence/raw/final-tcl-app-install-2026-09-05.txt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/evidence/raw/final-tcl-app-install-2026-09-05.txt)
- **E2** — [evidence/raw/final/tcl-seven-relay-transfer-ack-2026-09-05.txt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/evidence/raw/final/tcl-seven-relay-transfer-ack-2026-09-05.txt)
- **E3** — [TEST_REPORT.md](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/TEST_REPORT.md)
- **E4** — [KNOWN_LIMITATIONS.md](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/KNOWN_LIMITATIONS.md)
- **E5** — [AI_CONTINUATION_CONTEXT.md](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/AI_CONTINUATION_CONTEXT.md)
- **S1** — [chrome/src/background/service-worker.ts](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/chrome/src/background/service-worker.ts)
- **S2** — [chrome/src/content/capture.ts](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/chrome/src/content/capture.ts)
- **S3** — [android/app/src/main/java/com/reader/app/nostr/NostrCodec.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/nostr/NostrCodec.kt)
- **S4** — [android/app/src/main/java/com/reader/app/sync/SyncWorker.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/sync/SyncWorker.kt)
- **S5** — [chrome/src/nostr/transport.ts](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/chrome/src/nostr/transport.ts)
- **S6** — [android/app/src/main/java/com/reader/app/sync/TransferManager.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/sync/TransferManager.kt)
- **S7** — [android/app/src/main/java/com/reader/app/data/ReaderDb.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/data/ReaderDb.kt)
- **S8** — [chrome/src/extraction/pipeline.ts](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/chrome/src/extraction/pipeline.ts)
- **S9** — [chrome/src/providers/adapters.ts](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/chrome/src/providers/adapters.ts)
- **S10** — [chrome/manifest.json](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/chrome/manifest.json)
- **S11** — [android/app/src/main/java/com/reader/app/ui/MainActivity.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/ui/MainActivity.kt)
- **S12** — [android/app/src/main/java/com/reader/app/ui/screens/ReaderScreen.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/ui/screens/ReaderScreen.kt)
- **S13** — [android/app/src/main/java/com/reader/app/ui/screens/ArticleRenderer.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/ui/screens/ArticleRenderer.kt)
- **S14** — [android/app/src/main/java/com/reader/app/core/ArticleModel.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/core/ArticleModel.kt)
- **S15** — [chrome/src/ui/options.html](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/chrome/src/ui/options.html)
- **S16** — [android/app/src/main/java/com/reader/app/tts/TtsController.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/tts/TtsController.kt)
- **S17** — [android/app/src/main/java/com/reader/app/prefs/Prefs.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/prefs/Prefs.kt)
- **S18** — [android/app/src/main/java/com/reader/app/ui/theme/Tokens.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/ui/theme/Tokens.kt)
- **S19** — [android/app/src/main/java/com/reader/app/ui/screens/InboxScreen.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/ui/screens/InboxScreen.kt)
- **S20** — [android/app/src/main/java/com/reader/app/nostr/Nip44.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/nostr/Nip44.kt)
- **S21** — [android/app/src/main/java/com/reader/app/nostr/Secp256k1.kt](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/android/app/src/main/java/com/reader/app/nostr/Secp256k1.kt)
- **S22** — [chrome/tests/service-worker-recovery.test.ts](https://github.com/mariusschober/nostr-reader/blob/0c9884e955558f478d7668b46e9c1411bfe95c5d/chrome/tests/service-worker-recovery.test.ts)
- **P1** — [Chrome alarms API](https://developer.chrome.com/docs/extensions/reference/api/alarms)
- **P2** — [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md)
- **P3** — [NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md)
- **P4** — [NIP-40 expiration](https://github.com/nostr-protocol/nips/blob/master/40.md)
- **P5** — [Notebook endpoint; sign-in redirect observed](https://notebook.google.com/)
- **P6** — [Android WorkManager work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- **P7** — [NIP-44](https://github.com/nostr-protocol/nips/blob/master/44.md)
- **P8** — [Android 16-KiB page sizes](https://developer.android.com/guide/practices/page-sizes)
- **P9** — [Flexoki authoritative palette](https://github.com/kepano/flexoki/blob/main/README.md)
- **P10** — [Compose SelectionContainer and lazy-layout limitations](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/selection/SelectionContainer.composable)
- **P11** — [Chrome service-worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle)
