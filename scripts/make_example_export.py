#!/usr/bin/env python3
"""Create a desktop example bundle from the shipped catalog with ZERO vote events.

This is NOT a backup of votes. Real user votes are exported by the Android app.
"""
import argparse
import datetime
import hashlib
import io
import json
import pathlib
import tarfile

root = pathlib.Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output', type=pathlib.Path)
    args = p.parse_args()
    original = json.loads((root/'app/src/main/assets/books.json').read_text(encoding='utf-8'))
    catalog = [dict(b, isbnAliases=[b['isbn13']] if b.get('isbn13') else [],
                    externalRefs=[b['id']] if b['id'].startswith('babelio:') else []) for b in original]
    entries = {
        'catalog.json': (json.dumps(catalog, ensure_ascii=False, separators=(',', ':'))+'\n').encode('utf-8'),
        'events.jsonl': b'',
        'README.txt': b'EXAMPLE only: initial books, NO user vote events.\n',
    }
    manifest = {
        'format':'bookduels.bundle.v1',
        'createdAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'books':len(catalog),
        'events':0,
        'bookIdentity':'stable-work-id; isbn-is-an-edition-alias',
        'babelioRatingsImported':False,
        'files':{name:dict(bytes=len(blob),sha256=hashlib.sha256(blob).hexdigest()) for name,blob in entries.items()},
    }
    entries={'manifest.json':(json.dumps(manifest)+'\n').encode('utf-8'), **entries}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.output,'w:gz') as archive:
        for name,blob in entries.items():
            info=tarfile.TarInfo(name)
            info.size=len(blob)
            info.mode=0o644
            archive.addfile(info,io.BytesIO(blob))
    print(f'Example bundle: {len(catalog)} books, 0 events -> {args.output}')


if __name__=='__main__':
    main()
