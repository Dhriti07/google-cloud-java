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

/**
 * Thrown by the Rust connector. Carries the raw status so Java can build a {@link StorageException}
 * the same way the existing transports do (see {@link RustErrors}).
 */
final class RustNativeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** gRPC status code (0-16), or -1 if the service didn't return a gRPC status. */
  private final int grpcCode;

  /** HTTP status (JSON API), or -1 if the service didn't return an HTTP error. */
  private final int httpStatus;

  // Called from Rust (jni_client.rs). Keep the signature in sync.
  RustNativeException(int grpcCode, int httpStatus, String message) {
    super(message);
    this.grpcCode = grpcCode;
    this.httpStatus = httpStatus;
  }

  int getGrpcCode() {
    return grpcCode;
  }

  int getHttpStatus() {
    return httpStatus;
  }
}
