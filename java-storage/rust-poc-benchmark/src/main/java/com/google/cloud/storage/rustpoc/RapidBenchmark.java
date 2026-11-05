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

package com.google.cloud.storage.rustpoc;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.gax.paging.Page;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobAppendableUpload;
import com.google.cloud.storage.BlobAppendableUpload.AppendableUploadWriteableByteChannel;
import com.google.cloud.storage.BlobAppendableUploadConfig;
import com.google.cloud.storage.BlobAppendableUploadConfig.CloseAction;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BlobReadSession;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.RangeSpec;
import com.google.cloud.storage.ReadProjectionConfigs;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.Storage.BucketListOption;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.StorageOptions;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Workloads 1-6 through the normal java-storage API, on Java or on the Rust SDK.
 *
 * <p>The engine is chosen only by the JVM flag: with {@code -Dgcs.rust.lib=<libgcs_rust_ffi.so>}
 * the client runs on the Rust SDK; without it, on the Java path. Everything else (this code, the
 * jar, the JVM) is the same. Both engines use CloudPath. {@code --transport=bidi} uses a gRPC
 * client, {@code --transport=json} an HTTP client.
 *
 * <ul>
 *   <li>{@code open} (1): start a Bidi Read, wait for the first response (metadata), close.
 *   <li>{@code read50x2x4} (2): 50 reads of 2 MiB at a 4 MiB stride from a 200 MiB object, {@code
 *       --mode=concurrent} or {@code serial}.
 *   <li>{@code random8k} (3): random 8 KiB reads from a 100 MiB object.
 *   <li>{@code read} (4): N threads read random {@code --range-size} ranges of an {@code
 *       --object-size} object.
 *   <li>{@code write} (5): N threads each keep overwriting their own {@code --object-size} object
 *       ({@code blobAppendableUpload} for bidi, {@code writer} for json), in {@code --write-chunk}
 *       slices.
 *   <li>{@code list-objects} / {@code list-buckets} (6): one page of {@code --list-items} items.
 * </ul>
 *
 * <p>Workloads 1-3 and 6 run one operation at a time ({@code --threads=1}); 4 and 5 use 48 threads.
 * Latency is measured around the outermost API call(s) of each operation; operations that end in
 * the warm-up are not counted. Established TCP connections to port 443 and NIC Gbps are recorded
 * under {@code params.network}. Results go to {@code --out/<workload>-<transport>-...-<label>/}.
 */
public final class RapidBenchmark {

  private static final int MiB = 1024 * 1024;
  // Upper bound for one read (or one session open). A stalled call becomes a counted error instead
  // of hanging the run.
  private static final long OP_TIMEOUT_SEC = 120;
  // Upper bound for finishing one 200 MB write.
  private static final long WRITE_TIMEOUT_SEC = 600;

  private final Map<String, String> args;
  private final String engine;
  private final String workload;
  private final String bucket;
  private final String project;
  // --transport=bidi (gRPC client) or json (HTTP client).
  private final boolean json;
  private final int threads;
  private final int durationSec;
  private final int warmupSec;
  // Number of Storage instances; thread t uses instance t % clients. A Java Storage has one gRPC
  // connection, so this is how a Java user gets more connections.
  private final int clients;
  private final long objectSize;
  private final int rangeSize;
  private final int writeChunk;
  private final int listItems;
  private final Path out;
  // Free-form configuration name for the results table, e.g. "rust-default".
  private final String label;
  private final List<Storage> pool = new ArrayList<>();
  // Bytes accepted by write channels. A 200 MB upload can take most of a minute, so write
  // throughput comes from the bytes written inside the window, not from completed objects.
  private final LongAdder bytesWritten = new LongAdder();
  private long windowBytesWritten = -1;

  private RapidBenchmark(Map<String, String> args) {
    this.args = args;
    this.engine = System.getProperty("gcs.rust.lib") != null ? "rust" : "java";
    this.workload = required("workload");
    this.bucket = workload.equals("list-buckets") ? arg("bucket", "") : required("bucket");
    this.project = arg("project", "");
    boolean heavy = workload.equals("read") || workload.equals("write");
    this.json = arg("transport", "bidi").equals("json");
    this.threads = Integer.parseInt(arg("threads", heavy ? "48" : "1"));
    this.durationSec = Integer.parseInt(arg("duration", heavy ? "300" : "180"));
    this.warmupSec = Integer.parseInt(arg("warmup", heavy ? "30" : "10"));
    this.clients = Integer.parseInt(arg("clients", "1"));
    this.objectSize = Long.parseLong(arg("object-size", String.valueOf(200_000_000L)));
    this.rangeSize = Integer.parseInt(arg("range-size", String.valueOf(16 * MiB)));
    this.writeChunk = Integer.parseInt(arg("write-chunk", String.valueOf(2 * MiB)));
    this.listItems = Integer.parseInt(arg("list-items", "100"));
    this.label = arg("label", engine);
    String run =
        String.join(
            "-",
            workload,
            json ? "json" : "bidi",
            arg("mode", "x"),
            label,
            Instant.now().toString().replace(':', '-'));
    this.out = Paths.get(arg("out", "results"), run);
  }

  public static void main(String[] argv) throws Exception {
    Map<String, String> args = new HashMap<>();
    for (String a : argv) {
      if (!a.startsWith("--") || !a.contains("=")) {
        throw new IllegalArgumentException("expected --key=value, got " + a);
      }
      args.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
    }
    new RapidBenchmark(args).run();
  }

  private void run() throws Exception {
    Files.createDirectories(out);
    // Java's built-in gRPC client metrics are off: they only record calls on the Java channel, so
    // leaving them on would add a cost to the Java runs that the Rust runs don't pay.
    StorageOptions.Builder b =
        json
            ? StorageOptions.http()
            : StorageOptions.grpc().setAttemptDirectPath(false).setEnableGrpcClientMetrics(false);
    if (!project.isEmpty()) {
      b.setProjectId(project);
    }
    StorageOptions options = b.build();
    for (int i = 0; i < clients; i++) {
      pool.add(options.toBuilder().build().getService());
    }
    Storage storage = pool.get(0);
    try {
      switch (workload) {
        case "open":
          runOpen(storage);
          break;
        case "read50x2x4":
          runRead50x2x4(storage);
          break;
        case "random8k":
          runRandom8k(storage);
          break;
        case "read":
          runRead(storage);
          break;
        case "write":
          runWrite(storage);
          break;
        case "list-objects":
          runListObjects(storage);
          break;
        case "list-buckets":
          runListBuckets(storage);
          break;
        default:
          throw new IllegalArgumentException("unknown --workload=" + workload);
      }
    } finally {
      for (Storage s : pool) {
        s.close();
      }
    }
  }

  /** The Storage instance a worker thread uses. */
  private Storage storageFor(int thread) {
    return pool.get(thread % pool.size());
  }

  // ---------------------------------------------------------------------------------------------
  // Workloads 1-3 (one operation at a time, --threads=1)
  // ---------------------------------------------------------------------------------------------

  /** 1. Open: start a Bidi Read, wait for the first response (object metadata), close. */
  private void runOpen(Storage storage) throws Exception {
    BlobId id = ensureObject(storage, arg("object", "rust-poc-read-" + 100 * MiB), 100 * MiB);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("object", id.getName());
    params.put("engine_class", sessionClass(storage, id));
    runTimed(
        "OPEN",
        thread ->
            () -> {
              BlobReadSession s =
                  storageFor(thread).blobReadSession(id).get(OP_TIMEOUT_SEC, TimeUnit.SECONDS);
              s.close();
              return 1;
            },
        params);
  }

  /**
   * 2. Read50x2x4: from a 200 MiB object, read 50 ranges of 2 MiB at a 4 MiB stride. One operation
   * = all 50 reads. {@code --mode=concurrent} issues all 50 then waits; {@code serial} reads one
   * after another. {@code --transport=bidi} uses one open {@link BlobReadSession}; {@code json}
   * uses one {@code reader()} per range.
   */
  private void runRead50x2x4(Storage storage) throws Exception {
    final int n = 50;
    final int len = 2 * MiB;
    final long stride = 4L * MiB;
    String mode = arg("mode", "concurrent");
    boolean concurrent = mode.equals("concurrent");
    BlobId id = ensureObject(storage, arg("object", "rust-poc-read-" + 200 * MiB), 200L * MiB);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("object", id.getName());
    params.put("mode", mode);
    params.put("ranges", n);
    params.put("range_size", len);
    params.put("stride", stride);
    if (json) {
      params.put("engine_class", readerClass(storage, id));
      ExecutorService pool = Executors.newFixedThreadPool(n);
      try {
        runTimed(
            "READ50X2X4_JSON_" + mode.toUpperCase(Locale.ROOT),
            thread -> {
              Storage s = storageFor(thread);
              ByteBuffer[] bufs = new ByteBuffer[n];
              for (int i = 0; i < n; i++) {
                bufs[i] = ByteBuffer.allocate(len);
              }
              return () -> {
                long total = 0;
                if (concurrent) {
                  List<Future<Integer>> fs = new ArrayList<>(n);
                  for (int i = 0; i < n; i++) {
                    long off = i * stride;
                    ByteBuffer b = bufs[i];
                    fs.add(pool.submit(() -> jsonRange(s, id, off, b)));
                  }
                  for (Future<Integer> f : fs) {
                    total += f.get(OP_TIMEOUT_SEC, TimeUnit.SECONDS);
                  }
                } else {
                  for (int i = 0; i < n; i++) {
                    total += jsonRange(s, id, i * stride, bufs[i]);
                  }
                }
                return total;
              };
            },
            params);
      } finally {
        pool.shutdownNow();
      }
      return;
    }
    params.put("engine_class", sessionClass(storage, id));
    runTimed(
        "READ50X2X4_BIDI_" + mode.toUpperCase(Locale.ROOT),
        thread ->
            new SessionOp(storageFor(thread), id) {
              @Override
              long read(BlobReadSession session) throws Exception {
                long total = 0;
                if (concurrent) {
                  List<ApiFuture<byte[]>> fs = new ArrayList<>(n);
                  for (int i = 0; i < n; i++) {
                    fs.add(
                        session.readAs(
                            ReadProjectionConfigs.asFutureBytes()
                                .withRangeSpec(RangeSpec.of(i * stride, len))));
                  }
                  for (byte[] b : ApiFutures.allAsList(fs).get(OP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    total += b.length;
                  }
                } else {
                  for (int i = 0; i < n; i++) {
                    total +=
                        session
                            .readAs(
                                ReadProjectionConfigs.asFutureBytes()
                                    .withRangeSpec(RangeSpec.of(i * stride, len)))
                            .get(OP_TIMEOUT_SEC, TimeUnit.SECONDS)
                            .length;
                  }
                }
                return total;
              }
            },
        params);
  }

  /**
   * 3. Random8K: from an open 100 MiB object, read random 8 KiB ranges, one at a time. {@code bidi}
   * reads over one open {@link BlobReadSession}; {@code json} opens one {@code reader()} per read.
   */
  private void runRandom8k(Storage storage) throws Exception {
    final int len = 8 * 1024;
    BlobId id = ensureObject(storage, arg("object", "rust-poc-read-" + 100 * MiB), 100 * MiB);
    long slots = 100L * MiB / len;
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("object", id.getName());
    params.put("range_size", len);
    if (json) {
      params.put("engine_class", readerClass(storage, id));
      runTimed(
          "RANDOM8K_JSON",
          thread -> {
            Storage s = storageFor(thread);
            Random rnd = new Random(thread);
            ByteBuffer buf = ByteBuffer.allocate(len);
            return () -> jsonRange(s, id, (rnd.nextLong() & Long.MAX_VALUE) % slots * len, buf);
          },
          params);
      return;
    }
    params.put("engine_class", sessionClass(storage, id));
    runTimed(
        "RANDOM8K_BIDI",
        thread -> {
          Random rnd = new Random(thread);
          return new SessionOp(storageFor(thread), id) {
            @Override
            long read(BlobReadSession session) throws Exception {
              long off = (rnd.nextLong() & Long.MAX_VALUE) % slots * len;
              return session
                  .readAs(
                      ReadProjectionConfigs.asFutureBytes().withRangeSpec(RangeSpec.of(off, len)))
                  .get(OP_TIMEOUT_SEC, TimeUnit.SECONDS)
                  .length;
            }
          };
        },
        params);
  }

  // ---------------------------------------------------------------------------------------------
  // Workload 4: 16 MiB reads of a 200 MB object, 48 threads
  // ---------------------------------------------------------------------------------------------

  private void runRead(Storage storage) throws Exception {
    BlobId id = ensureObject(storage, arg("object", "rust-poc-read-" + objectSize), objectSize);
    long size = storage.get(id).getSize();
    if (size < rangeSize) {
      throw new IllegalStateException(id.getName() + " is smaller than --range-size");
    }
    long ranges = size / rangeSize;
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("object", id.getName());
    params.put("object_size", size);
    params.put("range_size", rangeSize);
    if (json) {
      params.put("engine_class", readerClass(storage, id));
      runTimed(
          "READ_JSON",
          thread -> {
            Storage s = storageFor(thread);
            Random rnd = new Random(thread);
            ByteBuffer buf = ByteBuffer.allocate(rangeSize);
            return () ->
                jsonRange(s, id, (rnd.nextLong() & Long.MAX_VALUE) % ranges * rangeSize, buf);
          },
          params);
      return;
    }
    params.put("engine_class", sessionClass(storage, id));
    runTimed(
        "BIDI_READ",
        thread -> {
          Random rnd = new Random(thread);
          return new SessionOp(storageFor(thread), id) {
            @Override
            long read(BlobReadSession session) throws Exception {
              long offset = (rnd.nextLong() & Long.MAX_VALUE) % ranges * rangeSize;
              return session
                  .readAs(
                      ReadProjectionConfigs.asFutureBytes()
                          .withRangeSpec(RangeSpec.of(offset, rangeSize)))
                  .get(OP_TIMEOUT_SEC, TimeUnit.SECONDS)
                  .length;
            }
          };
        },
        params);
  }

  // ---------------------------------------------------------------------------------------------
  // Workload 5: 200 MB writes, 48 threads
  // ---------------------------------------------------------------------------------------------

  /**
   * Each thread keeps overwriting its own object name, so the bytes stored stay at about threads x
   * object size (a Rapid bucket counts stored bytes against a per-zone quota).
   */
  private void runWrite(Storage storage) throws Exception {
    ByteBuffer payload = randomPayload(objectSize);
    String prefix = arg("prefix", "rust-poc-" + (json ? "jsonwrite" : "write") + "-" + label + "/");
    String[] uploadClass = {""};
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("object_size", objectSize);
    params.put("write_chunk", writeChunk);
    params.put("prefix", prefix);
    runTimed(
        json ? "WRITE_JSON" : "BIDI_APPENDABLE_WRITE",
        thread -> {
          BlobInfo info = BlobInfo.newBuilder(bucket, prefix + thread).build();
          Storage s = storageFor(thread);
          if (json) {
            return () -> {
              WriteChannel w = s.writer(info);
              uploadClass[0] = w.getClass().getSimpleName();
              try (WriteChannel ch = w) {
                writeSlices(ch, payload);
              }
              return objectSize;
            };
          }
          return () -> {
            BlobAppendableUpload upload =
                s.blobAppendableUpload(
                    info,
                    BlobAppendableUploadConfig.of()
                        .withCloseAction(CloseAction.FINALIZE_WHEN_CLOSING));
            uploadClass[0] = upload.getClass().getSimpleName();
            try (AppendableUploadWriteableByteChannel ch = upload.open()) {
              writeSlices(ch, payload);
            }
            return upload.getResult().get(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS).getSize();
          };
        },
        params,
        () -> params.put("engine_class", uploadClass[0]));
    if (Boolean.parseBoolean(arg("cleanup", "true"))) {
      for (int t = 0; t < threads; t++) {
        storage.delete(BlobId.of(bucket, prefix + t));
      }
    }
  }

  /** Writes the payload in --write-chunk slices, counting bytes for window throughput. */
  private void writeSlices(java.nio.channels.WritableByteChannel ch, ByteBuffer payload)
      throws IOException {
    ByteBuffer src = payload.duplicate();
    src.clear();
    while (src.hasRemaining()) {
      ByteBuffer slice = src.slice();
      slice.limit(Math.min(writeChunk, src.remaining()));
      int n = slice.remaining();
      while (slice.hasRemaining()) {
        bytesWritten.add(ch.write(slice));
      }
      src.position(src.position() + n);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Read helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * A worker's Bidi Read session. If a read fails, the session is closed and a new one is opened
   * for the next operation (what an application would do), and the error is still counted.
   */
  private abstract class SessionOp implements Op {
    private final Storage storage;
    private final BlobId id;
    private BlobReadSession session;

    SessionOp(Storage storage, BlobId id) throws Exception {
      this.storage = storage;
      this.id = id;
      this.session = open();
    }

    private BlobReadSession open() throws Exception {
      return storage.blobReadSession(id).get(OP_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    abstract long read(BlobReadSession session) throws Exception;

    @Override
    public long run() throws Exception {
      try {
        return read(session);
      } catch (Exception e) {
        try {
          session.close();
        } catch (Exception ignored) {
          // replacing it anyway
        }
        session = open();
        throw e;
      }
    }

    @Override
    public void close() throws IOException {
      session.close();
    }
  }

  /** Reads [offset, offset + buf.capacity()) with one {@code reader()}. Returns the bytes read. */
  private static int jsonRange(Storage storage, BlobId id, long offset, ByteBuffer buf)
      throws IOException {
    buf.clear();
    try (ReadChannel r = storage.reader(id.getBucket(), id.getName())) {
      r.seek(offset);
      r.limit(offset + buf.capacity());
      while (buf.hasRemaining()) {
        if (r.read(buf) < 0) {
          break;
        }
      }
    }
    if (buf.hasRemaining()) {
      throw new IOException("short read: " + buf.position() + " of " + buf.capacity());
    }
    return buf.position();
  }

  private static String sessionClass(Storage storage, BlobId id) throws Exception {
    try (BlobReadSession s = storage.blobReadSession(id).get(OP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
      return s.getClass().getSimpleName();
    }
  }

  private static String readerClass(Storage storage, BlobId id) throws IOException {
    try (ReadChannel r = storage.reader(id.getBucket(), id.getName())) {
      return r.getClass().getSimpleName();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Workload 6: List Objects / List Buckets
  // ---------------------------------------------------------------------------------------------

  private void runListObjects(Storage storage) throws Exception {
    String prefix = arg("prefix", "rust-poc-list-" + listItems + "/");
    int have = 0;
    for (Blob ignored : storage.list(bucket, BlobListOption.prefix(prefix)).iterateAll()) {
      have++;
    }
    for (int i = have; i < listItems; i++) {
      ensureObject(storage, String.format(Locale.ROOT, "%s%05d", prefix, i), 1);
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("prefix", prefix);
    params.put("page_size", listItems);
    runTimed(
        "LIST_OBJECTS",
        thread ->
            () -> {
              long n = 0;
              Page<Blob> page =
                  storageFor(thread)
                      .list(
                          bucket,
                          BlobListOption.prefix(prefix),
                          BlobListOption.pageSize(listItems));
              for (Blob ignored : page.getValues()) {
                n++;
              }
              if (n != listItems) {
                throw new IllegalStateException("listed " + n + " objects, expected " + listItems);
              }
              return n;
            },
        params);
  }

  private void runListBuckets(Storage storage) throws Exception {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("project", project);
    params.put("page_size", listItems);
    runTimed(
        "LIST_BUCKETS",
        thread ->
            () -> {
              long n = 0;
              for (Bucket ignored :
                  storageFor(thread).list(BucketListOption.pageSize(listItems)).getValues()) {
                n++;
              }
              return n;
            },
        params);
  }

  // ---------------------------------------------------------------------------------------------
  // Setup helpers (not timed)
  // ---------------------------------------------------------------------------------------------

  /** Returns the object, creating it (random data) if it doesn't exist. */
  private BlobId ensureObject(Storage storage, String name, long size) throws Exception {
    Blob existing = storage.get(BlobId.of(bucket, name));
    if (existing != null && existing.getSize() != null && existing.getSize() == size) {
      return existing.getBlobId();
    }
    System.out.printf("creating %s/%s (%d bytes)%n", bucket, name, size);
    ByteBuffer data = randomPayload(size);
    BlobInfo info = BlobInfo.newBuilder(bucket, name).build();
    if (StorageClass.valueOf("RAPID").equals(storage.get(bucket).getStorageClass())) {
      // Zonal buckets need appendable uploads.
      BlobAppendableUpload upload =
          storage.blobAppendableUpload(
              info,
              BlobAppendableUploadConfig.of().withCloseAction(CloseAction.FINALIZE_WHEN_CLOSING));
      try (AppendableUploadWriteableByteChannel ch = upload.open()) {
        writeSlices(ch, data);
      }
      return upload.getResult().get(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS).getBlobId();
    }
    try (WriteChannel w = storage.writer(info)) {
      while (data.hasRemaining()) {
        w.write(data);
      }
    }
    return storage.get(BlobId.of(bucket, name)).getBlobId();
  }

  private static ByteBuffer randomPayload(long size) {
    if (size > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("object size must fit in an int");
    }
    ByteBuffer b = ByteBuffer.allocateDirect((int) size);
    byte[] block = new byte[MiB];
    new Random(42).nextBytes(block);
    while (b.hasRemaining()) {
      b.put(block, 0, Math.min(block.length, b.remaining()));
    }
    b.flip();
    return b;
  }

  // ---------------------------------------------------------------------------------------------
  // Timed runner and metrics
  // ---------------------------------------------------------------------------------------------

  /** One measured operation; returns the bytes (or items) it handled. */
  private interface Op extends AutoCloseable {
    long run() throws Exception;

    @Override
    default void close() throws IOException {}
  }

  private interface OpFactory {
    Op create(int thread) throws Exception;
  }

  /** Per-thread log of [endNanos, latencyNanos, amount] triples, plus errors. */
  private static final class OpLog {
    long[] data = new long[3 * 1024];
    int n;
    long errors;
    String firstError;

    void add(long end, long latency, long amount) {
      if (3 * n + 3 > data.length) {
        data = Arrays.copyOf(data, data.length * 2);
      }
      data[3 * n] = end;
      data[3 * n + 1] = latency;
      data[3 * n + 2] = amount;
      n++;
    }
  }

  private void runTimed(String op, OpFactory factory, Map<String, Object> params) throws Exception {
    runTimed(op, factory, params, () -> {});
  }

  private void runTimed(String op, OpFactory factory, Map<String, Object> params, Runnable before)
      throws Exception {
    OpLog[] logs = new OpLog[threads];
    AtomicBoolean stop = new AtomicBoolean();
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int thread = t;
      logs[t] = new OpLog();
      Thread w =
          new Thread(
              () -> {
                OpLog log = logs[thread];
                boolean counted = false;
                try (Op o = factory.create(thread)) {
                  ready.countDown();
                  counted = true;
                  go.await();
                  while (!stop.get()) {
                    long start = System.nanoTime();
                    try {
                      long amount = o.run();
                      long end = System.nanoTime();
                      log.add(end, end - start, amount);
                    } catch (Exception e) {
                      log.errors++;
                      if (log.firstError == null) {
                        log.firstError = e.toString();
                        e.printStackTrace();
                      }
                    }
                  }
                } catch (Exception e) {
                  log.firstError = "setup: " + e;
                  e.printStackTrace();
                } finally {
                  if (!counted) {
                    ready.countDown();
                  }
                }
              },
              "bench-" + t);
      workers.add(w);
      w.start();
    }
    ready.await();

    com.sun.management.OperatingSystemMXBean os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    long t0 = System.nanoTime();
    go.countDown();
    System.out.printf(
        "%s on %s: %d threads, %d s warm-up + %d s measured%n",
        op, engine, threads, warmupSec, durationSec);
    Thread.sleep(TimeUnit.SECONDS.toMillis(warmupSec));
    long measureStart = System.nanoTime();
    Instant windowStart = Instant.now();
    long cpuStart = os.getProcessCpuTime();
    long writtenStart = bytesWritten.sum();
    long[] nicStart = nicBytes();
    int tcpAtStart = established443();
    Thread.sleep(TimeUnit.SECONDS.toMillis(durationSec / 2));
    int tcpMid = established443();
    Thread.sleep(TimeUnit.SECONDS.toMillis(durationSec - durationSec / 2));
    long measureEnd = System.nanoTime();
    Instant windowEnd = Instant.now();
    long writtenEnd = bytesWritten.sum();
    long[] nicEnd = nicBytes();
    int tcpAtEnd = established443();
    double cpuPct =
        100.0
            * (os.getProcessCpuTime() - cpuStart)
            / (measureEnd - measureStart)
            / Runtime.getRuntime().availableProcessors();
    stop.set(true);
    for (Thread w : workers) {
      w.join();
    }
    before.run();
    double secs = (measureEnd - measureStart) / 1e9;
    Map<String, Object> net = new LinkedHashMap<>();
    net.put("tcp_established_443_start", tcpAtStart);
    net.put("tcp_established_443_mid", tcpMid);
    net.put("tcp_established_443_end", tcpAtEnd);
    net.put("nic_rx_Gbps", (nicEnd[0] - nicStart[0]) * 8 / secs / 1e9);
    net.put("nic_tx_Gbps", (nicEnd[1] - nicStart[1]) * 8 / secs / 1e9);
    params.put("network", net);
    if (op.equals("BIDI_APPENDABLE_WRITE") || op.equals("WRITE_JSON")) {
      windowBytesWritten = writtenEnd - writtenStart;
    }
    report(op, logs, t0, measureStart, measureEnd, windowStart, windowEnd, cpuPct, params);
  }

  /**
   * Established TCP connections from this process to remote port 443 (the GCS endpoint), from
   * /proc/self/fd and /proc/net/tcp{,6}. Shows how many connections each engine really opened. -1
   * if unavailable.
   */
  private static int established443() {
    // Socket inodes owned by this process, so other programs' connections are not counted.
    Set<String> mine = new HashSet<>();
    try (DirectoryStream<Path> fds = Files.newDirectoryStream(Paths.get("/proc/self/fd"))) {
      for (Path fd : fds) {
        try {
          String target = Files.readSymbolicLink(fd).toString();
          if (target.startsWith("socket:[")) {
            mine.add(target.substring(8, target.length() - 1));
          }
        } catch (IOException ignored) {
          // fd closed while listing
        }
      }
    } catch (IOException e) {
      return -1;
    }
    int n = 0;
    for (String f : new String[] {"/proc/net/tcp", "/proc/net/tcp6"}) {
      try {
        for (String line : Files.readAllLines(Paths.get(f))) {
          String[] c = line.trim().split("\\s+");
          // c[2] = remote "ADDR:PORT" in hex, c[3] = state (01 = ESTABLISHED), c[9] = inode.
          if (c.length > 9 && c[2].endsWith(":01BB") && c[3].equals("01") && mine.contains(c[9])) {
            n++;
          }
        }
      } catch (IOException e) {
        return -1;
      }
    }
    return n;
  }

  /** Total [rx, tx] bytes over all non-loopback interfaces, from /proc/net/dev. */
  private static long[] nicBytes() {
    long rx = 0;
    long tx = 0;
    try {
      for (String line : Files.readAllLines(Paths.get("/proc/net/dev"))) {
        int colon = line.indexOf(':');
        if (colon < 0 || line.substring(0, colon).trim().equals("lo")) {
          continue;
        }
        String[] c = line.substring(colon + 1).trim().split("\\s+");
        rx += Long.parseLong(c[0]);
        tx += Long.parseLong(c[8]);
      }
    } catch (IOException | RuntimeException e) {
      return new long[] {0, 0};
    }
    return new long[] {rx, tx};
  }

  private void report(
      String op,
      OpLog[] logs,
      long t0,
      long measureStart,
      long measureEnd,
      Instant windowStart,
      Instant windowEnd,
      double cpuPct,
      Map<String, Object> params)
      throws IOException {
    int total = 0;
    long errors = 0;
    String firstError = null;
    for (OpLog l : logs) {
      total += l.n;
      errors += l.errors;
      if (firstError == null) {
        firstError = l.firstError;
      }
    }
    long[] lat = new long[total];
    int k = 0;
    long amount = 0;
    TreeMap<Long, long[]> perSecond = new TreeMap<>();
    try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("ops.csv")))) {
      csv.println("thread,end_ms,latency_ms,amount,measured");
      for (int t = 0; t < logs.length; t++) {
        OpLog l = logs[t];
        for (int i = 0; i < l.n; i++) {
          long end = l.data[3 * i];
          long latency = l.data[3 * i + 1];
          long a = l.data[3 * i + 2];
          boolean measured = end >= measureStart && end <= measureEnd;
          csv.printf(
              Locale.ROOT, "%d,%.3f,%.3f,%d,%b%n", t, (end - t0) / 1e6, latency / 1e6, a, measured);
          long[] s = perSecond.computeIfAbsent((end - t0) / 1_000_000_000L, x -> new long[2]);
          s[0]++;
          s[1] += a;
          if (measured) {
            lat[k++] = latency;
            amount += a;
          }
        }
      }
    }
    try (PrintWriter tl = new PrintWriter(Files.newBufferedWriter(out.resolve("timeline.csv")))) {
      tl.println("second,ops,amount");
      for (Map.Entry<Long, long[]> e : perSecond.entrySet()) {
        tl.printf(Locale.ROOT, "%d,%d,%d%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
      }
    }
    lat = Arrays.copyOf(lat, k);
    Arrays.sort(lat);
    double secs = (measureEnd - measureStart) / 1e9;
    // Item-counting operations report ops or items per second; the rest report bytes.
    boolean list = op.startsWith("LIST") || op.equals("OPEN");

    Map<String, Object> s = new LinkedHashMap<>();
    s.put("op", op);
    s.put("engine", engine);
    s.put("transport", json ? "json" : "bidi");
    s.put("label", label);
    s.put("bucket", bucket);
    s.put("threads", threads);
    s.put("clients", clients);
    s.put("rust_grpc_subchannels", System.getProperty("gcs.rust.grpc.subchannels", "sdk-default"));
    s.put("java_grpc_channels", System.getProperty("gcs.java.grpc.channels", "default"));
    s.put("jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments());
    s.put("params", params);
    s.put("window_start", windowStart.toString());
    s.put("window_end", windowEnd.toString());
    s.put("measured_seconds", secs);
    s.put("ops", k);
    s.put("errors", errors);
    s.put("first_error", firstError);
    s.put("latency_ms_p50", pct(lat, 50));
    s.put("latency_ms_p90", pct(lat, 90));
    s.put("latency_ms_p99", pct(lat, 99));
    s.put("latency_ms_p999", pct(lat, 99.9));
    s.put("latency_ms_max", k == 0 ? 0.0 : lat[k - 1] / 1e6);
    s.put("latency_ms_mean", k == 0 ? 0.0 : Arrays.stream(lat).average().orElse(0) / 1e6);
    s.put("ops_per_sec", k / secs);
    if (list) {
      s.put("items_per_sec", amount / secs);
    } else if (windowBytesWritten >= 0) {
      s.put("bytes", windowBytesWritten);
      s.put("throughput_MBps", windowBytesWritten / secs / 1e6);
      s.put("throughput_Gbps", windowBytesWritten * 8 / secs / 1e9);
      s.put("completed_objects_Gbps", amount * 8 / secs / 1e9);
    } else {
      s.put("bytes", amount);
      s.put("throughput_MBps", amount / secs / 1e6);
      s.put("throughput_Gbps", amount * 8 / secs / 1e9);
    }
    s.put("process_cpu_percent_of_all_cores", cpuPct);
    s.put("process_peak_rss_mib", peakRssMiB());
    s.put("available_processors", Runtime.getRuntime().availableProcessors());
    s.put("java_version", System.getProperty("java.version"));
    s.putAll(gceInstance());

    String json = Json.write(s);
    Files.write(out.resolve("summary.json"), json.getBytes(StandardCharsets.UTF_8));
    System.out.println(json);
    System.out.println("results: " + out.toAbsolutePath());
  }

  private static double pct(long[] sorted, double p) {
    if (sorted.length == 0) {
      return 0;
    }
    int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, i))] / 1e6;
  }

  private static double peakRssMiB() {
    try {
      for (String line : Files.readAllLines(Paths.get("/proc/self/status"))) {
        if (line.startsWith("VmHWM:")) {
          return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024.0;
        }
      }
    } catch (IOException | NumberFormatException e) {
      // not Linux
    }
    return -1;
  }

  /** GCE instance identity, used to fetch Cloud Monitoring metrics later (empty off GCE). */
  private static Map<String, Object> gceInstance() {
    Map<String, Object> m = new LinkedHashMap<>();
    String id = metadata("instance/id");
    if (id != null) {
      m.put("gce_instance_id", id);
      m.put("gce_instance_name", metadata("instance/name"));
      m.put("gce_zone", lastSegment(metadata("instance/zone")));
      m.put("gce_machine_type", lastSegment(metadata("instance/machine-type")));
      m.put("gce_project", metadata("project/project-id"));
    }
    return m;
  }

  private static String lastSegment(String s) {
    return s == null ? null : s.substring(s.lastIndexOf('/') + 1);
  }

  private static String metadata(String path) {
    try {
      HttpURLConnection c =
          (HttpURLConnection)
              new URL("http://metadata.google.internal/computeMetadata/v1/" + path)
                  .openConnection();
      c.setRequestProperty("Metadata-Flavor", "Google");
      c.setConnectTimeout(500);
      c.setReadTimeout(1000);
      if (c.getResponseCode() != 200) {
        return null;
      }
      return new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return null;
    }
  }

  /** Minimal JSON writer for the summary (no extra dependencies). */
  private static final class Json {
    static String write(Object o) {
      StringBuilder sb = new StringBuilder();
      write(sb, o, "");
      return sb.toString();
    }

    private static void write(StringBuilder sb, Object o, String indent) {
      if (o == null) {
        sb.append("null");
      } else if (o instanceof Map) {
        sb.append("{\n");
        String in = indent + "  ";
        boolean first = true;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
          sb.append(first ? "" : ",\n").append(in).append('"').append(e.getKey()).append("\": ");
          first = false;
          write(sb, e.getValue(), in);
        }
        sb.append('\n').append(indent).append('}');
      } else if (o instanceof Double) {
        double d = (Double) o;
        sb.append(Double.isFinite(d) ? String.format(Locale.ROOT, "%.3f", d) : "null");
      } else if (o instanceof Number || o instanceof Boolean) {
        sb.append(o);
      } else {
        sb.append('"')
            .append(o.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
            .append('"');
      }
    }
  }

  private String arg(String key, String def) {
    return args.getOrDefault(key, def);
  }

  private String required(String key) {
    String v = args.get(key);
    if (v == null || v.isEmpty()) {
      throw new IllegalArgumentException("--" + key + "=... is required");
    }
    return v;
  }
}
