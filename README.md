# Amster

Play Hitster with an Amazon Music account.

Scan a [Hitster](https://hitstergame.com) card with your Android phone and the song plays on Amazon Music. Amazon offers no public search API for private accounts, so this project controls the Amazon Music app on your phone.

| Folder | What it is |
| --- | --- |
| [android-app](android-app) | The Android app. It plays through the Amazon Music app of the same phone. Play Mode, Quiz Mode, Reader Mode, player controls, adjustable start position. |
| [amazon-web-scanner](amazon-web-scanner) | A tool that reads the Amazon Music web player to find the track id and album of every card. |
| [assets](assets) | The card sets as CSV files (assets/card-data), used by the Android app, and the data collected by the scanner (assets/amazon-data). |

The app folder has its own README with the build steps.

## Legal notice

Amster is an independent, unofficial project. It is not affiliated with, endorsed by, sponsored by or associated with Jumbo Spiele GmbH or the Hitster card game, or with Amazon. Hitster and Amazon Music are trademarks of their respective owners. They are named only to say which cards and which music service this app works with. You need your own cards and your own Amazon Music account. This project claims no trademark rights.
