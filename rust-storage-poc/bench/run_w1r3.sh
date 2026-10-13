#!/usr/bin/env bash
# w1r3 (JSON) on THIS VM: three implementations, one at a time, same bucket.
#
#   native_java  java-storage
#   rust_jni     Rust Storage SDK via JNI
#   rust_panama  Rust Storage SDK via Panama
#
# All three run the same harness (W1R3Benchmark) with the same JVM flags, object-size sequence
# and metrics, and write one CSV row per operation.
#
# Usage:  BUCKET=<bucket> ./run_w1r3.sh
# Env (defaults in brackets):
#   BUCKET (required, in the same region as this VM)  PROJECT [unset]
#   ITERATIONS [200]  ROUNDS [4, keep even: balanced forward/reversed order]
#   IMPLS ["native_java rust_jni rust_panama"]
#   WARMUP [10]  unrecorded warm-up iterations per process
#   READ_MODE [java]  rust_jni/rust_panama reads: java = stream into a 2 MiB Java heap buffer
#                (like native_java); discard = drain inside Rust (bridge floor)
#   OBJECT_SIZES [unset = 100000,2097152,100000000]  (quick checks only)
#   JAVA [java]
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
BUCKET="${BUCKET:?set BUCKET to a bucket in the same region as this VM}"
PROJECT="${PROJECT:-}"
ITERATIONS="${ITERATIONS:-200}"
ROUNDS="${ROUNDS:-4}"
WARMUP="${WARMUP:-10}"
READ_MODE="${READ_MODE:-java}"
IMPLS="${IMPLS:-native_java rust_jni rust_panama}"
JAVA="${JAVA:-java}"
OBJECT_SIZES="${OBJECT_SIZES:-}"
SIZE_ARGS=()
[[ -n "$OBJECT_SIZES" ]] && SIZE_ARGS=(--object-sizes="$OBJECT_SIZES")
# 1 GiB pre-touched heap, so heap growth doesn't show up in per-op RSS.
JVM_FLAGS=(-Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=1g
  -Dio.grpc.netty.shaded.io.netty.maxDirectMemory=1073741824)

JAR="$DIR/rust-storage-poc-bench.jar"
LIB="$DIR/libgcs_rust_ffi.so"
RUN_ID="$(date -u +%Y%m%d-%H%M%S)"
PREFIX="gcs-rust-poc/w1r3/$RUN_ID"
OUT="$DIR/results/w1r3-$RUN_ID"
mkdir -p "$OUT"
read -r -a IMPL_LIST <<<"$IMPLS"

die() { echo "ERROR: $*" >&2; exit 1; }
JAVA_MAJOR="$("$JAVA" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}' | cut -d. -f1)"
[[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" -ge 22 ]] || die "Java 22+ required (found ${JAVA_MAJOR:-unknown}); set JAVA=..."
command -v python3 >/dev/null || die "python3 is required"
FREE_MB="$(df -Pm "$OUT" | awk 'NR==2 {print $4}')"
[[ "$FREE_MB" -ge 300 ]] || die "only ${FREE_MB} MB free; need >= 300 MB"
[[ -f "$JAR" && -f "$LIB" ]] || die "missing $JAR or $LIB"
"$JAVA" --enable-native-access=ALL-UNNAMED -cp "$JAR" com.google.cloud.storage.rustpoc.W1R3Benchmark \
  --check-lib="$LIB" >/dev/null || die "$LIB can't be loaded on this machine"

{
  echo "run_id=$RUN_ID region=$(curl -s -H 'Metadata-Flavor: Google' \
    http://metadata.google.internal/computeMetadata/v1/instance/zone 2>/dev/null | sed 's|.*/||' || true)"
  echo "iterations=$ITERATIONS rounds=$ROUNDS impls=$IMPLS object_sizes=${OBJECT_SIZES:-default}"
  echo "warmup=$WARMUP read_mode=$READ_MODE GCS_RUST_RUNTIME=current_thread GCS_RUST_RETRY=never (java: retries off)"
  echo "jvm_flags=${JVM_FLAGS[*]}"
  "$JAVA" -version 2>&1
  uname -srm
  echo "nproc=$(nproc)"
  echo "machine-type=$(curl -s -H 'Metadata-Flavor: Google' \
    http://metadata.google.internal/computeMetadata/v1/instance/machine-type 2>/dev/null | sed 's|.*/||' || true)"
} | tee "$OUT/environment.txt"

run_impl() {
  local impl="$1" r="$2"
  echo "=== round $r: $impl ===" | tee -a "$OUT/run.log"
  GCS_RUST_RUNTIME=current_thread GCS_RUST_RETRY=never python3 "$DIR/timed_run.py" "$OUT/r$r-$impl" \
    "$JAVA" "${JVM_FLAGS[@]}" --enable-native-access=ALL-UNNAMED -cp "$JAR" \
    com.google.cloud.storage.rustpoc.W1R3Benchmark \
    --impl="$impl" --bucket="$BUCKET" --project="$PROJECT" --lib="$LIB" \
    --iterations="$ITERATIONS" --warmup="$WARMUP" --read-mode="$READ_MODE" --seed=42 \
    --prefix="$PREFIX/r$r/" --out="$OUT/r$r-$impl.csv" ${SIZE_ARGS[@]+"${SIZE_ARGS[@]}"}
}

N_IMPLS=${#IMPL_LIST[@]}
for ((r = 1; r <= ROUNDS; r++)); do
  # Balanced order: rounds in pairs, forward then reversed, rotation advancing per pair, e.g.
  #   r1: A B C   r2: C B A   r3: B C A   r4: A C B
  # so drift over time / the previous process's effects don't always favour the same one.
  rot=$(((r - 1) / 2))
  order=()
  for ((i = 0; i < N_IMPLS; i++)); do order+=("${IMPL_LIST[$(((i + rot) % N_IMPLS))]}"); done
  if ((r % 2 == 0)); then
    rev=(); for ((i = N_IMPLS - 1; i >= 0; i--)); do rev+=("${order[$i]}"); done; order=("${rev[@]}")
  fi
  echo "round $r order: ${order[*]}" | tee -a "$OUT/run.log"
  for impl in "${order[@]}"; do
    run_impl "$impl" "$r" || echo "WARN: round $r $impl failed (see $OUT/r$r-$impl*.stderr); continuing" | tee -a "$OUT/run.log"
  done
done

python3 "$DIR/summarize_w1r3.py" "$OUT" | tee "$OUT/summary.md"
echo
echo "Results: $OUT"
