#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
mkdir -p build/classes
mapfile -d '' sources < <(find src/main/java -name '*.java' -print0)
javac --release 17 -d build/classes "${sources[@]}"
echo 'Build complete: build/classes'
