#!/usr/bin/env python3
"""Summary of the reader mode (debug build of the app): what the tester found on the cards.

  python3 reader_summary.py [debug.log] [reader_mismatches.csv]

Reads the log of debug_sink.py (default: debug.log next to this script), one JSON event per line, and
looks at the events of the reader mode:

  reader verdict   one answer, sent when it was given
  reader dump      the same rows again (sent by builds that had the button "Send all results again", the app has none any more, old logs still have them)
  reader card      a scan (only used for the title and the artist)

Per card the LATEST answer counts (a skipped card is no answer). A "wrong" answer carries what the card
says: cardYear, cardTitle, cardArtist (all three, the unchanged ones equal ours). The mismatches go to
reader_mismatches.csv with all three pairs and a column `changed` that lists which of year, title and
artist differ:

  set, card, ourYear, cardYear, ourTitle, cardTitle, ourArtist, cardArtist, changed, source

`source` says where the answer came from: reader (the reader mode), scan or quiz (the "Report as wrong"
button of the game). Events without it are from the reader.

Events of the first version (a year only: yearOnCard, title, artist) are still read, they can only
differ in the year.
"""
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIELDS = ["set", "card", "ourYear", "cardYear", "ourTitle", "cardTitle", "ourArtist", "cardArtist", "changed", "source"]


def read_events(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if isinstance(event, dict) and event.get("tag") == "reader":
                yield event


def normalize(data):
    """One answer in the new field names, whichever version of the app sent it."""
    our_title = data.get("ourTitle") or data.get("title") or ""
    our_artist = data.get("ourArtist") or data.get("artist") or ""
    card_year = data.get("cardYear", data.get("yearOnCard"))
    return {
        "set": data.get("set"),
        "card": data.get("card"),
        "verdict": data.get("verdict"),
        "ourYear": data.get("ourYear"),
        "ourTitle": our_title,
        "ourArtist": our_artist,
        "cardYear": card_year,
        # the first version knew no title and artist of the card: they are taken as unchanged
        "cardTitle": data.get("cardTitle") if data.get("cardTitle") is not None else our_title,
        "cardArtist": data.get("cardArtist") if data.get("cardArtist") is not None else our_artist,
        "source": data.get("source") or "reader",
    }


def changed(answer):
    """Which of year, title and artist the card says differently, for a wrong answer."""
    if answer["verdict"] != "wrong":
        return []
    result = []
    if answer["cardYear"] is not None and answer["cardYear"] != answer["ourYear"]:
        result.append("year")
    if (answer["cardTitle"] or "").strip() != (answer["ourTitle"] or "").strip():
        result.append("title")
    if (answer["cardArtist"] or "").strip() != (answer["ourArtist"] or "").strip():
        result.append("artist")
    return result


def summarize(events):
    """-> (latest answer per (set, card), number of skipped scans)"""
    latest = {}
    skipped = 0
    for event in events:
        data = event.get("data") or {}
        if event.get("msg") not in ("reader verdict", "reader dump") or "set" not in data or "card" not in data:
            continue
        verdict = data.get("verdict")
        if verdict == "skipped":
            skipped += 1
            continue
        if verdict not in ("match", "wrong"):
            continue
        # the time of the answer is in the data (dump rows and verdicts alike), the log time is the fallback
        when = data.get("time") or str(event.get("t", ""))
        key = (data["set"], data["card"])
        old = latest.get(key)
        if old is None or when >= old["when"]:
            answer = normalize(data)
            answer["when"] = when
            latest[key] = answer
    return latest, skipped


def mismatches(latest):
    result = []
    for key in sorted(latest):
        answer = latest[key]
        diff = changed(answer)
        if not diff:
            continue
        row = {name: answer[name] for name in FIELDS if name != "changed"}
        for name in ("ourYear", "cardYear"):
            if row[name] is None:
                row[name] = ""
        row["changed"] = "+".join(diff)
        result.append(row)
    return result


def main():
    log = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "debug.log")
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "reader_mismatches.csv")
    if not os.path.exists(log):
        sys.exit("No log file: %s" % log)
    latest, skipped = summarize(read_events(log))
    matches = sum(1 for a in latest.values() if a["verdict"] == "match")
    wrong = [a for a in latest.values() if a["verdict"] == "wrong"]
    print("%d cards answered: %d match, %d wrong, %d skipped scans (latest answer per card)" % (len(latest), matches, len(wrong), skipped))
    bad = mismatches(latest)
    print("\nMismatches: %d" % len(bad))
    for m in bad:
        print("  %s card %s [%s] (%s)" % (m["set"], m["card"], m["changed"], m["source"]))
        if "year" in m["changed"]:
            print("      year:   %s -> %s" % (m["ourYear"], m["cardYear"]))
        if "title" in m["changed"]:
            print("      title:  %s -> %s" % (m["ourTitle"], m["cardTitle"]))
        if "artist" in m["changed"]:
            print("      artist: %s -> %s" % (m["ourArtist"], m["cardArtist"]))
    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(bad)
    print("\nWritten: %s" % out)


if __name__ == "__main__":
    main()
