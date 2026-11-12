# Amster (Gradle project)

The Gradle project of the Android app. What the app does, how to set it up and what the debug build adds is in [../README.md](../README.md).

## Build

From the folder above, `./build.sh` builds the debug APK and `./build.sh --prod` the production APK into `dist/`. Or in this folder:

```
./gradlew assembleDebug
./gradlew assembleRelease
```

Built with the Android SDK (compileSdk 36), JDK 21 and Gradle 8.13 (the wrapper). Kotlin, Java and lint warnings are errors.

```
./gradlew :app:testDebugUnitTest :app:lintVitalRelease
```

## Origin and license

The app is a fork of [SubSonicSter](https://codeberg.org/langhaarschneider/SubSonicSter) by langhaarschneider (MIT License, see `LICENSE.mit`). The QR code scanning (CameraX and ZXing), the parsing of the Hitster URLs and the management of the CSV card sets come from there. The upstream project is dual-licensed (Beerware and MIT), the original `LICENSE` file is kept for reference. The app is published under the MIT License. The libraries it uses are listed in `LICENSES_THIRD_PARTY.md`.
