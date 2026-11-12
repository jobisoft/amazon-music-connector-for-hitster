"""HTTP client for the local helper of the Amazon Web Scanner and the parsing of Amazon Music pages.

Only the standard library is used. The only network access is the loopback helper (127.0.0.1).
The admin token is read from the config file, kept in memory and never printed or logged.
"""
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_PORT = 8766
DEFAULT_CONFIG = "~/.config/amazon-web-scanner/config.json"
SITE = "https://music.amazon.de"
ASIN = r"B0[0-9A-Z]{8}"


class HostError(Exception):
    """One request failed (the page, the tab or the add-on). The run can go on with the next card.

    status, code and data hold the HTTP status, the "code" field and the JSON body of the answer, if there was one.
    """
    status = None
    code = None
    data = None


class FatalError(Exception):
    """The run cannot go on (helper gone, no tab, memory stays low). Stop the whole run."""


class MemoryLowError(FatalError):
    """The free memory stayed too low for too long."""


class TabGoneError(FatalError):
    """The pinned tab disappeared during the run. The scraper never switches to another tab."""


TAB_PATHS = ("/dom", "/act", "/navigate", "/back")


def load_token(config_path):
    """Read the admin token. The path may be the config file or its folder."""
    path = os.path.expanduser(config_path)
    if os.path.isdir(path):
        path = os.path.join(path, "config.json")
    try:
        with open(path, encoding="utf-8") as f:
            token = json.load(f)["adminToken"]
    except (OSError, ValueError, KeyError, TypeError):
        raise FatalError("cannot read adminToken from the config file " + path)
    if not isinstance(token, str) or not token:
        raise FatalError("adminToken in the config file is empty")
    return token


# ---------------------------------------------------------------- memory guard
def read_meminfo_kb():
    """MemAvailable of /proc/meminfo in kB, or None if it cannot be read."""
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1])
    except (OSError, ValueError, IndexError):
        pass
    return None


class MemGuard:
    """Waits while the free memory is low. Gives up after max_wait seconds (no endless loop)."""

    def __init__(self, min_mb=1500, reader=read_meminfo_kb, sleep=time.sleep, poll=15.0, max_wait=1800.0, log=None):
        self.min_kb = int(min_mb * 1024)
        self.reader = reader
        self.sleep = sleep
        self.poll = poll
        self.max_wait = max_wait
        self.log = log or (lambda msg: None)

    def check(self):
        waited = 0.0
        while True:
            avail = self.reader()
            if avail is None or avail >= self.min_kb:
                return avail
            if waited >= self.max_wait:
                raise MemoryLowError("MemAvailable %d MB stayed below %d MB for %d s" % (avail // 1024, self.min_kb // 1024, waited))
            self.log("MemAvailable %d MB is below %d MB, waiting" % (avail // 1024, self.min_kb // 1024))
            self.sleep(self.poll)
            waited += self.poll


# ---------------------------------------------------------------- client
def _invalid_tab(e):
    return e.status == 409 and e.code == "INVALID_TAB"


class Client:
    def __init__(self, port=DEFAULT_PORT, config=DEFAULT_CONFIG, home_every=60, page_pause=2.0, poll=1.2,
                 guard=None, sleep=time.sleep, log=None):
        self.base = "http://127.0.0.1:%d" % port
        self._token = load_token(config)
        self.home_every = home_every
        self.page_pause = page_pause
        self.poll = poll
        self.guard = guard
        self.sleep = sleep
        self.log = log or (lambda msg: None)
        self.pages = 0
        self.tab_id = None  # the tab chosen with --tab-id, sent with every request that works on a page
        self.back_ok = True  # set to False for the rest of the run when /back fails

    def gone(self):
        return TabGoneError("tab %s is gone or left Amazon Music, select a tab again with --tab-id" % self.tab_id)

    def call(self, path, body=None, timeout=45):
        if self.tab_id is not None and path in TAB_PATHS and isinstance(body, dict):
            body = dict(body)
            body.setdefault("tabId", self.tab_id)
            try:
                return self._raw(path, body, timeout)
            except HostError as e:
                if _invalid_tab(e):
                    raise self.gone()
                self._check_pinned()
                raise
        return self._raw(path, body, timeout)

    def _check_pinned(self):
        """After a failed request: raise TabGoneError if the pinned tab is not in the tab list any more."""
        try:
            ids = [t.get("tabId") for t in self._raw("/tabs", None, 30).get("tabs") or []]
        except HostError:
            return
        if self.tab_id not in ids:
            raise self.gone()

    def _raw(self, path, body=None, timeout=45):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, headers={"Authorization": "Bearer " + self._token})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            try:
                data = json.loads(e.read().decode("utf-8", "replace"))
                if not isinstance(data, dict):
                    data = {}
            except ValueError:
                data = {}
            e.close()
            msg = data.get("error") or ""
            if e.code in (401, 403):
                raise FatalError("helper refused the request (HTTP %d) %s" % (e.code, msg))
            if "no amazon music tab" in msg.lower() and not data.get("code"):
                raise FatalError(msg)
            err = HostError("HTTP %d %s" % (e.code, msg))
            err.status, err.code, err.data = e.code, data.get("code"), data
            raise err
        except (urllib.error.URLError, OSError) as e:
            raise FatalError("helper not reachable on %s (%s)" % (self.base, getattr(e, "reason", e)))
        except ValueError as e:
            raise HostError("bad JSON from helper: %s" % e)

    def alive(self):
        st = self.call("/status")
        if not st.get("ok"):
            raise FatalError("helper not ok")
        return st

    @staticmethod
    def _amazon(tabs):
        return [t for t in tabs if "music.amazon" in (t.get("url") or "")]

    def amazon_tabs(self):
        return self._amazon(self._raw("/tabs", None, 40).get("tabs") or [])

    def select_tab(self, tab_id):
        """Use exactly this tab for the whole run. It must be an open Amazon Music tab."""
        t = next((x for x in self.amazon_tabs() if x.get("tabId") == tab_id), None)
        if t is None:
            raise TabGoneError("tab %d is not an open Amazon Music tab" % tab_id)
        self.tab_id = tab_id
        self.log("using tab %s, %s" % (tab_id, t.get("url") or ""))

    def nav(self, url, timeout=45):
        """Load a page. A memory check comes first, every home_every pages the start page is loaded in between."""
        if self.guard:
            self.guard.check()
        self.sleep(self.page_pause)
        self.call("/navigate", {"url": url}, timeout)
        self.pages += 1
        if self.home_every and self.pages % self.home_every == 0:
            self.call("/navigate", {"url": SITE + "/"}, timeout)
            self.sleep(5)
            self.call("/navigate", {"url": url}, timeout)

    def go_back(self):
        """One step back in the history of the tab with /back. Returns True if it worked.

        If the helper has no /back or it fails, this is remembered and False is returned,
        so the caller loads the page again by its URL. That is not an error of the card.
        """
        if not self.back_ok:
            return False
        try:
            r = self.call("/back", {}, 30)
            if r.get("ok"):
                return True
        except HostError as e:
            self.log("/back does not work (%s), pages are loaded again from now on" % e)
        self.back_ok = False
        return False

    def dom_summary(self, limit=1500):
        return self.call("/dom", {"mode": "summary", "limit": limit}, 45).get("data") or []

    def text(self, selector, n=300):
        try:
            return (self.call("/dom", {"selector": selector, "mode": "text", "maxBytes": n}, 30).get("data") or "").strip()
        except HostError:
            return ""

    def tab(self):
        tabs = self.call("/tabs").get("tabs") or []
        if self.tab_id is None:
            raise FatalError("no tab selected")
        t = next((x for x in tabs if x.get("tabId") == self.tab_id), None)
        if t is None:
            raise self.gone()
        return t.get("url") or "", t.get("title") or ""

    def wait_items(self, ready, limit=1500, tries=12):
        """Poll the summary until ready(items) is true or the tries are used up. Returns (items, ok)."""
        items = []
        for _ in range(tries):
            self.sleep(self.poll)
            items = self.dom_summary(limit)
            if ready(items):
                return items, True
        return items, False


# ---------------------------------------------------------------- text helpers
def mmss(seconds):
    return "%02d:%02d" % (seconds // 60, seconds % 60) if seconds is not None else None


def norm_words(s):
    import unicodedata
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower().replace("'", "")
    return re.sub(r"[^a-z0-9 ]+", " ", s).split()


def core(title):
    """Title without brackets and without a trailing ' - something', as plain words."""
    t = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", title or "")
    t = re.split(r"\s+-\s+", t)[0]
    return " ".join(norm_words(t))


def first_artist(artist):
    """First artist of a card. A bracket like '(feat. X)' is dropped first."""
    artist = re.sub(r"\s*[\[(][^\])]*[\])]", "", artist or "")
    return re.split(r"\s*(?:&|,| feat\.| ft\.| featuring | and | x | vs\.? | \+ )\s*", artist or "")[0].strip()


def rows_resemble(row_title, card_core):
    rc = core(row_title)
    return bool(card_core and rc and (card_core in rc or rc in card_core))


def year_of(text):
    m = re.search(r"(\d{4})", text or "")
    return int(m.group(1)) if m else None


def songs_page_url(query):
    """The full result page of the songs, behind 'Mehr anzeigen' of the short list."""
    return SITE + "/search/" + urllib.parse.quote(query.replace("/", " "), safe="") + "/songs"


def album_search_url(artist, title):
    return SITE + "/search/" + urllib.parse.quote(artist, safe="") + "%09" + urllib.parse.quote(title, safe="") + "?type=album"


def plain_album_search_url(title):
    return SITE + "/search/" + urllib.parse.quote(title, safe="") + "?type=album"


# ---------------------------------------------------------------- page parsing
TIME = re.compile(r"\d{1,2}:\d{2}(:\d{2})?")


def parse_songs_page(items):
    """The rows of the full songs page. Every row starts with a TrackItem_Title link (/tracks/<ID>?do=play).

    The elements after the title belong to that row up to the menu button 'Weitere Optionen fuer <title>',
    which ends the row. Elements after that button (the mini player at the end of the page) belong to no row.
    Artists are the /artists/ links, an album name is taken only from a /albums/ link inside the row,
    a duration only from a time text inside the row. In the sample pages a row has neither, so both are null.
    """
    rows, cur = [], None
    for i in items:
        t, lab, h = i.get("testid") or "", i.get("label") or "", i.get("href") or ""
        m = re.match(r"/tracks/(%s)" % ASIN, h)
        if t == "TrackItem_Title" and m:
            cur = {"track_id": m.group(1), "title": lab, "artists": [], "album_name_search": None, "duration": None}
            rows.append(cur)
        elif cur is not None and lab.startswith("Weitere Optionen"):
            cur = None  # the menu button ends the row
        elif cur is not None and h.startswith("/artists/") and lab and not lab.startswith("Navigiere") and lab not in cur["artists"]:
            cur["artists"].append(lab)
        elif cur is not None and h.startswith("/albums/") and lab and cur["album_name_search"] is None:
            cur["album_name_search"] = lab
        elif cur is not None and cur["duration"] is None and not h and TIME.fullmatch((lab or i.get("text") or "").strip()):
            cur["duration"] = (lab or i.get("text")).strip()
    return rows


def songs_page_ready(items):
    return any(i.get("testid") == "TrackItem_Title" for i in items)


def wait_stable_songs(cl, ref=None, tries=12, limit=1500):
    """Poll the songs page until the rows are stable. Returns (items, stable).

    Stable means two polls in a row give the same song ids in the same order (and the reference order of the
    first load of the run, if there is one). The summary limit grows with the number of rows seen (200 plus 30
    per row, at least 1500) so that the limit never cuts what is in the page. The page is not scrolled.
    """
    prev, items = None, []
    for _ in range(tries):
        cl.sleep(cl.poll)
        items = cl.dom_summary(limit)
        ids = tuple(r["track_id"] for r in parse_songs_page(items))
        limit = max(limit, 200 + 30 * len(ids))
        if ids and ids == prev and (ref is None or ids == ref):
            return items, True
        prev = ids
    return items, False


def parse_album_search(items):
    """The albums of an album search result: id, name_search, artist_search, rank. Duplicates removed."""
    albums, cur = [], None
    for i in items:
        h, lab, tid = i.get("href") or "", i.get("label") or "", i.get("testid") or ""
        m = re.match(r"/albums/(%s)" % ASIN, h)
        if m and tid == "ItemMetadata_PrimaryText":
            cur = {"id": m.group(1), "name_search": lab, "artist_search": None, "rank": len(albums) + 1}
            albums.append(cur)
        elif h.startswith("/artists/") and cur is not None and cur["artist_search"] is None and lab and not lab.startswith("Navigiere"):
            cur["artist_search"] = lab
    seen, uniq = set(), []
    for a in albums:
        if a["id"] not in seen:
            seen.add(a["id"])
            uniq.append(a)
    return uniq


def album_search_ready(items):
    return any(i.get("testid") == "ItemMetadata_PrimaryText" and (i.get("href") or "").startswith("/albums/") for i in items)


def parse_album_rows(items):
    rows, cur = [], None
    for i in items:
        t, lab = i.get("testid") or "", i.get("label") or ""
        if t == "ListItem,album-descriptive-row-item":
            cur = {"index": len(rows), "title": lab, "duration": None}
            rows.append(cur)
        elif cur is not None and cur["duration"] is None and re.fullmatch(r"\d{1,2}:\d{2}(:\d{2})?", lab.strip()):
            cur["duration"] = lab.strip()
    return rows


def summary_limit(tracks):
    """How many elements the DOM summary may hold for an album with this many tracks (no upper cap).

    The limit grows with the track count so that nothing which is in the page is cut by the limit.
    """
    return 200 + 30 * tracks if tracks else 1500


def count_buttons(items):
    """Number of context menu buttons (one per track row) in a summary."""
    return sum(1 for i in items if "AlbumTrackEdge_ContextMenu" in (i.get("testid") or ""))


def count_track_rows(items):
    """Number of track rows in a summary, by the context menu buttons or by the rows, whichever is larger."""
    return max(count_buttons(items), len(parse_album_rows(items)))


def read_album_current(cl):
    """Read the album page that is open now (header and the rows that are in the page, with their duration).

    The page is never scrolled. A page that is too long to be in the DOM at once means that the search was wrong,
    so the user picks another query. rows_read tells how many rows were read, and tracks is the number of the header.
    If they differ it is no error, the two numbers are the information. The context menu buttons are counted too
    and a difference to the rows read is only logged.
    """
    head = tc = rd = du = artist = ""
    for _ in range(12):
        cl.sleep(cl.poll)
        head = cl.text('[data-testid="PageHeaderMetadata_Headline"]')
        artist = cl.text('[data-testid="PageHeaderMetadata_Primary"]')
        tc = cl.text('[data-testid="PageHeaderMetadata_TertiaryBox_TrackCount"]')
        rd = cl.text('[data-testid="PageHeaderMetadata_TertiaryBox_ReleaseDate"]')
        du = cl.text('[data-testid="PageHeaderMetadata_TertiaryBox_Duration"]')
        if head and tc and rd:  # the header texts load after the headline
            break
    n = re.search(r"(\d+)", tc or "")
    tracks = int(n.group(1)) if n else None
    items = cl.dom_summary(summary_limit(tracks))
    rows = parse_album_rows(items)
    buttons = count_buttons(items)
    if buttons and buttons != len(rows):
        cl.log("warning: album %s has %d context menu buttons but %d rows were read" % (head or "?", buttons, len(rows)))
    return {"album_name": head or None, "album_artist": artist or None, "release_date": rd or None,
            "year": year_of(rd), "tracks": tracks, "duration": du or None, "rows": rows, "rows_read": len(rows)}


def read_album(cl, album_id):
    cl.nav(SITE + "/albums/" + album_id)
    info = read_album_current(cl)
    info["album_id"] = album_id
    info["album_url"] = SITE + "/albums/" + album_id
    return info


MONTHS = {"jan", "feb", "mar", "maer", "maerz", "mär", "märz", "mrz", "apr", "mai", "jun", "juni", "jul", "juli", "aug",
          "sep", "sept", "okt", "nov", "dez"}
DATE_RE = re.compile(r"(\d{1,2})\. ([A-Za-zÄÖÜäöü]+)\.? (\d{4})")


def parse_track_title(title):
    """(track title, artist, album or None) from '<title> Song von <artist> aus dem Album <album> auf Amazon Music'."""
    m = re.fullmatch(r"(.*?) Song von (.+?) aus dem Album (.+) auf Amazon Music", title or "")
    if m:
        return m.group(1), m.group(2), m.group(3)
    m = re.fullmatch(r"(.*?) Song von (.+?) auf Amazon Music", title or "")
    return (m.group(1), m.group(2), None) if m else None


DURATION_RE = re.compile(r"(\d+) Min\.(?: (\d+) Sek\.)?|(\d+) Sek\.")


def parse_track_text(text):
    """(duration mm:ss or None, date text as written or None, year or None) from the text of a track page.

    The page has a line like 'Song | 1979 | Interpret | 4 Min. 26 Sek. | • | 23. Okt. 1995'. The duration may lack
    the minutes or the seconds ('3 Min.', '47 Sek.'). The date is the first German date after the duration
    (or in the text, if there is no duration) whose month is a known abbreviation. It is kept as written.
    The two are read independently, so a page without a date still gives its duration and the other way round.
    """
    dur = DURATION_RE.search(text or "")
    duration = None
    if dur:
        minutes = int(dur.group(1)) if dur.group(1) is not None else 0
        seconds_part = dur.group(2) if dur.group(1) is not None else dur.group(3)
        duration = mmss(minutes * 60 + (int(seconds_part) if seconds_part is not None else 0))
    for m in DATE_RE.finditer(text or "", dur.end() if dur else 0):
        if m.group(2).lower().rstrip(".") in MONTHS:
            return duration, m.group(0), int(m.group(3))
    return duration, None, None


EXPLICIT_RE = re.compile(r"(?:^|\n|\| )E(?:\n| \||$)")


def parse_explicit(text):
    """True if the track page marks the song as explicit, False if it does not, None if there is no text.

    Amazon shows a small E next to the title of an explicit song. In the text of the page it is a line `E`
    (or ` | E | ` in the fake page of the tests) right after the title. Only the head of the text is looked at,
    the lists further down hold the titles of other songs. The word "Explicit" in an album name does not count.
    """
    if not text:
        return None
    return EXPLICIT_RE.search(text[:400]) is not None


def read_track_page(cl, track_id):
    """Load the track page of a song id and read it. Each field is read on its own.

    Returns a dict with meta_title, album_name, duration, album_release_date, album_year and explicit (True when the
    page shows the E of an explicit song, False when it does not), where a field that
    could not be read is None, plus missing (a list of album, duration and date that were not found).
    Returns None if nothing was found at all (no album, no duration, no date). Raises HostError if the page is the
    page of another song. It waits for the page title (' Song von '), then once more for the text, with a fixed limit.
    """
    cl.nav(SITE + "/tracks/" + track_id)
    url = title = ""
    parsed = None
    for _ in range(8):
        cl.sleep(cl.poll)
        url, title = cl.tab()
        parsed = parse_track_title(title)
        if parsed:
            cl.sleep(cl.poll)  # the text comes a moment after the title
            break
    text = cl.call("/dom", {"mode": "text", "maxBytes": 6000}, 45).get("data") or ""
    if track_id not in url:
        raise HostError("meta page is not the track page of %s (tab is at %s)" % (track_id, url))
    duration, date, year = parse_track_text(text)
    album = parsed[2] if parsed else None
    if album is None and duration is None and date is None:
        return None
    missing = [name for name, value in (("album", album), ("duration", duration), ("date", date)) if value is None]
    return {"meta_title": parsed[0] if parsed else None, "album_name": album, "duration": duration,
            "album_release_date": date, "album_year": year, "explicit": parse_explicit(text), "missing": missing}


def track_page_duration(cl, track_id):
    """Duration (mm:ss) from the text of the track page, or None."""
    cl.nav(SITE + "/tracks/" + track_id)
    cl.sleep(cl.poll)
    t = cl.call("/dom", {"mode": "text", "maxBytes": 3000}, 45).get("data") or ""
    m = re.search(r"(\d+) Min\. (\d+) Sek\.", t)
    return mmss(int(m.group(1)) * 60 + int(m.group(2))) if m else None


def _wait_tab(cl, want, tries=10):
    url, title = "", ""
    for _ in range(tries):
        cl.sleep(cl.poll)
        url, title = cl.tab()
        if want(url, title):
            break
    return url, title


def row_track_id(cl, album_id, index):
    """Context menu of album row `index`, entry 'Song anzeigen', then read the song id from the tab URL.

    The album page is loaded again first if the tab is not on it. The page is never scrolled. If the row is not
    in the page, the job fails with a hint to make a more exact song search. Returns (track_id or None, tab title).
    """
    cur, _t = cl.tab()
    if not cur.split("?")[0].endswith("/albums/" + album_id):
        cl.nav(SITE + "/albums/" + album_id)
        for _ in range(9):
            cl.sleep(cl.poll)
            if cl.text('[data-testid="PageHeaderMetadata_Headline"]'):
                break
    m = re.search(r"(\d+)", cl.text('[data-testid="PageHeaderMetadata_TertiaryBox_TrackCount"]') or "")
    tracks = int(m.group(1)) if m else None
    shown = count_track_rows(cl.dom_summary(summary_limit(tracks)))
    if index >= shown:
        raise HostError("row not on the page (read %d of %s rows), use a more exact song job with the album name in the query"
                        % (shown, tracks if tracks else "?"))
    cl.call("/act", {"action": "click", "selector": '[data-testid*="AlbumTrackEdge_ContextMenu"]', "index": index, "timeout": 6000}, 30)
    cl.sleep(1.5)
    cl.call("/act", {"action": "click", "selector": '[data-testid*="OverflowMenu_Option_ViewTrack"]', "index": 0, "timeout": 6000}, 30)
    cl.pages += 1
    url, title = _wait_tab(cl, lambda u, t: "/tracks/" in u and " Song von " in t)
    m = re.search(r"/tracks/(%s)" % ASIN, url)
    if "/tracks/" in url and cl.go_back():
        for _ in range(9):  # wait until the album page shows again
            cl.sleep(cl.poll)
            if cl.text('[data-testid="PageHeaderMetadata_Headline"]'):
                break
    # without /back the next call finds the tab off the album page and loads it again
    return (m.group(1) if m else None), title


