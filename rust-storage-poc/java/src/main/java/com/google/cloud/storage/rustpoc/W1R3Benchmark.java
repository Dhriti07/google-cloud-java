package com.google.cloud.storage.rustpoc;

import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.cloud.storage.StorageChannelUtils;
import com.google.cloud.storage.StorageOptions;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The w1r3 workload of storage-shared-benchmarking (SSB; JSON transport) for three Java-hosted
 * implementations:
 *
 * <ul>
 *   <li>{@code native_java}: java-storage. SINGLE-SHOT = {@code create(info, bytes, off, len,
 *       disableGzipContent())}; RESUMABLE = {@code writer(info, doesNotExist())}, write all,
 *       close; READ x3 = {@code reader(id)} copied through a 2 MiB buffer and discarded; DELETE =
 *       {@code delete(id)}. Client: {@code StorageOptions.http()}, retries off.
 *   <li>{@code rust_jni} / {@code rust_panama}: the Rust Storage SDK via JNI / Panama: {@code
 *       write_object(...).set_if_generation_match(0).with_resumable_upload_threshold(0 | size+1)
 *       .send_unbuffered()}; {@code read_object().send()} streamed into the same 2 MiB Java
 *       buffer x3; {@code StorageControl delete_object}. {@code current_thread} Tokio runtime,
 *       retries off (see lib.rs).
 * </ul>
 *
 * <p>Per iteration: object size picked from {@code --object-sizes} (default 100 KB, 2 MiB, 100
 * MB), upload SINGLE-SHOT or RESUMABLE 50/50, READ[0..2], DELETE. The size/upload sequence uses a
 * fixed seed ({@link CppMt19937_64}) so all three implementations get the same sequence.
 *
 * <p>Metrics, measured the same way for every implementation ({@link ProcessMetrics}): op latency,
 * whole-process CPU ns per byte, Java and Rust allocated bytes per byte, peak RSS and RSS growth,
 * time spent inside the Rust SDK (so bridge time = op time - SDK time) and Java -> Rust calls.
 * {@code --warmup=N} runs N unrecorded iterations first.
 */
public final class W1R3Benchmark {

  private static final String WORKLOAD = "w1r3";
  private static final int MiB = 1024 * 1024;

  /** Retries off (one attempt). */
  private static final RetrySettings NEVER_RETRY =
      RetrySettings.newBuilder()
          .setMaxAttempts(1)
          .setTotalTimeoutDuration(Duration.ofSeconds(20))
          .setLogicalTimeout(Duration.ofSeconds(20))
          .setMaxRpcTimeoutDuration(Duration.ofSeconds(20))
          .build();

  /** The four w1r3 operations. Upload returns the id the reads/delete use. */
  interface Target extends AutoCloseable {
    String name();

    BlobId singleShot(BlobId id, byte[] data, int length) throws Exception;

    BlobId resumable(BlobId id, byte[] data, int length) throws Exception;

    long read(BlobId id) throws Exception;

    void delete(BlobId id) throws Exception;

    /** Bytes ever allocated by Rust (counting allocator); 0 if the Rust library isn't used. */
    default long rustAllocatedBytes() {
      return 0;
    }

    /** {Rust SDK nanoseconds, Java -> Rust calls} of this thread; null if Rust isn't used. */
    default long[] rustThreadStats() {
      return null;
    }

    @Override
    void close();
  }

  /** java-storage. */
  static final class NativeJava implements Target {
    private static final BlobTargetOption[] SINGLE_SHOT_OPTIONS = {
      BlobTargetOption.disableGzipContent()
    };
    private final Storage storage;
    private final ByteBuffer copyBuffer = ByteBuffer.allocate(2 * MiB); // read copy buffer

    NativeJava(String project) {
      StorageOptions.Builder builder = StorageOptions.http().setRetrySettings(NEVER_RETRY);
      if (!project.isEmpty()) {
        builder.setProjectId(project);
      }
      this.storage = builder.build().getService();
    }

    @Override
    public String name() {
      return "native_java";
    }

    @Override
    public BlobId singleShot(BlobId id, byte[] data, int length) {
      return storage
          .create(BlobInfo.newBuilder(id).build(), data, 0, length, SINGLE_SHOT_OPTIONS)
          .getBlobId();
    }

    @Override
    public BlobId resumable(BlobId id, byte[] data, int length) throws Exception {
      WriteChannel writer =
          storage.writer(BlobInfo.newBuilder(id).build(), BlobWriteOption.doesNotExist());
      StorageChannelUtils.blockingEmptyTo(ByteBuffer.wrap(data, 0, length), writer);
      writer.close();
      return id;
    }

    @Override
    public long read(BlobId id) throws Exception {
      // Copy through a 2 MiB buffer into a channel that discards the bytes.
      try (var reader = storage.reader(id)) {
        long total = 0;
        copyBuffer.clear();
        while (StorageChannelUtils.blockingFillFrom(copyBuffer, reader) != -1) {
          copyBuffer.flip();
          total += StorageChannelUtils.blockingEmptyTo(copyBuffer, NullChannel.INSTANCE);
          copyBuffer.clear();
        }
        return total;
      }
    }

    @Override
    public void delete(BlobId id) {
      storage.delete(id);
    }

    @Override
    public void close() {
      try {
        storage.close();
      } catch (Exception e) {
        // ignore
      }
    }
  }

  /** Rust SDK via JNI (see lib.rs). */
  static final class RustJni implements Target {
    private final JniGcsClient client;
    private final boolean discard;
    private final ByteBuffer copyBuffer = ByteBuffer.allocate(2 * MiB); // same as native_java

    RustJni(Path lib, boolean discard) {
      this.client = new JniGcsClient(lib);
      this.discard = discard;
    }

    @Override
    public String name() {
      return "rust_jni";
    }

    @Override
    public BlobId singleShot(BlobId id, byte[] data, int length) {
      client.write(id.getBucket(), id.getName(), slice(data, length), false);
      return id;
    }

    @Override
    public BlobId resumable(BlobId id, byte[] data, int length) {
      client.write(id.getBucket(), id.getName(), slice(data, length), true);
      return id;
    }

    @Override
    public long read(BlobId id) throws Exception {
      if (discard) {
        return client.readDiscard(id.getBucket(), id.getName(), 0);
      }
      return client.read(id.getBucket(), id.getName(), 0, copyBuffer, W1R3Benchmark::drain);
    }

    @Override
    public void delete(BlobId id) {
      client.delete(id.getBucket(), id.getName(), 0);
    }

    @Override
    public long rustAllocatedBytes() {
      return client.rustAllocatedBytes();
    }

    @Override
    public long[] rustThreadStats() {
      return new long[] {client.rustSdkNanos(), client.rustFfiCalls()};
    }

    @Override
    public void close() {
      client.close();
    }
  }

  /** Rust SDK via Panama (see lib.rs). */
  static final class RustPanama implements Target {
    private final PanamaGcsClient client;
    private final boolean discard;
    private final ByteBuffer copyBuffer = ByteBuffer.allocate(2 * MiB); // same as native_java

    RustPanama(Path lib, boolean discard) {
      this.client = new PanamaGcsClient(lib);
      this.discard = discard;
    }

    @Override
    public String name() {
      return "rust_panama";
    }

    @Override
    public BlobId singleShot(BlobId id, byte[] data, int length) {
      client.write(id.getBucket(), id.getName(), slice(data, length), false);
      return id;
    }

    @Override
    public BlobId resumable(BlobId id, byte[] data, int length) {
      client.write(id.getBucket(), id.getName(), slice(data, length), true);
      return id;
    }

    @Override
    public long read(BlobId id) throws Exception {
      if (discard) {
        return client.readDiscard(id.getBucket(), id.getName(), 0);
      }
      return client.read(id.getBucket(), id.getName(), 0, copyBuffer, W1R3Benchmark::drain);
    }

    @Override
    public void delete(BlobId id) {
      client.delete(id.getBucket(), id.getName(), 0);
    }

    @Override
    public long rustAllocatedBytes() {
      return client.rustAllocatedBytes();
    }

    @Override
    public long[] rustThreadStats() {
      return client.rustThreadStats();
    }

    @Override
    public void close() {
      client.close();
    }
  }

  /**
   * Sink for the Rust streaming reads: empties the filled 2 MiB buffer into the discarding
   * channel, exactly as {@code native_java} does after each fill.
   */
  private static void drain(ByteBuffer filled) throws java.io.IOException {
    StorageChannelUtils.blockingEmptyTo(filled, NullChannel.INSTANCE);
  }

  /**
   * The Rust bridges take a whole {@code byte[]}; per-size payloads are pre-built (outside the
   * measurement) so this is normally the same array.
   */
  private static byte[] slice(byte[] data, int length) {
    return data.length == length ? data : Arrays.copyOf(data, length);
  }

  // ------------------------------------------------------------------------------------------
  // Runner
  // ------------------------------------------------------------------------------------------

  /** One op, measured the same way for every implementation ({@link ProcessMetrics}). */
  record Sample(
      int iteration,
      long objectSize,
      String op,
      double latencyS,
      double procCpuNsPerByte,
      double javaAllocBytesPerByte,
      double rustAllocBytesPerByte,
      double peakRssMiB,
      double peakRssGrowthMiB,
      double opWallS,
      double rustSdkS,
      double ffiCalls,
      boolean ok,
      String error) {}

  private final Target target;
  private final String bucket;
  private final String prefix;
  private final String runId = Long.toString(System.currentTimeMillis());
  private final PrintWriter csv;
  private final List<Sample> samples = new ArrayList<>();
  private final ProcessMetrics metrics;
  /** False during warm-up: ops run and are measured, but nothing is recorded. */
  private boolean recording = true;

  private W1R3Benchmark(Target target, String bucket, String prefix, PrintWriter csv) {
    this.target = target;
    this.bucket = bucket;
    this.prefix = prefix;
    this.csv = csv;
    this.metrics = new ProcessMetrics(target::rustAllocatedBytes, target::rustThreadStats);
    if (!metrics.resetPeakRss()) {
      System.err.println(
          "WARN: can't reset peak RSS (/proc/self/clear_refs); peak_rss_mib is the process peak");
    }
    csv.println(
        "impl,workload,iteration,object_size,op,latency_s,"
            + "proc_cpu_ns_per_byte,java_alloc_bytes_per_byte,"
            + "rust_alloc_bytes_per_byte,peak_rss_mib,peak_rss_growth_mib,op_wall_s,rust_sdk_s,"
            + "bridge_s,ffi_calls,ok,error");
  }

  private void record(Sample s) {
    if (!recording) {
      if (!s.ok()) {
        System.err.printf("  WARMUP ERROR %s #%d %s: %s%n", WORKLOAD, s.iteration(), s.op(), s.error());
      }
      return;
    }
    String err = s.error() == null ? "" : s.error();
    String shortErr =
        err.substring(0, Math.min(200, err.length())).replace(',', ';').replace('\n', ' ');
    csv.printf(
        "%s,%s,%d,%d,%s,%.6f,%.4f,%.4f,%.4f,%.2f,%.2f,%.6f,%.6f,%.6f,%.0f,%d,%s%n",
        target.name(),
        WORKLOAD,
        s.iteration(),
        s.objectSize(),
        s.op(),
        s.latencyS(),
        s.procCpuNsPerByte(),
        s.javaAllocBytesPerByte(),
        s.rustAllocBytesPerByte(),
        s.peakRssMiB(),
        s.peakRssGrowthMiB(),
        s.opWallS(),
        s.rustSdkS(),
        s.opWallS() - s.rustSdkS(), // NaN for native_java (no Rust SDK time)
        s.ffiCalls(),
        s.ok() ? 1 : 0,
        shortErr);
    csv.flush();
    if (!s.ok()) {
      System.err.printf("  ERROR %s #%d %s: %s%n", WORKLOAD, s.iteration(), s.op(), err);
    }
    samples.add(s);
  }

  private static Sample failed(int i, long size, String op, String error) {
    double nan = Double.NaN;
    return new Sample(i, size, op, nan, nan, nan, nan, nan, nan, nan, nan, nan, false, error);
  }

  /** One measured operation. */
  @FunctionalInterface
  interface Op<T> {
    T run() throws Exception;
  }

  /** Measures one op with {@link ProcessMetrics}; returns its result or null on failure. */
  private <T> T measure(int i, long size, String op, Op<T> body) {
    try {
      // RSS reads/reset first and last, outside the CPU/alloc snapshots.
      long rssBefore = metrics.rssBytes();
      metrics.resetPeakRss();
      ProcessMetrics.Snapshot b = metrics.snapshot();
      T r = body.run();
      ProcessMetrics.Snapshot a = metrics.snapshot();
      long peakRss = metrics.peakRssBytes();
      double n = size;
      double wallS = (a.wallNanos() - b.wallNanos()) / 1e9;
      record(
          new Sample(
              i,
              size,
              op,
              wallS,
              (a.cpuNanos() - b.cpuNanos()) / n,
              (a.javaAlloc() - b.javaAlloc()) / n,
              (a.rustAlloc() - b.rustAlloc()) / n,
              peakRss / (double) MiB,
              Math.max(0, peakRss - rssBefore) / (double) MiB,
              wallS,
              b.sdkNanos() < 0 ? Double.NaN : (a.sdkNanos() - b.sdkNanos()) / 1e9,
              b.ffiCalls() < 0 ? Double.NaN : (double) (a.ffiCalls() - b.ffiCalls()),
              true,
              null));
      return r;
    } catch (Throwable t) {
      record(failed(i, size, op, t.getClass().getSimpleName() + ": " + t.getMessage()));
      return null;
    }
  }

  /** One w1r3 iteration: upload, READ[0..2], delete (delete runs even if a read fails). */
  private void iteration(int i, long size, boolean resumable, byte[] data) {
    int len = Math.toIntExact(size);
    String tag = recording ? WORKLOAD : "warmup";
    BlobId name = BlobId.of(bucket, prefix + target.name() + "/" + runId + "/" + tag + "-" + i);
    BlobId id =
        resumable
            ? measure(i, size, "RESUMABLE", () -> target.resumable(name, data, len))
            : measure(i, size, "SINGLE-SHOT", () -> target.singleShot(name, data, len));
    if (id == null) {
      return;
    }
    for (int r = 0; r < 3; r++) {
      measure(
          i,
          size,
          "READ[" + r + "]",
          () -> {
            long n = target.read(id);
            if (n != size) { // sanity: every byte of the object was delivered
              throw new IllegalStateException("short read: " + n + " of " + size + " bytes");
            }
            return n;
          });
    }
    measure(
        i,
        size,
        "DELETE",
        () -> {
          target.delete(id);
          return Boolean.TRUE;
        });
  }

  // ------------------------------------------------------------------------------------------
  // main
  // ------------------------------------------------------------------------------------------

  public static void main(String[] argv) throws Exception {
    if (argv.length == 1 && argv[0].startsWith("--check-lib=")) {
      // Only checks that the Rust library loads here (glibc / libgcc_s / CPU match).
      System.load(Path.of(argv[0].substring("--check-lib=".length())).toAbsolutePath().toString());
      System.out.println("ok");
      return;
    }
    Map<String, String> args = parseArgs(argv);
    String impl = required(args, "impl");
    String bucket = required(args, "bucket");
    String project = args.getOrDefault("project", "");
    Path lib = Path.of(args.getOrDefault("lib", "libgcs_rust_ffi.so")).toAbsolutePath();
    int iterations = Integer.parseInt(args.getOrDefault("iterations", "200"));
    long[] sizes =
        Arrays.stream(args.getOrDefault("object-sizes", "100000,2097152,100000000").split(","))
            .map(String::trim)
            .mapToLong(Long::parseLong)
            .toArray();
    long seed = Long.parseLong(args.getOrDefault("seed", "42"));
    int warmup = Integer.parseInt(args.getOrDefault("warmup", "0"));
    String prefix = args.getOrDefault("prefix", "gcs-rust-poc/w1r3/");
    Path out = Path.of(args.getOrDefault("out", impl + "-" + WORKLOAD + ".csv"));

    // Random payload per size, built before anything is measured.
    long maxSize = Arrays.stream(sizes).max().orElseThrow();
    byte[] shared = new byte[Math.toIntExact(maxSize)];
    new Random(7).nextBytes(shared);
    Map<Long, byte[]> payloads = new HashMap<>();
    for (long s : sizes) {
      payloads.computeIfAbsent(s, k -> Arrays.copyOf(shared, Math.toIntExact(k)));
    }

    String readMode = args.getOrDefault("read-mode", "java");
    if (!readMode.equals("java") && !readMode.equals("discard")) {
      throw new IllegalArgumentException("--read-mode must be java or discard: " + readMode);
    }
    boolean discard = readMode.equals("discard");
    Target target =
        switch (impl) {
          case "native_java" -> new NativeJava(project);
          case "rust_jni" -> new RustJni(lib, discard);
          case "rust_panama" -> new RustPanama(lib, discard);
          default -> throw new IllegalArgumentException("unknown --impl=" + impl);
        };
    System.out.printf(
        "impl=%s bucket=%s iterations=%d warmup=%d sizes=%s read_mode=%s runtime=%s java=%s csv=%s%n",
        impl,
        bucket,
        iterations,
        warmup,
        Arrays.toString(sizes),
        impl.equals("native_java") ? "java" : readMode,
        System.getenv().getOrDefault("GCS_RUST_RUNTIME", "current_thread"),
        System.getProperty("java.version"),
        out);

    try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8));
        target) {
      W1R3Benchmark bench = new W1R3Benchmark(target, bucket, prefix, csv);
      if (warmup > 0) {
        // Same workload, own random sequence, nothing recorded: covers JIT, TLS handshakes,
        // token fetch and connection pool setup before the measured iterations.
        bench.recording = false;
        CppMt19937_64 warm = new CppMt19937_64(seed + 1);
        long w0 = System.nanoTime();
        for (int i = 0; i < warmup; i++) {
          long size = sizes[warm.nextIndex(sizes.length)];
          boolean resumable = warm.nextIndex(2) == 1;
          bench.iteration(i, size, resumable, payloads.get(size));
        }
        bench.recording = true;
        System.out.printf(
            "  warmup: %d iterations in %.1f s (not recorded)%n",
            warmup, (System.nanoTime() - w0) / 1e9);
      }
      CppMt19937_64 rng = new CppMt19937_64(seed);
      long t0 = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        long size = sizes[rng.nextIndex(sizes.length)]; // size first, then upload type
        boolean resumable = rng.nextIndex(2) == 1;
        bench.iteration(i, size, resumable, payloads.get(size));
      }
      System.out.printf(
          "  %s: %d iterations in %.1f s%n", WORKLOAD, iterations, (System.nanoTime() - t0) / 1e9);
      bench.printSummary();
    }
  }

  private void printSummary() {
    Map<String, List<Sample>> groups = new TreeMap<>();
    for (Sample s : samples) {
      groups.computeIfAbsent(s.objectSize() + "|" + s.op(), k -> new ArrayList<>()).add(s);
    }
    System.out.printf(
        "%nSUMMARY impl=%s%n%11s %-12s %5s %4s %10s %10s %14s %14s %14s%n",
        target.name(), "size", "op", "n", "err", "p50 ms", "p90 ms", "proc ns/B p50",
        "java B/B p50", "rust B/B p50");
    for (var e : groups.entrySet()) {
      List<Sample> v = e.getValue();
      List<Sample> ok = v.stream().filter(Sample::ok).toList();
      double[] lat = ok.stream().mapToDouble(s -> s.latencyS() * 1e3).toArray();
      double[] cpu = ok.stream().mapToDouble(Sample::procCpuNsPerByte).toArray();
      double[] jmem = ok.stream().mapToDouble(Sample::javaAllocBytesPerByte).toArray();
      double[] rmem = ok.stream().mapToDouble(Sample::rustAllocBytesPerByte).toArray();
      String[] key = e.getKey().split("\\|");
      System.out.printf(
          "%11s %-12s %5d %4d %10.2f %10.2f %14.2f %14.2f %14.2f%n",
          key[0],
          key[1],
          v.size(),
          v.size() - ok.size(),
          pct(lat, 50),
          pct(lat, 90),
          pct(cpu, 50),
          pct(jmem, 50),
          pct(rmem, 50));
    }
  }

  private static double pct(double[] v, double p) {
    if (v.length == 0) {
      return Double.NaN;
    }
    double[] s = v.clone();
    Arrays.sort(s);
    return s[Math.min((int) (p / 100.0 * (s.length - 1) + 0.5), s.length - 1)];
  }

  /** Accepts and discards everything. */
  private static final class NullChannel implements WritableByteChannel {
    static final NullChannel INSTANCE = new NullChannel();

    @Override
    public int write(ByteBuffer src) {
      int n = src.remaining();
      src.position(src.limit());
      return n;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }

  private static Map<String, String> parseArgs(String[] argv) {
    Map<String, String> out = new HashMap<>();
    for (String a : argv) {
      if (!a.startsWith("--") || !a.contains("=")) {
        throw new IllegalArgumentException("expected --key=value, got: " + a);
      }
      int eq = a.indexOf('=');
      out.put(a.substring(2, eq), a.substring(eq + 1));
    }
    return out;
  }

  private static String required(Map<String, String> args, String key) {
    String v = args.get(key);
    if (v == null || v.isEmpty()) {
      throw new IllegalArgumentException("missing --" + key + "=...");
    }
    return v;
  }
}
