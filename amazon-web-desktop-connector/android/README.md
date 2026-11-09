# Hitster Amazon Desktop Connector (Android app)

An Android app that scans the QR codes of [Hitster](https://hitstergame.com) cards and plays the songs on **Amazon Music** in the Firefox browser of your computer. It is the phone half of a pair. The other half is the [Firefox add-on](../addon).

The app does not play any music itself. Amazon offers no public search API for private accounts, so the add-on searches in your open Amazon Music tab, as if you had searched for the song yourself.

## How it works

1. The app scans a card. The QR code only holds a number, for example `https://www.hitstergame.com/de/00123`.
2. The app translates the number into title and artist with the CSV files of the community project [songseeker-hitster-playlists](https://github.com/andygruber/songseeker-hitster-playlists).
3. It sends title and artist to the helper app of the Firefox add-on on your computer (`POST /play`, secured with a token).
4. The add-on plays the song in Amazon Music.

The app never shows title or artist of the scanned song. It only reports whether the song was handed over to the computer.

## Origin

This app is a fork of [SubSonicSter](https://codeberg.org/langhaarschneider/SubSonicSter) by langhaarschneider (MIT License, see `LICENSE.mit`), taken at upstream commit `1b2822b` (version 2.2.0). The following parts come from there:

- QR code scanning (CameraX and ZXing) and the parsing of the Hitster URLs
- the import and management of the CSV card sets

Removed are the SubSonic client and the audio player. New are the connector client (`ConnectorRepository`), the connector settings and a result screen that hides the song.

## Setup

1. Install the Firefox add-on and its helper app on your computer. The options page of the add-on explains the steps and shows a QR code for this app.
2. Open the app in the same Wi-Fi. It looks for the helper app by itself: it sends a UDP broadcast to port 8765 and every helper answers. Several computers are offered as a list.
3. The options page of the add-on opens on the computer and asks whether the phone may connect. Press **Allow**. This is needed once. The app keeps the token, and it recognises the computer again by an id when its address changes.
4. Open Amazon Music in Firefox on the computer and log in.
5. Tap **Scan QR Code**.

If the search finds nothing, **Manual setup** takes the server address and the token from the options page of the add-on.

## Card sets

The card sets of [songseeker-hitster-playlists](https://github.com/andygruber/songseeker-hitster-playlists) come with the app (the folder `card-sets/assets/csv` at the top of this repository, a snapshot, see `LICENSES_THIRD_PARTY.md`; Gradle adds it to the assets of the app). They are copied into the storage of the app on the first start, so nothing has to be imported. To refresh the snapshot, copy the `hitster-*.csv` files from that project into `card-sets/assets/csv`. The button "Add all CSV-Files" in the card config loads the current ones from the web at any time.

## Unknown cards

If a card is in none of the card sets (a new edition, for example), the app does not stop. It sends one of three test songs to the computer. The screen still shows the error (which card set is missing and which card was scanned), and says that a test song is a stand-in. That way the whole chain from the phone to Amazon Music can be tried without the right card set. The text tells which card set is missing.

## Security note

The connection to the helper app uses plain HTTP inside your local network, which is why the app allows cleartext traffic. The token keeps other devices of the network from controlling your player. Do not use the setup in networks you do not trust.

## Build

Open the project in Android Studio, or run:

```
./gradlew assembleDebug
```

The build needs the Android SDK (compileSdk 36) and a JDK 17 or newer. Kotlin warnings are treated as errors, as in the upstream project.

## License

MIT, see `LICENSE.mit`. The upstream project is dual-licensed (Beerware and MIT), the original `LICENSE` file is kept for reference.
