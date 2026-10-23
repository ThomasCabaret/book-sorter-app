#!/usr/bin/env python3
"""Train and validate a compact pairwise latent model on goodbooks-10k.

The generic model never reads BookDuels events. A held-out set of external
readers is used to simulate the phone: item parameters are frozen, a small
reader vector is fitted from support comparisons, and unseen comparisons are
then evaluated. The exported derivative contains item factors plus anonymous
training-reader factors; raw ratings are not embedded in the artifact.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from dataclasses import dataclass

import numpy as np

from recommend_goodbooks import load_books


MODEL_FORMAT = "bookduels.goodbooks-latent.v1"


@dataclass(frozen=True)
class GroupedRatings:
    user_ids: np.ndarray
    items: np.ndarray
    ratings: np.ndarray
    starts: np.ndarray
    counts: np.ndarray


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_grouped_ratings(path: pathlib.Path, min_reader_ratings: int) -> GroupedRatings:
    rows = np.loadtxt(path, delimiter=",", skiprows=1, dtype=np.int32)
    if rows.ndim != 2 or rows.shape[1] != 3:
        raise ValueError("ratings.csv must have user_id,book_id,rating columns")
    if np.any(rows[:, 0] < 1) or np.any(rows[:, 1] < 1) or np.any((rows[:, 2] < 1) | (rows[:, 2] > 5)):
        raise ValueError("ratings.csv contains invalid IDs or ratings")
    order = np.argsort(rows[:, 0], kind="stable")
    rows = rows[order]
    user_ids, counts = np.unique(rows[:, 0], return_counts=True)
    eligible = counts >= min_reader_ratings
    row_mask = np.repeat(eligible, counts)
    filtered = rows[row_mask]
    kept_counts = counts[eligible].astype(np.int64)
    starts = np.concatenate((np.array([0], dtype=np.int64), np.cumsum(kept_counts)[:-1]))
    return GroupedRatings(
        user_ids=user_ids[eligible].astype(np.int32),
        items=(filtered[:, 1] - 1).astype(np.int32),
        ratings=filtered[:, 2].astype(np.int8),
        starts=starts,
        counts=kept_counts,
    )


def select_groups(source: GroupedRatings, group_indices: np.ndarray) -> GroupedRatings:
    group_indices = np.asarray(group_indices, dtype=np.int64)
    chunks = [
        np.arange(source.starts[index], source.starts[index] + source.counts[index], dtype=np.int64)
        for index in group_indices
    ]
    positions = np.concatenate(chunks) if chunks else np.empty(0, dtype=np.int64)
    counts = source.counts[group_indices].copy()
    starts = np.concatenate((np.array([0], dtype=np.int64), np.cumsum(counts)[:-1]))
    return GroupedRatings(
        user_ids=source.user_ids[group_indices].copy(),
        items=source.items[positions].copy(),
        ratings=source.ratings[positions].copy(),
        starts=starts,
        counts=counts,
    )


def split_support_query(
    source: GroupedRatings, query_fraction: float, rng: np.random.Generator,
) -> tuple[GroupedRatings, GroupedRatings]:
    support_positions: list[np.ndarray] = []
    query_positions: list[np.ndarray] = []
    support_counts = np.empty(len(source.user_ids), dtype=np.int64)
    query_counts = np.empty(len(source.user_ids), dtype=np.int64)
    for index, (start, count) in enumerate(zip(source.starts, source.counts)):
        permutation = rng.permutation(int(count)) + start
        query_count = min(int(count) - 2, max(2, round(int(count) * query_fraction)))
        query_positions.append(permutation[:query_count])
        support_positions.append(permutation[query_count:])
        query_counts[index] = query_count
        support_counts[index] = int(count) - query_count

    def build(positions: list[np.ndarray], counts: np.ndarray) -> GroupedRatings:
        joined = np.concatenate(positions)
        starts = np.concatenate((np.array([0], dtype=np.int64), np.cumsum(counts)[:-1]))
        return GroupedRatings(
            user_ids=source.user_ids.copy(),
            items=source.items[joined].copy(),
            ratings=source.ratings[joined].copy(),
            starts=starts,
            counts=counts,
        )

    return build(support_positions, support_counts), build(query_positions, query_counts)


def sample_pairs(
    source: GroupedRatings, pairs_per_reader: int, rng: np.random.Generator,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    readers = np.repeat(np.arange(len(source.user_ids), dtype=np.int32), pairs_per_reader)
    counts = source.counts[readers]
    starts = source.starts[readers]
    first = starts + np.floor(rng.random(len(readers)) * counts).astype(np.int64)
    second = starts + np.floor(rng.random(len(readers)) * counts).astype(np.int64)
    for _ in range(8):
        tied = source.ratings[first] == source.ratings[second]
        if not np.any(tied):
            break
        second[tied] = starts[tied] + np.floor(rng.random(np.count_nonzero(tied)) * counts[tied]).astype(np.int64)
    keep = source.ratings[first] != source.ratings[second]
    readers = readers[keep]
    first = first[keep]
    second = second[keep]
    first_wins = source.ratings[first] > source.ratings[second]
    winners = np.where(first_wins, source.items[first], source.items[second]).astype(np.int64)
    losers = np.where(first_wins, source.items[second], source.items[first]).astype(np.int64)
    strength = np.abs(source.ratings[first].astype(np.float32) - source.ratings[second]).astype(np.float32)
    return readers.astype(np.int64), winners, losers, strength


def item_means(source: GroupedRatings, item_count: int) -> tuple[np.ndarray, np.ndarray]:
    sums = np.bincount(source.items, weights=source.ratings, minlength=item_count)
    counts = np.bincount(source.items, minlength=item_count)
    means = np.divide(sums, counts, out=np.zeros(item_count, dtype=np.float64), where=counts > 0)
    return means.astype(np.float32), counts.astype(np.int32)


def pair_metrics(margins: np.ndarray) -> dict[str, float]:
    margins = margins.astype(np.float64)
    accuracy = np.mean((margins > 0).astype(np.float64) + 0.5 * (margins == 0))
    log_loss = np.mean(np.logaddexp(0.0, -margins))
    return {"pairwiseAccuracy": float(accuracy), "pairwiseLogLoss": float(log_loss)}


def train(args: argparse.Namespace) -> dict:
    try:
        import torch
        import torch.nn.functional as functional
    except ImportError as error:
        raise ValueError("PyTorch is required for latent-model training") from error

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    split_seed, training_seed, support_seed, query_seed = np.random.SeedSequence(args.seed).spawn(4)
    split_rng = np.random.default_rng(split_seed)
    training_rng = np.random.default_rng(training_seed)
    support_rng = np.random.default_rng(support_seed)
    query_rng = np.random.default_rng(query_seed)
    grouped = load_grouped_ratings(args.ratings, args.min_reader_ratings)
    books, _by_isbn, _by_title_author, _by_work = load_books(args.books)
    item_count = max(books)
    if np.any(grouped.items >= item_count):
        raise ValueError("ratings.csv refers to an item absent from books.csv")

    reader_order = split_rng.permutation(len(grouped.user_ids))
    validation_count = max(1, round(len(reader_order) * args.validation_readers))
    validation = select_groups(grouped, reader_order[:validation_count])
    training = select_groups(grouped, reader_order[validation_count:])
    support, query = split_support_query(validation, args.query_fraction, split_rng)
    means, rating_counts = item_means(training, item_count)

    user_factors = torch.nn.Embedding(len(training.user_ids), args.dimensions)
    item_factors = torch.nn.Embedding(item_count, args.dimensions)
    item_bias = torch.nn.Embedding(item_count, 1)
    torch.nn.init.normal_(user_factors.weight, std=0.08)
    torch.nn.init.normal_(item_factors.weight, std=0.08)
    torch.nn.init.zeros_(item_bias.weight)
    optimizer = torch.optim.AdamW(
        list(user_factors.parameters()) + list(item_factors.parameters()) + list(item_bias.parameters()),
        lr=args.learning_rate, weight_decay=args.regularization,
    )

    for epoch in range(1, args.epochs + 1):
        users, winners, losers, strength = sample_pairs(training, args.pairs_per_reader, training_rng)
        order = training_rng.permutation(len(users))
        total_loss = 0.0
        total = 0
        for start in range(0, len(order), args.batch_size):
            chosen = order[start:start + args.batch_size]
            user = torch.from_numpy(users[chosen])
            winner = torch.from_numpy(winners[chosen])
            loser = torch.from_numpy(losers[chosen])
            weight = torch.from_numpy(0.5 + 0.5 * strength[chosen] / 4.0)
            margin = (
                (user_factors(user) * (item_factors(winner) - item_factors(loser))).sum(dim=1)
                + item_bias(winner).squeeze(1) - item_bias(loser).squeeze(1)
            )
            loss = (functional.softplus(-margin) * weight).mean()
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            total_loss += float(loss.detach()) * len(chosen)
            total += len(chosen)
        print(f"epoch {epoch}/{args.epochs}: {total:,} pairs, loss={total_loss / total:.5f}", flush=True)

    support_users, support_winners, support_losers, support_strength = sample_pairs(
        support, args.personalization_pairs, support_rng
    )
    # Draw the evaluation sample before optimization so changing the number of
    # personalization epochs cannot silently change the held-out benchmark.
    query_users, query_winners, query_losers, _query_strength = sample_pairs(
        query, args.query_pairs, query_rng
    )
    personal = torch.nn.Embedding(len(validation.user_ids), args.dimensions)
    torch.nn.init.zeros_(personal.weight)
    personal_optimizer = torch.optim.AdamW(
        personal.parameters(), lr=args.personalization_learning_rate,
        weight_decay=args.regularization,
    )
    frozen_items = item_factors.weight.detach()
    frozen_bias = item_bias.weight.detach().squeeze(1)
    for _epoch in range(args.personalization_epochs):
        order = support_rng.permutation(len(support_users))
        for start in range(0, len(order), args.batch_size):
            chosen = order[start:start + args.batch_size]
            user = torch.from_numpy(support_users[chosen])
            winner = torch.from_numpy(support_winners[chosen])
            loser = torch.from_numpy(support_losers[chosen])
            weight = torch.from_numpy(0.5 + 0.5 * support_strength[chosen] / 4.0)
            margin = (
                (personal(user) * (frozen_items[winner] - frozen_items[loser])).sum(dim=1)
                + frozen_bias[winner] - frozen_bias[loser]
            )
            loss = (functional.softplus(-margin) * weight).mean()
            personal_optimizer.zero_grad()
            loss.backward()
            personal_optimizer.step()

    with torch.no_grad():
        query_user = torch.from_numpy(query_users)
        query_winner = torch.from_numpy(query_winners)
        query_loser = torch.from_numpy(query_losers)
        personalized_margins = (
            (personal(query_user) * (frozen_items[query_winner] - frozen_items[query_loser])).sum(dim=1)
            + frozen_bias[query_winner] - frozen_bias[query_loser]
        ).numpy()
        bias_margins = (frozen_bias[query_winner] - frozen_bias[query_loser]).numpy()
    mean_margins = means[query_winners] - means[query_losers]

    work_ids = np.zeros(item_count, dtype=np.int64)
    goodreads_book_ids = np.zeros(item_count, dtype=np.int64)
    for book_id, meta in books.items():
        if meta.work_id is not None:
            work_ids[book_id - 1] = meta.work_id
        goodreads_book_ids[book_id - 1] = meta.goodreads_book_id
    args.output.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        args.output,
        format=np.array(MODEL_FORMAT),
        item_factors=item_factors.weight.detach().numpy().astype(np.float16),
        item_bias=item_bias.weight.detach().numpy().squeeze(1).astype(np.float16),
        item_mean_rating=means.astype(np.float16),
        item_rating_count=rating_counts,
        goodreads_work_id=work_ids,
        goodreads_book_id=goodreads_book_ids,
        reader_factors=user_factors.weight.detach().numpy().astype(np.float16),
        reader_id=training.user_ids,
    )
    metrics = {
        "personalized": pair_metrics(personalized_margins),
        "itemBiasOnly": pair_metrics(bias_margins),
        "meanRatingBaseline": pair_metrics(mean_margins),
        "queryPairs": int(len(query_users)),
    }
    manifest = {
        "format": MODEL_FORMAT,
        "independentOfBookDuelsVotes": True,
        "source": {
            "name": "goodbooks-10k v1.0",
            "license": "CC BY-SA 4.0",
            "booksSha256": sha256(args.books),
            "ratingsSha256": sha256(args.ratings),
        },
        "parameters": {
            "seed": args.seed,
            "dimensions": args.dimensions,
            "minReaderRatings": args.min_reader_ratings,
            "epochs": args.epochs,
            "pairsPerReaderPerEpoch": args.pairs_per_reader,
            "learningRate": args.learning_rate,
            "regularization": args.regularization,
            "validationReaderFraction": args.validation_readers,
            "queryFraction": args.query_fraction,
            "personalizationPairsPerReader": args.personalization_pairs,
            "personalizationEpochs": args.personalization_epochs,
            "personalizationLearningRate": args.personalization_learning_rate,
        },
        "counts": {
            "items": item_count,
            "eligibleReaders": int(len(grouped.user_ids)),
            "trainingReaders": int(len(training.user_ids)),
            "validationReaders": int(len(validation.user_ids)),
            "ratings": int(len(grouped.items)),
        },
        "validation": metrics,
        "artifactBytes": args.output.stat().st_size,
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--books", required=True, type=pathlib.Path)
    parser.add_argument("--ratings", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    parser.add_argument("--dimensions", type=int, default=48)
    parser.add_argument("--min-reader-ratings", type=int, default=25)
    parser.add_argument("--epochs", type=int, default=6)
    parser.add_argument("--pairs-per-reader", type=int, default=48)
    parser.add_argument("--batch-size", type=int, default=8192)
    parser.add_argument("--learning-rate", type=float, default=0.01)
    parser.add_argument("--regularization", type=float, default=0.0005)
    parser.add_argument("--validation-readers", type=float, default=0.1)
    parser.add_argument("--query-fraction", type=float, default=0.25)
    parser.add_argument("--query-pairs", type=int, default=64)
    parser.add_argument("--personalization-pairs", type=int, default=64)
    parser.add_argument("--personalization-epochs", type=int, default=1)
    parser.add_argument("--personalization-learning-rate", type=float, default=0.01)
    parser.add_argument("--seed", type=int, default=20261023)
    args = parser.parse_args()
    if not (
        args.dimensions > 0 and args.min_reader_ratings >= 2 and args.epochs > 0
        and args.pairs_per_reader > 0 and args.batch_size > 0
        and 0 < args.validation_readers < 0.5 and 0 < args.query_fraction < 0.5
        and args.query_pairs > 0 and args.personalization_pairs > 0
        and args.personalization_epochs > 0
    ):
        parser.error("invalid model parameter")
    return args


def main() -> int:
    try:
        manifest = train(parse_args())
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Latent training failed: {error}", file=sys.stderr)
        return 1
    validation = manifest["validation"]
    print(
        "held-out pair accuracy: "
        f"personalized={validation['personalized']['pairwiseAccuracy']:.4f}, "
        f"mean-baseline={validation['meanRatingBaseline']['pairwiseAccuracy']:.4f}"
    )
    print(f"artifact: {manifest['artifactBytes']:,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
