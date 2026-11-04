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
import java.util.concurrent.RejectedExecutionException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Receives the result of an async Rust call ({@code rust-ffi/src/callback.rs}).
 *
 * <p>Rust calls {@link #succeeded} or {@link #failed} exactly once, on a Rust thread. Both hand the
 * result to {@code executor}, so user code (future listeners) never runs on Rust's runtime.
 */
abstract class RustCompletion {

  private final Executor executor;

  RustCompletion(Executor executor) {
    this.executor = executor;
  }

  /** {@code value} is a handle or a byte count; {@code bytes} may be null. */
  abstract void onSuccess(long value, byte @Nullable [] bytes);

  abstract void onFailure(StorageException e);

  // Called from Rust. Keep the name and signature in sync with callback.rs.
  final void succeeded(long value, byte @Nullable [] bytes) {
    dispatch(() -> onSuccess(value, bytes));
  }

  // Called from Rust. Keep the name and signature in sync with callback.rs.
  final void failed(int grpcCode, int httpStatus, String message) {
    StorageException e =
        RustErrors.toStorageException(new RustNativeException(grpcCode, httpStatus, message));
    dispatch(() -> onFailure(e));
  }

  private void dispatch(Runnable r) {
    try {
      executor.execute(r);
    } catch (RejectedExecutionException e) {
      // The client is closing; still complete the future rather than leave it hanging.
      r.run();
    }
  }
}
