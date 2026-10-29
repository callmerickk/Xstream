#!/usr/bin/env bash
# Live server for the 64-bit Xstream app: PencariMovie Server (MIT, github.com/aiskendi/pencarimovie-server),
# built UNCHANGED from its tagged source for Android arm64 with the NDK (Android's own DNS and TLS paths, no glibc).
#
# Only one thing is left out: the server embeds its own web player (player libraries, 40 translations, ~17 MB).
# Xstream plays everything itself and only opens the server's small settings page, so those files are removed
# from public/ before the build. The server code is not touched.
#
# Output: app/src/phone/jniLibs/arm64-v8a/libxlive.so (Android only runs programs shipped as app libraries).
set -euo pipefail
TAG="${PM_TAG:-v3.0.4}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/live-server-cache"
OUT="$ROOT/app/src/phone/jniLibs/arm64-v8a"
mkdir -p "$CACHE" "$OUT"

if [ ! -s "$CACHE/libxlive-$TAG.so" ]; then
  WORK="$(mktemp -d)"
  git clone -q --depth 1 --branch "$TAG" https://github.com/aiskendi/pencarimovie-server "$WORK/src"
  cd "$WORK/src"
  # The server's web player (not used by Xstream). The settings dashboard (index.html, app.js, dashboard.*,
  # styles, logo, res/values strings, legacy runtime) stays.
  rm -rf public/res/i18n public/assets/libs public/app.bundle.js public/core-js.bundle.js public/nuvio.html \
         public/css public/assets/runtime/plugin-worker.js public/youtube-proxy.html
  du -sh public
  rustup target add aarch64-linux-android
  command -v cargo-ndk >/dev/null || cargo install cargo-ndk --locked
  export ANDROID_NDK_HOME="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
  PM_BUILD_VERSION="${TAG#v}" cargo ndk -t arm64-v8a --platform 24 build --release --locked
  cp target/aarch64-linux-android/release/pencarimovie-server "$CACHE/libxlive-$TAG.so"
  cd "$ROOT"; rm -rf "$WORK"
fi
cp "$CACHE/libxlive-$TAG.so" "$OUT/libxlive.so"
ls -la "$OUT/libxlive.so"
file "$OUT/libxlive.so" || true
