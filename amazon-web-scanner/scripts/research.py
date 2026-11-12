"""Card set CSV reader, card selection and the research file (one JSON line per card).

Standard library only. The research file is always rewritten as a whole with an atomic replace,
so a crash or Ctrl-C can never leave a half written file.
"""
import csv
import json
import os
import re
import tempfile

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CSV_DIR = os.path.join(REPO, "assets", "card-data")
OUT_DIR = os.path.join(REPO, "assets", "amazon-data")

STAGES = ("track_search", "album_search")


def csv_path(set_name, csv_dir=CSV_DIR):
    return os.path.join(csv_dir, set_name + ".csv")


def research_path(set_name, out_dir=OUT_DIR):
    return os.path.join(out_dir, set_name + ".research.jsonl")


def read_csv(path):
    """Returns {card number: {"card", "title", "artist", "card_year"}} from a card set CSV."""
    cards = {}
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            num = int(r["Card#"])
            year = (r.get("Year") or "").strip()
            cards[num] = {"card": num, "title": r["Title"], "artist": r["Artist"],
                          "card_year": int(year) if year.isdigit() else None}
    return cards


def parse_cards(spec, available, file_path=None):
    """Turn '6,15,88-92' or 'all' or the content of a cards file into a sorted list of card numbers.

    Numbers that are not in the CSV raise ValueError. A cards file may have commas, spaces or lines,
    and '#' starts a comment.
    """
    if file_path:
        with open(file_path, encoding="utf-8") as f:
            text = " ".join(re.sub(r"#.*", "", line) for line in f)
    else:
        text = spec or ""
    text = text.strip()
    if text.lower() == "all":
        return sorted(available)
    wanted = set()
    for part in re.split(r"[,\s]+", text):
        if not part:
            continue
        m = re.fullmatch(r"(\d+)(?:-(\d+))?", part)
        if not m:
            raise ValueError("bad card selection: %r" % part)
        lo = int(m.group(1))
        hi = int(m.group(2)) if m.group(2) else lo
        if hi < lo:
            raise ValueError("bad range: %r" % part)
        wanted.update(range(lo, hi + 1))
    if not wanted:
        raise ValueError("no cards selected")
    missing = sorted(wanted - set(available))
    if missing:
        raise ValueError("cards not in the CSV: %s" % ",".join(map(str, missing)))
    return sorted(wanted)


def load(path):
    """Returns {card number: record} from the research file. A missing file gives {}. Bad lines are skipped."""
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
                out[int(rec["card"])] = rec
            except (ValueError, KeyError, TypeError):
                continue
    return out


def save(path, records):
    """Write all records ordered by card number with an atomic replace."""
    folder = os.path.dirname(os.path.abspath(path))
    os.makedirs(folder, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".research-", suffix=".tmp", dir=folder)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            for num in sorted(records):
                f.write(json.dumps(records[num], ensure_ascii=False) + "\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


BASE_FIELDS = ("title", "artist", "card_year")


def merge_stage(records, card_info, stage, data):
    """Put one stage into the record of a card. Other stages and unknown fields of the line are kept.

    A missing record is created from the CSV data. The title, artist and card_year of the line are always
    set from the current CSV, so a changed CSV is carried over.
    """
    num = card_info["card"]
    rec = records.get(num)
    if rec is None:
        rec = {"card": num}
        records[num] = rec
    for k in BASE_FIELDS:
        rec[k] = card_info[k]
    rec[stage] = data
    # keep the documented key order: base fields, track_search, album_search, anything else
    ordered = {k: rec[k] for k in ("card",) + BASE_FIELDS + STAGES if k in rec}
    for k, v in rec.items():
        ordered.setdefault(k, v)
    records[num] = ordered
    return ordered


def sync_cards(records, cards):
    """Set title, artist and card_year of every existing line from the CSV cards. No line is created.

    Returns a list of (card number, {field: (old, new)}) for the lines that changed. Cards that are not in
    the CSV any more are left alone.
    """
    changed = []
    for num in sorted(records):
        info = cards.get(num)
        if info is None:
            continue
        diff = {k: (records[num].get(k), info[k]) for k in BASE_FIELDS if records[num].get(k) != info[k] or k not in records[num]}
        if diff:
            for k, (_old, new) in diff.items():
                records[num][k] = new
            changed.append((num, diff))
    return changed


def has_stage(rec, stage):
    """A stage counts as done when it exists and has data (hits or releases), or was read without errors."""
    if not rec or not isinstance(rec.get(stage), dict):
        return False
    st = rec[stage]
    key = "hits" if stage == "track_search" else "releases"
    return bool(st.get(key)) or not st.get("errors")


def status_lines(set_name, cards, records):
    """Text lines for the status sub-command."""
    ts = [n for n in sorted(cards) if has_stage(records.get(n), "track_search")]
    als = [n for n in sorted(cards) if has_stage(records.get(n), "album_search")]
    hits = sum(len((records[n].get("track_search") or {}).get("hits") or []) for n in ts)
    rels = sum(len((records[n].get("album_search") or {}).get("releases") or []) for n in als)
    ids = sum(1 for n in als for r in records[n]["album_search"].get("releases") or [] for x in r.get("rows") or [] if x.get("track_id"))
    lines = ["%s: %d cards in the CSV" % (set_name, len(cards)),
             "  track_search: %d cards, %d hits" % (len(ts), hits),
             "  album_search: %d cards, %d releases, %d rows with a track id" % (len(als), rels, ids)]
    for name, done in (("track_search", ts), ("album_search", als)):
        missing = [n for n in sorted(cards) if n not in done]
        lines.append("  missing %s: %s" % (name, _compact(missing) if missing else "none"))
    return lines


def _compact(nums):
    """[1,2,3,7] -> '1-3,7'"""
    out, i = [], 0
    while i < len(nums):
        j = i
        while j + 1 < len(nums) and nums[j + 1] == nums[j] + 1:
            j += 1
        out.append(str(nums[i]) if i == j else "%d-%d" % (nums[i], nums[j]))
        i = j + 1
    return ",".join(out)
