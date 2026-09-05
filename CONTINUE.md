# CONTINUE — Reader v2 repair handoff

Updated: 2026-09-05

Immutable baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`

Working branch: `fix/pairing-delivery-hardening-982920b4`

Final runtime fix commit: `6ea80ee6f3732a192307661fd9cd5c485a4dd1dc`

Final audited artifact/documentation commit: generated
`artifacts/ARTIFACTS.json` `sourceCommit`.

## Current truth

Reader v2 now has an authenticated, durable Chrome-to-Android path:

```text
pair request -> Android consent -> pair response -> Chrome ACK
  -> Android completion -> active channel
  -> encrypted manifest/chunks -> durable Android document
  -> authenticated device ACK -> Chrome delivered
```

One fresh Chrome for Testing/TCL pairing completed over the six defaults plus
one custom relay. One synthetic article was stored exactly once and its bound
Android ACK moved Chrome from pending to delivered. This proves the complete
path can work; it is not the unexecuted 20/20 and 50/50 reliability series.

The exact runtime-checkpoint APK/test-APK pair was installed byte-for-byte on
the TCL, S23, and API-26 emulator and passed 10/10 instrumentation cases in each
environment. The two newest cases prove that future-due pending pairing work
retains a one-time recovery owner and that the Android runtime rejects a second
gzip member or trailing byte. On the preserved TCL, the existing authenticated
Chrome-device row remained visible after installation. No new pairing or
article was created for this follow-up.

The exact Chrome worker bytes matched `chrome/dist/background.js`, the packaged
ZIP, and the worker loaded by Chrome for Testing. Its seven-relay/device/digest
binding and delivered-1/pending-0 state survived both 20/20 distinct post-pair
worker terminations and one full same-profile browser restart.

The complete acceptance gate is therefore **NOT MEASURED**, while the executed
unit, instrumentation, packaging, live-relay, and single-flow checks are
reported independently in `TEST_REPORT.md`.

The Chrome ZIP is now reproducible on this host: two packages of one dist and a
third package after a fresh Vite build were byte-identical. Android debug APKs
are not byte-reproducible under the pinned AGP 8.5.2/D8 8.5.35 toolchain. Four
clean builds changed only D8's embedded synthetic-class checksum map; Kotlin
class hashes and normalized full DEX disassembly matched. Treat this as a byte
reproducibility **FAIL**, not as a runtime-code difference, and always install
the one exact APK hash named in the generated artifact manifest.

The paired Chrome profile also survived 20/20 distinct post-pair service-worker
terminations with identical seven-relay and outbox status. This is useful exact
lifecycle evidence, but it is not substituted for the still-unrun 20/20
termination-before-Android-reply pairing series.

## What changed

- Pairing is `reader-pair/2`, durable kind 1059 only, and requires both
  endpoints to authenticate the same session before either reports connected.
- NIP-59 wrapper routing, sender checks, NIP-44 v2 limits, strict JSON,
  NIP-42, BIP-340 signing, expiry, replay, gzip, hashes, chunk assembly, and
  endpoint ACK validation have executable regression coverage.
- Chrome persists pairing sessions and outbox state across MV3 suspension and
  restart. Relay acceptance and device delivery are separate states. Per-item
  mutations are serialized so a late publisher cannot recreate an ACK-deleted
  outbox item.
- After response authentication Chrome persists the pairing session without
  the one-time bootstrap secret, then retries a fresh ACK no more than once per
  30 seconds after first querying for completion.
- Android stages pairing and transfers transactionally, revokes key-loss
  channels, performs rolling-window catch-up, and persists both authenticated
  wrapper IDs and receiver ACK intent/outcomes in Room v5.
- Android one-time work remains retryable whenever a pending pairing exists,
  including when it wakes before the protocol's next-attempt timestamp.
- An initial response with zero matching positive relay confirmations remains
  non-active and retryable until expiry instead of discarding the provisioned
  transcript and forcing another scan.
- Reader uses six fixed public relays, a two-relay write quorum, and up to two
  user-added secure relays in Chrome Settings. Relay changes require re-pairing.
- The unsafe page-world signing bridge and inert settings controls were
  removed. Chrome key storage is restricted to trusted extension contexts.
- NIP-42 event rejection is distinct from Reader-event rejection; malformed or
  misbound Chrome AUTH templates are rejected before signing. Each operation
  signs at most one challenge, changed late challenges fail closed, and exact
  challenge echoes are redacted from notices/reasons/traces; duplicate AUTH/OK
  frames cannot cause repeated effects.
- The public-only `shared/test-vectors/pairing-v2.json` fixture makes Chrome's
  request/ACK and Android's response/completion one exact cross-runtime gate.
- All four codec runtimes require one complete gzip member and reject a second
  member or trailing data after streaming size, boundary, CRC32, and ISIZE
  checks.
- Chrome now restricts unauthenticated response catch-up to the fixed bootstrap
  relays but queries authenticated completion across the entire bound set. It
  also rejects corrupt durable relay state before network use or quorum math;
  no zero-relay success or implicit paired-channel fallback remains.
- Chrome now binds each completed channel to the exact public key derived from
  its persisted device secret. Missing, malformed, replaced, or non-curve key
  state fails closed instead of showing a ghost connection or sending under a
  wrong identity; never-sent captures remain recoverable after re-pairing.
- Chrome artifact packaging now stages a symlink-free dist, normalizes ZIP
  timestamps/order/metadata, and rejects a build unless two packages compare
  byte-for-byte.

## Evidence map

- Root cause: `PAIRING_ROOT_CAUSE.md`
- Normative protocol: `PROTOCOL.md`
- Architecture and state machines: `ARCHITECTURE_AND_PROTOCOL.md`,
  `RELIABILITY_STATE_MACHINES.md`
- Conformance: `NIP_CONFORMANCE_MATRIX.md`
- Security/privacy: `THREAT_MODEL.md`, `SECURITY_AND_PRIVACY_NOTES.md`
- Migration/rollback: `MIGRATION_AND_COMPATIBILITY.md`, `ROLLBACK.md`
- Live relays: `LIVE_RELAY_REPORT.md`
- Physical TCL work: `TCL_PHYSICAL_VALIDATION.md`
- Consolidated results and residuals: `TEST_REPORT.md`,
  `KNOWN_LIMITATIONS.md`
- Redacted raw evidence: `evidence/raw/`

## Reproduce the audited artifacts

Run `./scripts/build-audit-artifacts.sh` only from a clean tracked worktree. It
reinstalls locked Chrome dependencies, runs the Chrome/Android/Rust/Swift
gates, creates the debug APKs and extension ZIP, generates dependency evidence,
verifies Chrome ZIP reproducibility, and writes ignored
`artifacts/ARTIFACTS.json` plus `artifacts/SHA256SUMS`.

The manifest is the authority for exact source commit, hashes, build commands,
and post-build install results. The binaries are debug/developer artifacts,
not production releases.

## Physical devices and browser

- Decisive device: TCL T807D, ADB serial `ZXKRS4VKGQ8PWGEQ`.
- Secondary device: Samsung Galaxy S23, ADB serial `R3CW404GVBL`.
- The S23 has a previously confirmed physical OLED fault (bright horizontal
  line and intermittent lower-screen green illumination). Use ADB hierarchy or
  screen capture for app assertions and do not classify those panel artifacts
  as Reader defects.
- Browser validation uses Google Chrome for Testing, not Brave.
- Always provide an explicit ADB serial because both phones can be connected.

## Still required before a reliability PASS

- 20/20 clean pairings, 20/20 worker-termination pairings, and 20/20
  pre-reply Chrome-restart recoveries on the TCL.
- 50/50 normal deliveries with zero duplicate documents and verified device
  ACKs, plus 20/20 offline-to-online and 20/20 replay/duplicate attempts.
- The remaining controlled physical lifecycle/network/failure scenarios listed
  in `TCL_PHYSICAL_VALIDATION.md`.
- A public transfer using the exact final artifact only with explicit approval;
  do not infer authorization from build or device-test permission.
- Live third-party NIP-07/Amber signer checks, public NIP-42 challenge evidence,
  Android release signing, and a 16 KiB-page device remain unmeasured.

On the first Room v4 -> v5 catch-up, a retained transfer may produce one
authenticated recovery ACK because no historical v4 ledger exists. After that
write, repeated wrappers cannot restart a completed two-relay ACK quorum.

## Safety boundary

Do not push, merge, publish, force-push, introduce a backend, use a real Nostr
identity, or expose pairing/private-key material. Preserve the immutable
baseline and the dedicated repair branch. Distinguish `PASS`, `FAIL`,
`NOT MEASURED`, and `BLOCKED` exactly.
