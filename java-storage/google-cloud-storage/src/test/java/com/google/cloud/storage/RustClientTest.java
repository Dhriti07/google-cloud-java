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

import org.junit.Before;
import org.junit.Test;

/** Needs the Rust connector: run with {@code -Dgcs.rust.lib=<path to libgcs_rust_ffi.so>}. */
public final class RustClientTest {

  @Before
  public void requireLibrary() {
    assumeTrue(
        "set -D" + RustNative.LIBRARY_PROPERTY,
        System.getProperty(RustNative.LIBRARY_PROPERTY) != null);
  }

  @Test
  public void createAndClose() {
    RustClient client = RustClient.create(null);

    assertThat(client.handle()).isNotEqualTo(0);
    client.close();
  }

  @Test
  public void closeTwice_isSafe() {
    RustClient client = RustClient.create(null);

    client.close();
    client.close();
  }

  @Test
  public void handleAfterClose_throws() {
    RustClient client = RustClient.create(null);
    client.close();

    assertThrows(IllegalStateException.class, client::handle);
  }

  @Test
  public void setupFailure_isStorageException() {
    StorageException e =
        assertThrows(StorageException.class, () -> RustClient.create("not a valid endpoint"));

    assertThat(e.getCode()).isEqualTo(0);
    assertThat(e.getMessage()).contains("failed to create");
  }
}
