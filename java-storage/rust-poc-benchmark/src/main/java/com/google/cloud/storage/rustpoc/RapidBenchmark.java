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

import com.google.api.gax.paging.Page;
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
import com.google.cloud.storage.Storage.BlobWriteOption;
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
import java.util.Collections;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rapid workloads 4, 5 and 6 through the normal java-storage API.
 *
 * <p>The engine is chosen only by the JVM flag: with {@code -Dgcs.rust.lib=<libgcs_rust_ffi.so>}
 * the gRPC client runs on the Rust SDK; without it, on the Java path. Everything else (this code,
 * the jar, the JVM) is the same. Both sides use CloudPath (DirectPath off).
 *
 * <ul>
 *   <li>{@code --workload=read} (4): N threads, each with its own {@link BlobReadSession}, read
 *       random {@code --range-size} ranges of one {@code --object-size} object with {@code
 *       readAs(asFutureBytes())}.
 *   <li>{@code --workload=write} (5): N threads upload {@code --object-size} objects with {@code
 *       blobAppendableUpload} (zonal bucket), writing {@code --write-chunk} slices.
 *   <li>{@code --workload=list-objects} / {@code list-buckets} (6): one page of {@code
 *       --list-items} items per call.
 * </ul>
 *
 * <p>Connections: a Java {@code Storage} uses one gRPC connection; the Rust SDK opens one per CPU
 * for the data path. {@code --clients=K} creates K {@code Storage} instances (worker t uses t % K),
 * and {@code -Dgcs.rust.grpc.subchannels=N} sets the Rust connection count, so runs can be matched.
 * Established TCP connections to port 443 and NIC Gbps are recorded under {@code params.network}.
 *
 * <p>Latency is measured around the outermost API call(s) of each operation; operations that end in
 * the warm-up are not counted. Results go to {@code --out/<workload>-<engine>-<time>/}.
 */
public final class RapidBenchmark {

  private static final int MiB = 1024 * 1024;

  private final Map<String, String> args;
  private final String engine;
  private final String workload;
  private final String bucket;
  private final String project;
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

  private RapidBenchmark(Map<String, String> args) {
    this.args = args;
    this.engine = System.getProperty("gcs.rust.lib") != null ? "rust" : "java";
    this.workload = required("workload");
    this.bucket = workload.equals("list-buckets") ? arg("bucket", "") : required("bucket");
    this.project = arg("project", "");
    this.threads = Integer.parseInt(arg("threads", workload.startsWith("list") ? "1" : "48"));
    this.durationSec = Integer.parseInt(arg("duration", "300"));
    this.warmupSec = Integer.parseInt(arg("warmup", workload.startsWith("list") ? "10" : "30"));
    this.clients = Integer.parseInt(arg("clients", "1"));
    this.objectSize = Long.parseLong(arg("object-size", String.valueOf(200_000_000L)));
    this.rangeSize = Integer.parseInt(arg("range-size", String.valueOf(16 * MiB)));
    this.writeChunk = Integer.parseInt(arg("write-chunk", String.valueOf(2 * MiB)));
    this.listItems = Integer.parseInt(arg("list-items", "100"));
    this.label = arg("label", engine);
    String run = workload + "-" + label + "-" + Instant.now().toString().replace(':', '-');
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
    StorageOptions.Builder b = StorageOptions.grpc().setAttemptDirectPath(false);
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
  // Workload 4: Bidi Read
  // ---------------------------------------------------------------------------------------------

  private void runRead(Storage storage) throws Exception {
    String name = arg("object", "rust-poc-read-" + objectSize);
    BlobId id = ensureObject(storage, name, objectSize);
    long size;
    String sessionClass;
    try (BlobReadSession probe = storage.blobReadSession(id).get(60, TimeUnit.SECONDS)) {
      size = probe.getBlobInfo().getSize();
      sessionClass = probe.getClass().getSimpleName();
    }
    if (size < rangeSize) {
      throw new IllegalStateException(name + " is smaller than --range-size");
    }
    long ranges = size / rangeSize;
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("object", name);
    params.put("object_size", size);
    params.put("range_size", rangeSize);
    params.put("engine_class", sessionClass);
    runTimed(
        "BIDI_READ",
        thread -> {
          BlobReadSession session =
              storageFor(thread).blobReadSession(id).get(60, TimeUnit.SECONDS);
          Random rnd = new Random(thread);
          return new Op() {
            @Override
            public long run() throws Exception {
              long offset = (rnd.nextLong() & Long.MAX_VALUE) % ranges * rangeSize;
              byte[] got =
                  session
                      .readAs(
                          ReadProjectionConfigs.asFutureBytes()
                              .withRangeSpec(RangeSpec.of(offset, rangeSize)))
                      .get();
              return got.length;
            }

            @Override
            public void close() throws IOException {
              session.close();
            }
          };
        },
        params);
  }

  // ---------------------------------------------------------------------------------------------
  // Workload 5: Bidi Appendable Write
  // ---------------------------------------------------------------------------------------------

  private void runWrite(Storage storage) throws Exception {
    ByteBuffer payload = randomPayload(objectSize);
    String prefix =
        arg("prefix", "rust-poc-write-" + engine + "-" + System.currentTimeMillis() + "/");
    List<BlobId> created = Collections.synchronizedList(new ArrayList<>());
    String[] uploadClass = {""};
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("object_size", objectSize);
    params.put("write_chunk", writeChunk);
    params.put("prefix", prefix);
    runTimed(
        "BIDI_APPENDABLE_WRITE",
        thread -> {
          int[] seq = {0};
          return () -> {
            BlobInfo info = BlobInfo.newBuilder(bucket, prefix + thread + "-" + seq[0]++).build();
            BlobAppendableUpload upload =
                storageFor(thread)
                    .blobAppendableUpload(
                        info,
                        BlobAppendableUploadConfig.of()
                            .withCloseAction(CloseAction.FINALIZE_WHEN_CLOSING),
                        BlobWriteOption.doesNotExist());
            uploadClass[0] = upload.getClass().getSimpleName();
            writeAll(upload, payload);
            BlobInfo done = upload.getResult().get(10, TimeUnit.MINUTES);
            created.add(done.getBlobId());
            return done.getSize();
          };
        },
        params,
        () -> params.put("engine_class", uploadClass[0]));
    if (Boolean.parseBoolean(arg("cleanup", "true"))) {
      System.out.printf("deleting %d objects under %s%n", created.size(), prefix);
      for (BlobId id : created) {
        storage.delete(id);
      }
    }
  }

  private void writeAll(BlobAppendableUpload upload, ByteBuffer payload) throws IOException {
    ByteBuffer src = payload.duplicate();
    src.clear();
    try (AppendableUploadWriteableByteChannel ch = upload.open()) {
      while (src.hasRemaining()) {
        ByteBuffer slice = src.slice();
        slice.limit(Math.min(writeChunk, src.remaining()));
        int n = slice.remaining();
        while (slice.hasRemaining()) {
          ch.write(slice);
        }
        src.position(src.position() + n);
      }
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
      writeAll(upload, data);
      return upload.getResult().get(10, TimeUnit.MINUTES).getBlobId();
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
    long[] nicStart = nicBytes();
    int tcpAtStart = established443();
    Thread.sleep(TimeUnit.SECONDS.toMillis(durationSec / 2));
    int tcpMid = established443();
    Thread.sleep(TimeUnit.SECONDS.toMillis(durationSec - durationSec / 2));
    long measureEnd = System.nanoTime();
    Instant windowEnd = Instant.now();
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
    boolean list = op.startsWith("LIST");

    Map<String, Object> s = new LinkedHashMap<>();
    s.put("op", op);
    s.put("engine", engine);
    s.put("label", label);
    s.put("bucket", bucket);
    s.put("threads", threads);
    s.put("clients", clients);
    s.put("rust_grpc_subchannels", System.getProperty("gcs.rust.grpc.subchannels", "sdk-default"));
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
