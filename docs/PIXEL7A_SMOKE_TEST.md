# Pixel 7a smoke tests — 2026-10-20 and 2026-10-24

> The latest completed observations concern v0.7.2. Older interaction notes are
> retained below as an audit trail; they do not describe the current UI alone.

## Environment

- Device: Google Pixel 7a (`lynx`)
- Android: 17 / API 37
- Package: `org.example.bookduels`
- Artifact: debug APK built by `:app:assembleDebug`

The device serial and the user's unrelated files are deliberately not recorded.

## Passed

### Version 0.7.2 recommendation pass — 2026-10-24

- The connected Pixel reported the installed predecessor as v0.6.0/code 6.
  `adb install -r` upgraded it to v0.7.2/code 9 without uninstalling or clearing
  application data.
- Read-only SQLite snapshots immediately before installation, after installation
  and after the UI smoke test all passed `PRAGMA quick_check`. Each contained
  155 books and exactly the same 467 event rows: 447 votes, 10 Jokers and 10
  invalidations, through sequence 467. Their canonical event SHA-256 was
  `cfcf135e7d6afe461ebd987b43eb61b649bc36fe556e3372d03746738ffaaaa4`.
- The cold app launch completed in 687 ms; the final cold relaunch completed in
  661 ms. No fatal exception or application crash appeared in logcat.
- **À lire** rendered 104 mapped active votes, 74 linked catalog books and 575
  eligible candidates. Its leading results agreed with the exact desktop
  reference. Automated scrolling reached **100. In the Penal Colony**, proving
  that the phone rendered the new 100-result default rather than only the
  initially visible cards.
- Recommendation cards expose **Voir la fiche Goodreads**. Tapping the first
  result dispatched the exact work URL
  `https://www.goodreads.com/work/show/59258` to the installed Goodreads app.
- The app was finally relaunched on the Duels screen, ready for normal use.
- A complete read-only SQLite copy included the main database, WAL and SHM
  siblings. It confirmed that the one-time metadata curation stored
  *La Fille-flûte et autres fragments de futurs brisés* while preserving its
  stable ID, enabled state and the entire event fingerprint.

- ADB authorization and USB transport.
- Streamed debug installation with `adb install -r`.
- Cold start of `MainActivity` with no application crash or fatal exception.
- Initial SQLite seed: 152 active books and 152 retained books.
- Duels, ranking, library, and history tabs render and remain responsive.
- Empty-state ranking reports 0 decisions and 11,476 possible pairs.
- On-demand cover failures use the fallback drawable. The two initially selected
  ISBN cover endpoints returned HTTP 404; the app cache remained about 19 KiB.
- Android Storage Access Framework export to Downloads.
- The exported 6,486-byte archive passed `scripts/inspect_bundle.py`: 152 books,
  zero immutable events, valid manifest sizes and SHA-256 hashes.
- A second `adb install -r` preserved the SQLite database byte-for-byte while
  the app was stopped, then the app cold-started successfully again.
- Temporary screenshots and UI hierarchy dumps were removed. The validated
  `bookduels-backup.tar.gz` remains in the phone's Downloads folder.

### Version 0.3.0 feedback pass (superseded UI)

- Upgrade installation with `adb install -r`; package reports version code 3,
  version name 0.3.0, minimum API 26 and target API 37.
- Warm low-glare background, surfaces, controls and readable system-bar colors
  rendered correctly on the Pixel 7a.
- Missing Open Library images now render an explicit `Jaquette indisponible`
  fallback; non-ISBN entries use `Sans jaquette`.
- A real right swipe on the upper card and a real left swipe on the lower card
  each selected the swiped book. The explicit preference button used the same
  confirmation state.
- Each interaction displayed the selected title, highlighted the chosen card,
  displayed `✓ Sélectionné`, disabled competing actions and exposed `Annuler`
  for eight seconds.
- The pre-commit cancel path was exercised past the full eight-second deadline.
  Both SQLite database/WAL SHA-256 values stayed unchanged and the active
  counters remained at zero.
- One automation-coordinate error allowed the first test choice to reach the
  durable log. The Pixel credential prompt was exercised successfully and the
  test choice was neutralized through the intended append-only workflow. It no
  longer affects ranking or counters, but the original vote and compensating
  invalidation remain visible in the audit log by design.

### Version 0.4.0 interaction pass

- Upgrade installation reports version code 4 and version name 0.4.0.
- The twilight palette renders as intended on the Pixel: blue-charcoal app and
  system-bar backgrounds, slate cards/navigation, light text, teal actions and
  a warm selected-tab accent. No white application surface remains.
- A device screenshot taken immediately after a preference tap captured the
  selected card's teal fill, outline and `✓ Sélectionné` state during the 250 ms
  feedback interval; the next duel followed without a waiting banner.
- Three consecutive choices advanced normally and produced `1`, `2`, then `3`
  active votes while the persistent control reached `↶ Annuler (3)`.
- Three consecutive undo taps produced `2`, `1`, then `0` active votes, reduced
  the LIFO counter in lockstep, and returned to each corresponding prior duel.
  Original test events remain auditable alongside their compensating
  invalidations; no event row was updated or deleted.
- A final right swipe on the upper card in the installed release candidate
  produced one vote and `↶ Annuler (1)`; quick undo returned the active counter
  to zero and restored the prior duel.

## Intentionally not exercised

The initial export remains the pristine 152-book/zero-event backup. Later
interaction passes deliberately wrote test votes and compensating invalidations
to the phone's append-only audit log; the active vote/Joker counters are zero,
but the audit log is therefore no longer empty. No Joker, catalog toggle,
manual book, or CSV import was created. The following still need an explicit
test session before release:

1. Joker commit semantics and rapid/double input resistance.
2. Strong-biometric/device-credential fail-closed behavior for reinstatement.
3. Windows-1252 and UTF-8 Babelio imports through the Android file picker.
4. Instrumented SQLite v1-to-v3 and v2-to-v3 migrations from retained fixtures.
5. Rotation/process-death behavior and a longer cover-cache exercise.
6. Automated Compose assertions for swipe semantics and recommendation
   recomputation immediately after a newly committed vote.
