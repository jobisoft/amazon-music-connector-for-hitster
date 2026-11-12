# Amazon Web Scanner

Amazon Web Scanner lets scripts (or Claude) read and drive the logged-in Amazon Music web player in Firefox. It reads the DOM of a tab, clicks, types, presses keys and loads pages.

| Item | Value |
| --- | --- |
| Add-on id | `amazon-web-scanner@jobisoft.de` |
| Native host | `amazon_web_scanner_host` |
| Config folder | `amazon-web-scanner` |
| Default port | 8766 |

## How it works

1. The Firefox add-on (folder `addon/`) runs in the browser. Its content script reads and drives the `music.amazon.*` tabs.
2. Firefox starts the Python helper app (`addon/native-messaging-app/amazon_web_scanner_host.py`) through native messaging as soon as the add-on loads. The two talk over stdin and stdout.
3. The helper app runs a small HTTP server on `127.0.0.1`. A script sends a request there. The helper app forwards it to the add-on, waits for the answer and returns it as JSON.
4. Every request needs a secret token.

The helper app uses Python 3 only, without extra packages.

## Setup

1. Install Python 3.
2. Register the helper app once. On Linux or macOS run `bash addon/native-messaging-app/install.sh`. On Windows run `addon\native-messaging-app\install.bat`. The scripts also cover Firefox installed as snap or flatpak. Use `bash install.sh --dev` to run the helper app in place from this folder.
3. Restart Firefox.
4. Open `about:debugging#/runtime/this-firefox`, choose "Load Temporary Add-on" and pick `addon/manifest.json`. A temporary add-on has to be loaded again after each Firefox restart.
5. The options page of the add-on shows whether the helper app is connected, the address of the API and the token. It has a button that opens the Amazon Music tab. Log in there.
6. Test it: `curl -H "Authorization: Bearer <token>" http://127.0.0.1:8766/status`

To remove the registration run `uninstall.sh` or `uninstall.bat` from the same folder.

Optionally `python3 addon/build.py` packs an unsigned `.xpi` into `addon/dist/`. Do not keep it in the repository.

## HTTP API

All endpoints need the header `Authorization: Bearer <adminToken>`. The answer is JSON. A wrong or missing token gives 401. A failure in the add-on (no match, no answer in time) gives 502 with `{"ok": false, "error": "..."}`. A bad body gives 400. The calls that work on a tab need a whole number `tabId`, see "Choosing the tab". A missing or bad `tabId` gives 400 with `"code": "TAB_REQUIRED"`, and a `tabId` that is not an open `music.amazon.*` tab gives 409 with `"code": "INVALID_TAB"`.

| Request | Body | Answer |
| --- | --- | --- |
| `GET /status` | none | `{"ok": true, "addon": true, "hostVersion", "build"}`. `build` is the first 12 hex digits of the SHA-256 of the running host file, computed once at the start. Compare it with the file in the repository to see whether the helper runs that exact file. |
| `GET /tabs` | none | `{"ok": true, "tabs": [{"tabId", "windowId", "url", "title", "active", "contentScript"}]}` |
| `POST /dom` | `tabId`, `selector?`, `mode?` (`summary`, `text` or `html`, default `html`), `maxBytes?`, `limit?`, `strip?`, `waitFor?`, `timeout?` | `{"ok", "tabId", "url", "title", "mode", "selector", "matched", "truncated", "data"}` |
| `POST /act` | `tabId`, `action` (`click`, `type` or `press`), `selector`, `index?`, `text?`, `key?`, `timeout?` | `{"ok", "tabId", "action", "matched", "tag", "text", "url"}`. |
| `POST /navigate` | `tabId`, `url` (an `https://music.amazon.*` page) | `{"ok", "tabId", "url", "title"}` after the page has loaded |
| `POST /back` | `tabId` | `{"ok", "tabId", "url", "title"}` after the tab went one step back in its history. Fails with 502 if there is no earlier page. |
| `POST /reload` | `tabId` (only checked, the tab is not touched) | `{"ok": true, "message": "Reloading the add-on."}` The add-on and the helper app restart. |

Notes:

- Use a fixed `tabId` for a long run. `/dom`, `/act`, `/navigate`, `/back` and `/reload` never select a tab or focus a window, so you can work in your own tab meanwhile.
- Firefox may slow down timers in background tabs. The player and the page scripts then react late. A tab in the background may be slowed down like this, so keep the scraped tab visible, best in a window of its own that is not minimized.
- Selectors may cross shadow roots with `>>>`, for example `music-app >>> music-shuffle-button`.
- `waitFor` is a selector. The answer comes when it matches, or fails after `timeout` milliseconds (default 10000, at most 30000).
- `summary` lists the interactive elements with tag, test id, label, text and link. `text` returns the visible text. `html` returns the markup with `<#shadow-root>` markers.
- After changing the host or the add-on files run `install.sh` and reload the add-on, the `build` in `/status` changes by itself.
- The events of `/act` are synthetic. The page may treat them differently from real input.
- The helper app waits up to 40 s for `/tabs` and `/navigate`, 55 s for `/dom`, 45 s for `/act`, 40 s for `/back`, 10 s for `/reload`.

### Choosing the tab

`GET /tabs` is the only way to find tabs. It lists all Amazon Music tabs. The client picks a `tabId` and sends it with every call. The add-on never chooses a tab itself. A missing `tabId`, or one that is not a whole number, gives HTTP 400 with `"code": "TAB_REQUIRED"`, and the add-on is not asked. A `tabId` that is not an open `music.amazon.*` tab (any more, for example because the tab was closed or went to another site) gives HTTP 409 with `"code": "INVALID_TAB"`. List the tabs again then.

## Config file

| System | Path |
| --- | --- |
| Linux | `$XDG_CONFIG_HOME/amazon-web-scanner/config.json`, usually `~/.config/amazon-web-scanner/config.json` |
| macOS | `~/Library/Application Support/amazon-web-scanner/config.json` |
| Windows | `%APPDATA%\amazon-web-scanner\config.json` |

The file holds `port` (1024 to 65535, default 8766) and `adminToken`. Both are created on the first start. Change the port in the file and restart the helper app, for example with `POST /reload`. The options page can create a new token. The environment variable `AMAZON_WEB_SCANNER_CONFIG_DIR` overrides the folder, which the tests use.

## Safety notes

- The server listens on `127.0.0.1` only, and requests from other addresses are refused.
- The token is the only protection against other programs and web pages on this computer. The file has mode 600 where the system supports it. Never print the token in logs, scripts or chat, and never commit it. The options page shows it only on request.
- The add-on only touches tabs on `music.amazon.*`. `/navigate` refuses every other address.
- The DOM of a logged-in Amazon page may hold personal data. Keep what you read on this computer.
- There is no endpoint that runs arbitrary JavaScript. Only the actions in the table are possible.

## Tests

`python3 addon/test_host.py` starts the helper app on a free port with a temporary config folder and plays the role of Firefox. It never touches the real config or the default port. The content script and the options page are not covered by tests.

## Scripts

### scrape.py

`scripts/scrape.py` is one parametrized command line tool for all scrapes of Amazon Music Germany data for Hitster card sets. The tool uses Python 3 and the standard library only. Its only network access is the local helper. It only collects data. It never judges, picks or rejects a candidate.

The files are `scrape.py` (command line and the stages), `webclient.py` (HTTP calls, memory guard and page parsing) and `research.py` (CSV reader, card selection and the research file).

Commands:

| Command | What it does |
| --- | --- |
| `run <jobs.json> --tab-id N --results PATH` | Runs the jobs of a job file from top to bottom, exactly as they stand, and writes a result file. See below. |
| `tabs` | Shows the Amazon Music tabs (`GET /tabs`). Display only. |
| `status --set NAME` | Shows how many cards of the set have `track_search` and `album_search`, the counts, and which cards are missing. Needs no helper. |
| `sync-cards --set NAME` | Sets `title`, `artist` and `card_year` of every card that already has a line in the research file from the current CSV. No helper and no network. Prints the changed titles as `card: old -> new` and `N cards updated`. `--dry-run` only shows the list. No line is created. |

The old commands `song`, `album`, `look` and `track` and the parameters `--cards`, `--cards-file`, `--force`, `--max-hits`, `--song-ids`, `--hits`, `--tracks`, `--album`, `--row` and `--card` are gone. Their jobs are lines of the job file now. The tool decides nothing. What the file says is run, and nothing is skipped because a stage is already there.

Parameters of `run`:

| Parameter | Meaning |
| --- | --- |
| `--tab-id N` | Required. The tab to work in, never replaced during the run. Find the number with `scrape.py tabs`. |
| `--results PATH` | Required, also with `--dry-run`. The result file, created new (an existing file is overwritten) at every run. Without it `run` stops with exit code 2 and "you must name the result file: --results PATH". |
| `--pause S` | Seconds between jobs, default 20. |
| `--min-mem-mb N` | Wait while MemAvailable (`/proc/meminfo`) is lower, default 1500. The run stops after 30 minutes of waiting. |
| `--home-every N` | Load `https://music.amazon.de/` every N page loads, default 60. |
| `--max-errors N` | Stop after N failed jobs, default 5. |
| `--port N`, `--config PATH` | Port and config file of the helper (default 8766 and `~/.config/amazon-web-scanner/config.json`). The token is read from it and never printed. |
| `--dry-run` | Check the file and list the jobs (`index N set S card C job J query Q`). Nothing else is read or written. |

#### Job file

One JSON document (not JSON lines) with exactly one set and a list of card entries. JSON has no comments, so an entry may have an optional text `note`, which is copied into the result file.

```
{
  "set": "hitster-de-aaaa0039",
  "cards": [
    {"card": 8, "job": "song"},
    {"card": 212, "job": "song", "query": "Pumpkins, 1979", "note": "other query, the card title is short"},
    {"card": 134, "job": "album", "song_ids": true},
    {"card": 212, "job": "meta", "hits": [1, "3-4"]},
    {"card": 88, "job": "track", "album": "B07BZNK8P7", "row": 5}
  ]
}
```

`set` is the CSV name without `.csv`. The tool reads `assets/card-data/NAME.csv` and writes `assets/amazon-data/NAME.research.jsonl`. Every entry has `card` (a number in that CSV) and `job`. There is exactly one entry per card, a repeated card is an error. Unknown fields, on the top level or in an entry, are an error.

| Job | Fields | What it does |
| --- | --- | --- |
| `song` | optional `query` | Reads the full result page `https://music.amazon.de/search/<url-encoded query>/songs` (the page behind "Mehr anzeigen") and replaces `track_search` of the card with the list of hits. The default query is the first artist and the title as in the CSV. The rows are exactly those that are in the page after it has loaded and the row list is stable (two polls in a row give the same song ids). The page is not scrolled and the number of rows is not limited (the summary limit is 200 plus 30 per row, at least 1500). A hit has `rank` (1 to N in page order), `track_id`, `track_url`, `title`, `artists` and `origin`. `album_name` and `duration` are filled only if the row holds them. In the sample pages a row has neither, so they are null. The other album fields stay null. `track_search` also has `query` (the text searched) and `page` (`songs`). No album page is opened and no hit is filtered. |
| `album` | optional `query`, optional `song_ids` | Album search and replaces `album_search` of the card. The default query is first artist, a TAB, then the title (`%09` in the URL, `?type=album`, plain title search if nothing is found). A `query` with a TAB is taken as artist, TAB, title, any other text is searched as plain text. It stores up to 8 releases with their data (`tracks` of the header and `rows_read`, see below) and the rows that resemble the card title. With `song_ids` it reads the song id of each such row through the context menu "Song anzeigen". |
| `meta` | `hits` (list of ranks, ranges as text like `"2-4"` allowed) or `tracks` (list of song ids) | Reads the head data of each named hit of the stored list straight from its track page. For every hit `https://music.amazon.de/tracks/<ID>` is loaded by `/navigate` (no search, no menu, no album page, no click). The tool waits for the page title (it must hold ` Song von `), then once more for the text, with a fixed limit. Every field is read and stored on its own, and a field that cannot be read keeps its value in the hit (it is never overwritten with null). The track title and the album name come from the page title (`<title> Song von <artist> aus dem Album <album> auf Amazon Music`, without the album part the album name stays as it is). The duration comes from the text (`4 Min. 47 Sek.`, also `3 Min.` or `47 Sek.` alone, written as `mm:ss`) and the date from the text after it (`23. Okt. 1995`, German month abbreviations, kept as written, the year is read from it), each independent of the other. A real page may have no date, for example `Song | Machinehead (Live) | Bush | 4 Min. 47 Sek. | HD | Spielen`. Then album, title and duration are stored and `album_release_date` and `album_year` stay as they were. It writes `album_name`, `meta_title`, `album_release_date`, `album_year` and, only if still null, `duration`. It writes no `album_id` and no track count and does not check the row in the album. The page must belong to the requested id (the tab URL holds it). A hit counts as not read only if nothing at all was found (no album name, no duration, no date) or if the page is another song's: the hit stays unchanged and `errors` of the stage gets `{track_id, "meta page not readable"}` (or the other-page error). For a hit that was read in part there is no `errors` entry. The result line lists it in `incomplete`: `[{track_id, missing: ["date"]}]` with the names `album`, `duration` and `date`, and the message is `N of M read, K incomplete`. The result line has `metas` (the ranks). The research file is saved after each hit. |
| `track` | `album` (ASIN), `row` (index) | Reads the song id of one album row and adds it to the `album_search` of the card. |

A `meta` job needs the stored list of its card, so it comes in a later file than the `song` job of that card. The whole file is checked before the first call to the helper. The messages name the place in the document, for example `cards[3]: unknown job "x"`, `cards[5]: duplicate card 212 (also in cards[1])`, `"set" must be the name of a card set`, `there is no CSV for the set X`, `"cards" must be a list`, `"cards" is an empty list`, `unknown field at the top level: sets`, and for broken JSON the line and column of the fault. A `meta` entry must name hits of the stored list. Every error is listed, the exit code is 2 and nothing runs.

#### Result file

The result file (`--results`) is created new at every run and a line is written (and flushed) after each job: `index` (the position in `cards`), `set`, `card`, `job`, `query`, `note` (if the entry has one), `status` (`ok`, `error` or `skipped_tab_lost`), `seconds`, `errors`, `message`, and `hits` (song), `releases` and `rows` (album), `metas` (meta: the ranks). A failed job writes nothing into the research file and the run goes on with the next job. At the end comes a line `{"summary": true, "jobs", "ok", "error", "seconds", "stopped"}` where `stopped` is null or `tab lost`, `memory`, `max-errors`, `fatal` or `interrupted`. The same lines go to stderr. Ctrl-C finishes the running job and stops, a second Ctrl-C stops at once.

Work flow: write a job file (one set per file), `run` it, read the result file and the research file, write the next job file. The usual order is a `song` file for the list, a `meta` file for the hits you want to know more about, and `album` or `track` jobs only where the list is poor.

```
python3 scripts/scrape.py tabs
python3 scripts/scrape.py run jobs.json --tab-id 12 --results jobs.results.jsonl --dry-run
python3 scripts/scrape.py run jobs.json --tab-id 12 --results jobs.results.jsonl
```

The scraper never scrolls, in no job and on no page. It reads the rows that are in the page. Every release stores `tracks` (the number in the album header) next to `rows_read` (the number of rows that were in the page). Fewer rows than `tracks` is no error in an `album` job and nothing is written into `errors`, the two numbers show it. In a `track` job a row that is not in the page is an error: `row not on the page (read N of M rows), use a more exact song job with the album name in the query`, and nothing is written. A page that is too long to be in the DOM at once means the search was not exact enough. Make a `song` job with a `query` that holds the title and the album name, for example `The Smashing Pumpkins 1979 Mellon Collie And The Infinite Sadness`, then the song id is in the list. Keep the Amazon Music tab visible while a run goes on, best in a window of its own that is not minimized, because Firefox slows down tabs in the background.

The scraper never opens, closes or chooses a tab. You open the Amazon Music tab yourself. `python3 scripts/scrape.py tabs` reads `GET /tabs` and prints one line `tabId  windowId  active  url  title` per Amazon Music tab. This is display only. Pass the tab id to `run` as `--tab-id N`. Without it `run` stops at once with exit code 2 and "you must select a tab: --tab-id N (list tabs with: scrape.py tabs)". If there is no tab, `tabs` stops with exit code 4 and "no Amazon Music tab open".

At the start of `tabs` and `run` the scraper reads `GET /status` and prints `helper <hostVersion> build <build>`. It compares the build with the first 12 hex characters of the SHA-256 of `addon/native-messaging-app/amazon_web_scanner_host.py` in this repository. If they differ, or the helper reports no build (old helper), it prints a WARNING to run `install.sh` and restart the add-on, and goes on anyway. If the repository file is missing it prints an info line only.

At the start of a run `GET /tabs` checks that the tab exists and is an Amazon Music tab, otherwise the run stops with exit code 4 and "tab N is not an open Amazon Music tab". Then it prints `using tab N, <url>` and sends the tab id with every request. If a request fails with HTTP 409 `INVALID_TAB`, or the tab disappears, the run stops at once with exit code 4 and "tab N is gone or left Amazon Music, select a tab again with --tab-id". The jobs that are already saved stay in the research file, the result file gets a line `skipped_tab_lost` for every job that was not run, and the summary says `tab lost`. Other exit codes are 2 for bad arguments, 3 for a fatal error such as an unreachable helper or low memory, and 130 for Ctrl-C.

The research file has one JSON line per card (`card`, `title`, `artist`, `card_year`, `track_search`, `album_search`). It is rewritten as a whole with an atomic replace after every job. Other cards and other stages are kept and the lines are ordered by card number. A failed job is logged and not written. Failures inside a job (one album page that did not load) are listed in an `errors` list of that stage, which is absent when there are no errors. The file always holds only complete lines.

`python3 scripts/test_scrape.py` runs the tests of the tool. They use a fake helper on an ephemeral port and never touch Firefox, the real config or a real port.

## License

See `addon/LICENSE`.
