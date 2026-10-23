# Goodbooks French-work mapping audit

This audit records the manual pass completed on 2026-10-23. It is deliberately
conservative: an alias means that the BookDuels record and the Goodbooks record
represent the same abstract work, not merely the same author, series, or a
partly overlapping edition.

The reproducible resolver output is generated in the ignored local file
`data/goodbooks-10k/work_resolution_report.json`. With the reviewed aliases in
`config/goodbooks_work_aliases.csv`, it reports:

- 76 of 155 catalog records matched;
- 74 of 148 enabled records matched;
- zero ambiguous matches;
- 79 unmatched records, of which 74 are enabled.

## Aliases added by this pass

| BookDuels work | Goodbooks work | Evidence |
| --- | ---: | --- |
| *Les Ruines* (ISBN 9782253123149) | 2453000, *The Ruins* | The Goodreads work's edition list contains that French ISBN and title. |
| *Troupe 52* (ISBN 9782207132388) | 24510359, *The Troop* | The work's edition list contains that ISBN; Nick Cutter is Craig Davidson's pen name. |
| *Le Royaume des devins* (ISBN 9782070406418) | 942564, *Weaveworld* | The French publisher identifies the ISBN; bibliographic sources identify the original title. |
| *Fictions* (ISBN 9782070366149) | 1007116, *Ficciones* | The ISBN record and translation metadata agree on title and author. |
| *Un cantique pour Leibowitz* (ISBN 9782070417674) | 250975, *A Canticle for Leibowitz* | The Goodreads work's edition list contains that French ISBN and title. |

References used for this pass:

- [The Ruins editions](https://www.goodreads.com/work/editions/2453000-the-ruins?expanded=true&page=1)
- [The Troop editions](https://www.goodreads.com/work/editions/24510359-the-troop?expanded=true)
- [Nick Cutter/Craig Davidson identity](https://en.wikipedia.org/wiki/The_Troop_%28book%29)
- [Folio ISBN page for Le Royaume des devins](https://www.folio-lesite.fr/catalogue/le-royaume-des-devins/9782070406418)
- [Clive Barker bibliography for Weaveworld](https://www.clivebarker.com/html/visions/bib/book/books/weave.htm)
- [Google Books record for Fictions](https://books.google.com/books/about/Fictions.html?id=Dm4OngEACAAJ)
- [A Canticle for Leibowitz editions](https://www.goodreads.com/work/editions/250975-a-canticle-for-leibowitz)

These pages were inspected manually. The application and resolver do not
scrape Goodreads or Babelio HTML.

## Near-matches deliberately rejected

These cases have a tempting Goodbooks candidate but fail the work-identity
rule. They must stay unresolved until the local catalog models the relevant
whole works explicitly or there is a signed-off merge decision.

| BookDuels record(s) | Tempting Goodbooks record | Why it is rejected |
| --- | --- | --- |
| *Rupture dans le réel*, French tomes 1 and 2 | work 747250, *The Reality Dysfunction* | Both French records are portions of one original novel; assigning both would collapse two stable local IDs. A third French volume already resolves to the whole work. |
| *Anatèm*, French tomes 1 and 2 | work 6163095, *Anathem* | Both local records are portions of the same original work and both are enabled. |
| *Le Fléau*, tome 1 | work 1742269, *The Stand* | The local record is only the first part of the French split edition. |
| *Le livre du nouveau soleil*, tome 2 | work 40575, *Shadow & Claw* | The Goodbooks record is an omnibus of original books 1 and 2, while BookDuels keeps those works separate. |
| *L'Héritage de saint Leibowitz – Intégrale* | work 250975, *A Canticle for Leibowitz* | The local omnibus is broader than the first novel, which now has its own exact BookDuels alias. |
| *Les Montagnes hallucinées* (ISBN 9782290319055) | works 33559 or 17342821, *At the Mountains of Madness* | The French volume also contains *The Shadow Out of Time*, so neither candidate represents the full local record. The [BnF record](https://catalogue.bnf.fr/ark:/12148/cb358509557) lists both works. |
| Disabled Dune, Hyperion and Foundation split/omnibus or unread records | Several series works | All seven curated records retain their stable IDs and remain disabled. Five have no safe same-record match; the two exact works that do resolve remain excluded because visibility, not aliasing, controls participation. |

## Remaining coverage limit

The rest of the unmatched set was checked against the local Goodbooks
`books.csv` by original title and author as well as through the cached Open
Library ISBN response. No unique same-work candidate was found. This includes
many specialist or French works and works published after the dataset's 2017
snapshot, such as *Piranesi*, *Project Hail Mary*, *A Psalm for the Wild-Built*
and *Ymir*.

The unresolved list is not a failure queue to fill by fuzzy matching. It is a
coverage boundary of Goodbooks-10k. Improving it safely requires either a
second work-level dataset with compatible redistribution terms or a reviewed
model where each BookDuels record is first normalized to the intended whole
work. A title-only match, a series match, or an edition that contains only part
of the work is insufficient evidence.
