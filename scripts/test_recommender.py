#!/usr/bin/env python3
from __future__ import annotations

import csv
import pathlib
import tempfile
import unittest

from recommend_goodbooks import (
    collect_recommendations,
    find_neighbors,
    load_books,
    map_catalog,
    normalize_isbn13,
    order_similarity,
)


def isbn13(prefix: str) -> str:
    assert len(prefix) == 12 and prefix.isdigit()
    subtotal = sum(int(digit) * (1 if index % 2 == 0 else 3) for index, digit in enumerate(prefix))
    return prefix + str((-subtotal) % 10)


class RecommenderTest(unittest.TestCase):
    def test_scientific_notation_isbn_is_normalized(self):
        self.assertEqual("9780439023481", normalize_isbn13("9.780439023481e+12"))
        self.assertEqual("9780439023481", normalize_isbn13("9.78043902348e+12"))
        self.assertIsNone(normalize_isbn13("not-an-isbn"))

    def test_pairwise_order_similarity_rewards_same_order(self):
        scores = {1: 2.0, 2: 1.0, 3: 0.0}
        same, comparable = order_similarity([(1, 5), (2, 4), (3, 1)], scores)
        reverse, _ = order_similarity([(1, 1), (2, 4), (3, 5)], scores)
        self.assertEqual(3, comparable)
        self.assertGreater(same, 0.0)
        self.assertLess(reverse, 0.0)

    def test_duplicate_local_works_are_not_silently_mapped_to_one_dataset_work(self):
        first_isbn = isbn13("978000000001")
        second_isbn = isbn13("978000000002")
        catalog = [
            {"id": "local-a", "enabled": True, "isbn13": first_isbn, "isbnAliases": [first_isbn]},
            {"id": "local-b", "enabled": True, "isbn13": second_isbn, "isbnAliases": [second_isbn]},
        ]
        scores, excluded, ambiguous = map_catalog(
            catalog,
            {"local-a": 1.0, "local-b": -1.0},
            {first_isbn: [7], second_isbn: [7]},
        )
        self.assertEqual({}, scores)
        self.assertEqual({7}, excluded)
        self.assertEqual({"local-a", "local-b"}, set(ambiguous))

    def test_explicit_work_alias_bridges_a_different_edition_isbn(self):
        catalog = [{
            "id": "local-a", "enabled": True, "title": "Titre traduit",
            "author": "Auteur", "isbn13": isbn13("978000000003"), "isbnAliases": [],
        }]
        scores, excluded, ambiguous = map_catalog(
            catalog,
            {"local-a": 1.25},
            {},
            work_to_dataset_ids={9001: [7]},
            explicit_work_aliases={"local-a": 9001},
        )
        self.assertEqual({7: 1.25}, scores)
        self.assertEqual({7}, excluded)
        self.assertEqual([], ambiguous)

    def test_similar_readers_recommend_their_shared_favorite(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        (root / "build").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root / "build") as temporary:
            folder = pathlib.Path(temporary)
            books_path = folder / "books.csv"
            ratings_path = folder / "ratings.csv"
            with books_path.open("w", encoding="utf-8", newline="") as output:
                writer = csv.DictWriter(output, fieldnames=[
                    "book_id", "goodreads_book_id", "work_id", "isbn13", "title", "authors",
                    "average_rating",
                ])
                writer.writeheader()
                for number in range(1, 6):
                    writer.writerow({
                        "book_id": number,
                        "goodreads_book_id": 100 + number,
                        "work_id": 200 + number,
                        "isbn13": isbn13(f"97800000000{number}"),
                        "title": f"Book {number}",
                        "authors": "Author",
                        "average_rating": 4.0,
                    })
            with ratings_path.open("w", encoding="utf-8", newline="") as output:
                writer = csv.writer(output)
                writer.writerow(["user_id", "book_id", "rating"])
                writer.writerows([
                    [1, 1, 5], [1, 2, 4], [1, 3, 1], [1, 4, 5], [1, 5, 1],
                    [2, 1, 1], [2, 2, 4], [2, 3, 5], [2, 4, 1], [2, 5, 5],
                    [3, 1, 5], [3, 2, 3], [3, 3, 1], [3, 4, 4], [3, 5, 1],
                ])
            books, _, _, _ = load_books(books_path)
            similarities, means, eligible = find_neighbors(
                ratings_path, {1: 2.0, 2: 1.0, 3: 0.0}, neighbor_count=2,
                min_overlap=3, min_reader_ratings=5,
            )
            recommendations = collect_recommendations(
                ratings_path, books, similarities, means, {1, 2, 3}, None,
                min_support=2, limit=2,
            )
            self.assertEqual(2, eligible)
            self.assertEqual({1, 3}, set(similarities))
            self.assertEqual(4, recommendations[0]["bookId"])
            filtered, _, filtered_eligible = find_neighbors(
                ratings_path, {1: 2.0, 2: 1.0, 3: 0.0}, neighbor_count=2,
                min_overlap=3, min_reader_ratings=6,
            )
            self.assertEqual({}, filtered)
            self.assertEqual(0, filtered_eligible)


if __name__ == "__main__":
    unittest.main()
