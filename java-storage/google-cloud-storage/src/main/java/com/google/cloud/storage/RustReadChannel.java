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
import com.google.cloud.ReadChannel;
import com.google.cloud.RestorableState;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.storage.v2.ReadObjectRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

/**
 * {@link ReadChannel} for {@code Storage.reader(...)} on the JSON client, backed by the Rust SDK's
 * {@code read_object}.
 *
 * <p>Like the Java channel it opens the request lazily on the first {@code read}; {@code seek} and
 * {@code limit} change the range and reopen. After the first open the generation is pinned, so a
 * reopen reads the same object version. Each {@code read} copies the next bytes once from Rust into
 * the caller's buffer. Errors surface as {@link IOException} with a {@link StorageException} cause,
 * as on the Java path.
 */
final class RustReadChannel implements StorageReadChannel {

  private final RustClient client;
  private final ReadObjectRequest base;
  private final SettableApiFuture<BlobInfo> object = SettableApiFuture.create();
  private ByteRangeSpec range = ByteRangeSpec.nullRange();
  private long consumed;
  private long generation;
  private long reader;
  private boolean open = true;

  /** {@code base} is the request java-storage's option mapping builds, without a range. */
  RustReadChannel(RustClient client, ReadObjectRequest base) {
    this.client = client;
    this.base = base;
    this.generation = base.getGeneration();
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    if (!open) {
      throw new ClosedChannelException();
    }
    if (!dst.hasRemaining()) {
      return 0;
    }
    long max = dst.remaining();
    if (range.endOffset() != ByteRangeSpec.EFFECTIVE_INFINITY) {
      long left = range.endOffset() - range.beginOffset() - consumed;
      if (left <= 0) {
        return -1;
      }
      max = Math.min(max, left);
    }
    try {
      if (reader == 0) {
        reader = openReader();
      }
      int n;
      if (dst.isDirect()) {
        n =
            RustNative.jsonReaderReadDirect(
                client.handle(), reader, dst, dst.position(), (int) max);
      } else if (dst.hasArray()) {
        n =
            RustNative.jsonReaderReadArray(
                client.handle(),
                reader,
                dst.array(),
                dst.arrayOffset() + dst.position(),
                (int) max);
      } else {
        byte[] tmp = new byte[(int) max];
        n = RustNative.jsonReaderReadArray(client.handle(), reader, tmp, 0, tmp.length);
        if (n > 0) {
          dst.duplicate().put(tmp, 0, n);
        }
      }
      if (n > 0) {
        dst.position(dst.position() + n);
        consumed += n;
      }
      return n;
    } catch (RustNativeException e) {
      StorageException se = RustErrors.toStorageException(e);
      object.setException(se);
      throw new IOException(se);
    }
  }

  private long openReader() {
    ReadObjectRequest.Builder b =
        range.withShiftBeginOffset(consumed).seekReadObjectRequest(base.toBuilder());
    if (generation > 0) {
      b.setGeneration(generation);
    }
    byte[][] metadata = new byte[1][];
    long handle = RustNative.jsonReaderOpen(client.handle(), b.build().toByteArray(), metadata);
    try {
      BlobInfo info =
          Conversions.grpc().blobInfo().decode(com.google.storage.v2.Object.parseFrom(metadata[0]));
      generation = info.getGeneration() == null ? 0 : info.getGeneration();
      object.set(info);
    } catch (InvalidProtocolBufferException e) {
      RustNative.jsonReaderFree(handle);
      throw new IllegalStateException("invalid object metadata from Rust", e);
    }
    return handle;
  }

  @Override
  public StorageReadChannel setByteRangeSpec(ByteRangeSpec byteRangeSpec) {
    if (!byteRangeSpec.equals(range)) {
      release();
      range = byteRangeSpec;
      consumed = 0;
    }
    return this;
  }

  @Override
  public ByteRangeSpec getByteRangeSpec() {
    return range;
  }

  @Override
  public ApiFuture<BlobInfo> getObject() {
    return object;
  }

  @Override
  public void setChunkSize(int chunkSize) {
    // The Rust SDK streams the response; there is no client-side chunk size.
  }

  @Override
  public RestorableState<ReadChannel> capture() {
    throw new UnsupportedOperationException("capture() is not supported by the Rust engine");
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    open = false;
    release();
  }

  private void release() {
    if (reader != 0) {
      RustNative.jsonReaderFree(reader);
      reader = 0;
    }
  }
}
