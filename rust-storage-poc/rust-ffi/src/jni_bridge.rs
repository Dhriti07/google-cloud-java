// Copyright 2026 Google LLC
//
// Phase 2: JNI entry points. A thin layer that converts JNI types (JString, direct ByteBuffer,
// jlong handles, Java exceptions) into calls to the shared core in lib.rs.
//
// Function names must exactly match `Java_<package with _>_<Class>_<method>` for the JVM to find
// them, so renaming/moving the Java class requires renaming these functions.

use super::{
    CoreResult, ERR_GENERIC, GcsClientHandle, GcsReader, borrowed_bytes, core_client_new,
    core_delete_object, core_read_object, core_read_object_discard, core_read_object_to_vec,
    core_reader_fill, core_reader_open, core_write_object, guarded, resumable_from_mode,
};
use bytes::Bytes;
use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong};
use std::ptr;
use std::slice;

const EXCEPTION_CLASS: &str = "java/lang/RuntimeException";

/// Throws a Java exception (unless one is already pending from a failed JNI call).
fn throw(env: &mut JNIEnv, code: i32, msg: &str) {
    if env.exception_check().unwrap_or(false) {
        return;
    }
    let _ = env.throw_new(
        EXCEPTION_CLASS,
        format!("Rust storage error (code={code}): {msg}"),
    );
}

fn handle_from(handle: jlong) -> CoreResult<&'static GcsClientHandle> {
    if handle == 0 {
        Err((ERR_GENERIC, "null GcsClientHandle".to_string()))
    } else {
        Ok(unsafe { &*(handle as *const GcsClientHandle) })
    }
}

/// Copies a Java String into a Rust String (JNI converts from Java's modified UTF-8).
fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> CoreResult<String> {
    if s.is_null() {
        return Err((ERR_GENERIC, "null Java string".to_string()));
    }
    env.get_string(s)
        .map(Into::into)
        .map_err(|e| (ERR_GENERIC, format!("failed to read Java string: {e}")))
}

/// Returns a raw pointer + length for `buf[offset..offset + len]` of a *direct* ByteBuffer.
fn direct_region(
    env: &JNIEnv,
    buf: &JByteBuffer,
    offset: jint,
    len: jint,
) -> CoreResult<(*mut u8, usize)> {
    if buf.is_null() {
        return Err((ERR_GENERIC, "null ByteBuffer".to_string()));
    }
    if offset < 0 || len < 0 {
        return Err((ERR_GENERIC, format!("invalid offset={offset} len={len}")));
    }
    let addr = env
        .get_direct_buffer_address(buf)
        .map_err(|e| (ERR_GENERIC, format!("ByteBuffer must be direct: {e}")))?;
    let cap = env
        .get_direct_buffer_capacity(buf)
        .map_err(|e| (ERR_GENERIC, format!("failed to get ByteBuffer capacity: {e}")))?;
    let (offset, len) = (offset as usize, len as usize);
    if offset + len > cap {
        return Err((
            ERR_GENERIC,
            format!("offset+len ({}) exceeds ByteBuffer capacity ({cap})", offset + len),
        ));
    }
    Ok((unsafe { addr.add(offset) }, len))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeClientNew<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    match guarded("nativeClientNew", core_client_new) {
        Ok(handle) => Box::into_raw(handle) as jlong,
        Err((code, msg)) => {
            throw(&mut env, code, &msg);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeClientFree<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        let _ = guarded("nativeClientFree", || {
            drop(unsafe { Box::from_raw(handle as *mut GcsClientHandle) });
            Ok(())
        });
    }
}

/// Returns the new object's generation. Zero-copy: Rust reads straight from the direct
/// ByteBuffer's native memory.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeWriteObject<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bucket: JString<'local>,
    object: JString<'local>,
    data: JByteBuffer<'local>,
    offset: jint,
    length: jint,
) -> jlong {
    let res = guarded("nativeWriteObject", || {
        let h = handle_from(handle)?;
        let bucket = jstring_to_string(&mut env, &bucket)?;
        let object = jstring_to_string(&mut env, &object)?;
        let (ptr, len) = direct_region(&env, &data, offset, length)?;
        let payload = unsafe { borrowed_bytes(ptr, len) };
        core_write_object(h, &bucket, &object, payload, false)
    });
    match res {
        Ok(generation) => generation,
        Err((code, msg)) => {
            throw(&mut env, code, &msg);
            0
        }
    }
}

/// Returns the number of bytes written into `dst[offset..offset + length]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeReadObject<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bucket: JString<'local>,
    object: JString<'local>,
    dst: JByteBuffer<'local>,
    offset: jint,
    length: jint,
) -> jlong {
    let res = guarded("nativeReadObject", || {
        let h = handle_from(handle)?;
        let bucket = jstring_to_string(&mut env, &bucket)?;
        let object = jstring_to_string(&mut env, &object)?;
        let (ptr, len) = direct_region(&env, &dst, offset, length)?;
        let out: &mut [u8] = if len == 0 {
            &mut []
        } else {
            unsafe { slice::from_raw_parts_mut(ptr, len) }
        };
        core_read_object(h, &bucket, &object, out)
    });
    match res {
        Ok(bytes_read) => bytes_read as jlong,
        Err((code, msg)) => {
            throw(&mut env, code, &msg);
            -1
        }
    }
}

// --- byte[] APIs (what java-storage users call; used by the end-to-end benchmark) ---

/// Uploads a Java `byte[]`. The array is copied once into Rust memory (it can't be pinned for the
/// duration of a network call without blocking the GC), then handed to the SDK without copying.
/// `upload_mode`: 0 = single-shot, 1 = resumable.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeWriteObjectBytes<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bucket: JString<'local>,
    object: JString<'local>,
    data: JByteArray<'local>,
    upload_mode: jint,
) -> jlong {
    let res = guarded("nativeWriteObjectBytes", || {
        let h = handle_from(handle)?;
        let bucket = jstring_to_string(&mut env, &bucket)?;
        let object = jstring_to_string(&mut env, &object)?;
        if data.is_null() {
            return Err((ERR_GENERIC, "null byte[]".to_string()));
        }
        let resumable = resumable_from_mode(upload_mode)?;
        let vec = env
            .convert_byte_array(&data)
            .map_err(|e| (ERR_GENERIC, format!("failed to copy byte[]: {e}")))?;
        core_write_object(h, &bucket, &object, Bytes::from(vec), resumable)
    });
    match res {
        Ok(generation) => generation,
        Err((code, msg)) => {
            throw(&mut env, code, &msg);
            0
        }
    }
}

/// Streams an object and discards the bytes inside Rust. Returns the byte count.
/// `generation <= 0` reads the latest generation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeReadObjectDiscard<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bucket: JString<'local>,
    object: JString<'local>,
    generation: jlong,
) -> jlong {
    let res = guarded("nativeReadObjectDiscard", || {
        let h = handle_from(handle)?;
        let bucket = jstring_to_string(&mut env, &bucket)?;
        let object = jstring_to_string(&mut env, &object)?;
        core_read_object_discard(h, &bucket, &object, generation)
    });
    match res {
        Ok(n) => n as jlong,
        Err((code, msg)) => {
            throw(&mut env, code, &msg);
            -1
        }
    }
}

/// Downloads an object and returns it as a new Java `byte[]` (one copy from Rust memory).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeReadObjectBytes<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bucket: JString<'local>,
    object: JString<'local>,
) -> jbyteArray {
    let res = guarded("nativeReadObjectBytes", || {
        let h = handle_from(handle)?;
        let bucket = jstring_to_string(&mut env, &bucket)?;
        let object = jstring_to_string(&mut env, &object)?;
        let data = core_read_object_to_vec(h, &bucket, &object)?;
        env.byte_array_from_slice(&data)
            .map_err(|e| (ERR_GENERIC, format!("failed to allocate byte[]: {e}")))
    });
    match res {
        Ok(array) => array.into_raw(),
        Err((code, msg)) => {
            throw(&mut env, code, &msg);
            ptr::null_mut()
        }
    }
}

/// Deletes an object. `generation <= 0` deletes the live generation.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeDeleteObject<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bucket: JString<'local>,
    object: JString<'local>,
    generation: jlong,
) {
    let res = guarded("nativeDeleteObject", || {
        let h = handle_from(handle)?;
        let bucket = jstring_to_string(&mut env, &bucket)?;
        let object = jstring_to_string(&mut env, &object)?;
        core_delete_object(h, &bucket, &object, generation)
    });
    if let Err((code, msg)) = res {
        throw(&mut env, code, &msg);
    }
}

/// Total bytes ever allocated by Rust code in this library (counting global allocator).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeAllocatedBytes<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    super::alloc_stats().0 as jlong
}

/// Nanoseconds the calling thread has spent inside the Rust SDK (monotonic, not counted as a call).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeSdkNanos<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    super::thread_stats().0 as jlong
}

/// Java -> Rust calls made by the calling thread (monotonic, not counted as a call).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeFfiCalls<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    super::thread_stats().1 as jlong
}

/// Opens a streaming read; returns an opaque reader handle (free with `nativeReaderClose`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeReaderOpen<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bucket: JString<'local>,
    object: JString<'local>,
    generation: jlong,
) -> jlong {
    let res = guarded("nativeReaderOpen", || {
        let h = handle_from(handle)?;
        let bucket = jstring_to_string(&mut env, &bucket)?;
        let object = jstring_to_string(&mut env, &object)?;
        core_reader_open(h, &bucket, &object, generation)
    });
    match res {
        Ok((reader, _size)) => Box::into_raw(reader) as jlong,
        Err((code, msg)) => {
            throw(&mut env, code, &msg);
            0
        }
    }
}

/// Copies the next bytes into `dst[offset..offset + length]` (a Java heap byte[]), filling it
/// unless the object ends first. Returns the byte count; 0 means end of object.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeReaderRead<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    reader: jlong,
    dst: JByteArray<'local>,
    offset: jint,
    length: jint,
) -> jint {
    let res = guarded("nativeReaderRead", || {
        let h = handle_from(handle)?;
        if reader == 0 {
            return Err((ERR_GENERIC, "null GcsReader".to_string()));
        }
        if dst.is_null() || offset < 0 || length < 0 {
            return Err((ERR_GENERIC, format!("invalid byte[] offset={offset} length={length}")));
        }
        let r = unsafe { &mut *(reader as *mut GcsReader) };
        core_reader_fill(h, r, length as usize, |off, bytes| {
            // Safety: i8 and u8 have the same layout.
            let signed = unsafe { slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len()) };
            env.set_byte_array_region(&dst, offset + off as jint, signed)
                .map_err(|e| (ERR_GENERIC, format!("failed to copy into byte[]: {e}")))
        })
    });
    match res {
        Ok(n) => n as jint,
        Err((code, msg)) => {
            throw(&mut env, code, &msg);
            -1
        }
    }
}

/// Releases a reader from `nativeReaderOpen`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_rustpoc_JniGcsClient_nativeReaderClose<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    reader: jlong,
) {
    if reader != 0 {
        let _ = guarded("nativeReaderClose", || {
            drop(unsafe { Box::from_raw(reader as *mut GcsReader) });
            Ok(())
        });
    }
}
