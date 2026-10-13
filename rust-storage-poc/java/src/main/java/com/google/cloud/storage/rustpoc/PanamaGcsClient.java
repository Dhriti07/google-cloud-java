package com.google.cloud.storage.rustpoc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Phase 1 PoC: Project Panama (Java FFM API) wrapper over the Rust google-cloud-storage SDK.
 *
 * <p>Key production features demonstrated:
 * <ul>
 *   <li>Zero JNI boilerplate: binds directly to C-ABI symbols in {@code libgcs_rust_ffi.so}.
 *   <li>Zero-copy WriteObject & ReadObject: passes off-heap {@link MemorySegment} pointers
 *       directly to/from Rust without intermediate heap byte[] copies.
 *   <li>Single-threaded Rust Tokio runtime owned by the native client handle.
 *   <li>Structured error & panic propagation into Java exceptions with full stack traces.
 * </ul>
 */
public final class PanamaGcsClient implements AutoCloseable {

  private static final int ERR_BUF_SIZE = 2048;

  private final MethodHandle fnClientNew;
  private final MethodHandle fnClientFree;
  private final MethodHandle fnWriteObject;
  private final MethodHandle fnReadObject;
  private final MethodHandle fnReadObjectAlloc;
  private final MethodHandle fnFreeBuffer;
  private final MethodHandle fnWriteObjectMode;
  private final MethodHandle fnReadObjectDiscard;
  private final MethodHandle fnDeleteObject;
  private final MethodHandle fnBufferAlloc;
  private final MethodHandle fnWriteObjectOwned;
  private final MethodHandle fnAllocStats;
  private final MethodHandle fnReaderOpen;
  private final MethodHandle fnReaderRead;
  private final MethodHandle fnReaderClose;
  private final MethodHandle fnThreadStats;

  /** Reusable native buffer Rust fills during streaming reads (grown on demand, freed on close). */
  private final Arena scratchArena = Arena.ofShared();
  private MemorySegment scratch = MemorySegment.NULL;

  private MemorySegment clientHandle;

  public PanamaGcsClient(Path sharedLibPath) {
    Linker linker = Linker.nativeLinker();
    SymbolLookup lookup = SymbolLookup.libraryLookup(sharedLibPath, Arena.global());

    // gcs_client_new(err_buf: *mut u8, err_cap: usize) -> *mut GcsClientHandle
    this.fnClientNew =
        linker.downcallHandle(
            lookup.find("gcs_client_new").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    // gcs_client_free(handle: *mut GcsClientHandle) -> void
    this.fnClientFree =
        linker.downcallHandle(
            lookup.find("gcs_client_free").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    // gcs_write_object(
    //   handle, bucket_ptr, bucket_len, object_ptr, object_len,
    //   data_ptr, data_len, out_generation, err_buf, err_cap
    // ) -> i32
    this.fnWriteObject =
        linker.downcallHandle(
            lookup.find("gcs_write_object").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // bucket_ptr
                ValueLayout.JAVA_LONG, // bucket_len
                ValueLayout.ADDRESS,   // object_ptr
                ValueLayout.JAVA_LONG, // object_len
                ValueLayout.ADDRESS,   // data_ptr
                ValueLayout.JAVA_LONG, // data_len
                ValueLayout.ADDRESS,   // out_generation (*mut i64)
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_read_object(
    //   handle, bucket_ptr, bucket_len, object_ptr, object_len,
    //   dst_ptr, dst_cap, out_bytes_read, err_buf, err_cap
    // ) -> i32
    this.fnReadObject =
        linker.downcallHandle(
            lookup.find("gcs_read_object").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // bucket_ptr
                ValueLayout.JAVA_LONG, // bucket_len
                ValueLayout.ADDRESS,   // object_ptr
                ValueLayout.JAVA_LONG, // object_len
                ValueLayout.ADDRESS,   // dst_ptr
                ValueLayout.JAVA_LONG, // dst_cap
                ValueLayout.ADDRESS,   // out_bytes_read (*mut usize)
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_read_object_alloc(
    //   handle, bucket_ptr, bucket_len, object_ptr, object_len,
    //   out_ptr (*mut *mut u8), out_len (*mut usize), err_buf, err_cap
    // ) -> i32
    this.fnReadObjectAlloc =
        linker.downcallHandle(
            lookup.find("gcs_read_object_alloc").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // bucket_ptr
                ValueLayout.JAVA_LONG, // bucket_len
                ValueLayout.ADDRESS,   // object_ptr
                ValueLayout.JAVA_LONG, // object_len
                ValueLayout.ADDRESS,   // out_ptr
                ValueLayout.ADDRESS,   // out_len
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_free_buffer(ptr: *mut u8, len: usize) -> void
    this.fnFreeBuffer =
        linker.downcallHandle(
            lookup.find("gcs_free_buffer").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    // gcs_write_object_mode(
    //   handle, bucket_ptr, bucket_len, object_ptr, object_len,
    //   data_ptr, data_len, upload_mode (0 single-shot, 1 resumable), out_generation, err_buf, err_cap
    // ) -> i32
    this.fnWriteObjectMode =
        linker.downcallHandle(
            lookup.find("gcs_write_object_mode").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // bucket_ptr
                ValueLayout.JAVA_LONG, // bucket_len
                ValueLayout.ADDRESS,   // object_ptr
                ValueLayout.JAVA_LONG, // object_len
                ValueLayout.ADDRESS,   // data_ptr
                ValueLayout.JAVA_LONG, // data_len
                ValueLayout.JAVA_INT,  // upload_mode
                ValueLayout.ADDRESS,   // out_generation (*mut i64)
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_read_object_discard(
    //   handle, bucket_ptr, bucket_len, object_ptr, object_len, generation,
    //   out_bytes_read, err_buf, err_cap
    // ) -> i32
    this.fnReadObjectDiscard =
        linker.downcallHandle(
            lookup.find("gcs_read_object_discard").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // bucket_ptr
                ValueLayout.JAVA_LONG, // bucket_len
                ValueLayout.ADDRESS,   // object_ptr
                ValueLayout.JAVA_LONG, // object_len
                ValueLayout.JAVA_LONG, // generation
                ValueLayout.ADDRESS,   // out_bytes_read (*mut usize)
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_delete_object(
    //   handle, bucket_ptr, bucket_len, object_ptr, object_len, generation, err_buf, err_cap
    // ) -> i32
    this.fnDeleteObject =
        linker.downcallHandle(
            lookup.find("gcs_delete_object").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // bucket_ptr
                ValueLayout.JAVA_LONG, // bucket_len
                ValueLayout.ADDRESS,   // object_ptr
                ValueLayout.JAVA_LONG, // object_len
                ValueLayout.JAVA_LONG, // generation
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_buffer_alloc(len: usize) -> *mut u8
    this.fnBufferAlloc =
        linker.downcallHandle(
            lookup.find("gcs_buffer_alloc").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    // gcs_write_object_owned(
    //   handle, bucket_ptr, bucket_len, object_ptr, object_len,
    //   data_ptr (from gcs_buffer_alloc, ownership moves to Rust), data_len, upload_mode,
    //   out_generation, err_buf, err_cap
    // ) -> i32
    this.fnWriteObjectOwned =
        linker.downcallHandle(
            lookup.find("gcs_write_object_owned").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // bucket_ptr
                ValueLayout.JAVA_LONG, // bucket_len
                ValueLayout.ADDRESS,   // object_ptr
                ValueLayout.JAVA_LONG, // object_len
                ValueLayout.ADDRESS,   // data_ptr
                ValueLayout.JAVA_LONG, // data_len
                ValueLayout.JAVA_INT,  // upload_mode
                ValueLayout.ADDRESS,   // out_generation (*mut i64)
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_alloc_stats(out_allocated: *mut u64, out_live: *mut u64) -> void
    this.fnAllocStats =
        linker.downcallHandle(
            lookup.find("gcs_alloc_stats").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // gcs_reader_open(
    //   handle, bucket_ptr, bucket_len, object_ptr, object_len, generation,
    //   out_reader (*mut *mut GcsReader), out_size (*mut i64), err_buf, err_cap
    // ) -> i32
    this.fnReaderOpen =
        linker.downcallHandle(
            lookup.find("gcs_reader_open").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // bucket_ptr
                ValueLayout.JAVA_LONG, // bucket_len
                ValueLayout.ADDRESS,   // object_ptr
                ValueLayout.JAVA_LONG, // object_len
                ValueLayout.JAVA_LONG, // generation
                ValueLayout.ADDRESS,   // out_reader
                ValueLayout.ADDRESS,   // out_size
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_reader_read(handle, reader, dst_ptr, dst_cap, out_n (*mut usize), err_buf, err_cap) -> i32
    this.fnReaderRead =
        linker.downcallHandle(
            lookup.find("gcs_reader_read").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // reader
                ValueLayout.ADDRESS,   // dst_ptr
                ValueLayout.JAVA_LONG, // dst_cap
                ValueLayout.ADDRESS,   // out_n
                ValueLayout.ADDRESS,   // err_buf
                ValueLayout.JAVA_LONG  // err_cap
                ));

    // gcs_reader_close(reader) -> void
    this.fnReaderClose =
        linker.downcallHandle(
            lookup.find("gcs_reader_close").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    // gcs_thread_stats(out_sdk_nanos: *mut u64, out_ffi_calls: *mut u64) -> void
    this.fnThreadStats =
        linker.downcallHandle(
            lookup.find("gcs_thread_stats").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);
      MemorySegment handle = (MemorySegment) fnClientNew.invokeExact(errBuf, (long) ERR_BUF_SIZE);
      if (handle.equals(MemorySegment.NULL)) {
        throw new RuntimeException("Failed to initialize Rust Storage client: " + readError(errBuf));
      }
      this.clientHandle = handle;
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected error invoking gcs_client_new", t);
    }
  }

  /**
   * Uploads an off-heap {@link MemorySegment} directly to GCS via the Rust SDK (zero-copy).
   *
   * @return the created object's generation number.
   */
  public long writeObject(String bucket, String objectName, MemorySegment payload) {
    ensureOpen();
    try (Arena arena = Arena.ofConfined()) {
      byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
      byte[] objectBytes = objectName.getBytes(StandardCharsets.UTF_8);

      MemorySegment bucketSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bucketBytes);
      MemorySegment objectSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, objectBytes);
      MemorySegment outGen = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);

      int rc =
          (int)
              fnWriteObject.invokeExact(
                  clientHandle,
                  bucketSeg,
                  (long) bucketBytes.length,
                  objectSeg,
                  (long) objectBytes.length,
                  payload,
                  payload.byteSize(),
                  outGen,
                  errBuf,
                  (long) ERR_BUF_SIZE);

      if (rc != 0) {
        throw new RuntimeException(
            "Rust gcs_write_object failed (code=" + rc + "): " + readError(errBuf));
      }
      return outGen.get(ValueLayout.JAVA_LONG, 0);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in writeObject", t);
    }
  }

  /**
   * Downloads an object from GCS via the Rust SDK directly into the caller's off-heap
   * {@link MemorySegment} buffer (zero-copy across the FFI boundary).
   *
   * @return number of bytes written into {@code destination}.
   */
  public long readObject(String bucket, String objectName, MemorySegment destination) {
    ensureOpen();
    try (Arena arena = Arena.ofConfined()) {
      byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
      byte[] objectBytes = objectName.getBytes(StandardCharsets.UTF_8);

      MemorySegment bucketSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bucketBytes);
      MemorySegment objectSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, objectBytes);
      MemorySegment outBytesRead = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);

      int rc =
          (int)
              fnReadObject.invokeExact(
                  clientHandle,
                  bucketSeg,
                  (long) bucketBytes.length,
                  objectSeg,
                  (long) objectBytes.length,
                  destination,
                  destination.byteSize(),
                  outBytesRead,
                  errBuf,
                  (long) ERR_BUF_SIZE);

      if (rc != 0) {
        throw new RuntimeException(
            "Rust gcs_read_object failed (code=" + rc + "): " + readError(errBuf));
      }
      return outBytesRead.get(ValueLayout.JAVA_LONG, 0);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in readObject", t);
    }
  }

  /**
   * Uploads a heap {@code byte[]} (the API shape java-storage users call). Pays the real
   * production cost: one copy from the Java heap into native memory, then zero-copy into Rust.
   *
   * @return the created object's generation number.
   */
  public long write(String bucket, String objectName, byte[] data) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment payload = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
      return writeObject(bucket, objectName, payload);
    }
  }

  /**
   * Downloads a whole object into a new heap {@code byte[]} without knowing its size upfront (like
   * {@code Storage.readAllBytes}). Rust allocates the buffer; Java copies it into a byte[] and
   * frees the native buffer.
   */
  public byte[] readAllBytes(String bucket, String objectName) {
    ensureOpen();
    try (Arena arena = Arena.ofConfined()) {
      byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
      byte[] objectBytes = objectName.getBytes(StandardCharsets.UTF_8);

      MemorySegment bucketSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bucketBytes);
      MemorySegment objectSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, objectBytes);
      MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment outLen = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);

      int rc =
          (int)
              fnReadObjectAlloc.invokeExact(
                  clientHandle,
                  bucketSeg,
                  (long) bucketBytes.length,
                  objectSeg,
                  (long) objectBytes.length,
                  outPtr,
                  outLen,
                  errBuf,
                  (long) ERR_BUF_SIZE);
      if (rc != 0) {
        throw new RuntimeException(
            "Rust gcs_read_object_alloc failed (code=" + rc + "): " + readError(errBuf));
      }

      long len = outLen.get(ValueLayout.JAVA_LONG, 0);
      MemorySegment nativeBuf = outPtr.get(ValueLayout.ADDRESS, 0).reinterpret(len);
      try {
        return nativeBuf.toArray(ValueLayout.JAVA_BYTE);
      } finally {
        fnFreeBuffer.invokeExact(nativeBuf, len);
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in readAllBytes", t);
    }
  }

  /**
   * Uploads a heap {@code byte[]} with an explicit protocol (single-shot or resumable). The array
   * is copied once into a Rust-allocated buffer, and Rust takes ownership of it, so the SDK may
   * keep the payload as long as it needs (same single copy as JNI's {@code convert_byte_array}).
   */
  public long write(String bucket, String objectName, byte[] data, boolean resumable) {
    ensureOpen();
    try (Arena arena = Arena.ofConfined()) {
      byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
      byte[] objectBytes = objectName.getBytes(StandardCharsets.UTF_8);
      MemorySegment bucketSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bucketBytes);
      MemorySegment objectSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, objectBytes);
      MemorySegment outGen = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);

      MemorySegment payload = MemorySegment.NULL;
      if (data.length > 0) {
        payload = (MemorySegment) fnBufferAlloc.invokeExact((long) data.length);
        if (payload.equals(MemorySegment.NULL)) {
          throw new OutOfMemoryError("gcs_buffer_alloc(" + data.length + ") failed");
        }
        payload = payload.reinterpret(data.length);
        MemorySegment.copy(data, 0, payload, ValueLayout.JAVA_BYTE, 0, data.length);
      }

      // Ownership of `payload` moves to Rust here, on success and on error.
      int rc =
          (int)
              fnWriteObjectOwned.invokeExact(
                  clientHandle,
                  bucketSeg,
                  (long) bucketBytes.length,
                  objectSeg,
                  (long) objectBytes.length,
                  payload,
                  (long) data.length,
                  resumable ? 1 : 0,
                  outGen,
                  errBuf,
                  (long) ERR_BUF_SIZE);
      if (rc != 0) {
        throw new RuntimeException(
            "Rust gcs_write_object_owned failed (code=" + rc + "): " + readError(errBuf));
      }
      return outGen.get(ValueLayout.JAVA_LONG, 0);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in write", t);
    }
  }

  /** Total bytes ever allocated by Rust code in the loaded library (counting allocator). */
  public long rustAllocatedBytes() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
      fnAllocStats.invokeExact(out, MemorySegment.NULL);
      return out.get(ValueLayout.JAVA_LONG, 0);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in rustAllocatedBytes", t);
    }
  }

  /** {SDK nanoseconds, Java -> Rust calls} for the calling thread (monotonic). */
  public long[] rustThreadStats() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment sdk = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment calls = arena.allocate(ValueLayout.JAVA_LONG);
      fnThreadStats.invokeExact(sdk, calls);
      return new long[] {sdk.get(ValueLayout.JAVA_LONG, 0), calls.get(ValueLayout.JAVA_LONG, 0)};
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in rustThreadStats", t);
    }
  }

  /**
   * Streams an object into Java memory, the way java-storage's {@code ReadChannel} is used. Rust
   * fills a reusable native scratch buffer (one downcall per fill), and Java copies it into {@code
   * buffer}'s heap array; then {@code sink} consumes each filled buffer. That's two copies (Rust
   * chunk → native, native → heap): a regular Panama downcall can't let Rust write into a heap
   * array while it waits on the network (only GC-blocking "critical" downcalls can).
   *
   * @param buffer a heap buffer (e.g. a 2 MiB copy buffer); cleared before each fill.
   * @return total bytes read.
   */
  public long read(
      String bucket,
      String objectName,
      long generation,
      java.nio.ByteBuffer buffer,
      JniGcsClient.BufferSink sink)
      throws java.io.IOException {
    ensureOpen();
    if (!buffer.hasArray()) {
      throw new IllegalArgumentException("buffer must be a heap ByteBuffer");
    }
    int cap = buffer.capacity();
    if (scratch.byteSize() < cap) {
      scratch = scratchArena.allocate(cap, 64);
    }
    MemorySegment reader;
    try (Arena arena = Arena.ofConfined()) {
      byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
      byte[] objectBytes = objectName.getBytes(StandardCharsets.UTF_8);
      MemorySegment bucketSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bucketBytes);
      MemorySegment objectSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, objectBytes);
      MemorySegment outReader = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);
      int rc =
          (int)
              fnReaderOpen.invokeExact(
                  clientHandle,
                  bucketSeg,
                  (long) bucketBytes.length,
                  objectSeg,
                  (long) objectBytes.length,
                  generation,
                  outReader,
                  MemorySegment.NULL,
                  errBuf,
                  (long) ERR_BUF_SIZE);
      if (rc != 0) {
        throw new RuntimeException(
            "Rust gcs_reader_open failed (code=" + rc + "): " + readError(errBuf));
      }
      reader = outReader.get(ValueLayout.ADDRESS, 0);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in read (open)", t);
    }

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment outN = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);
      MemorySegment heap = MemorySegment.ofArray(buffer.array());
      long heapBase = buffer.arrayOffset();
      long total = 0;
      while (true) {
        int rc =
            (int)
                fnReaderRead.invokeExact(
                    clientHandle, reader, scratch, (long) cap, outN, errBuf, (long) ERR_BUF_SIZE);
        if (rc != 0) {
          throw new RuntimeException(
              "Rust gcs_reader_read failed (code=" + rc + "): " + readError(errBuf));
        }
        int n = (int) outN.get(ValueLayout.JAVA_LONG, 0);
        if (n == 0) {
          return total;
        }
        MemorySegment.copy(scratch, 0, heap, heapBase, n);
        buffer.clear().limit(n);
        sink.accept(buffer);
        total += n;
      }
    } catch (RuntimeException | java.io.IOException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in read", t);
    } finally {
      try {
        fnReaderClose.invokeExact(reader);
      } catch (Throwable t) {
        // best effort; the read result (or its exception) matters more
      }
    }
  }

  /**
   * Streams an object and discards the bytes inside Rust (nothing is copied into Java).
   *
   * @param generation the generation to read, or {@code <= 0} for the latest.
   * @return number of bytes received.
   */
  public long readDiscard(String bucket, String objectName, long generation) {
    ensureOpen();
    try (Arena arena = Arena.ofConfined()) {
      byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
      byte[] objectBytes = objectName.getBytes(StandardCharsets.UTF_8);
      MemorySegment bucketSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bucketBytes);
      MemorySegment objectSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, objectBytes);
      MemorySegment outBytes = arena.allocate(ValueLayout.JAVA_LONG);
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);

      int rc =
          (int)
              fnReadObjectDiscard.invokeExact(
                  clientHandle,
                  bucketSeg,
                  (long) bucketBytes.length,
                  objectSeg,
                  (long) objectBytes.length,
                  generation,
                  outBytes,
                  errBuf,
                  (long) ERR_BUF_SIZE);
      if (rc != 0) {
        throw new RuntimeException(
            "Rust gcs_read_object_discard failed (code=" + rc + "): " + readError(errBuf));
      }
      return outBytes.get(ValueLayout.JAVA_LONG, 0);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in readDiscard", t);
    }
  }

  /** Deletes an object; {@code generation <= 0} deletes the live generation. */
  public void delete(String bucket, String objectName, long generation) {
    ensureOpen();
    try (Arena arena = Arena.ofConfined()) {
      byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
      byte[] objectBytes = objectName.getBytes(StandardCharsets.UTF_8);
      MemorySegment bucketSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, bucketBytes);
      MemorySegment objectSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, objectBytes);
      MemorySegment errBuf = arena.allocate(ERR_BUF_SIZE);

      int rc =
          (int)
              fnDeleteObject.invokeExact(
                  clientHandle,
                  bucketSeg,
                  (long) bucketBytes.length,
                  objectSeg,
                  (long) objectBytes.length,
                  generation,
                  errBuf,
                  (long) ERR_BUF_SIZE);
      if (rc != 0) {
        throw new RuntimeException(
            "Rust gcs_delete_object failed (code=" + rc + "): " + readError(errBuf));
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException("Unexpected FFI failure in delete", t);
    }
  }

  private void ensureOpen() {
    if (clientHandle == null || clientHandle.equals(MemorySegment.NULL)) {
      throw new IllegalStateException("PanamaGcsClient is already closed");
    }
  }

  private static String readError(MemorySegment errBuf) {
    return errBuf.reinterpret(ERR_BUF_SIZE).getString(0, StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    if (clientHandle != null && !clientHandle.equals(MemorySegment.NULL)) {
      try {
        fnClientFree.invokeExact(clientHandle);
      } catch (Throwable t) {
        throw new RuntimeException("Failed to free Rust GcsClientHandle", t);
      } finally {
        clientHandle = MemorySegment.NULL;
        scratchArena.close();
      }
    }
  }

  /**
   * Quick standalone smoke test for Phase 1 (Panama). Usage: {@code <lib.so> <bucket> [object]}.
   */
  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: PanamaGcsClient <libgcs_rust_ffi.so> <bucket> [object]");
      System.exit(2);
    }
    String libPathStr = args[0];
    String bucket = args[1];
    String objectName = args.length > 2 ? args[2] : "panama_poc_test.txt";

    System.out.println("=== Phase 1 PoC: Project Panama -> Rust Storage SDK ===");
    System.out.println("Loading Rust shared library: " + libPathStr);

    try (PanamaGcsClient client = new PanamaGcsClient(Path.of(libPathStr));
        Arena offHeapArena = Arena.ofConfined()) {

      // 1. WriteObject test
      String message = "Hello from Java 26 Project Panama calling Rust google-cloud-storage!\n";
      byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
      MemorySegment uploadBuffer = offHeapArena.allocateFrom(ValueLayout.JAVA_BYTE, messageBytes);

      long t0 = System.nanoTime();
      long generation = client.writeObject(bucket, objectName, uploadBuffer);
      long writeMs = (System.nanoTime() - t0) / 1_000_000;
      System.out.printf(
          "[WriteObject] Uploaded '%s' (%d bytes) to gs://%s/ -> generation=%d (%d ms)%n",
          objectName, messageBytes.length, bucket, generation, writeMs);

      // 2. ReadObject test
      MemorySegment downloadBuffer = offHeapArena.allocate(4096);
      long t1 = System.nanoTime();
      long bytesRead = client.readObject(bucket, objectName, downloadBuffer);
      long readMs = (System.nanoTime() - t1) / 1_000_000;

      byte[] receivedBytes =
          downloadBuffer.asSlice(0, bytesRead).toArray(ValueLayout.JAVA_BYTE);
      String receivedText = new String(receivedBytes, StandardCharsets.UTF_8);

      System.out.printf(
          "[ReadObject]  Downloaded '%s' (%d bytes) in %d ms:%n  Content: %s",
          objectName, bytesRead, readMs, receivedText);
    }
  }
}
