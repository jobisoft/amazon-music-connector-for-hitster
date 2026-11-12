# Card sets

The Hitster card sets (which card number is which song) and their display names, used by the Android app. Gradle adds `assets/` to the assets of the app (`assets.srcDir(...)` in `android/app/build.gradle.kts`), nothing is copied.

- `assets/csv/hitster-*.csv`: one file per edition, based in part on [andygruber/songseeker-hitster-playlists](https://github.com/andygruber/songseeker-hitster-playlists), with changes and additional columns. The license text is in `assets/csv/LICENSE-songseeker-hitster-playlists.txt`.
- The columns are `Card#,Title,Artist,Year,AmazonID,Album`: the number of the card, the title and the artist as printed, the year, the Amazon Music track id of the song and the album that Amazon Music shows for that track id. The Android Amazon app plays the track id first, by handing the web address of the track to the media session of Amazon Music. A card without a track id is searched by title and artist. `Year`, `AmazonID` and `Album` may be empty. The app does not use `Album` yet.
- The app also reads the upstream files of SongSeeker, which have more columns (`URL`, `Hashed Info`, `Youtube-Title`, `ISRC`, `Album`) in other orders. The columns are found by their names, the others are ignored, and the cards of such a file are searched by text.
- `assets/card_sets.csv`: file name to display name (`File,Game`), from the `Game` column of the upstream `playlists.csv`.

The Android Amazon app copies the CSV files into its own storage and refreshes a file when the bundled file changes. The phone keeps a hash per file. A file the user deleted stays deleted, and files with other names are never touched. Nothing has to be imported.
