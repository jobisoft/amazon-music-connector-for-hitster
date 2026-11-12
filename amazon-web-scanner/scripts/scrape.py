#!/usr/bin/env python3
"""Collect Amazon Music Germany data for the cards of a Hitster card set, as a job file says.

  python3 scrape.py tabs
  python3 scrape.py run jobs.json --tab-id 12 --results jobs.results.jsonl
  python3 scrape.py status --set hitster-de-aaaa0039
  python3 scrape.py sync-cards --set hitster-de-aaaa0039

The tool does exactly what the job file says and decides nothing. It never judges, picks or rejects a candidate.
It talks to the helper of the Amazon Web Scanner on 127.0.0.1 and to nothing else.
The research file <repo>/assets/amazon-data/<set>.research.jsonl is rewritten after every job.
Standard library only. See the section 'scrape.py' in amazon-web-scanner/README.md.
"""
import argparse
import hashlib
import json
import os
import re
import signal
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import research as R  # noqa: E402
import webclient as W  # noqa: E402

DEFAULT_MAX_RELEASES = 8
HOST_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "addon", "native-messaging-app", "amazon_web_scanner_host.py")
MAX_ROWS_PER_RELEASE = 10  # rows that resemble the card title, per release
JOBS = ("song", "album", "meta", "track")
SET_NAME = re.compile(r"[A-Za-z0-9._-]+")
JOB_FIELDS = {"song": {"query"}, "album": {"query", "song_ids"}, "meta": {"hits", "tracks"}, "track": {"album", "row"}}


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, file=sys.stderr, flush=True)


class Stopper:
    """First Ctrl-C: finish the running job, write it and stop. Second Ctrl-C: stop at once."""

    def __init__(self):
        self.requested = False

    def handler(self, signum, frame):
        if self.requested:
            raise KeyboardInterrupt
        self.requested = True
        log("Ctrl-C: the run stops after the running job (press again to stop at once)")


# ---------------------------------------------------------------- song job
def _hit_from_row(rank, r):
    return {"rank": rank, "track_id": r["track_id"], "track_url": W.SITE + "/tracks/" + r["track_id"],
            "title": r["title"], "artists": r["artists"], "album_id": None, "album_url": None,
            "album_name": r.get("album_name_search"), "album_artist": None, "album_year": None,
            "album_release_date": None, "album_tracks": None, "album_duration": None,
            "duration": r.get("duration"), "origin": ["song search"]}


def default_song_query(card):
    return "%s %s" % (W.first_artist(card["artist"]), card["title"])


def collect_song(cl, card, query):
    """Song search for one card. Returns the value of the track_search field.

    It reads the full result page /search/<query>/songs, exactly the rows that are in the page after it is loaded
    and stable. The page is not scrolled and the number of rows is not limited. It only makes the list. No album page
    is opened here, a meta job reads the head data of hits that were named.
    """
    res = {"query": query, "page": "songs", "hits": []}
    cl.nav(W.songs_page_url(query))
    items, _stable = W.wait_stable_songs(cl)
    if not W.songs_page_ready(items):
        res["errors"] = [{"error": "no result rows after waiting"}]
    res["hits"] = [_hit_from_row(rank, r) for rank, r in enumerate(W.parse_songs_page(items), 1)]
    return res


# ---------------------------------------------------------------- hit selection
def pick_hits(hits, spec_hits, spec_tracks):
    """The hits named by "hits" (ranks, ints or range strings) or "tracks" (song ids). Raises ValueError if one is missing."""
    if spec_hits is not None:
        ranks = set()
        for x in spec_hits:
            ranks |= set(R.parse_cards(str(x), {h["rank"] for h in hits}))
        return [h for h in hits if h["rank"] in ranks]
    known = {h["track_id"]: h for h in hits}
    missing = [x for x in spec_tracks if x not in known]
    if missing:
        raise ValueError("not in the stored list: %s" % ",".join(missing))
    return [known[x] for x in dict.fromkeys(spec_tracks)]


# ---------------------------------------------------------------- meta job
def meta_at(cl, card, records, path, hits, stopper):
    """Read the track page of each chosen hit and write what could be read into the hit.

    Returns (errors of these hits, incomplete). Each field is read and stored on its own and a field that could not
    be read keeps its value in the hit. A hit with only some fields read gets no error, it is listed in incomplete
    as {track_id, missing: [album, duration, date]}. Only a hit where nothing at all was found, or whose page is
    another song, gets the error. Each hit gets its own page load by song id. No search, no menu, no album page,
    no click.
    """
    ts = records[card["card"]]["track_search"]
    errors = list(ts.get("errors") or [])
    incomplete = []
    ids = {h["track_id"] for h in hits}
    for hit in hits:
        if stopper.requested:
            break
        tid = hit["track_id"]
        errors = [e for e in errors if e.get("track_id") != tid]
        try:
            meta = W.read_track_page(cl, tid)
            if meta is None:
                errors.append({"track_id": tid, "error": "meta page not readable"})
                log("hit %d (%s): meta page not readable" % (hit["rank"], tid))
            else:
                if meta["album_name"] is not None:
                    hit["album_name"] = meta["album_name"]
                if meta["meta_title"] is not None:
                    hit["meta_title"] = meta["meta_title"]
                if meta["album_release_date"] is not None:
                    hit["album_release_date"], hit["album_year"] = meta["album_release_date"], meta["album_year"]
                if meta["explicit"] is not None:
                    hit["explicit"] = meta["explicit"]
                if meta["duration"] is not None and hit.get("duration") is None:
                    hit["duration"] = meta["duration"]
                if meta["missing"]:
                    incomplete.append({"track_id": tid, "missing": meta["missing"]})
                log("hit %d (%s): %s, %s%s" % (hit["rank"], tid, meta["album_name"], meta["album_release_date"],
                                              ", missing " + "/".join(meta["missing"]) if meta["missing"] else ""))
        except W.HostError as e:
            errors.append({"track_id": tid, "error": str(e)})
            log("hit %d (%s): %s" % (hit["rank"], tid, e))
        if errors:
            ts["errors"] = errors
        else:
            ts.pop("errors", None)
        R.merge_stage(records, card, "track_search", ts)
        R.save(path, records)
    return [e for e in errors if e.get("track_id") in ids], incomplete


# ---------------------------------------------------------------- album job
def collect_album(cl, card, query, song_ids):
    """Album search for one card. Returns the value of the album_search field."""
    if query is None:
        query = "%s\t%s" % (W.first_artist(card["artist"]), card["title"])
    errors = []
    url = W.album_search_url(*query.split("\t", 1)) if "\t" in query else W.plain_album_search_url(query)
    cl.nav(url)
    items, ok = cl.wait_items(W.album_search_ready, 800)
    albums = W.parse_album_search(items)
    if not albums and "\t" in query:
        url = W.plain_album_search_url(query.split("\t", 1)[1])
        cl.nav(url)
        items, ok = cl.wait_items(W.album_search_ready, 800)
        albums = W.parse_album_search(items)
    if not albums and not ok:
        errors.append({"error": "no album result widget after waiting"})
    res = {"url": url, "releases": []}
    tcore = W.core(card["title"])
    for a in albums[:DEFAULT_MAX_RELEASES]:
        try:
            info = W.read_album(cl, a["id"])
            rows = [dict(r) for r in info["rows"] if W.rows_resemble(r["title"], tcore)][:MAX_ROWS_PER_RELEASE]
            for r in rows:
                r["track_id"] = None
                r["track_url"] = None
                if song_ids:
                    try:
                        tid, _title = W.row_track_id(cl, a["id"], r["index"])
                        r["track_id"] = tid
                        r["track_url"] = W.SITE + "/tracks/" + tid if tid else None
                    except W.HostError as e:
                        errors.append({"album_id": a["id"], "row": r["index"], "error": str(e)})
                        log("  row %d of %s: %s" % (r["index"], a["id"], e))
            res["releases"].append({"rank": a["rank"], "album_id": a["id"], "album_url": info["album_url"],
                                    "album_name": info["album_name"] or a["name_search"],
                                    "album_artist": info["album_artist"] or a["artist_search"],
                                    "release_date": info["release_date"], "year": info["year"], "tracks": info["tracks"],
                                    "rows_read": info["rows_read"], "duration": info["duration"], "rows": rows})
        except W.HostError as e:
            errors.append({"album_id": a["id"], "error": str(e)})
            log("  album %s: %s" % (a["id"], e))
    if errors:
        res["errors"] = errors
    return res


# ---------------------------------------------------------------- track command
def add_track(cl, card, records, album_id, row_index):
    """Read the song id of one album row and put it into the album_search of the card."""
    info = W.read_album(cl, album_id)
    tid, _title = W.row_track_id(cl, album_id, row_index)
    if not tid:
        raise W.HostError("no song id found for album %s row %d" % (album_id, row_index))
    rec = records.get(card["card"]) or {}
    st = dict(rec.get("album_search") or {"url": "", "releases": []})
    releases = list(st.get("releases") or [])
    rel = next((r for r in releases if r.get("album_id") == album_id), None)
    if rel is None:
        rel = {"rank": None, "album_id": album_id, "album_url": info["album_url"], "album_name": info["album_name"],
               "album_artist": info["album_artist"], "release_date": info["release_date"], "year": info["year"],
               "tracks": info["tracks"], "duration": info["duration"], "rows": []}
        releases.append(rel)
    rows = list(rel.get("rows") or [])
    row = next((r for r in rows if r.get("index") == row_index), None)
    if row is None:
        page_row = next((r for r in info["rows"] if r["index"] == row_index), None) or {"title": None, "duration": None}
        row = {"index": row_index, "title": page_row["title"], "duration": page_row["duration"]}
        rows.append(row)
        rows.sort(key=lambda r: r["index"])
    row["track_id"] = tid
    row["track_url"] = W.SITE + "/tracks/" + tid
    rel["rows"] = rows
    st["releases"] = releases
    return st


# ---------------------------------------------------------------- job file
class SetData:
    """The CSV cards, the research records and the research file path of one card set."""

    def __init__(self, name, cards, records, path):
        self.name, self.cards, self.records, self.path = name, cards, records, path


class Sets:
    """The card sets that a job file names. Each set is loaded once, when a job needs it."""

    def __init__(self, csv_dir, out_dir):
        self.csv_dir, self.out_dir, self.cache = csv_dir, out_dir, {}

    def get(self, name):
        """The SetData of a set, or None if its CSV does not exist."""
        if name not in self.cache:
            try:
                cards = R.read_csv(R.csv_path(name, self.csv_dir))
            except OSError:
                self.cache[name] = None
            else:
                path = R.research_path(name, self.out_dir)
                self.cache[name] = SetData(name, cards, R.load(path), path)
        return self.cache[name]


def parse_jobs(path, sets):
    """Read and check the whole job file. Returns (jobs, errors) where errors are texts with a path like cards[3].

    The file is one JSON document: {"set": "<csv name>", "cards": [ {"card": 8, "job": "song"}, ... ]}.
    Nothing is run when there is an error. One job per card is allowed.
    """
    with open(path, encoding="utf-8") as f:
        text = f.read()
    try:
        doc = json.loads(text)
    except ValueError as e:
        if hasattr(e, "lineno"):
            return [], ["not valid JSON at line %d column %d: %s" % (e.lineno, e.colno, e.msg)]
        return [], ["not valid JSON: %s" % e]
    if not isinstance(doc, dict):
        return [], ["the file must be a JSON object with \"set\" and \"cards\""]
    errors = []
    unknown = sorted(set(doc) - {"set", "cards"})
    if unknown:
        errors.append("unknown field at the top level: %s" % ", ".join(unknown))
    name = doc.get("set")
    sd = None
    if not isinstance(name, str) or not SET_NAME.fullmatch(name):
        errors.append('"set" must be the name of a card set, for example hitster-de-aaaa0039')
    else:
        sd = sets.get(name)
        if sd is None:
            errors.append("there is no CSV for the set %s" % name)
    entries = doc.get("cards")
    if not isinstance(entries, list):
        errors.append('"cards" must be a list')
        return [], errors
    if not entries:
        errors.append('"cards" is an empty list, there is nothing to run')
    if sd is None:
        return [], errors
    cards, records = sd.cards, sd.records
    jobs, seen = [], {}
    for i, d in enumerate(entries):
        bad = len(errors)

        def err(msg):
            errors.append("cards[%d]: %s" % (i, msg))
        if not isinstance(d, dict):
            err("a job must be a JSON object")
            continue
        card, kind = d.get("card"), d.get("job")
        if not isinstance(card, int) or isinstance(card, bool):
            err('"card" must be a number')
            continue
        if kind not in JOBS:
            err('unknown job %s, "job" must be one of %s' % (json.dumps(kind), ", ".join(JOBS)))
            continue
        unknown = sorted(set(d) - {"card", "job", "note"} - JOB_FIELDS[kind])
        if unknown:
            err("unknown field for job %s: %s" % (kind, ", ".join(unknown)))
            continue
        if card not in cards:
            err("card %d is not in the CSV of %s" % (card, name))
            continue
        if card in seen:
            err("duplicate card %d (also in cards[%d])" % (card, seen[card]))
            continue
        seen[card] = i
        if "note" in d and not isinstance(d["note"], str):
            err('"note" must be a text')
        if "query" in d and (not isinstance(d["query"], str) or not d["query"].strip()):
            err('"query" must be a non-empty text')
        if "song_ids" in d and not isinstance(d["song_ids"], bool):
            err('"song_ids" must be true or false')
        if kind == "track":
            if not isinstance(d.get("album"), str) or not re.fullmatch(W.ASIN, d["album"]):
                err('"album" must be an album ASIN like B0716SVJ7N')
            if not isinstance(d.get("row"), int) or isinstance(d.get("row"), bool) or d["row"] < 0:
                err('"row" must be a row index (0 or more)')
        if kind == "meta":
            if ("hits" in d) == ("tracks" in d):
                err('meta needs exactly one of "hits" and "tracks"')
            else:
                key = "hits" if "hits" in d else "tracks"
                val = d[key]
                if not isinstance(val, list) or not val:
                    err('"%s" must be a non-empty list' % key)
                elif key == "tracks" and not all(isinstance(x, str) for x in val):
                    err('"tracks" must be a list of song ids')
                elif key == "hits" and not all(isinstance(x, (int, str)) and not isinstance(x, bool) for x in val):
                    err('"hits" must be a list of ranks or range texts like "2-4"')
                else:
                    ts = (records.get(card) or {}).get("track_search")
                    if not ts or not ts.get("hits"):
                        err("card %d has no stored song list, run a song job first" % card)
                    else:
                        try:
                            pick_hits(ts["hits"], val if key == "hits" else None, val if key == "tracks" else None)
                        except ValueError as e:
                            err("%s (run a song job first)" % e)
        if len(errors) == bad:
            job = dict(d)
            job["index"], job["set"] = i, name
            jobs.append(job)
    return jobs, errors


def job_query(job, card, records):
    """The search text a job uses (shown in dry-run and in the result). records are those of the set of the job."""
    if job["job"] == "song":
        return job.get("query") or default_song_query(card)
    if job["job"] == "album":
        return job.get("query") or "%s\t%s" % (W.first_artist(card["artist"]), card["title"])
    if job["job"] == "meta":
        return ((records.get(card["card"]) or {}).get("track_search") or {}).get("query")
    return None


def run_job(cl, job, sd, stopper):
    """Run one job exactly as it stands. Returns the fields for its result line. A failure raises and writes nothing."""
    kind, num = job["job"], job["card"]
    card, records, path = sd.cards[num], sd.records, sd.path
    if kind == "song":
        data = collect_song(cl, card, job_query(job, card, records))
        R.merge_stage(records, card, "track_search", data)
        R.save(path, records)
        return {"query": data["query"], "hits": len(data["hits"]), "errors": data.get("errors", []),
                "message": "%d hits" % len(data["hits"])}
    if kind == "album":
        query = job_query(job, card, records)
        data = collect_album(cl, card, query, bool(job.get("song_ids")))
        R.merge_stage(records, card, "album_search", data)
        R.save(path, records)
        rows = sum(len(r["rows"]) for r in data["releases"])
        return {"query": query, "releases": len(data["releases"]), "rows": rows, "errors": data.get("errors", []),
                "message": "%d releases, %d rows" % (len(data["releases"]), rows)}
    if kind == "meta":
        ts = records[num]["track_search"]
        hits = pick_hits(ts["hits"], job.get("hits"), job.get("tracks"))
        errors, incomplete = meta_at(cl, card, records, path, hits, stopper)
        bad = {e["track_id"] for e in errors}
        done = sum(1 for h in hits if h["track_id"] not in bad)
        return {"query": ts["query"], "metas": [h["rank"] for h in hits], "errors": errors, "incomplete": incomplete,
                "message": "%d of %d read" % (done, len(hits)) + (", %d incomplete" % len(incomplete) if incomplete else "")}
    st = add_track(cl, card, records, job["album"], job["row"])
    R.merge_stage(records, card, "album_search", st)
    R.save(path, records)
    return {"query": None, "errors": [], "message": "song id stored"}


class Results:
    """The result file of one run. It is created new, and every line is flushed at once."""

    def __init__(self, path):
        self.f = open(path, "w", encoding="utf-8", newline="\n")

    def write(self, obj):
        self.f.write(json.dumps(obj, ensure_ascii=False) + "\n")
        self.f.flush()
        log("result: " + json.dumps(obj, ensure_ascii=False))

    def close(self):
        self.f.close()


def run_jobs(cl, jobs, sets, results, opts, stopper, sleep):
    """Run the jobs from top to bottom. Returns (exit code, stopped reason or None)."""
    started = time.monotonic()
    ok = bad = 0
    stopped = None
    code = 0

    def line_for(job, status, secs, extra):
        d = {"index": job["index"], "set": job["set"], "card": job["card"], "job": job["job"], "query": None, "status": status, "seconds": round(secs, 1)}
        d.update(extra)
        if job.get("note") is not None:
            d["note"] = job["note"]
        d.setdefault("errors", [])
        d.setdefault("message", "")
        return d
    ran = 0
    for i, job in enumerate(jobs):
        if stopper.requested:
            stopped, code = "interrupted", 130
            break
        if ran:
            for _ in range(int(opts.pause)):
                if stopper.requested:
                    break
                sleep(1)
            if stopper.requested:
                stopped, code = "interrupted", 130
                break
        ran += 1
        t0 = time.monotonic()
        sd = sets.get(job["set"])
        card = sd.cards[job["card"]]
        log("cards[%d]: %s card %d %s / %s" % (job["index"], job["set"], card["card"], card["artist"], card["title"]))
        try:
            extra = run_job(cl, job, sd, stopper)
            results.write(line_for(job, "ok", time.monotonic() - t0, extra))
            ok += 1
        except W.TabGoneError as e:
            bad += 1
            results.write(line_for(job, "error", time.monotonic() - t0, {"message": str(e), "errors": [str(e)]}))
            for rest in jobs[i + 1:]:
                results.write(line_for(rest, "skipped_tab_lost", 0, {"message": "tab lost, not run"}))
            stopped, code = "tab lost", 4
            break
        except W.MemoryLowError as e:
            bad += 1
            results.write(line_for(job, "error", time.monotonic() - t0, {"message": str(e), "errors": [str(e)]}))
            stopped, code = "memory", 3
            break
        except W.FatalError as e:
            bad += 1
            results.write(line_for(job, "error", time.monotonic() - t0, {"message": str(e), "errors": [str(e)]}))
            stopped, code = "fatal", 3
            break
        except KeyboardInterrupt:
            bad += 1
            results.write(line_for(job, "error", time.monotonic() - t0, {"message": "interrupted", "errors": ["interrupted"]}))
            stopped, code = "interrupted", 130
            break
        except (W.HostError, ValueError, KeyError) as e:
            bad += 1
            results.write(line_for(job, "error", time.monotonic() - t0, {"message": "%s: %s" % (type(e).__name__, e), "errors": [str(e)]}))
            if bad >= opts.max_errors:
                stopped, code = "max-errors", 3
                break
        if stopper.requested and i < len(jobs) - 1:
            stopped, code = "interrupted", 130
            break
    results.write({"summary": True, "jobs": len(jobs), "ok": ok, "error": bad,
                   "seconds": round(time.monotonic() - started, 1), "stopped": stopped})
    return code, stopped


def build_parser():
    p = argparse.ArgumentParser(prog="scrape.py", description="Collect Amazon Music Germany data for Hitster card sets, as a job file says. Decides nothing.")
    p.add_argument("command", choices=["run", "status", "tabs", "sync-cards"])
    p.add_argument("jobs", nargs="?", help="run: the job file (one JSON document with the set and the list of cards)")
    p.add_argument("--set", dest="set_name", help="status and sync-cards: the CSV name without .csv, for example hitster-de-aaaa0039")
    p.add_argument("--tab-id", type=int, help="run: the tab to work in (required, list the tabs with: scrape.py tabs)")
    p.add_argument("--results", help="run: the result file (required, it is created new at every run)")
    p.add_argument("--port", type=int, default=W.DEFAULT_PORT)
    p.add_argument("--config", default=W.DEFAULT_CONFIG, help="config file of the helper (or its folder)")
    p.add_argument("--pause", type=float, default=20, help="seconds between jobs (default 20)")
    p.add_argument("--min-mem-mb", type=int, default=1500, help="wait while MemAvailable is lower (default 1500)")
    p.add_argument("--home-every", type=int, default=60, help="load the start page every N page loads (default 60)")
    p.add_argument("--max-errors", type=int, default=5, help="stop after this many failed jobs (default 5)")
    p.add_argument("--dry-run", action="store_true", help="run: check the job file and list the jobs, touch nothing. sync-cards: show the list only")
    p.add_argument("--csv-dir", default=R.CSV_DIR, help=argparse.SUPPRESS)
    p.add_argument("--out-dir", default=R.OUT_DIR, help=argparse.SUPPRESS)
    return p


def repo_build(path=None):
    """First 12 hex characters of the SHA-256 of the helper file in this repository, or None if it is missing."""
    try:
        with open(path or HOST_FILE, "rb") as f:
            return hashlib.sha256(f.read()).hexdigest()[:12]
    except OSError:
        return None


def check_helper(status):
    """Print the version line of the running helper and compare its build with the repository file.

    Only warnings, the run goes on in every case.
    """
    version, build = status.get("hostVersion"), status.get("build")
    if not build:
        log("helper build unknown (old helper)")
        log("WARNING: the running helper is not the repository version, run addon/native-messaging-app/install.sh and restart the add-on")
        return
    log("helper %s build %s" % (version, build))
    mine = repo_build()
    if mine is None:
        log("info: the helper file of the repository was not found, build not compared")
    elif mine != build:
        log("WARNING: the running helper is not the repository version, run addon/native-messaging-app/install.sh and restart the add-on")


def show_tabs(opts, sleep):
    """The tabs command: show the Amazon Music tabs from GET /tabs. Display only, nothing is selected."""
    try:
        cl = W.Client(opts.port, opts.config, sleep=sleep, log=log)
        check_helper(cl.alive())
        tabs = cl.amazon_tabs()
    except W.FatalError as e:
        log("STOP: %s" % e)
        return 3
    except W.HostError as e:
        log("ERROR: %s" % e)
        return 1
    if not tabs:
        log("no Amazon Music tab open")
        return 4
    print("tabId  windowId  active  url  title")
    for t in tabs:
        print("%s  %s  %s  %s  %s" % (t.get("tabId"), t.get("windowId"), "yes" if t.get("active") else "no", t.get("url"), t.get("title")))
    return 0


def main(argv=None, sleep=time.sleep, meminfo=W.read_meminfo_kb, poll=1.2, page_pause=2.0, install_signal=True):
    opts = build_parser().parse_args(argv)
    if opts.command == "tabs":
        return show_tabs(opts, sleep)
    if opts.command == "run":
        if not opts.jobs:
            log("run needs the job file: scrape.py run jobs.json --tab-id N --results PATH")
            return 2
        if opts.tab_id is None:
            log("you must select a tab: --tab-id N (list tabs with: scrape.py tabs)")
            return 2
        if not opts.results:
            log("you must name the result file: --results PATH")
            return 2
        sets = Sets(opts.csv_dir, opts.out_dir)
        try:
            jobs, errors = parse_jobs(opts.jobs, sets)
        except OSError as e:
            log("cannot read the job file: %s" % e)
            return 2
        if errors:
            for e in errors:
                log(e)
            log("%d errors in the job file, nothing was run" % len(errors))
            return 2
        if not jobs:
            log("the job file has no jobs")
            return 2
        if opts.dry_run:
            for j in jobs:
                q = job_query(j, sets.get(j["set"]).cards[j["card"]], sets.get(j["set"]).records)
                extra = " ".join("%s=%s" % (k, json.dumps(j[k])) for k in ("hits", "tracks", "song_ids", "album", "row") if k in j)
                print("index %d set %s card %d job %s query %s%s" % (j["index"], j["set"], j["card"], j["job"], json.dumps(q, ensure_ascii=False), " " + extra if extra else ""))
            return 0
    else:
        if not opts.set_name:
            log("--set is required")
            return 2
        try:
            cards = R.read_csv(R.csv_path(opts.set_name, opts.csv_dir))
        except OSError as e:
            log("cannot read the CSV: %s" % e)
            return 2
        path = R.research_path(opts.set_name, opts.out_dir)
        records = R.load(path)
        if opts.command == "status":
            print("\n".join(R.status_lines(opts.set_name, cards, records)))
            return 0
        changed = R.sync_cards(records, cards)  # sync-cards needs no helper and no network
        for num, diff in changed:
            if "title" in diff:
                print("%d: %s -> %s" % (num, diff["title"][0], diff["title"][1]))
        if opts.dry_run:
            print("%d cards would be updated (dry run, nothing written)" % len(changed))
        else:
            if changed:
                R.save(path, records)
            print("%d cards updated" % len(changed))
        return 0
    stopper = Stopper()
    if install_signal:
        signal.signal(signal.SIGINT, stopper.handler)
    results = Results(opts.results)
    code = stopped = None
    try:
        try:
            guard = W.MemGuard(opts.min_mem_mb, reader=meminfo, sleep=sleep, log=log)
            cl = W.Client(opts.port, opts.config, opts.home_every, page_pause, poll, guard, sleep, log)
            check_helper(cl.alive())
            cl.select_tab(opts.tab_id)
        except W.TabGoneError as e:
            log("STOP: %s" % e)
            for j in jobs:
                results.write({"index": j["index"], "set": j["set"], "card": j["card"], "job": j["job"], "query": None, "status": "skipped_tab_lost",
                               "seconds": 0.0, "errors": [], "message": str(e)})
            results.write({"summary": True, "jobs": len(jobs), "ok": 0, "error": 0, "seconds": 0.0, "stopped": "tab lost"})
            return 4
        except W.FatalError as e:
            log("STOP: %s" % e)
            results.write({"summary": True, "jobs": len(jobs), "ok": 0, "error": 0, "seconds": 0.0, "stopped": "fatal"})
            return 3
        except W.HostError as e:
            log("ERROR: %s" % e)
            results.write({"summary": True, "jobs": len(jobs), "ok": 0, "error": 0, "seconds": 0.0, "stopped": "fatal"})
            return 1
        code, stopped = run_jobs(cl, jobs, sets, results, opts, stopper, sleep)
        if stopped:
            log("stopped: %s" % stopped)
        return code
    finally:
        results.close()


if __name__ == "__main__":
    sys.exit(main())
