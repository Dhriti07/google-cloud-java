#!/usr/bin/env bash
# Builds libgcs_rust_ffi.so on THIS machine (use when the prebuilt .so fails to load, typically
# because the VM's glibc is older than the one it was built against).
# Needs: curl, a C compiler (gcc/cc). Installs rustup into ~/.cargo if Rust isn't present.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$DIR/src/rust-ffi"
[[ -d "$SRC" ]] || { tar -xzf "$DIR/rust-storage-poc-src.tar.gz" -C "$DIR"; }

if ! command -v cargo >/dev/null 2>&1; then
  [[ -f "$HOME/.cargo/env" ]] || curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
  # shellcheck disable=SC1091
  source "$HOME/.cargo/env"
fi

command -v cc >/dev/null 2>&1 || {
  echo "ERROR: no C compiler found. Install one, e.g.: sudo apt-get install -y build-essential" >&2
  exit 1
}

# Build from the crate directory so rust-ffi/.cargo/config.toml (frame pointers) applies.
(cd "$SRC" && cargo build --release --locked)
cp "$SRC/target/release/libgcs_rust_ffi.so" "$DIR/libgcs_rust_ffi.so"
echo "Built $DIR/libgcs_rust_ffi.so"
