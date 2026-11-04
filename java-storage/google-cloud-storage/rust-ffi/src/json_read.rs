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

//! Read (JSON API `ReadObject`) through the Rust SDK's `read_object`, for `ReadChannel`.
//!
//! The Java `ReadChannel` is blocking, so these calls run on the calling Java thread with
//! `block_on`, like the Java library waits today.

use crate::error::{FfiError, FfiResult};
use crate::jni_client::{client, run};
use crate::proto::google::storage::v2 as pb;
use bytes::{Buf, Bytes};
use google_cloud_storage::model_ext::ReadRange;
use google_cloud_storage::read_object::ReadObjectResponse;
use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass};
use jni::sys::{jint, jlong};
use prost::Message;
use std::ptr;

/// An open JSON read. Java frees it with `jsonReaderFree`.
pub(crate) struct JsonReader {
    response: ReadObjectResponse,
    /// The part of the last chunk not yet copied to Java.
    pending: Bytes,
    done: bool,
}

/// Opens a read for `request` (a `google.storage.v2.ReadObjectRequest` built by java-storage's
/// own option mapping, including the byte range). Returns the reader handle; the sparse object
/// metadata from the response is written to `metadata_out[0]` as `google.storage.v2.Object`
/// bytes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonReaderOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    request: JByteArray<'local>,
    metadata_out: jni::objects::JObjectArray<'local>,
) -> jlong {
    run(&mut env, "jsonReaderOpen", 0, |env| {
        let c = client(client_handle)?;
        let req = pb::ReadObjectRequest::decode(env.convert_byte_array(&request)?.as_slice())
            .map_err(|e| FfiError::internal(format!("invalid ReadObjectRequest: {e}")))?;
        let (bucket, object) = (req.bucket.clone(), req.object.clone());

        let mut b = c.storage.read_object(req.bucket, req.object);
        if req.generation > 0 {
            b = b.set_generation(req.generation);
        }
        if let Some(v) = req.if_generation_match {
            b = b.set_if_generation_match(v);
        }
        if let Some(v) = req.if_generation_not_match {
            b = b.set_if_generation_not_match(v);
        }
        if let Some(v) = req.if_metageneration_match {
            b = b.set_if_metageneration_match(v);
        }
        if let Some(v) = req.if_metageneration_not_match {
            b = b.set_if_metageneration_not_match(v);
        }
        b = b.set_read_range(read_range(req.read_offset, req.read_limit));

        let response = c.rt.block_on(b.send())?;
        let h = response.object();
        let metadata = pb::Object {
            bucket,
            name: object,
            generation: h.generation,
            metageneration: h.metageneration,
            size: h.size,
            content_encoding: h.content_encoding.clone(),
            content_language: h.content_language.clone(),
            content_type: h.content_type.clone(),
            storage_class: h.storage_class.clone(),
            checksums: h.checksums.as_ref().map(|c| pb::ObjectChecksums {
                crc32c: c.crc32c,
                md5_hash: c.md5_hash.clone(),
            }),
            ..Default::default()
        }
        .encode_to_vec();
        let array = env.byte_array_from_slice(&metadata)?;
        env.set_object_array_element(&metadata_out, 0, &array)?;

        let reader = JsonReader {
            response,
            pending: Bytes::new(),
            done: false,
        };
        Ok(Box::into_raw(Box::new(reader)) as jlong)
    })
}

/// `read_offset` < 0 means "the last |read_offset| bytes"; `read_limit` 0 means "to the end",
/// matching `google.storage.v2.ReadObjectRequest`.
fn read_range(offset: i64, limit: i64) -> ReadRange {
    match (offset, limit) {
        (o, _) if o < 0 => ReadRange::tail(o.unsigned_abs()),
        (o, 0) => ReadRange::offset(o as u64),
        (o, l) => ReadRange::segment(o as u64, l as u64),
    }
}

/// Copies up to `len` bytes into `dst[off..]` of a heap byte[]. Returns the count, or -1 at
/// the end of the object. Like a Java channel, it may return fewer bytes than asked.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonReaderReadArray<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    reader: jlong,
    dst: JByteArray<'local>,
    off: jint,
    len: jint,
) -> jint {
    run(&mut env, "jsonReaderReadArray", -1, |env| {
        let c = client(client_handle)?;
        let r = reader_mut(reader)?;
        if off < 0 || len < 0 {
            return Err(FfiError::internal(format!(
                "invalid region off={off} len={len}"
            )));
        }
        next_bytes(c, r, len as usize, |bytes| {
            // Safety: i8 and u8 have the same layout.
            let signed =
                unsafe { std::slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len()) };
            Ok(env.set_byte_array_region(&dst, off, signed)?)
        })
    })
}

/// Same as `jsonReaderReadArray`, into a direct ByteBuffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonReaderReadDirect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    reader: jlong,
    dst: JByteBuffer<'local>,
    off: jint,
    len: jint,
) -> jint {
    run(&mut env, "jsonReaderReadDirect", -1, |env| {
        let c = client(client_handle)?;
        let r = reader_mut(reader)?;
        let addr = env.get_direct_buffer_address(&dst)?;
        let cap = env.get_direct_buffer_capacity(&dst)?;
        if off < 0 || len < 0 || off as usize + len as usize > cap {
            return Err(FfiError::internal(format!(
                "invalid region off={off} len={len} capacity={cap}"
            )));
        }
        next_bytes(c, r, len as usize, |bytes| {
            // Safety: checked against the capacity; Java keeps the buffer alive for the call.
            unsafe {
                ptr::copy_nonoverlapping(bytes.as_ptr(), addr.add(off as usize), bytes.len())
            };
            Ok(())
        })
    })
}

/// Hands at most `max` buffered bytes to `copy` (one copy into Java), waiting for the next chunk
/// from the network if nothing is buffered. Returns the count, or -1 at the end of the object.
fn next_bytes(
    c: &crate::client::Client,
    r: &mut JsonReader,
    max: usize,
    copy: impl FnOnce(&[u8]) -> FfiResult<()>,
) -> FfiResult<jint> {
    if max == 0 {
        return Ok(0);
    }
    while r.pending.is_empty() {
        if r.done {
            return Ok(-1);
        }
        match c.rt.block_on(r.response.next()) {
            None => r.done = true,
            Some(chunk) => r.pending = chunk?,
        }
    }
    let n = max.min(r.pending.len()).min(i32::MAX as usize);
    copy(&r.pending[..n])?;
    r.pending.advance(n);
    Ok(n as jint)
}

/// Frees a reader (closing the HTTP response if it wasn't fully read).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonReaderFree<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    reader: jlong,
) {
    run(&mut env, "jsonReaderFree", (), |_| {
        if reader != 0 {
            // Safety: `reader` came from jsonReaderOpen and Java frees it only once.
            drop(unsafe { Box::from_raw(reader as *mut JsonReader) });
        }
        Ok(())
    })
}

fn reader_mut<'a>(handle: jlong) -> FfiResult<&'a mut JsonReader> {
    if handle == 0 {
        Err(FfiError::internal("null JSON reader handle"))
    } else {
        // Safety: a live handle from jsonReaderOpen; Java uses one reader from one thread.
        Ok(unsafe { &mut *(handle as *mut JsonReader) })
    }
}
