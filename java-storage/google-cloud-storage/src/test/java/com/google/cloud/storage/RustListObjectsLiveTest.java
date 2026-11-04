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

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Storage.BlobField;
import com.google.cloud.storage.Storage.BlobListOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * List Objects through the Rust engine against a real bucket. Skipped unless both are set: {@code
 * -Dgcs.rust.lib=<path to libgcs_rust_ffi.so>} and {@code -Dgcs.rust.test.bucket=<bucket>}. Test
 * objects are created and deleted through the normal Java path.
 */
public final class RustListObjectsLiveTest {

  private static final String BUCKET_PROPERTY = "gcs.rust.test.bucket";

  private Storage storage;
  private String bucket;
  private String prefix;
  private final List<BlobId> created = new ArrayList<>();

  @Before
  public void setUp() {
    bucket = System.getProperty(BUCKET_PROPERTY);
    assumeTrue("set -D" + RustNative.LIBRARY_PROPERTY, RustNative.isConfigured());
    assumeTrue("set -D" + BUCKET_PROPERTY, bucket != null && !bucket.isEmpty());
    storage = StorageOptions.grpc().setAttemptDirectPath(false).build().getService();
    prefix = "rust-list-" + UUID.randomUUID() + "/";
    for (String name : new String[] {"a", "b", "c", "d", "e", "sub/x"}) {
      BlobInfo info = BlobInfo.newBuilder(bucket, prefix + name).build();
      created.add(storage.create(info, new byte[] {1, 2, 3}).getBlobId());
    }
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
  public void pagesThroughAllObjects() {
    long before = RustLists.PAGES_FETCHED.get();

    List<String> names = new ArrayList<>();
    for (Blob b :
        storage
            .list(bucket, BlobListOption.prefix(prefix), BlobListOption.pageSize(2))
            .iterateAll()) {
      names.add(b.getName().substring(prefix.length()));
      assertThat(b.getSize()).isEqualTo(3L);
    }

    assertThat(names).containsExactly("a", "b", "c", "d", "e", "sub/x").inOrder();
    // 6 objects, 2 per page: 3 pages, all fetched through Rust.
    assertThat(RustLists.PAGES_FETCHED.get() - before).isEqualTo(3);
  }

  @Test
  public void currentDirectory_returnsPrefixesAsDirectories() {
    Page<Blob> page =
        storage.list(bucket, BlobListOption.prefix(prefix), BlobListOption.currentDirectory());

    List<String> dirs = new ArrayList<>();
    int files = 0;
    for (Blob b : page.getValues()) {
      if (b.isDirectory()) {
        dirs.add(b.getName());
      } else {
        files++;
      }
    }
    assertThat(files).isEqualTo(5);
    assertThat(dirs).containsExactly(prefix + "sub/");
  }

  @Test
  public void fieldsOption_limitsReturnedFields() {
    Page<Blob> page =
        storage.list(bucket, BlobListOption.prefix(prefix), BlobListOption.fields(BlobField.NAME));

    Blob first = page.getValues().iterator().next();
    assertThat(first.getName()).isEqualTo(prefix + "a");
    // Fields left out by the read mask decode as 0, the same as on the Java gRPC path (checked).
    assertThat(first.getGeneration()).isEqualTo(0L);
    assertThat(first.getSize()).isEqualTo(0L);
  }

  @Test
  public void missingBucket_is404() {
    StorageException e =
        assertThrows(
            StorageException.class,
            () -> storage.list(bucket + "-missing-" + UUID.randomUUID()).getValues());

    assertThat(e.getCode()).isEqualTo(404);
  }
}
