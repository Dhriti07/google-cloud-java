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

# Sets up a fresh Ubuntu GCE VM: installs the tools, clones the branch, builds the Rust connector
# (release) on the VM and downloads the benchmark jar.
#
# The jar is a self-contained (shaded) build of this branch's google-cloud-storage plus the
# benchmark, made with `mvn package` in java-storage/rust-poc-benchmark from the same commit.
# Building it on a fresh VM does not work: the branch's base needs unreleased versions of the auth
# library, common protos and platform BOMs that are not on Maven Central.
#
# Usage (on the VM; needs a boot disk of 20 GB or more and the cloud-platform access scope):
#   curl -sSLO https://raw.githubusercontent.com/Dhriti07/google-cloud-java/ffi-java/java-storage/rust-poc-benchmark/scripts/setup_vm.sh
#   JAR_URL=gs://<bucket>/<path>/rust-poc-benchmark.jar bash setup_vm.sh
set -euo pipefail

REPO=${REPO:-https://github.com/Dhriti07/google-cloud-java.git}
BRANCH=${BRANCH:-ffi-java}
DIR=${DIR:-$HOME/google-cloud-java}
JAR_URL=${JAR_URL:?set JAR_URL to the gs:// path of the prebuilt rust-poc-benchmark.jar}

sudo apt-get update -q
sudo apt-get install -y -q openjdk-21-jdk-headless git build-essential pkg-config \
  protobuf-compiler curl python3 ethtool
if ! command -v cargo >/dev/null; then
  curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
fi
# shellcheck disable=SC1091
source "$HOME/.cargo/env"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

if [ ! -d "$DIR" ]; then
  git clone --branch "$BRANCH" --single-branch "$REPO" "$DIR"
fi
cd "$DIR"
git log --oneline -1

(cd java-storage/google-cloud-storage/rust-ffi && cargo build --release)
mkdir -p java-storage/rust-poc-benchmark/target
gcloud storage cp "$JAR_URL" java-storage/rust-poc-benchmark/target/rust-poc-benchmark.jar
sha256sum java-storage/rust-poc-benchmark/target/rust-poc-benchmark.jar

echo "== environment"
nproc
java -version 2>&1 | head -1
cargo --version
ip -br link | grep -v '^lo'
for nic in $(ls /sys/class/net | grep -v '^lo$'); do
  echo "$nic driver: $(ethtool -i "$nic" 2>/dev/null | awk '/^driver/{print $2}') mtu: $(cat /sys/class/net/$nic/mtu)"
done
echo "built:"
ls -la java-storage/rust-poc-benchmark/target/rust-poc-benchmark.jar \
  java-storage/google-cloud-storage/rust-ffi/target/release/libgcs_rust_ffi.so
