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

# Sets up a fresh Ubuntu GCE VM and builds everything the benchmark needs, on the VM itself:
# the branch's google-cloud-storage, the Rust connector (release build) and the benchmark jar.
#
# Usage (on the VM):
#   curl -sSLO https://raw.githubusercontent.com/Dhriti07/google-cloud-java/ffi-java/java-storage/rust-poc-benchmark/scripts/setup_vm.sh
#   bash setup_vm.sh            # clones into ~/google-cloud-java
set -euo pipefail

REPO=${REPO:-https://github.com/Dhriti07/google-cloud-java.git}
BRANCH=${BRANCH:-ffi-java}
DIR=${DIR:-$HOME/google-cloud-java}

sudo apt-get update -q
sudo apt-get install -y -q openjdk-21-jdk-headless maven git build-essential pkg-config \
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

SKIP="-DskipTests -Dclirr.skip -Denforcer.skip -Danimal.sniffer.skip -Djacoco.skip -Dcheckstyle.skip"
# The branch is based on an upstream commit that needs unreleased platform BOMs.
mvn -q -B install -f sdk-platform-java/pom.xml -P quick-build -T 1C $SKIP
mvn -q -B install -pl java-storage/google-cloud-storage -am -T 1C $SKIP
(cd java-storage/google-cloud-storage/rust-ffi && cargo build --release)
(cd java-storage/rust-poc-benchmark && mvn -q -B package $SKIP)

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
