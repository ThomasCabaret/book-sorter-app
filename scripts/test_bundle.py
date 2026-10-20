#!/usr/bin/env python3
"""Desktop bundle validator checks (including tampering and event replay)."""
import hashlib
import io
import json
import pathlib
import tarfile
import tempfile
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
validator = root / 'scripts' / 'inspect_bundle.py'
book_a = {"id":"isbn:9780306406157","title":"Alpha","author":"A","isbn13":"9780306406157","isbnAliases":["9780306406157"],"enabled":True}
book_b = {"id":"manual:b","title":"Beta","author":"B","isbnAliases":[],"enabled":True}
events = [
 {"seq":1,"eventId":"vote1","timestampMs":1,"kind":"VOTE","firstBookId":book_a['id'],"secondBookId":book_b['id'],"chosenBookId":book_a['id']},
 {"seq":2,"eventId":"joker2","timestampMs":2,"kind":"JOKER","firstBookId":book_a['id'],"secondBookId":book_b['id']},
 {"seq":3,"eventId":"undo3","timestampMs":3,"kind":"INVALIDATE","targetSeq":1,"batchId":"batch1"},
]
files = {
 'catalog.json':(json.dumps([book_a, book_b])+'\n').encode(),
 'events.jsonl':(''.join(json.dumps(e)+'\n' for e in events)).encode(),
 'README.txt':b'Sample\n',
}

def make_bundle(path, files, manifest_override=None):
    manifest = {"format":"bookduels.bundle.v1","books":2,"events":3,"files": {
        name:{'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()} for name,content in files.items()
    }}
    if manifest_override:
        manifest.update(manifest_override)
    with tarfile.open(path,'w:gz') as tf:
        for name,content in [('manifest.json',json.dumps(manifest).encode()),*files.items()]:
            ti=tarfile.TarInfo(name);ti.size=len(content)
            tf.addfile(ti,io.BytesIO(content))

with tempfile.TemporaryDirectory() as tmp:
    archive=pathlib.Path(tmp)/'bookduels.tar.gz'
    out=pathlib.Path(tmp)/'pairs.json'
    make_bundle(archive,files)
    valid=subprocess.run([sys.executable,validator,archive,'--emit-pairs',out],capture_output=True,text=True)
    assert valid.returncode==0,valid.stderr
    pairs=json.loads(out.read_text())['pairs']
    assert pairs==[{'firstBookId':book_a['id'],'secondBookId':book_b['id'],'firstWins':0,'secondWins':0,'jokers':1}]
    changed=dict(files);changed['events.jsonl']=files['events.jsonl']+b'corruption'
    # Deliberately preserve old checksums in manifest: validator must reject.
    manifest={"format":"bookduels.bundle.v1","books":2,"events":3,"files":{
        name:{'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()} for name,content in files.items()
    }}
    with tarfile.open(archive,'w:gz') as tf:
        for name,content in [('manifest.json',json.dumps(manifest).encode()),*changed.items()]:
            ti=tarfile.TarInfo(name);ti.size=len(content)
            tf.addfile(ti,io.BytesIO(content))
    invalid=subprocess.run([sys.executable,validator,archive],capture_output=True,text=True)
    assert invalid.returncode!=0
print('PASS: portable manifest, checksums, replay and tampering detection')
