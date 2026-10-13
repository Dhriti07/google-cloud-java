package com.google.cloud.storage.rustpoc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Phase 2 PoC: JNI wrapper over the Rust google-cloud-storage SDK.
 *
 * <p>Calls the same shared Rust core as {@link PanamaGcsClient}, via the JNI functions in {@code
 * jni_bridge.rs}. Data is passed in direct {@link ByteBuffer}s so Rust reads/writes the native
 * memory directly (no heap byte[] copies across the boundary). Rust errors and panics arrive as
 * Java exceptions thrown from the native code.
 */
public final class JniGcsClient implements AutoCloseable {

  // Implemented in rust-ffi/src/jni_bridge.rs as Java_com_google_cloud_storage_rustpoc_JniGcsClient_*.
  private static native long nativeClientNew();

  private static native void nativeClientFree(long handle);

  private static native long nativeWriteObject(
      long handle, String bucket, String object, ByteBuffer data, int offset, int length);

  private static native long nativeReadObject(
      long handle, String bucket, String object, ByteBuffer dst, int offset, int length);

  private static native long nativeWriteObjectBytes(
      long handle, String bucket, String object, byte[] data, int uploadMode);

  private static native byte[] nativeReadObjectBytes(long handle, String bucket, String object);

  private static native long nativeReadObjectDiscard(
      long handle, String bucket, String object, long generation);

  private static native void nativeDeleteObject(
      long handle, String bucket, String object, long generation);

  private static native long nativeAllocatedBytes();

  private static native long nativeSdkNanos();

  private static native long nativeFfiCalls();

  private static native long nativeReaderOpen(
      long handle, String bucket, String object, long generation);

  private static native int nativeReaderRead(
      long handle, long reader, byte[] dst, int offset, int length);

  private static native void nativeReaderClose(long reader);

  private long handle;

  /** Total bytes ever allocated by Rust code in the loaded library (counting allocator). */
  public long rustAllocatedBytes() {
    return nativeAllocatedBytes();
  }

  /** Nanoseconds the calling thread has spent inside the Rust SDK (monotonic). */
  public long rustSdkNanos() {
    return nativeSdkNanos();
  }

  /** Java -> Rust calls made by the calling thread (monotonic; the counter reads don't count). */
  public long rustFfiCalls() {
    return nativeFfiCalls();
  }

  /**
   * Streams an object into Java memory, the way java-storage's {@code ReadChannel} is used: Rust
   * copies the bytes straight into {@code buffer}'s heap array (one copy, one JNI call per fill),
   * then {@code sink} consumes each filled buffer.
   *
   * @param buffer a heap buffer (e.g. a 2 MiB copy buffer); cleared before each fill.
   * @return total bytes read.
   */
  public long read(
      String bucket, String objectName, long generation, ByteBuffer buffer, BufferSink sink)
      throws java.io.IOException {
    ensureOpen();
    if (!buffer.hasArray()) {
      throw new IllegalArgumentException("buffer must be a heap ByteBuffer");
    }
    long reader = nativeReaderOpen(handle, bucket, objectName, generation);
    try {
      long total = 0;
      byte[] arr = buffer.array();
      int base = buffer.arrayOffset();
      while (true) {
        buffer.clear();
        int n = nativeReaderRead(handle, reader, arr, base, buffer.capacity());
        if (n <= 0) {
          return total;
        }
        buffer.limit(n);
        sink.accept(buffer);
        total += n;
      }
    } finally {
      nativeReaderClose(reader);
    }
  }

  /** Consumes one filled buffer (position 0, limit = bytes read). */
  @FunctionalInterface
  public interface BufferSink {
    void accept(ByteBuffer filled) throws java.io.IOException;
  }

  public JniGcsClient(Path sharedLibPath) {
    System.load(sharedLibPath.toAbsolutePath().toString());
    this.handle = nativeClientNew();
  }

  /**
   * Uploads {@code data[position, limit)} (must be a direct buffer) and advances its position.
   *
   * @return the created object's generation number.
   */
  public long writeObject(String bucket, String objectName, ByteBuffer data) {
    ensureOpen();
    requireDirect(data);
    int length = data.remaining();
    long generation =
        nativeWriteObject(handle, bucket, objectName, data, data.position(), length);
    data.position(data.position() + length);
    return generation;
  }

  /**
   * Downloads an object into {@code dst[position, limit)} (must be a direct buffer) and advances
   * its position.
   *
   * @return number of bytes read.
   */
  public long readObject(String bucket, String objectName, ByteBuffer dst) {
    ensureOpen();
    requireDirect(dst);
    long bytesRead =
        nativeReadObject(handle, bucket, objectName, dst, dst.position(), dst.remaining());
    dst.position(dst.position() + (int) bytesRead);
    return bytesRead;
  }

  /**
   * Uploads a heap {@code byte[]} (the API shape java-storage users call) as a single-shot upload.
   * Rust copies the array once into native memory.
   *
   * @return the created object's generation number.
   */
  public long write(String bucket, String objectName, byte[] data) {
    return write(bucket, objectName, data, false);
  }

  /** Like {@link #write(String, String, byte[])}, choosing single-shot or resumable upload. */
  public long write(String bucket, String objectName, byte[] data, boolean resumable) {
    ensureOpen();
    return nativeWriteObjectBytes(handle, bucket, objectName, data, resumable ? 1 : 0);
  }

  /** Downloads a whole object into a new heap {@code byte[]} (like {@code Storage.readAllBytes}). */
  public byte[] readAllBytes(String bucket, String objectName) {
    ensureOpen();
    return nativeReadObjectBytes(handle, bucket, objectName);
  }

  /**
   * Streams an object and discards the bytes inside Rust (nothing is copied into Java).
   *
   * @param generation the generation to read, or {@code <= 0} for the latest.
   * @return number of bytes received.
   */
  public long readDiscard(String bucket, String objectName, long generation) {
    ensureOpen();
    return nativeReadObjectDiscard(handle, bucket, objectName, generation);
  }

  /** Deletes an object; {@code generation <= 0} deletes the live generation. */
  public void delete(String bucket, String objectName, long generation) {
    ensureOpen();
    nativeDeleteObject(handle, bucket, objectName, generation);
  }

  private static void requireDirect(ByteBuffer buffer) {
    if (!buffer.isDirect()) {
      throw new IllegalArgumentException("ByteBuffer must be direct (ByteBuffer.allocateDirect)");
    }
  }

  private void ensureOpen() {
    if (handle == 0) {
      throw new IllegalStateException("JniGcsClient is already closed");
    }
  }

  @Override
  public void close() {
    if (handle != 0) {
      nativeClientFree(handle);
      handle = 0;
    }
  }

  /** Quick standalone smoke test for Phase 2 (JNI). Usage: {@code <lib.so> <bucket> [object]}. */
  public static void main(String[] args) {
    if (args.length < 2) {
      System.err.println("usage: JniGcsClient <libgcs_rust_ffi.so> <bucket> [object]");
      System.exit(2);
    }
    String libPathStr = args[0];
    String bucket = args[1];
    String objectName = args.length > 2 ? args[2] : "jni_poc_test.txt";

    System.out.println("=== Phase 2 PoC: JNI -> Rust Storage SDK ===");
    System.out.println("Loading Rust shared library: " + libPathStr);

    try (JniGcsClient client = new JniGcsClient(Path.of(libPathStr))) {
      // 1. WriteObject test
      byte[] messageBytes =
          "Hello from Java JNI calling Rust google-cloud-storage!\n"
              .getBytes(StandardCharsets.UTF_8);
      ByteBuffer uploadBuffer = ByteBuffer.allocateDirect(messageBytes.length);
      uploadBuffer.put(messageBytes).flip();

      long t0 = System.nanoTime();
      long generation = client.writeObject(bucket, objectName, uploadBuffer);
      long writeMs = (System.nanoTime() - t0) / 1_000_000;
      System.out.printf(
          "[WriteObject] Uploaded '%s' (%d bytes) to gs://%s/ -> generation=%d (%d ms)%n",
          objectName, messageBytes.length, bucket, generation, writeMs);

      // 2. ReadObject test
      ByteBuffer downloadBuffer = ByteBuffer.allocateDirect(4096);
      long t1 = System.nanoTime();
      long bytesRead = client.readObject(bucket, objectName, downloadBuffer);
      long readMs = (System.nanoTime() - t1) / 1_000_000;

      downloadBuffer.flip();
      byte[] receivedBytes = new byte[downloadBuffer.remaining()];
      downloadBuffer.get(receivedBytes);
      System.out.printf(
          "[ReadObject]  Downloaded '%s' (%d bytes) in %d ms:%n  Content: %s",
          objectName, bytesRead, readMs, new String(receivedBytes, StandardCharsets.UTF_8));

      // 3. Error path: a missing object should surface as a Java exception, not a JVM crash.
      try {
        client.readObject(bucket, "does-not-exist-" + System.nanoTime(), ByteBuffer.allocateDirect(16));
        System.out.println("[ErrorPath]   UNEXPECTED: no exception for missing object");
      } catch (RuntimeException e) {
        String msg = e.getMessage();
        System.out.println(
            "[ErrorPath]   Got expected exception: "
                + (msg.length() > 160 ? msg.substring(0, 160) + "..." : msg));
      }
    }
  }
}
