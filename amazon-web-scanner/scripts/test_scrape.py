#!/usr/bin/env python3
"""Tests for scrape.py with a fake helper on an ephemeral port. No real Firefox, no real config, no real port.

  python3 test_scrape.py
  python3 -I test_scrape.py
"""
import contextlib
import io
import json
import os
import re
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import research as R  # noqa: E402
import scrape as S  # noqa: E402
import webclient as W  # noqa: E402

TOKEN = "test-token-not-real-0123456789"
CSV_TEXT = ('Card#,Title,Artist,Year,AmazonID,Album\r\n'
            '1,Hells Bells,AC/DC,1980,,\r\n'
            '6,"Machinehead - Remastered",Bush,1994,,\r\n'
            '7,"Song, With Comma","Artist A & Artist B",,,\r\n'
            '9,Plain Song,Solo Artist,2001,,\r\n')

# ------------------------------------------------------------------ fake world
ALBUMS = {
    "B0ALBUM001": {"name": "Sixteen Stone", "artist": "Bush", "tracks": 12, "date": "01. NOV. 1994", "dur": "52 Minuten",
                   "rows": [("Everything Zen", "04:37", "B0TRACK001"), ("Machinehead", "04:16", "B0TRACK002"),
                            ("Machinehead (Live)", "04:40", "B0TRACK003")]},
    "B0ALBUM002": {"name": "Loaded: The Greatest Hits", "artist": "Bush", "tracks": 21, "date": "10. NOV. 2023", "dur": "1 Stunde und 28 Minuten",
                   "rows": [("Glycerine", "04:26", "B0TRACK004"), ("Machinehead", "04:17", "B0TRACK005")]},
    "B0ALBUM003": {"name": "Broken Album", "artist": "Bush", "tracks": 5, "date": "01. JAN. 2000", "dur": "20 Minuten",
                   "rows": [("Machinehead", "04:00", "B0TRACK006")], "fail": True},
}
ALBUMS["B0LONG0001"] = {"name": "Mellon Collie Deluxe", "artist": "Smashing Pumpkins", "tracks": 92, "date": "23. OKT. 1995", "dur": "3 Stunden",
                        "rows": [("Track %d" % i, "03:%02d" % (i % 60), "B0LONGT%03d" % i) for i in range(92)], "lazy": True}
ALBUMS["B0LONG0001"]["rows"][60] = ("1979", "04:25", "B0LONGT060")
ALBUMS["B0LONG0002"] = {"name": "Stuck Edition", "artist": "Smashing Pumpkins", "tracks": 92, "date": "01. JAN. 2000", "dur": "3 Stunden",
                        "rows": [("Track %d" % i, "03:%02d" % (i % 60), "B0STUCK%03d" % i) for i in range(92)], "lazy": True, "stuck": True}
ALBUMS["B0LONG0002"]["rows"][60] = ("1979", "04:25", "B0STUCK060")
ALBUMS["B0LONG0002"]["buttons_extra"] = 5  # more buttons than rows: rows are really missing
# the header counts 93 tracks, the page has 92 rows and 92 buttons (a disc heading is counted, for example)
ALBUMS["B0DEVALB01"] = {"name": "Odd Header", "artist": "Dev Artist", "tracks": 93, "date": "01. JAN. 2001", "dur": "2 Stunden",
                       "rows": [("Dev Song %d" % i, "02:%02d" % (i % 60), "B0DEVT%03d" % i) for i in range(92)], "nopad": True}
TRACK_META = {
    "B0TRACK002": {"title": "Machinehead", "artist": "Bush", "album": "Sixteen Stone", "dur": "4 Min. 16 Sek.", "date": "01. Nov. 1994"},
    "B0TRACK005": {"title": "Machinehead (Remastered 2012)", "artist": "Bush", "album": "Loaded: The Greatest Hits [Explicit]",
                   "dur": "4 Min. 17 Sek.", "date": "10. Nov. 2023"},
    "B0TRACK003": {"title": "Machinehead (Live)", "artist": "Bush", "album": "Live", "dur": "4 Min. 40 Sek.", "date": "5. Mai 2001", "explicit": True},
    "B0TRACK007": {"title": "Machinehead", "artist": "Lullaby Baby", "album": "Baby Hits", "dur": "2 Min. 0 Sek.", "date": None},  # no date
    # the real pages: no date at all, and a page title that is longer than the title in the text
    "B0METANOD1": {"title": "Machinehead (Live From KROQ's Almost Acoustic Christmas, Los Angeles/1995)", "artist": "Bush", "album": "Zen X Four",
                   "dur": "4 Min. 47 Sek.", "date": None, "text": "Song | Machinehead (Live) | Bush | 4 Min. 47 Sek. | HD | Spielen"},
    "B0METANOD2": {"title": "Aint That a Shame", "artist": "Fats Domino", "album": "The Piano Men", "dur": "2 Min. 25 Sek.", "date": None},
    "B0METANODU": {"title": "Song Without Time", "artist": "Bush", "album": "Some Album", "dur": None, "date": "3. März 2002"},
    "B0METANAL1": {"title": "Song Without Album", "artist": "Bush", "album": None, "dur": "3 Min. 5 Sek.", "date": "12. Sept. 1999"},
    "B0METAONLY": {"title": "Only A Title", "artist": "Bush", "album": None, "dur": None, "date": None},
    "B0METANONE": {"title": "x", "artist": "x", "album": None, "dur": None, "date": None, "page_title": "Amazon Music", "text": "nothing here"},
    "B0METASEC1": {"title": "Seconds Only", "artist": "Bush", "album": "Odd", "dur": None, "date": None, "text": "Song | Seconds Only | Bush | 47 Sek. | HD | Spielen"},
    "B0METAMIN1": {"title": "Minutes Only", "artist": "Bush", "album": "Odd", "dur": None, "date": None, "text": "Song | Minutes Only | Bush | 3 Min. | • | 30. Dez. 1990"},
}
SONG_SEARCH = {
    "Meta Cases": [{"dur": "03:00", "id": i, "title": "Hit " + i, "artists": ["Bush"], "album": "X", "release": None, "album_id": "B0ALBUM001"}
                   for i in ("B0METANOD1", "B0METANOD2", "B0METANODU", "B0METANAL1", "B0METAONLY", "B0METANONE", "B0METASEC1", "B0METAMIN1")],
    "Bush Machinehead - Remastered": [
        {"dur": "04:16", "id": "B0TRACK002", "title": "Machinehead", "artists": ["Bush"], "album": "Sixteen Stone", "release": "01. NOV. 1994", "album_id": "B0ALBUM001"},
        {"dur": "04:17", "id": "B0TRACK005", "title": "Machinehead", "artists": ["Bush"], "album": "Loaded: The Greatest Hits", "release": None, "album_id": "B0ALBUM002"},
        {"dur": "04:40", "id": "B0TRACK003", "title": "Machinehead (Live)", "artists": ["Bush", "Guest"], "album": "Live", "release": "05. MAI 2001", "album_id": "B0ALBUM001"},
    ],
    "Solo Artist Plain Song": [],
}
SONG_SEARCH["Smashing Pumpkins 1979"] = [
    {"dur": "04:25", "id": "B0LONGT060", "title": "1979", "artists": ["Smashing Pumpkins"], "album": "Mellon Collie Deluxe",
     "release": None, "album_id": "B0LONG0001"}]
SONG_SEARCH["Bush Machinehead - Remastered"].append(
    {"dur": "02:00", "id": "B0TRACK007", "title": "Machinehead", "artists": ["Lullaby Baby"], "album": "Baby Hits", "release": None, "album_id": "B0ALBUM004"})
ALBUMS["B0ALBUM004"] = {"name": "Baby Hits", "artist": "Lullaby Baby", "tracks": 10, "date": "01. JAN. 2015", "dur": "30 Minuten",
                        "rows": [("Machinehead", "02:00", "B0TRACK007")]}
ALBUM_SEARCH = {
    ("Smashing Pumpkins", "1979"): ["B0LONG0001", "B0LONG0002"],
    ("Dev Artist", "Dev Song 7"): ["B0DEVALB01"],
    ("Bush", "Machinehead - Remastered"): ["B0ALBUM001", "B0ALBUM002", "B0ALBUM003"],
    ("Artist A", "Song, With Comma"): [],
    ("Solo Artist", "Plain Song"): [],
}
PLAIN_ALBUM_SEARCH = {"Song, With Comma": ["B0ALBUM002"]}


class FakeTab:
    def __init__(self, tab_id, url="https://music.amazon.de/", window_id=1):
        self.id, self.url, self.window_id = tab_id, url, window_id
        self.title = "Amazon Music"
        self.menu = None
        self.visible = 40  # rows of a lazy album page that are shown now
        self.history = []


class FakeHost:
    def __init__(self):
        self.tabs = {1: FakeTab(1)}
        self.cur = self.tabs[1]
        self.next_id = 100
        self.requests = []
        self.back_mode = "ok"  # ok, 404 or error
        self.status_extra = {}  # for example hostVersion and build
        self.lock = threading.Lock()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=0.01), daemon=True)
        self.thread.start()

    # the state of the tab that the current request works on
    url = property(lambda s: s.cur.url, lambda s, v: setattr(s.cur, "url", v))
    title = property(lambda s: s.cur.title, lambda s, v: setattr(s.cur, "title", v))
    menu = property(lambda s: s.cur.menu, lambda s, v: setattr(s.cur, "menu", v))
    history = property(lambda s: s.cur.history)

    def close(self):
        if self.thread.is_alive():
            self.server.shutdown()
            self.server.server_close()
            self.thread.join()

    def count(self, path):
        return sum(1 for p, _b in self.requests if p == path)

    def navigated(self):
        return [b["url"] for p, b in self.requests if p == "/navigate"]

    # --- pages
    def page(self):
        u = urllib.parse.urlparse(self.url)
        path = urllib.parse.unquote(u.path)
        if path.startswith("/search/") and path.endswith("/songs"):
            return ("songs", path[len("/search/"):-len("/songs")])
        if path.startswith("/search/"):
            q = path[len("/search/"):]
            if "type=album" in u.query:
                return ("asearch", q)
            return ("ssearch", q)  # the old short list, not used by the scraper any more
        m = re.match(r"/albums/(\w+)", path)
        if m:
            return ("album", m.group(1))
        m = re.match(r"/tracks/(\w+)", path)
        if m:
            return ("track", m.group(1))
        return ("home", None)

    def go(self, url):
        self.history.append(self.url)
        self.url = url
        self.cur.visible = 40
        kind, key = self.page()
        self.title = "Amazon Music"
        if kind == "track":
            m = TRACK_META.get(key)
            if m is None:
                self.title = "X Song von Bush aus dem Album Y auf Amazon Music"
            elif "page_title" in m:
                self.title = m["page_title"]
            elif m["album"] is None:
                self.title = "%s Song von %s auf Amazon Music" % (m["title"], m["artist"])
            else:
                self.title = "%s Song von %s aus dem Album %s auf Amazon Music" % (m["title"], m["artist"], m["album"])

    def summary(self):
        kind, key = self.page()
        items = [{"tag": "button", "testid": "Player", "label": "Play"}]
        if kind == "songs":
            for h in SONG_SEARCH.get(key, []):  # the structure of the real page: a row has title, artists and a menu button, no album, no time
                items.append({"tag": "div", "testid": "HorizontalItem,undefined", "text": h["title"] + h["artists"][0]})
                items.append({"tag": "a", "testid": "TrackItem_Title", "label": h["title"], "href": "/tracks/%s?do=play" % h["id"], "text": h["title"]})
                for a in h["artists"]:
                    items.append({"tag": "a", "label": a, "href": "/artists/" + a})
                items.append({"tag": "button", "testid": "IconButton,TrackLike_Follow", "label": "Gefällt mir"})
                items.append({"tag": "button", "selector": "button#%s.css-g5y9jx.r-1loqt21" % h["id"], "testid": "IconButton",
                              "label": "Weitere Optionen für " + h["title"]})
            # the mini player at the end of the page has a title, an artist and an album link that belong to no row
            items.append({"tag": "a", "testid": "MiniPlayer_Title", "label": "Mini", "href": "/tracks/B0MINIPLAY"})
            items.append({"tag": "a", "label": "Mini Artist", "href": "/artists/B0MINIART"})
            items.append({"tag": "a", "label": "Mini Album", "href": "/albums/B0MINIALBU"})
        elif kind == "asearch":
            if "\t" in key:
                ids = ALBUM_SEARCH.get(tuple(key.split("\t", 1)), [])
            else:
                ids = PLAIN_ALBUM_SEARCH.get(key, [])
            for aid in ids:
                a = ALBUMS[aid]
                items.append({"tag": "a", "testid": "ItemMetadata_PrimaryText", "label": a["name"], "href": "/albums/" + aid})
                items.append({"tag": "a", "label": a["artist"], "href": "/artists/x"})
        elif kind == "album":
            for t, d, _tid in self.album_rows(key):
                items.append({"tag": "li", "testid": "ListItem,album-descriptive-row-item", "label": t})
                items.append({"tag": "span", "label": d})
                items.append({"tag": "button", "testid": "AlbumTrackEdge_ContextMenu", "label": "Mehr"})
            for _ in range(ALBUMS[key].get("buttons_extra", 0)):
                items.append({"tag": "button", "testid": "AlbumTrackEdge_ContextMenu", "label": "Mehr"})
        return items

    def album_rows(self, key):
        """The rows that the page shows now. A normal fake album is padded to its track count with filler rows."""
        a = ALBUMS[key]
        if a.get("lazy"):
            return a["rows"][:self.cur.visible]
        if a.get("nopad"):
            return a["rows"]
        return a["rows"] + [("Filler %d" % i, "01:00", "B0FILL%04d" % i) for i in range(len(a["rows"]), a["tracks"])]

    def text(self, selector):
        kind, key = self.page()
        if kind == "album":
            a = ALBUMS[key]
            for tid, val in (("Headline", a["name"]), ("Primary", a["artist"]), ("TrackCount", "%d Titel" % a["tracks"]),
                             ("ReleaseDate", a["date"]), ("Duration", a["dur"])):
                if selector.endswith('PageHeaderMetadata_%s"]' % tid) or selector.endswith('TertiaryBox_%s"]' % tid):
                    return val
        return ""

    def amazon_tabs(self):
        return [x for x in self.tabs.values() if "music.amazon" in x.url]

    def handle(self, method, path, body):
        self.requests.append((path, body))
        if path in ("/dom", "/act", "/navigate", "/back"):
            if "tabId" in body:
                if body["tabId"] not in self.tabs or self.tabs[body["tabId"]] not in self.amazon_tabs():
                    return 409, {"ok": False, "code": "INVALID_TAB", "error": "Not an open Amazon Music tab."}
                self.cur = self.tabs[body["tabId"]]
            else:
                self.cur = self.tabs[min(self.tabs)]
        if path == "/status":
            return 200, dict({"ok": True, "addon": True}, **self.status_extra)
        if path == "/tabs":
            return 200, {"ok": True, "tabs": [{"tabId": x.id, "url": x.url, "title": x.title, "windowId": x.window_id}
                                              for x in self.tabs.values()]}
        if path == "/back":
            if self.back_mode == "404":
                return 404, {"ok": False, "error": "Not found."}
            if self.back_mode == "error" or not self.history:
                return 502, {"ok": False, "error": "no history"}
            self.url = self.history.pop()
            self.cur.visible = 40
            self.title = "Amazon Music"
            return 200, {"ok": True, "url": self.url}
        if path == "/navigate":
            m = re.search(r"/albums/(\w+)", body["url"])
            if m and ALBUMS.get(m.group(1), {}).get("fail"):
                return 502, {"ok": False, "error": "page did not load"}
            self.go(body["url"])
            return 200, {"ok": True, "url": self.url}
        if path == "/dom":
            if body.get("mode") == "summary":
                return 200, {"ok": True, "data": self.summary()}
            if body.get("selector"):
                return 200, {"ok": True, "data": self.text(body["selector"])}
            kind, key = self.page()
            m = TRACK_META.get(key) if kind == "track" else None
            if m:
                if "text" in m:
                    return 200, {"ok": True, "data": m["text"]}
                parts = ["Song", m["title"]] + (["E"] if m.get("explicit") else []) + [m["artist"]] + ([m["dur"]] if m["dur"] else [])
                parts += ["•", m["date"]] if m["date"] else ["HD", "Spielen"]
                return 200, {"ok": True, "data": " | ".join(parts)}
            return 200, {"ok": True, "data": "Lied 4 Min. 16 Sek. und mehr"}
        if path == "/act":
            sel, idx = body["selector"], body.get("index", 0)
            kind, key = self.page()
            if body.get("action") == "press":
                return 200, {"ok": True}  # a key press loads nothing
            if "AlbumTrackEdge_ContextMenu" in sel and idx >= len(self.album_rows(key)):
                return 502, {"ok": False, "error": "no match"}
            if "AlbumTrackEdge_ContextMenu" in sel:
                self.menu = ("row", key, idx)
            elif "OverflowMenu_Option_ViewTrack" in sel and self.menu and self.menu[0] == "row":
                self.go("https://music.amazon.de/tracks/" + self.album_rows(self.menu[1])[self.menu[2]][2])
            else:
                return 502, {"ok": False, "error": "no match"}
            return 200, {"ok": True}
        return 404, {"ok": False}

    def _handler(self):
        host = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _do(self, method):
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}") if n else {}
                path = urllib.parse.urlparse(self.path).path
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    code, obj = 401, {"ok": False, "error": "Invalid token."}
                else:
                    with host.lock:
                        code, obj = host.handle(method, path, body)
                data = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                self._do("GET")

            def do_POST(self):
                self._do("POST")
        return H


# ------------------------------------------------------------------ test base
class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.csv_dir = os.path.join(self.tmp.name, "csv")
        self.out_dir = os.path.join(self.tmp.name, "out")
        self.cfg_dir = os.path.join(self.tmp.name, "cfg")
        for d in (self.csv_dir, self.cfg_dir):
            os.makedirs(d)
        with open(os.path.join(self.csv_dir, "testset.csv"), "w", encoding="utf-8", newline="") as f:
            f.write(CSV_TEXT)
        with open(os.path.join(self.cfg_dir, "config.json"), "w") as f:
            json.dump({"port": 1, "adminToken": TOKEN}, f)
        self.host = FakeHost()
        self.addCleanup(self.host.close)
        self.research = os.path.join(self.out_dir, "testset.research.jsonl")

    OLD_COMMANDS = ("song", "album", "track")

    def jobs_path(self):
        return os.path.join(self.tmp.name, "jobs.json")

    def write_jobs(self, *entries, set_name="testset", **top):
        """Write a job file: one JSON document with the set and the list of card entries."""
        doc = dict({"set": set_name, "cards": list(entries)}, **top)
        return self.write_raw(json.dumps(doc))

    def write_raw(self, text):
        with open(self.jobs_path(), "w", encoding="utf-8") as f:
            f.write(text)
        return self.jobs_path()

    def results(self):
        path = os.path.join(self.tmp.name, "jobs.results.jsonl")
        with open(path, encoding="utf-8") as f:
            return [json.loads(x) for x in f if x.strip()]

    def translate(self, args):
        """Turn the old style arguments of the tests (song --cards 6) into a job file and a run command."""
        cmd, rest, jobs, keep = args[0], args[1:], [], []
        opt = {}
        flags = {"--song-ids", "--force", "--dry-run", "--all-hits"}
        i = 0
        while i < len(rest):
            a = rest[i]
            if a in flags:
                opt[a] = True
                i += 1
            else:
                opt[a] = rest[i + 1]
                i += 2
        cards = R.parse_cards(opt.get("--cards") or opt.get("--card"), set(R.read_csv(os.path.join(self.csv_dir, "testset.csv"))))
        for n in cards:
            job = {"card": n, "job": cmd}
            if cmd == "song" and "--query" in opt:
                job["query"] = opt["--query"]
            if cmd == "album" and opt.get("--song-ids"):
                job["song_ids"] = True
            if cmd == "track":
                job.update({"album": opt["--album"], "row": int(opt["--row"])})
            jobs.append(job)
        path = self.write_jobs(*jobs)
        out = ["run", path]
        for k in ("--tab-id", "--dry-run", "--max-errors"):
            if k in opt:
                out += [k] if opt[k] is True else [k, opt[k]]
        return out

    def run_cli(self, *args, meminfo=lambda: 10 ** 7, tab="1", results=True):
        args = list(args)
        if args[0] in self.OLD_COMMANDS:
            try:
                args = self.translate(args)
            except (ValueError, KeyError):
                return 2, "", "bad test arguments"
        if args[0] == "run" and tab is not None and "--tab-id" not in args:
            args += ["--tab-id", tab]
        if args[0] == "run" and results and "--results" not in args:
            args += ["--results", os.path.join(self.tmp.name, "jobs.results.jsonl")]
        sets = [] if args[0] == "run" else ["--set", "testset"]  # run takes the set from every job line
        argv = args + sets + ["--csv-dir", self.csv_dir, "--out-dir", self.out_dir,
                       "--port", str(self.host.port), "--config", self.cfg_dir, "--pause", "0"]
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = S.main(argv, sleep=lambda s: None, meminfo=meminfo, poll=0, page_pause=0, install_signal=False)
        self.assertNotIn(TOKEN, out.getvalue() + err.getvalue())
        return rc, out.getvalue(), err.getvalue()

    def records(self):
        return R.load(self.research)

    def releases_of_card_6(self, *ids):
        """Let the fake album search of card 6 find only these albums."""
        key = ("Bush", "Machinehead - Remastered")
        old = ALBUM_SEARCH[key]
        ALBUM_SEARCH[key] = list(ids)
        self.addCleanup(ALBUM_SEARCH.__setitem__, key, old)


# ------------------------------------------------------------------ tests
class CsvAndCards(Base):
    def test_read_csv(self):
        cards = R.read_csv(os.path.join(self.csv_dir, "testset.csv"))
        self.assertEqual(sorted(cards), [1, 6, 7, 9])
        self.assertEqual(cards[6], {"card": 6, "title": "Machinehead - Remastered", "artist": "Bush", "card_year": 1994})
        self.assertEqual(cards[7]["title"], "Song, With Comma")
        self.assertEqual(cards[7]["artist"], "Artist A & Artist B")
        self.assertIsNone(cards[7]["card_year"])

    def test_parse_cards(self):
        avail = {1, 6, 7, 9, 10, 11, 12}
        self.assertEqual(R.parse_cards("9,1,10-12", avail), [1, 9, 10, 11, 12])
        self.assertEqual(R.parse_cards("all", avail), sorted(avail))
        self.assertEqual(R.parse_cards("6,6,6", avail), [6])
        for bad in ("5", "x", "12-10", "", "1,,a"):
            with self.assertRaises(ValueError):
                R.parse_cards(bad, avail)

    def test_cards_file(self):
        p = os.path.join(self.tmp.name, "cards.txt")
        with open(p, "w") as f:
            f.write("1, 6  # first\n7\n9-9\n")
        self.assertEqual(R.parse_cards(None, {1, 6, 7, 9}, p), [1, 6, 7, 9])

    def test_bad_cards_exit_code(self):
        rc, _o, err = self.run_cli("song", "--cards", "55")
        self.assertEqual(rc, 2)
        self.assertEqual(self.host.requests, [])


class ResearchFile(Base):
    def info(self, n):
        return {"card": n, "title": "T%d" % n, "artist": "A", "card_year": 2000}

    def test_merge_keeps_other_cards_and_stages(self):
        recs = {}
        R.merge_stage(recs, self.info(5), "track_search", {"query": "q", "hits": []})
        R.merge_stage(recs, self.info(2), "track_search", {"query": "q2", "hits": []})
        recs[5]["custom"] = "kept"
        R.merge_stage(recs, self.info(5), "album_search", {"url": "u", "releases": []})
        R.save(self.research, recs)
        again = R.load(self.research)
        self.assertEqual(list(again), [2, 5])  # ordered by card number
        self.assertEqual(again[5]["track_search"]["query"], "q")
        self.assertEqual(again[5]["album_search"]["url"], "u")
        self.assertEqual(again[5]["custom"], "kept")
        self.assertEqual(list(again[5])[:6], ["card", "title", "artist", "card_year", "track_search", "album_search"])
        R.merge_stage(again, self.info(2), "album_search", {"url": "x", "releases": []})
        self.assertEqual(again[2]["track_search"]["query"], "q2")

    def test_atomic_write_failure_keeps_old_file(self):
        R.save(self.research, {1: {"card": 1, "x": 1}})
        with open(self.research, "rb") as f:
            before = f.read()
        with self.assertRaises(TypeError):
            R.save(self.research, {1: {"card": 1, "x": object()}})
        with open(self.research, "rb") as f:
            self.assertEqual(f.read(), before)
        self.assertEqual(os.listdir(self.out_dir), ["testset.research.jsonl"])  # no temp file left

    def test_load_skips_bad_lines(self):
        os.makedirs(self.out_dir)
        with open(self.research, "w") as f:
            f.write('{"card": 3}\nnot json\n\n{"nocard": 1}\n')
        self.assertEqual(list(R.load(self.research)), [3])


class SongStage(Base):
    def test_song_stage_makes_only_the_list(self):
        rc, out, err = self.run_cli("song", "--cards", "6")
        self.assertEqual(rc, 0, err)
        rec = self.records()[6]
        self.assertEqual(rec["card_year"], 1994)
        ts = rec["track_search"]
        self.assertEqual(ts["query"], "Bush Machinehead - Remastered")  # title as printed, first artist only
        self.assertNotIn("album_search", rec)
        self.assertNotIn("errors", ts)
        self.assertNotIn("saturated", ts)
        self.assertEqual([h["rank"] for h in ts["hits"]], [1, 2, 3, 4])
        h1, h2, h3, h4 = ts["hits"]
        self.assertEqual(h1["track_id"], "B0TRACK002")
        self.assertEqual(h1["track_url"], "https://music.amazon.de/tracks/B0TRACK002")
        self.assertEqual((h1["title"], h1["artists"]), ("Machinehead", ["Bush"]))
        self.assertEqual(ts["page"], "songs")
        self.assertEqual(self.host.navigated(), ["https://music.amazon.de/search/Bush%20Machinehead%20-%20Remastered/songs"])
        # the sample pages of the songs page show neither an album nor a time in a row
        for h in ts["hits"]:
            self.assertIsNone(h["album_name"])
            self.assertIsNone(h["duration"])
        self.assertNotIn("B0MINIPLAY", [h["track_id"] for h in ts["hits"]])  # the mini player belongs to no row
        self.assertEqual(h1["artists"], ["Bush"])
        self.assertEqual(h1["origin"], ["song search"])
        self.assertEqual(h3["artists"], ["Bush", "Guest"])
        self.assertEqual(h4["artists"], ["Lullaby Baby"])
        for h in ts["hits"]:
            for k in ("album_id", "album_url", "album_artist", "album_year", "album_release_date", "album_tracks", "album_duration"):
                self.assertIsNone(h[k], k)
        # no album page and no menu click, whatever the artist
        self.assertEqual([u for u in self.host.navigated() if "/albums/" in u], [])
        self.assertEqual(self.host.count("/act"), 0)

    def test_removed_commands_and_options_are_refused(self):
        for argv in (["song", "--set", "x"], ["album", "--set", "x"], ["look", "--set", "x"], ["track", "--set", "x"],
                     ["run", "j", "--cards", "1"], ["run", "j", "--cards-file", "f"], ["run", "j", "--force"],
                     ["run", "j", "--max-hits", "3"], ["run", "j", "--song-ids"], ["run", "j", "--hits", "1"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                S.build_parser().parse_args(argv)

    def test_own_query_is_used_encoded_and_stored(self):
        SONG_SEARCH["Custom Query, With Comma"] = [SONG_SEARCH["Bush Machinehead - Remastered"][0]]
        self.addCleanup(SONG_SEARCH.pop, "Custom Query, With Comma")
        self.write_jobs({"card": 6, "job": "song", "query": "Custom Query, With Comma"})
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.host.navigated()[0], "https://music.amazon.de/search/Custom%20Query%2C%20With%20Comma/songs")
        ts = self.records()[6]["track_search"]
        self.assertEqual(ts["query"], "Custom Query, With Comma")
        self.assertEqual(len(ts["hits"]), 1)
        self.assertEqual(self.results()[0]["query"], "Custom Query, With Comma")

    def test_there_is_no_limit_of_hits(self):
        many = [dict(SONG_SEARCH["Bush Machinehead - Remastered"][0], id="B0MANY%04d" % i) for i in range(30)]
        SONG_SEARCH["Many Hits"] = many
        self.addCleanup(SONG_SEARCH.pop, "Many Hits")
        self.write_jobs({"card": 6, "job": "song", "query": "Many Hits"})
        self.run_cli("run", self.jobs_path())
        self.assertEqual(len(self.records()[6]["track_search"]["hits"]), 30)

    def test_no_hits_is_stored_and_resumed(self):
        rc, _o, err = self.run_cli("song", "--cards", "9")
        self.assertEqual(rc, 0)
        ts = self.records()[9]["track_search"]
        self.assertEqual(ts["hits"], [])
        self.assertEqual(ts["errors"], [{"error": "no result rows after waiting"}])

    def test_token_is_checked_by_the_fake(self):
        with open(os.path.join(self.cfg_dir, "config.json"), "w") as f:
            json.dump({"adminToken": "wrong"}, f)
        rc, _o, err = self.run_cli("song", "--cards", "6")
        self.assertEqual(rc, 3)
        self.assertFalse(os.path.exists(self.research))


class RepeatAndMerge(Base):
    def test_a_second_run_repeats_the_same_jobs(self):
        self.run_cli("song", "--cards", "6")
        n = self.host.count("/navigate")
        rc, _o, err = self.run_cli("song", "--cards", "6")
        self.assertEqual(rc, 0)
        self.assertGreater(self.host.count("/navigate"), n)  # no skipping, the job runs as it stands
        self.assertNotIn("skipped", err)
        self.assertEqual(len(self.records()[6]["track_search"]["hits"]), 4)  # the stage is replaced, not doubled

    def test_stages_merge_and_other_cards_stay(self):
        self.run_cli("song", "--cards", "6,1")
        self.run_cli("album", "--cards", "6")
        recs = self.records()
        self.assertEqual(sorted(recs), [1, 6])
        self.assertIn("track_search", recs[6])
        self.assertIn("album_search", recs[6])
        self.assertIn("track_search", recs[1])
        self.assertNotIn("album_search", recs[1])
        before = recs[6]["track_search"]
        self.run_cli("album", "--cards", "6")
        self.assertEqual(self.records()[6]["track_search"], before)

    def test_dry_run_touches_nothing(self):
        rc, out, _e = self.run_cli("song", "--cards", "6,1", "--dry-run")
        self.assertEqual(rc, 0)
        self.assertIn('index 1 set testset card 6 job song query "Bush Machinehead - Remastered"', out)
        self.assertEqual(self.host.requests, [])
        self.assertFalse(os.path.exists(self.research))
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "jobs.results.jsonl")))


class AlbumStage(Base):
    def test_album_stage_without_song_ids(self):
        rc, out, err = self.run_cli("album", "--cards", "6")
        self.assertEqual(rc, 0, err)
        st = self.records()[6]["album_search"]
        self.assertEqual(st["url"], "https://music.amazon.de/search/Bush%09Machinehead%20-%20Remastered?type=album")
        self.assertEqual(self.host.count("/act"), 0)
        rels = st["releases"]
        self.assertEqual([r["album_id"] for r in rels], ["B0ALBUM001", "B0ALBUM002"])  # the third one failed to load
        r1 = rels[0]
        self.assertEqual((r1["rank"], r1["album_name"], r1["album_artist"], r1["year"], r1["tracks"], r1["duration"]),
                         (1, "Sixteen Stone", "Bush", 1994, 12, "52 Minuten"))
        self.assertEqual(r1["release_date"], "01. NOV. 1994")
        self.assertEqual(r1["album_url"], "https://music.amazon.de/albums/B0ALBUM001")
        # rows that resemble the card title: "Machinehead" and "Machinehead (Live)", never "Everything Zen"
        self.assertEqual([(x["index"], x["title"], x["duration"], x["track_id"], x["track_url"]) for x in r1["rows"]],
                         [(1, "Machinehead", "04:16", None, None), (2, "Machinehead (Live)", "04:40", None, None)])
        self.assertEqual(len(st["errors"]), 1)
        self.assertEqual(st["errors"][0]["album_id"], "B0ALBUM003")
        self.assertIn("page did not load", st["errors"][0]["error"])

    def test_song_ids_via_act_and_tabs(self):
        self.releases_of_card_6("B0ALBUM001", "B0ALBUM002")
        rc, _o, err = self.run_cli("album", "--cards", "6", "--song-ids")
        self.assertEqual(rc, 0, err)
        rels = self.records()[6]["album_search"]["releases"]
        self.assertEqual([x["track_id"] for x in rels[0]["rows"]], ["B0TRACK002", "B0TRACK003"])
        self.assertEqual(rels[0]["rows"][0]["track_url"], "https://music.amazon.de/tracks/B0TRACK002")
        self.assertEqual([x["track_id"] for x in rels[1]["rows"]], ["B0TRACK005"])
        self.assertEqual(self.host.count("/act"), 2 * 3)  # two clicks per row
        self.assertNotIn("errors", self.records()[6]["album_search"])

    def album_loads(self):
        return [u for u in self.host.navigated() if "/albums/" in u]

    def test_song_ids_use_back_instead_of_reloading(self):
        self.releases_of_card_6("B0ALBUM001")
        rc, _o, err = self.run_cli("album", "--cards", "6", "--song-ids")
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.host.count("/back"), 2)
        self.assertEqual(len(self.album_loads()), 1)  # the album page was loaded once only
        rows = self.records()[6]["album_search"]["releases"][0]["rows"]
        self.assertEqual([x["track_id"] for x in rows], ["B0TRACK002", "B0TRACK003"])

    def test_back_missing_falls_back_to_reload_once(self):
        self.releases_of_card_6("B0ALBUM001", "B0ALBUM002")
        for mode in ("404", "error"):
            self.host.back_mode = mode
            self.host.requests.clear()
            rc, _o, err = self.run_cli("album", "--cards", "6", "--song-ids")
            self.assertEqual(rc, 0, err)
            self.assertEqual(self.host.count("/back"), 1)  # tried once, then remembered
            self.assertEqual(len(self.album_loads()), 3)  # 2 first loads, 1 reload before row 2 of album 1
            self.assertEqual(err.count("/back does not work"), 1)
            recs = self.records()[6]["album_search"]
            self.assertNotIn("errors", recs)
            self.assertEqual([x["track_id"] for r in recs["releases"] for x in r["rows"]],
                             ["B0TRACK002", "B0TRACK003", "B0TRACK005"])

    def test_a_repeated_album_job_runs_again(self):
        self.releases_of_card_6("B0ALBUM001")
        self.run_cli("album", "--cards", "6")
        self.run_cli("album", "--cards", "6", "--song-ids")
        rows = self.records()[6]["album_search"]["releases"][0]["rows"]
        self.assertEqual([x["track_id"] for x in rows], ["B0TRACK002", "B0TRACK003"])
        n = self.host.count("/act")
        self.run_cli("album", "--cards", "6", "--song-ids")
        self.assertEqual(self.host.count("/act"), 2 * n)  # no skipping

    def test_plain_search_fallback(self):
        rc, _o, err = self.run_cli("album", "--cards", "7")
        self.assertEqual(rc, 0, err)
        st = self.records()[7]["album_search"]
        self.assertEqual(st["url"], "https://music.amazon.de/search/Song%2C%20With%20Comma?type=album")
        self.assertEqual(len(st["releases"]), 1)
        self.assertEqual(st["releases"][0]["rows"], [])  # no row resembles the title
        self.assertIsNone(self.records()[7]["card_year"])
        self.assertTrue(any("Artist%20A%09Song" in u for u in self.host.navigated()))  # first artist only

    def test_track_command(self):
        rc, _o, err = self.run_cli("track", "--cards", "6", "--album", "B0ALBUM002", "--row", "1")
        self.assertEqual(rc, 0, err)
        rel = self.records()[6]["album_search"]["releases"][0]
        self.assertEqual(rel["album_id"], "B0ALBUM002")
        self.assertEqual(rel["rows"], [{"index": 1, "title": "Machinehead", "duration": "04:17",
                                        "track_id": "B0TRACK005", "track_url": "https://music.amazon.de/tracks/B0TRACK005"}])
        # a second call for the same album adds a row to the same release
        self.run_cli("track", "--cards", "6", "--album", "B0ALBUM002", "--row", "0")
        rows = self.records()[6]["album_search"]["releases"][0]["rows"]
        self.assertEqual([r["index"] for r in rows], [0, 1])
        self.assertEqual(len(self.records()[6]["album_search"]["releases"]), 1)

    def test_album_job_with_own_query(self):
        self.releases_of_card_6("B0ALBUM001")
        ALBUM_SEARCH[("Bush", "Sixteen")] = ["B0ALBUM002"]
        self.addCleanup(ALBUM_SEARCH.pop, ("Bush", "Sixteen"))
        self.write_jobs({"card": 6, "job": "album", "query": "Bush\tSixteen"})
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 0, err)
        st = self.records()[6]["album_search"]
        self.assertEqual(st["url"], "https://music.amazon.de/search/Bush%09Sixteen?type=album")
        self.assertEqual([r["album_id"] for r in st["releases"]], ["B0ALBUM002"])
        self.assertEqual(self.results()[0]["query"], "Bush\tSixteen")


class SongsPage(Base):
    def test_parser_assigns_items_to_the_next_title_not_by_position(self):
        items = [{"testid": "MiniPlayer_Title", "label": "Before", "href": "/tracks/B0BEFORE01"},
                 {"label": "Stray Artist", "href": "/artists/B0STRAY001"},
                 {"testid": "TrackItem_Title", "label": "One", "href": "/tracks/B0ROWONE01?do=play"},
                 {"label": "Artist A", "href": "/artists/B0ARTISTA1"},
                 {"label": "Artist B", "href": "/artists/B0ARTISTB1"},
                 {"label": "Some Album", "href": "/albums/B0ALBUMXX1"},
                 {"label": "3:21"},
                 {"selector": "button#B0ROWONE01.css-1.r-2", "label": "Weitere Optionen fuer One"},
                 {"label": "After Artist", "href": "/artists/B0AFTER001"},
                 {"testid": "TrackItem_Title", "label": "Two", "href": "/tracks/B0ROWTWO01?do=play"},
                 {"selector": "button.css-g.r-1", "label": "Weitere Optionen fuer Two"},
                 {"selector": "button.css-g.r-1", "label": "Weitere Optionen fuer Three"}]
        rows = W.parse_songs_page(items)
        self.assertEqual([r["track_id"] for r in rows], ["B0ROWONE01", "B0ROWTWO01"])
        one, two = rows
        self.assertEqual((one["artists"], one["album_name_search"], one["duration"]), (["Artist A", "Artist B"], "Some Album", "3:21"))
        self.assertEqual((two["artists"], two["album_name_search"], two["duration"]), ([], None, None))

    def test_many_rows_are_all_read_in_page_order(self):
        many = [dict(SONG_SEARCH["Bush Machinehead - Remastered"][0], id="B0MANY%04d" % i, title="Row %d" % i) for i in range(40)]
        SONG_SEARCH["Many Hits"] = many
        self.addCleanup(SONG_SEARCH.pop, "Many Hits")
        self.write_jobs({"card": 6, "job": "song", "query": "Many Hits"})
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 0, err)
        hits = self.records()[6]["track_search"]["hits"]
        self.assertEqual([h["rank"] for h in hits], list(range(1, 41)))
        self.assertEqual([h["title"] for h in hits], ["Row %d" % i for i in range(40)])
        presses = [b for p, b in self.host.requests if p == "/act"]
        self.assertEqual(presses, [])  # no scrolling and no key presses

    def test_rows_are_read_again_until_the_count_is_stable(self):
        sizes = []
        old = self.host.summary

        def summary():
            items = old()
            sizes.append(sum(1 for i in items if i.get("testid") == "TrackItem_Title"))
            if len(sizes) == 1:  # the first poll shows two rows only, as if the page was still loading
                cut = [n for n, i in enumerate(items) if i.get("testid") == "TrackItem_Title"][2]
                return items[:cut]
            return items
        self.host.summary = summary
        self.write_jobs({"card": 6, "job": "song"})
        self.run_cli("run", self.jobs_path())
        self.assertEqual(len(self.records()[6]["track_search"]["hits"]), 4)
        self.assertGreaterEqual(len(sizes), 3)  # two rows, then four, then four again

    def test_limit_of_the_summary_grows_with_the_rows(self):
        limits = []
        old = self.host.handle

        def handle(method, path, body):
            if path == "/dom" and body.get("mode") == "summary":
                limits.append(body["limit"])
            return old(method, path, body)
        self.host.handle = handle
        many = [dict(SONG_SEARCH["Bush Machinehead - Remastered"][0], id="B0BIG%05d" % i) for i in range(200)]
        SONG_SEARCH["Big"] = many
        self.addCleanup(SONG_SEARCH.pop, "Big")
        self.write_jobs({"card": 6, "job": "song", "query": "Big"})
        self.run_cli("run", self.jobs_path())
        self.assertEqual(max(limits), 200 + 30 * 200)
        self.assertEqual(len(self.records()[6]["track_search"]["hits"]), 200)

    def test_query_with_comma_and_spaces_is_encoded(self):
        SONG_SEARCH["Pumpkins, 1979"] = [SONG_SEARCH["Smashing Pumpkins 1979"][0]]
        self.addCleanup(SONG_SEARCH.pop, "Pumpkins, 1979")
        self.write_jobs({"card": 6, "job": "song", "query": "Pumpkins, 1979"})
        self.run_cli("run", self.jobs_path())
        self.assertEqual(self.host.navigated(), ["https://music.amazon.de/search/Pumpkins%2C%201979/songs"])
        self.assertEqual(self.records()[6]["track_search"]["hits"][0]["track_id"], "B0LONGT060")

class JobFile(Base):
    def run_file(self, *entries, extra=(), **top):
        self.write_jobs(*entries, **top)
        return self.run_cli("run", self.jobs_path(), *extra)

    def add_set(self, name="otherset"):
        with open(os.path.join(self.csv_dir, name + ".csv"), "w", encoding="utf-8", newline="") as f:
            f.write('Card#,Title,Artist,Year,AmazonID,Album\r\n6,"Machinehead - Remastered",Bush,1994,,\r\n2,"Hells Bells",AC/DC,1980,,\r\n')

    def test_a_valid_file_with_all_four_job_kinds(self):
        self.releases_of_card_6("B0ALBUM001")
        self.run_file({"card": 1, "job": "song"}, {"card": 6, "job": "song"})
        rc, out, err = self.run_file(
            {"card": 6, "job": "meta", "hits": [1, "3-3"], "note": "first the originals"},
            {"card": 7, "job": "album", "song_ids": True},
            {"card": 9, "job": "track", "album": "B0ALBUM002", "row": 1},
            {"card": 1, "job": "song", "query": "Bush Machinehead - Remastered"})
        self.assertEqual(rc, 0, err)
        res = self.results()
        self.assertEqual([(r["index"], r["set"], r["card"], r["job"], r["status"]) for r in res[:-1]],
                         [(0, "testset", 6, "meta", "ok"), (1, "testset", 7, "album", "ok"), (2, "testset", 9, "track", "ok"),
                          (3, "testset", 1, "song", "ok")])  # in file order
        meta, album, track, song = res[:-1]
        self.assertEqual(meta["note"], "first the originals")
        self.assertNotIn("note", album)
        self.assertEqual(meta["metas"], [1, 3])
        self.assertEqual(meta["query"], "Bush Machinehead - Remastered")
        self.assertEqual((album["releases"], album["rows"]), (1, 0))
        self.assertEqual(track["message"], "song id stored")
        self.assertEqual(song["hits"], 4)
        self.assertEqual(res[-1], dict(res[-1], summary=True, jobs=4, ok=4, error=0, stopped=None))
        recs = self.records()
        self.assertEqual(recs[6]["track_search"]["hits"][0]["meta_title"], "Machinehead")
        self.assertEqual(recs[9]["album_search"]["releases"][0]["rows"][0]["track_id"], "B0TRACK005")
        self.assertEqual(recs[1]["track_search"]["query"], "Bush Machinehead - Remastered")

    def test_validation_errors_have_paths_and_nothing_runs(self):
        self.run_file({"card": 6, "job": "song"})
        os.unlink(os.path.join(self.tmp.name, "jobs.results.jsonl"))
        self.host.requests.clear()
        rc, _o, err = self.run_file(
            {"card": 6, "job": "song"},                                  # 0 fine
            {"card": 6, "job": "album"},                                 # 1 duplicate
            {"card": 1, "job": "dance"},                                 # 2 unknown job
            {"job": "song"},                                             # 3 card missing
            {"card": 99, "job": "song"},                                 # 4 not in the CSV
            {"card": 7, "job": "song", "color": 1},                      # 5 unknown field
            {"card": 7, "job": "song", "hits": [1]},                     # 6 field of another job
            {"card": 9, "job": "meta", "hits": [1]},                     # 7 no stored list
            {"card": 1, "job": "track", "album": "B0ALBUM001"},          # 8 row missing
            [1, 2],                                                      # 9 not an object
            {"card": 7, "job": "song", "note": 5})                       # 10 note not a text
        self.assertEqual(rc, 2)
        for i, word in ((1, "duplicate card 6 (also in cards[0])"), (2, 'unknown job "dance"'), (3, '"card" must be a number'),
                        (4, "card 99 is not in the CSV of testset"), (5, "unknown field for job song: color"),
                        (6, "unknown field for job song: hits"), (7, "has no stored song list"), (8, '"row" must be'),
                        (9, "must be a JSON object"), (10, '"note" must be a text')):
            self.assertIn("cards[%d]: " % i, err)
            self.assertRegex(err, r"cards\[%d\]: [^\n]*%s" % (i, re.escape(word)), err)
        self.assertNotIn("cards[0]:", err)
        self.assertEqual(self.host.requests, [])  # no call to the helper at all
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "jobs.results.jsonl")))

    def test_top_level_errors(self):
        for text, word in (('{"cards": [{"card": 6, "job": "song"}]}', '"set" must be the name of a card set'),
                           ('{"set": 5, "cards": [{"card": 6, "job": "song"}]}', '"set" must be the name of a card set'),
                           ('{"set": "../etc/x", "cards": [{"card": 6, "job": "song"}]}', '"set" must be the name of a card set'),
                           ('{"set": "nosuchset", "cards": [{"card": 6, "job": "song"}]}', "there is no CSV for the set nosuchset"),
                           ('{"set": "testset"}', '"cards" must be a list'),
                           ('{"set": "testset", "cards": {"card": 6}}', '"cards" must be a list'),
                           ('{"set": "testset", "cards": []}', "empty list"),
                           ('{"set": "testset", "cards": [{"card": 6, "job": "song"}], "sets": []}', "unknown field at the top level: sets"),
                           ('[1, 2]', "must be a JSON object")):
            self.write_raw(text)
            rc, _o, err = self.run_cli("run", self.jobs_path())
            self.assertEqual(rc, 2, text)
            self.assertIn(word, err)
        self.assertEqual(self.host.requests, [])

    def test_broken_json_names_line_and_column(self):
        self.write_raw('{"set": "testset",\n "cards": [ {"card": 6, "job": "song"}, ]\n}')
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 2)
        self.assertRegex(err, r"not valid JSON at line 2 column \d+")
        self.assertEqual(self.host.requests, [])

    def test_a_file_in_the_old_line_format_is_refused(self):
        self.write_raw(json.dumps({"set": "testset", "card": 6, "job": "song"}) + "\n")
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 2)

    def test_missing_file(self):
        rc, _o, err = self.run_cli("run", os.path.join(self.tmp.name, "nothing.json"))
        self.assertEqual(rc, 2)

    def test_results_file_is_created_new_each_run(self):
        self.run_file({"card": 6, "job": "song"}, {"card": 1, "job": "song"})
        first = self.results()
        self.assertEqual([r.get("card") for r in first], [6, 1, None])
        self.run_file({"card": 6, "job": "song"})
        second = self.results()
        self.assertEqual(len(second), 2)  # the old lines are gone
        self.assertTrue(second[-1]["summary"])
        for key in ("index", "set", "card", "job", "query", "status", "seconds", "errors", "message", "hits"):
            self.assertIn(key, second[0])

    def test_results_option(self):
        out = os.path.join(self.tmp.name, "elsewhere.jsonl")
        self.run_file({"card": 6, "job": "song"}, extra=("--results", out))
        with open(out) as f:
            self.assertTrue(json.loads(f.readlines()[-1])["summary"])
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "jobs.results.jsonl")))

    def test_a_job_runs_every_time_without_skipping(self):
        self.run_file({"card": 6, "job": "song"})
        n = self.host.count("/navigate")
        self.run_file({"card": 6, "job": "song"})
        self.assertEqual(self.host.count("/navigate"), 2 * n)

    def test_a_failed_job_does_not_stop_the_others(self):
        old = S.collect_song

        def flaky(cl, card, query):
            if card["card"] == 1:
                raise W.HostError("boom")
            return old(cl, card, query)
        S.collect_song = flaky
        self.addCleanup(setattr, S, "collect_song", old)
        self.run_file({"card": 1, "job": "song"}, {"card": 6, "job": "song"})
        res = self.results()
        self.assertEqual([r.get("status") for r in res], ["error", "ok", None])
        self.assertIn("boom", res[0]["message"])
        self.assertEqual(res[-1]["error"], 1)
        self.assertEqual(sorted(self.records()), [6])  # the failed job wrote nothing

    def test_lost_tab_ends_the_run_with_a_summary(self):
        old = S.collect_song

        def vanish(cl, card, query):
            if card["card"] == 6:
                del self.host.tabs[1]
            return old(cl, card, query)
        S.collect_song = vanish
        self.addCleanup(setattr, S, "collect_song", old)
        rc, _o, err = self.run_file({"card": 1, "job": "song"}, {"card": 6, "job": "song"}, {"card": 7, "job": "song"}, {"card": 9, "job": "song"})
        self.assertEqual(rc, 4)
        res = self.results()
        self.assertEqual([r.get("status") for r in res[:-1]], ["ok", "error", "skipped_tab_lost", "skipped_tab_lost"])
        self.assertEqual((res[-1]["stopped"], res[-1]["jobs"], res[-1]["ok"]), ("tab lost", 4, 1))
        self.assertEqual(sorted(self.records()), [1])  # job 1 stays

    def test_tab_check_failure_gives_a_results_file_too(self):
        rc, _o, err = self.run_cli("run", self.write_jobs({"card": 6, "job": "song", "note": "n"}), "--tab-id", "55")
        self.assertEqual(rc, 4)
        res = self.results()
        self.assertEqual([r.get("status") for r in res[:-1]], ["skipped_tab_lost"])
        self.assertEqual((res[0]["index"], res[0]["set"]), (0, "testset"))
        self.assertEqual(res[-1]["stopped"], "tab lost")

    def test_low_memory_stops_with_a_summary(self):
        self.write_jobs({"card": 6, "job": "song"}, {"card": 1, "job": "song"})
        rc, _o, err = self.run_cli("run", self.jobs_path(), meminfo=lambda: 1000)
        self.assertEqual(rc, 3)
        self.assertEqual(self.results()[-1]["stopped"], "memory")

    def test_results_file_is_required_even_for_a_dry_run(self):
        path = self.write_jobs({"card": 6, "job": "song"})
        for extra in ((), ("--dry-run",)):
            rc, _o, err = self.run_cli("run", path, *extra, results=False)
            self.assertEqual(rc, 2)
            self.assertIn("you must name the result file: --results PATH", err)
        self.assertEqual(self.host.requests, [])
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "jobs.results.jsonl")))

    def test_an_existing_result_file_is_overwritten(self):
        out = os.path.join(self.tmp.name, "r.jsonl")
        with open(out, "w") as f:
            f.write("old content\n")
        self.run_file({"card": 6, "job": "song"}, extra=("--results", out))
        with open(out) as f:
            self.assertNotIn("old content", f.read())

    def test_run_needs_file_and_tab(self):
        rc, _o, err = self.run_cli("run")
        self.assertEqual(rc, 2)
        rc, _o, err = self.run_cli("run", self.write_jobs({"card": 6, "job": "song"}), tab=None)
        self.assertEqual(rc, 2)
        self.assertIn("you must select a tab", err)
        self.assertEqual(self.host.requests, [])

    def test_one_set_per_file_each_set_has_its_own_research_file(self):
        self.add_set()
        self.run_file({"card": 6, "job": "song"}, {"card": 2, "job": "song"}, set_name="otherset")
        self.run_file({"card": 6, "job": "song"})
        other = R.load(os.path.join(self.out_dir, "otherset.research.jsonl"))
        self.assertEqual(sorted(other), [2, 6])
        self.assertEqual(sorted(self.records()), [6])
        self.assertEqual(self.results()[0]["set"], "testset")

    def test_dry_run_lists_every_card_entry(self):
        self.write_jobs({"card": 6, "job": "song", "query": "Own Text"}, {"card": 7, "job": "album", "song_ids": True})
        rc, out, _e = self.run_cli("run", self.jobs_path(), "--dry-run")
        self.assertEqual(rc, 0)
        self.assertEqual(out.splitlines(), ['index 0 set testset card 6 job song query "Own Text"',
                                            'index 1 set testset card 7 job album query "Artist A\\tSong, With Comma" song_ids=true'])
        self.assertEqual(self.host.requests, [])


class Errors(Base):
    def test_card_error_goes_on_and_max_errors(self):
        # card 1 has no fake data (empty result, written), make album 003 the only result to force an album error
        ALBUM_SEARCH[("AC/DC", "Hells Bells")] = ["B0ALBUM003"]
        self.addCleanup(ALBUM_SEARCH.pop, ("AC/DC", "Hells Bells"))
        rc, _o, err = self.run_cli("album", "--cards", "1,6")
        self.assertEqual(rc, 0, err)
        recs = self.records()
        self.assertEqual(recs[1]["album_search"]["releases"], [])
        self.assertEqual(len(recs[1]["album_search"]["errors"]), 1)
        self.assertIn("releases", recs[6]["album_search"])

    def test_failing_cards_stop_at_max_errors(self):
        calls = []

        def boom(cl, card, opts):
            calls.append(card["card"])
            raise W.HostError("boom")
        old = S.collect_song
        S.collect_song = boom
        self.addCleanup(setattr, S, "collect_song", old)
        rc, _o, err = self.run_cli("song", "--cards", "all", "--max-errors", "2")
        self.assertEqual(rc, 3)
        self.assertEqual(calls, [1, 6])
        self.assertIn("stopped: max-errors", err)
        self.assertFalse(os.path.exists(self.research))

    def test_error_card_continues_with_next(self):
        calls = []
        old = S.collect_song

        def flaky(cl, card, opts):
            calls.append(card["card"])
            if card["card"] == 1:
                raise W.HostError("boom")
            return old(cl, card, opts)
        S.collect_song = flaky
        self.addCleanup(setattr, S, "collect_song", old)
        rc, _o, err = self.run_cli("song", "--cards", "1,6")
        self.assertEqual(rc, 0)
        self.assertEqual(sorted(self.records()), [6])
        self.assertEqual([r["status"] for r in self.results() if "status" in r], ["error", "ok"])

    def test_helper_not_reachable(self):
        self.host.close()
        rc, _o, err = self.run_cli("song", "--cards", "6")
        self.assertEqual(rc, 3)

    def test_stop_request_ends_after_the_running_job(self):
        stopper = S.Stopper()
        old = S.collect_song

        def once(cl, card, query):
            stopper.requested = True  # as if Ctrl-C came during this job
            return old(cl, card, query)
        S.collect_song = once
        self.addCleanup(setattr, S, "collect_song", old)
        opts = S.build_parser().parse_args(["run", "x", "--pause", "0"])
        sets = S.Sets(self.csv_dir, self.out_dir)
        cl = W.Client(self.host.port, self.cfg_dir, 0, 0, 0, None, lambda s: None)
        cl.select_tab(1)
        rpath = os.path.join(self.tmp.name, "r.jsonl")
        results = S.Results(rpath)
        jobs = [{"index": 0, "set": "testset", "card": 6, "job": "song"}, {"index": 1, "set": "testset", "card": 1, "job": "song"}]
        with contextlib.redirect_stderr(io.StringIO()):
            code, stopped = S.run_jobs(cl, jobs, sets, results, opts, stopper, lambda s: None)
        results.close()
        self.assertEqual((code, stopped), (130, "interrupted"))
        self.assertEqual(sorted(R.load(self.research)), [6])  # job 1 was written completely, job 2 not started
        with open(rpath) as f:
            lines = [json.loads(x) for x in f]
        self.assertEqual([x.get("status") for x in lines], ["ok", None])
        self.assertEqual(lines[-1]["stopped"], "interrupted")

    def test_ctrl_c_in_a_job_is_an_error_line_and_a_summary(self):
        old = S.collect_song

        def interrupt(cl, card, query):
            raise KeyboardInterrupt
        S.collect_song = interrupt
        self.addCleanup(setattr, S, "collect_song", old)
        rc, _o, err = self.run_cli("song", "--cards", "6,1")
        self.assertEqual(rc, 130)
        res = self.results()
        self.assertEqual([x.get("status") for x in res], ["error", None])
        self.assertEqual(res[-1]["stopped"], "interrupted")


class TabHandling(Base):
    def page_calls(self):
        return [(p, b) for p, b in self.host.requests if p in ("/dom", "/act", "/navigate", "/back")]

    def hook(self, fn):
        """Run fn(cl, card) before each song collection, as if something happened to the tab."""
        old = S.collect_song

        def wrapped(cl, card, opts):
            fn(cl, card)
            return old(cl, card, opts)
        S.collect_song = wrapped
        self.addCleanup(setattr, S, "collect_song", old)

    def test_tab_id_is_required(self):
        for cmd in ("song", "album"):
            rc, _o, err = self.run_cli(cmd, "--cards", "6", tab=None)
            self.assertEqual(rc, 2)
            self.assertIn("you must select a tab: --tab-id N (list tabs with: scrape.py tabs)", err)
        rc, _o, err = self.run_cli("track", "--cards", "6", "--album", "B0ALBUM001", "--row", "1", tab=None)
        self.assertEqual(rc, 2)
        rc, _o, err = self.run_cli("song", "--cards", "6", "--dry-run", tab=None)
        self.assertEqual(rc, 2)
        self.assertEqual(self.host.requests, [])  # nothing was asked from the helper

    def test_status_needs_no_tab(self):
        rc, _o, _e = self.run_cli("status")
        self.assertEqual(rc, 0)
        self.assertEqual(self.host.requests, [])

    def test_tab_is_checked_and_pinned(self):
        self.host.tabs[7] = FakeTab(7, "https://music.amazon.de/playlists/x")
        rc, _o, err = self.run_cli("album", "--cards", "6,1", "--song-ids", "--max-releases", "1", "--tab-id", "7")
        self.assertEqual(rc, 0, err)
        self.assertIn("using tab 7, https://music.amazon.de/playlists/x\n", err)
        calls = self.page_calls()
        self.assertTrue(calls)
        self.assertTrue(all(b.get("tabId") == 7 for _p, b in calls))
        self.assertEqual(self.host.tabs[1].history, [])  # the other tab was left alone

    def test_unknown_or_foreign_tab_is_refused(self):
        self.host.tabs[8] = FakeTab(8, "https://example.org/")
        for tab in ("55", "8"):
            rc, _o, err = self.run_cli("song", "--cards", "6", "--tab-id", tab)
            self.assertEqual(rc, 4)
            self.assertIn("tab %s is not an open Amazon Music tab" % tab, err)
        self.assertEqual(self.page_calls(), [])

    def test_tabs_command(self):
        self.host.tabs[7] = FakeTab(7, "https://music.amazon.de/playlists/x", window_id=3)
        self.host.tabs[8] = FakeTab(8, "https://example.org/")
        rc, out, err = self.run_cli("tabs")
        self.assertEqual(rc, 0, err)
        lines = out.splitlines()
        self.assertEqual(lines[0], "tabId  windowId  active  url  title")
        self.assertEqual(len(lines), 3)  # the header and two Amazon tabs
        self.assertIn("7  3  no  https://music.amazon.de/playlists/x  Amazon Music", lines)
        self.assertNotIn("example.org", out)
        self.assertEqual(self.host.count("/tabs"), 1)
        self.assertEqual([p for p, _b in self.host.requests if p not in ("/status", "/tabs")], [])

    def test_tabs_command_without_tab(self):
        self.host.tabs.clear()
        rc, out, err = self.run_cli("tabs")
        self.assertEqual(rc, 4)
        self.assertIn("no Amazon Music tab open", err)
        self.assertEqual(out, "")

    def test_409_stops_at_once_and_keeps_finished_cards(self):
        def vanish(cl, card):
            if card["card"] == 7:
                del self.host.tabs[1]
        self.hook(vanish)
        rc, _o, err = self.run_cli("song", "--cards", "6,7,9")
        self.assertEqual(rc, 4)
        self.assertIn("tab 1 is gone or left Amazon Music, select a tab again with --tab-id", err)
        self.assertEqual(sorted(self.records()), [6])  # card 6 was saved, nothing after the stop

    def test_tab_leaves_amazon(self):
        self.hook(lambda cl, card: setattr(self.host.tabs[1], "url", "https://example.org/"))
        rc, _o, err = self.run_cli("song", "--cards", "6")
        self.assertEqual(rc, 4)
        self.assertIn("gone or left Amazon Music", err)

    def test_removed_options_are_refused(self):
        for opt in ("--new-window", "--new-tab", "--keep-tab"):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                S.build_parser().parse_args(["song", "--set", "x", opt])


class HelperBuild(Base):
    def setUp(self):
        super().setUp()
        self.host_file = os.path.join(self.tmp.name, "amazon_web_scanner_host.py")
        with open(self.host_file, "wb") as f:
            f.write(b"# a pretend helper file\n")
        self.build = S.repo_build(self.host_file)
        old = S.HOST_FILE
        S.HOST_FILE = self.host_file
        self.addCleanup(setattr, S, "HOST_FILE", old)

    def test_build_is_12_hex_characters_of_sha256(self):
        import hashlib
        self.assertEqual(self.build, hashlib.sha256(b"# a pretend helper file\n").hexdigest()[:12])
        self.assertRegex(self.build, r"^[0-9a-f]{12}$")

    def test_same_build_prints_the_line_only(self):
        self.host.status_extra = {"hostVersion": "1.2.0", "build": self.build}
        for cmd in (("song", "--cards", "6"), ("tabs",)):
            rc, _o, err = self.run_cli(*cmd)
            self.assertEqual(rc, 0, err)
            self.assertIn("helper 1.2.0 build %s" % self.build, err)
            self.assertNotIn("WARNING", err)

    def test_different_build_warns_and_goes_on(self):
        self.host.status_extra = {"hostVersion": "1.2.0", "build": "000000000000"}
        rc, _o, err = self.run_cli("song", "--cards", "6")
        self.assertEqual(rc, 0, err)
        self.assertIn("helper 1.2.0 build 000000000000", err)
        self.assertIn("WARNING: the running helper is not the repository version, run addon/native-messaging-app/install.sh and restart the add-on", err)
        self.assertIn(6, self.records())  # the run was done

    def test_old_helper_warns_and_goes_on(self):
        rc, _o, err = self.run_cli("album", "--cards", "6", "--max-releases", "1")
        self.assertEqual(rc, 0, err)
        self.assertIn("helper build unknown (old helper)", err)
        self.assertIn("WARNING", err)

    def test_missing_repository_file_gives_an_info_line(self):
        S.HOST_FILE = os.path.join(self.tmp.name, "does-not-exist.py")
        self.host.status_extra = {"hostVersion": "1.2.0", "build": "abcdef012345"}
        rc, _o, err = self.run_cli("tabs")
        self.assertEqual(rc, 0, err)
        self.assertIn("helper 1.2.0 build abcdef012345", err)
        self.assertIn("info: the helper file of the repository was not found", err)
        self.assertNotIn("WARNING", err)

    def test_status_command_does_not_ask_the_helper(self):
        rc, _o, err = self.run_cli("status")
        self.assertNotIn("helper", err)
        self.assertEqual(self.host.requests, [])

    def test_repository_path_points_to_the_real_file_name(self):
        S.HOST_FILE = os.path.join(HERE, "..", "addon", "native-messaging-app", "amazon_web_scanner_host.py")
        b = S.repo_build()
        self.assertTrue(b is None or len(b) == 12)


class SyncCards(Base):
    def seed(self):
        """A research file whose lines carry old CSV data and some extra fields."""
        old = {1: {"card": 1, "title": "Hells Bells - Remastered", "artist": "AC/DC", "card_year": 1981,
                   "track_search": {"query": "q", "hits": [{"rank": 1}]}, "note": "keep"},
               6: {"card": 6, "title": "Machinehead - Remastered", "artist": "Bush", "card_year": 1994,
                   "album_search": {"url": "u", "releases": []}},
               99: {"card": 99, "title": "Not in the CSV", "artist": "X", "card_year": 2000}}
        R.save(self.research, old)
        return old

    def test_sync_updates_lines_and_keeps_the_rest(self):
        self.seed()
        rc, out, err = self.run_cli("sync-cards")
        self.assertEqual(rc, 0, err)
        self.assertIn("1: Hells Bells - Remastered -> Hells Bells", out)
        self.assertNotIn("6:", out)  # the title of card 6 is the same as in the CSV
        self.assertIn("1 cards updated", out)
        recs = self.records()
        self.assertEqual((recs[1]["title"], recs[1]["artist"], recs[1]["card_year"]), ("Hells Bells", "AC/DC", 1980))
        self.assertEqual(recs[1]["track_search"], {"query": "q", "hits": [{"rank": 1}]})
        self.assertEqual(recs[1]["note"], "keep")
        self.assertEqual(recs[6]["title"], "Machinehead - Remastered")
        self.assertEqual(recs[99]["title"], "Not in the CSV")  # not in the CSV, left alone
        self.assertEqual(sorted(recs), [1, 6, 99])  # no line was created for cards 7 and 9
        self.assertEqual(list(recs[1])[:5], ["card", "title", "artist", "card_year", "track_search"])

    def test_sync_dry_run_changes_nothing(self):
        self.seed()
        with open(self.research, "rb") as f:
            before = f.read()
        rc, out, err = self.run_cli("sync-cards", "--dry-run")
        self.assertEqual(rc, 0, err)
        self.assertIn("1: Hells Bells - Remastered -> Hells Bells", out)
        self.assertIn("dry run", out)
        with open(self.research, "rb") as f:
            self.assertEqual(f.read(), before)

    def test_sync_needs_no_helper_and_no_file(self):
        self.host.close()  # the helper is not reachable and the command still works
        rc, out, err = self.run_cli("sync-cards")
        self.assertEqual(rc, 0, err)
        self.assertIn("0 cards updated", out)
        self.assertFalse(os.path.exists(self.research))
        self.assertEqual(self.host.requests, [])

    def test_stage_merge_takes_the_csv_fields(self):
        self.seed()
        rc, _o, err = self.run_cli("song", "--cards", "1", "--force")
        self.assertEqual(rc, 0, err)
        rec = self.records()[1]
        self.assertEqual((rec["title"], rec["card_year"]), ("Hells Bells", 1980))
        self.assertEqual(rec["note"], "keep")
        self.assertEqual(rec["track_search"]["query"], "AC/DC Hells Bells")


class LongAlbums(Base):
    """Nothing is ever scrolled. A long album shows what the page has, and rows_read next to tracks says how much that is."""

    def add_card(self, num, title, artist):
        with open(os.path.join(self.csv_dir, "testset.csv"), "a", encoding="utf-8", newline="") as f:
            f.write('%d,"%s","%s",1995,,\r\n' % (num, title, artist))

    def acts(self):
        return [b for p, b in self.host.requests if p == "/act"]

    def test_summary_limit_grows_with_the_track_count_without_a_cap(self):
        self.assertEqual(W.summary_limit(12), 560)
        self.assertEqual(W.summary_limit(92), 2960)
        self.assertEqual(W.summary_limit(1000), 30200)
        self.assertEqual(W.summary_limit(None), 1500)

    def test_a_row_that_is_not_in_the_page_is_no_error_in_an_album_job(self):
        self.add_card(13, "1979", "Smashing Pumpkins")
        rc, _o, err = self.run_cli("album", "--cards", "13")
        self.assertEqual(rc, 0, err)
        st = self.records()[13]["album_search"]
        for rel in st["releases"]:
            self.assertEqual((rel["tracks"], rel["rows_read"], rel["rows"]), (92, 40, []))  # the row "1979" (index 60) is not in the page
            self.assertNotIn("scrolled", rel)
        self.assertNotIn("errors", st)
        self.assertEqual(self.results()[0]["status"], "ok")
        self.assertEqual(self.acts(), [])  # no scroll and no key press

    def test_a_row_in_the_page_is_found_without_anything_else(self):
        self.add_card(15, "Track 5", "Smashing Pumpkins")
        ALBUM_SEARCH[("Smashing Pumpkins", "Track 5")] = ["B0LONG0001"]
        self.addCleanup(ALBUM_SEARCH.pop, ("Smashing Pumpkins", "Track 5"))
        rc, _o, err = self.run_cli("album", "--cards", "15", "--song-ids")
        self.assertEqual(rc, 0, err)
        rel = self.records()[15]["album_search"]["releases"][0]
        self.assertEqual((rel["tracks"], rel["rows_read"]), (92, 40))
        self.assertEqual([(x["title"], x["track_id"]) for x in rel["rows"]], [("Track 5", "B0LONGT005")])
        self.assertTrue(all(a["action"] == "click" for a in self.acts()))

    def test_every_normal_release_has_rows_read(self):
        self.run_cli("album", "--cards", "6")
        rels = self.records()[6]["album_search"]["releases"]
        self.assertEqual([(r["tracks"], r["rows_read"]) for r in rels], [(12, 12), (21, 21)])

    def test_header_count_that_differs_from_the_rows_is_no_error(self):
        self.add_card(14, "Dev Song 7", "Dev Artist")
        rc, _o, err = self.run_cli("album", "--cards", "14")
        self.assertEqual(rc, 0, err)
        st = self.records()[14]["album_search"]
        self.assertNotIn("errors", st)
        rel = st["releases"][0]
        self.assertEqual((rel["tracks"], rel["rows_read"]), (93, 92))
        self.assertTrue(any(x["title"] == "Dev Song 7" for x in rel["rows"]))

    def test_track_job_reads_a_row_that_is_in_the_page(self):
        self.write_jobs({"card": 6, "job": "track", "album": "B0LONG0001", "row": 10})
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.records()[6]["album_search"]["releases"][0]["rows"][0]["track_id"], "B0LONGT010")
        self.assertTrue(all(a["action"] == "click" for a in self.acts()))

    def test_track_job_with_a_row_that_is_not_in_the_page_is_an_error(self):
        self.write_jobs({"card": 6, "job": "track", "album": "B0LONG0001", "row": 60})
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 0, err)
        res = self.results()[0]
        self.assertEqual(res["status"], "error")
        self.assertIn("row not on the page (read 40 of 92 rows), use a more exact song job with the album name in the query", res["message"])
        self.assertFalse(os.path.exists(self.research))  # nothing was written
        self.assertEqual(self.acts(), [])  # no click, no scroll

    def test_the_scraper_has_no_scroll_code_left(self):
        for module in (S, W, R):
            for name in dir(module):
                self.assertNotIn("scroll", name.lower(), name)
        with open(os.path.join(HERE, "scrape.py"), encoding="utf-8") as f1, open(os.path.join(HERE, "webclient.py"), encoding="utf-8") as f2:
            for text in (f1.read(), f2.read()):
                self.assertNotIn('"scroll"', text)

    def test_song_and_meta_send_no_page_command_at_all(self):
        self.run_cli("song", "--cards", "6")
        self.write_jobs({"card": 6, "job": "meta", "hits": [1, 2, 3, 4]})
        self.run_cli("run", self.jobs_path())
        self.assertEqual(self.acts(), [])


class MetaJob(Base):
    def setUp(self):
        super().setUp()
        self.write_jobs({"card": 6, "job": "song"})
        self.run_cli("run", self.jobs_path())
        self.host.requests.clear()

    def meta(self, **spec):
        self.write_jobs(dict({"card": 6, "job": "meta"}, **spec))
        return self.run_cli("run", self.jobs_path())

    def hits(self):
        return self.records()[6]["track_search"]["hits"]

    def test_title_and_text_parsers(self):
        title = "1979 (Remastered 2012) Song von The Smashing Pumpkins aus dem Album Mellon Collie And The Infinite Sadness (Deluxe Edition) [Explicit] auf Amazon Music"
        self.assertEqual(W.parse_track_title(title), ("1979 (Remastered 2012)", "The Smashing Pumpkins",
                                                      "Mellon Collie And The Infinite Sadness (Deluxe Edition) [Explicit]"))
        self.assertEqual(W.parse_track_title("Single Song von Someone auf Amazon Music"), ("Single", "Someone", None))
        self.assertIsNone(W.parse_track_title("Amazon Music"))
        self.assertEqual(W.parse_track_text("Song | 1979 | Pumpkins | 4 Min. 26 Sek. | • | 23. Okt. 1995"), ("04:26", "23. Okt. 1995", 1995))
        for text, year in (("1. Jan. 2000", 2000), ("2. Feb. 2001", 2001), ("3. März 2002", 2002), ("3. Maerz 2002", 2002),
                           ("3. Mrz 2002", 2002), ("4. Apr. 2003", 2003), ("5. Mai 2004", 2004), ("6. Juni 2005", 2005),
                           ("7. Juli 2006", 2006), ("8. Aug. 2007", 2007), ("12. Sept. 2008", 2008), ("12. Sep. 2008", 2008),
                           ("9. Okt. 2009", 2009), ("10. Nov. 2010", 2010), ("15. Dez. 1980", 1980)):
            dur, date, y = W.parse_track_text("Song | A | B | 1 Min. 5 Sek. | • | " + text)
            self.assertEqual((dur, date, y), ("01:05", text, year), text)
        self.assertEqual(W.parse_track_text("Song | A | B | 3 Min. 0 Sek. | • | 5. Foo 1999"), ("03:00", None, None))
        self.assertEqual(W.parse_track_text("nothing"), (None, None, None))

    def test_meta_by_rank_loads_only_the_track_pages(self):
        rc, _o, err = self.meta(hits=[1, 3])
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.host.navigated(), ["https://music.amazon.de/tracks/B0TRACK002", "https://music.amazon.de/tracks/B0TRACK003"])
        self.assertEqual([b for p, b in self.host.requests if p == "/act"], [])  # no menu, no click, no scroll
        h = self.hits()
        self.assertEqual((h[0]["album_name"], h[0]["album_release_date"], h[0]["album_year"], h[0]["duration"], h[0]["meta_title"]),
                         ("Sixteen Stone", "01. Nov. 1994", 1994, "04:16", "Machinehead"))
        self.assertEqual((h[2]["album_name"], h[2]["album_release_date"], h[2]["album_year"], h[2]["duration"], h[2]["meta_title"]),
                         ("Live", "5. Mai 2001", 2001, "04:40", "Machinehead (Live)"))
        for hit in (h[0], h[2]):
            self.assertIsNone(hit["album_id"])
            self.assertIsNone(hit["album_tracks"])
        self.assertIs(h[0]["explicit"], False)  # no E next to the title
        self.assertIs(h[2]["explicit"], True)  # the E of an explicit song
        self.assertNotIn("explicit", h[1])
        self.assertNotIn("meta_title", h[1])  # a hit that was not named is untouched
        self.assertIsNone(h[1]["album_year"])
        self.assertNotIn("errors", self.records()[6]["track_search"])
        res = self.results()[0]
        self.assertEqual((res["status"], res["metas"], res["message"]), ("ok", [1, 3], "2 of 2 read"))

    def test_parse_explicit_reads_the_e_next_to_the_title(self):
        e = W.parse_explicit
        self.assertTrue(e("Startseite\nSong\nI Love It (Explicit)\nE\nIcona Pop\n[feat.\nCharli xcx\n]\n2 Min. 37 Sek."))
        self.assertTrue(e("Song | Machinehead | E | Bush | 4 Min. 40 Sek."))
        self.assertFalse(e("Startseite\nSong\nRehab\nAmy Winehouse\n3 Min. 34 Sek.\n•\n07. Nov. 2006"))
        self.assertFalse(e("Song\nAlbum [Explicit]\nArtist\n3 Min."))  # the word in a name is no E
        self.assertFalse(e("Song\nTitle\nArtist\n3 Min. 3 Sek." + "\n" * 500 + "E\nlater in the page"))  # only the head is looked at
        self.assertFalse(e("Song\nBEE\nArtist"))
        self.assertIsNone(e(""))
        self.assertIsNone(e(None))

    def test_range_and_tracks(self):
        rc, _o, err = self.meta(hits=["2-3"])
        self.assertEqual(rc, 0, err)
        self.assertEqual([x.get("meta_title") for x in self.hits()], [None, "Machinehead (Remastered 2012)", "Machinehead (Live)", None])
        self.assertEqual(self.hits()[1]["album_name"], "Loaded: The Greatest Hits [Explicit]")
        rc, _o, err = self.meta(tracks=["B0TRACK002"])
        self.assertEqual(self.hits()[0]["meta_title"], "Machinehead")

    def test_a_known_duration_is_kept(self):
        recs = self.records()
        recs[6]["track_search"]["hits"][0]["duration"] = "03:00"
        R.save(self.research, recs)
        self.meta(hits=[1])
        self.assertEqual(self.hits()[0]["duration"], "03:00")
        self.assertEqual(self.hits()[0]["album_year"], 1994)

    def test_a_page_without_a_date_keeps_the_rest_and_is_no_error(self):
        rc, _o, err = self.meta(hits=[1, 4])
        self.assertEqual(rc, 0, err)
        h = self.hits()
        self.assertEqual(h[0]["meta_title"], "Machinehead")
        self.assertEqual((h[3]["album_name"], h[3]["duration"], h[3]["meta_title"]), ("Baby Hits", "02:00", "Machinehead"))
        self.assertIsNone(h[3]["album_release_date"])  # not read, so not touched
        self.assertIsNone(h[3]["album_year"])
        self.assertNotIn("errors", self.records()[6]["track_search"])
        res = self.results()[0]
        self.assertEqual((res["status"], res["message"], res["errors"]), ("ok", "2 of 2 read, 1 incomplete", []))
        self.assertEqual(res["incomplete"], [{"track_id": "B0TRACK007", "missing": ["date"]}])

    def test_a_complete_run_has_an_empty_incomplete_list(self):
        self.meta(hits=[1])
        res = self.results()[0]
        self.assertEqual((res["incomplete"], res["message"]), ([], "1 of 1 read"))

    def meta_cases(self, *ranks):
        self.write_jobs({"card": 1, "job": "song", "query": "Meta Cases"})
        self.run_cli("run", self.jobs_path())
        self.write_jobs({"card": 1, "job": "meta", "hits": list(ranks)})
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 0, err)
        return self.records()[1]["track_search"], self.results()[0]

    def test_the_real_pages_without_a_date_store_album_title_and_duration(self):
        ts, res = self.meta_cases(1, 2)
        a, b = ts["hits"][:2]
        self.assertEqual((a["album_name"], a["meta_title"], a["duration"]),
                         ("Zen X Four", "Machinehead (Live From KROQ's Almost Acoustic Christmas, Los Angeles/1995)", "04:47"))
        self.assertEqual((b["album_name"], b["meta_title"], b["duration"]), ("The Piano Men", "Aint That a Shame", "02:25"))
        for hit in (a, b):
            self.assertIsNone(hit["album_release_date"])
            self.assertIsNone(hit["album_year"])
        self.assertNotIn("errors", ts)
        self.assertEqual(res["incomplete"], [{"track_id": "B0METANOD1", "missing": ["date"]}, {"track_id": "B0METANOD2", "missing": ["date"]}])
        self.assertEqual(res["message"], "2 of 2 read, 2 incomplete")

    def test_a_page_without_a_duration_stores_the_date(self):
        ts, res = self.meta_cases(3)
        h = ts["hits"][2]
        self.assertEqual((h["album_name"], h["album_release_date"], h["album_year"], h["duration"]), ("Some Album", "3. März 2002", 2002, None))
        self.assertEqual(res["incomplete"], [{"track_id": "B0METANODU", "missing": ["duration"]}])

    def test_a_title_without_the_album_part_keeps_the_album_name(self):
        ts, res = self.meta_cases(4)
        recs = self.records()
        recs[1]["track_search"]["hits"][3]["album_name"] = "Known Album"
        R.save(self.research, recs)
        self.write_jobs({"card": 1, "job": "meta", "hits": [4]})
        self.run_cli("run", self.jobs_path())
        h = self.records()[1]["track_search"]["hits"][3]
        self.assertEqual((h["album_name"], h["meta_title"], h["album_release_date"], h["album_year"], h["duration"]),
                         ("Known Album", "Song Without Album", "12. Sept. 1999", 1999, "03:05"))
        self.assertEqual(self.results()[0]["incomplete"], [{"track_id": "B0METANAL1", "missing": ["album"]}])

    def test_a_page_with_nothing_to_read_is_an_error_and_changes_nothing(self):
        ts, res = self.meta_cases(5, 6)
        for h in ts["hits"][4:6]:
            self.assertNotIn("meta_title", h)
            self.assertIsNone(h["album_year"])
            self.assertIsNone(h["duration"])
        self.assertEqual(ts["errors"], [{"track_id": "B0METAONLY", "error": "meta page not readable"},
                                        {"track_id": "B0METANONE", "error": "meta page not readable"}])
        self.assertEqual(res["message"], "0 of 2 read")
        self.assertEqual(res["incomplete"], [])

    def test_duration_with_only_minutes_or_only_seconds(self):
        ts, res = self.meta_cases(7, 8)
        self.assertEqual((ts["hits"][6]["duration"], ts["hits"][6]["album_name"]), ("00:47", "Odd"))
        self.assertEqual((ts["hits"][7]["duration"], ts["hits"][7]["album_release_date"]), ("03:00", "30. Dez. 1990"))
        self.assertEqual(W.parse_track_text("x | 3 Min. | y"), ("03:00", None, None))
        self.assertEqual(W.parse_track_text("x | 47 Sek. | y"), ("00:47", None, None))
        self.assertEqual(W.parse_track_text("x | 2 Min. 5 Sek. | 1. Mrz 2001"), ("02:05", "1. Mrz 2001", 2001))
        self.assertEqual(W.parse_track_text("1. Mai 2001 only a date"), (None, "1. Mai 2001", 2001))

    def test_a_field_that_cannot_be_read_never_overwrites_a_value(self):
        recs = self.records()
        h = recs[6]["track_search"]["hits"][3]
        h.update({"album_release_date": "1. Jan. 2000", "album_year": 2000})
        R.save(self.research, recs)
        self.meta(hits=[4])  # the page of this hit has no date
        h = self.hits()[3]
        self.assertEqual((h["album_release_date"], h["album_year"]), ("1. Jan. 2000", 2000))

    def test_the_page_must_belong_to_the_id(self):
        old = self.host.handle

        def handle(method, path, body):
            if path == "/navigate" and body["url"].endswith("B0TRACK002"):
                body = dict(body, url="https://music.amazon.de/tracks/B0TRACK005")  # the tab lands on another song
            return old(method, path, body)
        self.host.handle = handle
        self.meta(hits=[1])
        self.assertNotIn("meta_title", self.hits()[0])
        err = self.records()[6]["track_search"]["errors"][0]
        self.assertEqual(err["track_id"], "B0TRACK002")
        self.assertIn("meta page is not the track page of B0TRACK002", err["error"])

    def test_pre_check_names_hits_of_the_stored_list(self):
        for spec in ({"hits": [9]}, {"tracks": ["B0NOTHERE1"]}, {"hits": [1], "tracks": ["B0TRACK002"]}, {}):
            rc, _o, err = self.meta(**spec)
            self.assertEqual(rc, 2, spec)
            self.assertIn("nothing was run", err)
        self.write_jobs({"card": 7, "job": "meta", "hits": [1]})
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 2)
        self.assertIn("run a song job first", err)
        self.assertEqual(self.host.requests, [])

    def test_meta_does_not_take_song_ids(self):
        rc, _o, err = self.meta(hits=[1], song_ids=True)
        self.assertEqual(rc, 2)
        self.assertIn("unknown field for job meta: song_ids", err)

    def test_the_job_look_is_gone(self):
        self.write_jobs({"card": 6, "job": "look", "hits": [1]})
        rc, _o, err = self.run_cli("run", self.jobs_path())
        self.assertEqual(rc, 2)
        self.assertIn('cards[0]: unknown job "look"', err)
        self.assertEqual(self.host.requests, [])


class MemoryGuard(Base):
    def test_waits_until_memory_is_free(self):
        values = iter([100_000, 200_000, 3_000_000])
        slept = []
        g = W.MemGuard(1500, reader=lambda: next(values), sleep=slept.append, poll=5)
        self.assertEqual(g.check(), 3_000_000)
        self.assertEqual(slept, [5, 5])

    def test_gives_up(self):
        g = W.MemGuard(1500, reader=lambda: 1000, sleep=lambda s: None, poll=10, max_wait=30)
        with self.assertRaises(W.FatalError):
            g.check()

    def test_unreadable_meminfo_does_not_block(self):
        self.assertIsNone(W.MemGuard(1500, reader=lambda: None).check())

    def test_guard_used_before_page_loads(self):
        levels = iter([100_000, 100_000, 5_000_000] + [5_000_000] * 1000)
        rc, _o, err = self.run_cli("song", "--cards", "9", meminfo=lambda: next(levels))
        self.assertEqual(rc, 0)
        self.assertEqual(err.count("is below 1500 MB, waiting"), 2)

    def test_low_memory_stops_the_run(self):
        rc, _o, err = self.run_cli("song", "--cards", "6", meminfo=lambda: 1000)
        self.assertEqual(rc, 3)
        self.assertIn("stayed below", err)
        self.assertEqual(self.host.count("/navigate"), 0)

    def test_real_reader_returns_number_or_none(self):
        v = W.read_meminfo_kb()
        self.assertTrue(v is None or v > 0)


class Status(Base):
    def test_status(self):
        self.run_cli("song", "--cards", "6,1")  # card 1 has no result widget in the fake, so it counts as not done
        self.run_cli("album", "--cards", "6")
        rc, out, _e = self.run_cli("status")
        self.assertEqual(rc, 0)
        self.assertIn("4 cards in the CSV", out)
        self.assertIn("track_search: 1 cards, 4 hits", out)
        self.assertIn("album_search: 1 cards", out)
        self.assertIn("missing track_search: 1,7,9", out)
        self.assertIn("missing album_search: 1,7,9", out)
        self.assertEqual(R._compact([1, 2, 3, 7, 9, 10]), "1-3,7,9-10")


if __name__ == "__main__":
    unittest.main()
