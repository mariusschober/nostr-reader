# Reader v2 rollback and recovery

Primary v2 implementation commit: `91aa1c49ebc98cb68ef0df1c9c0608d8b3c1791b`

Receiver ACK durability follow-up: `3c4546bfd8b27fa11ac3d5af576dba2050b5de6e`

Final runtime fix commit: `6ea80ee6f3732a192307661fd9cd5c485a4dd1dc`

Deterministic Chrome packaging commit: `a061ddcb111cf05048828e65fcddc9c797f1a6ec`

Final audited artifact/documentation commit: recorded in generated
`artifacts/ARTIFACTS.json` because that commit cannot contain its own hash.

Immutable pre-repair baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`

Rollback is intentionally not an in-place protocol downgrade. Reader v2
changes the Android database, trust states, compression framing, pairing
transcript, retry ownership, and delivery ACK. An older build must never
reinterpret a v2 channel or Room v5 database. The stricter one-member decoder
does not change valid v2 bytes; it rejects only concatenated or suffixed input
that was already outside the documented contract.

## Preferred source rollback

Create a separate branch/worktree at the immutable baseline and build there.
This preserves the repaired branch and its evidence and avoids history
rewrites. Do not force-push or reset the repair branch.

If a normal forward-moving Git rollback is required, first revert any later
documentation-only audited commit, then review and revert this exact repair
series newest-first with `git revert --no-commit`:

```text
a061ddcb111cf05048828e65fcddc9c797f1a6ec
6ea80ee6f3732a192307661fd9cd5c485a4dd1dc
b9b812db7b899708948f5e946045fd73e2af3b03
0a2ac8b4a930ea57f89e33427bef2768b92a961d
4d8211f3ce7d51a0c37f99e74af53b015332a83b
1eccb27e6b08f22bb9236c4981879f0f587ac666
a40beb55217526eccc84f4673a157a106ad47a54
3a18670772a2ddfed8ced607b1a43f8a7fbb31d4
0df5baf43d67cd42ec13e91d7a72bf759821c7b2
f1497551f7130b5b449050288b35e16d01dfce87
720180ff8a2fe1eecea2eefaf07b2fd0229a0255
286137f1d69c0596dbfd5a075ba6481c9174fb96
3c4546bfd8b27fa11ac3d5af576dba2050b5de6e
22a84eb2b3f48787a69ff5fd72a4bcdec430cf8d
4e979bd03fa1ae95e1b1be04156522facdef3b85
91aa1c49ebc98cb68ef0df1c9c0608d8b3c1791b
```

Confirm the resulting tree against `982920b4e91dd5af4af6046f56df281c7adfe545`
before committing the rollback. Re-run the baseline build/tests and inspect the
resulting diff before using its artifacts. The preferred separate-baseline
branch remains simpler and safer.

## Android data boundary

A baseline APK cannot safely open Reader's Room v5 database.

1. Export or otherwise preserve user-controlled documents with a current build.
2. Record that all device pairings and queued transport state will be lost.
3. Disconnect the paired Chrome device if it is still available.
4. Clear Reader app data or uninstall Reader before installing the older APK.
5. Import only the user-controlled document export; never copy the v5 database,
   preferences, or wrapped key files into the older app.

Clearing data or uninstalling is destructive: it removes local Reader
documents, progress, Room state, preferences, and Android Keystore-wrapped
channel material. Make the export first. Android cloud/device-transfer backups
are deliberately excluded and are not a recovery source.

## Chrome profile boundary

Test an older extension in a separate Chrome profile. Do not load it over the
paired v2 profile and do not copy extension storage between versions.

If the v2 profile must be retired, preserve any user-visible queued article
content that is still needed, choose **Disconnect device**, remove the
extension, and then install the older build in a clean profile. A later return
to v2 requires a fresh QR pairing; old one-time pairing keys and channel trust
must not be restored.

## Relay residue

Published wrappers are encrypted and expire according to their NIP-40 and
inner expiry bounds, but a relay may retain ciphertext indefinitely. Rollback
cannot delete relay copies and must not claim to. Revoking local keys prevents
future authenticated use of the retired channel but does not provide forward
secrecy for ciphertext retained before a key compromise.

## Restore the repaired path

1. Return to a commit containing the v2 implementation.
2. Run `./scripts/build-audit-artifacts.sh` from a clean tracked worktree.
3. Install only artifacts whose `artifacts/ARTIFACTS.json` source commit and
   SHA-256 values match the files being tested.
4. Create a fresh pairing and review the exact relay list/fingerprint on
   Android.
5. Treat the channel as connected only after both endpoints complete the
   authenticated handshake; treat an article as delivered only after its
   authenticated device ACK.

No backend, production signing material, real identity, push, merge, release,
or publication step is part of this rollback procedure.
