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

# Additional run: the 48-thread Bidi workloads (4 and 5) with the SAME number of gRPC connections
# on both engines, each engine using a single client: Java with an N-connection pool
# (-Dgcs.java.grpc.channels=N, PoC benchmarking only) vs Rust with N
# (-Dgcs.rust.grpc.subchannels=N). Default N = 48.
# Java and Rust run back to back for each workload, so both see the same network conditions.
# Results go to their own directory; nothing from earlier runs is touched.
#
# A 45 s smoke test of the new configurations comes first; the full run starts only if every
# smoke run has 0 errors.
#
# Usage (on the VM, after setup_vm.sh):  bash run_matched.sh   (~35 min; CONNS=<N> to change N)
set -uo pipefail

REGIONAL=${REGIONAL:-dhriti-read-west1}
ZONAL=${ZONAL:-dhriti-zonal-read-west1}
COOLDOWN=${COOLDOWN:-60}
CONNS=${CONNS:-48}

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
JAR=$ROOT/java-storage/rust-poc-benchmark/target/rust-poc-benchmark.jar
LIB=$ROOT/java-storage/google-cloud-storage/rust-ffi/target/release/libgcs_rust_ffi.so
OUT=${OUT:-$HOME/rust-poc-results/matched-${CONNS}conn}
JAVA=${JAVA:-/usr/lib/jvm/java-21-openjdk-amd64/bin/java}

for f in "$JAR" "$LIB"; do
  if [ ! -f "$f" ]; then
    echo "missing $f: run setup_vm.sh first" >&2
    exit 1
  fi
done
mkdir -p "$OUT"
exec > >(tee -a "$OUT/run_matched.log") 2>&1
echo "results: $OUT"

JAVA_LABEL=java-${CONNS}conn
RUST_LABEL=rust-${CONNS}conn
RUNS=(
  "--workload=read --transport=bidi --bucket=$REGIONAL"
  "--workload=write --transport=bidi --bucket=$ZONAL"
)

run_one() { # label, out dir, extra args, workload args...
  local label=$1 out=$2 extra=$3 flags=""
  shift 3
  case $label in
    java-*conn) flags="-Dgcs.java.grpc.channels=$CONNS" ;;
    rust-*conn) flags="-Dgcs.rust.lib=$LIB -Dgcs.rust.grpc.subchannels=$CONNS" ;;
  esac
  echo "== $(date -u +%T) $label $*"
  # shellcheck disable=SC2086
  "$JAVA" $flags -jar "$JAR" "$@" $extra --label="$label" --out="$out" \
    | grep -E '^(results:)|"(ops|errors|first_error|latency_ms_p50|throughput_MBps|items_per_sec|tcp_established_443_mid)"'
}

echo "== smoke (new configurations, 45 s each)"
SMOKE_LABELS="$JAVA_LABEL $RUST_LABEL"
for r in "${RUNS[@]}"; do
  for l in $SMOKE_LABELS; do
    # shellcheck disable=SC2086
    run_one "$l" "$OUT/smoke" "--duration=45 --warmup=5" $r
    sleep 5
  done
done
EXPECTED=$(( ${#RUNS[@]} * $(echo $SMOKE_LABELS | wc -w) ))
python3 - "$OUT/smoke" "$EXPECTED" <<'EOF'
import glob, json, sys
bad, n = [], 0
for f in glob.glob(sys.argv[1] + "/*/summary.json"):
    s = json.load(open(f)); n += 1
    # A 200 MB write can take longer than the 45 s window, so writes need throughput rather
    # than completed objects.
    moved = s["ops"] > 0 or (s.get("throughput_MBps") or 0) > 0
    if s["errors"] or not moved:
        bad.append(f"{s['op']} ops={s['ops']} errors={s['errors']} {s.get('first_error')}")
print(f"smoke runs: {n}")
if n != int(sys.argv[2]) or bad:
    print("SMOKE-FAILED"); print("\n".join(bad)); sys.exit(1)
print("SMOKE-OK")
EOF
if [ $? -ne 0 ]; then echo "stopping: smoke failed"; exit 1; fi
sleep "$COOLDOWN"

echo "== full run"
i=0
for r in "${RUNS[@]}"; do
  # Alternate which engine goes first.
  # shellcheck disable=SC2086
  if ((i % 2 == 0)); then
    run_one "$JAVA_LABEL" "$OUT" "" $r; sleep "$COOLDOWN"
    run_one "$RUST_LABEL" "$OUT" "" $r; sleep "$COOLDOWN"
  else
    run_one "$RUST_LABEL" "$OUT" "" $r; sleep "$COOLDOWN"
    run_one "$JAVA_LABEL" "$OUT" "" $r; sleep "$COOLDOWN"
  fi
  i=$((i + 1))
done

echo "== waiting 240 s for Cloud Monitoring"
sleep 240
python3 "$HERE/cloud_metrics.py" "$OUT"/*/summary.json || echo "cloud metrics failed (continuing)"
python3 "$HERE/summarize.py" "$OUT" > "$OUT/summary.md"
echo "done: $OUT/summary.md"
