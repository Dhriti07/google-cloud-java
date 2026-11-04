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

//! Write (JSON API resumable upload) through the Rust SDK's `write_object`, for `WriteChannel`.
//!
//! The upload runs as a Tokio task reading from a bounded channel. Each Java `write` puts one
//! chunk on the channel (blocking while it is full); `close` ends the stream and waits for the
//! object. This keeps the data streaming without knowing the size up front, like the Java
//! channel.

use crate::client::Client;
use crate::error::{FfiError, FfiResult};
use crate::jni_client::{client, run};
use crate::proto::{self, google::storage::v2 as pb};
use bytes::Bytes;
use google_cloud_storage::model::Object;
use google_cloud_storage::streaming_source::StreamingSource;
use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass};
use jni::sys::{jbyteArray, jint, jlong};
use prost::Message;
use std::ptr;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

/// Chunks in flight between Java and the upload task.
const QUEUE_DEPTH: usize = 2;

/// Upload source fed by Java `write` calls.
struct ChannelSource {
    rx: mpsc::Receiver<Bytes>,
}

impl StreamingSource for ChannelSource {
    type Error = std::io::Error;

    async fn next(&mut self) -> Option<Result<Bytes, Self::Error>> {
        self.rx.recv().await.map(Ok)
    }
}

/// An open JSON upload. Java frees it with `jsonWriterFree`.
pub(crate) struct JsonWriter {
    tx: Option<mpsc::Sender<Bytes>>,
    task: Option<JoinHandle<google_cloud_storage::Result<Object>>>,
}

/// Starts a resumable upload for `spec` (a `google.storage.v2.WriteObjectSpec` built by
/// java-storage's own option mapping). `chunk_size` is the Java channel's chunk size, used as
/// the SDK's resumable upload buffer so both sides send the same chunk sizes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonWriterOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    spec: JByteArray<'local>,
    chunk_size: jint,
) -> jlong {
    run(&mut env, "jsonWriterOpen", 0, |env| {
        let c = client(client_handle)?;
        let spec = pb::WriteObjectSpec::decode(env.convert_byte_array(&spec)?.as_slice())
            .map_err(|e| FfiError::internal(format!("invalid WriteObjectSpec: {e}")))?;
        if chunk_size <= 0 {
            return Err(FfiError::internal(format!(
                "invalid chunk size {chunk_size}"
            )));
        }
        let (tx, rx) = mpsc::channel(QUEUE_DEPTH);
        let task = start(c, spec, ChannelSource { rx }, chunk_size as usize)?;
        let writer = JsonWriter {
            tx: Some(tx),
            task: Some(task),
        };
        Ok(Box::into_raw(Box::new(writer)) as jlong)
    })
}

fn start(
    c: &Client,
    spec: pb::WriteObjectSpec,
    source: ChannelSource,
    chunk_size: usize,
) -> FfiResult<JoinHandle<google_cloud_storage::Result<Object>>> {
    let r = spec
        .resource
        .ok_or_else(|| FfiError::internal("WriteObjectSpec has no resource"))?;
    // Java's writer() always uses a resumable upload; threshold 0 does the same here.
    let mut b = c
        .storage
        .write_object(r.bucket, r.name, source)
        .with_resumable_upload_threshold(0usize)
        .with_resumable_upload_buffer_size(chunk_size);
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
    if !r.content_type.is_empty() {
        b = b.set_content_type(r.content_type);
    }
    if !r.cache_control.is_empty() {
        b = b.set_cache_control(r.cache_control);
    }
    if !r.content_disposition.is_empty() {
        b = b.set_content_disposition(r.content_disposition);
    }
    if !r.content_encoding.is_empty() {
        b = b.set_content_encoding(r.content_encoding);
    }
    if !r.content_language.is_empty() {
        b = b.set_content_language(r.content_language);
    }
    if !r.storage_class.is_empty() {
        b = b.set_storage_class(r.storage_class);
    }
    if !r.kms_key.is_empty() {
        b = b.set_kms_key(r.kms_key);
    }
    if !r.metadata.is_empty() {
        b = b.set_metadata(r.metadata);
    }
    if let Some(t) = r.custom_time {
        b = b.set_custom_time(google_cloud_wkt::Timestamp::clamp(t.seconds, t.nanos));
    }
    Ok(c.rt.spawn(async move { b.send_buffered().await }))
}

/// Queues `src[off..off+len)` (heap byte[]), copied once into Rust. Blocks while the queue is
/// full.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonWriterWriteArray<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    writer: jlong,
    src: JByteArray<'local>,
    off: jint,
    len: jint,
) {
    run(&mut env, "jsonWriterWriteArray", (), |env| {
        let c = client(client_handle)?;
        let w = writer_mut(writer)?;
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
        send(c, w, Bytes::from(data))
    })
}

/// Same as `jsonWriterWriteArray`, from a direct ByteBuffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonWriterWriteDirect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    writer: jlong,
    src: JByteBuffer<'local>,
    off: jint,
    len: jint,
) {
    run(&mut env, "jsonWriterWriteDirect", (), |env| {
        let c = client(client_handle)?;
        let w = writer_mut(writer)?;
        let addr = env.get_direct_buffer_address(&src)?;
        let cap = env.get_direct_buffer_capacity(&src)?;
        if off < 0 || len < 0 || off as usize + len as usize > cap {
            return Err(FfiError::internal(format!(
                "invalid region off={off} len={len} capacity={cap}"
            )));
        }
        // Safety: checked against the capacity; Java keeps the buffer alive for the call.
        let data = unsafe { std::slice::from_raw_parts(addr.add(off as usize), len as usize) };
        send(c, w, Bytes::copy_from_slice(data))
    })
}

fn send(c: &Client, w: &mut JsonWriter, chunk: Bytes) -> FfiResult<()> {
    let tx =
        w.tx.as_ref()
            .ok_or_else(|| FfiError::internal("writer is already closed"))?;
    if c.rt.block_on(tx.send(chunk)).is_ok() {
        return Ok(());
    }
    // The upload task ended early (it dropped the receiver): report its error.
    w.tx = None;
    join(c, w)?;
    Err(FfiError::internal(
        "upload ended before all data was written",
    ))
}

fn join(c: &Client, w: &mut JsonWriter) -> FfiResult<Object> {
    let task = w
        .task
        .take()
        .ok_or_else(|| FfiError::internal("writer is already closed"))?;
    let result =
        c.rt.block_on(task)
            .map_err(|e| FfiError::internal(format!("upload task failed: {e}")))?;
    Ok(result?)
}

/// Ends the stream and waits for the upload. Returns the object as
/// `google.storage.v2.Object` bytes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonWriterClose<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    writer: jlong,
) -> jbyteArray {
    run(&mut env, "jsonWriterClose", ptr::null_mut(), |env| {
        let c = client(client_handle)?;
        let w = writer_mut(writer)?;
        w.tx = None; // closes the channel, so the source reports the end of the data
        let object = join(c, w)?;
        Ok(env
            .byte_array_from_slice(&proto::encode_object(&object))?
            .into_raw())
    })
}

/// Frees a writer. An unfinished upload is abandoned (its task ends when the channel closes).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_jsonWriterFree<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    writer: jlong,
) {
    run(&mut env, "jsonWriterFree", (), |_| {
        if writer != 0 {
            // Safety: `writer` came from jsonWriterOpen and Java frees it only once.
            drop(unsafe { Box::from_raw(writer as *mut JsonWriter) });
        }
        Ok(())
    })
}

fn writer_mut<'a>(handle: jlong) -> FfiResult<&'a mut JsonWriter> {
    if handle == 0 {
        Err(FfiError::internal("null JSON writer handle"))
    } else {
        // Safety: a live handle from jsonWriterOpen; Java uses one writer from one thread.
        Ok(unsafe { &mut *(handle as *mut JsonWriter) })
    }
}
