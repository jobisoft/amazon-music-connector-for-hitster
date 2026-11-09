#!/usr/bin/env bash
# Build the Android app (plays through the Amazon Music app).
#
#   ./build.sh          debug build (debug channel, "Send diagnostics"), dist/Amazon-Music-Connector-for-Hitster-1.0.1-<stamp>-debug.apk
#   ./build.sh --prod   production build (R8, no debug channel), dist/Amazon-Music-Connector-for-Hitster-1.0.1-<stamp>-prod.apk
#
# The debug APK in dist/ stays where it is when you build the production one, and the other way round.
# Built with JDK 21 and the Android SDK, the paths below are the ones of this machine.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

PROD=0
[[ "${1:-}" == "--prod" ]] && PROD=1

echo "sdk.dir=$ANDROID_HOME" > android/local.properties
STAMP="$(date +%Y%m%d.%H%M)"
VERSION_CODE="$(( $(date +%s) / 60 ))"
if (( PROD )); then
  KIND="prod"; TASK=":app:assembleRelease"; SRC="android/app/build/outputs/apk/release/app-release.apk"
else
  KIND="debug"; TASK=":app:assembleDebug"; SRC="android/app/build/outputs/apk/debug/app-debug.apk"
fi
echo "JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME, version 1.0.1-$STAMP-$KIND (code $VERSION_CODE)"
(cd android && sh ./gradlew "$TASK" \
  -PbuildStamp="$STAMP" -PbuildVersionCode="$VERSION_CODE")

mkdir -p dist
OUT="dist/Amazon-Music-Connector-for-Hitster-1.0.1-$STAMP-$KIND.apk"
# Only the files of the same kind are replaced.
rm -f dist/*-"$KIND".apk
cp "$SRC" "$OUT"
echo "APK: $OUT ($(du -h "$OUT" | cut -f1))"
if (( PROD )); then
  mkdir -p dist/mapping && cp android/app/build/outputs/mapping/release/mapping.txt "dist/mapping/mapping-1.0.1-$STAMP-prod.txt" 2>/dev/null || true
fi
