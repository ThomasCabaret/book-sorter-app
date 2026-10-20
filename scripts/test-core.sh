#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlinc app/src/main/java/org/example/bookduels/core/*.kt scripts/CoreSmoke.kt -d "$OUT/tests.jar"
kotlin -classpath "$OUT/tests.jar" org.example.bookduels.core.CoreSmokeKt
