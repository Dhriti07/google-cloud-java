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
import static org.junit.Assume.assumeTrue;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Storage.BucketListOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * List Buckets through the Rust engine against a real project. Skipped unless both are set: {@code
 * -Dgcs.rust.lib=<path to libgcs_rust_ffi.so>} and {@code -Dgcs.rust.test.project=<project where
 * the caller may list buckets>}.
 */
public final class RustListBucketsLiveTest {

  private static final String PROJECT_PROPERTY = "gcs.rust.test.project";

  private String project;
  private Storage storage;

  @Before
  public void setUp() {
    project = System.getProperty(PROJECT_PROPERTY);
    assumeTrue("set -D" + RustNative.LIBRARY_PROPERTY, RustNative.isConfigured());
    assumeTrue("set -D" + PROJECT_PROPERTY, project != null && !project.isEmpty());
    storage = newStorage();
  }

  @After
  public void tearDown() throws Exception {
    if (storage != null) {
      storage.close();
    }
  }

  @Test
  public void matchesJavaPath() throws Exception {
    long before = RustLists.PAGES_FETCHED.get();
    List<String> viaRust = firstPages(storage, 3, BucketListOption.pageSize(2));
    long pages = RustLists.PAGES_FETCHED.get() - before;

    // The same call on the normal Java gRPC path (flag cleared just for this client).
    String lib = System.clearProperty(RustNative.LIBRARY_PROPERTY);
    List<String> viaJava;
    try (Storage java = newStorage()) {
      viaJava = firstPages(java, 3, BucketListOption.pageSize(2));
    } finally {
      System.setProperty(RustNative.LIBRARY_PROPERTY, lib);
    }

    assertThat(viaRust).isNotEmpty();
    assertThat(viaRust).containsExactlyElementsIn(viaJava).inOrder();
    assertThat(pages).isEqualTo((viaRust.size() + 1) / 2);
  }

  @Test
  public void prefix_filtersBuckets() {
    String first =
        storage.list(BucketListOption.pageSize(1)).getValues().iterator().next().getName();

    List<String> names = new ArrayList<>();
    for (Bucket b : storage.list(BucketListOption.prefix(first)).getValues()) {
      names.add(b.getName());
    }

    assertThat(names).contains(first);
    for (String n : names) {
      assertThat(n).startsWith(first);
    }
  }

  private Storage newStorage() {
    return StorageOptions.grpc()
        .setAttemptDirectPath(false)
        .setProjectId(project)
        .build()
        .getService();
  }

  /** Up to {@code maxPages} pages, following next-page tokens. */
  private static List<String> firstPages(Storage s, int maxPages, BucketListOption... options) {
    List<String> out = new ArrayList<>();
    Page<Bucket> page = s.list(options);
    for (int i = 0; i < maxPages && page != null; i++) {
      for (Bucket b : page.getValues()) {
        out.add(b.getName() + " " + b.getLocation() + " " + b.getStorageClass());
      }
      page = page.hasNextPage() && i + 1 < maxPages ? page.getNextPage() : null;
    }
    return out;
  }
}
