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

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.ApiExceptionFactory;
import io.grpc.Status;

/** Turns errors from the Rust connector into the {@link StorageException}s users see today. */
final class RustErrors {

  private RustErrors() {}

  /**
   * JSON API errors (HTTP status) map like the HTTP transport. gRPC errors go through the same
   * {@link StorageException#asStorageException(ApiException)} the gRPC transport uses. Errors with
   * neither (I/O, auth, client setup) get code 0.
   */
  static StorageException toStorageException(RustNativeException e) {
    if (e.getHttpStatus() > 0) {
      return new StorageException(e.getHttpStatus(), e.getMessage(), e);
    }
    if (e.getGrpcCode() >= 0) {
      Status status = Status.fromCodeValue(e.getGrpcCode()).withDescription(e.getMessage());
      ApiException apiEx =
          ApiExceptionFactory.createException(
              status.asRuntimeException(), GrpcStatusCode.of(status.getCode()), false);
      StorageException se = StorageException.asStorageException(apiEx);
      se.addSuppressed(e);
      return se;
    }
    return new StorageException(0, e.getMessage(), e);
  }
}
