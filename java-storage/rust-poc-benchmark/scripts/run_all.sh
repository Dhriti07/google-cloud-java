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

# Runs Rapid workloads 4, 5 and 6 on both engines, several rounds, alternating the order, with a
# cooldown between runs. Same jar and same JVM flags for every run; only the engine flag and the
# connection settings differ. Then fetches Cloud Monitoring CPU/memory and writes summary.md.
#
# Configurations for the data workloads (read, write):
#   java-default   Java path, one Storage (= 1 gRPC connection, what users get today)
#   rust-default   Rust SDK defaults (= 1 gRPC connection per vCPU for the data path)
#   rust-1conn     Rust with 1 connection          (same connections as java-default)
#   java-48conn    Java with 48 Storage instances  (1 per thread, 48 connections)
#   rust-48conn    Rust with 48 connections        (same connections as java-48conn)
# List workloads use one connection on both engines by default, so they run the defaults only.
#
# Usage (on the VM, after setup_vm.sh):
#   bash run_all.sh                     # everything, ~3 h with the defaults below
#   ROUNDS=1 DURATION=60 bash run_all.sh   # quick check
set -uo pipefail

ROUNDS=${ROUNDS:-3}
DURATION=${DURATION:-300}
LIST_DURATION=${LIST_DURATION:-60}
COOLDOWN=${COOLDOWN:-60}
THREADS=${THREADS:-48}
WORKLOADS=${WORKLOADS:-"read write list-objects list-buckets"}
DATA_CONFIGS=${DATA_CONFIGS:-"java-default rust-default rust-1conn java-48conn rust-48conn"}
READ_BUCKET=${READ_BUCKET:-dhriti-read-west1}
WRITE_BUCKET=${WRITE_BUCKET:-dhriti-zonal-read-west1}
LIST_BUCKET=${LIST_BUCKET:-dhriti-read-west1}
PROJECT=${PROJECT:-gcs-hyd-connector-benchmarks}
HEAP=${HEAP:-16g}

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
JAR=$ROOT/java-storage/rust-poc-benchmark/target/rust-poc-benchmark.jar
LIB=$ROOT/java-storage/google-cloud-storage/rust-ffi/target/release/libgcs_rust_ffi.so
OUT=${OUT:-$HOME/rust-poc-results/$(date -u +%Y%m%dT%H%M%SZ)}
JAVA=${JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}
# Identical for every run.
JVM_FLAGS="-Xms$HEAP -Xmx$HEAP -XX:MaxDirectMemorySize=$HEAP -XX:+UseG1GC"

for f in "$JAR" "$LIB"; do
  if [ ! -f "$f" ]; then
    echo "missing $f: run setup_vm.sh first" >&2
    exit 1
  fi
done

mkdir -p "$OUT"
exec > >(tee -a "$OUT/run_all.log") 2>&1
echo "results: $OUT"

# config -> "extra JVM flags|extra benchmark args"
config() {
  case $1 in
    java-default) echo "|--clients=1" ;;
    rust-default) echo "-Dgcs.rust.lib=$LIB|--clients=1" ;;
    rust-1conn) echo "-Dgcs.rust.lib=$LIB -Dgcs.rust.grpc.subchannels=1|--clients=1" ;;
    java-48conn) echo "|--clients=48" ;;
    rust-48conn) echo "-Dgcs.rust.lib=$LIB -Dgcs.rust.grpc.subchannels=48|--clients=1" ;;
    *) echo "unknown config $1" >&2; exit 1 ;;
  esac
}

workload_args() {
  case $1 in
    read) echo "--workload=read --bucket=$READ_BUCKET --threads=$THREADS --duration=$DURATION" ;;
    write) echo "--workload=write --bucket=$WRITE_BUCKET --threads=$THREADS --duration=$DURATION" ;;
    list-objects) echo "--workload=list-objects --bucket=$LIST_BUCKET --duration=$LIST_DURATION" ;;
    list-buckets) echo "--workload=list-buckets --project=$PROJECT --duration=$LIST_DURATION" ;;
  esac
}

run_one() { # workload config
  local w=$1 c=$2 spec jvm args
  spec=$(config "$c")
  jvm=${spec%%|*}
  args=${spec#*|}
  echo "== $(date -u +%T) $w $c"
  # shellcheck disable=SC2086
  "$JAVA" $JVM_FLAGS $jvm -jar "$JAR" $(workload_args "$w") $args --label="$c" --out="$OUT" \
    | grep -E '^(creating|deleting|results:)|"(ops|errors|first_error|latency_ms_p50|latency_ms_p99|throughput_Gbps|items_per_sec|tcp_established_443_mid|nic_rx_Gbps|nic_tx_Gbps)"'
  sleep "$COOLDOWN"
}

# Create the read object and the list objects once, untimed, so no measured run pays for it.
echo "== prepare"
"$JAVA" $JVM_FLAGS -jar "$JAR" --workload=read --bucket="$READ_BUCKET" --threads=1 --duration=1 \
  --warmup=0 --out="$OUT/prepare" >/dev/null
"$JAVA" $JVM_FLAGS -jar "$JAR" --workload=list-objects --bucket="$LIST_BUCKET" --duration=1 \
  --warmup=0 --out="$OUT/prepare" >/dev/null

for ((r = 1; r <= ROUNDS; r++)); do
  for w in $WORKLOADS; do
    if [[ $w == list-* ]]; then configs="java-default rust-default"; else configs=$DATA_CONFIGS; fi
    # Alternate the order every round so neither engine always goes first.
    if ((r % 2 == 0)); then configs=$(echo "$configs" | tr ' ' '\n' | tac | tr '\n' ' '); fi
    echo "#### round $r/$ROUNDS: $w ($configs)"
    for c in $configs; do
      run_one "$w" "$c"
    done
  done
done

# Cloud Monitoring ingests with a few minutes' delay.
echo "== waiting 240 s for Cloud Monitoring"
sleep 240
python3 "$HERE/cloud_metrics.py" "$OUT"/*/summary.json || echo "cloud metrics failed (continuing)"
python3 "$HERE/summarize.py" "$OUT" > "$OUT/summary.md"
echo "done: $OUT/summary.md"
