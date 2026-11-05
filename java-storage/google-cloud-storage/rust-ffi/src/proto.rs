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
pub(crate) fn encode_object(o: &model::Object) -> Vec<u8> {
    object(o).encode_to_vec()
}

/// Decodes a `google.storage.v2.ListObjectsRequest` from Java (built by java-storage's own
/// option mapping) into the SDK's request type.
pub(crate) fn list_objects_request(
    bytes: &[u8],
) -> Result<model::ListObjectsRequest, prost::DecodeError> {
    let r = pb::ListObjectsRequest::decode(bytes)?;
    let mut m = model::ListObjectsRequest::new()
        .set_parent(r.parent)
        .set_page_size(r.page_size)
        .set_page_token(r.page_token)
        .set_delimiter(r.delimiter)
        .set_include_trailing_delimiter(r.include_trailing_delimiter)
        .set_prefix(r.prefix)
        .set_versions(r.versions)
        .set_lexicographic_start(r.lexicographic_start)
        .set_lexicographic_end(r.lexicographic_end)
        .set_soft_deleted(r.soft_deleted)
        .set_include_folders_as_prefixes(r.include_folders_as_prefixes)
        .set_match_glob(r.match_glob)
        .set_filter(r.filter);
    if let Some(mask) = r.read_mask {
        m = m.set_read_mask(google_cloud_wkt::FieldMask::default().set_paths(mask.paths));
    }
    Ok(m)
}

/// Encodes an SDK list response as `google.storage.v2.ListObjectsResponse` bytes for Java.
pub(crate) fn encode_list_objects_response(r: &model::ListObjectsResponse) -> Vec<u8> {
    pb::ListObjectsResponse {
        objects: r.objects.iter().map(object).collect(),
        prefixes: r.prefixes.clone(),
        next_page_token: r.next_page_token.clone(),
    }
    .encode_to_vec()
}

/// Decodes a `google.storage.v2.ListBucketsRequest` from Java into the SDK's request type.
pub(crate) fn list_buckets_request(
    bytes: &[u8],
) -> Result<model::ListBucketsRequest, prost::DecodeError> {
    let r = pb::ListBucketsRequest::decode(bytes)?;
    let mut m = model::ListBucketsRequest::new()
        .set_parent(r.parent)
        .set_page_size(r.page_size)
        .set_page_token(r.page_token)
        .set_prefix(r.prefix)
        .set_return_partial_success(r.return_partial_success);
    if let Some(mask) = r.read_mask {
        m = m.set_read_mask(google_cloud_wkt::FieldMask::default().set_paths(mask.paths));
    }
    Ok(m)
}

/// Encodes an SDK list response as `google.storage.v2.ListBucketsResponse` bytes for Java.
pub(crate) fn encode_list_buckets_response(r: &model::ListBucketsResponse) -> Vec<u8> {
    pb::ListBucketsResponse {
        buckets: r.buckets.iter().map(bucket).collect(),
        next_page_token: r.next_page_token.clone(),
        unreachable: r.unreachable.clone(),
    }
    .encode_to_vec()
}

/// Field-by-field copy of an SDK bucket into the protobuf type.
///
/// Not copied: `acl` and `default_object_acl` (as for objects), `ip_filter` and the encryption
/// enforcement configs (not used by the PoC workloads).
fn bucket(b: &model::Bucket) -> pb::Bucket {
    use model::bucket as mb;
    use pb::bucket as pbb;
    pb::Bucket {
        name: b.name.clone(),
        bucket_id: b.bucket_id.clone(),
        etag: b.etag.clone(),
        project: b.project.clone(),
        metageneration: b.metageneration,
        location: b.location.clone(),
        location_type: b.location_type.clone(),
        storage_class: b.storage_class.clone(),
        rpo: b.rpo.clone(),
        lifecycle: b
            .lifecycle
            .as_ref()
            .map(|l: &mb::Lifecycle| pbb::Lifecycle {
                rule: l.rule.iter().map(lifecycle_rule).collect(),
            }),
        create_time: b.create_time.as_ref().map(timestamp),
        cors: b
            .cors
            .iter()
            .map(|c| pbb::Cors {
                origin: c.origin.clone(),
                method: c.method.clone(),
                response_header: c.response_header.clone(),
                max_age_seconds: c.max_age_seconds,
            })
            .collect(),
        update_time: b.update_time.as_ref().map(timestamp),
        default_event_based_hold: b.default_event_based_hold,
        labels: b.labels.clone(),
        website: b.website.as_ref().map(|w| pbb::Website {
            main_page_suffix: w.main_page_suffix.clone(),
            not_found_page: w.not_found_page.clone(),
        }),
        versioning: b
            .versioning
            .as_ref()
            .map(|v| pbb::Versioning { enabled: v.enabled }),
        logging: b.logging.as_ref().map(|l| pbb::Logging {
            log_bucket: l.log_bucket.clone(),
            log_object_prefix: l.log_object_prefix.clone(),
        }),
        owner: b.owner.as_ref().map(|w| pb::Owner {
            entity: w.entity.clone(),
            entity_id: w.entity_id.clone(),
        }),
        encryption: b.encryption.as_ref().map(|e| pbb::Encryption {
            default_kms_key: e.default_kms_key.clone(),
            ..Default::default()
        }),
        billing: b.billing.as_ref().map(|x| pbb::Billing {
            requester_pays: x.requester_pays,
        }),
        retention_policy: b.retention_policy.as_ref().map(|r| pbb::RetentionPolicy {
            effective_time: r.effective_time.as_ref().map(timestamp),
            is_locked: r.is_locked,
            retention_duration: r.retention_duration.as_ref().map(duration),
        }),
        iam_config: b.iam_config.as_ref().map(|i| pbb::IamConfig {
            uniform_bucket_level_access: i.uniform_bucket_level_access.as_ref().map(|u| {
                pbb::iam_config::UniformBucketLevelAccess {
                    enabled: u.enabled,
                    lock_time: u.lock_time.as_ref().map(timestamp),
                }
            }),
            public_access_prevention: i.public_access_prevention.clone(),
        }),
        satisfies_pzs: b.satisfies_pzs,
        custom_placement_config: b.custom_placement_config.as_ref().map(|c| {
            pbb::CustomPlacementConfig {
                data_locations: c.data_locations.clone(),
            }
        }),
        autoclass: b.autoclass.as_ref().map(|a| pbb::Autoclass {
            enabled: a.enabled,
            toggle_time: a.toggle_time.as_ref().map(timestamp),
            terminal_storage_class: a.terminal_storage_class.clone(),
            terminal_storage_class_update_time: a
                .terminal_storage_class_update_time
                .as_ref()
                .map(timestamp),
        }),
        hierarchical_namespace: b
            .hierarchical_namespace
            .as_ref()
            .map(|h| pbb::HierarchicalNamespace { enabled: h.enabled }),
        soft_delete_policy: b
            .soft_delete_policy
            .as_ref()
            .map(|d| pbb::SoftDeletePolicy {
                retention_duration: d.retention_duration.as_ref().map(duration),
                effective_time: d.effective_time.as_ref().map(timestamp),
            }),
        object_retention: b
            .object_retention
            .as_ref()
            .map(|o| pbb::ObjectRetention { enabled: o.enabled }),
        ..Default::default()
    }
}

fn lifecycle_rule(r: &model::bucket::lifecycle::Rule) -> pb::bucket::lifecycle::Rule {
    use pb::bucket::lifecycle::rule as pr;
    pb::bucket::lifecycle::Rule {
        action: r.action.as_ref().map(|a| pr::Action {
            r#type: a.r#type.clone(),
            storage_class: a.storage_class.clone(),
        }),
        condition: r.condition.as_ref().map(|c| pr::Condition {
            age_days: c.age_days,
            created_before: c.created_before.as_ref().map(|d| google::r#type::Date {
                year: d.year,
                month: d.month,
                day: d.day,
            }),
            is_live: c.is_live,
            num_newer_versions: c.num_newer_versions,
            matches_storage_class: c.matches_storage_class.clone(),
            days_since_custom_time: c.days_since_custom_time,
            custom_time_before: c.custom_time_before.as_ref().map(|d| google::r#type::Date {
                year: d.year,
                month: d.month,
                day: d.day,
            }),
            days_since_noncurrent_time: c.days_since_noncurrent_time,
            noncurrent_time_before: c.noncurrent_time_before.as_ref().map(|d| {
                google::r#type::Date {
                    year: d.year,
                    month: d.month,
                    day: d.day,
                }
            }),
            matches_prefix: c.matches_prefix.clone(),
            matches_suffix: c.matches_suffix.clone(),
        }),
    }
}

fn duration(d: &google_cloud_wkt::Duration) -> prost_types::Duration {
    prost_types::Duration {
        seconds: d.seconds(),
        nanos: d.nanos(),
    }
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
    fn list_request_round_trips_every_field() {
        let java = pb::ListObjectsRequest {
            parent: "projects/_/buckets/b".into(),
            page_size: 100,
            page_token: "tok".into(),
            delimiter: "/".into(),
            include_trailing_delimiter: true,
            prefix: "p/".into(),
            versions: true,
            read_mask: Some(prost_types::FieldMask {
                paths: vec!["name".into(), "size".into()],
            }),
            lexicographic_start: "a".into(),
            lexicographic_end: "z".into(),
            soft_deleted: true,
            include_folders_as_prefixes: true,
            match_glob: "**.txt".into(),
            filter: "f".into(),
        };
        let m = list_objects_request(&java.encode_to_vec()).unwrap();

        assert_eq!(m.parent, "projects/_/buckets/b");
        assert_eq!(m.page_size, 100);
        assert_eq!(m.page_token, "tok");
        assert_eq!(m.delimiter, "/");
        assert!(m.include_trailing_delimiter);
        assert_eq!(m.prefix, "p/");
        assert!(m.versions);
        assert_eq!(m.read_mask.unwrap().paths, vec!["name", "size"]);
        assert_eq!(m.lexicographic_start, "a");
        assert_eq!(m.lexicographic_end, "z");
        assert!(m.soft_deleted);
        assert!(m.include_folders_as_prefixes);
        assert_eq!(m.match_glob, "**.txt");
        assert_eq!(m.filter, "f");
    }

    #[test]
    fn list_response_round_trips() {
        let resp = model::ListObjectsResponse::new()
            .set_objects([full_object(), model::Object::new().set_name("b")])
            .set_prefixes(["dir/"])
            .set_next_page_token("next");
        let p = pb::ListObjectsResponse::decode(encode_list_objects_response(&resp).as_slice())
            .unwrap();

        assert_eq!(p.objects.len(), 2);
        assert_eq!(p.objects[0].size, 16_777_216);
        assert_eq!(p.objects[1].name, "b");
        assert_eq!(p.prefixes, vec!["dir/"]);
        assert_eq!(p.next_page_token, "next");
    }

    #[test]
    fn list_buckets_request_round_trips_every_field() {
        let java = pb::ListBucketsRequest {
            parent: "projects/p".into(),
            page_size: 50,
            page_token: "tok".into(),
            prefix: "b-".into(),
            read_mask: Some(prost_types::FieldMask {
                paths: vec!["name".into()],
            }),
            return_partial_success: true,
        };
        let m = list_buckets_request(&java.encode_to_vec()).unwrap();

        assert_eq!(m.parent, "projects/p");
        assert_eq!(m.page_size, 50);
        assert_eq!(m.page_token, "tok");
        assert_eq!(m.prefix, "b-");
        assert_eq!(m.read_mask.unwrap().paths, vec!["name"]);
        assert!(m.return_partial_success);
    }

    #[test]
    fn bucket_round_trips_through_protobuf() {
        use model::bucket as mb;
        let b = model::Bucket::new()
            .set_name("projects/_/buckets/b")
            .set_bucket_id("b")
            .set_project("projects/123")
            .set_metageneration(4)
            .set_location("US-CENTRAL1")
            .set_location_type("region")
            .set_storage_class("STANDARD")
            .set_rpo("DEFAULT")
            .set_create_time(Timestamp::clamp(1_793_700_000, 1))
            .set_labels([("team", "gcs")])
            .set_versioning(mb::Versioning::new().set_enabled(true))
            .set_iam_config(
                mb::IamConfig::new()
                    .set_public_access_prevention("enforced")
                    .set_uniform_bucket_level_access(
                        mb::iam_config::UniformBucketLevelAccess::new().set_enabled(true),
                    ),
            )
            .set_soft_delete_policy(
                mb::SoftDeletePolicy::new()
                    .set_retention_duration(google_cloud_wkt::Duration::clamp(604_800, 0)),
            )
            .set_hierarchical_namespace(mb::HierarchicalNamespace::new().set_enabled(true))
            .set_lifecycle(
                mb::Lifecycle::new().set_rule([mb::lifecycle::Rule::new()
                    .set_action(mb::lifecycle::rule::Action::new().set_type("Delete"))
                    .set_condition(mb::lifecycle::rule::Condition::new().set_age_days(30))]),
            )
            .set_cors([mb::Cors::new().set_origin(["*"]).set_max_age_seconds(60)]);
        let resp = model::ListBucketsResponse::new()
            .set_buckets([b])
            .set_next_page_token("next")
            .set_unreachable(["projects/_/buckets/u"]);

        let p = pb::ListBucketsResponse::decode(encode_list_buckets_response(&resp).as_slice())
            .unwrap();
        assert_eq!(p.next_page_token, "next");
        assert_eq!(p.unreachable, vec!["projects/_/buckets/u"]);
        let b = &p.buckets[0];
        assert_eq!(b.name, "projects/_/buckets/b");
        assert_eq!(b.bucket_id, "b");
        assert_eq!(b.project, "projects/123");
        assert_eq!(b.metageneration, 4);
        assert_eq!(b.location, "US-CENTRAL1");
        assert_eq!(b.location_type, "region");
        assert_eq!(b.storage_class, "STANDARD");
        assert_eq!(b.rpo, "DEFAULT");
        assert_eq!(b.create_time.unwrap().nanos, 1);
        assert_eq!(b.labels.get("team").map(String::as_str), Some("gcs"));
        assert!(b.versioning.unwrap().enabled);
        let iam = b.iam_config.clone().unwrap();
        assert_eq!(iam.public_access_prevention, "enforced");
        assert!(iam.uniform_bucket_level_access.unwrap().enabled);
        let sd = b.soft_delete_policy.unwrap();
        assert_eq!(sd.retention_duration.unwrap().seconds, 604_800);
        assert!(b.hierarchical_namespace.unwrap().enabled);
        let rule = &b.lifecycle.as_ref().unwrap().rule[0];
        assert_eq!(rule.action.as_ref().unwrap().r#type, "Delete");
        assert_eq!(rule.condition.as_ref().unwrap().age_days, Some(30));
        assert_eq!(b.cors[0].origin, vec!["*"]);
        assert_eq!(b.cors[0].max_age_seconds, 60);
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
