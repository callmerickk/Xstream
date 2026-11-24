#!/usr/bin/env bash
# Copies the shared source from the StreamX tree into this Xstream repo, so both apps build from identical code.
#
# Usage: tools/sync-shared.sh /path/to/StreamNest   (the StreamX source tree, the folder that holds app/)
#
# Shared (copied, replaced as a whole): app/src/main, app/src/phone, app/src/tv, app/libs, app/proguard-rules.pro
#
# Brand files (never copied, each app keeps its own):
#   app/build.gradle.kts        applicationId, versions, signing, and the brand values: BRAND_NAME, IS_BETA,
#                               UPDATE_REPO, UPDATE_CDN, APK_PHONE, APK_TV, SHARED_ROOT, INVITE_SCHEME/HOST/PATH,
#                               manifest placeholders (brandName, inviteScheme, inviteHost, invitePath) and
#                               resValues (app_name, brand_name, widget_start)
#   app/src/brand/res/          Xstream's logo colours (logo_signal, ic_fg_signal), added to phone + tv in Gradle
#   app/src/main/java/com/example/streamnest/Changelog.kt   each app's own release notes
#   settings.gradle.kts, .github/workflows/, RELEASE_NOTES.md, min-version.txt, docs/, tools/
#
# Brand.kt and Feature.kt are SHARED: they must exist in the StreamX tree too (the script stops if they don't).
set -euo pipefail

SRC="${1:-}"
DST="$(cd "$(dirname "$0")/.." && pwd)"
[ -n "$SRC" ] && [ -d "$SRC/app/src/main" ] || { echo "usage: $0 /path/to/StreamX-tree (folder with app/src/main)"; exit 1; }
SRC="$(cd "$SRC" && pwd)"
PKG=app/src/main/java/com/example/streamnest

# The StreamX tree must already use the brand layer, or this would bring hard-coded StreamX values back.
for f in "$PKG/Brand.kt" "$PKG/Feature.kt"; do
  [ -f "$SRC/$f" ] || { echo "stop: $SRC/$f is missing — add the brand layer to the StreamX tree first"; exit 1; }
done
grep -q '\${inviteScheme}' "$SRC/app/src/main/AndroidManifest.xml" || { echo "stop: StreamX manifest has no \${inviteScheme} placeholder"; exit 1; }
if grep -q 'name="app_name"' "$SRC/app/src/main/res/values/strings.xml"; then
  echo "stop: StreamX strings.xml still sets app_name (it must come from Gradle resValue)"; exit 1
fi

# Keep this app's brand files aside while the shared folders are replaced.
KEEP="$(mktemp -d)"
trap 'rm -rf "$KEEP"' EXIT
cp -a "$DST/$PKG/Changelog.kt" "$KEEP/Changelog.kt"
[ -f "$DST/app/src/main/assets/nuvio/libs.js" ] && cp -a "$DST/app/src/main/assets/nuvio/libs.js" "$KEEP/libs.js"

for d in app/src/main app/src/phone app/src/tv app/libs; do
  rm -rf "${DST:?}/$d"
  [ -d "$SRC/$d" ] && { mkdir -p "$(dirname "$DST/$d")"; cp -a "$SRC/$d" "$DST/$d"; }
done
cp -a "$SRC/app/proguard-rules.pro" "$DST/app/proguard-rules.pro"

cp -a "$KEEP/Changelog.kt" "$DST/$PKG/Changelog.kt"
[ -f "$KEEP/libs.js" ] && cp -a "$KEEP/libs.js" "$DST/app/src/main/assets/nuvio/libs.js"

echo "Synced shared source from $SRC"
echo "Kept: app/build.gradle.kts, app/src/brand/, Changelog.kt. Now: add a Changelog entry, bump the version, check build."
git -C "$DST" status --short | head -40
