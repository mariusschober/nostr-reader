#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ -n "$(git -C "$repo_dir" status --porcelain --untracked-files=no)" ]; then
  echo "Refusing artifact build: tracked worktree is dirty." >&2
  exit 1
fi

mkdir -p "$repo_dir/artifacts"

cd "$repo_dir/chrome"
npm ci
npm audit --omit=dev
npm audit
npm run typecheck
npm test
npm run build
npm sbom --package-lock-only --sbom-format cyclonedx --sbom-type application > "$repo_dir/artifacts/chrome-sbom.cdx.json"

cd "$repo_dir"
reader_repro_zip=$(mktemp "${TMPDIR:-/tmp}/reader-chrome-repro.XXXXXX")
reader_repro_apk=$(mktemp "${TMPDIR:-/tmp}/reader-android-repro.XXXXXX")
reader_repro_test_apk=$(mktemp "${TMPDIR:-/tmp}/reader-android-test-repro.XXXXXX")
trap 'rm -f -- "$reader_repro_zip" "$reader_repro_apk" "$reader_repro_test_apk"' EXIT HUP INT TERM
"$repo_dir/scripts/package-chrome-extension.sh" "$repo_dir/artifacts/reader-chrome-extension.zip"
"$repo_dir/scripts/package-chrome-extension.sh" "$reader_repro_zip"
cmp "$repo_dir/artifacts/reader-chrome-extension.zip" "$reader_repro_zip"
echo "Chrome extension packaging reproducibility: PASS"

cd "$repo_dir/android"
./gradlew --no-daemon --max-workers=1 clean assembleDebug assembleDebugAndroidTest
cp "$repo_dir/android/app/build/outputs/apk/debug/app-debug.apk" "$reader_repro_apk"
cp "$repo_dir/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$reader_repro_test_apk"
./gradlew --no-daemon --max-workers=1 clean test lint assembleDebug assembleDebugAndroidTest
cmp "$reader_repro_apk" "$repo_dir/android/app/build/outputs/apk/debug/app-debug.apk"
cmp "$reader_repro_test_apk" "$repo_dir/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
echo "Android app and instrumentation APK reproducibility: PASS"
./gradlew -q app:dependencies --configuration debugRuntimeClasspath > "$repo_dir/artifacts/android-debug-runtime-dependencies.txt"
cp "$repo_dir/android/app/build/outputs/apk/debug/app-debug.apk" "$repo_dir/artifacts/reader-debug.apk"
cp "$repo_dir/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$repo_dir/artifacts/reader-debug-androidTest.apk"

cd "$repo_dir/rust-core"
reader_cargo_bin=$(command -v cargo || true)
if [ -z "$reader_cargo_bin" ]; then
  reader_user_dir=$(cd && pwd)
  for reader_cargo_candidate in \
    "$reader_user_dir"/.rustup/toolchains/stable-*/bin/cargo \
    "$reader_user_dir"/.rustup/toolchains/*/bin/cargo
  do
    if [ -x "$reader_cargo_candidate" ]; then
      reader_cargo_bin=$reader_cargo_candidate
      break
    fi
  done
fi
if [ -z "$reader_cargo_bin" ]; then
  echo "Cargo was not found in PATH or a rustup toolchain." >&2
  exit 1
fi
reader_rust_bin=$(dirname "$reader_cargo_bin")
PATH="$reader_rust_bin:$PATH"
export PATH
"$reader_cargo_bin" test

cd "$repo_dir/mac"
swift test

cd "$repo_dir"
node scripts/write-artifact-manifest.mjs
