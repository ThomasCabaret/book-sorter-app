#!/usr/bin/env python3
from __future__ import annotations

import unittest

import numpy as np

from recommend_latent import fit_personal_vector, nearest_readers, training_preferences


class LatentInferenceTest(unittest.TestCase):
    def test_active_enabled_votes_are_mapped_and_jokers_are_ignored(self):
        pairs = [{
            "firstBookId": "a", "secondBookId": "b",
            "firstWins": 3, "secondWins": 1, "jokers": 9,
        }, {
            "firstBookId": "a", "secondBookId": "disabled",
            "firstWins": 5, "secondWins": 0, "jokers": 0,
        }]
        winners, losers, weights = training_preferences(
            pairs, {"a": 1, "b": 2, "disabled": 3}, {"a", "b"}
        )
        self.assertEqual([0, 1], winners.tolist())
        self.assertEqual([1, 0], losers.tolist())
        self.assertEqual([3.0, 1.0], weights.tolist())

    def test_personal_fit_and_nearest_reader_follow_observed_preference(self):
        factors = np.array([[1.0, 0.0], [-1.0, 0.0], [0.0, 1.0]], dtype=np.float16)
        bias = np.zeros(3, dtype=np.float16)
        personal = fit_personal_vector(
            factors, bias,
            np.array([0], dtype=np.int64),
            np.array([1], dtype=np.int64),
            np.array([4.0], dtype=np.float32),
        )
        scores = factors.astype(np.float32) @ personal
        self.assertGreater(scores[0], scores[1])
        neighbors = nearest_readers(
            personal,
            np.array([[1.0, 0.0], [-1.0, 0.0]], dtype=np.float16),
            np.array([10, 20]),
            1,
        )
        self.assertEqual(10, neighbors[0]["anonymousReaderId"])


if __name__ == "__main__":
    unittest.main()
