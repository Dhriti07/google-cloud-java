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
import com.google.cloud.storage.BlobAppendableUploadConfig.CloseAction;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.storage.v2.WriteObjectSpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@link BlobAppendableUpload} backed by the Rust SDK's {@code open_appendable_object} (gRPC
 * BidiWriteObject). Zonal buckets only.
 *
 * <p>The channel is blocking, like the Java one: each call waits for Rust on the calling thread.
 * Each {@code write} copies the data once into Rust, because the SDK keeps it until the service
 * confirms it and the caller may reuse the buffer as soon as {@code write} returns. The Rust SDK
 * decides when to send; the config's {@link FlushPolicy} is not applied.
 */
final class RustBlobAppendableUpload implements BlobAppendableUpload {

  private final RustClient client;
  private final BlobInfo info;
  private final byte[] spec;
  private final CloseAction closeAction;
  private final SettableApiFuture<BlobInfo> result = SettableApiFuture.create();
  private @Nullable Channel channel;

  private RustBlobAppendableUpload(
      RustClient client, BlobInfo info, byte[] spec, CloseAction closeAction) {
    this.client = client;
    this.info = info;
    this.spec = spec;
    this.closeAction = closeAction;
  }

  /** {@code spec} is the same WriteObjectSpec the gRPC transport would send. */
  static RustBlobAppendableUpload create(
      RustClient client, BlobInfo info, BlobAppendableUploadConfig config, WriteObjectSpec spec) {
    if (info.getGeneration() != null) {
      throw new UnsupportedOperationException(
          "taking over an existing appendable object is not supported by the Rust engine");
    }
    return new RustBlobAppendableUpload(client, info, spec.toByteArray(), config.getCloseAction());
  }

  @Override
  public synchronized AppendableUploadWriteableByteChannel open() throws IOException {
    if (channel != null) {
      throw new IllegalStateException("open() was already called");
    }
    try {
      channel = new Channel(RustNative.appendableOpen(client.handle(), spec));
    } catch (RustNativeException e) {
      throw fail(e);
    }
    return channel;
  }

  @Override
  public ApiFuture<BlobInfo> getResult() {
    return result;
  }

  private StorageException fail(RustNativeException e) {
    StorageException se = RustErrors.toStorageException(e);
    result.setException(se);
    return se;
  }

  private final class Channel implements AppendableUploadWriteableByteChannel {

    private long appender;

    private Channel(long appender) {
      this.appender = appender;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      long a = live();
      int n = src.remaining();
      if (n == 0) {
        return 0;
      }
      try {
        if (src.isDirect()) {
          RustNative.appendableAppendDirect(client.handle(), a, src, src.position(), n);
        } else if (src.hasArray()) {
          RustNative.appendableAppendArray(
              client.handle(), a, src.array(), src.arrayOffset() + src.position(), n);
        } else {
          byte[] tmp = new byte[n];
          src.duplicate().get(tmp);
          RustNative.appendableAppendArray(client.handle(), a, tmp, 0, n);
        }
      } catch (RustNativeException e) {
        throw fail(e);
      }
      src.position(src.position() + n);
      return n;
    }

    @Override
    public void flush() throws IOException {
      try {
        RustNative.appendableFlush(client.handle(), live());
      } catch (RustNativeException e) {
        throw fail(e);
      }
    }

    @Override
    public void finalizeAndClose() throws IOException {
      finalizeAndClose(null);
    }

    @Override
    public void finalizeAndClose(@Nullable String expectedCrc32c) throws IOException {
      if (appender == 0) {
        return;
      }
      try {
        BlobInfo done = decode(RustNative.appendableFinalize(client.handle(), appender));
        if (expectedCrc32c != null && !expectedCrc32c.equals(done.getCrc32c())) {
          StorageException e =
              new StorageException(
                  0, "CRC32C mismatch: expected " + expectedCrc32c + ", got " + done.getCrc32c());
          result.setException(e);
          throw e;
        }
        result.set(done);
      } catch (RustNativeException e) {
        throw fail(e);
      } finally {
        release();
      }
    }

    @Override
    public void closeWithoutFinalizing() throws IOException {
      if (appender == 0) {
        return;
      }
      try {
        long generation = RustNative.appendableGeneration(appender);
        long persisted = RustNative.appendableClose(client.handle(), appender);
        result.set(
            info.toBuilder()
                .setBlobId(BlobId.of(info.getBucket(), info.getName(), generation))
                .setSize(persisted)
                .build());
      } catch (RustNativeException e) {
        throw fail(e);
      } finally {
        release();
      }
    }

    @Override
    public void close() throws IOException {
      close(null);
    }

    @Override
    public void close(@Nullable String expectedCrc32c) throws IOException {
      if (closeAction == CloseAction.FINALIZE_WHEN_CLOSING) {
        finalizeAndClose(expectedCrc32c);
      } else {
        closeWithoutFinalizing();
      }
    }

    @Override
    public boolean isOpen() {
      return appender != 0;
    }

    private long live() throws ClosedChannelException {
      if (appender == 0) {
        throw new ClosedChannelException();
      }
      return appender;
    }

    private void release() {
      RustNative.appendableFree(appender);
      appender = 0;
    }
  }

  private static BlobInfo decode(byte[] object) {
    try {
      return Conversions.grpc().blobInfo().decode(com.google.storage.v2.Object.parseFrom(object));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("invalid object metadata from Rust", e);
    }
  }
}
