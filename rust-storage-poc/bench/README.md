# Benchmark: w1r3 (JSON), Java SDK vs Rust SDK via JNI / Panama

Compares the Java Storage SDK with the Rust Storage SDK called from Java (JNI and Panama), using the
`w1r3` workload from [storage-shared-benchmarking](https://github.com/googleapis/storage-shared-benchmarking).
Scope: JSON transport, w1r3 only.

## What runs (one at a time, same VM, same bucket, order balanced forward/reversed across rounds)

| impl | what it is |
|---|---|
| `native_java` | java-storage (`StorageOptions.http()`, retries off) |
| `rust_jni` | Rust Storage SDK via JNI (`current_thread` Tokio runtime, retries off) |
| `rust_panama` | Rust Storage SDK via Panama (same Rust code as `rust_jni`) |

All three run the same harness (`W1R3Benchmark`), with the same JVM flags
(`-Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=1g`) and the same object-size sequence.

### The w1r3 workload
Per iteration: object size picked from 100 KB / 2 MiB / 100 MB; upload SINGLE-SHOT or RESUMABLE
(50/50); `READ[0..2]`; DELETE.

| op | `native_java` | `rust_jni`, `rust_panama` |
|---|---|---|
| SINGLE-SHOT | `create(info, bytes, off, len, disableGzipContent())` | `write_object(..).set_if_generation_match(0).with_resumable_upload_threshold(size+1).send_unbuffered()` |
| RESUMABLE | `writer(info, doesNotExist())`, write all, `close()` | same, threshold 0 |
| READ[0..2] | `reader(id)` copied through a 2 MiB buffer and discarded | `read_object().send()`, streamed into the same 2 MiB Java buffer |
| DELETE | `delete(id)` (JSON) | `StorageControl.delete_object` (gRPC; not comparable) |

### Metrics (per op, measured the same way for all three)
latency; whole-process CPU per byte (`clock_gettime(CLOCK_PROCESS_CPUTIME_ID)`); exact Java heap
and Rust allocated bytes per byte; peak RSS and RSS growth; time inside the Rust SDK, so bridge
time = op time − SDK time; number of Java → Rust calls.

## Files

| file | purpose |
|---|---|
| `run_w1r3.sh` | runs everything and writes `summary.md` |
| `summarize_w1r3.py` | summary tables and sanity checks |
| `plot_w1r3.py` | graphs (needs matplotlib + numpy) |
| `timed_run.py` | per-process wall / CPU / max RSS |
| `build_native.sh`, `measure_package_size.sh` | build the Rust library; measure package sizes |

## Run on a VM (same region as the bucket)

Put these in one directory on the VM: `run_w1r3.sh`, `summarize_w1r3.py`, `timed_run.py`,
`rust-storage-poc-bench.jar` (from `java/`, `mvn package`) and `libgcs_rust_ffi.so` (from
`rust-ffi/`, built for glibc ≥ 2.17).

```bash
export JAVA=<path to JDK 22+>/bin/java BUCKET=<bucket>
# quick check (~2 min)
ITERATIONS=3 ROUNDS=2 OBJECT_SIZES=100000 ./run_w1r3.sh
# full run (200 iterations x 4 rounds x 3 impls), in the background
nohup ./run_w1r3.sh > w1r3.log 2>&1 &
# graphs
python3 plot_w1r3.py results/w1r3-<run-id> results/w1r3-<run-id>/graphs
```

Needs: Java 22+ (for Panama), python3, ~300 MB free disk, and Application Default Credentials that
can write to the bucket (`gcloud auth application-default login`).
Results: `results/w1r3-<run-id>/summary.md`.
