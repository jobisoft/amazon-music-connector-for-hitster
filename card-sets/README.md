# Card sets

The Hitster card sets (which card number is which song) and their display names, shared by the Android apps of this repository. Gradle adds `assets/` to the assets of each app (`assets.srcDir(...)` in `android/app/build.gradle.kts`), nothing is copied.

- `assets/csv/hitster-*.csv`: one file per edition, a snapshot of [andygruber/songseeker-hitster-playlists](https://github.com/andygruber/songseeker-hitster-playlists). The license text is in `assets/csv/LICENSE-songseeker-hitster-playlists.txt`. `hitster-de-aaaa0075.csv` ("Hitster - Original") comes from the open pull request #57 of that project, see `YEAR_CORRECTIONS.md`.
- `assets/card_sets.csv`: file name to display name (`File,Game`), from the `Game` column of the upstream `playlists.csv`.

An app copies the CSV files into its own storage and refreshes a file when the bundled file changes. The phone keeps a hash per file. A file the user deleted stays deleted, and files with other names are never touched. Nothing has to be imported.
