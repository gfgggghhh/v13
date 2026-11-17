#!/usr/bin/env bash
# Compiles the real processing sources + the bench against an OpenCV *desktop* Java jar and runs it.
# Usage:  tools/bench/build.sh /path/to/opencv.jar        (then: tools/bench/run.sh spatial in out low,medium)
# Windows: the official OpenCV release ships  build/java/opencv-4xx.jar  and  build/java/x64/opencv_java4xx.dll
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$HERE/../.."
CV_JAR="${1:-/usr/share/java/opencv.jar}"
SRC="$ROOT/app/src/main/java/com/veinfinder/app"
OUT="${BENCH_OUT:-$HERE/build}"; mkdir -p "$OUT"
kotlinc -nowarn -cp "$CV_JAR" -d "$OUT/bench.jar" \
  "$SRC/VeinProcessor.kt" "$SRC/processing/"*.kt "$HERE/OriginalVeinProcessor.kt" "$HERE/Bench.kt" "$HERE/Stress.kt"
echo "built $OUT/bench.jar"
