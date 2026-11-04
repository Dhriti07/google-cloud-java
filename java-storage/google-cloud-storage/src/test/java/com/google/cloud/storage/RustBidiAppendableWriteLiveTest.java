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

import com.google.cloud.storage.BlobAppendableUpload.AppendableUploadWriteableByteChannel;
import com.google.cloud.storage.BlobAppendableUploadConfig.CloseAction;
import com.google.cloud.storage.Storage.BlobWriteOption;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Bidi Appendable Write through the Rust engine against a real zonal bucket. Skipped unless both
 * are set: {@code -Dgcs.rust.lib=<path to libgcs_rust_ffi.so>} and {@code
 * -Dgcs.rust.test.zonal.bucket=<zonal bucket>}. Objects are read back through Rust Bidi Read and
 * deleted through the normal Java path.
 */
public final class RustBidiAppendableWriteLiveTest {

  private static final String BUCKET_PROPERTY = "gcs.rust.test.zonal.bucket";

  private Storage storage;
  private String bucket;
  private final List<BlobId> created = new ArrayList<>();

  @Before
  public void setUp() {
    bucket = System.getProperty(BUCKET_PROPERTY);
    assumeTrue("set -D" + RustNative.LIBRARY_PROPERTY, RustNative.isConfigured());
    assumeTrue("set -D" + BUCKET_PROPERTY, bucket != null && !bucket.isEmpty());
    storage = StorageOptions.grpc().setAttemptDirectPath(false).build().getService();
  }

  @After
  public void tearDown() throws Exception {
    if (storage != null) {
      for (BlobId id : created) {
        storage.delete(id);
      }
      storage.close();
    }
  }

  @Test
  public void finalizedUpload_readsBack() throws Exception {
    byte[] data = new byte[3 * 1024 * 1024 + 17];
    new Random(7).nextBytes(data);
    BlobInfo info =
        BlobInfo.newBuilder(bucket, "rust-append-" + UUID.randomUUID())
            .setContentType("application/x-rust-poc")
            .build();

    BlobAppendableUpload upload =
        storage.blobAppendableUpload(
            info,
            BlobAppendableUploadConfig.of().withCloseAction(CloseAction.FINALIZE_WHEN_CLOSING),
            BlobWriteOption.doesNotExist());
    assertThat(upload).isInstanceOf(RustBlobAppendableUpload.class);
    try (AppendableUploadWriteableByteChannel ch = upload.open()) {
      ch.write(ByteBuffer.wrap(data, 0, 1024 * 1024)); // heap buffer
      ch.flush();
      ByteBuffer direct = ByteBuffer.allocateDirect(data.length - 1024 * 1024);
      direct.put(data, 1024 * 1024, data.length - 1024 * 1024).flip();
      while (direct.hasRemaining()) {
        ch.write(direct); // direct buffer
      }
    }
    BlobInfo done = upload.getResult().get(30, TimeUnit.SECONDS);
    created.add(done.getBlobId());

    assertThat(done.getSize()).isEqualTo(data.length);
    assertThat(done.getContentType()).isEqualTo("application/x-rust-poc");
    try (BlobReadSession session =
        storage.blobReadSession(done.getBlobId()).get(30, TimeUnit.SECONDS)) {
      byte[] read = session.readAs(ReadProjectionConfigs.asFutureBytes()).get(30, TimeUnit.SECONDS);
      assertThat(Arrays.equals(read, data)).isTrue();
    }
  }

  @Test
  public void doesNotExist_onExistingObject_is412() throws Exception {
    BlobInfo info = BlobInfo.newBuilder(bucket, "rust-append-" + UUID.randomUUID()).build();
    BlobAppendableUploadConfig finalize =
        BlobAppendableUploadConfig.of().withCloseAction(CloseAction.FINALIZE_WHEN_CLOSING);
    BlobAppendableUpload first =
        storage.blobAppendableUpload(info, finalize, BlobWriteOption.doesNotExist());
    try (AppendableUploadWriteableByteChannel ch = first.open()) {
      ch.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
    }
    created.add(first.getResult().get(30, TimeUnit.SECONDS).getBlobId());

    BlobAppendableUpload second =
        storage.blobAppendableUpload(info, finalize, BlobWriteOption.doesNotExist());
    StorageException e = assertThrows(StorageException.class, second::open);

    assertThat(e.getCode()).isEqualTo(412);
  }

  @Test
  public void closeWithoutFinalizing_reportsPersistedSize() throws Exception {
    BlobInfo info = BlobInfo.newBuilder(bucket, "rust-append-" + UUID.randomUUID()).build();
    BlobAppendableUpload upload =
        storage.blobAppendableUpload(info, BlobAppendableUploadConfig.of());
    try (AppendableUploadWriteableByteChannel ch = upload.open()) {
      ch.write(ByteBuffer.wrap(new byte[1000]));
    }
    BlobInfo result = upload.getResult().get(30, TimeUnit.SECONDS);
    created.add(result.getBlobId());

    assertThat(result.getGeneration()).isGreaterThan(0L);
    assertThat(result.getSize()).isEqualTo(1000L);
  }
}
