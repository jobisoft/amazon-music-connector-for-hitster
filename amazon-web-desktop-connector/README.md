# Amazon Music Connector for Hitster

Play the songs of [Hitster](https://hitstergame.com) cards on Amazon Music. You scan a card with your phone, and the song plays in the browser on your computer.

Amazon offers no public search API for private accounts, so the Firefox add-on searches in the Amazon Music web player for you, as if you had typed the search yourself.

```
Android app  --HTTP-->  helper app  --native messaging-->  Firefox add-on  -->  Amazon Music tab
 (scans card)           (on the PC)                         (content script)
```

| Folder | What it is |
| --- | --- |
| [android](android) | The Android app. Scans the QR code, translates it into title and artist, and sends both to the helper app. A fork of [SubSonicSter](https://codeberg.org/langhaarschneider/SubSonicSter). MIT License. |
| [addon](addon) | The Firefox add-on with its native helper app. The helper listens on the local network and hands the requests to the add-on, which drives the Amazon Music tab. MPL 2.0. |

## Status

This was a first attempt and is not developed further for now. The add-on is not published in the Firefox Add-on Store. It works only as a temporary add-on, loaded as a debug install through `about:debugging` (Firefox forgets it when it closes). The Android app and the helper app are complete and work together with it, see the README of each folder for details.

## Build everything

```
./build.sh
```

builds the Android app (debug APK) and then the add-on with the app inside. The result is `addon/dist/amazon-music-connector-for-hitster_<version>.xpi`. It needs a JDK 17 to 24 (not 25) and the Android SDK, see the header of the script.
