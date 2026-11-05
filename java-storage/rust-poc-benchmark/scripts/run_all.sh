#!/usr/bin/env bash
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Runs every workload once on both engines (Java defaults vs Rust defaults), one run at a time with
# a cooldown between runs, then fetches Cloud Monitoring CPU/memory and writes summary.md.
#
#   1  Open                        bidi
#   2  Read50x2x4 concurrent/serial bidi, json
#   3  Random8K                     bidi, json
#   4  16 MiB reads, 48 threads     bidi, json
#   5  200 MB writes, 48 threads    bidi (appendable, zonal bucket), json (regional bucket)
#   6  List Objects / List Buckets  gRPC
#
# Same jar and JVM flags for every run; only -Dgcs.rust.lib switches the engine. CloudPath on both.
#
# Usage (on the VM, after setup_vm.sh):
#   bash run_all.sh            # the full run (~2 h 15 min)
#   SMOKE=1 bash run_all.sh    # every run for 45 s, to catch problems first (~30 min)
set -uo pipefail

REGIONAL=${REGIONAL:-dhriti-read-west1}
ZONAL=${ZONAL:-dhriti-zonal-read-west1}
PROJECT=${PROJECT:-gcs-hyd-connector-benchmarks}
COOLDOWN=${COOLDOWN:-60}
SMOKE=${SMOKE:-}
# Optional fixed heap; by default no heap flags (the JVM sizes the heap itself).
HEAP=${HEAP:-}

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
JAR=$ROOT/java-storage/rust-poc-benchmark/target/rust-poc-benchmark.jar
LIB=$ROOT/java-storage/google-cloud-storage/rust-ffi/target/release/libgcs_rust_ffi.so
OUT=${OUT:-$HOME/rust-poc-results/$(date -u +%Y%m%dT%H%M%SZ)${SMOKE:+-smoke}}
JAVA=${JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}
if [ -n "$HEAP" ]; then
  JVM_FLAGS="-Xms$HEAP -Xmx$HEAP -XX:MaxDirectMemorySize=$HEAP"
else
  JVM_FLAGS=""
fi
EXTRA=""
if [ -n "$SMOKE" ]; then
  EXTRA="--duration=45 --warmup=5"
  COOLDOWN=5
fi

for f in "$JAR" "$LIB"; do
  if [ ! -f "$f" ]; then
    echo "missing $f: run setup_vm.sh first" >&2
    exit 1
  fi
done

mkdir -p "$OUT"
exec > >(tee -a "$OUT/run_all.log") 2>&1
echo "results: $OUT"

# One line per workload variant: benchmark arguments. Each runs on both engines.
RUNS=(
  "--workload=open --transport=bidi --bucket=$REGIONAL"
  "--workload=read50x2x4 --transport=bidi --mode=concurrent --bucket=$REGIONAL"
  "--workload=read50x2x4 --transport=bidi --mode=serial --bucket=$REGIONAL"
  "--workload=read50x2x4 --transport=json --mode=concurrent --bucket=$REGIONAL"
  "--workload=read50x2x4 --transport=json --mode=serial --bucket=$REGIONAL"
  "--workload=random8k --transport=bidi --bucket=$REGIONAL"
  "--workload=random8k --transport=json --bucket=$REGIONAL"
  "--workload=read --transport=bidi --bucket=$REGIONAL"
  "--workload=read --transport=json --bucket=$REGIONAL"
  "--workload=write --transport=bidi --bucket=$ZONAL"
  "--workload=write --transport=json --bucket=$REGIONAL"
  "--workload=list-objects --transport=bidi --bucket=$REGIONAL"
  "--workload=list-buckets --transport=bidi --project=$PROJECT"
)

run_one() { # label, args...
  local label=$1 flag=""
  shift
  [ "$label" = rust-default ] && flag="-Dgcs.rust.lib=$LIB"
  echo "== $(date -u +%T) $label $*"
  # shellcheck disable=SC2086
  "$JAVA" $JVM_FLAGS $flag -jar "$JAR" "$@" $EXTRA --label="$label" --out="$OUT" \
    | grep -E '^(creating|results:)|"(ops|errors|first_error|latency_ms_p50|latency_ms_p99|throughput_MBps|items_per_sec|tcp_established_443_mid)"'
  sleep "$COOLDOWN"
}

# Create the test objects once, untimed: 200 MB (workload 4), 200 MiB (2), 100 MiB (1, 3), and the
# 100 list objects (6).
echo "== prepare"
for a in "--workload=read" "--workload=read50x2x4" "--workload=random8k" "--workload=list-objects"; do
  "$JAVA" $JVM_FLAGS -jar "$JAR" $a --bucket="$REGIONAL" --threads=1 --duration=1 --warmup=0 \
    --out="$OUT/prepare" >/dev/null || echo "prepare failed: $a"
done

i=0
for r in "${RUNS[@]}"; do
  # Alternate which engine goes first so neither always runs first.
  # shellcheck disable=SC2086
  if ((i % 2 == 0)); then
    run_one java-default $r
    run_one rust-default $r
  else
    run_one rust-default $r
    run_one java-default $r
  fi
  i=$((i + 1))
done

if [ -z "$SMOKE" ]; then
  # Cloud Monitoring ingests with a few minutes' delay.
  echo "== waiting 240 s for Cloud Monitoring"
  sleep 240
  python3 "$HERE/cloud_metrics.py" "$OUT"/*/summary.json || echo "cloud metrics failed (continuing)"
fi
python3 "$HERE/summarize.py" "$OUT" > "$OUT/summary.md"
echo "done: $OUT/summary.md"
