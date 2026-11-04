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

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.After;
import org.junit.Test;

public final class RustCompletionTest {

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "callback-test"));

  @After
  public void tearDown() {
    executor.shutdownNow();
  }

  @Test
  public void succeeded_runsOnExecutorWithValues() throws Exception {
    CompletableFuture<String> got = new CompletableFuture<>();
    RustCompletion c =
        new RustCompletion(executor) {
          @Override
          void onSuccess(long value, byte @Nullable [] bytes) {
            got.complete(Thread.currentThread().getName() + ":" + value + ":" + bytes.length);
          }

          @Override
          void onFailure(StorageException e) {
            got.completeExceptionally(e);
          }
        };

    c.succeeded(42, new byte[3]);

    assertThat(got.get(5, TimeUnit.SECONDS)).isEqualTo("callback-test:42:3");
  }

  @Test
  public void failed_mapsToStorageException() throws Exception {
    CompletableFuture<StorageException> got = new CompletableFuture<>();
    RustCompletion c =
        new RustCompletion(executor) {
          @Override
          void onSuccess(long value, byte @Nullable [] bytes) {}

          @Override
          void onFailure(StorageException e) {
            got.complete(e);
          }
        };

    c.failed(9, -1, "generation mismatch");

    StorageException e = got.get(5, TimeUnit.SECONDS);
    assertThat(e.getCode()).isEqualTo(412);
    assertThat(e.getMessage()).contains("generation mismatch");
  }

  @Test
  public void executorShutDown_stillCompletes() {
    executor.shutdown();
    CompletableFuture<Long> got = new CompletableFuture<>();
    RustCompletion c =
        new RustCompletion(executor) {
          @Override
          void onSuccess(long value, byte @Nullable [] bytes) {
            got.complete(value);
          }

          @Override
          void onFailure(StorageException e) {}
        };

    c.succeeded(7, null);

    assertThat(got.getNow(-1L)).isEqualTo(7L);
  }
}
