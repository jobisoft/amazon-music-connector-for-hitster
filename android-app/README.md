# Amster (Android app)

The app plays the song of a scanned [Hitster](https://hitstergame.com) card in the **Amazon Music app of the same phone** and controls it with official Android media APIs. The card sets are in [../assets/card-data](../assets/card-data), their data is based in part on the [SongSeeker Hitster playlists](https://github.com/andygruber/songseeker-hitster-playlists) by Andreas Gruber (MIT License).

Tested with a Samsung SM-A326B, Android 13 and Amazon Music 26.39.1.

## Modes

Home has three mode cards (Play Mode, Quiz Mode, Reader Mode) and six smaller tiles (Options, Card Config, Export, Help, Open Source Licenses, About).

- **Play Mode:** scan a card. The song starts at the start position chosen in the Options (20 s by default, see Start position). The camera runs for 15 s (the screen shows "Scan card (Ns)" under it), then the app goes back to the main screen like Back. The screen hides title, artist and year. Tap the blurred box to show them, tap again to hide them. The revealed box, its blurred form, the Reader Mode box and the report overlay all use the order artist, year (large), title. A green box names the card and its card set ("Displaying card from ..."). An unknown card plays nothing. The screen says that the card is unknown and that it can be added manually in Reader Mode (for a missing set it also points to the project's GitHub page), and Cancel goes back to the camera.
- **Quiz Mode:** switch card sets on in a list (or "All sets"), then "Start". No camera. Random cards play one after another and the next one starts when the song ends. "Play next random card" starts it by hand. A card is played once until the deck is used up. The same song in several editions counts once. The deck is shuffled again then, and the screen says "All cards played, starting again".
- **Reader Mode:** scan a card to see artist, year, title and "(card#N)" at once. The camera view is the same as in Play Mode (the line "Scan card (Ns)" under the window, 15 s, then back to the main screen). After a scan the camera is off and the data view shows the card like Quiz Mode does: the set name in the green box at the top, the song in a glass box in the middle (a long press on it opens "Edit Card", the line "Long press to edit" under it says so), "Scan next card" at the bottom (it brings the camera back). Nothing plays and the mode does not talk to Amazon Music. It needs no notification access.

"Card Config" explains what a card set is, lists the card sets as a file list and imports CSV files from the phone (several at once), with a button "Check online" that opens the project page on GitHub for more set files. Deleting a set there also deletes its updates (`card_overrides.csv`), so the set is gone. A set that only exists as updates (read by the reader, no CSV file) is listed too and can be deleted the same way. It links to this repository. The app finds the columns `Card#`, `Title`, `Artist`, `Year`, `AmazonID`, `Album` and `Edition` by their names in the header, in any case and in any place, and ignores every other column. `Card#`, `Title`, `Artist` and `Year` are needed, the others may be missing or empty. A row may leave the year empty. The first filled value of `Edition` is the name of the set in the lists, a set without one shows its file name. A file without them shows "This CSV file needs the columns Card#, Title, Artist and Year." A card of a set that is not installed shows the error "The card set <file> is missing: This card set is not installed. Import its CSV file in Card Config. Card: <url>". Help, Open Source Licenses and About are on Home.

## Player controls

Play Mode and Quiz Mode show one row: **-10 s, Pause/Play, Stop/Play again, +10 s**, and a slider. The two middle buttons show the next possible action.

| State | Pause/Play | Stop/Play again |
| --- | --- | --- |
| playing | Pause | Stop |
| paused by hand | Play | Stop |
| stopped | Play | Play again |
| song ended | Play | Play again |

- **Pause** keeps the position. **Stop** pauses and seeks back to the start position.
- **Play** after the end, after another song took over, or without a session runs the whole start sequence again for the card song.
- -10 s, +10 s and the slider refuse to move another song and say "The player is on another song".
- Leaving the screen (back gesture or back button of the phone) stops the song like Stop (pause, back to the start offset). "Scan next card" pauses the song without moving it, because a new song follows. Going to the background stops nothing.
- When the scan mode starts (Play Mode or Quiz Mode opened, "Scan next card", Cancel) the song that plays is paused, fire and forget. A card then goes straight to the load. The screen shows "Sending song to the Amazon Music app.".

## Start sequence

Amazon plays a loaded song by itself from its beginning. The app listens: the list of active sessions and, for the session of Amazon Music, every change of the playback state and the metadata are reported as events on a thread of their own. Each report goes to one decision function (`PlayReactor.onSnapshot`) and the action it returns is executed in the same step. After the seek the app also looks at the session every 125 ms (step 5).

1. **The scan mode starts** (Play Mode or Quiz Mode opened, button "Scan next card", "Cancel" on the unknown card screen, "Cancel" on the sending screen): the song that plays gets `pause()`, fire and forget. The app does not wait for the answer and does not rewind.
2. **A card is known.** Play Mode: the camera turns on and with it the camera timeout of 15 s starts (`ScanTimeout`, the screen shows "Scan card (Ns)"). It runs out: the app goes back to the main screen like Back (the song is stopped, the volume is back). A scan stops the timeout. Quiz Mode: a card is picked.
2a. **The pre-load of a cold start** (only when the session of Amazon Music is empty, state NONE, after the wake of step 2): Amazon Music that was just woken replaces a card that is loaded into its empty session a moment later with its old queue, which plays another song. So the card is loaded once for nothing (`ColdPreload`): the volume goes to 0 with a timeout of 20 s, the end guard is not involved, the load goes out as in step 3. The app watches the session for up to 15 s (`ColdPreload.OBSERVE_MS`) and the sending screen counts down ("connecting to Amazon Music (15s)"). It waits until another song than ours plays, or the time is over. Our own song that plays is ignored. Then the song gets `pause()` and step 3 follows. Log tag `preload` (`start`, `snapshot`, `done` with the reason).
3. **The load:** the volume goes to 0 (`VolumeDuck`, the old level is remembered), with a timeout of 15 s that only puts the level back. The expected song of the end guard is cleared and its reload is armed (see End of the song). The listeners are opened, then `transportControls.playFromUri(...)` with the Amazon ID goes out (log: `search query by id`, step `i-playFromUri`). For about 0.5 s the session still shows the old song, paused, then our song appears (`id loaded`) and Amazon plays it from 0 s.
4. **The first PLAYING of our song** (the media id tells ours from another item): the end guard grabs the id and the title from the session, and one `seekTo` goes to the start offset, wherever the playhead is.
5. **Arrival:** every 125 ms (`PlayReactor.POLL_MS`), with or without a report (a seek from a position near the offset may get no report), the app checks whether the song plays and the playhead is at or after the start offset (minus 1.5 s, `StartJump.NEAR_S`). After 3 s (`PlayReactor.ARRIVE_MAX_MS`) the sequence goes on anyway. There is no second seek and no `play()`.
6. **Finish:** the volume goes back.

With the start position Off, with an unknown length or without the seek action, the first PLAYING report ends the sequence. The Stop button and leaving the screen are the only places that rewind: `pause()`, then `seekTo` to the start offset, then the volume goes back.


The only timers are the deadlines that the decision names (the 125 ms look, the 3 s wait, the 15 s limit) and the 4 s the item may take to show up. The `ev` events record every report with the time since the load request, the `act` events every seek with its reason.

The app never mutes the music stream. At the start of every request it only checks whether the stream is muted and unmutes it once if so (log tag `mute`, `stream was muted, unmuted before the request`). The check cannot tell who muted the stream, so a mute by the user is undone too. A volume turned down to zero is not a mute and stays as it is.

**Volume level during the load.** So that the first moments of a song are not heard, the volume level of the music stream is set to 0 when a card is loaded (step 3) and put back when the song plays at its start offset (step 6). A card with a text search keeps the level at 0 until then. The original level is written to disk before it is changed. It is restored on every way out of a request (success, failure, cancel, exception), when the screen is left, by a timeout of 15 s after the load (`VolumeDuck.LOAD_MAX_MS`, it does nothing but restore) and at the start of the app, if the app was killed while the level was 0. A level that is not 0 any more at that moment was set by the user and stays as it is. A level that was 0 before the request is left alone. With the start position Off nothing is ducked, because the song is not moved. The log tag is `duck` (`ducked`, `restored`, `duck disabled by setting`, `no duck, the start position is Off`, `level kept, it was changed meanwhile`, `still ducked, the remembered level stays`, `level is 0 already, nothing to duck`).

**Prefer Amazon ID over search.** The switch on the Options screen (on by default, stored on the phone, read at the start of every request) decides how a card with an Amazon ID is played. On, the ID is tried first and the text search follows only if that fails. Off, every card is searched by title and artist. The IDs were only checked with German Amazon accounts, so with accounts of other countries a card may play a wrong song.

**Start position.** The dropdown "Start position" on the Options screen sets where a song starts after it loads: Off, 10 s, 20 s (default), 30 s or the middle of the song. The setting is stored on the phone and read at the start of every request and by the controls, so a change counts from the next card. A song shorter than the offset plus 30 s starts at a quarter of its length (rounded down), the middle is half of the length. Off does not move the song: the app does not pause it while it starts, plays it if it stays paused, and Stop and Play again go to the beginning. The volume duck is switched off while the start position is Off, and the switch on Home is dimmed then. The log has `startPosition` in the `plan` and `start` events.

The switch "Lower volume before playback for a better experience" on the Options screen (on by default, stored on the phone) turns this off: the level is not changed, nothing on disk, no timer. The setting is read at the start of every request. It is a switch because the level is sent to Bluetooth headphones with absolute volume, and some headsets may react badly to it. A duck that is still pending from an earlier run is restored at the start of the app with the switch off too.

**Status bar.** Some phones show the title of the playing song in the status bar (the Samsung capsule of the live notifications, for example), which would give the card away. The switch "Hide the status bar while playing" on the Options screen (on by default, stored on the phone) hides the status bar while a card is on the screen of Play Mode and while the Quiz screen is open. It is read when the screen is shown. A swipe down from the top shows the bar for a moment, and it comes back when the screen is left. The navigation bar stays as it is. The Reader screen shows title and artist anyway and keeps the status bar. The notification shade is not affected.

## Search and wrong versions

Besides the wake of a cold start, the play request has three phases, and the log names each of them. A card with an `AmazonID` starts with the phase **load**: the app hands the session `playFromUri` with the web address of the track and the parameter `agent=google` (`https://music.amazon.de/tracks/<ASIN>?agent=google`, the IDs of the card sets are IDs of the German store). The id counts as loaded when the session shows its `MEDIA_ID` within 4 s (event `id loaded` with the time in ms). If it does not show, the event `id not shown` records what the session shows (`shownId`, `shownTitle`, `state`) and the search below takes over.

The phase **start** follows a loaded id: the app waits up to 15 s for PLAYING of our song, seeks once and looks until the song arrived (steps 4 and 5 above). No `pause()` and no `play()` is sent. If the track does not play within 15 s, the event `id start failed` has the reason (`buffering` or `not playing`) and the state, and the search takes over. The load and the start are two phases on purpose: a loaded id that only waits for the network is the right track, and it gets its time before anything else is tried.

The phase **search** is the fallback of both and the start of a card without an id. It sends one query and waits for the title to show (3 tries, 4 s each, 1.5 s apart). If it fails as well, the request ends with a red error box: "Amazon Music loaded the song but does not start it. Try the card again." (a title had shown) or "Amazon Music did not play the song. It may have ignored the request or started something else." Every phase change writes one `phase` event (tag `play`) with the time since the request.

The load screen shows one word under "Sending song to the Amazon Music app.": `connecting to Amazon Music` (cold start), `connecting to Amazon Music (Ns)` (the pre-load, counting down from 15), `loading`, `searching`, and while the loaded id does not play yet the state of the session: `waiting`, `preparing` (paused or stopped), `buffering`, `connecting`, `positioning`, `error`. After 10 s without a playing song the load screen offers **Cancel**. It ends the request, pauses Amazon Music and returns to the camera of Play Mode. In Quiz Mode, which has no camera, Cancel leaves to the card set picker like Back.

**Cold start.** Amazon Music that was just started has a media session before its player is ready. A session without title and without any action beyond the three `PLAY_FROM_*` ones (or without a state) counts as no session, so the request wakes Amazon Music first (the media button, `connecting to Amazon Music`). After a cold start Amazon may replace the loaded track with its own old queue a moment later. The end guard pauses the foreign item and loads the card again, once (see End of the song).

The search sends `playFromSearch` with the query `<artist>, <title>`. The extras are the focus (song), the title, the artist and the query.

The debug log shows what Amazon played: `search result` has the title and artist that Amazon shows, the media id, the duration and whether the song was loaded by its id (`viaId`). The screens say nothing about it, the user can look into the Amazon Music app. There is no second search.

The search accepts a loaded song only if it matches the card title (case, accents and text in brackets are ignored, one title may contain the other). The artist is not compared. A song loaded by its id is not compared by title. If no song is accepted, the app shows an error.

## How Amazon Music is reached

- **Control:** `MediaSessionManager.getActiveSessions()` returns the media session of Amazon Music. Android allows this only for an app with notification access, so the app has an empty `NotificationListenerService`. It reads no notifications.
- **Session exists:** the id route for a card with an `AmazonID`, else (or after it failed) `playFromSearch`, up to 3 tries 1.5 s apart.
- **Cold start:** first a pause media button to Amazon's receiver, then the same key through `AudioManager`, then the `MEDIA_PLAY_FROM_SEARCH` intent. The intent only opens Amazon's home screen and plays nothing. Then the id route or `playFromSearch` as above.
- **Bring-back:** if an Amazon screen was opened, the app brings its own screen to the front at the end of the request (retries every 400 ms for 8 s). Android 10 and later may refuse this.

## End of the song

`EndGuard` pauses the card song before it ends, so that Amazon cannot go on with another song. Like the start sequence it listens to the session and does not poll.

- **When it runs:** while Play Mode or Quiz Mode is open. It starts with the screen and stops when the screen is left. It holds a wake lock for that time, so the timer fires with the screen off and the app stays awake. If Android kills the app during a song, nothing pauses it at the end.
- **The expected song:** the guard protects one song, the expected song. It grabs the media id and the title from the session when the card song plays for the first time (step 4), for cards with and without an Amazon ID alike. The load of the next card clears it (step 3), until the next card plays the guard does nothing.
- **Pause in the end zone:** the end zone is the last second of a song (`EndDecision.END_ZONE_S`, one constant). Every report of the session plans one one-shot timer for the moment the playhead reaches the zone, from the reported position, the time of the report, the speed and the length. When the timer runs out the song is paused (pause, not stop) and counts as ended. The screen says "The song ended and is paused". The timer is planned again by every report and cancelled when the song is paused, buffers or another item takes over. A length below 3 s or an unknown length means no end handling.
- **End zone rule:** a report that shows the card song playing inside the end zone is paused at once, so a seek into the last second followed by Play (in Amazon, on headphones) does not play. The player screen does not resume there either. Play and Play again start the card song again from its start offset. A playhead moved back out of the zone (more than a second in front of it) lets the song end again.
- **End by a switch or a stop:** if Amazon goes on to another item, or stops the card song, within the last 5 s of its length (`EndDecision.SWITCH_END_WINDOW_S`), that is the end of the song as well, also when the timer was not due yet. The other item is paused as below, the screen says the song ended and the quiz goes on with the next card (reason `end switch` or `end stopped`). A switch in the middle of the song, a skip with a headphone key, is not an end. A pause by hand near the end is not an end either.
- **Switch detector:** the second safety net. As long as a song is expected, the guard watches the session. If another item plays or buffers, it pauses it at once. This holds after the song has ended.
- **Reload:** the load of a card arms it for 20 s (`EndGuard.RELOAD_WINDOW_MS`). In that time the first foreign item that replaces the expected song (Amazon's restored queue) is paused and the card is loaded again (`playFromUri` or the search, as the card was loaded), once. The next load arms it again.
- **Quiz:** the pause at the end starts the next card. A Pause by hand or a Stop prevents it.
- **Play again** replays the card song. After the end, if the player is on another song, or without a session, the whole start sequence runs again.
- The guard sends at most 6 pauses per expected song.
- Log tags `guard`: `started`, `stopped`, `expecting`, `expected song cleared`, `reload armed`, `reload`, `reload off, time is up`, `end timer armed` (`delayMs`, `positionS`, `durationS`, `zoneS`), `end paused` (`leftS`, the time left to the true end), `end switch` and `end stopped` (`positionS`, `durationS`, `leftS`, `to`: where the song was when Amazon moved on), `end zone play paused`, `playhead moved back, not ended any more`, `switch paused`, `switch confirmed` (`pausedMs`).

## Reader Mode and "Edit Card"

- **Scan:** a scan switches from the camera view to the data view, so the same card is read again only after "Scan next card". The same code read again within 4 s is the card still in front of the lens and is ignored. A card that was away for 4 s counts as a scan again. "Already modified on this phone" under the hint marks a card with a modification.
- **Edit Card** (a long press on the shown song in Play Mode, Quiz Mode and Reader Mode, the line "Tap to hide, long press to edit" in the game and "Long press to edit" in the reader under the song box says so) opens an overlay titled "Edit Card" with the fields Artist, Year, Title, Album and Amazon ID, filled with the current values, the button **Read via OCR** (not in a build made with `--no-ocr`), and Cancel and Save. Save is on when a value differs and all are valid (year four digits from 1900 to this year, title and artist not empty, the Amazon ID empty, a track ID or a pasted link of Amazon Music, otherwise the field shows "Not an Amazon track ID"). An empty Amazon ID means search only. Title, artist and album are multiline fields. Line breaks become one space when saving. Cancel and Back close only the overlay. The overlay does not close on a tap outside. While it is open, the reader drops scans and the quiz holds its next card back, also while the OCR page is open. After Save the shown card is refreshed in place.
- **Read via OCR** (the button of the edit overlay in Play Mode, in Reader Mode and when adding a card, Quiz Mode has none because it plays random cards and the card is not at hand) opens the OCR page. It is the camera view (the line "Scan card (Ns)" counts 15 s down, the time restarts after an analysis without a clear result and stands still while the analysis runs) with the plain text "Read song information from card (OCR)" under the window (no box, no button). The song of the game goes on playing. When the time is up and on Back the page closes and the overlay is shown again with its fields as they were. With a clear result the page closes and the overlay is shown with year, title and artist from the card (the Amazon ID and the album stay), the user still decides on Save or Cancel. Save follows the usual rule, it is on only when a value differs from the known one. The OCR works in two steps. First the camera collects photos: a picture counts when it is sharp and the whole back of the card is on it (artist, year and title found, not cut at the edge, about the same gap above and below the year). The text says "Show the back of the card." and shows two numbers: "Total OCR scans: 7" (the pictures of the camera that were sharp enough to be read) and "Good OCR scans: 1/2" (the pictures that count, 2 are needed). After 2 good photos the camera window is blurred with a spinner (the camera is off) and the analysis reads every photo three times: as it is, the area of the card cut out and enlarged, and that area with a local threshold. The text is read by Google ML Kit (text recognition for the Latin alphabet, the model is in the app, nothing leaves the phone). A card may be held in any direction: ML Kit tells the direction of the text of every line, the picture is turned until the text is upright (the log has `reader ocr rotated`). The small number of the card and the code of the set in the corners are not part of the artist or the title (lines under 60 percent of the biggest text line are dropped). Every reading that finds a card is a vote, the year, the artist and the title are decided one by one by the most frequent value. The engines drop the dots of umlauts and mix up a capital I and a small l, so readings that differ only in that are one group and the spelling with accents wins. Without a clear result the collection starts again ("Could not read the card. Try again."). Photos, readings and votes go to the debug log (`reader ocr frame checked`, `reader ocr analysis`, `reader ocr vote`, `reader ocr used`, the pictures to `tools/debug_images`). The OCR engine is Google ML Kit (not free software), every reading is a vote and the engine is in the debug log. The text fields of the overlay are kept in the `EditDraftStore`, so they survive the OCR page.
- **Corrections** are written to `card_overrides.csv` in the app storage (`set,card,year,title,artist,time,source,amazonId,album,edition`, the last row per card wins, a row without the `amazonId` column keeps the id of the card set and a row without the `album` column keeps the album. An empty `amazonId` or `album` clears the value. A row with an album but without a new id writes `-` in the `amazonId` column, which means the id stays). They apply to every mode at once, also the id the play path tries first, and survive app updates and the refresh of the card sets. `reader_results.csv` is the log of the reports (`time,set,card,verdict,ourYear,ourTitle,ourArtist,cardYear,cardTitle,cardArtist,source,ourAmazonId,cardAmazonId,ourAlbum,cardAlbum`, older lines without the last columns are still read). The events `reader verdict` carry `ourAmazonId`, `cardAmazonId`, `ourAlbum` and `cardAlbum`, and `changed` lists `amazonId` or `album` when it differs. The edit dialog of a wrong card has an Album field like Title, the album is not shown anywhere else in the app.
- **Export** (a tile on the main screen, the number of cards in brackets) shares the latest correction per card as `hitster-corrections-<date>.csv` (the columns of `card_overrides.csv`) through the Android share sheet (`FileProvider`, not exported).
- **Unknown cards:** a card that the app does not know shows "Unknown card <number>" and the name of its set (or the file name, and "This card set is not in the app." for a set without a name), and the buttons **Add Card** and **Cancel** (back to the camera view, there is no "Scan next card" here). A set that is not on the phone and has no name from earlier updates is named first: the overlay "New card set" asks "What is the edition called?" ("Edition name", Cancel, Continue). The name is stored as soon as it is entered, it stays when the overlay of "Add Card" is cancelled. **Add Card** opens the overlay "Add Card" with the fields of "Edit Card", all empty, and the same button **Read via OCR**. Save adds the card as a modification (`source` is `manual`, or `ocr` when the fields were filled by the OCR, the first modification of a new set carries the `edition`) and shows it like a scanned card. While an overlay is open, scans are ignored. A card that reads the same as it is already known is not stored as a modification, and **Save** of "Edit Card" stays off until a value differs. A set that exists only as modifications has no CSV file. Its cards come from `card_overrides.csv`, its name from the `edition` column, and the quiz lists it.
- **Scan window:** Play Mode and Reader Mode show the camera in a square with a square frame, because the cards are square (`SquareScanWindow`). The window is 90 percent of the width of the screen (held upright), and the frame fills it.

## Look

The app is always dark: a gradient from near black to deep navy behind every screen, translucent glass cards with a hairline border, a cyan to blue accent gradient for the main actions and soft corners. The colors are in `ui/theme/Palette.kt` and the unit test `ThemeContrastTest` checks the contrast of every text and background pair. All screens use `ScreenScaffold` (gradient, title, content at most 560 dp wide). There are no back buttons, the back gesture or back button of the phone goes back. In Play Mode and Quiz Mode the next button sits at the bottom of the screen and nothing moves when the song is shown. The app stays in portrait on every device (`screenOrientation` in the manifest). Screens fade into each other.

## Setup

1. Install the Amazon Music app and log in.
2. Install `dist/Amster-<version>-prod.apk` (Android asks about unknown sources, Play Protect asks once).
3. On Home tap "Open notification access settings" and switch the app on.
4. Back on Home both setup lines show a tick. Play Mode and Quiz Mode are on then. Reader Mode is always on.

## Debug, production and Store builds

`./build.sh` builds the debug app, `./build.sh --prod` the production app (an APK to install by hand), `./build.sh --aab` the bundle for the Google Play Store. The OCR for unknown cards (Google ML Kit, not free software, the model is in the app) is in every build. `./build.sh --prod --no-ocr` (build flag `-Pocr=false`) leaves it out of the production app, which is then small again (about 2.7 MB). With it the production app is larger, see the table. Each file name has the version `1.3.0-<yyyymmdd>.<hhmm>` and the kind `-debug`, `-prod` or `-store`.

| | debug | production | Store bundle |
| --- | --- | --- | --- |
| file | `-debug.apk` | `-prod.apk` | `-store.aab` |
| `INTERNET` permission | yes (log sink) | no, the app works offline | no |
| application id | `de.jobisoft.amster.apk.debug` | `de.jobisoft.amster.apk` | `de.jobisoft.amster` |
| app name | Amster (Debug) | Amster (Beta) | Amster |
| version name ends in | `-apk-debug` | `-apk` | `-store` |
| signed with | debug keystore | debug keystore | upload key |
| debug channel (`DebugLog`, log sink) | on | off, removed by R8 | off, removed by R8 |
| screens gallery (`src/debug`) | on | not in the app | not in the app |
| cleartext HTTP | yes | no | no |
| R8 shrinking and obfuscation | no | yes (`proguard-rules.pro`) | yes |
| OCR in the edit overlay (ML Kit) | yes | yes, not with `--no-ocr` | yes, not with `--no-ocr` |
| size | about 29 MB | about 8.5 MB (2.7 MB with `--no-ocr`) | like production |

Both builds have `WAKE_LOCK`, `CAMERA`, and `<queries>` for `com.amazon.mp3`. Both are built for 64-bit ARM only (`abiFilters`, every phone of the last years): the OCR library of ML Kit is 10 MB per processor type. An emulator with x86 and an old 32-bit phone cannot run them.

- The id is chosen by Gradle from the task: only a bundle (`bundleRelease`, also "Generate Signed Bundle" in Android Studio) gets `de.jobisoft.amster`. The APK builds carry `.apk` in their id, because they are signed with the debug keystore (`~/.android/debug.keystore`) and Android does not install an app over one with another signature. All three install side by side and each has its own data (card sets, corrections, settings).
- **Notification access is granted per app.** The setup lines on Home check the app itself.
- All installed Amster apps listen to the media sessions, so only one of them should send a song at a time.
- `build.sh` sets JDK 21 and the SDK path at its top. The R8 mapping of a production build is copied to `dist/mapping/` (not committed), it decodes crash stack traces.

## Store bundle

`./build.sh --aab` writes `dist/Amster-1.3.0-<stamp>-store.aab` (R8, no debug channel). It signs with the upload key `upload.jks` in the repository root (alias `upload`, Git ignores `*.jks` and `*.keystore`) and asks for its password. The password is not stored anywhere: the script hands it to Gradle in an environment variable for this one run and Gradle does not cache it. Keep a copy of the key somewhere safe.

```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

The key password is the one of the keystore (press Enter at that question). Other values: `AMSTER_KEYSTORE` (file), `AMSTER_KEY_ALIAS`, `AMSTER_KEY_PASSWORD`. A bundle cannot be installed by hand. To try one on a connected phone, `bundletool build-apks --bundle=<file>.aab --output=amster.apks --connected-device --ks=upload.jks --ks-key-alias=upload` and `bundletool install-apks --apks=amster.apks` make the APKs for that phone.

## Crashes

Every build writes an uncaught exception to `last_crash.txt` in the app storage (`util/CrashReporter.kt`). At the next start Home shows it in a dialog with **Copy**, **Share** and **Close**. Only Close deletes it. An exception in the play path becomes a red error box and is written there too.

## Screens gallery (debug app only)

The main screen of the debug app has the extra tile **Screens (debug)**. It opens a list like the file list of Card Config: one line per screen or state, a magnifier at the end opens it. The groups are Home (setup states, the crash dialog, the two toasts of Export), Play Mode camera, Play Mode and Quiz Mode result (sending, unknown card, unknown QR code, the song hidden and shown, every error text), Quiz Mode sets, Reader Mode (camera, card, unknown card, errors), OCR page, Dialogs (Edit Card, Add Card, the name of a new set), Card Config, CSV preview, and the real screens Options, Help, About and Open Source Licenses.

An entry shows the real composable with sample data. The camera is replaced by a dark box, buttons do nothing, and the edit overlay does not open (`LocalSampleMode`), so nothing is played, sent or saved. The gallery lives in `src/debug/java/.../gallery`, the production and store builds have a stub in `src/release` and no tile. To add a state, add a `GalleryEntry` to `GalleryEntries.kt`.

## Debug channel (debug app only)

The debug app posts its events to a log sink on the PC and to logcat (tag `Amster`).

- Start the sink with `python3 tools/debug_sink.py` (port 8781, writes `tools/debug.log`, `GET /log` shows the last 300 events).
- The app finds the sink by itself. At start it sends the UDP broadcast `AMSTER-SINK?` to port 8782 and the sink answers with its HTTP port. Without an answer the app sends nothing and searches again with the next events (at most every 10 s). Allow UDP 8782 in the firewall of the PC. Networks that block broadcasts between devices (some guest Wi-Fi) do not work.
- An event is `{"t": epoch ms, "tag": "...", "msg": "...", "data": {...}}`, sent in batches. A sink that is not running is silent.
- The first event `app started` has the app version, the application id, the device, the Amazon Music version and the notification access.
- Tags: `play` (`request`, `phase`, `id loaded`, `id not shown`, `id start failed`, `song plays`), `duck` (`ducked`, `restored`, `level kept, it was changed meanwhile`), `ev` (`snapshot`: every report of the session during a request with `ms` since the load request, `state`, `positionMs`, `mediaId` and `item`, which is ours, foreign or none), `act` (`seekTo` with `reason`, `ms` and the state that caused it), `search` (`query`, `query by id`, `result`, `queue`, `final`, `result` has `viaId`), `start` (the numbers of one attempt: `titleToPlayingMs` is the time from the title to the first PLAYING, `firstPlayingPositionS` where the playhead was then, `playingToSeekMs`, `seekToArrivedMs`, `doneState` and `donePositionS` the state and position when the sequence went on, `seekCalls`), `preload`, `mute`, `stop`, `guard`, `leave`, `quiz`, `reader`, `timing`, `foreground`, `ctl`, `control`, `crash`, `error`, the step tags `a0-mediaButton`, `a-intent`, `i-playFromUri`, `b-playFromSearch`.
- The log is live: read it while a card is scanned, the phases and their times are in the events listed above.
- `tools/reader_summary.py [debug.log] [reader_mismatches.csv]` reads the sink log and writes the cards whose reported values differ from ours.

## Test the intent with adb

```
adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -p com.amazon.mp3 \
  --es android.intent.extra.focus "vnd.android.cursor.item/audio" \
  --es android.intent.extra.title "Blue Monday" \
  --es android.intent.extra.artist "New Order" \
  --es query "Blue Monday New Order"
```

This only opens Amazon's home screen. Whether the session can be controlled shows with `adb shell dumpsys media_session | grep -A12 com.amazon.mp3`.

## Card sets

The bundled CSV files in `assets/card-data/` belong to the app. The phone keeps the sha256 of the copy it wrote last (SharedPreferences `seeded_card_sets`). At every start a file is copied when it is missing and never was written, and overwritten when the bundled file differs from the record. A file the user deleted stays deleted. Files with other names, for example imported by the user, are never touched. Corrections are separate, so a refresh loses nothing. The display names come from `card_sets.csv`. A set without an entry shows its file name without `.csv`.

## Tests

Unit tests (`./gradlew :app:testDebugUnitTest`) cover the logic without a phone: player states, start jump and the start positions, the reactions to the session reports (`PlayReactor`, `SessionSnapshot`), end decisions, the volume duck rules, the camera timeout, the pre-load of a cold start, switch detector, play and restart decisions, control buttons, search query, version check, quiz deck, corrections, reader results, card set names and seeding, the card set files (the bundled ones and the layouts of the upstream files), the `AmazonID` column and its media id, the Amazon ID of the overlay, crash reports, sink discovery, song identity, the report overlay and the result texts. The Python tools have tests in `tools/test_*.py`.
