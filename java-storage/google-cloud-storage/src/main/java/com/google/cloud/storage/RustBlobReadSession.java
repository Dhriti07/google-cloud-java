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
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.storage.v2.BidiReadObjectSpec;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@link BlobReadSession} backed by the Rust SDK's {@code open_object} (gRPC BidiReadObject).
 *
 * <p>Supports {@link ReadProjectionConfigs#asFutureBytes()}; other projections come in later
 * commits. Each read starts its own range on the shared bidi stream and completes from Rust, so no
 * Java thread waits while the data arrives.
 */
final class RustBlobReadSession implements BlobReadSession {

  private static final long UNSET = Long.MIN_VALUE;

  private final RustClient client;
  private final long session;
  private final BlobInfo info;
  private final AtomicBoolean open = new AtomicBoolean(true);

  private RustBlobReadSession(RustClient client, long session, BlobInfo info) {
    this.client = client;
    this.session = session;
    this.info = info;
  }

  /** Opens a session for {@code spec}, the same spec the gRPC transport would send. */
  static ApiFuture<BlobReadSession> open(RustClient client, BidiReadObjectSpec spec) {
    SettableApiFuture<BlobReadSession> f = SettableApiFuture.create();
    try {
      RustNative.readSessionOpen(
          client.handle(),
          spec.getBucket(),
          spec.getObject(),
          preconditions(spec),
          new RustCompletion(client.callbackExecutor()) {
            @Override
            void onSuccess(long handle, byte @Nullable [] metadata) {
              try {
                f.set(new RustBlobReadSession(client, handle, decode(metadata)));
              } catch (RuntimeException e) {
                RustNative.readSessionClose(handle);
                f.setException(e);
              }
            }

            @Override
            void onFailure(StorageException e) {
              f.setException(e);
            }
          });
    } catch (RustNativeException e) {
      f.setException(RustErrors.toStorageException(e));
    }
    return f;
  }

  @Override
  public BlobInfo getBlobInfo() {
    return info;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <Projection> Projection readAs(ReadProjectionConfig<Projection> config) {
    if (!open.get()) {
      throw new IllegalStateException("BlobReadSession is closed");
    }
    if (config instanceof ReadAsFutureBytes) {
      return (Projection) readBytes(((ReadAsFutureBytes) config).getRange());
    }
    throw new UnsupportedOperationException(
        config.getClass().getSimpleName() + " is not supported by the Rust engine yet");
  }

  @Override
  public void close() {
    if (open.compareAndSet(true, false)) {
      RustNative.readSessionClose(session);
    }
  }

  private ApiFuture<byte[]> readBytes(RangeSpec range) {
    SettableApiFuture<byte[]> f = SettableApiFuture.create();
    int length = rangeLength(info.getSize() == null ? 0 : info.getSize(), range);
    if (length == 0) {
      // The API reads to the end when asked for 0 bytes, so don't ask.
      f.set(new byte[0]);
      return f;
    }
    byte[] dst = new byte[length];
    try {
      RustNative.rangeRead(
          client.handle(),
          session,
          range.begin(),
          length,
          dst,
          new RustCompletion(client.callbackExecutor()) {
            @Override
            void onSuccess(long read, byte @Nullable [] ignored) {
              f.set(read == length ? dst : Arrays.copyOf(dst, (int) read));
            }

            @Override
            void onFailure(StorageException e) {
              f.setException(e);
            }
          });
    } catch (RustNativeException e) {
      f.setException(RustErrors.toStorageException(e));
    }
    return f;
  }

  /** Bytes in {@code range}, clamped to the object size. */
  static int rangeLength(long objectSize, RangeSpec range) {
    long begin = Math.min(range.begin(), objectSize);
    long length = objectSize - begin;
    if (range.maxLength().isPresent()) {
      length = Math.min(length, range.maxLength().getAsLong());
    }
    if (length > Integer.MAX_VALUE - 8) {
      throw new IllegalArgumentException("range too large for a byte[]: " + length + " bytes");
    }
    return (int) length;
  }

  private static long[] preconditions(BidiReadObjectSpec spec) {
    return new long[] {
      spec.getGeneration() > 0 ? spec.getGeneration() : UNSET,
      spec.hasIfGenerationMatch() ? spec.getIfGenerationMatch() : UNSET,
      spec.hasIfGenerationNotMatch() ? spec.getIfGenerationNotMatch() : UNSET,
      spec.hasIfMetagenerationMatch() ? spec.getIfMetagenerationMatch() : UNSET,
      spec.hasIfMetagenerationNotMatch() ? spec.getIfMetagenerationNotMatch() : UNSET,
    };
  }

  private static BlobInfo decode(byte @Nullable [] metadata) {
    if (metadata == null) {
      throw new IllegalStateException("Rust returned no object metadata");
    }
    try {
      return Conversions.grpc().blobInfo().decode(com.google.storage.v2.Object.parseFrom(metadata));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("invalid object metadata from Rust", e);
    }
  }
}
