#!/usr/bin/env bash
# run.sh <spatial|seq> <inDir> <outDir> <variants>
HERE="$(cd "$(dirname "$0")" && pwd)"
CV_JAR="${CV_JAR:-/usr/share/java/opencv.jar}"; JNI="${CV_JNI_DIR:-/usr/lib/jni}"
KT="${KOTLIN_STDLIB:-$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib/kotlin-stdlib.jar}"
java -Djava.library.path="$JNI" -cp "${BENCH_OUT:-$HERE/build}/bench.jar:$CV_JAR:$KT" bench.BenchKt "$@"
