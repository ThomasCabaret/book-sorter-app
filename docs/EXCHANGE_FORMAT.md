# BookDuels standalone exchange format v1

Archive: `.tar.gz` with USTAR file entries. Encoding: UTF-8. `manifest.json`
contains a `format` string exactly `bookduels.bundle.v1`, a creation timestamp,
counts and `files[name] = {"bytes": N, "sha256": "..."}` for the other three
members. The file hashes authenticate neither the user nor the device.

## catalog.json

An array of objects, always including:

- `id` (unique, permanent work ID), `title`, `author`, `enabled` (boolean)
- `isbnAliases` (array of valid ISBN-13 strings, mapped to this work)
- `externalRefs` (array of opaque namespaced identifiers such as
  `babelio:SI81168757195`)
- `isbn13` (optional primary edition ISBN) and `coverUrl` (optional HTTPS URL)

No rating from Babelio appears in the catalog. A work can have several edition
identifiers; never infer that two different work IDs are identical solely
because their title is similar.

## events.jsonl

Each line is a JSON object containing `seq` (ascending integer), `eventId`
(unique string), `timestampMs` (Unix epoch milliseconds), and `kind`:

- `VOTE`: `firstBookId` and `secondBookId` are the shown top/bottom IDs,
  `chosenBookId` equals one of them.
- `JOKER`: shown pair, no `chosenBookId`.
- `INVALIDATE` / `REINSTATE`: `targetSeq` refers to a previous VOTE or JOKER
  event and `batchId` identifies the compensation batch.

Fields not present are equivalent to `null`. Only base events not subsequently
invalidated contribute to active scores. Apply invalidations and reinstatements
in `seq` order to reconstruct current state. Preserve all events even when
computing a condensed preference matrix.

## Desktop interface

```bash
python3 scripts/inspect_bundle.py real-export.tar.gz --emit-pairs active-pairs.json
```

`active-pairs.json` contains `format = bookduels.pairwise.v1`, `bookCount`, and
`pairs`, each with `firstBookId`, `secondBookId`, `firstWins`, `secondWins`,
`jokers`. The pair is sorted lexicographically by stable book ID (not screen
position). Active Jokers are counted but not used as preference wins.

The exchange format is intended for a future desktop collaborative-filtering
program. Join books with Goodreads/Goodbooks/other datasets by trusted ISBN
edition identifiers and controlled title/author metadata, not by numeric
`book_id` reinterpretation or by Babelio star ratings.

**Do not reuse any ID, discard invalidated events, automatically overwrite a
local journal with a foreign bundle, or promise SHA-256 proves who created it.**
