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

//! Metadata that crosses JNI as `google.storage.v2` protobuf binary.
//!
//! The Rust SDK returns its own model types and keeps its protobuf types private, so the
//! connector converts the model into types generated from the same `storage.proto` Java uses
//! (see `build.rs`). Java parses the bytes with `com.google.storage.v2.Object.parseFrom` and
//! builds `BlobInfo` with java-storage's existing converter, the same work the gRPC transport
//! does today.

use google_cloud_storage::model;
use prost::Message;

/// Generated types. The module tree mirrors the proto packages because the generated code
/// refers to other packages with relative `super::` paths.
#[allow(clippy::all, dead_code, rustdoc::all)]
pub(crate) mod google {
    pub mod storage {
        pub mod v2 {
            include!(concat!(env!("OUT_DIR"), "/google.storage.v2.rs"));
        }
    }
    pub mod iam {
        pub mod v1 {
            include!(concat!(env!("OUT_DIR"), "/google.iam.v1.rs"));
        }
    }
    pub mod r#type {
        include!(concat!(env!("OUT_DIR"), "/google.r#type.rs"));
    }
    pub mod rpc {
        include!(concat!(env!("OUT_DIR"), "/google.rpc.rs"));
    }
    pub mod api {
        include!(concat!(env!("OUT_DIR"), "/google.api.rs"));
    }
}

use google::storage::v2 as pb;

/// Encodes an SDK object as `google.storage.v2.Object` bytes for Java.
// Used by the Bidi Read commit that follows.
#[allow(dead_code)]
pub(crate) fn encode_object(o: &model::Object) -> Vec<u8> {
    object(o).encode_to_vec()
}

/// Field-by-field copy of the SDK model into the protobuf type.
///
/// Not copied: `acl` (the gRPC API doesn't return it unless asked through the read mask),
/// `contexts` and `retention` (not used by the PoC workloads).
fn object(o: &model::Object) -> pb::Object {
    pb::Object {
        name: o.name.clone(),
        bucket: o.bucket.clone(),
        etag: o.etag.clone(),
        generation: o.generation,
        restore_token: o.restore_token.clone(),
        metageneration: o.metageneration,
        storage_class: o.storage_class.clone(),
        size: o.size,
        content_encoding: o.content_encoding.clone(),
        content_disposition: o.content_disposition.clone(),
        cache_control: o.cache_control.clone(),
        content_language: o.content_language.clone(),
        delete_time: o.delete_time.as_ref().map(timestamp),
        finalize_time: o.finalize_time.as_ref().map(timestamp),
        content_type: o.content_type.clone(),
        create_time: o.create_time.as_ref().map(timestamp),
        component_count: o.component_count,
        checksums: o.checksums.as_ref().map(|c| pb::ObjectChecksums {
            crc32c: c.crc32c,
            md5_hash: c.md5_hash.clone(),
        }),
        update_time: o.update_time.as_ref().map(timestamp),
        kms_key: o.kms_key.clone(),
        update_storage_class_time: o.update_storage_class_time.as_ref().map(timestamp),
        temporary_hold: o.temporary_hold,
        retention_expire_time: o.retention_expire_time.as_ref().map(timestamp),
        metadata: o.metadata.clone(),
        event_based_hold: o.event_based_hold,
        owner: o.owner.as_ref().map(|w| pb::Owner {
            entity: w.entity.clone(),
            entity_id: w.entity_id.clone(),
        }),
        customer_encryption: o
            .customer_encryption
            .as_ref()
            .map(|e| pb::CustomerEncryption {
                encryption_algorithm: e.encryption_algorithm.clone(),
                key_sha256_bytes: e.key_sha256_bytes.clone(),
            }),
        custom_time: o.custom_time.as_ref().map(timestamp),
        soft_delete_time: o.soft_delete_time.as_ref().map(timestamp),
        hard_delete_time: o.hard_delete_time.as_ref().map(timestamp),
        ..Default::default()
    }
}

fn timestamp(t: &google_cloud_wkt::Timestamp) -> prost_types::Timestamp {
    prost_types::Timestamp {
        seconds: t.seconds(),
        nanos: t.nanos(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bytes::Bytes;
    use google_cloud_wkt::Timestamp;

    fn full_object() -> model::Object {
        model::Object::new()
            .set_name("dir/obj")
            .set_bucket("projects/_/buckets/b")
            .set_etag("CNeWnPDXzowDEAE=")
            .set_generation(1793764481748311i64)
            .set_metageneration(3)
            .set_storage_class("STANDARD")
            .set_size(16_777_216)
            .set_content_type("application/octet-stream")
            .set_content_encoding("gzip")
            .set_content_disposition("inline")
            .set_cache_control("no-cache")
            .set_content_language("en")
            .set_component_count(2)
            .set_kms_key("projects/p/locations/l/keyRings/r/cryptoKeys/k")
            .set_temporary_hold(true)
            .set_event_based_hold(false)
            .set_create_time(Timestamp::clamp(1_793_700_000, 123_000_000))
            .set_update_time(Timestamp::clamp(1_793_700_001, 0))
            .set_custom_time(Timestamp::clamp(1_793_700_002, 5))
            .set_checksums(
                model::ObjectChecksums::new()
                    .set_crc32c(123_456_789u32)
                    .set_md5_hash(Bytes::from_static(&[7u8; 16])),
            )
            .set_metadata([("owner", "bench"), ("run", "r1")])
            .set_owner(
                model::Owner::new()
                    .set_entity("user-x")
                    .set_entity_id("id-1"),
            )
            .set_customer_encryption(
                model::CustomerEncryption::new()
                    .set_encryption_algorithm("AES256")
                    .set_key_sha256_bytes(Bytes::from_static(&[9u8; 32])),
            )
    }

    #[test]
    fn object_round_trips_through_protobuf() {
        let bytes = encode_object(&full_object());
        let p = pb::Object::decode(bytes.as_slice()).unwrap();

        assert_eq!(p.name, "dir/obj");
        assert_eq!(p.bucket, "projects/_/buckets/b");
        assert_eq!(p.etag, "CNeWnPDXzowDEAE=");
        assert_eq!(p.generation, 1793764481748311);
        assert_eq!(p.metageneration, 3);
        assert_eq!(p.storage_class, "STANDARD");
        assert_eq!(p.size, 16_777_216);
        assert_eq!(p.content_type, "application/octet-stream");
        assert_eq!(p.content_encoding, "gzip");
        assert_eq!(p.content_disposition, "inline");
        assert_eq!(p.cache_control, "no-cache");
        assert_eq!(p.content_language, "en");
        assert_eq!(p.component_count, 2);
        assert_eq!(p.kms_key, "projects/p/locations/l/keyRings/r/cryptoKeys/k");
        assert!(p.temporary_hold);
        assert_eq!(p.event_based_hold, Some(false));
        let ct = p.create_time.unwrap();
        assert_eq!((ct.seconds, ct.nanos), (1_793_700_000, 123_000_000));
        assert_eq!(p.update_time.unwrap().seconds, 1_793_700_001);
        assert_eq!(p.custom_time.unwrap().nanos, 5);
        let c = p.checksums.unwrap();
        assert_eq!(c.crc32c, Some(123_456_789));
        assert_eq!(c.md5_hash.as_ref(), &[7u8; 16]);
        assert_eq!(p.metadata.get("run").map(String::as_str), Some("r1"));
        assert_eq!(p.owner.unwrap().entity_id, "id-1");
        let e = p.customer_encryption.unwrap();
        assert_eq!(e.encryption_algorithm, "AES256");
        assert_eq!(e.key_sha256_bytes.len(), 32);
    }

    #[test]
    fn empty_object_has_no_optional_fields() {
        let p = pb::Object::decode(encode_object(&model::Object::new()).as_slice()).unwrap();

        assert!(p.create_time.is_none());
        assert!(p.checksums.is_none());
        assert!(p.event_based_hold.is_none());
        assert_eq!(p.generation, 0);
    }
}
