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

//! Bidi Read (gRPC `BidiReadObject`) through the Rust SDK's `open_object`.
//!
//! One Java `BlobReadSession` is one SDK `ObjectDescriptor`. Each range read is its own Tokio
//! task on the shared bidi stream, so many ranges (from many Java threads) can be in flight.

use crate::callback::{Completion, spawn_completing};
use crate::error::{FfiError, FfiResult};
use crate::jni_client::{client, java_string, run};
use crate::proto;
use google_cloud_storage::model_ext::ReadRange;
use google_cloud_storage::object_descriptor::ObjectDescriptor;
use jni::objects::{GlobalRef, JByteArray, JClass, JLongArray, JObject, JString};
use jni::sys::jlong;
use jni::{JNIEnv, JavaVM};

/// Marks an absent precondition in the `long[]` from Java (`Long.MIN_VALUE`).
const UNSET: i64 = i64::MIN;

/// An open read session. Java owns the handle and frees it with `readSessionClose`.
pub(crate) struct ReadSession {
    descriptor: ObjectDescriptor,
}

/// Opens a session; reports `(session handle, google.storage.v2.Object bytes)`.
///
/// `preconditions` is `[generation, ifGenerationMatch, ifGenerationNotMatch,
/// ifMetagenerationMatch, ifMetagenerationNotMatch]`, with `Long.MIN_VALUE` for "not set".
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_readSessionOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    bucket: JString<'local>,
    object: JString<'local>,
    preconditions: JLongArray<'local>,
    completion: JObject<'local>,
) {
    run(&mut env, "readSessionOpen", (), |env| {
        let c = client(client_handle)?;
        let bucket = java_string(env, &bucket)?;
        let object = java_string(env, &object)?;
        let mut pre = [UNSET; 5];
        env.get_long_array_region(&preconditions, 0, &mut pre)?;
        let completion = Completion::new(env, &completion)?;

        let set = |v: i64| (v != UNSET).then_some(v);
        let mut open = c.storage.open_object(bucket, object);
        if let Some(v) = set(pre[0]).filter(|g| *g > 0) {
            open = open.set_generation(v);
        }
        if let Some(v) = set(pre[1]) {
            open = open.set_if_generation_match(v);
        }
        if let Some(v) = set(pre[2]) {
            open = open.set_if_generation_not_match(v);
        }
        if let Some(v) = set(pre[3]) {
            open = open.set_if_metageneration_match(v);
        }
        if let Some(v) = set(pre[4]) {
            open = open.set_if_metageneration_not_match(v);
        }

        spawn_completing(&c.rt, completion, async move {
            let descriptor = open.send().await?;
            let metadata = proto::encode_object(&descriptor.object());
            let session = Box::into_raw(Box::new(ReadSession { descriptor })) as i64;
            Ok((session, Some(metadata)))
        });
        Ok(())
    })
}

/// Reads `[offset, offset + length)` into `dst[0..length)` as the data arrives (one copy per
/// chunk, straight into the Java array). Reports `(bytes read, null)`. `length` must be > 0:
/// the API treats a read length of 0 as "to the end".
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_rangeRead<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    session: jlong,
    offset: jlong,
    length: jlong,
    dst: JByteArray<'local>,
    completion: JObject<'local>,
) {
    run(&mut env, "rangeRead", (), |env| {
        let c = client(client_handle)?;
        let s = session_ref(session)?;
        if offset < 0
            || length <= 0
            || dst.is_null()
            || length > i64::from(env.get_array_length(&dst)?)
        {
            return Err(FfiError::internal(format!(
                "invalid range read: offset={offset} length={length}"
            )));
        }
        let descriptor = s.descriptor.clone();
        let dst = env.new_global_ref(&dst)?;
        let completion = Completion::new(env, &completion)?;
        let vm = completion.vm();
        let length = length as usize;

        spawn_completing(&c.rt, completion, async move {
            let mut response = descriptor
                .read_range(ReadRange::segment(offset as u64, length as u64))
                .await;
            let mut pos = 0usize;
            while let Some(chunk) = response.next().await.transpose()? {
                if pos + chunk.len() > length {
                    return Err(FfiError::internal(format!(
                        "range returned more than the {length} bytes requested"
                    )));
                }
                copy_into(&vm, &dst, pos, &chunk)?;
                pos += chunk.len();
            }
            Ok((pos as i64, None))
        });
        Ok(())
    })
}

/// Frees a session. Range reads already started keep their own reference to the stream.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_readSessionClose<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) {
    run(&mut env, "readSessionClose", (), |_| {
        if session != 0 {
            // Safety: `session` came from readSessionOpen and Java frees it only once.
            drop(unsafe { Box::from_raw(session as *mut ReadSession) });
        }
        Ok(())
    })
}

/// Copies `chunk` into the Java byte[] at `pos`. Synchronous, so the JNI environment (which
/// can't move between threads) is never held across an `await`.
fn copy_into(vm: &JavaVM, dst: &GlobalRef, pos: usize, chunk: &[u8]) -> FfiResult<()> {
    // Tokio worker threads stay attached as daemons, so this is cheap after the first call.
    let env = vm.attach_current_thread_as_daemon()?;
    // Safety: `dst` holds a global reference to this byte[] for the whole read.
    let array = unsafe { JByteArray::from_raw(dst.as_raw()) };
    // Safety: i8 and u8 have the same layout.
    let signed = unsafe { std::slice::from_raw_parts(chunk.as_ptr() as *const i8, chunk.len()) };
    env.set_byte_array_region(&array, pos as i32, signed)?;
    Ok(())
}

fn session_ref<'a>(handle: jlong) -> FfiResult<&'a ReadSession> {
    if handle == 0 {
        Err(FfiError::internal("null read session handle"))
    } else {
        // Safety: a live handle from readSessionOpen; Java checks the session is still open.
        Ok(unsafe { &*(handle as *const ReadSession) })
    }
}
