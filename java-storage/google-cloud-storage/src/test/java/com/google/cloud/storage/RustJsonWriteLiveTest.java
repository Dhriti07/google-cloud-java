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

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.common.collect.ImmutableMap;
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
 * Write (JSON) through the Rust engine against a real bucket. Skipped unless both are set: {@code
 * -Dgcs.rust.lib=<path to libgcs_rust_ffi.so>} and {@code -Dgcs.rust.test.bucket=<bucket>}. Objects
 * are read back and deleted through the normal Java path.
 */
public final class RustJsonWriteLiveTest {

  private static final String BUCKET_PROPERTY = "gcs.rust.test.bucket";

  private Storage storage;
  private String bucket;
  private final List<BlobId> created = new ArrayList<>();

  @Before
  public void setUp() {
    bucket = System.getProperty(BUCKET_PROPERTY);
    assumeTrue("set -D" + RustNative.LIBRARY_PROPERTY, RustNative.isConfigured());
    assumeTrue("set -D" + BUCKET_PROPERTY, bucket != null && !bucket.isEmpty());
    storage = StorageOptions.http().build().getService();
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

  private BlobInfo newInfo() {
    return BlobInfo.newBuilder(bucket, "rust-json-write-" + UUID.randomUUID())
        .setContentType("application/x-rust-poc")
        .setMetadata(ImmutableMap.of("k", "v"))
        .build();
  }

  @Test
  public void writesAndReadsBack() throws Exception {
    byte[] data = new byte[3 * 1024 * 1024 + 17];
    new Random(5).nextBytes(data);
    BlobInfo info = newInfo();

    WriteChannel w = storage.writer(info, BlobWriteOption.doesNotExist());
    assertThat(w).isInstanceOf(RustWriteChannel.class);
    w.write(ByteBuffer.wrap(data, 0, 1024 * 1024)); // heap
    ByteBuffer direct = ByteBuffer.allocateDirect(data.length - 1024 * 1024);
    direct.put(data, 1024 * 1024, data.length - 1024 * 1024).flip();
    w.write(direct); // direct
    w.close();
    BlobInfo done = ((RustWriteChannel) w).getObject().get(5, TimeUnit.SECONDS);
    created.add(done.getBlobId());

    assertThat(done.getSize()).isEqualTo(data.length);
    assertThat(done.getContentType()).isEqualTo("application/x-rust-poc");
    assertThat(storage.get(done.getBlobId()).getMetadata()).containsEntry("k", "v");
    assertThat(Arrays.equals(storage.readAllBytes(done.getBlobId()), data)).isTrue();
  }

  @Test
  public void smallChunkSize_manyChunks() throws Exception {
    byte[] data = new byte[1024 * 1024 + 3];
    new Random(6).nextBytes(data);
    WriteChannel w = storage.writer(newInfo());
    w.setChunkSize(256 * 1024);
    for (int off = 0; off < data.length; off += 100_000) {
      w.write(ByteBuffer.wrap(data, off, Math.min(100_000, data.length - off)));
    }
    w.close();
    BlobInfo done = ((RustWriteChannel) w).getObject().get(5, TimeUnit.SECONDS);
    created.add(done.getBlobId());

    assertThat(Arrays.equals(storage.readAllBytes(done.getBlobId()), data)).isTrue();
  }

  @Test
  public void emptyObject() throws Exception {
    WriteChannel w = storage.writer(newInfo());
    w.close();
    BlobInfo done = ((RustWriteChannel) w).getObject().get(5, TimeUnit.SECONDS);
    created.add(done.getBlobId());

    assertThat(done.getSize()).isEqualTo(0L);
  }

  @Test
  public void doesNotExist_onExistingObject_is412AtClose() throws Exception {
    BlobInfo info = newInfo();
    created.add(storage.create(info, new byte[] {1}).getBlobId());

    WriteChannel w = storage.writer(info, BlobWriteOption.doesNotExist());
    w.write(ByteBuffer.wrap(new byte[10]));
    StorageException e = assertThrows(StorageException.class, w::close);

    assertThat(e.getCode()).isEqualTo(412);
  }
}
