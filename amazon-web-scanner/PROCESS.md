# Process: finding the Amazon Music ID and album of a card

How the `AmazonID` and `Album` columns of a card set are filled. Amazon Music Germany is the only source. The collected data of a card set is in `assets/amazon-data/<csv name>.research.jsonl` at the top of the repository.

## What a card needs

- `AmazonID`: the id of the track (the number in `https://music.amazon.de/tracks/<ID>`), never the id of an album.
- `Album`: the name of the album or single that Amazon shows for this track.

## Scope

The first run (track search with a text rule) gave a confident pick for most cards. Cards without any flag are taken over as they are and are not looked at again. Only cards with a flag go through the steps below: a remix that was rejected, a pick that is much later than the earliest release, a weak pick, no hit, or an edition addition in the title that was fixed. For `hitster-de-aaaa0039` these are 35 of 308 cards.

## Steps for each card

The user opens the Amazon Music tab and stays logged in. Nobody else opens or closes tabs. The tool needs the number of that tab: `python3 scripts/scrape.py tabs` lists it, and every run gets it with `--tab-id N`. A run never chooses a tab and stops at once (exit code 4) if the tab is gone or left Amazon Music, then the user selects a tab again.

All work is done with job files (one JSON document with the set and a list of card entries, see the section `scrape.py` in `README.md`). Each entry names the card and the job. `scrape.py run <jobs.json> --tab-id N --results <results.jsonl>` runs the entries from top to bottom, exactly as they stand, and writes the result file that `--results` names. The tool decides nothing and skips nothing.

1. **Song search.** A `song` job (`{"card": 8, "job": "song"}`, optional `"query"`) reads the full result page `https://music.amazon.de/search/<query>/songs` and stores the list of its rows as the page shows them: rank, song id, title and artists (album name and duration only if the row has them, which it does not in the samples). The query is first artist and title as printed on the card, or the `query` of the job. The page is not scrolled, so the rows are those that are in the page once it has loaded. It opens no album page. Titles are searched as printed on the card, so edition additions like "Remastered" or "2023 Remaster" are fixed in the CSV first (or the job gets its own `query`). Then read the list and name the hits that need a look at their album.
   A `meta` job (`{"card": 8, "job": "meta", "hits": [1, "3-4"]}` or `"tracks": [<song ids>]`) loads the track page of exactly those hits by their song id and writes album name, release date (as written on the page), year, the title of the page (`meta_title`) and, if still empty, the duration into the hit. It opens no search, no menu and no album page. Every field is read and stored on its own, so a page without a date (some pages have none) still gives album, title and duration, and a field that cannot be read stays as it was. The result line lists such hits in `incomplete` with the missing names (`album`, `duration`, `date`). Only a page where nothing at all was found gets the error `meta page not readable` in the card.
2. **Judge the list.** Read the hits of one card at a time. The list is good enough (early exit) when it holds a believable original, meaning the right artist, no cover, no live, no karaoke, no tribute and no remix with a named remixer. The card is done then, the pick and its song id come from the list.
3. **Meta.** For the hits that decide the pick, a `meta` job reads album name, release date, year and duration from the track page of the hit. See step 1 for what it writes. It is the only way to learn the album and the year of a hit, and it needs no menu click and no album page.
   A `meta` job names at most 5 hits per card, the most plausible ones (right artist, no live, karaoke, remix or cover). A job file holds one entry per card, so if the pick is not safe yet, the next job file gets a new entry for that card with other ranks. This goes on in waves until a pick is safe. Never load all hits of a long list.
4. **A row or the original is missing in the list.** Then make a more exact song search for the card: a `song` job with its own `query` that holds the title and the album name, for example `The Smashing Pumpkins 1979 Mellon Collie And The Infinite Sadness`. The song id of the wanted song is then in the list itself. Nothing is ever scrolled, so a list shows the rows that are in the page once it has loaded, and a more exact query is the remedy for a page that is too long.
5. **Emergency exit: album search.** Only if no query helps (for example the original is missing from every list), an `album` job (`"song_ids": true`) searches `https://music.amazon.de/search/<artist>%09<title>?type=album` (a tab between artist and title), loads the releases and the rows that match the title, and reads the song id of each row through "Song anzeigen". A `track` job (`"album": "<ASIN>", "row": <index>`) reads the song id of one row if a pick still has none. Each release shows `tracks` (the album header) and `rows_read` (the rows that were in the page). Nothing is scrolled. A `track` job whose row is not in the page fails with `row not on the page (read N of M rows), use a more exact song job with the album name in the query`, and an `album` job without a fitting row is no error (compare `rows_read` and `tracks`). In both cases the remedy is step 4.

## Which release to pick

- The oldest release is the original. Prefer it. The age decides between the candidates that were looked at. With many equivalent entries (compilations of the same recording) the oldest of all does not have to be found.
- A single or an album does not matter much. A single is unambiguous, because it has no album or the album has the name of the song. An album is just as fine.
- A later re-release of a single is fine. There is no window of years.
- Remastered, Radio Edit, Single Version and Mono are always fine.
- A sampler is fine if it is not a remix and nothing better exists. A best-of by name ("The Very Best Of") counts as a sampler.
- A remix with a named remixer, a cover, a live version, a karaoke version and a tribute are not fine. A plain "Radio Remix" or "Mix" without a person is decided case by case. Release names like "(Remixes)" and "Jacques Lu Cont's Thin White Duke" are remixes without the word.
- The artist must match.
- Year and duration are hints only, because Amazon data can be wrong (a best-of from 1980, years that differ by one).
- Between two releases of the same year, prefer the plain album without an edition addition (Deluxe, Expanded, Anniversary).
- Avoid releases that are marked [Explicit], in the album name or in the title, so that the game can be played with children. Take a clean version or one without the label. If only explicit releases exist, the card is shown to the user.
- A single or a plain album is preferred, but a run of 5 hits is enough. If those 5 hits show only samplers or best-of albums, a sampler is fine and the search ends there.

## Explicit and clean releases

What was seen while filling `hitster-de`, 0039 and 0075 (2026-11-10). The rule itself is in "Which release to pick".

- The label in the album name sits on the release, not on the song. `Album [Explicit]` and `Album [Clean]` are two releases of the same album with different track ids. A harmless song can be on an album with the label.
- The song itself is marked by a small **E** that Amazon shows next to its title on the track page. In the text of the page it is a line `E` right after the title ("I Love It (Explicit)", E, Icona Pop). Seen on 2026-11-10: `B00IPKLH8G` (I Love It) has the E, `B00IUCO6PU` (the clean twin) has none. `B006LSV05O` (Rehab, album "Back To Black (Bonus track) [Explicit]") and `B076Y18JYY` (Just Give Me A Reason, album "The Truth About Love [Explicit]") have no E, so the songs are not explicit although the album name says so. The E is the sign to trust, the album label alone is not. The `meta` job reads it into the field `explicit` of the hit (true or false).
- Sometimes the track title has the label too ("I Love It (Explicit)", "Gold Digger [Clean]"). The rows of the `/songs` page have no album, so the label of the album shows only after a `meta` job on the hit.
- The clean twin is usually next to the explicit one in the same list, with the same title and nearly the same length (within a second). Examples: I Love It `B00IPKLH8G` (2:37, explicit) and `B00IUCO6PU` (2:36, clean), Gold Digger `B001SVENRG` (3:28, explicit) and `B00MEARTCG` (3:27, clean), Valerie on "Version [Explicit]" `B01F0G31C2` and "Version [Clean]" `B07D6GG4SK`.
- A single named like the song has often no label at all. Valerie `B076P6ZQ9G` (album "Valerie") is such a single.
- Not every explicit release has a clean one. "Just Give Me A Reason" only showed releases of "The Truth About Love [Explicit]", and "Die Eine" of Die Firma only the explicit deluxe edition of 1998. The clean one found for "Die Eine" (`B089DQW9VZ`, "Krieg Und Frieden") is a new recording of 2005, which the user accepted for a game with children.
- To find the clean twin, search with the title, the artist and the album name without the label, then `meta` the hits (at most 5). Prefer a hit with `[Clean]` or without a label over one with `[Explicit]`. If only explicit hits exist, the card goes to the user.
- The `Title` column of a card never has the label. Only the `Album` column can.
- State on 2026-11-10: albums with "Explicit" in the name are on 83 cards of 0039, 59 of 0075 and 44 of `hitster-de` (142 different tracks, 44 of them in more than one set). Only a handful have been switched to a clean twin so far (I Love It, Gold Digger, Valerie). The rest is to be reviewed.

## What the searches miss

- The album search does not list everything. For some cards it missed the original album completely (Led Zeppelin III, the plain "Hysteria", "90125"). It is not a filter.
- The song search lists one hit per album and may leave out the single of the same recording. This does not matter, because an album is as good as a single.
- Judging is done by reading each card on its own, not by text rules. Rules on titles failed again and again.

## The research file

`assets/amazon-data/<csv name>.research.jsonl` at the top of the repository holds the collected data of a card set, one line per card, and nothing else. No app packs it. The decisions are not stored in it, because they end up in the CSV. The data can be judged again with other rules at any time. `scripts/scrape.py` writes it.

The fields of a line, the same for every card set:

- `card`, `title`, `artist`, `card_year`: the card as in the CSV.
- `track_search`: `query` is the text of the song search, `hits` are all hits of the web player in their order. A hit has `rank`, `track_id`, `track_url`, `title`, `artists`, `album_id`, `album_url`, `album_name`, `album_artist`, `album_year`, `album_release_date`, `album_tracks`, `album_duration`, `duration` and `origin`. A song job fills only the first part. Old files may still hold album ids and the other album fields, they are read and left as they are. `origin` tells which lookup saw the id. Fields a lookup did not record are `null`. A hit that went through a `meta` job also has `meta_title` (the title of its track page).
- `album_search` (only for cards that needed it): `url` is the address of the album search (`/search/<artist>%09<title>?type=album`), `releases` are the albums found, each with `rank`, `album_id`, `album_url`, `album_name`, `album_artist`, `release_date`, `year`, `tracks` (from the album header), `rows_read` (rows that were in the page, smaller than `tracks` for a very long album), `duration` and `rows`. A row is a track of the album that resembles the card title, with `index`, `title`, `duration`, `track_id` and `track_url` (the id is read with "Song anzeigen" in the context menu, `null` if it was not read).

## Decisions

The decision for each card is kept outside the repository in a working file with one line per card until it is written into the CSV. The fields are `card`, `album`, `track_id`, `type`, `year`, `confidence`, `why` and `final`.

- `track_id` is the song id of the picked track. It is always set explicitly, never found again later by album name and year, because a name can match another track (a B-side of the same single, for example).
- `final` is true only for a decision that is made from the complete data of the card (both searches or a song search that was judged good enough) and, where the user decided, confirmed by the user. A draft has `final` false.
- When the user changes a pick, the entry changes to the song id the user named.


## Care for the browser

Keep the Amazon Music tab visible while a run goes on, best in a window of its own that is not minimized, because Firefox slows down tabs in the background and the page then reacts late. The tool works in small batches with pauses, waits while less than 1.5 GB of memory is free and goes to the start page of Amazon Music about every 60 loads. Firefox was once killed by the memory guard of the system. Never print the admin token of the helper.
