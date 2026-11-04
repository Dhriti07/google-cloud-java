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

  /** Returns a client handle. {@code endpoint} may be null for the default endpoint. */
  static native long clientNew(@Nullable String endpoint);

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
}
