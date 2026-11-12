# Amazon Music Connector for Hitster

Scan a [Hitster](https://hitstergame.com) card with your Android phone and the song plays on Amazon Music. Amazon offers no public search API for private accounts, so this project controls the Amazon Music app on your phone.

| Folder | What it is |
| --- | --- |
| [amazon-app-android-connector](amazon-app-android-connector) | The Android app. It plays through the Amazon Music app of the same phone. Play Mode, Quiz Mode, Reader Mode, player controls, adjustable start position. |
| [amazon-web-scanner](amazon-web-scanner) | A tool that reads the Amazon Music web player to find the track id and album of every card. |
| [assets](assets) | The card sets as CSV files (assets/card-data), used by the Android app, and the data collected by the scanner (assets/amazon-data). |

The app folder has its own README with the build steps.
