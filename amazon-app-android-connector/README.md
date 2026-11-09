# Amazon Music Connector for Hitster (Android app)

The app plays the song of a scanned [Hitster](https://hitstergame.com) card in the **Amazon Music app of the same phone** and controls it with official Android media APIs. The card sets are in [../card-sets](../card-sets), their data comes from the [SongSeeker Hitster playlists](https://github.com/andygruber/songseeker-hitster-playlists) by Andreas Gruber (MIT License). The separate Desktop connector is in [../amazon-web-desktop-connector](../amazon-web-desktop-connector).

## Modes

Home has three buttons.

- **Play Mode:** scan a card. The song starts at 20 s (a quarter of its length for songs under 50 s). The screen hides title, artist and year. Tap the blurred box to show them, tap again to hide them. The revealed box, its blurred form, the Reader Mode box and the report overlay all use the order artist, year (large), title. A green box names the card and its card set ("Displaying card from ..."). An unknown card plays a test song and shows the reason in a red box.
- **Quiz Mode:** tick card sets (or "All sets"), then "Start". No camera. Random cards play one after another and the next one starts when the song ends. "Play next random card" starts it by hand. A card is played once until the deck is used up. The same song in several editions counts once. The deck is shuffled again then, and the screen says "All cards played, starting again".
- **Reader Mode:** scan a card to see artist, year, title and "(card#N)" at once. Nothing plays unless you tap "Send to Amazon Music app". It needs no notification access.

"Card Config" lists the card sets and imports CSV files from the phone (several at once). It links to the SongSeeker repository, where card sets can be downloaded. A set that is not installed gives the error "This card set is not installed. Import its CSV file in Card Config." Help, Open Source Licenses and About are on Home.

## Player controls

Play Mode and Quiz Mode show one row: **-10 s, Pause/Play, Stop/Restart, +10 s**, and a slider. The two middle buttons show the next possible action.

| State | Pause/Play | Stop/Restart |
| --- | --- | --- |
| playing | Pause | Stop |
| paused by hand | Play | Stop |
| stopped | Play | Restart |
| song ended | Play | Restart |

- **Pause** keeps the position. **Stop** pauses and seeks back to 20 s.
- **Play** after the end, after another song took over, or without a session runs the whole start sequence again for the card song.
- -10 s, +10 s and the slider refuse to move another song and say "The player is on another song".
- Leaving the screen (Back button, back gesture) stops the song like Stop and unmutes. "Scan next card" and going to the background do not.
- Before every request the song that plays is stopped and the stop is confirmed (up to 3 s). The screen then shows "Sending card info to the Amazon Music app ...".

## Start sequence

Amazon starts a loaded song at once from its beginning. To keep the intro silent the app does this:

1. stop the playing song and confirm it,
2. mute the music stream (`ADJUST_MUTE`, the volume level stays),
3. load the song and pause it as soon as its title shows (the session is polled every 50 ms),
4. seek to the start offset and wait until the position is there, repeat up to 4 times,
5. play and wait for PLAYING,
6. unmute and arm the end guard.

The mute lasts at most 12 s for the load. It is also undone when the request fails or the screen is left. If the app is killed while muted, it unmutes at the next start. A mute set by the user is never undone.

## Search and wrong versions

Attempt 1 sends `playFromSearch` with the query `<artist>, <title> [Single Version, <year>]` (`[Single Version]` without a year). The year is the card's year, the corrected one if there is a correction. The title extra is `<title> [Single Version, <year>]`, the artist extra is the plain artist.

If the title that loads has a marker word in a bracket or after a dash, and the card title does not have it, the song counts as another version (`VersionCheck`). The markers are remix, mix, live, karaoke, instrumental, cover, tribute, acoustic, demo, sped up, slowed, reverb, lullaby, originally performed, made famous and in the style of. Remastered, single version, album version, explicit, radio edit, mono, stereo, years and "feat." are fine.

In that case the app pauses the item and sends attempt 2 once: the classic query `<title> <artist>` with the plain title extra. It waits up to 3 s for an item with another media id. If that one is also another version, or nothing else comes, the song plays anyway and the screen says "Amazon played a different version: <title>" (info area in Play and Quiz Mode, status line in Reader Mode).

The loaded song must match the card title (case, accents and "(Remastered)" are ignored, one title may contain the other). The artist is not compared. Otherwise the app shows an error.

## How Amazon Music is reached

- **Control:** `MediaSessionManager.getActiveSessions()` returns the media session of Amazon Music. Android allows this only for an app with notification access, so the app has an empty `NotificationListenerService`. It reads no notifications.
- **Session exists:** `playFromSearch`, up to 3 tries 1.5 s apart. If the right title loads but stays paused, the app calls `play()`.
- **Cold start:** first a pause media button to Amazon's receiver, then the same key through `AudioManager`, then the `MEDIA_PLAY_FROM_SEARCH` intent. The intent only opens Amazon's home screen and plays nothing. Then `playFromSearch` as above.
- **Last fallback:** `https://music.amazon.<tld>/search/<query>` in the Amazon Music app. It is skipped when the right title is loaded already.
- **Bring-back:** if an Amazon screen was opened, the app brings its own screen to the front once the song plays (retries every 400 ms for 8 s). Android 10 and later may refuse this.

## End of the song

`EndGuard` keeps Amazon from going on with another song.

- **Muted end:** one second before the true end the music stream is muted once. The song plays to its end in silence. The screen says "Sound is muted until the next card". The mute stays until the next card plays at its start offset. The whole music stream of the phone is silent meanwhile, other apps too. It also ends on Stop, when the playhead is moved away from the end, when the screen is left, at the next app start and after 30 minutes at most.
- **Switch detector:** from the start of the card song the guard watches the session. If another item plays, it pauses it at once. This holds after the song has ended.
- **Quiz:** the muted end starts the next card. A Pause by hand or a Stop prevents it.
- **Play again** replays the card song. If the player is on another song, the whole start sequence runs again.
- The guard holds a wake lock (at most 35 minutes) while it watches. If Android kills the app during a song, the song plays on unmuted.

## Reader Mode and "Report as wrong"

- **Scan:** the same code read again within 4 s is the card still in front of the lens and is ignored. A card that was away for 4 s counts as a scan again. "corrected on this phone" marks a card with a correction. The counter reads "Scanned n, updated u" (cards scanned in this visit, cards with a correction on this phone).
- **Report as wrong** (Reader Mode, and Play Mode and Quiz Mode while the card is revealed) opens an overlay with the fields Artist, Year, Title, filled with the current values, and Cancel and Save. Save is on when a value differs and all are valid (year four digits from 1900 to this year, title and artist not empty). Title and artist are multiline fields. Line breaks become one space when saving. Cancel and Back close only the overlay. The overlay does not close on a tap outside. While it is open, the reader drops scans and the quiz holds its next card back. After Save the button reads "Reported, Edit again" and the shown card is refreshed in place.
- **Corrections** are written to `card_overrides.csv` in the app storage (`set,card,year,title,artist,time,source`, the last row per card wins). They apply to every mode at once and survive app updates and the refresh of the card sets. `reader_results.csv` is the log of the reports.
- **Export** in the top bar of Reader Mode shares the latest correction per card as `hitster-corrections-<date>.csv` through the Android share sheet (`FileProvider`, not exported). A line under the card says where to send the file.
- **Send to Amazon Music app** plays the shown card through the same start sequence as Play Mode. It is off with a reason under it when Amazon Music is missing or notification access is not granted. The status line reads "Sending ...", "Playing" or "Song ended. The sound stays muted until you send the next card or leave." Every scan that is not dropped stops a song started this way (like Stop, with unmute) and clears the line. Leaving Reader Mode stops it too.

## Setup

1. Install the Amazon Music app and log in.
2. Install `dist/Amazon-Music-Connector-for-Hitster-<version>-prod.apk` (Android asks about unknown sources, Play Protect asks once).
3. On Home tap "Open notification access settings" and switch the app on.
4. Back on Home both setup lines show a tick. Play Mode and Quiz Mode are on then. Reader Mode is always on.

## Debug and production apps

`./build.sh` builds the debug app, `./build.sh --prod` the production app. Each file name has the version `1.0.0-<date>.<time>`.

| | debug | production |
| --- | --- | --- |
| `INTERNET` permission | yes (log sink) | no, the app works offline |
| application id | `de.jobisoft.hitsterconnector.debug` | `de.jobisoft.hitsterconnector` |
| app name | Amazon Music Connector for Hitster (Debug) | Amazon Music Connector for Hitster |
| debug channel (`DebugLog`, log sink, "Send diagnostics") | on | off, removed by R8 |
| `QUERY_ALL_PACKAGES`, cleartext HTTP | yes | no |
| R8 shrinking and obfuscation | no | yes (`proguard-rules.pro`) |
| APK size | about 24 MB | about 3.3 MB |

Both builds have `WAKE_LOCK`, `CAMERA`, and `<queries>` for `com.amazon.mp3`.

- Both are signed with the debug keystore (`~/.android/debug.keystore`), but they are two separate apps. They install side by side and each has its own data (card sets, corrections, settings).
- **Notification access is granted per app.** The setup lines on Home check the app itself.
- Both apps listen to the media sessions, so only one of them should send a song at a time.
- `build.sh` sets JDK 21 and the SDK path at its top. The R8 mapping of a production build is copied to `dist/mapping/` (not committed), it decodes crash stack traces.

## Crashes

Every build writes an uncaught exception to `last_crash.txt` in the app storage (`util/CrashReporter.kt`). At the next start Home shows it in a dialog with **Copy**, **Share** and **Close**. Only Close deletes it. An exception in the play path becomes a red error box and is written there too.

## Debug channel (debug app only)

The debug app posts its events to a log sink on the PC and to logcat (tag `HitsterConnector`).

- Start the sink with `python3 tools/debug_sink.py` (port 8781, writes `tools/debug.log`, `GET /log` shows the last 300 events).
- The app finds the sink by itself. At start it sends the UDP broadcast `HITSTER-SINK?` to port 8782 and the sink answers with its HTTP port. Without an answer the app sends nothing, "Send diagnostics" searches again. Allow UDP 8782 in the firewall of the PC. Networks that block broadcasts between devices (some guest Wi-Fi) do not work.
- An event is `{"t": epoch ms, "tag": "...", "msg": "...", "data": {...}}`, sent in batches. A sink that is not running is silent.
- The first event `app started` has the app version, the application id, the device, the Amazon Music version and the notification access.
- Tags: `play`, `search` (`query`, `result`, `queue`, `final`), `start` (timings and `leakedIntroS`), `mute`, `unmute`, `stop`, `guard` (`switch detected` has `leakedMs`), `leave`, `quiz`, `reader`, `timing`, `foreground`, `ctl`, and the state of all media sessions every 500 ms during a step.
- Amazon does not expose its queue, so the `queue` event shows null.
- **Send diagnostics** on Home runs the play steps once for Blue Monday by New Order and lists Amazon packages and components.
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

The bundled CSV files in `card-sets/assets/csv/` belong to the app. The phone keeps the sha256 of the copy it wrote last (SharedPreferences `seeded_card_sets`). At every start a file is copied when it is missing and never was written, and overwritten when the bundled file differs from the record. A file the user deleted stays deleted. Files with other names, for example imported by the user, are never touched. Corrections are separate, so a refresh loses nothing. The display names come from `card_sets.csv`. A set without an entry shows its file name without `.csv`.

## Observed on a phone

Samsung SM-A326B, Android 13, Amazon Music 26.39.1:

- The `MEDIA_PLAY_FROM_SEARCH` intent only opens `MusicHomeActivity`.
- `playFromSearch` on an existing session plays the right song in about 2 s and `seekTo` works.
- After a cold start a loaded song can stay paused, so the app calls `play()`.
- A `MediaBrowser` connection to Amazon's service is refused.
- Search for "Rehab" by Amy Winehouse returned the remix "Rehab (Pharoahe Monch Remix) [Explicit]" first.

## Not verified

- The production app with R8 was never run on a phone.
- Whether the intro is silent, the mute sets in and is undone, and how long each step takes (`leakedIntroS` in the `start` event).
- Whether the last second stays silent, whether Amazon's auto-advance is audible before the detector pauses it (`leakedMs`), and whether the guard survives a long song with the screen off.
- Whether the search query with `[Single Version, <year>]` and the retry avoid remixes.
- The silent wake by media button, the bring-back of the app on Android 13, the layout of the overlay with the keyboard on a 360 dp phone and the share sheet of the export.
- The blur of the reveal box works from Android 12 only. Below that the real text is never composed while hidden.
- Amazon may expose no controllable session for some streams or refuse `seekTo`. Then the start offset, the buttons and the slider do not work.
- Android 14 and later may refuse to start Amazon's activity from the background.

Unit tests (`./gradlew :app:testDebugUnitTest`) cover the logic without a phone: stop confirmation, start jump, end decisions, switch detector, play and restart decisions, control buttons, search query, version check, quiz deck, corrections, reader results, card set names and seeding.
