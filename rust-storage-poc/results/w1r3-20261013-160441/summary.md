# w1r3 (JSON): Java SDK vs Rust SDK via JNI / Panama

**12,000 measurements, 0 errors** (native_java: 4,000 / 0 err, rust_jni: 4,000 / 0 err, rust_panama: 4,000 / 0 err).

**Sanity checks:** all passed (Rust SDK time ≤ op time; native calls per upload as expected; no Rust SDK time on native_java; Rust allocations recorded).

```
run_id=20261013-160441 region=us-central1-a
iterations=200 rounds=4 impls=native_java rust_jni rust_panama object_sizes=100000,2097152,100000000
note: each round also ran two other reference programs (not part of this report), in the same balanced order
warmup=10 read_mode=java GCS_RUST_RUNTIME=current_thread GCS_RUST_RETRY=never (java: retries off)
jvm_flags=-Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=1g -Dio.grpc.netty.shaded.io.netty.maxDirectMemory=1073741824
openjdk version "25.0.4.1" 2026-08-18 LTS
OpenJDK Runtime Environment Temurin-25.0.4.1+1 (build 25.0.4.1+1-LTS)
OpenJDK 64-Bit Server VM Temurin-25.0.4.1+1 (build 25.0.4.1+1-LTS, mixed mode, sharing)
Linux 6.1.0-49-cloud-amd64 x86_64 (Debian 12)
nproc=2
machine-type=c4-standard-2
```

## 100 KB

**Latency** (ms; p50 per round in brackets)

| op | impl | n | err | p50 | p50 per round | p90 | p99 | mean | max | p50 vs native_java |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | native_java | 140 | 0 | 87.54 | 87.1–89.2 | 122.03 | 199.55 | 96.46 | 511.49 | 1.00x |
| SINGLE-SHOT | rust_jni | 140 | 0 | 93.41 | 85.4–95.9 | 142.22 | 1280.47 | 581.66 | 66298.92 | 1.07x |
| SINGLE-SHOT | rust_panama | 140 | 0 | 85.43 | 81.2–93.7 | 120.23 | 250.61 | 94.88 | 305.65 | 0.98x |
| RESUMABLE | native_java | 116 | 0 | 164.95 | 157.2–174.5 | 226.30 | 431.60 | 177.87 | 569.05 | 1.00x |
| RESUMABLE | rust_jni | 116 | 0 | 170.84 | 163.3–177.2 | 263.21 | 461.49 | 213.66 | 3565.64 | 1.04x |
| RESUMABLE | rust_panama | 116 | 0 | 164.85 | 158.2–167.6 | 242.73 | 622.76 | 186.51 | 840.65 | 1.00x |
| READ | native_java | 768 | 0 | 44.52 | 42.7–47.4 | 78.37 | 148.61 | 51.37 | 334.84 | 1.00x |
| READ | rust_jni | 768 | 0 | 44.37 | 42.0–48.4 | 86.03 | 228.40 | 58.67 | 2068.50 | 1.00x |
| READ | rust_panama | 768 | 0 | 43.85 | 41.9–46.8 | 75.07 | 165.46 | 50.71 | 687.48 | 0.98x |

**Cost per op** (whole-process CPU; exact Java heap + Rust allocations; RSS)

| op | impl | proc CPU ns/B p50 | CPU vs native_java | Java alloc B/B p50 | Rust alloc B/B p50 | total alloc B/B p50 | peak RSS MiB max | RSS growth MiB p50 | RSS growth MiB max |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | native_java | 26.57 | 1.00x | 1.55 | 0.00 | 1.55 | 1165 | 0.0 | 0.1 |
| SINGLE-SHOT | rust_jni | 3.42 | 0.13x | 0.00 | 2.67 | 2.67 | 1142 | 0.0 | 0.0 |
| SINGLE-SHOT | rust_panama | 4.11 | 0.15x | 0.01 | 2.67 | 2.68 | 1151 | 0.0 | 0.0 |
| RESUMABLE | native_java | 56.02 | 1.00x | 169.83 | 0.00 | 169.83 | 1165 | 0.0 | 0.1 |
| RESUMABLE | rust_jni | 4.10 | 0.07x | 0.00 | 2.83 | 2.83 | 1142 | 0.0 | 0.0 |
| RESUMABLE | rust_panama | 4.89 | 0.09x | 0.01 | 2.83 | 2.84 | 1151 | 0.0 | 0.0 |
| READ | native_java | 15.68 | 1.00x | 21.84 | 0.00 | 21.84 | 1165 | 0.0 | 1.5 |
| READ | rust_jni | 3.23 | 0.21x | 0.00 | 4.43 | 4.43 | 1142 | 0.0 | 3.5 |
| READ | rust_panama | 3.68 | 0.23x | 0.02 | 4.64 | 4.65 | 1151 | 0.0 | 0.4 |

**Bridge overhead** (op time − time inside the Rust SDK, same op)

| op | impl | op ms p50 | Rust SDK ms p50 | bridge ms p50 | bridge ms p90 | bridge % of op p50 | native calls/op |
|---|---|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | rust_jni | 93.41 | 93.36 | 0.049 | 0.063 | 0.1% | 1 |
| SINGLE-SHOT | rust_panama | 85.43 | 85.28 | 0.138 | 0.153 | 0.2% | 2 |
| RESUMABLE | rust_jni | 170.84 | 170.79 | 0.052 | 0.063 | 0.0% | 1 |
| RESUMABLE | rust_panama | 164.85 | 164.70 | 0.144 | 0.157 | 0.1% | 2 |
| READ | rust_jni | 44.37 | 44.31 | 0.050 | 0.067 | 0.1% | 4 |
| READ | rust_panama | 43.85 | 43.74 | 0.095 | 0.136 | 0.2% | 4 |

## 2 MiB

**Latency** (ms; p50 per round in brackets)

| op | impl | n | err | p50 | p50 per round | p90 | p99 | mean | max | p50 vs native_java |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | native_java | 164 | 0 | 113.39 | 108.4–119.1 | 164.37 | 253.82 | 124.42 | 593.49 | 1.00x |
| SINGLE-SHOT | rust_jni | 164 | 0 | 111.86 | 106.4–120.7 | 162.53 | 551.12 | 423.77 | 48615.83 | 0.99x |
| SINGLE-SHOT | rust_panama | 164 | 0 | 107.84 | 103.1–115.4 | 154.10 | 294.70 | 124.08 | 1152.59 | 0.95x |
| RESUMABLE | native_java | 128 | 0 | 191.48 | 182.6–201.9 | 300.73 | 799.46 | 248.38 | 4126.28 | 1.00x |
| RESUMABLE | rust_jni | 128 | 0 | 186.69 | 172.8–213.0 | 281.94 | 844.68 | 217.49 | 1387.27 | 0.97x |
| RESUMABLE | rust_panama | 128 | 0 | 191.97 | 168.1–203.8 | 303.66 | 711.13 | 239.04 | 2904.84 | 1.00x |
| READ | native_java | 876 | 0 | 57.72 | 55.2–63.7 | 101.80 | 295.49 | 67.82 | 593.37 | 1.00x |
| READ | rust_jni | 876 | 0 | 57.63 | 53.7–61.9 | 100.65 | 316.85 | 71.47 | 1618.06 | 1.00x |
| READ | rust_panama | 876 | 0 | 57.05 | 55.3–60.2 | 106.25 | 233.15 | 69.29 | 1031.29 | 0.99x |

**Cost per op** (whole-process CPU; exact Java heap + Rust allocations; RSS)

| op | impl | proc CPU ns/B p50 | CPU vs native_java | Java alloc B/B p50 | Rust alloc B/B p50 | total alloc B/B p50 | peak RSS MiB max | RSS growth MiB p50 | RSS growth MiB max |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | native_java | 2.32 | 1.00x | 0.13 | 0.00 | 0.13 | 1165 | 0.0 | 0.2 |
| SINGLE-SHOT | rust_jni | 0.76 | 0.33x | 0.00 | 2.03 | 2.03 | 1142 | 0.0 | 0.5 |
| SINGLE-SHOT | rust_panama | 0.81 | 0.35x | 0.00 | 2.03 | 2.03 | 1151 | 0.0 | 0.0 |
| RESUMABLE | native_java | 2.79 | 1.00x | 8.11 | 0.00 | 8.11 | 1165 | 0.0 | 0.1 |
| RESUMABLE | rust_jni | 0.79 | 0.28x | 0.00 | 2.04 | 2.04 | 1142 | 0.0 | 0.0 |
| RESUMABLE | rust_panama | 0.86 | 0.31x | 0.00 | 2.04 | 2.04 | 1151 | 0.0 | 0.0 |
| READ | native_java | 1.67 | 1.00x | 1.06 | 0.00 | 1.06 | 1165 | 0.0 | 0.1 |
| READ | rust_jni | 1.11 | 0.66x | 0.00 | 4.34 | 4.34 | 1142 | 0.0 | 4.9 |
| READ | rust_panama | 1.26 | 0.76x | 0.00 | 4.62 | 4.62 | 1151 | 0.0 | 6.7 |

**Bridge overhead** (op time − time inside the Rust SDK, same op)

| op | impl | op ms p50 | Rust SDK ms p50 | bridge ms p50 | bridge ms p90 | bridge % of op p50 | native calls/op |
|---|---|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | rust_jni | 111.86 | 111.46 | 0.384 | 0.409 | 0.3% | 1 |
| SINGLE-SHOT | rust_panama | 107.84 | 107.41 | 0.421 | 0.461 | 0.4% | 2 |
| RESUMABLE | rust_jni | 186.69 | 186.30 | 0.382 | 0.405 | 0.2% | 1 |
| RESUMABLE | rust_panama | 191.97 | 191.52 | 0.435 | 0.489 | 0.2% | 2 |
| READ | rust_jni | 57.63 | 57.31 | 0.252 | 0.297 | 0.4% | 4 |
| READ | rust_panama | 57.05 | 56.61 | 0.457 | 0.535 | 0.8% | 4 |

## 100 MB

**Latency** (ms; p50 per round in brackets)

| op | impl | n | err | p50 | p50 per round | p90 | p99 | mean | max | p50 vs native_java |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | native_java | 136 | 0 | 1240.36 | 1191.8–1355.3 | 2124.27 | 5665.08 | 1492.38 | 9990.85 | 1.00x |
| SINGLE-SHOT | rust_jni | 136 | 0 | 1303.80 | 1213.7–1348.3 | 2668.09 | 5140.92 | 1569.33 | 12998.56 | 1.05x |
| SINGLE-SHOT | rust_panama | 136 | 0 | 1196.61 | 1102.6–1322.3 | 2529.62 | 3731.71 | 1428.33 | 5612.86 | 0.96x |
| RESUMABLE | native_java | 116 | 0 | 1711.97 | 1381.1–2131.6 | 2857.11 | 8279.47 | 2069.10 | 17165.42 | 1.00x |
| RESUMABLE | rust_jni | 116 | 0 | 1294.87 | 1132.3–1344.3 | 2697.86 | 6137.97 | 1597.21 | 6648.16 | 0.76x |
| RESUMABLE | rust_panama | 116 | 0 | 1301.30 | 1126.7–1445.6 | 2837.80 | 6597.69 | 1655.44 | 12003.93 | 0.76x |
| READ | native_java | 756 | 0 | 524.60 | 497.7–555.8 | 1141.97 | 3087.15 | 702.76 | 12814.43 | 1.00x |
| READ | rust_jni | 756 | 0 | 513.55 | 437.5–594.6 | 1064.15 | 2995.25 | 805.41 | 100443.64 | 0.98x |
| READ | rust_panama | 756 | 0 | 543.56 | 520.1–589.0 | 1119.65 | 2848.53 | 683.91 | 5669.50 | 1.04x |

**Cost per op** (whole-process CPU; exact Java heap + Rust allocations; RSS)

| op | impl | proc CPU ns/B p50 | CPU vs native_java | Java alloc B/B p50 | Rust alloc B/B p50 | total alloc B/B p50 | peak RSS MiB max | RSS growth MiB p50 | RSS growth MiB max |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | native_java | 0.94 | 1.00x | 0.06 | 0.00 | 0.06 | 1165 | 0.0 | 0.3 |
| SINGLE-SHOT | rust_jni | 0.62 | 0.66x | 0.00 | 2.00 | 2.00 | 1238 | 95.4 | 95.6 |
| SINGLE-SHOT | rust_panama | 0.64 | 0.69x | 0.00 | 2.00 | 2.00 | 1246 | 95.4 | 95.4 |
| RESUMABLE | native_java | 0.76 | 1.00x | 0.18 | 0.00 | 0.18 | 1165 | 0.0 | 3.2 |
| RESUMABLE | rust_jni | 0.62 | 0.81x | 0.00 | 2.00 | 2.00 | 1238 | 95.4 | 95.7 |
| RESUMABLE | rust_panama | 0.65 | 0.86x | 0.00 | 2.00 | 2.00 | 1247 | 95.4 | 95.6 |
| READ | native_java | 0.78 | 1.00x | 0.04 | 0.00 | 0.04 | 1165 | 0.0 | 0.2 |
| READ | rust_jni | 0.87 | 1.11x | 0.00 | 4.35 | 4.35 | 1142 | 0.0 | 0.3 |
| READ | rust_panama | 0.96 | 1.22x | 0.00 | 4.62 | 4.62 | 1151 | 0.0 | 0.1 |

**Bridge overhead** (op time − time inside the Rust SDK, same op)

| op | impl | op ms p50 | Rust SDK ms p50 | bridge ms p50 | bridge ms p90 | bridge % of op p50 | native calls/op |
|---|---|--:|--:|--:|--:|--:|--:|
| SINGLE-SHOT | rust_jni | 1303.80 | 1281.09 | 22.839 | 23.276 | 1.8% | 1 |
| SINGLE-SHOT | rust_panama | 1196.61 | 1172.37 | 24.506 | 24.949 | 2.1% | 2 |
| RESUMABLE | rust_jni | 1294.87 | 1272.15 | 22.861 | 23.236 | 1.8% | 1 |
| RESUMABLE | rust_panama | 1301.30 | 1276.59 | 24.579 | 25.417 | 1.9% | 2 |
| READ | rust_jni | 513.55 | 509.33 | 4.277 | 4.679 | 0.8% | 51 |
| READ | rust_panama | 543.56 | 535.01 | 8.616 | 9.147 | 1.6% | 51 |

## Whole processes (timed_run.py, all rounds)

| impl | wall s | CPU s (user+sys) | max RSS MiB | failed runs |
|---|--:|--:|--:|--:|
| native_java | 1428 | 270 | 1167 | 0 |
| rust_jni | 1530 | 162 | 1146 | 0 |
| rust_panama | 1296 | 180 | 1153 | 0 |

## Notes

- Latency is measured around each call in the same harness for all three.
- DELETE is not in the tables (Rust: gRPC StorageControl; Java: JSON).
- RSS is dominated by the 1 GiB pre-touched Java heap; RSS growth shows memory held by the op.
