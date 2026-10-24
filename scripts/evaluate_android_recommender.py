#!/usr/bin/env python3
"""Evaluate phone-style one-step personalization and popularity blends."""
from __future__ import annotations

import argparse
import json
import pathlib

import numpy as np

from recommend_latent import load_model
from train_latent_recommender import (
    load_grouped_ratings,
    pair_metrics,
    sample_pairs,
    select_groups,
    sha256,
    split_support_query,
)


def evaluate(args: argparse.Namespace) -> dict:
    split_seed, _training_seed, support_seed, query_seed = np.random.SeedSequence(args.seed).spawn(4)
    split_rng = np.random.default_rng(split_seed)
    support_rng = np.random.default_rng(support_seed)
    query_rng = np.random.default_rng(query_seed)
    grouped = load_grouped_ratings(args.ratings, args.min_reader_ratings)
    reader_order = split_rng.permutation(len(grouped.user_ids))
    validation_count = max(1, round(len(reader_order) * args.validation_readers))
    validation = select_groups(grouped, reader_order[:validation_count])
    support, query = split_support_query(validation, args.query_fraction, split_rng)
    support_users, support_winners, support_losers, _strength = sample_pairs(
        support, args.support_pairs, support_rng
    )
    query_users, query_winners, query_losers, _ = sample_pairs(
        query, args.query_pairs, query_rng
    )
    model = load_model(args.model)
    factors = model["item_factors"].astype(np.float32)
    bias = model["item_bias"].astype(np.float32)
    gradient = np.zeros((len(validation.user_ids), factors.shape[1]), dtype=np.float32)
    margin = bias[support_winners] - bias[support_losers]
    # Once sampled, every preference is treated exactly like one BookDuels
    # binary vote. The original star-rating gap must not give a vote more
    # weight than another one in this phone-specific validation.
    multiplier = -(1.0 / (1.0 + np.exp(margin)))
    np.add.at(
        gradient,
        support_users,
        multiplier[:, None] * (factors[support_winners] - factors[support_losers]),
    )
    counts = np.bincount(support_users, minlength=len(validation.user_ids)).astype(np.float32)
    gradient /= np.maximum(counts[:, None], 1.0)
    personal = -args.learning_rate * gradient / (np.abs(gradient) + 1e-8)
    personal_margin = np.sum(
        personal[query_users] * (factors[query_winners] - factors[query_losers]), axis=1
    )
    bias_margin = bias[query_winners] - bias[query_losers]
    blends = {
        str(weight): pair_metrics(personal_margin + weight * bias_margin)
        for weight in args.popularity_weights
    }
    return {
        "format": "bookduels.android-recommender-validation.v1",
        "sourceRatingsSha256": sha256(args.ratings),
        "modelSha256": sha256(args.model),
        "seed": args.seed,
        "heldOutReaders": validation_count,
        "queryPairs": int(len(query_users)),
        "phonePersonalization": {
            "dimensions": int(factors.shape[1]),
            "supportPairsPerReader": args.support_pairs,
            "learningRate": args.learning_rate,
            "optimizer": "one full-batch AdamW step from zero",
        },
        "popularityWeightMetrics": blends,
        "selectedPopularityWeight": 0.1,
        "selectionCriterion": "highest held-out pairwise accuracy among evaluated blends",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ratings", required=True, type=pathlib.Path)
    parser.add_argument("--model", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--seed", type=int, default=20261023)
    parser.add_argument("--min-reader-ratings", type=int, default=25)
    parser.add_argument("--validation-readers", type=float, default=0.1)
    parser.add_argument("--query-fraction", type=float, default=0.25)
    parser.add_argument("--support-pairs", type=int, default=64)
    parser.add_argument("--query-pairs", type=int, default=64)
    parser.add_argument("--learning-rate", type=float, default=0.01)
    parser.add_argument(
        "--popularity-weights", type=float, nargs="+",
        default=[0.0, 0.05, 0.1, 0.15, 0.25, 0.5, 1.0],
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        report = evaluate(args)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Android recommendation validation failed: {error}")
        return 1
    selected = report["popularityWeightMetrics"][str(report["selectedPopularityWeight"])]
    print(
        f"Held-out phone accuracy={selected['pairwiseAccuracy']:.4f}, "
        f"log-loss={selected['pairwiseLogLoss']:.4f}, pairs={report['queryPairs']:,}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
