#!/bin/sh
# Prove clean-build byte reproducibility for the debug APK.
# Recipe (exact): same absolute path, --offline --no-daemon --max-workers=1 clean.
# Compares two consecutive clean builds byte-for-byte, then per-DEX entry.
# Usage: from android/: ../../scripts/verify-reproducibility.sh
# (Or pass an alternate project dir as $1; the APK must already be built once
# so the baseline below has something to compare — the script rebuilds twice.)
set -eu
cd "$(dirname "$0")/../android"
APK="app/build/outputs/apk/debug/app-debug.apk"

build_once() {
  ./gradlew --offline --no-daemon --max-workers=1 clean :app:assembleDebug
}

echo "== build A =="
build_once
cp "$APK" /tmp/repro-check-A.apk
echo "== build B =="
build_once
cp "$APK" /tmp/repro-check-B.apk
if cmp -s /tmp/repro-check-A.apk /tmp/repro-check-B.apk; then
  echo "PASS: consecutive clean builds are byte-identical"
else
  echo "FAIL: APKs differ — per-entry comparison:"
  rm -rf /tmp/repro-check-A.d /tmp/repro-check-B.d
  mkdir -p /tmp/repro-check-A.d /tmp/repro-check-B.d
  unzip -q -o /tmp/repro-check-A.apk -d /tmp/repro-check-A.d
  unzip -q -o /tmp/repro-check-B.apk -d /tmp/repro-check-B.d
  (cd /tmp/repro-check-A.d && sha256sum classes*.dex | sort > /tmp/repro-check-A.sha)
  (cd /tmp/repro-check-B.d && sha256sum classes*.dex | sort > /tmp/repro-check-B.sha)
  diff -u /tmp/repro-check-A.sha /tmp/repro-check-B.sha || true
  exit 1
fi
