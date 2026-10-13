package com.google.cloud.storage.rustpoc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Per-operation metrics measured the same way for every implementation:
 *
 * <ul>
 *   <li><b>Process CPU</b>: {@code clock_gettime(CLOCK_PROCESS_CPUTIME_ID)}, i.e. user+sys CPU of
 *       every thread in the process (JVM threads, Tokio workers/blocking pool, any native thread).
 *       (JVM thread-CPU APIs only see threads the JVM knows about.)
 *   <li><b>Java allocated bytes</b>: {@code getTotalThreadAllocatedBytes()}, exact bytes allocated
 *       on the Java heap by all live Java threads (no dependency on GC events).
 *   <li><b>Rust allocated bytes</b>: the counting global allocator in {@code libgcs_rust_ffi.so}
 *       (0 for {@code native_java}, which doesn't load the library).
 * </ul>
 */
final class ProcessMetrics {
  private static final int CLOCK_PROCESS_CPUTIME_ID = 2; // Linux <time.h>

  private static final MethodHandle CLOCK_GETTIME;
  private static final com.sun.management.ThreadMXBean THREADS;

  static {
    Linker linker = Linker.nativeLinker();
    // int clock_gettime(clockid_t, struct timespec*)
    CLOCK_GETTIME =
        linker.downcallHandle(
            linker.defaultLookup().find("clock_gettime").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!THREADS.isThreadAllocatedMemoryEnabled()) {
      THREADS.setThreadAllocatedMemoryEnabled(true);
    }
  }

  private final MemorySegment timespec = Arena.global().allocate(16, 8); // {i64 sec, i64 nsec}
  private final LongSupplier rustAllocatedBytes;
  private final Supplier<long[]> rustThreadStats;

  /**
   * @param rustThreadStats {SDK nanoseconds, Java -> Rust calls} of the calling thread, or a
   *     supplier returning null when the implementation doesn't use Rust.
   */
  ProcessMetrics(LongSupplier rustAllocatedBytes, Supplier<long[]> rustThreadStats) {
    this.rustAllocatedBytes = rustAllocatedBytes;
    this.rustThreadStats = rustThreadStats;
  }

  /** User+sys CPU nanoseconds consumed by all threads of this process. */
  long processCpuNanos() {
    try {
      int rc = (int) CLOCK_GETTIME.invokeExact(CLOCK_PROCESS_CPUTIME_ID, timespec);
      if (rc != 0) {
        throw new IllegalStateException("clock_gettime failed: " + rc);
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
    return timespec.get(ValueLayout.JAVA_LONG, 0) * 1_000_000_000L
        + timespec.get(ValueLayout.JAVA_LONG, 8);
  }

  long javaAllocatedBytes() {
    return THREADS.getTotalThreadAllocatedBytes();
  }

  long rustAllocatedBytes() {
    return rustAllocatedBytes.getAsLong();
  }

  /**
   * Snapshot taken right before / after the measured call. {@code sdkNanos} / {@code ffiCalls}
   * are -1 for implementations that don't use Rust.
   */
  record Snapshot(
      long wallNanos, long cpuNanos, long javaAlloc, long rustAlloc, long sdkNanos, long ffiCalls) {}

  Snapshot snapshot() {
    long[] rust = rustThreadStats.get();
    return new Snapshot(
        System.nanoTime(),
        processCpuNanos(),
        javaAllocatedBytes(),
        rustAllocatedBytes(),
        rust == null ? -1 : rust[0],
        rust == null ? -1 : rust[1]);
  }

  // --- RSS (what the Go/Python/C++ PoCs report) -----------------------------------------------

  private static final Path STATUS = Path.of("/proc/self/status");
  private static final Path CLEAR_REFS = Path.of("/proc/self/clear_refs");
  private boolean peakResetWorks = true;

  /** Current RSS in bytes ({@code VmRSS}). */
  long rssBytes() {
    return statusKiB("VmRSS:") * 1024;
  }

  /** Peak RSS in bytes since the last {@link #resetPeakRss()} ({@code VmHWM}). */
  long peakRssBytes() {
    return statusKiB("VmHWM:") * 1024;
  }

  /**
   * Resets the kernel's peak-RSS counter to the current RSS (writes {@code 5} to {@code
   * /proc/self/clear_refs}, Linux 4.0+). Returns false if the kernel doesn't allow it, in which
   * case {@link #peakRssBytes()} is the process-lifetime peak.
   */
  boolean resetPeakRss() {
    if (peakResetWorks) {
      try {
        Files.writeString(CLEAR_REFS, "5");
      } catch (IOException | UnsupportedOperationException e) {
        peakResetWorks = false;
      }
    }
    return peakResetWorks;
  }

  private static long statusKiB(String key) {
    try {
      for (String line : Files.readAllLines(STATUS)) {
        if (line.startsWith(key)) {
          return Long.parseLong(line.substring(key.length()).trim().split("\\s+")[0]);
        }
      }
    } catch (IOException e) {
      // fall through
    }
    return -1;
  }
}
