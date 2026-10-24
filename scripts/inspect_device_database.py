#!/usr/bin/env python3
"""Print a read-only integrity fingerprint for a pulled BookDuels SQLite file.

Copy adjacent ``-wal`` and ``-shm`` files too when they exist; SQLite will then
read the complete committed state without checkpointing or mutating the device.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sqlite3


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=pathlib.Path)
    args = parser.parse_args()

    database = args.database.resolve()
    connection = sqlite3.connect(f"file:{database.as_posix()}?mode=ro", uri=True)
    try:
        integrity = connection.execute("PRAGMA quick_check").fetchone()[0]
        if integrity != "ok":
            raise ValueError(f"SQLite quick_check failed: {integrity}")
        event_columns = [row[1] for row in connection.execute("PRAGMA table_info(events)")]
        event_rows = connection.execute("SELECT * FROM events ORDER BY seq").fetchall()
        canonical_events = "\n".join(
            json.dumps(dict(zip(event_columns, row)), sort_keys=True, separators=(",", ":"))
            for row in event_rows
        ).encode("utf-8")
        report = {
            "database": str(database),
            "quickCheck": integrity,
            "schemaVersion": connection.execute("PRAGMA user_version").fetchone()[0],
            "bookCount": connection.execute("SELECT COUNT(*) FROM books").fetchone()[0],
            "eventCount": len(event_rows),
            "maxEventSequence": connection.execute(
                "SELECT COALESCE(MAX(seq), 0) FROM events"
            ).fetchone()[0],
            "eventKinds": dict(connection.execute(
                "SELECT kind, COUNT(*) FROM events GROUP BY kind ORDER BY kind"
            )),
            "eventsSha256": hashlib.sha256(canonical_events).hexdigest(),
        }
    finally:
        connection.close()
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
