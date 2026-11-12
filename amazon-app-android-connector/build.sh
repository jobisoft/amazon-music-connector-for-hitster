#!/usr/bin/env bash
# Build the Android app (plays through the Amazon Music app).
#
#   ./build.sh          debug build (debug channel), dist/Amazon-Music-Connector-for-Hitster-1.1.0-<stamp>-debug.apk
#   ./build.sh --prod   production build (R8, no debug channel), dist/Amazon-Music-Connector-for-Hitster-1.1.0-<stamp>-prod.apk
#   ./build.sh --prod --no-ocr   production build without the OCR for unknown cards (Google ML Kit), small (the default has it)
#
# The debug APK in dist/ stays where it is when you build the production one, and the other way round.
# Built with JDK 21 and the Android SDK, the paths below are the ones of this machine.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

PROD=0
OCR_FLAG=""
for arg in "$@"; do
  case "$arg" in
    --prod) PROD=1 ;;
    --no-ocr) OCR_FLAG="false" ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done
# OCR for unknown cards in the reader (Google ML Kit): every build has it. --no-ocr leaves it out of a production build (small app).

echo "sdk.dir=$ANDROID_HOME" > android/local.properties
STAMP="$(date +%Y%m%d.%H%M)"
VERSION_CODE="$(( $(date +%s) / 60 ))"
if (( PROD )); then
  KIND="prod"; TASK=":app:assembleRelease"; SRC="android/app/build/outputs/apk/release/app-release.apk"
else
  KIND="debug"; TASK=":app:assembleDebug"; SRC="android/app/build/outputs/apk/debug/app-debug.apk"
fi
echo "JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME, version 1.1.0-$STAMP-$KIND (code $VERSION_CODE), ocr=$OCR_FLAG"
(cd android && sh ./gradlew "$TASK" \
  -PbuildStamp="$STAMP" -PbuildVersionCode="$VERSION_CODE" -Pocr="$OCR_FLAG")

mkdir -p dist
OUT="dist/Amazon-Music-Connector-for-Hitster-1.1.0-$STAMP-$KIND.apk"
# Only the files of the same kind are replaced.
rm -f dist/*-"$KIND".apk
cp "$SRC" "$OUT"
echo "APK: $OUT ($(du -h "$OUT" | cut -f1))"
if (( PROD )); then
  mkdir -p dist/mapping && cp android/app/build/outputs/mapping/release/mapping.txt "dist/mapping/mapping-1.1.0-$STAMP-prod.txt" 2>/dev/null || true
fi
