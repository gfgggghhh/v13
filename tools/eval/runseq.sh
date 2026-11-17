#!/usr/bin/env bash
export PATH=$PATH:/opt/kotlinc/bin
V="$1"; OUT="${2:-seq_out}"; rm -rf $OUT; mkdir -p $OUT
for d in seq_in/*/; do n=$(basename $d); ../Vin-viw-main/tools/bench/run.sh seq $d $OUT/$n "$V" >/dev/null 2>&1; done
