#!/usr/bin/env python3
"""Build the compact, attributed recommendation asset consumed by Android.

The output contains frozen item factors and public book metadata, never raw
ratings or BookDuels events. Personalization remains an on-device calculation.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import pathlib
import re
import struct

import numpy as np

from recommend_goodbooks import (
    DEFAULT_GENRE_TAGS,
    load_books,
    load_work_aliases,
    match_catalog,
)
from recommend_latent import load_model


MAGIC = b"BDREC002"
FORMAT = "bookduels.android-recommender.v2"
EXCLUDED_TAGS = {
    "art", "biography", "childrens", "childrens-books", "children",
    "comic", "comics", "graphic-novel", "graphic-novels", "history",
    "drama", "manga", "memoir", "non-fiction", "nonfiction", "picture-book",
    "picture-books", "plays", "poetry", "sequential-art", "superhero", "superheroes",
    "theatre",
}
EXCLUDED_TITLE_PARTS = (
    " box set", " boxset", " boxed set", " complete collection",
    " complete edition", " complete series", " complete works", " collected ",
    " collection", " omnibus", " treasury", " trilogy", "ultimate hitchhiker",
)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tag_strengths(
    tags_path: pathlib.Path, book_tags_path: pathlib.Path,
) -> tuple[dict[int, int], dict[int, int]]:
    with tags_path.open("r", encoding="utf-8-sig", newline="") as source:
        tag_names = {
            int(row["tag_id"]): row["tag_name"].strip().casefold()
            for row in csv.DictReader(source)
        }
    positive: dict[int, int] = {}
    excluded: dict[int, int] = {}
    with book_tags_path.open("r", encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            external_id = int(row["goodreads_book_id"])
            count = int(row["count"])
            name = tag_names.get(int(row["tag_id"]), "")
            if name in DEFAULT_GENRE_TAGS:
                positive[external_id] = positive.get(external_id, 0) + count
            if name in EXCLUDED_TAGS:
                excluded[external_id] = excluded.get(external_id, 0) + count
    return positive, excluded


def write_text(output, text: str) -> None:
    payload = text.encode("utf-8")
    if len(payload) > 65535:
        raise ValueError("Recommendation metadata string is too long")
    output.write(struct.pack(">H", len(payload)))
    output.write(payload)


def load_candidate_exclusions(path: pathlib.Path, catalog_ids: set[str], work_ids: set[int]) -> set[int]:
    excluded = set()
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required = {"bookduels_book_id", "goodreads_work_id"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise ValueError("Candidate-exclusion CSV has invalid columns")
        for row in reader:
            local_id = row["bookduels_book_id"].strip()
            work_id = int(row["goodreads_work_id"])
            if (local_id and local_id not in catalog_ids) or work_id not in work_ids:
                raise ValueError(f"Unknown candidate exclusion: {local_id} -> {work_id}")
            excluded.add(work_id)
    return excluded


def build(args: argparse.Namespace) -> dict:
    model = load_model(args.model)
    books, by_isbn, by_title_author, by_work = load_books(args.books)
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    aliases = load_work_aliases(args.work_aliases)
    local_to_dataset, _catalog_ids, ambiguous = match_catalog(
        catalog, by_isbn, by_title_author, by_work, aliases
    )
    if ambiguous:
        raise ValueError(f"Ambiguous catalog records: {ambiguous}")
    item_count, dimensions = model["item_factors"].shape
    if item_count != max(books) or set(books) != set(range(1, item_count + 1)):
        raise ValueError("Android asset requires contiguous goodbooks book IDs")
    positive, excluded = tag_strengths(args.tags, args.book_tags)
    known_work_exclusions = load_candidate_exclusions(
        args.candidate_exclusions,
        {book["id"] for book in catalog},
        {meta.work_id for meta in books.values() if meta.work_id is not None},
    )

    candidate = np.zeros(item_count, dtype=np.uint8)
    for book_id, meta in books.items():
        positive_count = positive.get(meta.goodreads_book_id, 0)
        excluded_count = excluded.get(meta.goodreads_book_id, 0)
        title_key = f" {meta.title.casefold()}"
        title_excluded = " / " in meta.title or any(part in title_key for part in EXCLUDED_TITLE_PARTS) or bool(
            re.search(r"#\d+\s*-\s*\d+", title_key)
        )
        # A stray user tag must not exclude a novel. Exclude only when unwanted
        # categories carry substantial weight relative to the relevant genres.
        if (
            positive_count > 0 and excluded_count * 50 <= positive_count and
            not title_excluded and meta.work_id not in known_work_exclusions
        ):
            candidate[book_id - 1] = 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as output:
        output.write(MAGIC)
        output.write(struct.pack(">III", item_count, dimensions, len(local_to_dataset)))
        for local_id, dataset_id in sorted(local_to_dataset.items()):
            write_text(output, local_id)
            output.write(struct.pack(">I", dataset_id - 1))
        factors = model["item_factors"].astype(np.float16, copy=False)
        for item_index in range(item_count):
            meta = books[item_index + 1]
            output.write(struct.pack(
                ">IqqIffi",
                item_index + 1,
                int(meta.work_id or 0),
                int(meta.best_book_id),
                int(candidate[item_index]),
                float(model["item_bias"][item_index]),
                float(meta.average_rating),
                int(model["item_rating_count"][item_index]),
            ))
            write_text(output, meta.isbn13 or "")
            write_text(output, meta.title)
            write_text(output, meta.authors)
            raw_half = factors[item_index].view(np.uint16).astype(">u2", copy=False)
            output.write(raw_half.tobytes())

    manifest = {
        "format": FORMAT,
        "independentOfBookDuelsVotes": True,
        "source": {
            "name": "goodbooks-10k v1.0",
            "url": "https://github.com/zygmuntz/goodbooks-10k",
            "license": "CC BY-SA 4.0",
        },
        "modelSha256": sha256(args.model),
        "assetSha256": sha256(args.output),
        "assetBytes": args.output.stat().st_size,
        "items": item_count,
        "dimensions": dimensions,
        "candidateItems": int(candidate.sum()),
        "catalogAliases": len(local_to_dataset),
        "candidatePolicy": {
            "positiveTags": sorted(DEFAULT_GENRE_TAGS),
            "excludedTags": sorted(EXCLUDED_TAGS),
            "excludedTagStrengthAtMostTwoPercentOfPositive": True,
            "excludedTitleParts": list(EXCLUDED_TITLE_PARTS),
            "excludeMultiVolumeSeriesRanges": True,
            "knownCatalogWorkExclusions": sorted(known_work_exclusions),
        },
        "phoneInference": {
            "personalization": "one full-batch AdamW step from zero",
            "personalizationLearningRate": 0.01,
            "populationPriorWeight": 0.1,
        },
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=pathlib.Path)
    parser.add_argument("--books", required=True, type=pathlib.Path)
    parser.add_argument("--tags", required=True, type=pathlib.Path)
    parser.add_argument("--book-tags", required=True, type=pathlib.Path)
    parser.add_argument("--catalog", required=True, type=pathlib.Path)
    parser.add_argument("--work-aliases", required=True, type=pathlib.Path)
    parser.add_argument("--candidate-exclusions", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    return parser.parse_args()


def main() -> int:
    try:
        manifest = build(parse_args())
    except (OSError, ValueError, KeyError, csv.Error, json.JSONDecodeError) as error:
        print(f"Android recommendation asset failed: {error}")
        return 1
    print(
        f"Wrote {manifest['assetBytes']:,} bytes: {manifest['items']} items, "
        f"{manifest['candidateItems']} candidates, {manifest['catalogAliases']} aliases"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
