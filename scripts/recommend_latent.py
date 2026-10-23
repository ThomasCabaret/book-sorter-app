#!/usr/bin/env python3
"""Fit a BookDuels reader vector and rank books with a trained latent model.

The portable archive is validated before use. Only active votes between enabled,
unambiguously matched works fit the personal vector; Jokers and external star
ratings never become BookDuels events. All catalog works are excluded from the
recommendation candidates, including currently disabled ones.
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import sys

import numpy as np

from inspect_bundle import active_pairwise, read_bundle
from recommend_goodbooks import (
    DEFAULT_GENRE_TAGS,
    load_books,
    load_genre_goodreads_ids,
    load_work_aliases,
    match_catalog,
)
from train_latent_recommender import MODEL_FORMAT


def load_model(path: pathlib.Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as archive:
        model = {name: archive[name] for name in archive.files}
    if str(model.get("format")) != MODEL_FORMAT:
        raise ValueError("Unsupported latent-model format")
    required = {
        "item_factors", "item_bias", "item_mean_rating", "item_rating_count",
        "goodreads_work_id", "goodreads_book_id", "reader_factors", "reader_id",
    }
    if not required.issubset(model):
        raise ValueError("Latent model is missing arrays")
    item_count, dimensions = model["item_factors"].shape
    if dimensions < 1 or any(len(model[name]) != item_count for name in (
        "item_bias", "item_mean_rating", "item_rating_count",
        "goodreads_work_id", "goodreads_book_id",
    )):
        raise ValueError("Latent model has inconsistent item arrays")
    if model["reader_factors"].ndim != 2 or model["reader_factors"].shape[1] != dimensions:
        raise ValueError("Latent model has inconsistent reader factors")
    if len(model["reader_id"]) != len(model["reader_factors"]):
        raise ValueError("Latent model has inconsistent reader IDs")
    if not all(np.all(np.isfinite(model[name])) for name in (
        "item_factors", "item_bias", "item_mean_rating", "reader_factors",
    )):
        raise ValueError("Latent model contains non-finite values")
    return model


def training_preferences(
    pairs: list[dict], local_to_dataset: dict[str, int], enabled_ids: set[str],
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    winners: list[int] = []
    losers: list[int] = []
    weights: list[float] = []
    for pair in pairs:
        first_local = pair["firstBookId"]
        second_local = pair["secondBookId"]
        if first_local not in enabled_ids or second_local not in enabled_ids:
            continue
        first = local_to_dataset.get(first_local)
        second = local_to_dataset.get(second_local)
        if first is None or second is None:
            continue
        if pair["firstWins"]:
            winners.append(first - 1)
            losers.append(second - 1)
            weights.append(float(pair["firstWins"]))
        if pair["secondWins"]:
            winners.append(second - 1)
            losers.append(first - 1)
            weights.append(float(pair["secondWins"]))
    return (
        np.asarray(winners, dtype=np.int64),
        np.asarray(losers, dtype=np.int64),
        np.asarray(weights, dtype=np.float32),
    )


def fit_personal_vector(
    item_factors: np.ndarray,
    item_bias: np.ndarray,
    winners: np.ndarray,
    losers: np.ndarray,
    weights: np.ndarray,
    learning_rate: float = 0.01,
    epochs: int = 1,
    regularization: float = 0.0005,
) -> np.ndarray:
    if not (len(winners) == len(losers) == len(weights)) or len(winners) == 0:
        raise ValueError("At least one mapped active vote is required")
    try:
        import torch
        import torch.nn.functional as functional
    except ImportError as error:
        raise ValueError("PyTorch is required for latent recommendation") from error
    factors = torch.from_numpy(item_factors.astype(np.float32, copy=False))
    bias = torch.from_numpy(item_bias.astype(np.float32, copy=False))
    winner = torch.from_numpy(winners)
    loser = torch.from_numpy(losers)
    weight = torch.from_numpy(weights / weights.mean())
    personal = torch.nn.Parameter(torch.zeros(factors.shape[1], dtype=torch.float32))
    optimizer = torch.optim.AdamW([personal], lr=learning_rate, weight_decay=regularization)
    for _epoch in range(epochs):
        margin = ((factors[winner] - factors[loser]) * personal).sum(dim=1)
        margin = margin + bias[winner] - bias[loser]
        loss = (functional.softplus(-margin) * weight).mean()
        optimizer.zero_grad()
        loss.backward()
        optimizer.step()
    return personal.detach().numpy()


def nearest_readers(
    personal: np.ndarray, reader_factors: np.ndarray, reader_ids: np.ndarray, limit: int,
) -> list[dict]:
    personal_norm = float(np.linalg.norm(personal))
    if personal_norm == 0.0 or limit == 0 or len(reader_factors) == 0:
        return []
    factors = reader_factors.astype(np.float32)
    norms = np.linalg.norm(factors, axis=1)
    similarities = np.divide(
        factors @ personal,
        norms * personal_norm,
        out=np.full(len(factors), -1.0, dtype=np.float32),
        where=norms > 0,
    )
    count = min(limit, len(similarities))
    selected = np.argpartition(-similarities, count - 1)[:count]
    selected = selected[np.argsort(-similarities[selected])]
    return [
        {"anonymousReaderId": int(reader_ids[index]), "cosineSimilarity": float(similarities[index])}
        for index in selected
    ]


def build_report(args: argparse.Namespace) -> dict:
    catalog, _events, bases, active = read_bundle(args.bundle)
    pairs = active_pairwise(bases, active)
    books, by_isbn, by_title_author, by_work = load_books(args.books)
    aliases = load_work_aliases(args.work_aliases) if args.work_aliases else {}
    local_to_dataset, catalog_dataset_ids, ambiguous = match_catalog(
        catalog, by_isbn, by_title_author, by_work, aliases
    )
    enabled_ids = {book["id"] for book in catalog if book.get("enabled", True)}
    winners, losers, weights = training_preferences(pairs, local_to_dataset, enabled_ids)
    model = load_model(args.model)
    if len(model["item_factors"]) != max(books):
        raise ValueError("Latent model and books.csv have different item counts")
    personal = fit_personal_vector(
        model["item_factors"], model["item_bias"], winners, losers, weights,
        args.learning_rate, args.epochs, args.regularization,
    )
    scores = (
        model["item_factors"].astype(np.float32) @ personal
        + model["item_bias"].astype(np.float32)
    )
    genre_ids = None
    if args.tags or args.book_tags:
        if not args.tags or not args.book_tags:
            raise ValueError("--tags and --book-tags must be supplied together")
        genre_ids = load_genre_goodreads_ids(args.tags, args.book_tags, DEFAULT_GENRE_TAGS)
    candidates = []
    for item_index, score in enumerate(scores):
        dataset_id = item_index + 1
        if dataset_id in catalog_dataset_ids or dataset_id not in books:
            continue
        meta = books[dataset_id]
        if genre_ids is not None and meta.goodreads_book_id not in genre_ids:
            continue
        candidates.append({
            "bookId": dataset_id,
            "workId": meta.work_id,
            "isbn13": meta.isbn13,
            "title": meta.title,
            "authors": meta.authors,
            "score": float(score),
            "datasetAverageRating": meta.average_rating,
            "datasetRatingCount": int(model["item_rating_count"][item_index]),
        })
    candidates.sort(key=lambda item: (-item["score"], -item["datasetRatingCount"], item["bookId"]))
    return {
        "format": "bookduels.latent-recommendations.v1",
        "dataset": "goodbooks-10k",
        "modelFormat": MODEL_FORMAT,
        "method": "fixed pairwise item factors with a regularized personal vector",
        "matchedCatalogBooks": len(local_to_dataset),
        "ambiguousCatalogBookIds": ambiguous,
        "activeMappedPreferenceDirections": int(len(weights)),
        "activeMappedVoteCount": int(weights.sum()),
        "genreFilter": sorted(DEFAULT_GENRE_TAGS) if genre_ids is not None else None,
        "recommendations": candidates[:args.limit],
        "nearestReaderDiagnostics": nearest_readers(
            personal, model["reader_factors"], model["reader_id"], args.nearest_readers
        ),
        "nearestReaderWarning": (
            "Diagnostic only: nearest-reader retrieval has not been validated as a scoring improvement."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=pathlib.Path)
    parser.add_argument("--model", required=True, type=pathlib.Path)
    parser.add_argument("--books", required=True, type=pathlib.Path)
    parser.add_argument("--work-aliases", type=pathlib.Path)
    parser.add_argument("--tags", type=pathlib.Path)
    parser.add_argument("--book-tags", type=pathlib.Path)
    parser.add_argument("--limit", type=int, default=50)
    parser.add_argument("--nearest-readers", type=int, default=20)
    parser.add_argument("--learning-rate", type=float, default=0.01)
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--regularization", type=float, default=0.0005)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        if args.limit < 1 or args.nearest_readers < 0 or args.learning_rate <= 0 or args.epochs < 1:
            raise ValueError("Invalid recommendation parameter")
        report = build_report(args)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError, csv.Error, json.JSONDecodeError) as error:
        print(f"Latent recommendation failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Wrote {len(report['recommendations'])} recommendations from "
        f"{report['activeMappedVoteCount']} mapped active votes to {args.output}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
