#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
dep_dir="${RUNNER_TEMP:-$PWD/.cache}/mega-bench"
mkdir -p "$dep_dir"
dep_url="$(python3 -c 'import json;print(json.load(open("config/mega-bench.json"))["url"])')"
curl --location --fail --silent --show-error --max-time 180 --retry 0 "$dep_url" --output "$dep_dir/MegaBench.jar"
python3 tools/build.py --server-jar "$HYTALE_SERVER_JAR" --mega-bench-jar "$dep_dir/MegaBench.jar" --java-home "$JAVA_HOME"
