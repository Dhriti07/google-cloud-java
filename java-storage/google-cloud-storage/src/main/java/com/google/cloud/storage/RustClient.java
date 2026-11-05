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

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Owns one Rust client (Tokio runtime + Rust Storage SDK clients). One per {@link Storage}
 * instance; closed when the {@code Storage} is closed.
 */
final class RustClient implements AutoCloseable {

  private final AtomicLong handle;

  // Runs future completions handed over from Rust threads (see RustCompletion).
  private final ExecutorService callbackExecutor;

  private RustClient(long handle) {
    this.handle = new AtomicLong(handle);
    AtomicInteger n = new AtomicInteger();
    this.callbackExecutor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "gcs-rust-callback-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
  }

  /** {@code endpoint} may be null for the default endpoint. */
  static RustClient create(@Nullable String endpoint) {
    RustNative.ensureLoaded();
    try {
      int subchannels = Integer.getInteger(RustNative.SUBCHANNELS_PROPERTY, 0);
      return new RustClient(RustNative.clientNew(endpoint, subchannels));
    } catch (RustNativeException e) {
      throw RustErrors.toStorageException(e);
    }
  }

  /** The handle to pass to {@link RustNative} calls. */
  long handle() {
    long h = handle.get();
    if (h == 0) {
      throw new IllegalStateException("RustClient is closed");
    }
    return h;
  }

  Executor callbackExecutor() {
    return callbackExecutor;
  }

  @Override
  public void close() {
    long h = handle.getAndSet(0);
    if (h != 0) {
      RustNative.clientClose(h);
      callbackExecutor.shutdown();
    }
  }
}
