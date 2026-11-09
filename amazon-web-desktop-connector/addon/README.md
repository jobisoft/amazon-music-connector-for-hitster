# Amazon Music Connector for Hitster

A Firefox add-on that plays the songs of scanned [Hitster](https://hitstergame.com) cards in an open Amazon Music tab. It is the browser half of a pair. The other half is the Android app [Hitster Amazon Desktop Connector](../android).

## How it works

1. The Android app scans the QR code of a card and looks up the title and the artist of the song.
2. The app sends both to a small **native helper app** on your computer. The helper listens on your local network.
3. The helper passes the request to this add-on through **native messaging**.
4. The add-on searches and plays the song in your open Amazon Music tab (content script).

A Firefox add-on cannot open a network port, which is why the helper app is needed. It is a single process: Firefox starts it, and it ends when Firefox ends.

## Status

This add-on is a first attempt and is not developed further for now. It is not published in the Firefox Add-on Store and works only as a temporary debug install (`about:debugging`, "Load Temporary Add-on", then select the `.xpi` or its `manifest.json`).

## Setup

Load the add-on, then open its options page. While the helper app is missing, the page offers it as a download and shows the install steps for your system. In short:

- Linux and macOS: unpack the archive and run `bash install.sh`
- Windows: unpack the archive and run `install.bat`
- The helper app needs Python 3.

Restart Firefox afterwards. The options page then shows the status of the helper app, the address and the token for the Android app, and the most recent requests. To work on the helper itself, run `bash install.sh --dev` from the source folder. The manifest then points to `src/native-messaging-app/amazon_music_connector_host.py` instead of a copy, so a change to it takes effect when the add-on is reloaded (`POST /reload` of the debug interface).

### Amazon Music tab

The options page has a button that opens the Amazon Music web player of your store (for example `music.amazon.de`). Log in there and leave the tab open. The add-on only touches tabs of the Amazon Music domains. Its content script is loaded into them automatically.

### Android app

When the add-on is built with the Android app inside (see Build), the options page shows a QR code. The code leads to `http://<ip of this computer>:8765/app`, a page served by the helper app. It has a download button for the APK.

On the phone, Android asks once to allow the browser (or the file manager) to **install unknown apps**. After that the downloaded file installs like any other app. The link is plain HTTP inside your network, which Android permits for downloads.

If the phone cannot open the link while the computer can, first check that the phone is in the same Wi-Fi as the computer. Mobile data and guest networks do not work, and some routers keep the devices of a Wi-Fi apart from each other. If the network is right, a firewall on the computer may block the port (8765 by default). The helper writes every request of another device into its log (`GET /log` of the debug interface, entries of kind `http`), so you can see whether the phone arrives at all.

The helper knows the address of this computer, because it asks the operating system for the interface with the default route. If the computer has several network interfaces, the options page offers a choice.

The add-on hands the APK to the helper after each start of Firefox, unless the helper has this version already. The helper keeps it in its configuration folder.

Firefox installed as a snap or flatpak runs in a sandbox. The installer also registers the helper in the profile folder of those packages, but whether Firefox then starts it depends on the sandbox rules of the package. This is not verified yet.

## HTTP API of the helper app

Requests need the header `Authorization: Bearer <token>`, unless the open access is switched on on the options page (for a network you trust). The requests marked "open" never need it.

| Request | Answer |
| --- | --- |
| `GET /ping` (open) | `{"app": "amazon-music-connector-for-hitster", "version": "...", "name": "<computer>", "id": "<installation>", "open": false}` |
| `POST /pair` with `{"name": "<device>"}` (open) | Asks to allow the device. The user decides on the options page. `{"ok": true, "id": "..."}` |
| `GET /pair/<id>` (open) | `{"state": "pending" \| "approved" \| "denied" \| "expired", "token": "..."}`, the token comes with `approved` |
| `GET /`, `GET /app`, `GET /app/<file>.apk`, `GET /app.apk` (open) | The page and the file of the bundled Android app |
| `GET /status` | `{"ok": true, "addon": true}` |
| `POST /play` with `{"title": "...", "artist": "...", "year": 1981, "card": "de/00123", "start": 20}` (`year`, `card` and `start` in seconds are optional) | `{"ok": true, "message": "..."}` or `{"ok": false, "error": "..."}` |
| `POST /control` with `{"action": "back10" \| "forward10" \| "restart" \| "stop" \| "seek" \| "state", "position": 12.3}` (`position` for `seek`) | `{"ok": true, "position": 12.3, "duration": 201.0, "playing": true}` or `{"ok": false, "error": "..."}` |

A song starts at 20 seconds (at a quarter of its length when it is shorter than 50 seconds). `restart` jumps there again and plays, `stop` pauses. While an Amazon Music tab is open the add-on also keeps the account setting "Autoplay" (similar songs after the music ends) switched off: when the page has loaded, and before a play request if the last check is older than ten minutes.

The app finds the computer by itself. It sends the UDP broadcast `AMC-FIND?` to port 8765 and every helper answers with the data of `GET /ping` and its HTTP port. Allow UDP 8765 in the firewall of the computer. A network that keeps the devices apart does not pass the broadcast, then the address and the token go into the app under Manual setup. The UDP port can be changed with the key `discoveryPort` in `config.json`.

The port (default 8765) and the token live in `config.json` in the folder `amazon-music-connector-for-hitster` of your user configuration directory (`~/.config` on Linux). Both can be changed on the options page.

## Debug interface

For developing the content script, the helper has a second interface. It lets a developer watch the requests of the phone, send requests, and look at the DOM of the Amazon Music tab. It needs the **admin token** from `config.json` and **refuses every request that does not come from the loopback interface** (127.0.0.1). The normal token of the phone does not work here. The DOM of a logged-in Amazon page may hold personal data, so it never leaves the computer.

```
TOKEN=$(python3 -c "import json,os;print(json.load(open(os.path.expanduser('~/.config/amazon-music-connector-for-hitster/config.json')))['adminToken'])")
H="Authorization: Bearer $TOKEN"; B=http://127.0.0.1:8765

curl -H "$H" $B/info                              # state of the helper
curl -H "$H" "$B/log?since=0"                     # all log entries
curl -H "$H" "$B/log?since=17&wait=30"            # wait for new entries (long poll)
curl -H "$H" $B/tabs                              # open Amazon Music tabs
curl -H "$H" -d '{"title":"Start Me Up","artist":"The Rolling Stones"}' $B/play
curl -H "$H" -d '{"url":"https://music.amazon.de/search/Start%20Me%20Up"}' $B/navigate   # load a page
curl -H "$H" -d '{"mode":"summary","waitFor":"[data-testid=x]","timeout":10000}' $B/dom   # wait for it
curl -H "$H" -d '{"action":"click","selector":"[data-testid=TopNavBar_HomeTab]"}' $B/act   # click
curl -H "$H" -d '{"action":"type","selector":"input","text":"Start Me Up"}' $B/act         # type
curl -H "$H" -d '{"action":"press","selector":"input","key":"Enter"}' $B/act               # key
curl -H "$H" -X POST $B/reload                    # reload the add-on from its files
curl -H "$H" -d '{"mode":"summary"}' $B/dom       # interactive elements
curl -H "$H" -d '{"mode":"html","selector":"music-app >>> music-search"}' $B/dom
```

Log entries have a revision (`rev`). An entry that changes, for example a request that gets its result, gets a new revision. Ask for `since=<highest rev you saw>` to get everything new.

The DOM request takes these fields, all optional:

| Field | Meaning |
| --- | --- |
| `mode` | `html` (default, markup with `<#shadow-root>` markers), `text` (the visible text) or `summary` (interactive elements) |
| `selector` | CSS selector. `>>>` crosses a shadow root: `music-app >>> music-button` |
| `maxBytes` | Size limit for `html` and `text` (default 400000) |
| `limit` | Maximum number of items for `summary` (default 400) |
| `strip` | `true` (default) leaves out scripts, styles and the insides of SVG |
| `tabId` | Which tab, if more than one is open |
| `waitFor` | Selector to wait for before the DOM is read. The player is a single page app, results show up after the load. |
| `timeout` | How long to wait, in milliseconds (default 10000, at most 30000) |

`POST /act` clicks an element, types text into it, or presses a key on it. The element is found with the same selector syntax as for the DOM dump, and `index` picks one of several matches. The events are synthetic, so the page may treat them differently from a real click. That is exactly what this interface is for.

`POST /reload` reloads the add-on with `browser.runtime.reload()`. Firefox reads the files again from the place the temporary add-on was loaded from (the folder with `manifest.json`, or the `.xpi`). So after a change you rebuild the XPI if needed and reload, without touching `about:debugging`. The helper restarts together with the add-on. With a normal install it is the copy made by `install.sh`, which only changes when you run the installer again. With `install.sh --dev` it is the file in the source folder.

`POST /navigate` loads a page of Amazon Music in the tab and answers when it has finished loading. Only `https://music.amazon.*` addresses are accepted.

## Test

```
python3 test/test_host.py
```

The tests play the roles of Firefox and of the phone against the helper app. They do not need Firefox. The add-on itself (options page, content script) has no automated tests yet.

## Build

```
npm run build
```

or, without npm:

```
python3 build.py
```

This syncs the version from `package.json` into `src/manifest.json` and creates `dist/amazon-music-connector-for-hitster_<version>.xpi` (the dots of the version become underscores).

The script bundles the Android app if it finds a build in `../android/app/build/outputs/apk/` (release first, then debug). To use another file, run `python3 build.py --apk path/to/app.apk`. Without any APK the add-on builds fine, and the options page says that no app is inside. A debug APK is signed with a debug key, which is fine for installing on your own phone.

## Third-party code

The options page draws the QR code with [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) (MIT). See `src/vendor/VENDOR.md`.

## License

MPL 2.0, see [LICENSE](LICENSE).
