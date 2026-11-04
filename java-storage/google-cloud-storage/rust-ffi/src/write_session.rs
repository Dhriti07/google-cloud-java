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

//! Bidi Appendable Write (gRPC `BidiWriteObject`) through the Rust SDK's
//! `open_appendable_object`. Appendable objects only exist in zonal buckets.
//!
//! The Java API is blocking (`open`, `write`, `flush`, `finalizeAndClose`), so these calls run
//! the SDK future on the calling Java thread with `block_on`, like the Java library waits today.

use crate::client::Client;
use crate::error::{FfiError, FfiResult};
use crate::jni_client::{client, run};
use crate::proto::{self, google::storage::v2 as pb};
use bytes::Bytes;
use google_cloud_storage::appendable_object_writer::AppendableObjectWriter;
use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass};
use jni::sys::{jbyteArray, jint, jlong};
use prost::Message;
use std::ptr;

/// An open appendable upload. `None` once finalized or closed. Java frees it with
/// `appendableFree`.
pub(crate) struct Appender {
    writer: Option<AppendableObjectWriter>,
    generation: i64,
}

/// Opens the upload described by `spec` (a `google.storage.v2.WriteObjectSpec` built by
/// java-storage's own option mapping). Returns the appender handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_appendableOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    spec: JByteArray<'local>,
) -> jlong {
    run(&mut env, "appendableOpen", 0, |env| {
        let c = client(client_handle)?;
        let spec = pb::WriteObjectSpec::decode(env.convert_byte_array(&spec)?.as_slice())
            .map_err(|e| FfiError::internal(format!("invalid WriteObjectSpec: {e}")))?;
        let writer = c.rt.block_on(open(c, spec))?;
        let generation = writer.generation();
        let appender = Appender {
            writer: Some(writer),
            generation,
        };
        Ok(Box::into_raw(Box::new(appender)) as jlong)
    })
}

async fn open(c: &Client, spec: pb::WriteObjectSpec) -> FfiResult<AppendableObjectWriter> {
    let resource = spec
        .resource
        .ok_or_else(|| FfiError::internal("WriteObjectSpec has no resource"))?;
    let mut b = c
        .storage
        .open_appendable_object(resource.bucket, resource.name);
    if let Some(v) = spec.if_generation_match {
        b = b.set_if_generation_match(v);
    }
    if let Some(v) = spec.if_generation_not_match {
        b = b.set_if_generation_not_match(v);
    }
    if let Some(v) = spec.if_metageneration_match {
        b = b.set_if_metageneration_match(v);
    }
    if let Some(v) = spec.if_metageneration_not_match {
        b = b.set_if_metageneration_not_match(v);
    }
    if !resource.content_type.is_empty() {
        b = b.set_content_type(resource.content_type);
    }
    if !resource.cache_control.is_empty() {
        b = b.set_cache_control(resource.cache_control);
    }
    if !resource.content_disposition.is_empty() {
        b = b.set_content_disposition(resource.content_disposition);
    }
    if !resource.content_encoding.is_empty() {
        b = b.set_content_encoding(resource.content_encoding);
    }
    if !resource.content_language.is_empty() {
        b = b.set_content_language(resource.content_language);
    }
    if !resource.kms_key.is_empty() {
        b = b.set_kms_key(resource.kms_key);
    }
    if !resource.metadata.is_empty() {
        b = b.set_metadata(resource.metadata);
    }
    if let Some(t) = resource.custom_time {
        b = b.set_custom_time(google_cloud_wkt::Timestamp::clamp(t.seconds, t.nanos));
    }
    Ok(b.send().await?)
}

/// Appends `src[off..off+len)` from a heap byte[]. The bytes are copied once into Rust: the SDK
/// keeps them until the service confirms them, and Java may reuse the array after this returns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_appendableAppendArray<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    appender: jlong,
    src: JByteArray<'local>,
    off: jint,
    len: jint,
) {
    run(&mut env, "appendableAppendArray", (), |env| {
        let c = client(client_handle)?;
        let a = appender_mut(appender)?;
        if off < 0 || len < 0 {
            return Err(FfiError::internal(format!(
                "invalid region off={off} len={len}"
            )));
        }
        let mut data = vec![0u8; len as usize];
        // Safety: i8 and u8 have the same layout.
        let dst =
            unsafe { std::slice::from_raw_parts_mut(data.as_mut_ptr() as *mut i8, data.len()) };
        env.get_byte_array_region(&src, off, dst)?;
        append(c, a, Bytes::from(data))
    })
}

/// Appends `src[off..off+len)` from a direct ByteBuffer (copied once, as above).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_appendableAppendDirect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    appender: jlong,
    src: JByteBuffer<'local>,
    off: jint,
    len: jint,
) {
    run(&mut env, "appendableAppendDirect", (), |env| {
        let c = client(client_handle)?;
        let a = appender_mut(appender)?;
        let addr = env.get_direct_buffer_address(&src)?;
        let cap = env.get_direct_buffer_capacity(&src)?;
        if off < 0 || len < 0 || off as usize + len as usize > cap {
            return Err(FfiError::internal(format!(
                "invalid region off={off} len={len} capacity={cap}"
            )));
        }
        // Safety: checked against the buffer's capacity; Java keeps the buffer alive for the call.
        let data = unsafe { std::slice::from_raw_parts(addr.add(off as usize), len as usize) };
        append(c, a, Bytes::copy_from_slice(data))
    })
}

fn append(c: &Client, a: &mut Appender, data: Bytes) -> FfiResult<()> {
    let w = writer(a)?;
    Ok(c.rt.block_on(w.append(data))?)
}

/// Waits until everything appended so far is persisted. Returns the persisted size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_appendableFlush<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    appender: jlong,
) -> jlong {
    run(&mut env, "appendableFlush", -1, |_| {
        let c = client(client_handle)?;
        let w = writer(appender_mut(appender)?)?;
        Ok(c.rt.block_on(w.flush())?)
    })
}

/// Finalizes the object. Returns its `google.storage.v2.Object` bytes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_appendableFinalize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    appender: jlong,
) -> jbyteArray {
    run(&mut env, "appendableFinalize", ptr::null_mut(), |env| {
        let c = client(client_handle)?;
        let w = take_writer(appender_mut(appender)?)?;
        let object = c.rt.block_on(w.finalize())?;
        Ok(env
            .byte_array_from_slice(&proto::encode_object(&object))?
            .into_raw())
    })
}

/// Closes the stream without finalizing; the object stays appendable. Returns the persisted
/// size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_appendableClose<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    appender: jlong,
) -> jlong {
    run(&mut env, "appendableClose", -1, |_| {
        let c = client(client_handle)?;
        let w = take_writer(appender_mut(appender)?)?;
        Ok(c.rt.block_on(w.close())?)
    })
}

/// The generation the service assigned when the upload was opened.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_appendableGeneration<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    appender: jlong,
) -> jlong {
    run(&mut env, "appendableGeneration", 0, |_| {
        Ok(appender_mut(appender)?.generation)
    })
}

/// Frees an appender (dropping an unfinished upload closes its stream).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_appendableFree<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    appender: jlong,
) {
    run(&mut env, "appendableFree", (), |_| {
        if appender != 0 {
            // Safety: `appender` came from appendableOpen and Java frees it only once.
            drop(unsafe { Box::from_raw(appender as *mut Appender) });
        }
        Ok(())
    })
}

fn appender_mut<'a>(handle: jlong) -> FfiResult<&'a mut Appender> {
    if handle == 0 {
        Err(FfiError::internal("null appender handle"))
    } else {
        // Safety: a live handle from appendableOpen; Java serializes calls on one upload.
        Ok(unsafe { &mut *(handle as *mut Appender) })
    }
}

fn writer(a: &mut Appender) -> FfiResult<&mut AppendableObjectWriter> {
    a.writer
        .as_mut()
        .ok_or_else(|| FfiError::internal("appendable upload is already closed"))
}

fn take_writer(a: &mut Appender) -> FfiResult<AppendableObjectWriter> {
    a.writer
        .take()
        .ok_or_else(|| FfiError::internal("appendable upload is already closed"))
}
