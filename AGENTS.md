# BookDuels v0.6 — instructions for coding agents

## Core objective

Single-user Google Pixel 7a app. Two stacked book cards, swipe either card left
or right to choose that book; Joker = no decision. Ranked Pairs for provisional
Condorcet-compatible ordering, regularized Bradley-Terry for uncertain
comparisons and active pair selection. A disabled book is excluded from current
ranking/duels, not from the durable event log.

## Non-negotiable persistence and safety invariants

- Stable `book_id` identifies a *work*, not an edition. Never recycle/remap it.
- SQLite `events` is append-only. No UPDATE, DELETE, truncate, DROP, destructive
  migration fallback, nor silent reinstall reset of event data.
- Historical corrections use `INVALIDATE` and `REINSTATE`, recording target and
  batch in new rows; perform them transactionally with revision checks.
- Historical rollback/reinstatement from the History screen, destructive
  restore, reset, replacement, or arbitrary event mutation MUST use BOTH an
  explicit high-friction confirmation and fresh Android strong biometric or
  secure-device credential validation.
- The duel screen has one narrow exception: a visible quick-undo button may
  append one `INVALIDATE` for the latest still-active decision recorded during
  this running app session. Keep a strict LIFO stack capped at three, require a
  revision check, never delete/update events, and never target arbitrary or
  older history through this path.
- CSV import/add-book/edit-visibility must NEVER update event rows or delete
  catalog entries; canceled imports make no partial changes.
- V1 migration preserves all V1 events and uses disabled placeholders for
  unknown legacy book IDs. Never silently merge legacy IDs with new books.
- A portable export must snapshot books, all identifier aliases and all
  historical events consistently; never export only currently active events.
- Do not assume disabling a book erases it. Import never implicitly disables.
- Android app data clear/uninstall can still delete local data. Never promise
  protection against operating-system deletion.

## Babelio CSV format and identity

- CSV has semicolon-separated quoted columns `ISBN`, `Titre`, `Auteur`, `Statut`,
  `Note`, and dates/publisher metadata. Typically Windows-1252/Latin-1, but
  UTF-8 is also allowed.
- Import **only** rows with normalized `Statut == "lu"`; discard all ratings.
  Never convert star ratings into duels, seed ranking, or export them.
- 152 initial Babelio rows: 146 genuinely valid ISBN-13, SIX alphanumeric
  export references masquerading as ISBN. These are stored as namespaced
  `babelio:...` identifiers. NEVER pass them to an ISBN covers API.
- The curated seed retains 155 records: three missing whole works were added;
  seven redundant split/omnibus/unread saga entries start disabled.
- Prefer exact ISBN/opaque external reference matching; conservative normalized
  title AND author only when unique; otherwise skip as ambiguous. Different
  edition identifiers are aliases for one work where supported by evidence.
- Never override existing book metadata based on reimported rows.

## On-device and desktop boundaries

- Device history stored in local SQLite; UI in French, code/docs/identifiers in
  English; avoid analytics, accounts, background bulk scraping, or cloud sync.
- Covers from Open Library Covers endpoint **on demand** with Coil caching; do
  not preload the catalog or scrape Babelio/Goodreads HTML.
- Android export uses a real USTAR/GZIP file with manifest SHA-256 checks,
  `catalog.json`, complete `events.jsonl` and `README.txt`.
- `scripts/inspect_bundle.py` validates bytes and event-reference semantics
  before generating active pairwise counts. SHA-256 is not authentication.
- Do not implement Android history import without signed-off format validation,
  explicit user preview/merge plan and protected confirmation.

## Mathematical honesty

- Ranked Pairs locks observed majority edges. Bradley-Terry only completes
  unconstrained topological order and proposes new duels; it does not imply
  strict Condorcet dominance in sparse observations.
- The `adjacentStability` and BT standard errors are **heuristics**, not
  calibrated posterior rank probabilities.
- Never treat Joker as tie; aggregate repeated jokers and suppress proposals
  of that pair by the current cubic penalty.
- While unseen pairs remain, independently sample a hidden consistency recheck
  for each proposal (5% by default, user-configurable). The Android caller must
  derive stable per-proposal entropy from a private random installation secret:
  never use a visible periodic cadence and never reveal whether the current
  pair is a recheck. Select rechecks from seen
  pairs using model uncertainty, direct disagreement and Ranked Pairs cycle
  tension; repeated Jokers remain cubically suppressed. Aggregate consistency
  statistics may appear only in Settings. This is a heuristic audit, not a
  psychological measurement.

## Build and test

- `./scripts/test-core.sh` pure Kotlin parser/ranking/replay tests.
- `./scripts/test-archive.sh` produces tar.gz in Kotlin and opens in Python.
- `python3 scripts/test_bundle.py` checks good and tampered desktop archives.
- `python3 scripts/test_recommender.py` checks the offline recommendation core.
- `python3 scripts/inspect_bundle.py samples/initial-catalog-example.tar.gz`
  verifies the shipped 155-book/zero-event example.
- `./gradlew :app:assembleDebug`, JVM unit tests, and an initial Pixel 7a
  Android 17/API 37 install/start/navigation/export smoke test are verified.
- Before release: instrumentation for SQLite v1->v3 and v2->v3 migrations, SAF encoding,
  Compose swipe semantics, biometric fail-closed and Pixel 7a install/upgrade.

## V3 known omissions

- No secure Android import/restore of portable tarballs.
- No manual metadata/ISBN/cover URL edit, explicit work merge, or bulk cover
  discovery by title/author.
- No off-device automated backup or archive encryption/signature.
- No automated on-device interaction suite yet; the initial manual/ADB smoke
  test is documented in `docs/PIXEL7A_SMOKE_TEST.md`.
