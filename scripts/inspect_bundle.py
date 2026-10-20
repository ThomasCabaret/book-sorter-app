#!/usr/bin/env python3
"""Validate a BookDuels tar.gz and optionally extract active pairwise votes.

Offline, read-only, dependency-free. No archive members are extracted to disk.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import pathlib
import sys
import tarfile

ALLOWED = {"manifest.json", "catalog.json", "events.jsonl", "README.txt"}
MAX_MEMBER = 64 * 1024 * 1024


def read_bundle(path: pathlib.Path):
    with tarfile.open(path, "r:gz") as archive:
        members = archive.getmembers()
        names = [m.name for m in members]
        required = {"manifest.json", "catalog.json", "events.jsonl", "README.txt"}
        if set(names) != required or len(names) != len(required):
            raise ValueError(f"Unexpected archive members: {names}")
        if any(not m.isfile() or m.size > MAX_MEMBER for m in members):
            raise ValueError("Unsupported tar entry or oversized member")
        content = {m.name: archive.extractfile(m).read() for m in members}
    manifest = json.loads(content["manifest.json"])
    if manifest.get("format") != "bookduels.bundle.v1":
        raise ValueError("Incompatible bundle format")
    declared = manifest.get("files")
    if set(declared or {}) != required - {"manifest.json"}:
        raise ValueError("Manifest lists unexpected files")
    for name, meta in declared.items():
        payload = content[name]
        if meta.get("bytes") != len(payload) or meta.get("sha256") != hashlib.sha256(payload).hexdigest():
            raise ValueError(f"SHA-256/size mismatch: {name}")
    books = json.loads(content["catalog.json"])
    if not isinstance(books, list):
        raise ValueError("catalog.json must be a list")
    by_id = {}
    isbn_to_id = {}
    ref_to_id = {}
    for book in books:
        book_id = book["id"]
        if not isinstance(book_id, str) or not book_id or book_id in by_id:
            raise ValueError("Duplicate/invalid book ID")
        if not all(isinstance(book[key], str) and book[key] for key in ("title", "author")):
            raise ValueError("Invalid book metadata")
        if not isinstance(book["enabled"], bool):
            raise ValueError("Invalid enabled flag")
        by_id[book_id] = book
        isbns = book.get("isbnAliases", [])
        if book.get("isbn13") and book["isbn13"] not in isbns:
            raise ValueError("Primary ISBN missing in alias list")
        for isbn in isbns:
            if (not isinstance(isbn, str) or len(isbn) != 13 or not isbn.isascii() or not isbn.isdigit()
                    or sum(int(digit) * (1 if idx % 2 == 0 else 3)
                           for idx, digit in enumerate(isbn)) % 10 != 0):
                raise ValueError("Invalid ISBN alias")
            if isbn in isbn_to_id:
                raise ValueError("Repeated ISBN alias")
            isbn_to_id[isbn] = book_id
        for ref in book.get("externalRefs", []):
            if not isinstance(ref, str) or not ref or ref in ref_to_id:
                raise ValueError("Duplicate/invalid external reference")
            ref_to_id[ref] = book_id
    events = [json.loads(line) for line in content["events.jsonl"].splitlines() if line]
    if len(books) != manifest["books"] or len(events) != manifest["events"]:
        raise ValueError("Manifest counts do not match data")
    event_ids = set()
    bases = {}
    active = {}
    last_seq = 0
    for e in events:
        seq = e["seq"]
        if not isinstance(seq, int) or seq <= last_seq:
            raise ValueError("Event sequence is not strictly increasing")
        last_seq = seq
        event_id = e["eventId"]
        if event_id in event_ids:
            raise ValueError("Repeated eventId")
        event_ids.add(event_id)
        if not isinstance(e["timestampMs"], int):
            raise ValueError("Invalid timestampMs")
        kind = e["kind"]
        if kind in ("VOTE", "JOKER"):
            first = e.get("firstBookId")
            second = e.get("secondBookId")
            if first not in by_id or second not in by_id or first == second:
                raise ValueError("Vote/Joker references an unknown or repeated book")
            winner = e.get("chosenBookId")
            if kind == "VOTE" and winner not in (first, second):
                raise ValueError("Vote winner not in pair")
            if kind == "JOKER" and winner is not None:
                raise ValueError("Joker must have no winner")
            bases[seq] = e
            active[seq] = True
        elif kind in ("INVALIDATE", "REINSTATE"):
            target = e.get("targetSeq")
            if not isinstance(target, int) or target not in bases:
                raise ValueError("History action refers to an unknown future/non-base event")
            if not e.get("batchId"):
                raise ValueError("History action missing batchId")
            active[target] = kind == "REINSTATE"
        else:
            raise ValueError(f"Unknown event kind: {kind}")
    return books, events, bases, active


def active_pairwise(bases, active):
    counters = collections.defaultdict(lambda: [0, 0, 0])
    for seq, event in bases.items():
        if not active[seq]:
            continue
        pair = tuple(sorted((event["firstBookId"], event["secondBookId"])))
        counts = counters[pair]
        if event["kind"] == "JOKER":
            counts[2] += 1
        elif event["chosenBookId"] == pair[0]:
            counts[0] += 1
        else:
            counts[1] += 1
    return [dict(firstBookId=a, secondBookId=b, firstWins=w1, secondWins=w2, jokers=j)
            for (a, b), (w1, w2, j) in sorted(counters.items())]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=pathlib.Path, help="Android-exported *.tar.gz")
    parser.add_argument("--emit-pairs", type=pathlib.Path, help="Write active pairwise preferences JSON")
    args = parser.parse_args()
    try:
        books, events, bases, active = read_bundle(args.bundle)
        pairs = active_pairwise(bases, active)
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError, json.JSONDecodeError) as e:
        print(f"INVALID bundle: {e}", file=sys.stderr)
        return 1
    active_events = [e for seq, e in bases.items() if active[seq]]
    print(f"VALID: {len(books)} catalog books, {len(events)} immutable events")
    print(f"Active: {sum(e['kind'] == 'VOTE' for e in active_events)} votes, "
          f"{sum(e['kind'] == 'JOKER' for e in active_events)} jokers, "
          f"{sum(a['firstWins'] + a['secondWins'] > 0 for a in pairs)} compared pairs")
    if args.emit_pairs:
        data = {"format": "bookduels.pairwise.v1", "bookCount": len(books), "pairs": pairs}
        args.emit_pairs.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Pairwise preferences written to {args.emit_pairs}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
