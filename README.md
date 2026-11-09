# Amazon Music Connector for Hitster

Scan a [Hitster](https://hitstergame.com) card with your Android phone and the song plays on Amazon Music. Amazon offers no public search API for private accounts, so this project controls Amazon Music itself, either the app on your phone or the web player in your browser.

| Folder | What it is |
| --- | --- |
| [amazon-app-android-connector](amazon-app-android-connector) | The Android app. It plays through the Amazon Music app of the same phone. Play Mode, Quiz Mode, Reader Mode, player controls, start at 20 s. |
| [amazon-web-desktop-connector](amazon-web-desktop-connector) | The Desktop connector. The phone talks to a helper on a computer and a Firefox add-on drives the Amazon Music web player. A first attempt, not developed further for now, and not published in the Firefox Add-on Store. |
| [card-sets](card-sets) | The card sets (CSV) and their names, used by the Android apps. |

Each app folder has its own README with the build steps.
