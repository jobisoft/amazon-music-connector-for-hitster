#!/usr/bin/env bash
# Build the Android app (debug APK) and the Firefox add-on with the app inside.
#
#   ./build.sh            build both
#   ./build.sh --addon    only the add-on (uses the APK from the last Android build)
#
# Built with JDK 21 (JAVA_HOME, or one found in /usr/lib/jvm) and the
# Android SDK (ANDROID_HOME, or ~/Android/Sdk). The debug APK is signed with the
# debug key of Android Studio, which is fine for installing on your own phone.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ "${1:-}" != "--addon" ]]; then
  usable() { [[ -x "$1/bin/java" ]]; }
  if [[ -n "${JAVA_HOME:-}" ]] && ! usable "$JAVA_HOME"; then
    echo "JAVA_HOME=$JAVA_HOME has no bin/java, looking for another one." >&2
    unset JAVA_HOME
  fi
  if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in /usr/lib/jvm/java-21-* \
        /snap/android-studio/current/jbr /opt/android-studio/jbr "$HOME/android-studio/jbr"; do
      if usable "$candidate"; then export JAVA_HOME="$candidate"; break; fi
    done
  fi
  if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "No JDK found. We build with JDK 21, install one, for example: sudo apt install openjdk-21-jdk-headless" >&2
    exit 1
  fi
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
  if [[ ! -f android/local.properties ]]; then
    echo "sdk.dir=$ANDROID_HOME" > android/local.properties
  fi
  echo "JAVA_HOME=${JAVA_HOME:-<not set>}  ANDROID_HOME=$ANDROID_HOME"
  # Every build gets its own version: 1.0.1-<date>.<time>, and a growing version code
  STAMP="$(date +%Y%m%d.%H%M)"
  VERSION_CODE="$(( $(date +%s) / 60 ))"
  echo "Android app version: 1.0.1-$STAMP (code $VERSION_CODE)"
  (cd android && sh ./gradlew :app:assembleDebug -PbuildStamp="$STAMP" -PbuildVersionCode="$VERSION_CODE")
fi

APK=android/app/build/outputs/apk/debug/app-debug.apk
if [[ ! -f "$APK" ]]; then
  echo "No APK at $APK, the add-on would be built without the app." >&2
  exit 1
fi
(cd addon && python3 build.py --apk "../$APK")
