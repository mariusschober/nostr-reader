# Audit artifact policy

`artifacts/` is the local output directory for reproducible QA builds. The
generated binaries and machine-readable manifest are intentionally ignored by
Git because each manifest records the clean source commit used to build it;
committing that manifest into the same source commit would make provenance
self-referential.

Generate the complete set from a clean tracked worktree:

```sh
./scripts/build-audit-artifacts.sh
```

Generated files:

- `reader-debug.apk` — Android debug-signed app APK.
- `reader-debug-androidTest.apk` — matching instrumentation APK.
- `reader-chrome-extension.zip` — deterministic Chrome MV3 package.
- `chrome-sbom.cdx.json` — Chrome CycloneDX SBOM.
- `android-debug-runtime-dependencies.txt` — Android runtime dependency tree.
- `ARTIFACTS.json` — source commit, commands, SHA-256 values, and install-test
  status.
- `SHA256SUMS` — checksum file for the generated set.

At the 2026-09-05 handoff, the reproducible local debug artifacts had these
hashes:

```text
4c555d6d8e53784b3d7a5c00a7e85df671f3f5abede1923da5766573616cea5e  reader-debug.apk
244c4bd0d829f17a342c78e06d6d7bb9f6aa354dd2c527564a1da0c9187aa563  reader-debug-androidTest.apk
01b4d5b714c385d70c635e0043c6c6f6a55b8223caafd98475f6a79dc8744c92  reader-chrome-extension.zip
```

The Chrome ZIP is byte-identical to the package verified in Chrome for
Testing. The final reproducible APK pair was not installed after the
toolchain-only rebuild; its physical result remains `NOT MEASURED`. These are
developer artifacts, not production-signed or published releases.

`TEST-REPORT.md` is a preserved historical report and is superseded by the
root `TEST_REPORT.md`.
