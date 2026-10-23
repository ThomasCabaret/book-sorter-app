# BookDuels v0.6 — Offline-first Android app (Pixel 7a)

An Android app for a single reader. Compare any two read books with an
independent horizontal swipe on either the upper or lower book card. The card
swiped left **or** right is the preferred book. A separate Joker action means
"decline to choose" and does not introduce a tie or a preference vote.

This is a **source project**, not a release APK. The v0.6 JVM tests, Python
exchange tests, debug APK build, and lint pass with Android SDK/API 37. The
v0.5 set-aside/database migration was installed on the Pixel 7a without losing
the existing event log. The new v0.6 consistency-check flow still needs its
short on-device interaction check when the phone is next connected.

## Features

- A persistent, editable on-device SQLite book catalog seeded from
  `app/src/main/assets/books.json` at first launch.
- **152 read-book rows** from the supplied Babelio export bundled as initial data;
  57 "A lire" and 12 "En cours" entries omitted. **Every Babelio rating is ignored.**
- All 152 original references are preserved: **146 valid ISBN-13** and **6 opaque
  alphanumeric Babelio references**. Opaque references are not passed to the
  Open Library ISBN Covers API.
- The curated catalog retains 155 records: three missing whole novels were
  added and seven redundant split-volume, omnibus, or unread saga entries start
  **set aside**. This yields 148 active works without erasing any original row.
- Import another Babelio CSV from the Android file picker. Supports UTF-8,
  Windows-1252/ISO-8859-1, semicolon separators, escaped double quotes and
  `Statut=Lu` filtering. Other statuses are ignored; they never disable books.
- CSV imports are incremental and transactionally idempotent. An ISBN or
  external reference is matched first; an exact normalized title **and** author
  pair is a conservative fallback. Unclear multi-matches are skipped.
- Add a read book manually, with optional valid ISBN-13. Search the catalog,
  put books aside and restore them without changing vote history. The duel
  cards expose the same quick set-aside action.
- Ranked Pairs (Tideman) over observed majority edges with a regularized
  Bradley-Terry estimate for unconstrained ranking and pair acquisition.
  While unseen pairs remain, an unpredictable 5% by default of proposals
  silently recheck a seen pair selected from model uncertainty, conflicting
  answers, cycle tension and a cubic penalty for repeated Jokers. The rate is
  adjustable in Settings and the duel never reveals that it is a recheck.
  Metrics are heuristics,
  **not calibrated confidence probabilities or psychological measurements**.
- An offline desktop recommender can compare the pairwise order with similar
  readers from a locally downloaded goodbooks-10k dataset and produce a small
  JSON report. Third-party raw ratings are never bundled in the APK or Git.
- SQLite **append-only** vote/joker/invalidation/reinstatement event log.
  Historical rollback/reinstatement remains gated by an explicit confirmation
  AND secure device authentication (strong biometric or screen-lock credential).
- A true twilight theme uses deep blue-charcoal surfaces, light text, muted
  teal actions and warm accents instead of a dimmed white interface.
- A tap or swipe commits immediately, flashes the selected card for 250 ms,
  then shows the next duel. A persistent **Annuler** control can undo the last
  three choices from the current app session, one at a time, and returns to the
  corresponding duel. Each undo appends a compensating `INVALIDATE` event;
  it never deletes or rewrites the original decision.
- On-demand ISBN cover requests from Open Library (Coil cache; no bulk scraping).
  Missing images display a placeholder. No Babelio/Goodreads scraping.
- A portable `tar.gz` export with `manifest.json`, `catalog.json`,
  `events.jsonl`, `README.txt`; per-file SHA-256 and byte lengths, consistent
  SQLite snapshot, all original historical events. A desktop validator and
  active pairwise-preferences extractor are included.

## Running on the Pixel 7a

Requirements: Android Studio, Android SDK API 37, JDK 17+, and network access
on the developer machine for Gradle dependencies (not required for normal
offline app use except on-demand cover images).

```bash
./scripts/test-core.sh
./scripts/test-archive.sh
python3 scripts/test_bundle.py
python3 scripts/test_recommender.py
python3 scripts/test_work_resolver.py
python3 scripts/test_latent_recommender.py
python3 scripts/test_latent_inference.py
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On this Windows workstation, `local.properties` already points to the local SDK
(the file is intentionally ignored by Git). Android Studio's bundled JDK can be
used from PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat test :app:assembleDebug
python scripts\test_bundle.py
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

The standard Gradle Wrapper is bundled for Linux/macOS and Windows, pins Gradle
9.3.1, validates the official distribution URL and checks its SHA-256 digest.
The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## First Git publication

The hand-off tarball, local SDK path, IDE metadata, caches and build outputs are
ignored. Once the destination repository exists, run:

```bash
git init
git add .
git status
git commit -m "Initial BookDuels Android app"
git branch -M main
git remote add origin https://github.com/ThomasCabaret/book-sorter-app.git
git push -u origin main
```

If the remote already contains an initial commit (for example a generated
README), reconcile that history before pushing rather than forcing it.

## Pixel 7a validation

An initial Android 17/API 37 smoke test is recorded in
`docs/PIXEL7A_SMOKE_TEST.md`. For another device or build, enable Developer
options and USB debugging, connect the unlocked phone, accept its RSA
fingerprint prompt, then run the PowerShell commands above. Before treating the
build as release-ready, complete the remaining interaction checks:

1. The first launch seeds 155 retained records (148 active) and a relaunch preserves votes.
2. Both card swipes and both explicit choice buttons commit exactly one choice,
   show the 250 ms selection flash, and advance immediately. Verify that the
   bounded three-choice undo stack unwinds in strict reverse order.
3. Covers load only on duel cards, remain reasonably sharp, and the app cache
   stays bounded while many different books are shown.
4. A Windows-1252 Babelio CSV import is previewed/applied atomically; unread
   rows and ratings do not affect the catalog or ranking.
5. Putting a book aside from either a duel card or the library removes it and
   its pairwise results from the current ranking; restoring it brings those
   preserved duels back.
6. Neutralize/reinstate actions fail closed without secure authentication and
   succeed only after a fresh Pixel credential/strong-biometric prompt.
7. The exported tarball passes `scripts/inspect_bundle.py` on the PC.
8. Installing this debug build with `adb install -r` preserves the database and
   applies the one-time saga curation; explicitly test direct v1-to-v3 and
   v2-to-v3 migration with retained fixtures before a release build.

On first launch the SQLite catalog gets seeded with 155 retained records: the
152 original Babelio rows plus three explicitly curated whole novels. It is
thereafter the source of truth. New versions of the APK can add new entries to
the seed catalog; the startup merge **never replaces existing books, resets
visibility choices, or modifies event rows**. A separate one-time v0.5 curation
implements the requested saga corrections without changing any event. Re-importing
the exact original CSV reports 0 new books (as expected).

## Android UI

- **Duels**: two stacked cards; swipe either book left or right to choose it,
  or press its explicit button. The chosen card is highlighted and labelled
  for 250 ms before the next duel appears. **Annuler** unwinds up to the last
  three choices made in the current app session and reopens the relevant duel.
  Joker follows the same fast flow. **Mettre de côté** removes either displayed
  book from upcoming duels and the ranking without deleting its history. No
  decision is saved for just opening a duel.
  By default, each proposal has an independent 5% chance of silently revisiting
  a useful non-trivial pair. This is deliberately indistinguishable from an
  ordinary duel and is not tied to the visible vote count.
- **Classement**: provisional Ranked Pairs order, total votes, jokers,
  comparison coverage, graph components, skipped cyclic edges, uncertain
  Bradley-Terry proxy; a strict direct Condorcet winner is shown only when
  supported by direct head-to-head results against every other active book.
- **Livres**: import Babelio CSV, add books, search, and switch among **Actifs**,
  **Mis de côté**, and **Tous**. Putting a book aside removes it from current
  pair selection/ranking, **not from history**; it can be restored at any time.
- **Historique**: chronological user decisions, neutralize an active suffix,
  reinstate the most recent batch; export a complete portable `.tar.gz`.
- **Réglages**: adjust the hidden consistency-check rate from 0 to 20% and see
  aggregate repeat consistency statistics. It never identifies the current or
  any individual verification duel.

A CSV with `Statut=Lu` entries is a **list of read books**, not an ordered list
and not a source of pairwise outcomes. Ratings are never included in the
SQLite database or in portable exports.

## Book identity and merging

The SQLite `book_id` is immutable, is the only key used by duel events, and
represents a *work* rather than a particular edition. It is never reassigned.

- Valid numeric ISBN-13 values with correct checksums are recorded in
  `book_isbns` and used for thumbnail retrieval. The first (primary) ISBN is
  kept; another edition may become an alias without changing `book_id`.
- Non-ISBN references from the Babelio `ISBN` column are stored in
  `book_external_refs` prefixed by `babelio:`. They remain stable matching
  keys but **must not** be treated as ISBNs or sent to cover APIs.
- A matching imported ISBN/external reference finds the existing book.
- Otherwise an exactly matching normalized title **and** author can attach an
  additional ISBN/reference to the same work. Normalization handles case,
  diacritics and whitespace/punctuation; it is deliberately not fuzzy.
- If multiple catalog records match by title/author, import skips the row
  rather than guessing. Imported metadata never overwrites an existing
  book's title/author; import and rollback of SQLite changes are atomic.
- An item not present in a newer CSV is **kept**. An item with `A lire` or
  `En cours` in a newly imported CSV is **ignored**, never un-read/disabled.
- The manual add feature follows the same deduplication rules.

### Explicit saga curation (v0.5)

The one-time catalog curation keeps exactly the requested whole works active:

- Dune: `Dune`, `Le Messie de Dune`;
- Hyperion: `Hypérion`, `La Chute d’Hypérion`, `Endymion`,
  `L’Éveil d’Endymion`;
- Foundation: `Fondation`, `Fondation et Empire`.

Seven pre-existing box-set, split-pocket, or unread entries are retained with
their stable IDs but start set aside. Existing votes involving them stay in the
append-only log and are ignored only while that book remains set aside.

**Known limitation:** different editions with significantly different titles or
author formatting can remain distinct works unless manually reconciled in a
future version. This avoids silently merging two actually different books.
The UI presently has no work-merging, title editing, or manual cover-URL
editing function.

## Offline recommendations

The optional desktop pipeline is documented in
[`docs/RECOMMENDER.md`](docs/RECOMMENDER.md). It validates a portable export,
matches exact ISBN-13 aliases, finds external readers whose relative ordering
agrees with the BookDuels order, and ranks books favored by those neighbors.
Goodbooks ratings already target abstract Goodreads works rather than editions;
exact ISBN, Open Library's cached edition links, unique title+author, and the
reviewed `config/goodbooks_work_aliases.csv` bridge the local catalog to those
works. The current resolver safely matches 76 of the 155 retained records (74
active), with no silent merge of split French volumes. The evidence and
rejected near-matches are recorded in
[`docs/GOODBOOKS_MAPPING_AUDIT.md`](docs/GOODBOOKS_MAPPING_AUDIT.md).
Goodbooks-10k is the first supported source because its 10,000 work-level
records and six million ratings are manageable on a PC and cover fantasy/SF
through tags. Raw CSV files and generated personal reports are ignored.

The heavier pairwise factor model is trained entirely from the external
ratings, independently of BookDuels votes. On a fixed set of 5,339 completely
held-out readers, the selected 48-dimensional model reaches 66.54% accuracy on
330,882 unseen preference pairs versus 63.49% for its item-bias baseline. Its
compressed derivative is 5.40 MB. These are empirical ranking metrics, not a
probability that a recommendation will be liked.

There is intentionally no on-device recommendation import yet. A later phone
extension should use only a compact, attributed derivative after its data
format, preview and merge rules have been reviewed; external ratings must never
be converted into historical duel events.

## Cover policy

When a book has a valid ISBN, `Book.thumbnailUrl()` returns:

```
https://covers.openlibrary.org/b/isbn/<ISBN>-M.jpg?default=false
```

The images are requested only when a duel card is displayed and cached by
Coil; the library list deliberately does **not** preload hundreds of images.
The app uses the medium Open Library rendition, decodes it to the measured card
size, limits the in-memory cache to 8% of the process budget, and caps the
on-disk thumbnail cache at 32 MiB. Android may clear that cache at any time.
Some ISBNs have no associated cover; a failed request is labelled **Jaquette
indisponible** rather than leaving an unexplained empty rectangle. Six initial
opaque book references do not produce an ISBN URL and are labelled **Sans
jaquette**. Open Library's
cover API is rate limited for ISBN requests (at the time of design, 100
requests/IP/5 minutes). The app does not scrape Goodreads or Babelio.
Reference: https://openlibrary.org/dev/docs/api/covers

## Durable, reversible history

The private SQLite file `bookduels.db` is event-sourced:

| Event | Meaning |
| --- | --- |
| `VOTE` | One explicit win for a selected book in the shown pair |
| `JOKER` | No decision; acquisition penalty for re-showing that pair |
| `INVALIDATE` | Deactivate an earlier vote/Joker by sequence number |
| `REINSTATE` | Reactivate an earlier base event |

History is ordered by increasing `seq` with stable `eventId`, timestamp,
actual top/bottom IDs, winner if any, plus batch and target sequence numbers
for compensating actions. Every update uses SQLite transactions. The ranking
uses replayed **active** base events only. The complete log remains available
in exports, including events that are currently invalidated.

A selected history row can trigger **"neutralize all active base events from
this sequence onward"**; an explicit confirmation and Android
`BiometricPrompt` authentication precede database mutation. A reinstate batch
is equivalently protected. Operations also check expected log revision to
avoid applying a stale confirmation.

The duel screen's bounded quick undo is deliberately narrower: it can target
only the latest still-active choice held in a three-item, current-session LIFO
stack. The visible undo tap is the confirmation; the store verifies the exact
history revision and that the target is still the newest active base event,
then appends one `INVALIDATE`. Arbitrary/older history edits and reinstatement
remain behind the protected confirmation flow above.

**Database migration:** direct schema v1 -> v3 preserves every event row and
creates the catalog and curation-marker tables; v2 -> v3 only adds that marker
table. V1 book metadata was stored only in APK assets, so unresolved event
references are preserved as
**disabled legacy placeholders**, not silently remapped or discarded. The
placeholders cannot automatically be resolved to a new work without evidence.
After the ordinary non-overwriting seed merge, the requested saga curation is
applied transactionally once; it changes titles/visibility only and never event
rows.

**Limits:** uninstalling the app or clearing Android app storage can still
destroy local data, and a rooted/compromised device is outside the protection
model. `android:allowBackup=false` means **Android's automatic backup is not
an off-device recovery mechanism**. Export a tarball regularly. Exported
tarballs are **unencrypted** and expose reading preferences.

## Desktop exchange (`tar.gz`)

The app's export dialog writes the following standard USTAR/GZIP members:

```text
bookduels-backup.tar.gz
  manifest.json     - format, counts, SHA-256 and byte count for each other file
  catalog.json      - all books, stable IDs, primary ISBN, ISBN aliases, external refs, enabled flags
  events.jsonl      - chronological full event log including invalidation/reinstatement actions
  README.txt        - short format and privacy guide
```

Sample compact `events.jsonl` rows:

```json
{"seq":1,"eventId":"sample-1","timestampMs":1760000000000,"kind":"VOTE","firstBookId":"isbn:9780306406157","secondBookId":"manual:abc","chosenBookId":"manual:abc"}
{"seq":2,"eventId":"sample-2","timestampMs":1760000001000,"kind":"JOKER","firstBookId":"isbn:9780306406157","secondBookId":"manual:abc"}
{"seq":3,"eventId":"sample-3","timestampMs":1760000002000,"kind":"INVALIDATE","targetSeq":1,"batchId":"batch-1"}
```

Keys omitted from a row represent `null`. A desktop application must **replay
the event log** rather than naively counting every historical `VOTE` row.
Original Babelio ratings are **not included**.

A standalone **example export** containing the curated 155 records and **zero
votes** is included at `samples/initial-catalog-example.tar.gz`. It is not a
backup of the user's future votes.

On the desktop:

```bash
python3 scripts/inspect_bundle.py samples/initial-catalog-example.tar.gz
python3 scripts/inspect_bundle.py /path/to/real-export.tar.gz --emit-pairs /tmp/bookduels-pairs.json
```

The validator rejects malformed archives, unexpected tar members, missing or
incorrect SHA-256 digests, ambiguous IDs/ISBNs, and invalid journal references.
`--emit-pairs` outputs active win counts in both directions and active joker
counts for each observed pair, ready for a collaborative-filtering companion.
The hash checks detect corruption/unsanctioned changes but are **not an
authenticating digital signature**: a malicious party could recreate the
manifest. Treat user-supplied archives as untrusted.

There is **no Android archive restore** in V3. A future restore must validate
everything, explicitly preview effects, require fresh secure-device auth,
and never silently replace vote history or reuse book IDs.

## Project layout

```text
app/src/main/assets/books.json           155 retained catalog records; no star ratings
app/src/main/java/org/example/bookduels/
  core/Book.kt                            Work/duel/event models and replay
  core/BabelioCsv.kt                      CSV parser and reference classification
  core/TarGzipWriter.kt                   Pure Kotlin USTAR/GZIP writer
  core/DuelEngine.kt                      Ranked Pairs, BT scores, acquisition
  data/BookCatalog.kt                     Seed parsing
  data/EventStore.kt                      SQLite 3 schema, merging, curation, event log
  data/PortableArchive.kt                 Export schema and checksums
  ui/MainActivity.kt                      SAF pickers and biometric prompt
  ui/MainViewModel.kt                     Serialized writes and UI state
  ui/BookDuelsApp.kt                      Duel/ranking/history Compose UI
  ui/LibraryScreen.kt                     Incremental CSV import and manual add
scripts/test-core.sh                      Kotlin core and CSV parser smoke checks
scripts/test-archive.sh                   USTAR/GZIP interoperability smoke check
scripts/test_bundle.py                    Positive/negative Python bundle tests
scripts/inspect_bundle.py                 Verified read-only desktop bundle parser
scripts/make_example_export.py            Demo export from seed (no votes)
scripts/resolve_work_aliases.py            Cached ISBN/work identity resolver
scripts/train_latent_recommender.py        Pairwise external-ratings model trainer
scripts/recommend_latent.py                Personal-vector fit and latent recommendations
config/goodbooks_work_aliases.csv          Reviewed French-to-Goodreads work links
samples/initial-catalog-example.tar.gz    Real standalone tarball with zero votes
docs/EXCHANGE_FORMAT.md                   Schema for a future desktop companion
docs/GOODBOOKS_MAPPING_AUDIT.md           Reviewed aliases and rejected near-matches
AGENTS.md                                 Codex implementation guardrails
```

## Next high-value engineering work

1. Build with the pinned dependencies; fix any Android Studio/API compatibility
   issues, then run instrumented SQLite migration and Compose gesture tests.
2. Verified **authenticated backup import/restore** that cannot overwrite or
   misattribute existing votes; consider a recovery key if backups are encrypted.
3. Add metadata editing for the six non-ISBN books, cover-URL override, and
   explicit preview for uncertain multi-edition merges.
4. Calibrated ranking uncertainty, benchmark pair selection, and decide whether
   Joker should be split into "not now" and "genuine indifference".
5. Define and test the compact on-device recommendation asset and its French
   UI without ever converting external ratings into duel-history events.
