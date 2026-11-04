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

import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Storage.BlobSourceOption;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Read (JSON) through the Rust engine against a real bucket. Skipped unless both are set: {@code
 * -Dgcs.rust.lib=<path to libgcs_rust_ffi.so>} and {@code -Dgcs.rust.test.bucket=<bucket>}. The
 * test object is written and deleted through the normal Java path.
 */
public final class RustJsonReadLiveTest {

  private static final String BUCKET_PROPERTY = "gcs.rust.test.bucket";

  private Storage storage;
  private BlobId id;
  private byte[] data;

  @Before
  public void setUp() {
    String bucket = System.getProperty(BUCKET_PROPERTY);
    assumeTrue("set -D" + RustNative.LIBRARY_PROPERTY, RustNative.isConfigured());
    assumeTrue("set -D" + BUCKET_PROPERTY, bucket != null && !bucket.isEmpty());
    storage = StorageOptions.http().build().getService();
    data = new byte[3 * 1024 * 1024 + 17];
    new Random(11).nextBytes(data);
    BlobInfo info = BlobInfo.newBuilder(bucket, "rust-json-read-" + UUID.randomUUID()).build();
    id = storage.create(info, data).getBlobId();
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
  public void readsWholeObject() throws Exception {
    try (ReadChannel r = storage.reader(id)) {
      assertThat(r).isInstanceOf(RustReadChannel.class);
      assertThat(Arrays.equals(readAll(r, ByteBuffer.allocate(64 * 1024)), data)).isTrue();
      BlobInfo info = ((RustReadChannel) r).getObject().get(5, TimeUnit.SECONDS);
      assertThat(info.getGeneration()).isEqualTo(id.getGeneration());
    }
  }

  @Test
  public void directBuffer() throws Exception {
    try (ReadChannel r = storage.reader(id)) {
      assertThat(Arrays.equals(readAll(r, ByteBuffer.allocateDirect(1024 * 1024)), data)).isTrue();
    }
  }

  @Test
  public void fillsOneBufferAcrossReads() throws Exception {
    // Each read lands at the buffer's current position, so later reads use a non-zero offset.
    ByteBuffer buf = ByteBuffer.allocate(data.length);
    try (ReadChannel r = storage.reader(id)) {
      while (buf.hasRemaining() && r.read(buf) >= 0) {}
    }
    assertThat(buf.position()).isEqualTo(data.length);
    assertThat(Arrays.equals(buf.array(), data)).isTrue();
  }

  @Test
  public void seekAndLimit() throws Exception {
    try (ReadChannel r = storage.reader(id)) {
      r.seek(1000);
      r.limit(6000);
      assertThat(
              Arrays.equals(
                  readAll(r, ByteBuffer.allocate(4096)), Arrays.copyOfRange(data, 1000, 6000)))
          .isTrue();
    }
  }

  @Test
  public void seekAfterPartialRead() throws Exception {
    try (ReadChannel r = storage.reader(id)) {
      ByteBuffer first = ByteBuffer.allocate(100);
      r.read(first);
      r.seek(data.length - 10);
      assertThat(
              Arrays.equals(
                  readAll(r, ByteBuffer.allocate(4096)),
                  Arrays.copyOfRange(data, data.length - 10, data.length)))
          .isTrue();
    }
  }

  @Test
  public void failedPrecondition_isIOExceptionWith412() {
    IOException e =
        assertThrows(
            IOException.class,
            () -> {
              try (ReadChannel r =
                  storage.reader(id, BlobSourceOption.generationMatch(id.getGeneration() + 1))) {
                r.read(ByteBuffer.allocate(10));
              }
            });

    assertThat(((StorageException) e.getCause()).getCode()).isEqualTo(412);
  }

  @Test
  public void missingObject_isIOExceptionWith404() {
    IOException e =
        assertThrows(
            IOException.class,
            () -> {
              try (ReadChannel r = storage.reader(id.getBucket(), id.getName() + "-missing")) {
                r.read(ByteBuffer.allocate(10));
              }
            });

    assertThat(((StorageException) e.getCause()).getCode()).isEqualTo(404);
  }

  private static byte[] readAll(ReadChannel r, ByteBuffer buf) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (r.read(buf) >= 0) {
      buf.flip();
      byte[] chunk = new byte[buf.remaining()];
      buf.get(chunk);
      out.write(chunk);
      buf.clear();
    }
    return out.toByteArray();
  }
}
