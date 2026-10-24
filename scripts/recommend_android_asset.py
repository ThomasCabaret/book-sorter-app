#!/usr/bin/env python3
"""Reference implementation of the exact compact Android recommender."""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import struct

import numpy as np

from inspect_bundle import active_pairwise, read_bundle
from recommend_goodbooks import normalize_author, normalize_text


def read_text(source) -> str:
    length, = struct.unpack(">H", source.read(2))
    payload = source.read(length)
    if len(payload) != length:
        raise ValueError("Truncated recommendation string")
    return payload.decode("utf-8")


def read_asset(path: pathlib.Path) -> dict:
    with path.open("rb") as source:
        if source.read(8) != b"BDREC001":
            raise ValueError("Unsupported recommendation asset")
        item_count, dimensions, alias_count = struct.unpack(">III", source.read(12))
        aliases = {read_text(source): struct.unpack(">I", source.read(4))[0] for _ in range(alias_count)}
        factors = np.empty((item_count, dimensions), dtype=np.float32)
        items = []
        for expected in range(1, item_count + 1):
            payload = source.read(28)
            if len(payload) != 28:
                raise ValueError("Truncated recommendation item")
            book_id, work_id, flags, bias, average, rating_count = struct.unpack(">IqIffi", payload)
            if book_id != expected:
                raise ValueError("Non-contiguous recommendation item IDs")
            isbn, title, authors = read_text(source), read_text(source), read_text(source)
            factor_bytes = source.read(dimensions * 2)
            if len(factor_bytes) != dimensions * 2:
                raise ValueError("Truncated recommendation factors")
            factors[expected - 1] = np.frombuffer(factor_bytes, dtype=">f2").astype(np.float32)
            items.append({
                "bookId": book_id, "workId": work_id, "candidate": bool(flags & 1),
                "bias": bias, "averageRating": average, "ratingCount": rating_count,
                "isbn13": isbn or None, "title": title, "authors": authors,
            })
        if source.read(1):
            raise ValueError("Trailing recommendation asset data")
    return {"dimensions": dimensions, "aliases": aliases, "items": items, "factors": factors}


def key(title: str, author: str) -> tuple[str, str]:
    return normalize_text(title), normalize_author(author)


SERIES_PATTERN = re.compile(r"\(([^()]*)#\s*([0-9]+(?:\.[0-9]+)?)[^()]*\)")


def series_info(title: str) -> tuple[str, float] | None:
    match = SERIES_PATTERN.search(title)
    if not match:
        return None
    series = normalize_text(match.group(1).rstrip(", "))
    return (series, float(match.group(2))) if series else None


def build_report(
    bundle: pathlib.Path, asset_path: pathlib.Path, limit: int, popularity_weight: float,
) -> dict:
    catalog, _events, bases, active = read_bundle(bundle)
    pairs = active_pairwise(bases, active)
    asset = read_asset(asset_path)
    items, factors = asset["items"], asset["factors"]
    aliases = asset["aliases"]
    enabled = {book["id"] for book in catalog if book.get("enabled", True)}
    local_to_item = {book["id"]: aliases[book["id"]] for book in catalog if book["id"] in aliases}

    work_owners: dict[int, list[str]] = {}
    for local_id, item_index in local_to_item.items():
        if local_id in enabled and items[item_index]["workId"]:
            work_owners.setdefault(items[item_index]["workId"], []).append(local_id)
    collisions = {local_id for owners in work_owners.values() if len(owners) > 1 for local_id in owners}
    local_to_item = {key_: value for key_, value in local_to_item.items() if key_ not in collisions}

    gradient = np.zeros(asset["dimensions"], dtype=np.float32)
    mapped_votes = 0
    for pair in pairs:
        first_id, second_id = pair["firstBookId"], pair["secondBookId"]
        if first_id not in enabled or second_id not in enabled:
            continue
        first, second = local_to_item.get(first_id), local_to_item.get(second_id)
        if first is None or second is None or first == second:
            continue
        for winner, loser, count in (
            (first, second, pair["firstWins"]), (second, first, pair["secondWins"]),
        ):
            if not count:
                continue
            margin = items[winner]["bias"] - items[loser]["bias"]
            multiplier = -1.0 / (1.0 + np.exp(margin))
            gradient += count * multiplier * (factors[winner] - factors[loser])
            mapped_votes += count
    if mapped_votes == 0:
        raise ValueError("No mapped active vote")
    gradient /= mapped_votes
    personal = -0.01 * gradient / (np.abs(gradient) + 1e-8)

    catalog_works = {items[index]["workId"] for index in local_to_item.values()} - {0}
    catalog_isbns = {book.get("isbn13") for book in catalog if book.get("isbn13")}
    catalog_keys = {key(book["title"], book["author"]) for book in catalog}
    known_series = {
        info[0] for index in local_to_item.values()
        if (info := series_info(items[index]["title"])) is not None
    }
    recommendations = []
    for index, item in enumerate(items):
        if not item["candidate"] or item["workId"] in catalog_works:
            continue
        if item["isbn13"] in catalog_isbns or key(item["title"], item["authors"]) in catalog_keys:
            continue
        series = series_info(item["title"])
        if series is not None and series[1] > 1 and series[0] not in known_series:
            continue
        personal_score = float(factors[index] @ personal)
        score = float(popularity_weight * item["bias"] + personal_score)
        recommendations.append({**item, "score": score, "personalScore": personal_score})
    recommendations.sort(key=lambda item: (-item["score"], -item["ratingCount"], item["bookId"]))
    return {
        "format": "bookduels.android-recommendations.v1",
        "matchedCatalogBooks": len(local_to_item),
        "mappedActiveVotes": mapped_votes,
        "eligibleCandidates": len(recommendations),
        "popularityWeight": popularity_weight,
        "recommendations": recommendations[:limit],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=pathlib.Path)
    parser.add_argument("--asset", required=True, type=pathlib.Path)
    parser.add_argument("--limit", type=int, default=40)
    parser.add_argument("--popularity-weight", type=float, default=0.1)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        if not 0 <= args.popularity_weight <= 1:
            raise ValueError("Popularity weight must be between zero and one")
        report = build_report(args.bundle, args.asset, args.limit, args.popularity_weight)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, struct.error, json.JSONDecodeError) as error:
        print(f"Android recommendation failed: {error}")
        return 1
    print(
        f"Wrote {len(report['recommendations'])} recommendations from "
        f"{report['mappedActiveVotes']} mapped votes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
