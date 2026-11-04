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

import org.junit.Test;

public final class RustErrorsTest {

  @Test
  public void grpcCode_mapsLikeGrpcTransport() {
    StorageException e =
        RustErrors.toStorageException(new RustNativeException(5, -1, "no such object"));

    assertThat(e.getCode()).isEqualTo(404);
    assertThat(e.getMessage()).contains("no such object");
  }

  @Test
  public void grpcFailedPrecondition_is412AndNotRetryable() {
    StorageException e =
        RustErrors.toStorageException(new RustNativeException(9, -1, "generation mismatch"));

    assertThat(e.getCode()).isEqualTo(412);
    assertThat(e.isRetryable()).isFalse();
  }

  @Test
  public void grpcUnavailable_is503AndRetryable() {
    StorageException e =
        RustErrors.toStorageException(new RustNativeException(14, -1, "try again"));

    assertThat(e.getCode()).isEqualTo(503);
    assertThat(e.isRetryable()).isTrue();
  }

  @Test
  public void httpStatus_isUsedAsIs() {
    StorageException e =
        RustErrors.toStorageException(new RustNativeException(-1, 429, "slow down"));

    assertThat(e.getCode()).isEqualTo(429);
    assertThat(e.getMessage()).contains("slow down");
  }

  @Test
  public void httpStatus_winsOverGrpcCode() {
    StorageException e = RustErrors.toStorageException(new RustNativeException(5, 410, "gone"));

    assertThat(e.getCode()).isEqualTo(410);
  }

  @Test
  public void noStatus_isCodeZero() {
    StorageException e =
        RustErrors.toStorageException(new RustNativeException(-1, -1, "connection reset"));

    assertThat(e.getCode()).isEqualTo(0);
    assertThat(e.getMessage()).isEqualTo("connection reset");
  }
}
