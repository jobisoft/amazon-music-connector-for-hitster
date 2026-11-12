#!/usr/bin/env bash
# Build the Android app (plays through the Amazon Music app).
#
#   ./build.sh          debug build (debug channel), dist/Amster-1.3.0-<stamp>-debug.apk, id de.jobisoft.amster.apk.debug
#   ./build.sh --prod   production build (R8, no debug channel), dist/Amster-1.3.0-<stamp>-prod.apk, id de.jobisoft.amster.apk
#   ./build.sh --aab    Store bundle (R8, no debug channel, signed with the upload key), dist/Amster-1.3.0-<stamp>-store.aab, id de.jobisoft.amster
#   ./build.sh --prod --no-ocr   production build without the OCR for unknown cards (Google ML Kit), small (the default has it)
#
# The files in dist/ stay where they are when you build another kind. --aab signs with upload.jks in the
# repository root (Git ignores it) and asks for its password, which is not stored anywhere (see README).
# Built with JDK 21 and the Android SDK, the paths below are the ones of this machine.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

PROD=0
AAB=0
OCR_FLAG=""
for arg in "$@"; do
  case "$arg" in
    --prod) PROD=1 ;;
    --aab) AAB=1; PROD=1 ;;
    --no-ocr) OCR_FLAG="false" ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done
# OCR for unknown cards in the reader (Google ML Kit): every build has it. --no-ocr leaves it out of a production build (small app).

echo "sdk.dir=$ANDROID_HOME" > android/local.properties
STAMP="$(date +%Y%m%d.%H%M)"
VERSION_CODE="$(( $(date +%s) / 60 ))"
EXT="apk"
GRADLE_EXTRA=""
if (( AAB )); then
  export AMSTER_KEYSTORE="${AMSTER_KEYSTORE:-$PWD/../upload.jks}"
  [[ -f "$AMSTER_KEYSTORE" ]] || { echo "missing $AMSTER_KEYSTORE (the upload key, see README)" >&2; exit 2; }
  if [[ -z "${AMSTER_KEYSTORE_PASSWORD:-}" ]]; then
    [[ -t 0 ]] || { echo "no terminal to ask for the keystore password" >&2; exit 2; }
    read -rsp "Keystore password: " AMSTER_KEYSTORE_PASSWORD; echo
    export AMSTER_KEYSTORE_PASSWORD
  fi
  # no configuration cache: it could keep the password
  GRADLE_EXTRA="--no-configuration-cache"
  KIND="store"; TASK=":app:bundleRelease"; SRC="android/app/build/outputs/bundle/release/app-release.aab"; EXT="aab"
elif (( PROD )); then
  KIND="prod"; TASK=":app:assembleRelease"; SRC="android/app/build/outputs/apk/release/app-release.apk"
else
  KIND="debug"; TASK=":app:assembleDebug"; SRC="android/app/build/outputs/apk/debug/app-debug.apk"
fi
echo "JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME, version 1.3.0-$STAMP-$KIND (code $VERSION_CODE), ocr=$OCR_FLAG"
(cd android && sh ./gradlew "$TASK" $GRADLE_EXTRA \
  -PbuildStamp="$STAMP" -PbuildVersionCode="$VERSION_CODE" -Pocr="$OCR_FLAG")

mkdir -p dist
OUT="dist/Amster-1.3.0-$STAMP-$KIND.$EXT"
# Only the files of the same kind are replaced.
rm -f dist/*-"$KIND"."$EXT"
cp "$SRC" "$OUT"
echo "${EXT^^}: $OUT ($(du -h "$OUT" | cut -f1))"
if (( PROD )); then
  mkdir -p dist/mapping && cp android/app/build/outputs/mapping/release/mapping.txt "dist/mapping/mapping-1.3.0-$STAMP-$KIND.txt" 2>/dev/null || true
fi
