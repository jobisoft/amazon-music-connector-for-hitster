# Card sets

One CSV file per Hitster edition: which card number is which song. The Android app bundles them. Gradle adds the folder `assets/` to the assets of the app (`assets.srcDir(...)` in `amazon-app-android-connector/android/app/build.gradle.kts`), nothing is copied. This README and the folder `assets/amazon-data` are not part of the app.

The files are based in part on [andygruber/songseeker-hitster-playlists](https://github.com/andygruber/songseeker-hitster-playlists), with changes and additional columns. The license text is in `LICENSE-songseeker-hitster-playlists.txt`.

## Columns

`Card#,Title,Artist,Year,AmazonID,Album,Edition`

- `Card#`, `Title`, `Artist`: the number of the card, the title and the artist as printed. These three are required in every row. 
- `Year`: the year. The column is required, a row may leave it empty.
- `AmazonID`: the Amazon Music track id of the song, may be empty. The app plays the track id first, by handing the web address of the track to the media session of Amazon Music. A card without a track id is searched by title and artist.
- `Album`: the album that Amazon Music shows for that track id, may be empty.
- `Edition`: the name of the card set, written in the first row only. The app shows it in its lists. A file without a name shows its file name.

The app finds the columns by the names in the header, in any case and in any order.

## On the phone

The app copies the CSV files into its own storage and refreshes a file when the bundled file changes. The phone keeps a hash per file. A file the user deleted stays deleted, and files with other names are never touched. Nothing has to be imported. Own card sets can be imported in Card Config, they need the columns `Card#`, `Title`, `Artist` and `Year`.
