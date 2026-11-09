#!/usr/bin/env python3
"""Pack src/ into an installable .xpi.

Usage: python3 build.py [--apk path/to/app.apk] [output-dir]      (default: dist)

The version is taken from package.json and written into src/manifest.json.

The packed add-on gets the time of the build in its name ("... (build 2026-11-08
04:12:05)") and in build.json, so that the version that runs can be told apart
from older ones. The files in src/ stay untouched, a build changes only the XPI.

The Android app is bundled in the add-on, so that the helper app can hand it
to the phone. Without --apk the script looks for a debug or release build of
the sibling folder ../android. If it finds none, the add-on is built without
the app and the options page says so.
"""
import hashlib
import json
import os
import re
import shutil
import sys
import zipfile
from datetime import datetime

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "src")


APP_DIR = os.path.join(SRC, "app")
APK_CANDIDATES = [
    os.path.join(ROOT, "..", "android", "app", "build", "outputs", "apk", "release", "app-release.apk"),
    os.path.join(ROOT, "..", "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
]


def default_name():
    """The English name of the add-on, from the locale file."""
    with open(os.path.join(SRC, "_locales", "en", "messages.json"), encoding="utf-8") as f:
        return json.load(f)["extensionName"]["message"]


def android_version(apk):
    """versionName of the Android app: from the build metadata next to the APK, if there is any."""
    meta = os.path.join(os.path.dirname(apk), "output-metadata.json")
    try:
        with open(meta, encoding="utf-8") as f:
            return str(json.load(f)["elements"][0]["versionName"])
    except (OSError, KeyError, IndexError, ValueError):
        pass
    try:
        with open(os.path.join(ROOT, "..", "android", "app", "build.gradle.kts"), encoding="utf-8") as f:
            return re.search(r'versionName\s*=\s*"([^"]+)"', f.read()).group(1)
    except (OSError, AttributeError):
        return ""


def bundle_apk(apk):
    """Copy the APK into src/app/ and describe it in apk.json."""
    for name in os.listdir(APP_DIR):
        if name.endswith(".apk") or name == "apk.json":
            os.remove(os.path.join(APP_DIR, name))
    if not apk:
        print("No Android app found, building without it.")
        return
    with open(apk, "rb") as f:
        data = f.read()
    meta = {
        "file": "Amazon-Music-Desktop-Connector-for-Hitster.apk",
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "version": android_version(apk),
    }
    shutil.copyfile(apk, os.path.join(APP_DIR, meta["file"]))
    with open(os.path.join(APP_DIR, "apk.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
        f.write("\n")
    print("Bundled the Android app %s (%.1f MB) from %s" % (meta["version"], len(data) / 1048576, apk))


def main():
    args = sys.argv[1:]
    apk = None
    if "--apk" in args:
        i = args.index("--apk")
        apk = os.path.abspath(args[i + 1])
        del args[i:i + 2]
    else:
        apk = next((os.path.abspath(c) for c in APK_CANDIDATES if os.path.exists(c)), None)
    out_dir = args[0] if args else os.path.join(ROOT, "dist")
    os.makedirs(APP_DIR, exist_ok=True)
    bundle_apk(apk)
    with open(os.path.join(ROOT, "package.json"), encoding="utf-8") as f:
        version = json.load(f)["version"]

    manifest_path = os.path.join(SRC, "manifest.json")
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    manifest["version"] = version
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print("Set manifest version to", version)

    os.makedirs(out_dir, exist_ok=True)
    xpi = os.path.join(out_dir, "amazon-music-connector-for-hitster_%s.xpi" % version.replace(".", "_"))
    if os.path.exists(xpi):
        os.remove(xpi)
    stamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with zipfile.ZipFile(xpi, "w", zipfile.ZIP_DEFLATED) as z:
        for folder, _dirs, files in sorted(os.walk(SRC)):
            for name in sorted(files):
                full = os.path.join(folder, name)
                rel = os.path.relpath(full, SRC).replace(os.sep, "/")
                if rel == "manifest.json":
                    packed = dict(manifest)
                    packed["name"] = "%s (build %s)" % (default_name(), stamp)
                    z.writestr(rel, json.dumps(packed, indent=2) + "\n")
                else:
                    z.write(full, rel)
        z.writestr("build.json", json.dumps({"version": version, "builtAt": stamp}) + "\n")
    print("Build time:", stamp)
    print("Created", xpi)


if __name__ == "__main__":
    main()
