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
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.google.cloud.storage.Storage.BlobSourceOption;
import com.google.cloud.storage.Storage.BlobTargetOption;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Bidi Read through the Rust engine against real GCS. Skipped unless both are set: {@code
 * -Dgcs.rust.lib=<path to libgcs_rust_ffi.so>} and {@code -Dgcs.rust.test.bucket=<bucket>}. The
 * test object is written and deleted through the normal Java path.
 */
public final class RustBidiReadLiveTest {

  private static final String BUCKET_PROPERTY = "gcs.rust.test.bucket";

  private Storage storage;
  private BlobId id;
  private byte[] data;

  @Before
  public void setUp() {
    String bucket = System.getProperty(BUCKET_PROPERTY);
    assumeTrue("set -D" + RustNative.LIBRARY_PROPERTY, RustNative.isConfigured());
    assumeTrue("set -D" + BUCKET_PROPERTY, bucket != null && !bucket.isEmpty());
    storage = StorageOptions.grpc().setAttemptDirectPath(false).build().getService();
    data = new byte[3 * 1024 * 1024 + 17];
    new Random(42).nextBytes(data);
    BlobInfo info = BlobInfo.newBuilder(bucket, "rust-bidi-read-" + UUID.randomUUID()).build();
    id = storage.create(info, data, BlobTargetOption.doesNotExist()).getBlobId();
  }

  @After
  public void tearDown() throws Exception {
    if (storage != null) {
      if (id != null) {
        storage.delete(id);
      }
      storage.close();
    }
  }

  @Test
  public void readsThroughRust() throws Exception {
    try (BlobReadSession session = storage.blobReadSession(id).get(30, TimeUnit.SECONDS)) {
      assertThat(session).isInstanceOf(RustBlobReadSession.class);
      assertThat(session.getBlobInfo().getBlobId()).isEqualTo(id);
      assertThat(session.getBlobInfo().getSize()).isEqualTo(data.length);

      byte[] all = session.readAs(ReadProjectionConfigs.asFutureBytes()).get(30, TimeUnit.SECONDS);
      assertThat(Arrays.equals(all, data)).isTrue();

      byte[] range =
          session
              .readAs(ReadProjectionConfigs.asFutureBytes().withRangeSpec(RangeSpec.of(1000, 5000)))
              .get(30, TimeUnit.SECONDS);
      assertThat(Arrays.equals(range, Arrays.copyOfRange(data, 1000, 6000))).isTrue();

      byte[] tail =
          session
              .readAs(
                  ReadProjectionConfigs.asFutureBytes()
                      .withRangeSpec(RangeSpec.beginAt(data.length - 10)))
              .get(30, TimeUnit.SECONDS);
      assertThat(Arrays.equals(tail, Arrays.copyOfRange(data, data.length - 10, data.length)))
          .isTrue();
    }
  }

  @Test
  public void failedPrecondition_is412() {
    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () ->
                storage
                    .blobReadSession(id, BlobSourceOption.generationMatch(id.getGeneration() + 1))
                    .get(30, TimeUnit.SECONDS));

    assertThat(e.getCause()).isInstanceOf(StorageException.class);
    assertThat(((StorageException) e.getCause()).getCode()).isEqualTo(412);
  }

  @Test
  public void missingObject_is404() {
    BlobId missing = BlobId.of(id.getBucket(), id.getName() + "-missing");
    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () -> storage.blobReadSession(missing).get(30, TimeUnit.SECONDS));

    assertThat(((StorageException) e.getCause()).getCode()).isEqualTo(404);
  }
}
