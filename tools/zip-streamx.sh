#!/usr/bin/env bash
# 0.9.1: zips a StreamX source tree as the release repo expects it (top folder StreamNest/, no build output).
# Usage: tools/zip-streamx.sh /path/to/StreamX-tree /path/to/StreamX-<ver>-TV-<tv>.zip
set -euo pipefail
TREE="$(cd "$1" && pwd)"; OUT="$(realpath -m "$2")"
ST="$(mktemp -d)"; trap 'rm -rf "$ST"' EXIT
mkdir "$ST/StreamNest"
(cd "$TREE" && tar cf - --exclude=./app/build --exclude=./build --exclude=.gradle --exclude=node_modules --exclude=__pycache__ .) | (cd "$ST/StreamNest" && tar xf -)
rm -f "$OUT"; (cd "$ST" && zip -qr -X "$OUT" StreamNest)
echo "$OUT: $(unzip -l "$OUT" | tail -1)"
