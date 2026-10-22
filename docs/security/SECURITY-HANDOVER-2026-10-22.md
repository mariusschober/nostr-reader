# Security handover — nostr-reader — 2026-10-22

Branch: `security/2026-10-22`, created from `main` @ `1e55b7d`. Pushed without a PR. No merge, deploy or CI run.
The commits were cherry-picked (`-x`) from the local review branch `improve/2026-10-22`. That branch is not pushed and is waiting for review.

## Fixes on this branch

| Commit | Item | What it does |
|---|---|---|
| 1247677 | NOSTR-READER-01 | Chrome `captures` IndexedDB rows now hold only a dedupe receipt: `captureId`, `transferId`, `createdAt` and `expiresAt`. They no longer keep the article markdown, title or source URL. A one-time startup scrub rewrites old rows in place, and the receipts expire after 7 days. **The data migration is irreversible:** old plaintext is dropped from local IndexedDB. |
| 6bcb52c | NOSTR-READER-08 | An explicit IPv4/IPv6 classifier rejects relay addresses that are IPv4-mapped or compatible, 0/8, CGNAT, RFC1918, link-local, documentation, benchmark, multicast, reserved, ULA, 6to4, Teredo or NAT64 (SSRF and private-network hardening). Chrome still cannot resolve DNS, so Android remains the network barrier. |
| 8a10836 | NOSTR-READER-31 | An external `ACTION_SEND` share now needs confirmation with a preview, and imports are limited to 5 per minute. `ACTION_PROCESS_TEXT` stays a quick import. An explicit HTML type replaces the `__HTML__` string sentinel. Rotating the screen no longer re-imports the launch intent. |

### Not on this branch
- **NOSTR-READER-16** (comparable six-word pairing code, MITM check) did not cherry-pick cleanly onto `main`: it conflicts in `chrome/src/background/service-worker.ts`. It was skipped here and exists only on `improve/2026-10-22` (`f8d85ba`).
- These security-relevant hardening items are on `improve/` only, not marked `security:`:
  - NOSTR-READER-18: commit the channel key and verify it by reading it back.
  - NOSTR-READER-04: signed disconnect teardown.
  - NOSTR-READER-21: revoke invalid relay sets.
  - NOSTR-READER-10: the link-share fetch reuses the private-address DNS guard.
  - NOSTR-READER-27: allow only http(s) open/copy URLs.

## Tests (Linux VM, this branch)
- `chrome`: `npm run typecheck`, `npm test` and `npm run build` pass. **23 files, 138 tests** (baseline 135).
- `android`: `./gradlew testDebugUnitTest lintDebug assembleDebug` passes. **115 unit tests**, 0 failures (baseline 112). Lint has 0 errors, and the debug APK builds.
- `rust-core`: unchanged, not re-run (baseline 6 pass).
- Not run: instrumented/device tests (no emulator), and manual pairing or share tests on a phone.

## Not done / needs Marius
- Review and merge are up to Marius. No PR was opened.
- Device check: the share confirmation dialog and relay rejection with real networks.
- Decide whether NOSTR-READER-16 should come here as a manual port, or go with the `improve/` branch.
