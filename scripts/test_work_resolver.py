#!/usr/bin/env python3
from __future__ import annotations

import unittest

from recommend_goodbooks import BookMeta, normalize_author
from resolve_work_aliases import resolve_catalog


class WorkResolverTest(unittest.TestCase):
    def meta(
        self, dataset_id: int, goodreads_id: int, work_id: int,
        title: str = "Dune Messiah", authors: str = "Frank Herbert",
    ) -> BookMeta:
        return BookMeta(
            book_id=dataset_id,
            goodreads_book_id=goodreads_id,
            best_book_id=goodreads_id,
            work_id=work_id,
            isbn13=None,
            title=title,
            original_title=title,
            authors=authors,
            average_rating=4.0,
        )

    def test_goodreads_edition_id_bridges_translated_isbn(self):
        catalog = [{
            "id": "local", "title": "Le Messie de Dune", "author": "Herbert Frank",
            "isbn13": "9782221255728", "enabled": True,
        }]
        docs = [{
            "key": "/works/OL893461W", "title": "Dune Messiah",
            "author_name": ["Frank Herbert"], "isbn": ["9782221255728"],
            "id_goodreads": ["106"],
        }]
        report = resolve_catalog(catalog, docs, {7: self.meta(7, 106, 3634639)}, {})
        self.assertEqual(1, report["matched"])
        self.assertEqual(3634639, report["resolutions"][0]["goodreadsWorkId"])
        self.assertEqual("goodreads-edition-id", report["resolutions"][0]["evidence"])

    def test_duplicate_enabled_local_records_are_not_silently_merged(self):
        catalog = [
            {"id": "a", "title": "A", "author": "X", "isbn13": "9782221255728", "enabled": True},
            {"id": "b", "title": "B", "author": "X", "isbn13": "9780441172696", "enabled": True},
        ]
        docs = [{
            "key": "/works/OL893461W", "title": "Dune Messiah", "author_name": ["Frank Herbert"],
            "isbn": ["9782221255728", "9780441172696"], "id_goodreads": ["106"],
        }]
        report = resolve_catalog(catalog, docs, {7: self.meta(7, 106, 3634639)}, {})
        self.assertEqual(0, report["matched"])
        self.assertEqual(2, report["ambiguous"])

    def test_author_normalization_accepts_surname_first(self):
        self.assertEqual(normalize_author("Frank Herbert"), normalize_author("Herbert Frank"))

    def test_reviewed_translation_alias_wins(self):
        catalog = [{
            "id": "local-brave-new-world", "title": "Le Meilleur des Mondes",
            "author": "Huxley Aldous", "enabled": True, "isbn13": None,
        }]
        brave_new_world = self.meta(
            55, 500, 3204877, "Brave New World", "Aldous Huxley"
        )
        report = resolve_catalog(
            catalog, [], {55: brave_new_world}, {},
            {"local-brave-new-world": 3204877},
        )
        resolution = report["resolutions"][0]
        self.assertEqual("matched", resolution["status"])
        self.assertEqual("reviewed-work-alias", resolution["evidence"])
        self.assertEqual("Brave New World", resolution["goodbooksTitle"])

    def test_reviewed_alias_rejects_unknown_work(self):
        catalog = [{"id": "local", "title": "Livre", "author": "Auteur", "enabled": True}]
        with self.assertRaisesRegex(ValueError, "unknown or non-unique work ID"):
            resolve_catalog(catalog, [], {}, {}, {"local": 999})


if __name__ == "__main__":
    unittest.main()
