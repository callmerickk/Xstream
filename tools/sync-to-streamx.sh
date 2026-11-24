#!/usr/bin/env bash
# 0.9.1: the one-way sync. The Xstream repo is the single source of the shared code; this copies it INTO a StreamX
# source tree (the folder that holds app/), so StreamX is built from the very same code with its own brand values.
#
# Usage: tools/sync-to-streamx.sh /path/to/StreamX-tree
#
# Shared (copied, replaced as a whole): app/src/main, app/src/phone, app/src/tv, app/libs, app/proguard-rules.pro,
#   0.9.2: build.gradle.kts (the top one: plugin versions + the shared Firebase part, Crashlytics / Performance) and
#   app/google-services.json (Firebase config for all four apps: StreamX, StreamX TV, Xstream, Xstream TV)
#
# Brand files (never copied, StreamX keeps its own):
#   app/build.gradle.kts        applicationId, versions, signing and the brand values (BRAND_NAME "StreamX",
#                               IS_BETA false, UPDATE_REPO, UPDATE_CDN, APK names, SHARED_ROOT "shared", invite links)
#   app/src/brand/              Xstream's logo colours and Music launcher manifest (StreamX has none)
#   app/src/main/java/com/example/streamnest/Changelog.kt   StreamX's own release notes
#   app/src/main/assets/nuvio/libs.js (built by the workflow), settings.gradle.kts, RELEASE_NOTES.md, tools/
#
# What StreamX turns on is decided only by Feature.STABLE (Feature.kt, shared): IS_BETA is false there, so every
# other switch is off. To promote a feature: add its name to Feature.STABLE here, run this script, bump StreamX's
# version in its build.gradle.kts, write its Changelog entry, zip and publish.
set -euo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
DST="${1:-}"
[ -n "$DST" ] && [ -d "$DST/app/src/main" ] || { echo "usage: $0 /path/to/StreamX-tree (folder with app/src/main)"; exit 1; }
DST="$(cd "$DST" && pwd)"
[ "$SRC" != "$DST" ] || { echo "stop: source and target are the same folder"; exit 1; }
PKG=app/src/main/java/com/example/streamnest
G="$DST/app/build.gradle.kts"

# The StreamX tree must carry the brand layer with StreamX's values, or the shared code would not compile there
# (Brand.kt reads BuildConfig.BRAND_NAME …) or would build a StreamX with Xstream's identity.
grep -q '"BRAND_NAME" to "StreamX"' "$G" || { echo "stop: $G has no StreamX brand map (\"BRAND_NAME\" to \"StreamX\")"; exit 1; }
grep -q 'val brandBeta = false' "$G" || { echo "stop: $G must say val brandBeta = false (StreamX is the stable app)"; exit 1; }
grep -q 'applicationId = "com.example.streamnest"' "$G" || { echo "stop: $G is not StreamX's (applicationId)"; exit 1; }

KEEP="$(mktemp -d)"
trap 'rm -rf "$KEEP"' EXIT
cp -a "$DST/$PKG/Changelog.kt" "$KEEP/Changelog.kt"
[ -f "$DST/app/src/main/assets/nuvio/libs.js" ] && cp -a "$DST/app/src/main/assets/nuvio/libs.js" "$KEEP/libs.js"

for d in app/src/main app/src/phone app/src/tv app/libs; do
  rm -rf "${DST:?}/$d"
  [ -d "$SRC/$d" ] && { mkdir -p "$(dirname "$DST/$d")"; cp -a "$SRC/$d" "$DST/$d"; }
done
cp -a "$SRC/app/proguard-rules.pro" "$DST/app/proguard-rules.pro"
# 0.9.2: the shared Gradle part (top build file) and the Firebase config travel too.
cp -a "$SRC/build.gradle.kts" "$DST/build.gradle.kts"
cp -a "$SRC/app/google-services.json" "$DST/app/google-services.json"
grep -q '"com.example.streamnest"' "$DST/app/google-services.json" || { echo "stop: google-services.json has no StreamX app (com.example.streamnest)"; exit 1; }
# Build output and the workflow-built Nuvio bundle never travel.
rm -rf "$DST/app/src/main/assets/nuvio/libs.js"

cp -a "$KEEP/Changelog.kt" "$DST/$PKG/Changelog.kt"
[ -f "$KEEP/libs.js" ] && cp -a "$KEEP/libs.js" "$DST/app/src/main/assets/nuvio/libs.js"

echo "Synced shared code from $SRC into $DST"
echo "Kept StreamX's: app/build.gradle.kts, Changelog.kt. On in StreamX: $(grep -o 'STABLE: Set<String> = setOf([^)]*)' "$DST/$PKG/Feature.kt")"
