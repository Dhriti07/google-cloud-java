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

import com.google.api.core.ApiFuture;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.RestorableState;
import com.google.cloud.WriteChannel;
import com.google.common.base.Preconditions;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.storage.v2.WriteObjectSpec;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

/**
 * {@link WriteChannel} for {@code Storage.writer(...)} on the JSON client, backed by the Rust SDK's
 * {@code write_object} (resumable upload).
 *
 * <p>The upload starts on the first {@code write} (or at {@code close} for an empty object), so
 * {@link #setChunkSize} works until then, like the Java channel. The chunk size (16 MiB by default,
 * as in Java) is passed to Rust as its upload buffer size. Each {@code write} copies the data once
 * into Rust. Errors surface as {@link StorageException}, as on the Java path (a failed precondition
 * shows up at {@code close}).
 */
final class RustWriteChannel implements StorageWriteChannel {

  private static final int DEFAULT_CHUNK_SIZE = 16 * 1024 * 1024;

  private final RustClient client;
  private final byte[] spec;
  private final SettableApiFuture<BlobInfo> object = SettableApiFuture.create();
  private int chunkSize = DEFAULT_CHUNK_SIZE;
  private long writer;
  private boolean open = true;

  /** {@code spec} is the WriteObjectSpec built by java-storage's own option mapping. */
  RustWriteChannel(RustClient client, WriteObjectSpec spec) {
    this.client = client;
    this.spec = spec.toByteArray();
  }

  @Override
  public int write(ByteBuffer src) throws ClosedChannelException {
    if (!open) {
      throw new ClosedChannelException();
    }
    int n = src.remaining();
    if (n == 0) {
      return 0;
    }
    try {
      long w = started();
      if (src.isDirect()) {
        RustNative.jsonWriterWriteDirect(client.handle(), w, src, src.position(), n);
      } else if (src.hasArray()) {
        RustNative.jsonWriterWriteArray(
            client.handle(), w, src.array(), src.arrayOffset() + src.position(), n);
      } else {
        byte[] tmp = new byte[n];
        src.duplicate().get(tmp);
        RustNative.jsonWriterWriteArray(client.handle(), w, tmp, 0, n);
      }
    } catch (RustNativeException e) {
      throw fail(e);
    }
    src.position(src.position() + n);
    return n;
  }

  @Override
  public void close() {
    if (!open) {
      return;
    }
    open = false;
    try {
      byte[] result = RustNative.jsonWriterClose(client.handle(), started());
      object.set(
          Conversions.grpc().blobInfo().decode(com.google.storage.v2.Object.parseFrom(result)));
    } catch (RustNativeException e) {
      throw fail(e);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("invalid object metadata from Rust", e);
    } finally {
      release();
    }
  }

  private long started() {
    if (writer == 0) {
      writer = RustNative.jsonWriterOpen(client.handle(), spec, chunkSize);
    }
    return writer;
  }

  private StorageException fail(RustNativeException e) {
    StorageException se = RustErrors.toStorageException(e);
    object.setException(se);
    return se;
  }

  private void release() {
    if (writer != 0) {
      RustNative.jsonWriterFree(writer);
      writer = 0;
    }
  }

  @Override
  public ApiFuture<BlobInfo> getObject() {
    return object;
  }

  @Override
  public void setChunkSize(int chunkSize) {
    Preconditions.checkArgument(chunkSize > 0, "chunkSize must be > 0, received %d", chunkSize);
    Preconditions.checkState(writer == 0, "unable to change chunk size after writing data");
    this.chunkSize = chunkSize;
  }

  @Override
  public RestorableState<WriteChannel> capture() {
    throw new UnsupportedOperationException("capture() is not supported by the Rust engine");
  }

  @Override
  public boolean isOpen() {
    return open;
  }
}
