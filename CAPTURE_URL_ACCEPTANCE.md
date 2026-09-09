# Stage A — Android URL capture acceptance (unpaired, no backend)

Branch: `codex/reliability-finalize` (ahead of `main`, baseline `1e55b7d` is an ancestor).
Scope: Browser Share → Reader → durable request → fetched/extracted article. Works with
Chrome closed, without pairing, without any application backend or sync feature.
Second pass (same day): extraction-quality program — the fetcher no longer drags
encyclopedia chrome, citations, appendices, or blog widgets into articles.

## What was built

- `ShareIntentClassifier` — standalone URL vs subject-plus-URL vs Markdown/SelectedText.
  Markdown imports and multi-URL/long prose are never auto-fetched.
- `CaptureUrlPolicy` + `CaptureDns` — http/https only, no credentials, no localhost/
  `.local`/`.internal`, malformed-numeric rejection, per-hop redirect validation
  (5 max, no https→http downgrade), DNS prohibited-network guard on every socket,
  default TLS verification. No test bypass in production.
- `CaptureFetcher` — single-document GET, no cookies/auth, no subresource fetches,
  8s connect / 12s read / 30s total, 5 MiB wire / 2 MiB expanded caps, MIME allowlist
  `text/html` + `application/xhtml+xml`, dispatcher max 2.
- `ArticleExtractor` — static Jsoup extraction in layers: generic noise strip,
  unconditional CMS/encyclopedia chrome strip (MediaWiki toolbar/tabs, revision
  dialog, tagline, hatnotes, TOC, edit links, infobox, navboxes, category footer,
  print footer; WordPress entry-meta, tag clouds, share/like/related widgets,
  post navigation, comments/reply, author boxes, post footers, Gutenberg post-meta
  blocks), inline-citation surgery, reference-list removal, appendix pruning
  (References/See also/External links/…, EN+DE, heading and paragraph-label forms),
  newsletter-CTA pruning (email-form cards, "Discover more from X" ballast sweep
  with long-prose stop rule), read-time/separator micro-paragraphs, a scorer that
  penalizes link density on every block type and only rewards genuine data tables,
  title suffix dedup ("Reading - Wikipedia" → "Reading"). Rejects empty, login/
  paywall/interstitial, JS-only, too-short, over-complex pages → honest link-only
  fallback. No JavaScript executed, no subresources fetched.
- `capture_requests` (Room v11, `MIGRATION_10_11`) separate from `DocumentEntity`;
  generation-guarded crash-safe commits; dedup of active intents by normalized URL;
  deliberate recapture via new request rows.
- `CaptureWorker` — unique work per request ID (input = ID only), CONNECTED constraint,
  persisted attempts/next-retry, retryable vs permanent error split, stale-commit suppression.
- Truthful UI — “Link saved — fetching article” after durable commit;
  “Article saved” after extracted-text commit; link-only docs explicitly say
  “Article text is unavailable” with Open original / Retry / selected-text guidance
  (reader banner + Markdown body). No empty/login page passes as an article.
- `ReaderDb` v11, reschedule-on-start for offline survival, paste/share entry points.

Nostr wire schemas unchanged. Pairing/keys/highlights/review history untouched.
No new Android permissions (manifest untouched).

## Extraction quality (second pass)

Trigger: the fetched `en.wikipedia.org/wiki/Reading` carried tabs, revision notice,
hatnotes, 111 inline citations, the reference list, navboxes and category links
(6,449 words); the author's WordPress post carried header meta, a tag cloud and
Like/related/subscribe widgets (835 words).

Fixes, each grounded against live HTML and pinned by golden tests
(`WikipediaExtractorTest`, incl. a selector-vitality test over every stripping family):

| # | Cause | Fix | Effect on device |
|---|---|---|---|
| 1 | `<main>`-wide candidate + inside-main strip guard | Unconditional chrome selector pass (MediaWiki + WordPress families) | Tabs/notice/hatnotes/navboxes/catlinks gone |
| 2 | Citations kept as `[n]` text + full bibliography | Inline-marker + reference-list removal | 0 citation markers; no References tail |
| 3 | See also / External links / tag clouds kept; scorer rewarded tables/lists | Appendix pruning (h2 + p-label forms, EN+DE); density penalty on all blocks; data-table gating | EN 6,449 → 3,059 words, 11 real headings, data table kept |
| 4 | `Reading - Wikipedia` prepended above H1 `Reading` | og:title/H1 suffix reconciliation | Title `Reading`, no duplicate heading |
| 5 | Subscribe/share/tag/footer blocks in generic containers | Email-form card + "Discover more" ballast pruning, entry-footer/post-terms/date selectors, read-time/bullet micro-paragraphs | Blog 835 → 647 words, zero chrome markers |
| 6 | **Latent bug found by the new tests:** Jsoup 1.17.2 parses `[attr*=v i]` without error but matches nothing — a whole selector generation (cookie/consent/advert/…) was silently dead | All contains-selectors rewritten to plain lowercase; vitality test pins every family | Generic pages additionally lose cookie/consent/ad walls |

Over-prune found and fixed the same day: a 4-level ancestor climb gutted a live post
(request went `link_only/too_short`); the pruner now climbs at most two levels, never
takes a container holding most of the article, and sweeps only ballast siblings with a
long-prose stop rule. The failing intermediate state never left the QA install.

Deliberate non-goals kept: infoboxes dropped (not condensed), references dropped (no
endnotes appendix), GitHub-login-style pages stay honest `link_only/too_short` rather
than claiming a precise `login_wall` (tuning follow-up, no honesty impact).

## Acceptance corpus

Automated (synthetic, in-repo):
- `shared/fixtures/generic/article-noise.html` + `expected.md` (reused, byte-stable).
- Unit fixtures: EN article, DE article, nav-heavy page, login, paywall, JS-only shell,
  empty, malformed HTML, Wikipedia chrome/citations/appendix/title, nested-wrapper CTA
  safety, WordPress widget/meta/tag/newsletter/CTA/micro-chrome, redirect chains
  (302→200, downgrade, private, loop), 401/403/404/429/5xx, unsupported MIME,
  3 MiB huge body, gzip expansion bomb, prohibited-DNS pre-network rejection,
  credentialed URLs, numeric-host abuse.
- Room: v10→v11 migration keeps documents; in-memory flow tests for offline survival,
  duplicate-intent dedup, worker-death recovery, cancel-stale suppression, link-only honesty.

Device matrix (TCL T807D, Android 16/API 36, `ZXKRS4VKGQ8PWGEQ`, unpaired, Chrome closed):

| URL | Result | Note |
|---|---|---|
| `https://en.wikipedia.org/wiki/Reading` | `completed`, 3,059 words, title `Reading` | 11 real headings, 0 citations, data table kept |
| `https://m.wikipedia.org/wiki/Reading` | `completed`, same doc hash `6cda7aea` | https→https redirect followed; content-addressed determinism |
| `https://de.wikipedia.org/wiki/Lesen` | `completed`, 3,193 words | German layout |
| `https://www.mariusschober.com/…/dot-connecting-intuition-of-ai/` | `completed`, 647 words | Author's blog; zero chrome markers; identical hash on every refetch |
| `https://example.com` | `link_only/too_short` | Honest thin-page fallback |
| `https://upload.wikimedia.org/…/Example.jpg` | `link_only/unsupported_mime` | Binary correctly refused, host fallback title |
| `https://github.com/login` | `link_only/too_short` | Honest; precise `login_wall` coding is follow-up tuning |
| `http://en.wikipedia.org/wiki/Reading` | `pending`, retrying (`network_retryable`) | Plain-http egress blocked on this 5G network; redirect-chain logic unit-proven; request durable, never lost |

Repeated fetches of the same URL always resolve to the identical document hash.

## Production-path test results (2026-09-09)

- Unit: `./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL, **196 tests, 0 failures**
  (6 capture/text suites + existing `PasteDetect`, `HtmlMarkdown`, `ReaderCore`,
  RSVP/TTS, Nostr codec).
- Lint: `./gradlew :app:lintDebug` — BUILD SUCCESSFUL.
- Chrome (unchanged): `npm run typecheck` clean; `npm test` 23 files / 140 tests PASS.
- Device: `CaptureFlowInstrumentedTest` (8) + `CaptureMigrationInstrumentedTest` (1) +
  `ReaderDbMigrationInstrumentedTest` + `TransferManagerInstrumentedTest`
  (**24 total — PASS** on final code). Existing Nostr receipt/dedup semantics intact.
- Foreground delivery not regressed: `NostrReaderSync activeChannels=0` steady during
  captures; capture uses toasts only, never blocks the reader.

## Exact artifacts (this source)

- App: `android/app/build/outputs/apk/debug/app-debug.apk`
  `sha256 567ebaf38edb8d759f2aef09f19a147afecc0c708c4398cfd1361d3c9eb9d15a`
  (P0/P1 build; install with `adb install -r`, no data wipe)
- Test APK: `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  `sha256 2d63d41829e6d4778b96d33a94378bc314032edf3fb1a8f7b41da16cc4f67a76`
- Device: TCL T807D, Android 16/API 36, `ZXKRS4VKGQ8PWGEQ`. No Chrome involvement.
- Screenshots (`docs/screenshots/`, same device, same day): `capture-inbox.png`
  (articles as `url · N min` beside honest `link · 1 min` fallbacks),
  `capture-article-de.png` (German article + Listen/Highlight/Speed dock),
  `capture-linkonly.png` (unavailable banner + Open original/Retry + guidance).
- Test-harness note: `connectedDebugAndroidTest` uninstalls the app when finished,
  so on-device QA rows do not survive a later instrumented run. This is harness
  behavior, not app behavior — production code has no destructive migration fallback
  and no mass-delete path (only user-initiated single-article archive delete), and the
  v1→v11 migration chain is test-proven. Final matrix above ran on a fresh install of
  the exact hashed APKs; the device was left with a clean reinstall afterwards.

## P0/P1 hardening (red-team pass, same branch)

Two independent audits (performance/reliability, UX) plus a self-review of the diff.
Fixed, unit- and device-tested unless noted:

- **P0 unbounded body buffering → OOM.** `OkHttpCaptureHttp` buffered the whole
  response before any size check. Now: `Content-Length` fails fast pre-read, the body
  streams through an 8 KiB window (never more than cap + 8 KiB resident), the requested
  per-call timeout is honored, connect errors map to actionable codes, and a new
  8 MiB whole-chain budget counts every redirect hop. Pinned by a raw-socket
  Content-Length-lie test and a chunked over-cap test.
- **P0 spaceless-script false negatives.** Whitespace word counting made whole CJK
  articles read as ~1 word (always `link_only`), with broken TTS chunking/RSVP on top.
  Now: `cjkCount` (Han/Hiragana/Katakana/Hangul/Thai), script-aware article floor
  (30 words or 60 CJK chars), CJK-inclusive reading counts, CJK sentence splitting
  (。！？…) and chunking in narration, ≤24-char offset-exact RSVP token slicing with
  surrogate safety. Pinned by Chinese extraction/narration/RSVP tests.
- **P0 unbounded `capture_requests`.** Terminal history now purges past 90 days /
  newest 2000 rows in the daily sync pass; active requests and all documents are never
  touched. (`transfer_outcomes` deliberately left unbounded: it is replay protection,
  and its rows are ~200 bytes.)
- **P1 one-clock retry.** The worker now honors `nextAttemptAt` (premature wakes burn
  no attempt) and claims atomically; cold-start rescheduling pages all due + stale
  rows instead of stopping at 20. Claim/park/cancel/reopen are single guarded
  `UPDATE`s (the claim also clears stale backoff timestamps so crash recovery is
  never gated by a dead schedule).
- **P1 feedback honesty.** Failure toasts show the human message, not the code;
  invalid links say "saved as text instead"; non-http link taps explain instead of
  swallowing; part switches keep per-part positions; footnote markers render small
  and are skipped by TTS/RSVP exactly like reading-time counting (footnote
  definitions are now real `Footnotes` blocks instead of spoken body paragraphs —
  pasted-footnote documents re-render slightly differently, with graceful anchor
  fallback and quotes preserved).
- **P1 bounded RTL.** First-strong paragraph direction + high-quality break strategy
  at the view layer (zero projection/offset change, anchors untouched) and mirrored
  swipe hints. Full per-paragraph RTL layout stays Phase 3 work.

S23 Ultra (SM-S918B, Android 16/API 36, `R3CW404GVBL`), P0/P1 build `567ebaf3`,
fresh install, unpaired, Chrome closed — all within ~15 s per share:

| URL | Result |
|---|---|
| `https://en.wikipedia.org/wiki/Reading` | `completed`, same doc hash `6cda7aea` as the TCL (cross-device determinism) |
| `https://ar.wikipedia.org/wiki/قراءة` | `completed`, 917 words, title `قراءة`, clean core prose with wiki links (RTL end to end) |
| `https://example.com` | `link_only/too_short` (deterministic) |

A pre-existing `Hello Reader` text note (23 words, `android-share`) was already on the
device and was left untouched. Instrumented suites on the S23 after the matrix:
`CaptureFlow` (11) + `CaptureMigration` (1) + `ReaderDbMigration` + `TransferManager`
(**27 total — PASS**). The harness uninstalls the app when finished, so the S23 was
left with a clean reinstall of the exact hashed APK.

## 100-article eval (2026-09-09, Mac JVM, production fetcher+extractor)

Harness: `android/app/src/test/java/com/reader/app/EvalMain.kt`, run manually via
`./gradlew :app:runCaptureEval` (NOT a CI gate — it hits the live web, politely:
sequential, 400 ms delay). List: `docs/eval/article-urls-v1.txt` (60 EN + 20 DE +
5 AR + 5 ZH Wikipedia, 10 stable elsewhere). Report: `docs/eval/report-v1.tsv`.

- **96 completed, 0 chrome flags** (no `[edit]` markers, no citation clusters, no
  leaked fallback text, no URL-shaped titles anywhere).
- 3 honest `link_only/too_short`: `example.com` (thin by design) plus two
  Python.org index/landing pages (link-farm-shaped; declining is defensible).
- 1 correct refusal: RFC `.txt` served as `text/plain` (outside the HTML allowlist).
- Timing: fetch p50 396 ms / p95 1.8 s; extract p50 50 ms / p95 115 ms.
- Words on completed: p50 6,288, min 257 (a stub, correctly kept), max 24,225.
- Cross-checks match device results exactly (EN `Reading` 3,059 w, DE `Lesen`,
  AR `قراءة` 917 w, blog 647 w).

This is a smoke-and-properties eval, not precision/recall against human-marked
cores — the labeled-corpus harness stays the next measurement step.

## 16 KiB page-size close-out

Yes — the emulator was set up locally instead of waiting for hardware: the SDK
already contained `system-images/android-36/google_apis_ps16k/arm64-v8a`, and an
existing `sprich_review_16k` AVD (Pixel 6) pointed at it. Booted headless
(`PAGESIZE=16384` confirmed), installed the exact hashed APK, launched clean,
and ran a full share→capture cycle (`example.com` → honest `link_only/too_short`,
durable row committed). No physical 16 KiB device attached; emulator evidence stands.

## Dependency security (2026-09-10)

- Chrome: `npm audit` 0 vulnerabilities; `osv-scanner` over `package-lock.json` (207 packages): no issues.
- Android: OSV batch over all 134 pinned `debugRuntimeClasspath` modules — 2 findings, both
  unreachable in our call graph (BouncyCastle GOST-CTR/LDAP helpers we never call;
  Jsoup `Cleaner`, which the codebase never uses — verified by grep; hostile HTML goes
  through parse + `select().remove()`, and hostile JSON through depth-capped `StrictJson`
  max 32). Closed anyway by upgrading `bcprov-jdk18on:1.78.1→1.85.2` and
  `jsoup:1.17.2→1.23.2`; full 196-test unit suite (crypto vectors + extraction goldens)
  green after the bump. coil/okhttp/zxing/kotlinx-serialization: clean.
- Licenses (POM-verified): MIT (jsoup), Bouncy Castle Licence, Apache-2.0
  (okhttp/coil/coroutines/serialization), BSD-2-Clause (commonmark); no copyleft in
  production. Re-scan on every dependency change.

## Capture-state / permission / privacy note

- States: `pending → fetching → completed | link_only | failed | cancelled`.
  Attempts + `nextAttemptAt` persisted; backoff 30s·2ⁿ (cap 6h, jitter); max 10 attempts
  then honest link. `generation` bumps on cancel/retry; stale workers cannot commit.
- Permissions: existing `INTERNET` + `ACCESS_NETWORK_STATE` only. No new permissions,
  no background-location, no account. WorkManager `CONNECTED` constraint only.
- Privacy: fetch is a direct device→publisher HTTPS GET with a neutral
  `Reader/0.9` UA, `Accept: text/html`, no cookies, no Authorization, no browser-cookie
  import, no credentialed URLs, no paywall/login bypass, no JS execution, no image/
  subresource fetches. Publisher sees IP/UA/timing like any browser visit. Article text
  stays on-device in Room; no Reader backend, no telemetry, no relay involvement for
  URL captures. Deletion follows library rules (archive → permanent delete); capture rows
  are history and never resurrect deleted content; recapture is explicit and new.

## Stage B — isolated local-TTS feasibility (NOT merged)

Status: **BLOCKED (not started on device)** — deliberately no model downloader, foreground
speech service, or large model merged into production.

Recorded research facts (to revalidate before any download): Pocket TTS upstream has
EN/DE material but desktop claims are not TCL evidence; code/weights/voice licenses differ
(Kyutai voices mix permissive + non-commercial — do not bundle NC voices); maintained
Piper is GPL-3.0 with separate model/voice/phonemizer licensing; Supertonic announced end
of official model support (July 2026) — do not adopt as default; a system voice is not
necessarily offline. No account/legal terms accepted; no silent setting changes.

Smallest justified plan (separate experiment branch/harness only): at most Pocket TTS
(licensed EN/DE checkpoint/voice) + Piper baseline; record exact versions/hashes/sizes/
licenses/attribution; block network post-acquisition and prove local synthesis; EN/DE
sentences covering names/numbers/punctuation/abbreviations/quotes/Unicode vs offline
system voice; cold/warm first-audio, RTF, PSS, thermal/battery, cancellation, 30-min
1×/2.5× (2.5× needs sustained RTF < 0.4); blind listening samples, no fabricated scores,
no ElevenReader parity claims without evidence; finish GO/NO-GO/BLOCKED. Failure must not
regress Stage A.
