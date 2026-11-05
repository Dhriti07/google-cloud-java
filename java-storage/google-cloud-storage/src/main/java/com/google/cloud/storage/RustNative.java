/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.storage;

import java.nio.ByteBuffer;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * JNI functions implemented by the Rust connector ({@code rust-ffi/src/jni_client.rs}).
 *
 * <p>Local PoC only. The library is loaded from the {@code gcs.rust.lib} system property. Failures
 * are thrown as {@link RustNativeException}.
 */
final class RustNative {

  static final String LIBRARY_PROPERTY = "gcs.rust.lib";

  /** Optional: number of gRPC connections for the data path. Unset = Rust SDK default. */
  static final String SUBCHANNELS_PROPERTY = "gcs.rust.grpc.subchannels";

  private static boolean loaded;

  private RustNative() {}

  /** True when the Rust connector is switched on with {@code -Dgcs.rust.lib}. */
  static boolean isConfigured() {
    String path = System.getProperty(LIBRARY_PROPERTY);
    return path != null && !path.isEmpty();
  }

  static synchronized void ensureLoaded() {
    if (loaded) {
      return;
    }
    String path = System.getProperty(LIBRARY_PROPERTY);
    if (path == null || path.isEmpty()) {
      throw new IllegalStateException(
          "Rust connector not configured: set -D"
              + LIBRARY_PROPERTY
              + "=<path to libgcs_rust_ffi.so>");
    }
    System.load(path);
    loaded = true;
  }

  /**
   * Returns a client handle. {@code endpoint} may be null for the default endpoint. {@code
   * grpcSubchannels} sets the data-path gRPC connection count; 0 keeps the Rust SDK default (one
   * per CPU).
   */
  static native long clientNew(@Nullable String endpoint, int grpcSubchannels);

  /** Frees a handle from {@link #clientNew}. Must be called exactly once per handle. */
  static native void clientClose(long handle);

  /**
   * Opens a Bidi Read session. Completes with (session handle, {@code google.storage.v2.Object}
   * bytes). {@code preconditions}: [generation, ifGenerationMatch, ifGenerationNotMatch,
   * ifMetagenerationMatch, ifMetagenerationNotMatch], {@code Long.MIN_VALUE} for "not set".
   */
  static native void readSessionOpen(
      long client, String bucket, String object, long[] preconditions, RustCompletion completion);

  /**
   * Reads {@code [offset, offset + length)} into {@code dst}. Completes with the number of bytes
   * read. {@code length} must be greater than 0.
   */
  static native void rangeRead(
      long client, long session, long offset, long length, byte[] dst, RustCompletion completion);

  /** Frees a session from {@link #readSessionOpen}. Must be called exactly once per session. */
  static native void readSessionClose(long session);

  /**
   * Opens a Bidi Appendable Write for {@code writeObjectSpec} ({@code google.storage.v2
   * .WriteObjectSpec} bytes). Blocks until the stream is open. Returns the appender handle.
   */
  static native long appendableOpen(long client, byte[] writeObjectSpec);

  /** Appends {@code src[off, off+len)}, copied once into Rust. Blocks until Rust accepts it. */
  static native void appendableAppendArray(
      long client, long appender, byte[] src, int off, int len);

  /** Appends {@code src[off, off+len)} of a direct buffer, copied once into Rust. */
  static native void appendableAppendDirect(
      long client, long appender, ByteBuffer src, int off, int len);

  /** Blocks until everything appended is persisted. Returns the persisted size. */
  static native long appendableFlush(long client, long appender);

  /** Finalizes the object. Returns its {@code google.storage.v2.Object} bytes. */
  static native byte[] appendableFinalize(long client, long appender);

  /** Closes without finalizing. Returns the persisted size. */
  static native long appendableClose(long client, long appender);

  /** The generation of the object being written. */
  static native long appendableGeneration(long appender);

  /** Frees an appender. Must be called exactly once per appender. */
  static native void appendableFree(long appender);

  /**
   * Fetches one page of List Objects. Takes {@code google.storage.v2.ListObjectsRequest} bytes and
   * returns {@code google.storage.v2.ListObjectsResponse} bytes. Blocks until the page arrives.
   */
  static native byte[] listObjects(long client, byte[] listObjectsRequest);

  /**
   * Fetches one page of List Buckets. Takes {@code google.storage.v2.ListBucketsRequest} bytes and
   * returns {@code google.storage.v2.ListBucketsResponse} bytes. Blocks until the page arrives.
   */
  static native byte[] listBuckets(long client, byte[] listBucketsRequest);

  /**
   * Opens a JSON read for {@code google.storage.v2.ReadObjectRequest} bytes (including the range).
   * Blocks until the response starts. Writes the sparse object metadata ({@code
   * google.storage.v2.Object} bytes) to {@code metadataOut[0]}. Returns the reader handle.
   */
  static native long jsonReaderOpen(long client, byte[] readObjectRequest, byte[][] metadataOut);

  /** Copies up to {@code len} bytes into {@code dst[off..]}. Returns the count, -1 at the end. */
  static native int jsonReaderReadArray(long client, long reader, byte[] dst, int off, int len);

  /** Same as {@link #jsonReaderReadArray}, into a direct buffer. */
  static native int jsonReaderReadDirect(
      long client, long reader, ByteBuffer dst, int off, int len);

  /** Frees a reader. Must be called exactly once per reader. */
  static native void jsonReaderFree(long reader);

  /**
   * Starts a JSON resumable upload for {@code google.storage.v2.WriteObjectSpec} bytes, using
   * {@code chunkSize} as the upload buffer size. Returns the writer handle.
   */
  static native long jsonWriterOpen(long client, byte[] writeObjectSpec, int chunkSize);

  /** Queues {@code src[off, off+len)}, copied once into Rust. Blocks while the queue is full. */
  static native void jsonWriterWriteArray(long client, long writer, byte[] src, int off, int len);

  /** Same as {@link #jsonWriterWriteArray}, from a direct buffer. */
  static native void jsonWriterWriteDirect(
      long client, long writer, ByteBuffer src, int off, int len);

  /** Ends the upload. Returns the object as {@code google.storage.v2.Object} bytes. */
  static native byte[] jsonWriterClose(long client, long writer);

  /** Frees a writer. Must be called exactly once per writer. */
  static native void jsonWriterFree(long writer);
}
