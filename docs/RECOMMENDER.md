# Offline recommendation prototype

BookDuels v0.6 includes a desktop-only interpretable-neighbor prototype and a
heavier pairwise latent-model trainer. Raw third-party ratings are never copied
into the SQLite event log, a portable BookDuels export, or Git. Only a compact,
attributed model derivative may later be reviewed for Android packaging.

## Dataset choice

The first supported source is
[goodbooks-10k](https://github.com/zygmuntz/goodbooks-10k): 10,000 popular
works, six million 1–5 ratings from 53,424 users, work-level aggregation,
ISBN-13 metadata, and user tags including fantasy and science fiction. Its
`ratings.csv` is about 69 MB. The dataset is published under
[CC BY-SA 4.0](https://github.com/zygmuntz/goodbooks-10k/blob/master/LICENSE).
Keep the downloaded CSV files in `data/goodbooks-10k/`, which is ignored by
Git.

The official v1.0 archive has now been downloaded locally from the GitHub
release. It is 32,675,160 bytes with local SHA-256
`261b97b56db61f3fb2ce5aadbb13704d30179fcc986c17ace665a0af9ed00731`.
The extracted source contains 5,976,479 ratings from 53,424 readers. It remains
ignored and is not redistributed by this repository.

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

The real distribution shows that 25 is only a quality guard, not a useful size
reduction for this already-filtered dataset: it retains 53,390 readers and
5,975,712 ratings. A threshold of 100 retains 37,084 readers and 4,615,637
ratings; 150 collapses to 4,235 readers and 688,371 ratings. The training stage
must therefore select the threshold by held-out ranking quality, not by an
arbitrary storage target. The conservative fantasy/SF tag set covers 4,637 of
the 10,000 works and 3,197,648 ratings before the reader filter.

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
The source CSV stores most ISBN-13 values in a shortened scientific notation;
the loader restores the lost thirteenth checksum digit from the preserved first
twelve digits before attempting an exact match.

The recommender uses four conservative levels:

1. a reviewed local `bookduels_book_id` → `goodreads_work_id` correction;
2. a cached Open Library ISBN result whose Goodreads edition ID occurs in
   goodbooks-10k;
3. an ISBN-13 equal to the representative goodbooks edition;
4. an exact normalized title-and-author match, only when unique.

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

### Cached resolver

`scripts/resolve_work_aliases.py` queries Open Library Search in small batches,
identifies itself, waits between requests, and stores responses in the ignored
data directory. It never treats title similarity alone as proof. Reviewed
translations live separately in `config/goodbooks_work_aliases.csv`, including
their human-readable evidence. Unknown external work IDs, unknown local IDs,
and two enabled local records resolving to one Goodreads work fail or become
explicit ambiguities rather than silent merges.

```powershell
python scripts\resolve_work_aliases.py `
  --catalog app\src\main\assets\books.json `
  --books data\goodbooks-10k\books.csv `
  --cache data\goodbooks-10k\openlibrary_resolution_cache.json `
  --reviewed-aliases config\goodbooks_work_aliases.csv `
  --report data\goodbooks-10k\work_resolution_report.json `
  --output data\goodbooks-10k\work_aliases.csv
```

The current run resolves 71 of 155 retained records, including 69 active
records, with zero ambiguity. The unresolved set is expected: many French or
recent genre works are outside the 2017-era goodbooks top 10,000. French pocket
splits such as the two active *Anatèm* volumes are intentionally not forced
onto one external work.

## Pairwise latent model

`scripts/train_latent_recommender.py` learns a Bradley-Terry/BPR-style matrix
factorization from the relative order induced by external 1–5 ratings. It does
not read a BookDuels archive or any personal duel. Ten percent of eligible
external readers are completely excluded from global training. For each of
those readers, the validator freezes item factors, fits a small personal vector
from support comparisons, and scores unseen query comparisons. This directly
simulates the later phone-side operation.

The selected reproducible run uses 48 dimensions, 53,390 eligible readers,
48 sampled pairs per training reader for six epochs, and a strongly shrunk
single personalization step. On 5,339 held-out readers and 330,882 query pairs:

- personalized latent model: 66.537% accuracy, 0.6108 log-loss;
- item-bias-only baseline: 63.493% accuracy, 0.6441 log-loss;
- mean-rating baseline: 63.411% accuracy, 0.6488 log-loss.

The compressed 48-dimensional artifact is 5,400,664 bytes. A 32-dimensional
variant reached 66.44% at 3.69 MB; 64 dimensions reached 66.45% at 7.11 MB, so
48 dimensions remains the measured choice. The checked-in benchmark manifest
is `docs/benchmarks/goodbooks-latent-48d.json`; raw data and generated `.npz`
files remain ignored.

```powershell
python scripts\train_latent_recommender.py `
  --books data\goodbooks-10k\books.csv `
  --ratings data\goodbooks-10k\ratings.csv `
  --output data\goodbooks-10k\models\pairwise-48d.npz `
  --manifest data\goodbooks-10k\models\pairwise-48d.manifest.json
```

The artifact contains compact item factors and anonymous external-reader
factors, not raw rating rows. Item factors provide the validated collaborative
prediction; reader factors preserve a path for an explicit nearest-profile
hybrid, but that second scoring layer must itself be evaluated before being
claimed better or shipped. On-device integration is still pending.

### Personal inference from a verified export

`scripts/recommend_latent.py` validates a portable BookDuels archive, resolves
its works with the same conservative aliases, and fits only the small personal
vector. Only active votes whose two books are active and unambiguously matched
are used. Jokers are ignored rather than treated as ties. Every matched catalog
work, including a disabled one, is excluded from candidates so that a known
edition is not recommended back to the reader.

```powershell
python scripts\recommend_latent.py C:\path\to\bookduels-export.tar.gz `
  --model data\goodbooks-10k\models\pairwise-48d.npz `
  --books data\goodbooks-10k\books.csv `
  --work-aliases data\goodbooks-10k\work_aliases.csv `
  --tags data\goodbooks-10k\tags.csv `
  --book-tags data\goodbooks-10k\book_tags.csv `
  --output recommendations-latent.json
```

The output includes predicted books and an optional nearest-reader diagnostic.
The latter is deliberately not used to score recommendations: cosine-neighbor
retrieval has not yet shown an improvement on the held-out benchmark. The
validated score is the fixed-item-factor prediction from the personal vector.

## Size policy

The 69 MB raw ratings file is acceptable on a desktop but is not needed on the
phone. The validated factor derivative is about 5.4 MB and contains no raw
rating rows. Candidate filtering can still use the conservative fantasy/SF tag
set. Packaging, license attribution, Kotlin inference, and upgrade semantics
must be reviewed before that derivative becomes an Android asset.

Run its synthetic tests with:

```powershell
python scripts\test_recommender.py
python scripts\test_work_resolver.py
python scripts\test_latent_recommender.py
python scripts\test_latent_inference.py
```

No Android importer is provided in v0.6. Adding one requires a separately
reviewed format, preview and merge behavior; it must not mutate historical
events or silently add external ratings as duels.
