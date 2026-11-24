#!/usr/bin/env bash
# 0.9.6 (webrtcOnDemand): Watch together's native library is not in Xstream's phone APK; the app downloads it from the
# assets branch (raw.githubusercontent.com/<repo>/assets/webrtc/<abi>/libjingle_peerconnection_so.so) and checks it
# against the SHA-256 written in WebRtcLib.kt. Run by the workflow after the build (the AAR is then in the Gradle cache):
#   1. extracts the .so of arm64-v8a and armeabi-v7a from the WebRTC AAR (the hashed file IS the uploaded file),
#   2. puts them on the assets branch when they are missing or different there (GITHUB_TOKEN, contents: write),
#   3. compares the hashes with WebRtcLib.kt: a difference fails the build (the app would refuse the download).
# Usage: tools/webrtc-assets.sh <owner/repo> [strict]   (strict = also fail when WebRtcLib.kt has no hash yet)
set -euo pipefail
REPO="$1"; STRICT="${2:-}"
KT=app/src/main/java/com/example/streamnest/WebRtcLib.kt
VER=$(grep -o 'LIB_VERSION = "[^"]*"' "$KT" | cut -d'"' -f2)
grep -q "io.getstream:stream-webrtc-android:$VER\"" app/build.gradle.kts || { echo "::error title=WebRTC::WebRtcLib.LIB_VERSION ($VER) is not the version in app/build.gradle.kts"; exit 1; }
AAR=$(find "$HOME/.gradle/caches" -name "stream-webrtc-android-$VER.aar" 2>/dev/null | head -1)
[ -n "$AAR" ] || { echo "::error title=WebRTC::stream-webrtc-android-$VER.aar not found in the Gradle cache"; exit 1; }
OUT=$(mktemp -d); mkdir -p "$OUT/webrtc"
bad=0; push=0
for ABI in arm64-v8a armeabi-v7a; do
  mkdir -p "$OUT/webrtc/$ABI"
  F="$OUT/webrtc/$ABI/libjingle_peerconnection_so.so"
  unzip -p "$AAR" "jni/$ABI/libjingle_peerconnection_so.so" > "$F"
  SHA=$(sha256sum "$F" | cut -d' ' -f1); KB=$(( $(stat -c%s "$F") / 1024 ))
  echo "::notice title=WebRTC $ABI ($KB KB)::\"$ABI\" to \"$SHA\","
  WANT=$(grep -o "\"$ABI\" to \"[^\"]*\"" "$KT" | cut -d'"' -f4)
  if [ "$WANT" != "$SHA" ]; then
    if [ ${#WANT} -ne 64 ] && [ "$STRICT" != "strict" ]; then echo "::warning title=WebRTC $ABI::WebRtcLib.kt has no hash yet — paste the line above"
    else echo "::error title=WebRTC $ABI::WebRtcLib.kt says $WANT, the AAR's library is $SHA"; bad=1; fi
  fi
  URL="https://raw.githubusercontent.com/$REPO/assets/webrtc/$ABI/libjingle_peerconnection_so.so"
  HAVE=$(curl -sfL "$URL" | sha256sum | cut -d' ' -f1 || true)
  [ "$HAVE" = "$SHA" ] && echo "assets branch: $ABI ok" || { echo "assets branch: $ABI missing or different ($HAVE)"; push=1; }
done
if [ "$push" = 1 ]; then
  [ -n "${GITHUB_TOKEN:-}" ] || { echo "::error title=WebRTC::GITHUB_TOKEN needed to update the assets branch"; exit 1; }
  W=$(mktemp -d)
  git clone -q --depth 1 -b assets "https://x-access-token:${GITHUB_TOKEN}@github.com/$REPO.git" "$W"
  rm -rf "$W/webrtc"; cp -r "$OUT/webrtc" "$W/webrtc"
  git -C "$W" add webrtc
  git -C "$W" -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit -q -m "WebRTC $VER native libraries (arm64-v8a, armeabi-v7a) for Watch together on demand"
  git -C "$W" push -q origin assets
  echo "::notice title=WebRTC::libraries $VER pushed to the assets branch"
fi
exit $bad
