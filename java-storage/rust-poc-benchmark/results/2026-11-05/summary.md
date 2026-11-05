## LIST_BUCKETS

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=-

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | items/s | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 103 | 1.03 | 974.2 | 1,055.2 | 2,162.2 | 0.0 | 409 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |
| rust-default | 1 | 0 | 109 | 1.09 | 965.2 | 994.9 | 1,025.0 | 0.0 | 315 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |

<details><summary>Every run</summary>

| config | run | ops | errors | items/s | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | list-buckets-bidi-x-java-default-2026-11-05T03-45-27.613116859Z | 186 | 0 | 103 | 1.03 | 974.2 | 1,055.2 | 2,162.2 | 0.0 | 409 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |  |
| rust-default | list-buckets-bidi-x-rust-default-2026-11-05T03-49-38.819966371Z | 196 | 0 | 109 | 1.09 | 965.2 | 994.9 | 1,025.0 | 0.0 | 315 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |  |

</details>

## LIST_OBJECTS

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | items/s | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 1,590 | 15.90 | 61.0 | 68.5 | 129.0 | 0.0 | 430 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |
| rust-default | 1 | 0 | 1,635 | 16.35 | 59.4 | 66.4 | 110.8 | 0.0 | 309 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |

<details><summary>Every run</summary>

| config | run | ops | errors | items/s | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | list-objects-bidi-x-java-default-2026-11-05T03-41-15.874464028Z | 2,862 | 0 | 1,590 | 15.90 | 61.0 | 68.5 | 129.0 | 0.0 | 430 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |  |
| rust-default | list-objects-bidi-x-rust-default-2026-11-05T03-37-04.687103216Z | 2,944 | 0 | 1,635 | 16.35 | 59.4 | 66.4 | 110.8 | 0.0 | 309 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |  |

</details>

## OPEN

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | items/s | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 11 | 10.61 | 90.8 | 107.6 | 144.0 | 0.0 | 379 | 0.2 | 0.8 | 1 | 0.00 | 0.00 |
| rust-default | 1 | 0 | 15 | 14.98 | 55.9 | 74.0 | 206.7 | 0.0 | 349 | 0.1 | 0.8 | 193 | 0.00 | 0.00 |

<details><summary>Every run</summary>

| config | run | ops | errors | items/s | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | open-bidi-x-java-default-2026-11-05T01-44-45.624614362Z | 1,909 | 0 | 11 | 10.61 | 90.8 | 107.6 | 144.0 | 0.0 | 379 | 0.2 | 0.8 | 1 | 0.00 | 0.00 |  |
| rust-default | open-bidi-x-rust-default-2026-11-05T01-48-57.468070259Z | 2,697 | 0 | 15 | 14.98 | 55.9 | 74.0 | 206.7 | 0.0 | 349 | 0.1 | 0.8 | 193 | 0.00 | 0.00 |  |

</details>

## RANDOM8K_BIDI

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 0.2 | 0.00 | 24.11 | 41.4 | 44.0 | 47.3 | 0.0 | 383 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |
| rust-default | 1 | 0 | 0.2 | 0.00 | 25.90 | 37.7 | 40.5 | 52.1 | 0.0 | 355 | 0.1 | 0.8 | 34 | 0.00 | 0.00 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | random8k-bidi-x-java-default-2026-11-05T02-31-06.895680787Z | 4,340 | 0 | 0.2 | 0.00 | 24.11 | 41.4 | 44.0 | 47.3 | 0.0 | 383 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |  |
| rust-default | random8k-bidi-x-rust-default-2026-11-05T02-26-54.696237835Z | 4,662 | 0 | 0.2 | 0.00 | 25.90 | 37.7 | 40.5 | 52.1 | 0.0 | 355 | 0.1 | 0.8 | 34 | 0.00 | 0.00 |  |

</details>

## RANDOM8K_JSON

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 0.1 | 0.00 | 16.52 | 59.2 | 66.2 | 90.8 | 0.0 | 1,290 | 0.1 | 0.9 | 1 | 0.00 | 0.00 |
| rust-default | 1 | 0 | 0.1 | 0.00 | 15.82 | 59.4 | 69.7 | 173.8 | 0.0 | 258 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | random8k-json-x-java-default-2026-11-05T02-35-18.936706177Z | 2,973 | 0 | 0.1 | 0.00 | 16.52 | 59.2 | 66.2 | 90.8 | 0.0 | 1,290 | 0.1 | 0.9 | 1 | 0.00 | 0.00 |  |
| rust-default | random8k-json-x-rust-default-2026-11-05T02-39-29.711311800Z | 2,848 | 0 | 0.1 | 0.00 | 15.82 | 59.4 | 69.7 | 173.8 | 0.0 | 258 | 0.1 | 0.8 | 1 | 0.00 | 0.00 |  |

</details>

## BIDI_READ

threads=48, measured=300 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 300.6 | 2.40 | 17.92 | 2,677.9 | 2,899.0 | 3,118.8 | 0.7 | 6,462 | 0.8 | 1.5 | 1 | 2.55 | 0.01 |
| rust-default | 1 | 0 | 1,470.7 | 11.77 | 87.66 | 541.8 | 588.1 | 682.9 | 2.1 | 16,928 | 3.2 | 3.2 | 64 | 12.47 | 0.08 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | read-bidi-x-java-default-2026-11-05T02-50-14.173424092Z | 5,376 | 0 | 300.6 | 2.40 | 17.92 | 2,677.9 | 2,899.0 | 3,118.8 | 0.7 | 6,462 | 0.8 | 1.5 | 1 | 2.55 | 0.01 |  |
| rust-default | read-bidi-x-rust-default-2026-11-05T02-43-40.546634874Z | 26,301 | 0 | 1,470.7 | 11.77 | 87.66 | 541.8 | 588.1 | 682.9 | 2.1 | 16,928 | 3.2 | 3.2 | 64 | 12.47 | 0.08 |  |

</details>

## READ_JSON

threads=48, measured=300 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 2,688.5 | 21.51 | 160.25 | 270.5 | 442.8 | 518.8 | 2.2 | 8,544 | 3.5 | 2.0 | 48 | 22.78 | 0.15 |
| rust-default | 1 | 0 | 2,731.1 | 21.85 | 162.79 | 265.8 | 437.9 | 520.2 | 3.6 | 1,543 | 5.3 | 0.9 | 49 | 23.14 | 0.14 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | read-json-x-java-default-2026-11-05T02-56-47.440743374Z | 48,076 | 0 | 2,688.5 | 21.51 | 160.25 | 270.5 | 442.8 | 518.8 | 2.2 | 8,544 | 3.5 | 2.0 | 48 | 22.78 | 0.15 |  |
| rust-default | read-json-x-rust-default-2026-11-05T03-03-19.172700144Z | 48,838 | 0 | 2,731.1 | 21.85 | 162.79 | 265.8 | 437.9 | 520.2 | 3.6 | 1,543 | 5.3 | 0.9 | 49 | 23.14 | 0.14 |  |

</details>

## READ50X2X4_BIDI_CONCURRENT

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 25.6 | 0.20 | 0.24 | 3,549.4 | 5,800.1 | 10,174.9 | 0.0 | 1,332 | 0.1 | 1.0 | 1 | 0.22 | 0.00 |
| rust-default | 1 | 0 | 32.0 | 0.26 | 0.31 | 3,296.7 | 3,383.8 | 3,416.0 | 0.0 | 2,433 | 0.1 | 1.1 | 51 | 0.27 | 0.00 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | read50x2x4-bidi-concurrent-java-default-2026-11-05T01-57-23.457295858Z | 44 | 0 | 25.6 | 0.20 | 0.24 | 3,549.4 | 5,800.1 | 10,174.9 | 0.0 | 1,332 | 0.1 | 1.0 | 1 | 0.22 | 0.00 |  |
| rust-default | read50x2x4-bidi-concurrent-rust-default-2026-11-05T01-53-09.450785127Z | 55 | 0 | 32.0 | 0.26 | 0.31 | 3,296.7 | 3,383.8 | 3,416.0 | 0.0 | 2,433 | 0.1 | 1.1 | 51 | 0.27 | 0.00 |  |

</details>

## READ50X2X4_BIDI_SERIAL

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 23.9 | 0.19 | 0.23 | 4,349.0 | 4,426.4 | 4,557.1 | 0.0 | 388 | 0.1 | 0.8 | 1 | 0.20 | 0.00 |
| rust-default | 1 | 0 | 23.9 | 0.19 | 0.23 | 4,442.2 | 4,575.4 | 4,680.1 | 0.0 | 353 | 0.1 | 0.8 | 60 | 0.20 | 0.00 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | read50x2x4-bidi-serial-java-default-2026-11-05T02-01-35.937244902Z | 41 | 0 | 23.9 | 0.19 | 0.23 | 4,349.0 | 4,426.4 | 4,557.1 | 0.0 | 388 | 0.1 | 0.8 | 1 | 0.20 | 0.00 |  |
| rust-default | read50x2x4-bidi-serial-rust-default-2026-11-05T02-05-50.065876634Z | 41 | 0 | 23.9 | 0.19 | 0.23 | 4,442.2 | 4,575.4 | 4,680.1 | 0.0 | 353 | 0.1 | 0.8 | 60 | 0.20 | 0.00 |  |

</details>

## READ50X2X4_JSON_CONCURRENT

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 139.8 | 1.12 | 1.33 | 692.9 | 1,040.3 | 1,428.7 | 0.3 | 4,252 | 0.6 | 1.4 | 6 | 1.20 | 0.01 |
| rust-default | 1 | 0 | 300.0 | 2.40 | 2.86 | 341.6 | 532.1 | 1,111.5 | 0.5 | 481 | 0.8 | 0.8 | 50 | 2.55 | 0.02 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | read50x2x4-json-concurrent-java-default-2026-11-05T02-14-16.230180195Z | 240 | 0 | 139.8 | 1.12 | 1.33 | 692.9 | 1,040.3 | 1,428.7 | 0.3 | 4,252 | 0.6 | 1.4 | 6 | 1.20 | 0.01 |  |
| rust-default | read50x2x4-json-concurrent-rust-default-2026-11-05T02-10-05.113737405Z | 515 | 0 | 300.0 | 2.40 | 2.86 | 341.6 | 532.1 | 1,111.5 | 0.5 | 481 | 0.8 | 0.8 | 50 | 2.55 | 0.02 |  |

</details>

## READ50X2X4_JSON_SERIAL

threads=1, measured=180 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 14.6 | 0.12 | 0.14 | 6,935.3 | 8,708.7 | 14,804.7 | 0.0 | 1,390 | 0.1 | 0.9 | 1 | 0.12 | 0.00 |
| rust-default | 1 | 0 | 13.4 | 0.11 | 0.13 | 7,175.4 | 11,129.9 | 16,633.6 | 0.0 | 351 | 0.1 | 0.8 | 1 | 0.11 | 0.00 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | read50x2x4-json-serial-java-default-2026-11-05T02-18-27.619635068Z | 25 | 0 | 14.6 | 0.12 | 0.14 | 6,935.3 | 8,708.7 | 14,804.7 | 0.0 | 1,390 | 0.1 | 0.9 | 1 | 0.12 | 0.00 |  |
| rust-default | read50x2x4-json-serial-rust-default-2026-11-05T02-22-41.497863803Z | 23 | 0 | 13.4 | 0.11 | 0.13 | 7,175.4 | 11,129.9 | 16,633.6 | 0.0 | 351 | 0.1 | 0.8 | 1 | 0.11 | 0.00 |  |

</details>

## BIDI_APPENDABLE_WRITE

threads=48, measured=300 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-zonal-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 343.5 | 2.75 | 1.68 | 27,257.7 | 32,852.9 | 37,706.1 | 23.5 | 19,075 | 22.7 | 3.7 | 1 | 0.01 | 2.80 |
| rust-default | 1 | 0 | 4,594.4 | 36.76 | 22.95 | 1,829.7 | 2,505.7 | 5,677.2 | 3.0 | 10,235 | 4.1 | 2.5 | 192 | 0.56 | 36.94 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | write-bidi-x-java-default-2026-11-05T03-16-53.850680479Z | 504 | 0 | 343.5 | 2.75 | 1.68 | 27,257.7 | 32,852.9 | 37,706.1 | 23.5 | 19,075 | 22.7 | 3.7 | 1 | 0.01 | 2.80 |  |
| rust-default | write-bidi-x-rust-default-2026-11-05T03-09-51.052952767Z | 6,886 | 0 | 4,594.4 | 36.76 | 22.95 | 1,829.7 | 2,505.7 | 5,677.2 | 3.0 | 10,235 | 4.1 | 2.5 | 192 | 0.56 | 36.94 |  |

</details>

## WRITE_JSON

threads=48, measured=300 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-default | 1 | 0 | 1,588.2 | 12.71 | 7.88 | 5,810.0 | 6,841.5 | 7,474.2 | 0.7 | 2,885 | 1.2 | 1.2 | 48 | 0.21 | 12.77 |
| rust-default | 1 | 0 | 1,646.2 | 13.17 | 8.22 | 5,710.1 | 6,426.1 | 7,122.8 | 0.7 | 1,509 | 1.1 | 1.1 | 48 | 0.23 | 13.21 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-default | write-json-x-java-default-2026-11-05T03-23-42.117555585Z | 2,364 | 0 | 1,588.2 | 12.71 | 7.88 | 5,810.0 | 6,841.5 | 7,474.2 | 0.7 | 2,885 | 1.2 | 1.2 | 48 | 0.21 | 12.77 |  |
| rust-default | write-json-x-rust-default-2026-11-05T03-30-21.839120050Z | 2,467 | 0 | 1,646.2 | 13.17 | 8.22 | 5,710.1 | 6,426.1 | 7,122.8 | 0.7 | 1,509 | 1.1 | 1.1 | 48 | 0.23 | 13.21 |  |

</details>

