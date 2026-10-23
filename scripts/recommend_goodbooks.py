#!/usr/bin/env python3
"""Build compact book recommendations from a BookDuels export and goodbooks-10k.

The raw third-party dataset stays on the desktop. The output is a small JSON
report that can be inspected or, after a future reviewed import design, passed
to the Android app. This script is offline and dependency-free.
"""
from __future__ import annotations

import argparse
import collections
import csv
import heapq
import json
import math
import pathlib
import re
import sys
import unicodedata
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from inspect_bundle import active_pairwise, read_bundle

DEFAULT_GENRE_TAGS = {
    "dark-fantasy", "dystopia", "dystopian", "epic-fantasy", "fantasy",
    "fantasy-sci-fi", "high-fantasy", "horror", "paranormal", "sci-fi",
    "science-fiction", "science-fiction-and-fantasy",
    "science-fiction-fantasy", "speculative-fiction", "urban-fantasy",
}
PRIOR_PRECISION = 1.5
SIMILARITY_SHRINKAGE = 12.0


@dataclass(frozen=True)
class BookMeta:
    book_id: int
    goodreads_book_id: int
    best_book_id: int
    work_id: int | None
    isbn13: str | None
    title: str
    original_title: str
    authors: str
    average_rating: float


def valid_isbn13(value: str) -> bool:
    return len(value) == 13 and value.isascii() and value.isdigit() and sum(
        int(digit) * (1 if index % 2 == 0 else 3)
        for index, digit in enumerate(value)
    ) % 10 == 0


def normalize_isbn13(raw: str | None) -> str | None:
    """Normalize plain or spreadsheet-style scientific-notation ISBN-13."""
    text = (raw or "").strip()
    if not text:
        return None
    digits = "".join(character for character in text if character.isdigit())
    if valid_isbn13(digits):
        return digits
    try:
        number = Decimal(text)
        integral = number.to_integral_value()
        if number != integral:
            return None
        candidate = format(integral, "f")
    except InvalidOperation:
        return None
    if valid_isbn13(candidate):
        return candidate
    # goodbooks-10k serialized many ISBN-13 values through a floating-point
    # scientific notation with only the first twelve significant digits. The
    # lost final digit is the ISBN checksum and is therefore exactly recoverable.
    if len(candidate) == 13 and candidate.isdigit():
        prefix = candidate[:12]
        checksum = (-sum(
            int(digit) * (1 if index % 2 == 0 else 3)
            for index, digit in enumerate(prefix)
        )) % 10
        recovered = prefix + str(checksum)
        return recovered if valid_isbn13(recovered) else None
    return None


def normalize_text(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value.casefold())
    without_marks = "".join(character for character in decomposed if not unicodedata.combining(character))
    return " ".join(re.sub(r"[^a-z0-9]+", " ", without_marks).split())


def normalize_author(value: str) -> str:
    """Tolerate Babelio's surname-first ordering without fuzzy name matching."""
    return " ".join(sorted(normalize_text(value).split()))


def logistic(value: float) -> float:
    if value >= 0.0:
        return 1.0 / (1.0 + math.exp(-value))
    exp_value = math.exp(value)
    return exp_value / (1.0 + exp_value)


def fit_bradley_terry(book_ids: list[str], pairs: list[dict]) -> dict[str, float]:
    """Mirror the app's regularized Bradley-Terry fit on aggregate pair counts."""
    ordered = sorted(book_ids)
    index = {book_id: position for position, book_id in enumerate(ordered)}
    observations = []
    for pair in pairs:
        first = pair["firstBookId"]
        second = pair["secondBookId"]
        if first not in index or second not in index:
            continue
        observations.append((
            index[first], index[second], int(pair["firstWins"]), int(pair["secondWins"])
        ))
    theta = [0.0] * len(ordered)
    for _ in range(300):
        gradient = [-PRIOR_PRECISION * value for value in theta]
        hessian = [PRIOR_PRECISION] * len(theta)
        for first, second, first_wins, second_wins in observations:
            total = first_wins + second_wins
            if total == 0:
                continue
            probability = logistic(theta[first] - theta[second])
            information = total * probability * (1.0 - probability)
            gradient[first] += first_wins - total * probability
            gradient[second] += second_wins - total * (1.0 - probability)
            hessian[first] += information
            hessian[second] += information
        change = 0.0
        for position in range(len(theta)):
            delta = 0.6 * gradient[position] / hessian[position]
            theta[position] += delta
            change = max(change, abs(delta))
        if change < 1e-8:
            break
    return dict(zip(ordered, theta))


def load_books(path: pathlib.Path) -> tuple[
    dict[int, BookMeta], dict[str, list[int]], dict[tuple[str, str], list[int]], dict[int, list[int]]
]:
    books = {}
    by_isbn = collections.defaultdict(list)
    by_title_author = collections.defaultdict(list)
    by_work = collections.defaultdict(list)
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required = {"book_id", "goodreads_book_id", "work_id", "isbn13", "title", "authors"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise ValueError(f"books.csv missing columns: {sorted(required - set(reader.fieldnames or []))}")
        for row in reader:
            book_id = int(row["book_id"])
            isbn13 = normalize_isbn13(row.get("isbn13"))
            meta = BookMeta(
                book_id=book_id,
                goodreads_book_id=int(row["goodreads_book_id"]),
                best_book_id=int(row.get("best_book_id") or row["goodreads_book_id"]),
                work_id=int(row["work_id"]) if row.get("work_id") else None,
                isbn13=isbn13,
                title=row["title"].strip(),
                original_title=(row.get("original_title") or "").strip(),
                authors=row["authors"].strip(),
                average_rating=float(row.get("average_rating") or 0.0),
            )
            books[book_id] = meta
            if isbn13:
                by_isbn[isbn13].append(book_id)
            author_key = normalize_author(meta.authors)
            for candidate_title in {meta.title, meta.original_title} - {""}:
                by_title_author[(normalize_text(candidate_title), author_key)].append(book_id)
            if meta.work_id is not None:
                by_work[meta.work_id].append(book_id)
    return books, dict(by_isbn), dict(by_title_author), dict(by_work)


def load_work_aliases(path: pathlib.Path) -> dict[str, int]:
    aliases = {}
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required = {"bookduels_book_id", "goodreads_work_id"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise ValueError(f"work-alias CSV missing columns: {sorted(required - set(reader.fieldnames or []))}")
        for row in reader:
            book_id = row["bookduels_book_id"].strip()
            work_id = int(row["goodreads_work_id"])
            if not book_id or book_id in aliases:
                raise ValueError("Duplicate or empty BookDuels ID in work-alias CSV")
            aliases[book_id] = work_id
    return aliases


def load_genre_goodreads_ids(
    tags_path: pathlib.Path,
    book_tags_path: pathlib.Path,
    allowed_tags: set[str],
) -> set[int]:
    wanted_ids = set()
    with tags_path.open("r", encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            if row["tag_name"].strip().lower() in allowed_tags:
                wanted_ids.add(int(row["tag_id"]))
    goodreads_ids = set()
    with book_tags_path.open("r", encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            if int(row["tag_id"]) in wanted_ids and int(row.get("count") or 0) > 0:
                goodreads_ids.add(int(row["goodreads_book_id"]))
    return goodreads_ids


def match_catalog(
    catalog: list[dict],
    isbn_to_dataset_ids: dict[str, list[int]],
    title_author_to_dataset_ids: dict[tuple[str, str], list[int]] | None = None,
    work_to_dataset_ids: dict[int, list[int]] | None = None,
    explicit_work_aliases: dict[str, int] | None = None,
) -> tuple[dict[str, int], set[int], list[str]]:
    local_to_dataset = {}
    catalog_dataset_ids = set()
    ambiguous = []
    dataset_owners = {}
    title_author_to_dataset_ids = title_author_to_dataset_ids or {}
    work_to_dataset_ids = work_to_dataset_ids or {}
    explicit_work_aliases = explicit_work_aliases or {}
    for book in catalog:
        aliases = set(book.get("isbnAliases", []))
        if book.get("isbn13"):
            aliases.add(book["isbn13"])
        explicit_work_id = explicit_work_aliases.get(book["id"])
        if explicit_work_id is not None:
            matches = set(work_to_dataset_ids.get(explicit_work_id, []))
        else:
            matches = {dataset_id for isbn in aliases for dataset_id in isbn_to_dataset_ids.get(isbn, [])}
            if not matches:
                title_author_key = (normalize_text(book["title"]), normalize_author(book["author"]))
                title_matches = title_author_to_dataset_ids.get(title_author_key, [])
                if len(title_matches) == 1:
                    matches = set(title_matches)
        catalog_dataset_ids.update(matches)
        if len(matches) > 1:
            ambiguous.append(book["id"])
            continue
        if not matches:
            continue
        dataset_id = next(iter(matches))
        previous_owner = dataset_owners.get(dataset_id)
        if previous_owner is not None:
            if previous_owner not in ambiguous:
                ambiguous.append(previous_owner)
            ambiguous.append(book["id"])
            local_to_dataset.pop(previous_owner, None)
            continue
        dataset_owners[dataset_id] = book["id"]
        local_to_dataset[book["id"]] = dataset_id
    return local_to_dataset, catalog_dataset_ids, ambiguous


def map_catalog(
    catalog: list[dict],
    local_scores: dict[str, float],
    isbn_to_dataset_ids: dict[str, list[int]],
    title_author_to_dataset_ids: dict[tuple[str, str], list[int]] | None = None,
    work_to_dataset_ids: dict[int, list[int]] | None = None,
    explicit_work_aliases: dict[str, int] | None = None,
) -> tuple[dict[int, float], set[int], list[str]]:
    local_to_dataset, catalog_dataset_ids, ambiguous = match_catalog(
        catalog, isbn_to_dataset_ids, title_author_to_dataset_ids,
        work_to_dataset_ids, explicit_work_aliases,
    )
    enabled = {book["id"] for book in catalog if book.get("enabled", True)}
    dataset_scores = {
        dataset_id: local_scores[local_id]
        for local_id, dataset_id in local_to_dataset.items()
        if local_id in enabled and local_id in local_scores
    }
    return dataset_scores, catalog_dataset_ids, ambiguous


def order_similarity(profile: list[tuple[int, float]], local_scores: dict[int, float]) -> tuple[float, int]:
    concordant = 0
    discordant = 0
    for left in range(len(profile)):
        left_book, left_rating = profile[left]
        for right in range(left + 1, len(profile)):
            right_book, right_rating = profile[right]
            rating_delta = left_rating - right_rating
            score_delta = local_scores[left_book] - local_scores[right_book]
            if rating_delta == 0.0 or abs(score_delta) < 1e-9:
                continue
            if rating_delta * score_delta > 0.0:
                concordant += 1
            else:
                discordant += 1
    comparable = concordant + discordant
    if comparable == 0:
        return 0.0, 0
    agreement = (concordant - discordant) / comparable
    return agreement * comparable / (comparable + SIMILARITY_SHRINKAGE), comparable


def find_neighbors(
    ratings_path: pathlib.Path,
    local_scores: dict[int, float],
    neighbor_count: int,
    min_overlap: int,
    min_reader_ratings: int = 25,
) -> tuple[dict[int, float], dict[int, float], int]:
    profiles = collections.defaultdict(list)
    totals = collections.defaultdict(lambda: [0.0, 0])
    with ratings_path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        for row in reader:
            user_id = int(row["user_id"])
            book_id = int(row["book_id"])
            rating = float(row["rating"])
            totals[user_id][0] += rating
            totals[user_id][1] += 1
            if book_id in local_scores:
                profiles[user_id].append((book_id, rating))

    heap = []
    eligible = 0
    for user_id, profile in profiles.items():
        if totals[user_id][1] < min_reader_ratings:
            continue
        if len({book_id for book_id, _ in profile}) < min_overlap:
            continue
        similarity, comparable = order_similarity(profile, local_scores)
        if comparable == 0 or similarity <= 0.0:
            continue
        eligible += 1
        item = (similarity, -user_id)
        if len(heap) < neighbor_count:
            heapq.heappush(heap, item)
        elif item > heap[0]:
            heapq.heapreplace(heap, item)
    similarities = {-negative_user: similarity for similarity, negative_user in heap}
    means = {user_id: totals[user_id][0] / totals[user_id][1] for user_id in similarities}
    return similarities, means, eligible


def collect_recommendations(
    ratings_path: pathlib.Path,
    books: dict[int, BookMeta],
    similarities: dict[int, float],
    user_means: dict[int, float],
    excluded_book_ids: set[int],
    genre_goodreads_ids: set[int] | None,
    min_support: int,
    limit: int,
) -> list[dict]:
    aggregates = collections.defaultdict(lambda: [0.0, 0.0, 0, 0.0])
    with ratings_path.open("r", encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            user_id = int(row["user_id"])
            similarity = similarities.get(user_id)
            if similarity is None:
                continue
            book_id = int(row["book_id"])
            meta = books.get(book_id)
            if meta is None or book_id in excluded_book_ids:
                continue
            if genre_goodreads_ids is not None and meta.goodreads_book_id not in genre_goodreads_ids:
                continue
            rating = float(row["rating"])
            aggregate = aggregates[book_id]
            aggregate[0] += similarity * (rating - user_means[user_id])
            aggregate[1] += abs(similarity)
            aggregate[2] += 1
            aggregate[3] += rating

    result = []
    for book_id, (weighted_sum, weight_sum, support, rating_sum) in aggregates.items():
        if support < min_support or weight_sum == 0.0:
            continue
        meta = books[book_id]
        affinity = weighted_sum / weight_sum
        confidence = support / (support + 5.0)
        result.append({
            "bookId": book_id,
            "workId": meta.work_id,
            "isbn13": meta.isbn13,
            "title": meta.title,
            "authors": meta.authors,
            "score": round(affinity * confidence, 6),
            "neighborAffinity": round(affinity, 6),
            "neighborSupport": support,
            "neighborMeanRating": round(rating_sum / support, 4),
            "datasetAverageRating": meta.average_rating,
        })
    result.sort(key=lambda item: (
        -item["score"], -item["neighborSupport"], -item["datasetAverageRating"], item["bookId"]
    ))
    return result[:limit]


def build_report(args: argparse.Namespace) -> dict:
    catalog, _events, bases, active = read_bundle(args.bundle)
    pairs = active_pairwise(bases, active)
    enabled_ids = [book["id"] for book in catalog if book.get("enabled", True)]
    local_scores = fit_bradley_terry(enabled_ids, pairs)
    books, by_isbn, by_title_author, by_work = load_books(args.books)
    explicit_work_aliases = load_work_aliases(args.work_aliases) if args.work_aliases else {}
    dataset_scores, excluded_ids, ambiguous = map_catalog(
        catalog, local_scores, by_isbn, by_title_author, by_work, explicit_work_aliases
    )
    if len(dataset_scores) < args.min_overlap:
        raise ValueError(
            f"Only {len(dataset_scores)} active catalog books matched exact ISBN-13; "
            f"at least {args.min_overlap} are required"
        )
    genre_ids = None
    if args.tags or args.book_tags:
        if not args.tags or not args.book_tags:
            raise ValueError("--tags and --book-tags must be supplied together")
        genre_ids = load_genre_goodreads_ids(args.tags, args.book_tags, DEFAULT_GENRE_TAGS)
    similarities, user_means, eligible = find_neighbors(
        args.ratings, dataset_scores, args.neighbors, args.min_overlap, args.min_reader_ratings
    )
    recommendations = collect_recommendations(
        args.ratings, books, similarities, user_means, excluded_ids, genre_ids,
        args.min_support, args.limit,
    )
    return {
        "format": "bookduels.recommendations.v1",
        "dataset": "goodbooks-10k",
        "method": "shrunk pairwise-order agreement over regularized Bradley-Terry scores",
        "genreFilter": sorted(DEFAULT_GENRE_TAGS) if genre_ids is not None else None,
        "matchedActiveBooks": len(dataset_scores),
        "matchedCatalogBooksExcluded": len(excluded_ids),
        "ambiguousCatalogBookIds": ambiguous,
        "eligibleSimilarReaders": eligible,
        "minimumReaderRatings": args.min_reader_ratings,
        "selectedNeighbors": len(similarities),
        "recommendations": recommendations,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=pathlib.Path, help="BookDuels portable .tar.gz export")
    parser.add_argument("--books", required=True, type=pathlib.Path, help="goodbooks-10k books.csv")
    parser.add_argument("--ratings", required=True, type=pathlib.Path, help="goodbooks-10k ratings.csv")
    parser.add_argument("--tags", type=pathlib.Path, help="Optional goodbooks-10k tags.csv")
    parser.add_argument("--book-tags", type=pathlib.Path, help="Optional goodbooks-10k book_tags.csv")
    parser.add_argument(
        "--work-aliases", type=pathlib.Path,
        help="Optional CSV: bookduels_book_id,goodreads_work_id for curated edition/work matches",
    )
    parser.add_argument("--neighbors", type=int, default=200, help="Maximum similar readers (default: 200)")
    parser.add_argument("--min-overlap", type=int, default=4, help="Minimum exactly matched books per reader")
    parser.add_argument(
        "--min-reader-ratings", type=int, default=25,
        help="Discard external readers with fewer ratings (default: 25)",
    )
    parser.add_argument("--min-support", type=int, default=2, help="Minimum selected-neighbor ratings per result")
    parser.add_argument("--limit", type=int, default=50, help="Maximum recommendations")
    parser.add_argument("--output", required=True, type=pathlib.Path, help="Compact output JSON")
    args = parser.parse_args()
    try:
        if (args.neighbors < 1 or args.min_overlap < 2 or args.min_reader_ratings < 1
                or args.min_support < 1 or args.limit < 1):
            raise ValueError("Numeric limits are out of range")
        report = build_report(args)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError, csv.Error, json.JSONDecodeError) as error:
        print(f"Recommendation failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Wrote {len(report['recommendations'])} recommendations from "
        f"{report['selectedNeighbors']} similar readers to {args.output}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
