// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Generates Rust types for `google.storage.v2` from the same `storage.proto` the Java
//! library uses, so metadata can cross JNI as protobuf binary. Needs `protoc` on the PATH
//! (or `PROTOC=<path>`).

fn main() -> std::io::Result<()> {
    // Paths are relative to this crate: java-storage/google-cloud-storage/rust-ffi.
    let storage = "../../proto-google-cloud-storage-v2/src/main/proto";
    let common = "../../../java-common-protos/proto-google-common-protos/src/main/proto";
    let iam = "../../../java-iam/proto-google-iam-v1/src/main/proto";
    let storage_proto = format!("{storage}/google/storage/v2/storage.proto");

    println!("cargo:rerun-if-changed={storage_proto}");
    prost_build::Config::new()
        // `bytes::Bytes` instead of `Vec<u8>`, so copying a field from the SDK's model (which
        // also uses `Bytes`) doesn't copy the data.
        .bytes(["."])
        .compile_protos(&[storage_proto.as_str()], &[storage, common, iam])
}
