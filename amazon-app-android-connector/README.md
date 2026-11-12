# Amazon Music Connector for Hitster (Android app)

The app plays the song of a scanned [Hitster](https://hitstergame.com) card in the **Amazon Music app of the same phone** and controls it with official Android media APIs. The card sets are in [../card-sets](../card-sets), their data comes from the [SongSeeker Hitster playlists](https://github.com/andygruber/songseeker-hitster-playlists) by Andreas Gruber (MIT License). The separate Desktop connector is in [../amazon-web-desktop-connector](../amazon-web-desktop-connector).

Tested with a Samsung SM-A326B, Android 13 and Amazon Music 26.39.1.

## Modes

Home has three mode buttons.

- **Play Mode:** scan a card. The song starts at the start position chosen in the Options (20 s by default, see Start position). The screen hides title, artist and year. Tap the blurred box to show them, tap again to hide them. The revealed box, its blurred form, the Reader Mode box and the report overlay all use the order artist, year (large), title. A green box names the card and its card set ("Displaying card from ..."). An unknown card plays a test song and shows the reason in a red box.
- **Quiz Mode:** tick card sets (or "All sets"), then "Start". No camera. Random cards play one after another and the next one starts when the song ends. "Play next random card" starts it by hand. A card is played once until the deck is used up. The same song in several editions counts once. The deck is shuffled again then, and the screen says "All cards played, starting again".
- **Reader Mode:** scan a card to see artist, year, title and "(card#N)" at once. Nothing plays unless you tap "Send to Amazon Music app". It needs no notification access.

"Card Config" lists the card sets and imports CSV files from the phone (several at once). It links to this repository and to the SongSeeker repository, where card sets can be downloaded. The app finds the columns `Card#`, `Title`, `Artist`, `Year` and `AmazonID` by their names in the header, in any case and in any place, and ignores every other column. So the upstream files of SongSeeker, which have more columns, work as well, and their cards are searched by text. `Card#`, `Title` and `Artist` are needed, `Year` and `AmazonID` may be missing or empty. A file without them shows "This CSV file needs the columns Card#, Title and Artist." A card of a set that is not installed shows the error "The card set <file> is missing: This card set is not installed. Import its CSV file in Card Config. Card: <url>". Help, Open Source Licenses and About are on Home.

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
- Leaving the screen (Back button, back gesture) and "Scan next card" stop the song like Stop (pause, back to the start offset). Going to the background does not.
- Before every request the song that plays is stopped and the stop is confirmed (up to 3 s). The screen then shows "Sending card info to the Amazon Music app ...".

## Start sequence

Amazon may start a loaded song by itself from its beginning. The app does not poll the session, it listens. The list of active sessions and, for the session of Amazon Music, every change of the playback state and the metadata are reported as events on a thread of their own. Each report goes to one decision function (`PlayReactor.onSnapshot`) and the action it returns is executed in the same step:

1. Stop the song that plays and confirm it (up to 3 s).
2. Open the listeners, then send the load request, so that no report is missed.
3. While the request is armed (until it sends its own final `play()`), every report that shows a player that is not paused (loading, buffering, playing) gets a `pause()` at once, whether the item is Amazon's old restored one or ours. One pause goes out per distinct state and media id, a further report that still shows an active player gets another one, at most 12 per request. A freshly loaded item may be reported first as paused at position 0 and start by itself a moment later, so the first report of our item gets a `pause()` even when it already says PAUSED, once per load, and the seek goes out in the same step.
4. Only then the item matters. Ours is moved to the start offset with `seekTo`, as soon as a pause is out and the session offers the seek action and knows the length. A seek that moved nothing is sent again after 1.2 s, at most 4 times. A new foreign item that is paused is a replacement, the id is loaded again.
5. When our item is paused near the start offset (after a cold start also once it stayed ours for 1 s) the app sends the final `play()`.
6. From there nothing is paused any more. The app waits for PLAYING (a paused session gets `play()` again after 1.5 s, at most 15 s) and sends one `seekTo` if the player starts far below the offset. Then the end guard is armed.

The only timers are deadlines that the decision names (the settle time, the answer to a seek, the play again, the 15 s limit) and the 4 s the item may take to show up. The `ev` events record every report with the time since the load request, the `act` events every pause, seek and play with its reason.

The app never mutes the music stream. At the start of every request it only checks whether the stream is muted and unmutes it once if so (log tag `mute`, `stream was muted, unmuted before the request`). The check cannot tell who muted the stream, so a mute by the user is undone too. A volume turned down to zero is not a mute and stays as it is.

**Volume level during the load.** So that the first moments of a song are not heard, the volume level of the music stream is set to 0 at the start of every request, right after that check, and put back as soon as the final `play()` was sent after the seek. A reload and the search keep the level at 0 until a final `play()` was sent, it is restored once. The original level is written to disk before it is changed. It is restored on every way out of a request (success, failure, cancel, exception, "Scan next card", leaving the screen), by a hard timeout of 20 s (`DUCK_MAX_MS`), and at the start of the app, if the app was killed while the level was 0. A level that is not 0 any more at that moment was set by the user and stays as it is. A level that was 0 before the request is left alone. With the start position Off nothing is ducked, because the song is not moved. The log tag is `duck` (`ducked`, `restored`, `duck disabled by setting`, `no duck, the start position is Off`, `level kept, it was changed meanwhile`, `still ducked, the remembered level stays`, `level is 0 already, nothing to duck`).

**Prefer Amazon ID over search.** The switch on the Options screen (on by default, stored on the phone, read at the start of every request) decides how a card with an Amazon ID is played. On, the ID is tried first and the text search follows only if that fails. Off, every card is searched by title and artist. The IDs were only checked with German Amazon accounts, so with accounts of other countries a card may play a wrong song.

**Start position.** The dropdown "Start position" on the Options screen sets where a song starts after it loads: Off, 10 s, 20 s (default), 30 s or the middle of the song. The setting is stored on the phone and read at the start of every request and by the controls, so a change counts from the next card. A song shorter than the offset plus 30 s starts at a quarter of its length (rounded down), the middle is half of the length. Off does not move the song: the app does not pause it while it starts, plays it if it stays paused, and Stop and Play again go to the beginning. The volume duck is switched off while the start position is Off, and the switch on Home is dimmed then. The log has `startPosition` in the `plan` and `start` events.

The switch "Lower volume before playback for a better experience" on the Options screen (on by default, stored on the phone) turns this off: the level is not changed, nothing on disk, no timer. The setting is read at the start of every request. It is a switch because the level is sent to Bluetooth headphones with absolute volume, and some headsets may react badly to it. A duck that is still pending from an earlier run is restored at the start of the app with the switch off too.

**Status bar.** Some phones show the title of the playing song in the status bar (the Samsung capsule of the live notifications, for example), which would give the card away. The switch "Hide the status bar while playing" on the Options screen (on by default, stored on the phone) hides the status bar while a card is on the screen of Play Mode and while the Quiz screen is open. It is read when the screen is shown. A swipe down from the top shows the bar for a moment, and it comes back when the screen is left. The navigation bar stays as it is. The Reader screen shows title and artist anyway and keeps the status bar. The notification shade is not affected.

## Search and wrong versions

Besides the wake of a cold start, the play request has three phases, and the log names each of them. A card with an `AmazonID` starts with the phase **load**: the app hands the session `playFromUri` with the web address of the track and the parameter `agent=google` (`https://music.amazon.de/tracks/<ASIN>?agent=google`, the IDs of the card sets are IDs of the German store). The id counts as loaded when the session shows its `MEDIA_ID` within 4 s (event `id loaded` with the time in ms). If it does not show, the event `id not shown` records what the session shows (`shownId`, `shownTitle`, `state`) and the search below takes over.

The phase **start** follows a loaded id: pause, jump to the start offset, play. The app waits up to 15 s for PLAYING and sends `play()` again only while the session is paused or stopped. A session that is buffering is not asked again (event `buffering wait` with the state). If the track does not play within 15 s, the event `id start failed` has the reason (`buffering` or `not playing`) and the state, and the search takes over. The load and the start are two phases on purpose: a loaded id that only waits for the network is the right track, and it gets its time before anything else is tried.

The phase **search** is the fallback of both and the start of a card without an id. It sends one query and waits for the title to show (3 tries, 4 s each, 1.5 s apart). If it fails as well, the request ends with a red error box: "Amazon Music loaded the song but does not start it. Try the card again." (a title had shown) or "Amazon Music did not play the song. It may have ignored the request or started something else." Every phase change writes one `phase` event (tag `play`) with the time since the request.

The load screen shows one word under "Sending card info to the Amazon Music app ...": `connecting to Amazon Music` (cold start), `loading`, `searching`, and while the loaded id does not play yet the state of the session: `waiting`, `preparing` (paused or stopped, the app pauses and seeks the track itself), `buffering`, `connecting`, `positioning`, `error`. Reader Mode shows the same line under its button. After 10 s without a playing song the load screen offers **Cancel**. It ends the request, stops Amazon Music like "Scan next card" and returns to the camera of Play Mode. In Quiz Mode, which has no camera, Cancel leaves to the card set picker like Back.

**Cold start.** Amazon Music that was just started has a media session before its player is ready. A session without title and without any action beyond the three `PLAY_FROM_*` ones (or without a state) counts as no session, so the request wakes Amazon Music first (the media button, `connecting to Amazon Music`). After a cold start Amazon may replace the loaded track with its own old queue a moment later. The listeners pause Amazon's old item as soon as it loads or plays, before anything else is done with it. The final `play()` of our item then waits until it has stayed ours for 1 s (event `settle`, outcome `settled` or `replaced`). If another item replaced ours, in the settle time or during the start phase, the event `session item replaced` records the wanted id, the foreign title and media id, the phase and the time, and the id is loaded again. This happens at most twice and only within 12 s of the start of the id route, then the search takes over. With a running player there is no settle wait, but the reload still applies.

The search sends `playFromSearch` with the query `<artist>, <title>`. The extras are the focus (song), the title, the artist and the query.

If the title that loads has a marker word in a bracket or after a dash, and the card title does not have it, the song counts as another version (`VersionCheck`). The markers are remix, mix, live, karaoke, instrumental, cover, tribute, acoustic, demo, sped up, slowed, reverb, lullaby, originally performed, made famous and in the style of. Remastered, single version, album version, explicit, radio edit, mono, stereo, years and "feat." are fine.

In that case the song plays anyway and the screen says "Amazon played a different version: <title>" (info area in Play and Quiz Mode, status line in Reader Mode). There is no second search.

The search accepts a loaded song only if it matches the card title (case, accents and text in brackets are ignored, one title may contain the other). The artist is not compared. A song loaded by its id is not compared by title. If no song is accepted, the app shows an error.

## How Amazon Music is reached

- **Control:** `MediaSessionManager.getActiveSessions()` returns the media session of Amazon Music. Android allows this only for an app with notification access, so the app has an empty `NotificationListenerService`. It reads no notifications.
- **Session exists:** the id route for a card with an `AmazonID`, else (or after it failed) `playFromSearch`, up to 3 tries 1.5 s apart. If the right title loads but stays paused, the app calls `play()`.
- **Cold start:** first a pause media button to Amazon's receiver, then the same key through `AudioManager`, then the `MEDIA_PLAY_FROM_SEARCH` intent. The intent only opens Amazon's home screen and plays nothing. Then the id route or `playFromSearch` as above, with the settle check described under Cold start.
- **Bring-back:** if an Amazon screen was opened, the app brings its own screen to the front at the end of the request (retries every 400 ms for 8 s). Android 10 and later may refuse this.

## End of the song

`EndGuard` pauses the card song before it ends, so that Amazon cannot go on with another song. Like the start sequence it listens to the session and does not poll.

- **Pause in the end zone:** the end zone is the last second of a song (`EndDecision.END_ZONE_S`, one constant). Every report of the session plans one one-shot timer for the moment the playhead reaches the zone, from the reported position, the time of the report, the speed and the length. When the timer runs out the song is paused (pause, not stop) and counts as ended. The screen says "The song ended and is paused". The timer is planned again by every report and cancelled when the song is paused, buffers or another item takes over. A length below 3 s or an unknown length means no end handling.
- **End zone rule:** a report that shows the card song playing inside the end zone is paused at once, so a seek into the last second followed by Play (in Amazon, on headphones) does not play. The player screen does not resume there either. Play and Play again start the card song again from its start offset. A playhead moved back out of the zone (more than a second in front of it) lets the song end again.
- **End by a switch or a stop:** if Amazon goes on to another item, or stops the card song, within the last 5 s of its length (`EndDecision.SWITCH_END_WINDOW_S`), that is the end of the song as well, also when the timer was not due yet. The other item is paused as below, the screen says the song ended and the quiz goes on with the next card (reason `end switch` or `end stopped`). A switch in the middle of the song, a skip with a headphone key, is not an end. A pause by hand near the end is not an end either.
- **Switch detector:** the second safety net. From the start of the card song the guard watches the session. If another item plays or buffers, it pauses it at once. This holds after the song has ended.
- **Quiz:** the pause at the end starts the next card. A Pause by hand or a Stop prevents it.
- **Play again** replays the card song. After the end, if the player is on another song, or without a session, the whole start sequence runs again.
- The guard stops watching 30 minutes after its last arming. Every new card song and every move of the playhead arms it again and starts the 30 minutes anew.
- The guard sends at most 6 pauses per arming. It holds a wake lock (at most 35 minutes) while it watches, so the timer fires with the screen off. If Android kills the app during a song, nothing pauses it at the end.
- Log tags `guard`: `armed`, `re-armed`, `end timer armed` (`delayMs`, `positionS`, `durationS`, `zoneS`), `end paused` (`leftS`, the time left to the true end), `end switch` and `end stopped` (`positionS`, `durationS`, `leftS`, `to`: where the song was when Amazon moved on), `end zone play paused`, `playhead moved back, not ended any more`, `switch paused`, `switch confirmed` (`pausedMs`), `disarmed`.

## Reader Mode and "Report as wrong"

- **Scan:** the same code read again within 4 s is the card still in front of the lens and is ignored. A card that was away for 4 s counts as a scan again. "corrected on this phone" marks a card with a correction. The counter reads "Scanned n, updated u" (cards scanned in this visit, cards with a correction on this phone).
- **Report as wrong** (Reader Mode, and Play Mode and Quiz Mode while the card is revealed) opens an overlay with the fields Artist, Year, Title and Amazon ID, filled with the current values, and Cancel and Save. Save is on when a value differs and all are valid (year four digits from 1900 to this year, title and artist not empty, the Amazon ID empty, a track ID or a pasted link of Amazon Music, otherwise the field shows "Not an Amazon track ID"). An empty Amazon ID means search only. Title and artist are multiline fields. Line breaks become one space when saving. Cancel and Back close only the overlay. The overlay does not close on a tap outside. While it is open, the reader drops scans and the quiz holds its next card back. After Save the button reads "Reported, Edit again" and the shown card is refreshed in place.
- **Corrections** are written to `card_overrides.csv` in the app storage (`set,card,year,title,artist,time,source,amazonId`, the last row per card wins, a row without the last column keeps the id of the card set). They apply to every mode at once, also the id the play path tries first, and survive app updates and the refresh of the card sets. `reader_results.csv` is the log of the reports (`time,set,card,verdict,ourYear,ourTitle,ourArtist,cardYear,cardTitle,cardArtist,source,ourAmazonId,cardAmazonId`). The events `reader verdict` carry `ourAmazonId` and `cardAmazonId`, and `changed` lists `amazonId` when it differs.
- **Export** in the top bar of Reader Mode shares the latest correction per card as `hitster-corrections-<date>.csv` (the columns of `card_overrides.csv`) through the Android share sheet (`FileProvider`, not exported). A line under the card says where to send the file.
- **Send to Amazon Music app** plays the shown card through the same start sequence as Play Mode. It is off with a reason under it when Amazon Music is missing or notification access is not granted. The status line reads "Sending ...", "Playing" or "Song ended and paused. Send the next card or leave." Every scan that is not dropped stops a song started this way (like Stop) and clears the line. Leaving Reader Mode stops it too.

## Setup

1. Install the Amazon Music app and log in.
2. Install `dist/Amazon-Music-Connector-for-Hitster-<version>-prod.apk` (Android asks about unknown sources, Play Protect asks once).
3. On Home tap "Open notification access settings" and switch the app on.
4. Back on Home both setup lines show a tick. Play Mode and Quiz Mode are on then. Reader Mode is always on.

## Debug and production apps

`./build.sh` builds the debug app, `./build.sh --prod` the production app. Each file name has the version `1.0.1-<yyyymmdd>.<hhmm>-debug` or `-prod`.

| | debug | production |
| --- | --- | --- |
| `INTERNET` permission | yes (log sink) | no, the app works offline |
| application id | `de.jobisoft.hitsterconnector.debug` | `de.jobisoft.hitsterconnector` |
| app name | Amazon Music Connector for Hitster (Debug) | Amazon Music Connector for Hitster |
| debug channel (`DebugLog`, log sink) | on | off, removed by R8 |
| cleartext HTTP | yes | no |
| R8 shrinking and obfuscation | no | yes (`proguard-rules.pro`) |
| APK size | about 22 MB | about 2.7 MB |

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
- The app finds the sink by itself. At start it sends the UDP broadcast `HITSTER-SINK?` to port 8782 and the sink answers with its HTTP port. Without an answer the app sends nothing and searches again with the next events (at most every 10 s). Allow UDP 8782 in the firewall of the PC. Networks that block broadcasts between devices (some guest Wi-Fi) do not work.
- An event is `{"t": epoch ms, "tag": "...", "msg": "...", "data": {...}}`, sent in batches. A sink that is not running is silent.
- The first event `app started` has the app version, the application id, the device, the Amazon Music version and the notification access.
- Tags: `play` (`request`, `phase`, `id loaded`, `id not shown`, `id start failed`, `buffering wait`, `settle`, `session item replaced`, `song plays`), `duck` (`ducked`, `restored`, `level kept, it was changed meanwhile`), `ev` (`snapshot`: every report of the session during a request with `ms` since the load request, `state`, `positionMs`, `mediaId` and `item`, which is ours, foreign or none), `act` (`pause`, `seekTo`, `play` with `reason`, `ms` and the state that caused it), `search` (`query`, `query by id`, `result`, `queue`, `final`, `result` has `viaId`), `start` (the numbers of one attempt: `firstActionMs` is the time from the load request to the first pause, `foreignPausedMs` the same for a foreign item, then `titleToPauseMs`, `titleToSeekConfirmedMs`, `titleToPlayingMs`, `seekCalls`, `pausedAgainBecausePlaying`, `maxPositionSeenS` and `leakedIntroS`, the furthest position of our item seen while it played below the offset), `mute`, `stop`, `guard`, `leave`, `quiz`, `reader`, `timing`, `foreground`, `ctl`, `control`, `crash`, `error`, the step tags `a0-mediaButton`, `a-intent`, `i-playFromUri`, `b-playFromSearch`.
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

The bundled CSV files in `card-sets/assets/csv/` belong to the app. The phone keeps the sha256 of the copy it wrote last (SharedPreferences `seeded_card_sets`). At every start a file is copied when it is missing and never was written, and overwritten when the bundled file differs from the record. A file the user deleted stays deleted. Files with other names, for example imported by the user, are never touched. Corrections are separate, so a refresh loses nothing. The display names come from `card_sets.csv`. A set without an entry shows its file name without `.csv`.

## Tests

Unit tests (`./gradlew :app:testDebugUnitTest`) cover the logic without a phone: stop confirmation, start jump and the start positions, the reactions to the session reports (`PlayReactor`, `SessionSnapshot`), end decisions, the volume duck rules, switch detector, play and restart decisions, control buttons, search query, version check, quiz deck, corrections, reader results, card set names and seeding, the card set files (the bundled ones and the layouts of the upstream files), the `AmazonID` column and its media id, the Amazon ID of the overlay, crash reports, sink discovery, song identity, the Send to Amazon rules, the report overlay and the result texts. The Python tools have tests in `tools/test_*.py`.
