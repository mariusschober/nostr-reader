# Reader v2 rollback and recovery

Implementation commit: `91aa1c49ebc98cb68ef0df1c9c0608d8b3c1791b`

Immutable pre-repair baseline: `982920b4e91dd5af4af6046f56df281c7adfe545`

Rollback is intentionally not an in-place protocol downgrade. Reader v2
changes the Android database, trust states, compression framing, pairing
transcript, and delivery ACK. An older build must never reinterpret a v2
channel or Room v4 database.

## Preferred source rollback

Create a separate branch/worktree at the immutable baseline and build there.
This preserves the repaired branch and its evidence and avoids history
rewrites. Do not force-push or reset the repair branch.

If a normal forward-moving Git rollback is required, review and revert
`91aa1c49ebc98cb68ef0df1c9c0608d8b3c1791b` with `git revert`. Resolve any
later documentation-only commit separately. Re-run the baseline build/tests
and inspect the resulting diff before using its artifacts.

## Android data boundary

A baseline APK cannot safely open Reader's Room v4 database.

1. Export or otherwise preserve user-controlled documents with a current build.
2. Record that all device pairings and queued transport state will be lost.
3. Disconnect the paired Chrome device if it is still available.
4. Clear Reader app data or uninstall Reader before installing the older APK.
5. Import only the user-controlled document export; never copy the v4 database,
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
