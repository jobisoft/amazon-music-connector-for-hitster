#!/usr/bin/env python3
"""Tests of reader_summary.py: python3 -m unittest tools/test_reader_summary.py (or run it)."""
import csv
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import reader_summary as rs  # noqa: E402


def verdict(card, v, our_year=2000, card_year=None, our_title="T", our_artist="A", card_title=None,
            card_artist=None, time="2026-11-09T10:00:00", msg="reader verdict", old=False, source=None):
    if old:  # an event of the first version of the app
        data = {"set": "s.csv", "card": card, "ourYear": our_year, "verdict": v, "yearOnCard": card_year,
                "title": our_title, "artist": our_artist, "time": time}
    else:
        data = {"set": "s.csv", "card": card, "verdict": v, "ourYear": our_year, "ourTitle": our_title,
                "ourArtist": our_artist, "cardYear": card_year, "cardTitle": card_title,
                "cardArtist": card_artist, "time": time}
        if source:
            data["source"] = source
    return {"tag": "reader", "msg": msg, "data": data}


def with_ids(event, our_id, card_id):
    """The event with the two Amazon ids (ourAmazonId, cardAmazonId)."""
    event["data"]["ourAmazonId"] = our_id
    event["data"]["cardAmazonId"] = card_id
    return event


def run(events):
    d = tempfile.mkdtemp()
    log = os.path.join(d, "debug.log")
    with open(log, "w", encoding="utf-8") as f:
        f.write("\n".join(json.dumps(e) for e in events) + "\nnot json\n")
        f.write(json.dumps({"tag": "play", "msg": "x"}) + "\n")
    out = os.path.join(d, "m.csv")
    text = subprocess.run([sys.executable, os.path.join(HERE, "reader_summary.py"), log, out],
                          capture_output=True, text=True, check=True).stdout
    with open(out, encoding="utf-8", newline="") as f:
        return text, list(csv.DictReader(f))


class ReaderSummaryTest(unittest.TestCase):
    def test_changed_lists_the_differing_fields(self):
        a = rs.normalize(verdict(1, "wrong", 2000, 1984, "T", "A", "T", "A")["data"])
        self.assertEqual(["year"], rs.changed(a))
        a = rs.normalize(verdict(1, "wrong", 2000, 2000, "T", "A", "Other", "A")["data"])
        self.assertEqual(["title"], rs.changed(a))
        a = rs.normalize(verdict(1, "wrong", 2000, 1984, "T", "A", "Other", "Someone")["data"])
        self.assertEqual(["year", "title", "artist"], rs.changed(a))
        a = rs.normalize(verdict(1, "match")["data"])
        self.assertEqual([], rs.changed(a))

    def test_mismatches_csv_has_all_pairs_and_the_changed_column(self):
        text, rows = run([
            verdict(1, "wrong", 2000, 1984, "Get Back", "The Beatles", "Get Back (Remastered)", "Beatles"),
            verdict(2, "match"),
        ])
        self.assertEqual(1, len(rows))
        row = rows[0]
        self.assertEqual("1", row["card"])
        self.assertEqual("2000", row["ourYear"])
        self.assertEqual("1984", row["cardYear"])
        self.assertEqual("Get Back", row["ourTitle"])
        self.assertEqual("Get Back (Remastered)", row["cardTitle"])
        self.assertEqual("The Beatles", row["ourArtist"])
        self.assertEqual("Beatles", row["cardArtist"])
        self.assertEqual("year+title+artist", row["changed"])
        self.assertIn("1 cards answered" if False else "2 cards answered", text)

    def test_the_latest_answer_per_card_wins_also_a_dump_row(self):
        _, rows = run([
            verdict(1, "wrong", 2000, 1984, "T", "A", "T", "A", time="2026-11-09T10:00:00"),
            verdict(1, "match", time="2026-11-09T10:05:00", msg="reader dump"),  # checked again
            verdict(2, "match", time="2026-11-09T10:00:00"),
            verdict(2, "wrong", 2000, 1999, "T", "A", "T", "A", time="2026-11-09T10:09:00", msg="reader dump"),
        ])
        self.assertEqual(["2"], [r["card"] for r in rows])

    def test_a_skipped_card_is_no_answer(self):
        text, rows = run([verdict(1, "wrong", 2000, 1984, "T", "A", "T", "A", time="t1"), verdict(1, "skipped", time="t2")])
        self.assertEqual(["1"], [r["card"] for r in rows])
        self.assertIn("1 skipped", text)

    def test_events_of_the_first_version_are_read_as_year_only(self):
        _, rows = run([
            verdict(5, "wrong", 2008, 1984, "Song", "Band", old=True),
            verdict(6, "match", old=True),
        ])
        self.assertEqual(1, len(rows))
        self.assertEqual("year", rows[0]["changed"])
        self.assertEqual("Song", rows[0]["ourTitle"])
        self.assertEqual("Song", rows[0]["cardTitle"])  # unknown in the first version: unchanged
        self.assertEqual("1984", rows[0]["cardYear"])

    def test_the_amazon_id_is_a_field_of_its_own_and_events_without_it_are_unchanged(self):
        a = rs.normalize(with_ids(verdict(1, "wrong", 2000, 2000, "T", "A", "T", "A"), "", "B006LSV05O")["data"])
        self.assertEqual(["amazonId"], rs.changed(a))
        a = rs.normalize(with_ids(verdict(1, "wrong", 2000, 1984, "T", "A", "T", "A"), "B006LSV05O", "B006LSV05O")["data"])
        self.assertEqual(["year"], rs.changed(a))
        a = rs.normalize(with_ids(verdict(1, "wrong", 2000, 2000, "T", "A", "T", "A"), "B006LSV05O", "")["data"])
        self.assertEqual(["amazonId"], rs.changed(a))
        a = rs.normalize(verdict(1, "wrong", 2000, 1984, "T", "A", "T", "A")["data"])
        self.assertEqual(["year"], rs.changed(a))
        text, rows = run([with_ids(verdict(7, "wrong", 2000, 2000, "T", "A", "T", "A"), "", "B006LSV05O")])
        self.assertEqual("amazonId", rows[0]["changed"])
        self.assertEqual("", rows[0]["ourAmazonId"])
        self.assertEqual("B006LSV05O", rows[0]["cardAmazonId"])
        self.assertIn("amazon: (none) -> B006LSV05O", text)

    def test_the_source_of_the_answer_is_in_the_csv_and_old_events_are_from_the_reader(self):
        _, rows = run([
            verdict(1, "wrong", 2000, 1984, "T", "A", "T", "A", source="scan"),
            verdict(2, "wrong", 2000, 1999, "T", "A", "T", "A", source="quiz"),
            verdict(3, "wrong", 2000, 1998, "T", "A", "T", "A"),               # no source: the reader
            verdict(4, "wrong", 2008, 1984, "S", "B", old=True),                # the first version
        ])
        self.assertEqual({"1": "scan", "2": "quiz", "3": "reader", "4": "reader"}, {r["card"]: r["source"] for r in rows})

    def test_a_wrong_answer_that_changes_nothing_is_no_mismatch(self):
        _, rows = run([verdict(1, "wrong", 2000, 2000, "T", "A", "T", "A")])
        self.assertEqual([], rows)

    def test_the_card_year_may_be_unchanged_while_the_title_differs(self):
        _, rows = run([verdict(3, "wrong", 1990, 1990, "Old", "A", "New", "A")])
        self.assertEqual("title", rows[0]["changed"])


if __name__ == "__main__":
    unittest.main()
