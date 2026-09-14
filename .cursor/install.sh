#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for the Reader monorepo.
#
# Prepares the three components that build and test on Linux:
#   - chrome/   (Node 22 + npm)      -> npm ci
#   - rust-core/(Rust/cargo)         -> cargo fetch
#   - android/  (JDK 17 + Android SDK)-> gradle assembleDebug (warms caches)
#
# mac/ (Swift package) only builds on macOS and is intentionally skipped here.
#
# Safe to run repeatedly: every step checks for existing state before doing work.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log() { printf '\n=== %s ===\n' "$1"; }

# ---------------------------------------------------------------------------
# JDK 17 (Android build targets JVM 17; matches CI)
# ---------------------------------------------------------------------------
JAVA_HOME_17="/usr/lib/jvm/java-17-openjdk-amd64"
if [ ! -x "$JAVA_HOME_17/bin/javac" ]; then
  log "Installing OpenJDK 17"
  sudo apt-get update -qq
  sudo apt-get install -y -qq openjdk-17-jdk-headless unzip
fi
export JAVA_HOME="$JAVA_HOME_17"

# ---------------------------------------------------------------------------
# Android SDK (command-line tools, platform 34, build-tools 34.0.0)
# ---------------------------------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if [ ! -x "$SDKMANAGER" ]; then
  log "Installing Android command-line tools"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp_zip="$(mktemp)"
  curl -fsSL -o "$tmp_zip" "$CMDLINE_TOOLS_URL"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  unzip -q "$tmp_zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f "$tmp_zip"
fi

log "Ensuring Android SDK packages"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Point Gradle at the SDK for this checkout (git-ignored, machine-specific).
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties

# Make interactive `./gradlew` use JDK 17 too, without touching shell profiles.
mkdir -p "$HOME/.gradle"
if ! grep -qs '^org.gradle.java.home=' "$HOME/.gradle/gradle.properties"; then
  printf 'org.gradle.java.home=%s\n' "$JAVA_HOME" >> "$HOME/.gradle/gradle.properties"
fi

# ---------------------------------------------------------------------------
# Chrome extension dependencies
# ---------------------------------------------------------------------------
log "Installing Chrome extension dependencies (npm ci)"
( cd chrome && npm ci )

# ---------------------------------------------------------------------------
# Rust core dependencies
# ---------------------------------------------------------------------------
log "Fetching rust-core dependencies (cargo fetch)"
( cd rust-core && cargo fetch --locked )

# ---------------------------------------------------------------------------
# Android: warm Gradle distribution + dependency caches by building the app
# ---------------------------------------------------------------------------
log "Warming Android build (gradle assembleDebug)"
( cd android && ./gradlew --no-daemon assembleDebug -q )

log "Reader environment ready"
