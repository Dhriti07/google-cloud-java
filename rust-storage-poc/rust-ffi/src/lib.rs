// Copyright 2026 Google LLC
//
// PoC: wrapper around google-cloud-storage (Rust), exposed to Java in two ways:
//   1. C ABI (`extern "C"` functions below) -> called from Java via Project Panama (FFM API).
//   2. JNI (`jni_bridge` module)            -> called from Java via classic `native` methods.
// Both entry points share the same core logic (`core_*` functions), so differences between them
// come only from the Java <-> Rust bridge. The benchmark itself measures full end-to-end cost
// (heap <-> native copies, argument conversion, error mapping, SDK work), not just the crossing.
//
// Tokio runtime: one per `GcsClientHandle`, never per request. `GCS_RUST_RUNTIME` picks the flavor:
//   current_thread (default) - the SDK runs on the calling Java thread, no thread hops (like a
//                              `#[tokio::main(flavor = "current_thread")]` Rust program).
//   multi_thread[:N]         - N worker threads (default: #CPUs), like the C++/Python PoCs.
//                              Java still blocks in `block_on` until the call finishes.
//
// Retries: `GCS_RUST_RETRY` = never (default) | default. `never` disables retries and read resumes,
// matching the benchmark's java-storage client (retries off), so both sides do the
// same number of attempts.

use bytes::{Buf, Bytes};
use google_cloud_gax::retry_policy::NeverRetry;
use google_cloud_storage::client::{Storage, StorageControl};
use google_cloud_storage::read_object::ReadObjectResponse;
use google_cloud_storage::read_resume_policy::NeverResume;
use std::alloc::{GlobalAlloc, Layout, System};
use std::any::Any;
use std::cell::Cell;
use std::future::Future;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;
use tokio::runtime::{Builder, Runtime};

mod jni_bridge;

// ---------------------------------------------------------------------------------------------
// Counting allocator: lets the benchmark measure bytes allocated by Rust per operation, the same
// way the JVM reports bytes allocated by Java threads. Allocations made directly by C code (e.g.
// aws-lc's own malloc calls) are not seen here.
// ---------------------------------------------------------------------------------------------

struct CountingAlloc;

/// Total bytes ever allocated (monotonic; a realloc counts its new size).
static ALLOCATED_BYTES: AtomicU64 = AtomicU64::new(0);
/// Bytes currently allocated.
static LIVE_BYTES: AtomicU64 = AtomicU64::new(0);

unsafe impl GlobalAlloc for CountingAlloc {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let p = unsafe { System.alloc(layout) };
        if !p.is_null() {
            ALLOCATED_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
            LIVE_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        }
        p
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let p = unsafe { System.alloc_zeroed(layout) };
        if !p.is_null() {
            ALLOCATED_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
            LIVE_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        }
        p
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) };
        LIVE_BYTES.fetch_sub(layout.size() as u64, Ordering::Relaxed);
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let p = unsafe { System.realloc(ptr, layout, new_size) };
        if !p.is_null() {
            ALLOCATED_BYTES.fetch_add(new_size as u64, Ordering::Relaxed);
            LIVE_BYTES.fetch_add(new_size as u64, Ordering::Relaxed);
            LIVE_BYTES.fetch_sub(layout.size() as u64, Ordering::Relaxed);
        }
        p
    }
}

#[global_allocator]
static GLOBAL: CountingAlloc = CountingAlloc;

fn alloc_stats() -> (u64, u64) {
    (
        ALLOCATED_BYTES.load(Ordering::Relaxed),
        LIVE_BYTES.load(Ordering::Relaxed),
    )
}

// ---------------------------------------------------------------------------------------------
// Per-thread counters for the benchmark: how long the calling thread spent inside the Rust SDK
// (network + SDK work, i.e. what a pure Rust caller would also pay), and how many Java -> Rust
// calls it made. Op latency minus SDK time = what the JNI / Panama wrapper adds, measured in the
// same op, so network variation cancels out. Per-thread so it stays per-op under concurrency.
// ---------------------------------------------------------------------------------------------

thread_local! {
    static SDK_NANOS: Cell<u64> = const { Cell::new(0) };
    static FFI_CALLS: Cell<u64> = const { Cell::new(0) };
}

fn add_sdk_nanos(start: Instant) {
    let d = start.elapsed().as_nanos() as u64;
    SDK_NANOS.with(|c| c.set(c.get() + d));
}

/// `block_on` for an SDK call, adding its duration to this thread's SDK time.
fn sdk_block_on<F: Future>(h: &GcsClientHandle, f: F) -> F::Output {
    let start = Instant::now();
    let out = h.rt.block_on(f);
    add_sdk_nanos(start);
    out
}

/// (SDK nanoseconds, Java -> Rust calls) for the calling thread, both monotonic.
fn thread_stats() -> (u64, u64) {
    (SDK_NANOS.with(Cell::get), FFI_CALLS.with(Cell::get))
}

/// Opaque handle returned to Java holding the Tokio runtime and the GCS clients.
pub struct GcsClientHandle {
    rt: Runtime,
    client: Storage,
    /// Used only for DeleteObject (the Rust crate exposes delete via StorageControl / gRPC).
    control: StorageControl,
}

// ---------------------------------------------------------------------------------------------
// Shared core (used by both the C ABI / Panama and the JNI entry points)
// ---------------------------------------------------------------------------------------------

/// Error codes returned across the FFI boundary.
const ERR_GENERIC: i32 = -1;
const ERR_PANIC: i32 = -2;
const ERR_BUFFER_TOO_SMALL: i32 = -3;

type CoreResult<T> = Result<T, (i32, String)>;

/// Runs `f`, converting any Rust panic into an error. A panic unwinding into the JVM is undefined
/// behavior and would crash the process, so every entry point goes through this. Also counts the
/// call for the benchmark's "native calls per op".
fn guarded<T>(name: &str, f: impl FnOnce() -> CoreResult<T>) -> CoreResult<T> {
    FFI_CALLS.with(|c| c.set(c.get() + 1));
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(payload) => Err((
            ERR_PANIC,
            format!("Rust panic in {name}: {}", panic_message(&*payload)),
        )),
    }
}

fn panic_message(payload: &(dyn Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}

fn build_runtime() -> CoreResult<Runtime> {
    let spec = std::env::var("GCS_RUST_RUNTIME").unwrap_or_else(|_| "current_thread".to_string());
    let mut builder = match spec.split_once(':') {
        _ if spec == "current_thread" => Builder::new_current_thread(),
        _ if spec == "multi_thread" => Builder::new_multi_thread(),
        Some(("multi_thread", n)) => {
            let workers: usize = n.parse().map_err(|_| {
                (ERR_GENERIC, format!("invalid GCS_RUST_RUNTIME={spec}: bad worker count"))
            })?;
            let mut b = Builder::new_multi_thread();
            b.worker_threads(workers);
            b
        }
        _ => {
            return Err((
                ERR_GENERIC,
                format!("invalid GCS_RUST_RUNTIME={spec} (current_thread | multi_thread[:N])"),
            ));
        }
    };
    builder
        .enable_all()
        .build()
        .map_err(|e| (ERR_GENERIC, format!("failed to build tokio runtime ({spec}): {e}")))
}

/// `GCS_RUST_RETRY`: `never` (default) or `default` (the crate's own retry/resume policies).
fn retries_disabled() -> CoreResult<bool> {
    match std::env::var("GCS_RUST_RETRY").as_deref() {
        Err(_) | Ok("never") => Ok(true),
        Ok("default") => Ok(false),
        Ok(other) => Err((
            ERR_GENERIC,
            format!("invalid GCS_RUST_RETRY={other} (never | default)"),
        )),
    }
}

fn core_client_new() -> CoreResult<Box<GcsClientHandle>> {
    let rt = build_runtime()?;
    let no_retry = retries_disabled()?;
    // Single-request uploads by default, matching java-storage's create(BlobInfo, byte[]).
    // (The Rust SDK otherwise switches to multi-request resumable uploads above 16 MiB.)
    // `gcs_write_object_mode` overrides this per call.
    let (client, control) = rt.block_on(async {
        let mut builder = Storage::builder().with_resumable_upload_threshold(usize::MAX);
        if no_retry {
            builder = builder
                .with_retry_policy(NeverRetry)
                .with_read_resume_policy(NeverResume);
        }
        let client = builder
            .build()
            .await
            .map_err(|e| (ERR_GENERIC, format!("failed to build Storage client: {e:?}")))?;
        // StorageControl (gRPC) endpoint.
        let mut control_builder =
            StorageControl::builder().with_endpoint("https://storagecontrol.googleapis.com");
        if no_retry {
            control_builder = control_builder.with_retry_policy(NeverRetry);
        }
        let control = control_builder
            .build()
            .await
            .map_err(|e| (ERR_GENERIC, format!("failed to build StorageControl client: {e:?}")))?;
        Ok::<_, (i32, String)>((client, control))
    })?;
    Ok(Box::new(GcsClientHandle {
        rt,
        client,
        control,
    }))
}

fn bucket_resource(bucket: &str) -> String {
    if bucket.starts_with("projects/") {
        bucket.to_string()
    } else {
        format!("projects/_/buckets/{bucket}")
    }
}

/// Upload modes accepted across FFI (`upload_mode` argument).
const UPLOAD_SINGLE_SHOT: i32 = 0;
const UPLOAD_RESUMABLE: i32 = 1;

fn resumable_from_mode(mode: i32) -> CoreResult<bool> {
    match mode {
        UPLOAD_SINGLE_SHOT => Ok(false),
        UPLOAD_RESUMABLE => Ok(true),
        _ => Err((
            ERR_GENERIC,
            format!("invalid upload_mode={mode} (0=single-shot, 1=resumable)"),
        )),
    }
}

/// WriteObject (JSON API). Returns the new object's generation.
/// Uses `set_if_generation_match(0)`, and `resumable` picks the
/// protocol with the threshold (0 = resumable, above the object size = single-shot).
fn core_write_object(
    h: &GcsClientHandle,
    bucket: &str,
    object: &str,
    payload: Bytes,
    resumable: bool,
) -> CoreResult<i64> {
    let bucket = bucket_resource(bucket);
    let threshold = if resumable { 0 } else { payload.len() + 1 };
    let obj = sdk_block_on(h, async {
            h.client
                .write_object(bucket, object, payload)
                .set_if_generation_match(0)
                .with_resumable_upload_threshold(threshold)
                .send_unbuffered()
                .await
        })
        .map_err(|e| (ERR_GENERIC, format!("Rust write_object error: {e:?}")))?;
    Ok(obj.generation)
}

/// ReadObject (JSON API). Copies the object into `dst` and returns the number of bytes read.
fn core_read_object(
    h: &GcsClientHandle,
    bucket: &str,
    object: &str,
    dst: &mut [u8],
) -> CoreResult<usize> {
    let bucket = bucket_resource(bucket);
    sdk_block_on(h, async {
        let mut reader = h
            .client
            .read_object(bucket, object)
            .send()
            .await
            .map_err(|e| (ERR_GENERIC, format!("Rust read_object send error: {e:?}")))?;

        let mut offset = 0usize;
        while let Some(chunk) = reader
            .next()
            .await
            .transpose()
            .map_err(|e| (ERR_GENERIC, format!("Rust read_object stream error: {e:?}")))?
        {
            let end = offset + chunk.len();
            if end > dst.len() {
                return Err((
                    ERR_BUFFER_TOO_SMALL,
                    format!(
                        "destination buffer capacity ({} bytes) exceeded at {end} bytes",
                        dst.len()
                    ),
                ));
            }
            dst[offset..end].copy_from_slice(&chunk);
            offset = end;
        }
        Ok(offset)
    })
}

/// ReadObject (JSON API) into a Rust-owned buffer, for callers that don't know the object size
/// in advance (like `Storage.readAllBytes`). Allocates once using the size from the response.
fn core_read_object_to_vec(h: &GcsClientHandle, bucket: &str, object: &str) -> CoreResult<Vec<u8>> {
    let bucket = bucket_resource(bucket);
    sdk_block_on(h, async {
        let mut reader = h
            .client
            .read_object(bucket, object)
            .send()
            .await
            .map_err(|e| (ERR_GENERIC, format!("Rust read_object send error: {e:?}")))?;

        let mut out = Vec::with_capacity(usize::try_from(reader.object().size).unwrap_or(0));
        while let Some(chunk) = reader
            .next()
            .await
            .transpose()
            .map_err(|e| (ERR_GENERIC, format!("Rust read_object stream error: {e:?}")))?
        {
            out.extend_from_slice(&chunk);
        }
        Ok(out)
    })
}

/// ReadObject (JSON API) that streams the response and discards the bytes inside Rust.
/// Returns the number of bytes received. `generation <= 0` reads the latest generation.
fn core_read_object_discard(
    h: &GcsClientHandle,
    bucket: &str,
    object: &str,
    generation: i64,
) -> CoreResult<usize> {
    let bucket = bucket_resource(bucket);
    sdk_block_on(h, async {
        let mut builder = h.client.read_object(bucket, object);
        if generation > 0 {
            builder = builder.set_generation(generation);
        }
        let mut reader = builder
            .send()
            .await
            .map_err(|e| (ERR_GENERIC, format!("Rust read_object send error: {e:?}")))?;
        let mut total = 0usize;
        while let Some(chunk) = reader
            .next()
            .await
            .transpose()
            .map_err(|e| (ERR_GENERIC, format!("Rust read_object stream error: {e:?}")))?
        {
            total += chunk.len();
        }
        Ok(total)
    })
}

/// An open streaming read (the shape of java-storage's `ReadChannel`): `core_reader_fill` pulls
/// the next bytes into caller memory. Must be closed before its `GcsClientHandle` is freed.
pub struct GcsReader {
    response: ReadObjectResponse,
    /// Rest of the last chunk the SDK returned that hasn't been copied out yet.
    pending: Bytes,
    done: bool,
}

fn core_reader_open(
    h: &GcsClientHandle,
    bucket: &str,
    object: &str,
    generation: i64,
) -> CoreResult<(Box<GcsReader>, i64)> {
    let bucket = bucket_resource(bucket);
    let response = sdk_block_on(h, async {
        let mut builder = h.client.read_object(bucket, object);
        if generation > 0 {
            builder = builder.set_generation(generation);
        }
        builder
            .send()
            .await
            .map_err(|e| (ERR_GENERIC, format!("Rust read_object send error: {e:?}")))
    })?;
    let size = response.object().size;
    Ok((
        Box::new(GcsReader {
            response,
            pending: Bytes::new(),
            done: false,
        }),
        size,
    ))
}

/// Copies up to `cap` bytes into the caller's memory through `sink(offset, bytes)`, waiting for
/// more data from the network as needed. Returns the number of bytes copied; 0 means end of
/// object. Fills `cap` completely unless the object ends first (like java-storage's
/// `blockingFillFrom` into a 2 MiB buffer).
fn core_reader_fill(
    h: &GcsClientHandle,
    r: &mut GcsReader,
    cap: usize,
    mut sink: impl FnMut(usize, &[u8]) -> CoreResult<()>,
) -> CoreResult<usize> {
    h.rt.block_on(async {
        let mut n = 0usize;
        while n < cap {
            if r.pending.is_empty() {
                if r.done {
                    break;
                }
                let wait = Instant::now();
                let next = r.response.next().await;
                add_sdk_nanos(wait); // only the SDK wait; the copies below are wrapper cost
                match next {
                    None => {
                        r.done = true;
                        break;
                    }
                    Some(Err(e)) => {
                        return Err((ERR_GENERIC, format!("Rust read_object stream error: {e:?}")));
                    }
                    Some(Ok(chunk)) => r.pending = chunk,
                }
                continue;
            }
            let take = (cap - n).min(r.pending.len());
            sink(n, &r.pending[..take])?;
            r.pending.advance(take);
            n += take;
        }
        Ok(n)
    })
}

/// DeleteObject via StorageControl (gRPC), like the C++ PoC's Rust path.
/// `generation <= 0` deletes the live generation.
fn core_delete_object(
    h: &GcsClientHandle,
    bucket: &str,
    object: &str,
    generation: i64,
) -> CoreResult<()> {
    let bucket = bucket_resource(bucket);
    sdk_block_on(h, async {
        let mut builder = h.control.delete_object().set_bucket(bucket).set_object(object);
        if generation > 0 {
            builder = builder.set_generation(generation);
        }
        builder.send().await
    })
    .map_err(|e| (ERR_GENERIC, format!("Rust delete_object error: {e:?}")))
}

/// Zero-copy view of caller-owned native memory (a Panama `MemorySegment` or a JNI
/// `DirectByteBuffer`).
struct BorrowedFfiSlice {
    ptr: *const u8,
    len: usize,
}

// Safety: the memory is owned by the calling Java frame, which blocks synchronously inside
// `rt.block_on(...)` until the write completes.
// PoC caveat: production code must guarantee the SDK drops every clone of the `Bytes` before the
// call returns (or copy / pin the buffer), otherwise this would be a use-after-free.
unsafe impl Send for BorrowedFfiSlice {}
unsafe impl Sync for BorrowedFfiSlice {}

impl AsRef<[u8]> for BorrowedFfiSlice {
    fn as_ref(&self) -> &[u8] {
        unsafe { slice::from_raw_parts(self.ptr, self.len) }
    }
}

/// Wraps caller-owned native memory as `Bytes` without copying.
///
/// # Safety
/// `ptr..ptr+len` must be valid for reads until the call consuming the returned `Bytes` returns.
unsafe fn borrowed_bytes(ptr: *const u8, len: usize) -> Bytes {
    if len == 0 {
        Bytes::new()
    } else {
        Bytes::from_owner(BorrowedFfiSlice { ptr, len })
    }
}

// ---------------------------------------------------------------------------------------------
// C ABI entry points (Phase 1: Project Panama)
// ---------------------------------------------------------------------------------------------

/// Writes an error message into a caller-provided buffer (null-terminated UTF-8).
unsafe fn write_error(err_buf: *mut u8, err_cap: usize, msg: &str) {
    if err_buf.is_null() || err_cap == 0 {
        return;
    }
    let bytes = msg.as_bytes();
    let copy_len = bytes.len().min(err_cap - 1);
    unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), err_buf, copy_len);
        *err_buf.add(copy_len) = 0;
    }
}

/// Borrows a UTF-8 string directly from caller memory without copying.
unsafe fn str_from_raw<'a>(ptr: *const u8, len: usize) -> CoreResult<&'a str> {
    if ptr.is_null() {
        return Err((ERR_GENERIC, "null string pointer passed across FFI".to_string()));
    }
    let bytes = unsafe { slice::from_raw_parts(ptr, len) };
    std::str::from_utf8(bytes).map_err(|e| (ERR_GENERIC, format!("invalid UTF-8 string: {e}")))
}

unsafe fn handle_ref<'a>(handle: *mut GcsClientHandle) -> CoreResult<&'a GcsClientHandle> {
    if handle.is_null() {
        Err((ERR_GENERIC, "null GcsClientHandle".to_string()))
    } else {
        Ok(unsafe { &*handle })
    }
}

/// Creates the client. Returns NULL on failure and writes the reason into `err_buf`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_client_new(err_buf: *mut u8, err_cap: usize) -> *mut GcsClientHandle {
    match guarded("gcs_client_new", core_client_new) {
        Ok(handle) => Box::into_raw(handle),
        Err((_, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            ptr::null_mut()
        }
    }
}

/// Destroys the client.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_client_free(handle: *mut GcsClientHandle) {
    if !handle.is_null() {
        let _ = guarded("gcs_client_free", || {
            drop(unsafe { Box::from_raw(handle) });
            Ok(())
        });
    }
}

/// WriteObject. Zero-copy from caller memory. Always a single-shot upload.
/// Returns 0 on success (generation written to `*out_generation`), or a negative error code with
/// the message in `err_buf` (-1 error, -2 panic).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_write_object(
    handle: *mut GcsClientHandle,
    bucket_ptr: *const u8,
    bucket_len: usize,
    object_ptr: *const u8,
    object_len: usize,
    data_ptr: *const u8,
    data_len: usize,
    out_generation: *mut i64,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    unsafe {
        gcs_write_object_mode(
            handle,
            bucket_ptr,
            bucket_len,
            object_ptr,
            object_len,
            data_ptr,
            data_len,
            UPLOAD_SINGLE_SHOT,
            out_generation,
            err_buf,
            err_cap,
        )
    }
}

/// WriteObject with an explicit upload protocol: `upload_mode` 0 = single-shot, 1 = resumable.
/// Same return convention as `gcs_write_object`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_write_object_mode(
    handle: *mut GcsClientHandle,
    bucket_ptr: *const u8,
    bucket_len: usize,
    object_ptr: *const u8,
    object_len: usize,
    data_ptr: *const u8,
    data_len: usize,
    upload_mode: i32,
    out_generation: *mut i64,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    let res = guarded("gcs_write_object_mode", || {
        let h = unsafe { handle_ref(handle)? };
        let bucket = unsafe { str_from_raw(bucket_ptr, bucket_len)? };
        let object = unsafe { str_from_raw(object_ptr, object_len)? };
        if data_ptr.is_null() && data_len > 0 {
            return Err((ERR_GENERIC, "null data_ptr with non-zero data_len".to_string()));
        }
        let resumable = resumable_from_mode(upload_mode)?;
        let payload = unsafe { borrowed_bytes(data_ptr, data_len) };
        core_write_object(h, bucket, object, payload, resumable)
    });

    match res {
        Ok(generation) => {
            if !out_generation.is_null() {
                unsafe { *out_generation = generation };
            }
            0
        }
        Err((code, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            code
        }
    }
}

/// ReadObject that streams the response and discards it inside Rust.
/// `generation <= 0` reads the latest generation. Returns 0 on success (byte count written to
/// `*out_bytes_read`), or a negative error code with the message in `err_buf`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_read_object_discard(
    handle: *mut GcsClientHandle,
    bucket_ptr: *const u8,
    bucket_len: usize,
    object_ptr: *const u8,
    object_len: usize,
    generation: i64,
    out_bytes_read: *mut usize,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    let res = guarded("gcs_read_object_discard", || {
        let h = unsafe { handle_ref(handle)? };
        let bucket = unsafe { str_from_raw(bucket_ptr, bucket_len)? };
        let object = unsafe { str_from_raw(object_ptr, object_len)? };
        core_read_object_discard(h, bucket, object, generation)
    });
    match res {
        Ok(bytes_read) => {
            if !out_bytes_read.is_null() {
                unsafe { *out_bytes_read = bytes_read };
            }
            0
        }
        Err((code, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            code
        }
    }
}

/// DeleteObject. `generation <= 0` deletes the live generation.
/// Returns 0 on success, or a negative error code with the message in `err_buf`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_delete_object(
    handle: *mut GcsClientHandle,
    bucket_ptr: *const u8,
    bucket_len: usize,
    object_ptr: *const u8,
    object_len: usize,
    generation: i64,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    let res = guarded("gcs_delete_object", || {
        let h = unsafe { handle_ref(handle)? };
        let bucket = unsafe { str_from_raw(bucket_ptr, bucket_len)? };
        let object = unsafe { str_from_raw(object_ptr, object_len)? };
        core_delete_object(h, bucket, object, generation)
    });
    match res {
        Ok(()) => 0,
        Err((code, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            code
        }
    }
}

/// ReadObject. Writes the object into caller memory (`dst_ptr`, capacity `dst_cap`).
/// Returns 0 on success (byte count written to `*out_bytes_read`), or a negative error code with
/// the message in `err_buf` (-1 error, -2 panic, -3 buffer too small).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_read_object(
    handle: *mut GcsClientHandle,
    bucket_ptr: *const u8,
    bucket_len: usize,
    object_ptr: *const u8,
    object_len: usize,
    dst_ptr: *mut u8,
    dst_cap: usize,
    out_bytes_read: *mut usize,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    let res = guarded("gcs_read_object", || {
        let h = unsafe { handle_ref(handle)? };
        let bucket = unsafe { str_from_raw(bucket_ptr, bucket_len)? };
        let object = unsafe { str_from_raw(object_ptr, object_len)? };
        if dst_ptr.is_null() && dst_cap > 0 {
            return Err((ERR_GENERIC, "null dst_ptr".to_string()));
        }
        let dst: &mut [u8] = if dst_cap == 0 {
            &mut []
        } else {
            unsafe { slice::from_raw_parts_mut(dst_ptr, dst_cap) }
        };
        core_read_object(h, bucket, object, dst)
    });

    match res {
        Ok(bytes_read) => {
            if !out_bytes_read.is_null() {
                unsafe { *out_bytes_read = bytes_read };
            }
            0
        }
        Err((code, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            code
        }
    }
}

/// ReadObject into a Rust-allocated buffer (the caller doesn't need to know the size upfront).
/// On success returns 0 and writes the buffer pointer/length to `*out_ptr` / `*out_len`.
/// The caller MUST release the buffer with `gcs_free_buffer(ptr, len)`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_read_object_alloc(
    handle: *mut GcsClientHandle,
    bucket_ptr: *const u8,
    bucket_len: usize,
    object_ptr: *const u8,
    object_len: usize,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    let res = guarded("gcs_read_object_alloc", || {
        let h = unsafe { handle_ref(handle)? };
        let bucket = unsafe { str_from_raw(bucket_ptr, bucket_len)? };
        let object = unsafe { str_from_raw(object_ptr, object_len)? };
        if out_ptr.is_null() || out_len.is_null() {
            return Err((ERR_GENERIC, "null out_ptr/out_len".to_string()));
        }
        core_read_object_to_vec(h, bucket, object)
    });

    match res {
        Ok(data) => {
            let boxed = data.into_boxed_slice();
            let len = boxed.len();
            unsafe {
                *out_len = len;
                *out_ptr = Box::into_raw(boxed) as *mut u8;
            }
            0
        }
        Err((code, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            code
        }
    }
}

/// Frees a buffer returned by `gcs_read_object_alloc` or `gcs_buffer_alloc`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_free_buffer(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        let _ = guarded("gcs_free_buffer", || {
            drop(unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(ptr, len)) });
            Ok(())
        });
    }
}

/// Allocates an uninitialized Rust-owned buffer of `len` bytes for an upload. The caller fills it
/// and passes it to `gcs_write_object_owned`, which takes ownership (so the SDK may keep it for as
/// long as it needs, e.g. for retries). If the caller doesn't upload it, it must call
/// `gcs_free_buffer(ptr, len)`. Returns NULL for `len == 0` or on failure.
#[unsafe(no_mangle)]
pub extern "C" fn gcs_buffer_alloc(len: usize) -> *mut u8 {
    if len == 0 {
        return ptr::null_mut();
    }
    guarded("gcs_buffer_alloc", || {
        Ok(Box::into_raw(Box::<[u8]>::new_uninit_slice(len)) as *mut u8)
    })
    .unwrap_or(ptr::null_mut())
}

/// WriteObject from a buffer returned by `gcs_buffer_alloc` and fully written by the caller.
/// Takes ownership of the buffer in all cases, including errors: the caller must not use or free
/// it afterwards. Same return convention as `gcs_write_object_mode`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_write_object_owned(
    handle: *mut GcsClientHandle,
    bucket_ptr: *const u8,
    bucket_len: usize,
    object_ptr: *const u8,
    object_len: usize,
    data_ptr: *mut u8,
    data_len: usize,
    upload_mode: i32,
    out_generation: *mut i64,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    // Take ownership first so the buffer is freed even if validation below fails.
    let payload = if data_ptr.is_null() || data_len == 0 {
        Bytes::new()
    } else {
        // Safety: allocated by `gcs_buffer_alloc(data_len)` and fully written by the caller.
        Bytes::from(unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(data_ptr, data_len)) })
    };
    let res = guarded("gcs_write_object_owned", || {
        let h = unsafe { handle_ref(handle)? };
        let bucket = unsafe { str_from_raw(bucket_ptr, bucket_len)? };
        let object = unsafe { str_from_raw(object_ptr, object_len)? };
        if data_ptr.is_null() && data_len > 0 {
            return Err((ERR_GENERIC, "null data_ptr with non-zero data_len".to_string()));
        }
        let resumable = resumable_from_mode(upload_mode)?;
        core_write_object(h, bucket, object, payload, resumable)
    });
    match res {
        Ok(generation) => {
            if !out_generation.is_null() {
                unsafe { *out_generation = generation };
            }
            0
        }
        Err((code, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            code
        }
    }
}

/// Rust allocator counters: total bytes ever allocated by Rust code in this library, and bytes
/// currently allocated. Either pointer may be NULL.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_alloc_stats(out_allocated: *mut u64, out_live: *mut u64) {
    let (allocated, live) = alloc_stats();
    if !out_allocated.is_null() {
        unsafe { *out_allocated = allocated };
    }
    if !out_live.is_null() {
        unsafe { *out_live = live };
    }
}

/// Opens a streaming read. On success returns 0, writes the reader to `*out_reader` and the object
/// size to `*out_size`. The reader must be released with `gcs_reader_close` before the client is
/// freed. `generation <= 0` reads the latest generation.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_reader_open(
    handle: *mut GcsClientHandle,
    bucket_ptr: *const u8,
    bucket_len: usize,
    object_ptr: *const u8,
    object_len: usize,
    generation: i64,
    out_reader: *mut *mut GcsReader,
    out_size: *mut i64,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    let res = guarded("gcs_reader_open", || {
        let h = unsafe { handle_ref(handle)? };
        let bucket = unsafe { str_from_raw(bucket_ptr, bucket_len)? };
        let object = unsafe { str_from_raw(object_ptr, object_len)? };
        if out_reader.is_null() {
            return Err((ERR_GENERIC, "null out_reader".to_string()));
        }
        core_reader_open(h, bucket, object, generation)
    });
    match res {
        Ok((reader, size)) => {
            unsafe { *out_reader = Box::into_raw(reader) };
            if !out_size.is_null() {
                unsafe { *out_size = size };
            }
            0
        }
        Err((code, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            code
        }
    }
}

/// Copies the next bytes of an open read into `dst[0..dst_cap]` (caller memory), filling it unless
/// the object ends first. Returns 0 and writes the byte count to `*out_n`; a count of 0 means end
/// of object.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_reader_read(
    handle: *mut GcsClientHandle,
    reader: *mut GcsReader,
    dst_ptr: *mut u8,
    dst_cap: usize,
    out_n: *mut usize,
    err_buf: *mut u8,
    err_cap: usize,
) -> i32 {
    let res = guarded("gcs_reader_read", || {
        let h = unsafe { handle_ref(handle)? };
        if reader.is_null() {
            return Err((ERR_GENERIC, "null GcsReader".to_string()));
        }
        if dst_ptr.is_null() && dst_cap > 0 {
            return Err((ERR_GENERIC, "null dst_ptr".to_string()));
        }
        let r = unsafe { &mut *reader };
        core_reader_fill(h, r, dst_cap, |off, bytes| {
            // Safety: off + bytes.len() <= dst_cap, guaranteed by core_reader_fill.
            unsafe { ptr::copy_nonoverlapping(bytes.as_ptr(), dst_ptr.add(off), bytes.len()) };
            Ok(())
        })
    });
    match res {
        Ok(n) => {
            if !out_n.is_null() {
                unsafe { *out_n = n };
            }
            0
        }
        Err((code, msg)) => {
            unsafe { write_error(err_buf, err_cap, &msg) };
            code
        }
    }
}

/// Releases a reader from `gcs_reader_open` (closing the HTTP response if it wasn't fully read).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_reader_close(reader: *mut GcsReader) {
    if !reader.is_null() {
        let _ = guarded("gcs_reader_close", || {
            drop(unsafe { Box::from_raw(reader) });
            Ok(())
        });
    }
}

/// Benchmark counters for the calling thread (monotonic): nanoseconds spent inside the Rust SDK,
/// and Java -> Rust calls made. This call itself is not counted. Either pointer may be NULL.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gcs_thread_stats(out_sdk_nanos: *mut u64, out_ffi_calls: *mut u64) {
    let (sdk, calls) = thread_stats();
    if !out_sdk_nanos.is_null() {
        unsafe { *out_sdk_nanos = sdk };
    }
    if !out_ffi_calls.is_null() {
        unsafe { *out_ffi_calls = calls };
    }
}
