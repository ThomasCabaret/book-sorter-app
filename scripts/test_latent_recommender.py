#!/usr/bin/env python3
from __future__ import annotations

import csv
import pathlib
import tempfile
import unittest

import numpy as np

from train_latent_recommender import (
    load_grouped_ratings,
    pair_metrics,
    sample_pairs,
    split_support_query,
)


class LatentRecommenderTest(unittest.TestCase):
    def test_group_split_and_pair_sampling_do_not_leak_rows(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        (root / "build").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root / "build") as temporary:
            path = pathlib.Path(temporary) / "ratings.csv"
            with path.open("w", newline="", encoding="utf-8") as output:
                writer = csv.writer(output)
                writer.writerow(["user_id", "book_id", "rating"])
                for user in (10, 20):
                    for book, rating in enumerate((1, 2, 3, 4, 5, 1), start=1):
                        writer.writerow([user, book, rating])
            grouped = load_grouped_ratings(path, 5)
            support, query = split_support_query(grouped, 0.34, np.random.default_rng(7))
            self.assertEqual(12, len(support.items) + len(query.items))
            self.assertTrue(np.all(support.counts == 4))
            self.assertTrue(np.all(query.counts == 2))
            users, winners, losers, strength = sample_pairs(
                support, 20, np.random.default_rng(8)
            )
            self.assertGreater(len(users), 0)
            self.assertTrue(np.all(winners != losers))
            self.assertTrue(np.all(strength > 0))

    def test_pair_metrics_reward_positive_margins(self):
        good = pair_metrics(np.array([1.0, 2.0, 3.0]))
        bad = pair_metrics(np.array([-1.0, -2.0, -3.0]))
        self.assertEqual(1.0, good["pairwiseAccuracy"])
        self.assertEqual(0.0, bad["pairwiseAccuracy"])
        self.assertLess(good["pairwiseLogLoss"], bad["pairwiseLogLoss"])


if __name__ == "__main__":
    unittest.main()
