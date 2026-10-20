#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
kotlinc app/src/main/java/org/example/bookduels/core/*.kt scripts/ArchiveSmoke.kt -d "$TEMP_DIR/smoke.jar"
kotlin -classpath "$TEMP_DIR/smoke.jar" org.example.bookduels.core.ArchiveSmokeKt "$TEMP_DIR/example.tar.gz"
python3 - "$TEMP_DIR/example.tar.gz" <<'PY'
import io,sys,tarfile
with tarfile.open(sys.argv[1],"r:gz") as tf:
    members=tf.getmembers()
    assert [x.name for x in members]==['manifest.json','catalog.json','events.jsonl']
    assert all(x.isfile() for x in members)
    assert tf.extractfile('catalog.json').read()==b'[]\n'
    assert tf.extractfile('events.jsonl').read()==b''
print('PASS: Kotlin-generated USTAR/gzip opened and verified by Python tarfile')
PY
