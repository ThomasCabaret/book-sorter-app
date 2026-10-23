# Offline recommendation prototype

BookDuels v0.6 includes a desktop-only, dependency-free prototype that compares
the reader's pairwise order with other readers' coarse star-rating orders. Raw
third-party ratings are never copied into the Android app, the SQLite event
log, a portable BookDuels export, or Git.

## Dataset choice

The first supported source is
[goodbooks-10k](https://github.com/zygmuntz/goodbooks-10k): 10,000 popular
works, six million 1–5 ratings from 53,424 users, work-level aggregation,
ISBN-13 metadata, and user tags including fantasy and science fiction. Its
`ratings.csv` is about 69 MB. The dataset is published under
[CC BY-SA 4.0](https://github.com/zygmuntz/goodbooks-10k/blob/master/LICENSE).
Keep the downloaded CSV files in `data/goodbooks-10k/`, which is ignored by
Git.

Two larger sources were evaluated but are not the default:

- The [UCSD Goodreads Book Graph](https://cseweb.ucsd.edu/~jmcauley/datasets/goodreads.html)
  has a particularly relevant Fantasy & Paranormal subset (258,585 books and
  about 55.4 million interactions), but the publisher limits it to academic,
  non-commercial use and forbids redistribution. It is suitable only for an
  explicitly compliant desktop experiment.
- [Amazon Reviews 2023](https://amazon-reviews-2023.github.io/) contains about
  29.5 million ratings for 4.4 million items in its Books category. It is much
  larger, identifies products primarily by ASIN rather than stable works, and
  is a poorer first match for BookDuels' ISBN/work identity model.

Goodbooks-10k is therefore the pragmatic first experiment. Embedding its raw
ratings in an APK would waste space and battery. A future phone feature should
consume only a reviewed compact derivative (for example, a small list of
recommendations or quantized item factors) and include the attribution and
share-alike notices required by the dataset license.

## Method

`scripts/recommend_goodbooks.py` performs these deterministic offline steps:

1. Validate the complete BookDuels archive and replay its append-only history.
2. Fit the same regularized Bradley–Terry scores used by the app, from active
   votes only. Jokers remain non-votes.
3. Match works first through a curated Goodreads work alias, then exact ISBN-13,
   then an exact normalized title **and** author only when unique. Ambiguous
   matches are reported and skipped.
4. Discard external readers with fewer than 25 ratings by default. For every
   remaining reader with enough overlap, compare all non-tied book
   pairs. The similarity is a shrunk concordance score: agreement on relative
   order raises it, disagreement lowers it, and tiny overlaps are discounted.
5. Select the most similar positive neighbors and rank unread candidates from
   their mean-centered ratings. Already matched catalog works are excluded.
6. If tag files are supplied, keep only a conservative fantasy, SF,
   speculative-fiction, dystopia, horror, or paranormal tag set.

This is deliberately interpretable and small. Goodreads ratings are coarse,
selection-biased observations, so the output is a recommendation heuristic,
not a probability that the reader will like a book.

## Usage

Download `books.csv`, `ratings.csv`, and optionally `tags.csv` plus
`book_tags.csv` from the goodbooks-10k release into the ignored local folder.
Export BookDuels from the phone, then run:

```powershell
python scripts\recommend_goodbooks.py `
  C:\path\to\bookduels-export.tar.gz `
  --books data\goodbooks-10k\books.csv `
  --ratings data\goodbooks-10k\ratings.csv `
  --tags data\goodbooks-10k\tags.csv `
  --book-tags data\goodbooks-10k\book_tags.csv `
  --min-reader-ratings 25 `
  --output recommendations.json
```

Omit both tag arguments to inspect recommendations across all genres. The
script makes two streaming passes over `ratings.csv`; memory use is bounded by
reader summary data rather than six million full rating objects. The output is
a small `bookduels.recommendations.v1` JSON report containing diagnostics,
scores, support counts and book metadata. It is ignored by Git by default.

## Editions and works

An ISBN identifies an edition, not the abstract work. Goodbooks-10k already
solves this **inside its ratings data**: its documentation states that the
`book_id` used by `ratings.csv` maps to `work_id`, so ratings for different
editions are aggregated. The remaining problem is identifying which Goodreads
work corresponds to a BookDuels ISBN when goodbooks-10k lists another edition.

The recommender uses three conservative levels:

1. an explicit local `bookduels_book_id` → `goodreads_work_id` correction;
2. an ISBN-13 equal to the representative goodbooks edition;
3. an exact normalized title-and-author match, only when unique.

For explicit corrections, create an ignored CSV such as
`data/goodbooks-10k/work_aliases.csv`:

```csv
bookduels_book_id,goodreads_work_id
stable-local-id,3634639
```

Then add `--work-aliases data\goodbooks-10k\work_aliases.csv`. This is also the
safe answer for split French pocket volumes, omnibuses, translations with
unrelated titles, or catalog mistakes: these cases cannot be inferred reliably
from ISBN syntax alone and must not be silently merged.

For a larger one-time edition table, two sources are possible. The UCSD
Goodreads Book Graph contains about 2.36 million edition records and 1.52
million works, but it is academic-only and cannot be redistributed. Open
Library exposes explicit Work and Edition records through its
[book APIs](https://openlibrary.org/dev/docs/api/books) and monthly
[bulk dumps](https://openlibrary.org/developers/dumps). Its API guidelines say
not to perform hundreds of per-book harvest requests, so any broad resolver
should use a bulk dump; low-volume manual ISBN corrections may use the ISBN API
and cache the answer. Neither source justifies a fuzzy automatic merge.

## Size policy

The 69 MB raw ratings file is acceptable on a desktop and would technically fit
on the phone, but shipping it would make every installation pay for data that
is mostly irrelevant. The current pipeline instead removes readers below the
configurable `--min-reader-ratings` threshold and can restrict candidates to
the conservative fantasy/SF tag set. Only the final recommendation JSON—usually
kilobytes—needs to be considered for a future phone import. Before an adaptive
fully on-device model is added, real filtered sizes and recall should be
measured on the downloaded data rather than guessed.

Run its synthetic tests with:

```powershell
python scripts\test_recommender.py
```

No Android importer is provided in v0.6. Adding one requires a separately
reviewed format, preview and merge behavior; it must not mutate historical
events or silently add external ratings as duels.
