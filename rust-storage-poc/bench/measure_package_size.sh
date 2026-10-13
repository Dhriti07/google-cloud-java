#!/usr/bin/env bash
# Measures the shipped size of the Rust library for every platform we'd need to support, the
# same way the Python PoC measured its wheel: plain release build (no debug info), symbols
# stripped, then packaged the way Java ships native code (inside a jar = zip/deflate).
#
# For each target: build -> report raw library size -> put it in a per-platform jar -> report jar
# size. Then build one jar with every platform that built, and report that too. Targets that fail
# to build are reported with the first error lines (a finding in itself, like the Go PoC's
# cross-build failures).
#
# Needs: rustup targets installed, cargo-zigbuild + zig (cross C toolchain for aws-lc-sys).
# Usage: bench/measure_package_size.sh [outdir]
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-/tmp/rust-poc-package-size}"
TARGET_DIR="${CARGO_TARGET_DIR:-/tmp/rust-target-size}"
rm -rf "$OUT" && mkdir -p "$OUT/all"

# target triple | zigbuild target (glibc floor for gnu) | library file | jar dir name
TARGETS=(
  "x86_64-unknown-linux-gnu|x86_64-unknown-linux-gnu.2.17|libgcs_rust_ffi.so|linux-x86_64"
  "aarch64-unknown-linux-gnu|aarch64-unknown-linux-gnu.2.17|libgcs_rust_ffi.so|linux-aarch64"
  "x86_64-unknown-linux-musl|x86_64-unknown-linux-musl|libgcs_rust_ffi.so|linux-musl-x86_64"
  "aarch64-unknown-linux-musl|aarch64-unknown-linux-musl|libgcs_rust_ffi.so|linux-musl-aarch64"
  "x86_64-apple-darwin|x86_64-apple-darwin|libgcs_rust_ffi.dylib|osx-x86_64"
  "aarch64-apple-darwin|aarch64-apple-darwin|libgcs_rust_ffi.dylib|osx-aarch64"
  "x86_64-pc-windows-gnu|x86_64-pc-windows-gnu|gcs_rust_ffi.dll|windows-x86_64"
)

export CARGO_PROFILE_RELEASE_DEBUG=false   # like Python's plain --release
export CARGO_PROFILE_RELEASE_STRIP=symbols # like Python's --strip (rustc strips, any platform)
FP="-C force-frame-pointers=yes"           # same as rust-ffi/.cargo/config.toml
# cdylib on musl needs a dynamically linked C runtime (target rustflags replace build.rustflags).
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="$FP -C target-feature=-crt-static"
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="$FP -C target-feature=-crt-static"

printf '%-22s %-8s %14s %14s\n' platform status "library bytes" "jar bytes" | tee "$OUT/sizes.txt"
for t in "${TARGETS[@]}"; do
  IFS='|' read -r triple zt lib name <<<"$t"
  log="$OUT/build-$name.log"
  if (cd "$DIR/rust-ffi" && CARGO_TARGET_DIR="$TARGET_DIR" cargo zigbuild --release --locked \
      --target "$zt" >"$log" 2>&1); then
    f="$TARGET_DIR/$triple/release/$lib"
    mkdir -p "$OUT/$name/native/$name" "$OUT/all/native/$name"
    cp "$f" "$OUT/$name/native/$name/" && cp "$f" "$OUT/all/native/$name/"
    (cd "$OUT/$name" && zip -q -r -9 "../gcs-rust-native-$name.jar" native)
    printf '%-22s %-8s %14d %14d\n' "$name" ok "$(stat -c %s "$f")" \
      "$(stat -c %s "$OUT/gcs-rust-native-$name.jar")" | tee -a "$OUT/sizes.txt"
  else
    printf '%-22s %-8s %14s %14s\n' "$name" FAILED - - | tee -a "$OUT/sizes.txt"
    grep -m3 -E "^error|error:|failed" "$log" | sed 's/^/    /' | tee -a "$OUT/sizes.txt"
  fi
  rm -rf "${TARGET_DIR:?}/$triple" # keep the RAM disk from filling up
done

(cd "$OUT/all" && zip -q -r -9 ../gcs-rust-native-all.jar native)
printf '%-22s %-8s %14s %14d\n' "ALL (one jar)" - - "$(stat -c %s "$OUT/gcs-rust-native-all.jar")" \
  | tee -a "$OUT/sizes.txt"
echo "Results: $OUT/sizes.txt"
