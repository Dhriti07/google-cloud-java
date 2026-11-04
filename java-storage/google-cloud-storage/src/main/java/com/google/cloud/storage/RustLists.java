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

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.storage.v2.ListObjectsRequest;
import com.google.storage.v2.ListObjectsResponse;
import java.util.concurrent.atomic.AtomicLong;

/**
 * List calls through the Rust SDK ({@code rust-ffi/src/lists.rs}). Requests and responses cross as
 * protobuf; the Rust SDK does its own retries, so callers don't wrap these in Java's retrier.
 */
final class RustLists {

  /** Number of list pages fetched through Rust. Lets tests prove the Rust path was taken. */
  static final AtomicLong PAGES_FETCHED = new AtomicLong();

  private RustLists() {}

  /** Fetches one page. Blocks the calling thread, like the Java {@code list} call. */
  static ListObjectsResponse listObjects(RustClient client, ListObjectsRequest req) {
    try {
      byte[] resp = RustNative.listObjects(client.handle(), req.toByteArray());
      PAGES_FETCHED.incrementAndGet();
      return ListObjectsResponse.parseFrom(resp);
    } catch (RustNativeException e) {
      throw RustErrors.toStorageException(e);
    } catch (InvalidProtocolBufferException e) {
      throw new StorageException(0, "invalid ListObjectsResponse from Rust", e);
    }
  }
}
