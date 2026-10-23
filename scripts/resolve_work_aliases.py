#!/usr/bin/env python3
"""Resolve BookDuels edition ISBNs to goodbooks-10k Goodreads work IDs.

Open Library Search is queried in small batches and cached locally. Resolution
is conservative: a Goodreads edition-ID intersection wins; otherwise an exact
normalized title AND author match must be unique. Ambiguities are reported and
never silently merged.
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from recommend_goodbooks import (
    load_books,
    load_work_aliases,
    normalize_author,
    normalize_text,
    valid_isbn13,
)

CACHE_FORMAT = "bookduels.openlibrary-resolution-cache.v1"
REPORT_FORMAT = "bookduels.work-resolution.v1"
SEARCH_URL = "https://openlibrary.org/search.json"
USER_AGENT = "BookDuels/0.6 (+https://github.com/ThomasCabaret/book-sorter-app)"


def catalog_isbns(catalog: list[dict]) -> list[str]:
    values = set()
    for book in catalog:
        candidates = set(book.get("isbnAliases", []))
        if book.get("isbn13"):
            candidates.add(book["isbn13"])
        values.update(value for value in candidates if valid_isbn13(value))
    return sorted(values)


def load_cache(path: pathlib.Path) -> tuple[set[str], dict[str, dict]]:
    if not path.exists():
        return set(), {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("format") != CACHE_FORMAT:
        raise ValueError("Unsupported Open Library cache format")
    queried = set(payload.get("queriedIsbns", []))
    docs = {doc["key"]: doc for doc in payload.get("docs", []) if doc.get("key")}
    return queried, docs


def save_cache(path: pathlib.Path, queried: set[str], docs: dict[str, dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "format": CACHE_FORMAT,
        "queriedIsbns": sorted(queried),
        "docs": [docs[key] for key in sorted(docs)],
    }
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def fetch_batch(isbns: list[str], attempts: int = 3) -> list[dict]:
    query = "isbn:(" + " OR ".join(isbns) + ")"
    params = urllib.parse.urlencode({
        "q": query,
        "fields": "key,title,author_name,isbn,id_goodreads",
        "limit": max(20, len(isbns) * 2),
    })
    request = urllib.request.Request(
        f"{SEARCH_URL}?{params}",
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
            if not isinstance(payload.get("docs"), list):
                raise ValueError("Open Library response has no docs list")
            return payload["docs"]
        except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError):
            if attempt + 1 == attempts:
                raise
            time.sleep(2 ** attempt)
    return []


def resolve_catalog(
    catalog: list[dict],
    openlibrary_docs: list[dict],
    goodbooks: dict,
    title_author_index: dict[tuple[str, str], list[int]],
    reviewed_work_aliases: dict[str, int] | None = None,
) -> dict:
    reviewed_work_aliases = reviewed_work_aliases or {}
    goodreads_to_dataset = {}
    work_to_dataset = {}
    for dataset_id, meta in goodbooks.items():
        goodreads_to_dataset.setdefault(meta.goodreads_book_id, set()).add(dataset_id)
        goodreads_to_dataset.setdefault(meta.best_book_id, set()).add(dataset_id)
        if meta.work_id is not None:
            work_to_dataset.setdefault(meta.work_id, set()).add(dataset_id)

    catalog_ids = {book["id"] for book in catalog}
    unknown_local_ids = sorted(set(reviewed_work_aliases) - catalog_ids)
    if unknown_local_ids:
        raise ValueError(f"Reviewed alias has unknown BookDuels ID: {unknown_local_ids[0]}")
    for local_id, work_id in reviewed_work_aliases.items():
        if len(work_to_dataset.get(work_id, set())) != 1:
            raise ValueError(f"Reviewed alias for {local_id} has unknown or non-unique work ID {work_id}")

    docs_by_isbn = {}
    for doc in openlibrary_docs:
        for isbn in doc.get("isbn", []):
            if isinstance(isbn, str):
                docs_by_isbn.setdefault(isbn, []).append(doc)

    resolutions = []
    for book in catalog:
        aliases = set(book.get("isbnAliases", []))
        if book.get("isbn13"):
            aliases.add(book["isbn13"])
        docs = {doc["key"]: doc for isbn in aliases for doc in docs_by_isbn.get(isbn, [])}.values()
        id_candidates = set()
        title_candidates = set()
        openlibrary_keys = set()
        for doc in docs:
            openlibrary_keys.add(doc["key"])
            for external_id in doc.get("id_goodreads", []):
                try:
                    id_candidates.update(goodreads_to_dataset.get(int(external_id), set()))
                except (TypeError, ValueError):
                    continue
            title = doc.get("title") or ""
            for author in doc.get("author_name", []):
                key = (normalize_text(title), normalize_author(author))
                matches = title_author_index.get(key, [])
                if len(matches) == 1:
                    title_candidates.add(matches[0])

        reviewed_work_id = reviewed_work_aliases.get(book["id"])
        if reviewed_work_id is not None:
            candidates = work_to_dataset[reviewed_work_id]
            evidence = "reviewed-work-alias"
        else:
            candidates = id_candidates if id_candidates else title_candidates
            evidence = "goodreads-edition-id" if id_candidates else "openlibrary-title-author"
        if len(candidates) == 1:
            dataset_id = next(iter(candidates))
            meta = goodbooks[dataset_id]
            status = "matched"
            work_id = meta.work_id
        elif len(candidates) > 1:
            status = "ambiguous"
            work_id = None
        else:
            status = "unmatched"
            work_id = None
            evidence = None
        resolutions.append({
            "bookduelsBookId": book["id"],
            "title": book["title"],
            "author": book["author"],
            "enabled": bool(book.get("enabled", True)),
            "isbn13": book.get("isbn13"),
            "status": status,
            "goodreadsWorkId": work_id,
            "goodbooksBookIds": sorted(candidates),
            "goodbooksTitle": meta.title if len(candidates) == 1 else None,
            "goodbooksAuthors": meta.authors if len(candidates) == 1 else None,
            "openlibraryWorkKeys": sorted(openlibrary_keys),
            "evidence": evidence,
        })

    enabled_by_work = {}
    for item in resolutions:
        if item["status"] == "matched" and item["enabled"]:
            enabled_by_work.setdefault(item["goodreadsWorkId"], []).append(item)
    for work_id, items in enabled_by_work.items():
        if len(items) > 1:
            for item in items:
                item["status"] = "ambiguous-local-collision"
                item["evidence"] = f"multiple enabled BookDuels records map to Goodreads work {work_id}"

    return {
        "format": REPORT_FORMAT,
        "catalogBooks": len(catalog),
        "matched": sum(item["status"] == "matched" for item in resolutions),
        "ambiguous": sum(item["status"].startswith("ambiguous") for item in resolutions),
        "unmatched": sum(item["status"] == "unmatched" for item in resolutions),
        "resolutions": resolutions,
    }


def write_alias_csv(path: pathlib.Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(["bookduels_book_id", "goodreads_work_id"])
        for item in report["resolutions"]:
            if item["status"] == "matched" and item["enabled"]:
                writer.writerow([item["bookduelsBookId"], item["goodreadsWorkId"]])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=pathlib.Path, help="BookDuels catalog JSON array")
    parser.add_argument("--books", required=True, type=pathlib.Path, help="goodbooks-10k books.csv")
    parser.add_argument("--cache", required=True, type=pathlib.Path, help="Ignored Open Library response cache")
    parser.add_argument("--report", required=True, type=pathlib.Path, help="Detailed JSON resolution report")
    parser.add_argument("--output", required=True, type=pathlib.Path, help="Resolved work-alias CSV")
    parser.add_argument(
        "--reviewed-aliases", type=pathlib.Path,
        help="Versioned, human-reviewed BookDuels ID to Goodreads work-ID CSV",
    )
    parser.add_argument("--batch-size", type=int, default=20)
    parser.add_argument("--delay", type=float, default=1.1, help="Seconds between API requests")
    parser.add_argument("--offline", action="store_true", help="Use cache only")
    args = parser.parse_args()
    try:
        if args.batch_size < 1 or args.batch_size > 50 or args.delay < 0:
            raise ValueError("Invalid batch size or delay")
        catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
        if not isinstance(catalog, list):
            raise ValueError("Catalog JSON must be an array")
        queried, cached_docs = load_cache(args.cache)
        wanted = catalog_isbns(catalog)
        missing = [isbn for isbn in wanted if isbn not in queried]
        if missing and args.offline:
            raise ValueError(f"Cache is missing {len(missing)} ISBN values")
        for start in range(0, len(missing), args.batch_size):
            batch = missing[start:start + args.batch_size]
            for doc in fetch_batch(batch):
                if doc.get("key"):
                    cached_docs[doc["key"]] = doc
            queried.update(batch)
            save_cache(args.cache, queried, cached_docs)
            print(f"Open Library: {min(start + len(batch), len(missing))}/{len(missing)} new ISBNs")
            if start + len(batch) < len(missing):
                time.sleep(args.delay)
        books, _by_isbn, by_title_author, _by_work = load_books(args.books)
        reviewed_aliases = load_work_aliases(args.reviewed_aliases) if args.reviewed_aliases else {}
        report = resolve_catalog(
            catalog, list(cached_docs.values()), books, by_title_author, reviewed_aliases
        )
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        write_alias_csv(args.output, report)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, csv.Error) as error:
        print(f"Work resolution failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Resolved: {report['matched']} matched, {report['ambiguous']} ambiguous, "
        f"{report['unmatched']} unmatched -> {args.output}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
