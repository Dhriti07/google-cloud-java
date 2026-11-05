## BIDI_READ

threads=48, measured=300 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-48conn | 1 | 0 | 1,467.2 | 11.74 | 87.45 | 546.2 | 581.4 | 638.8 | 2.1 | 5,029 | 2.8 | 1.5 | 48 | 12.44 | 0.07 |
| rust-48conn | 1 | 0 | 1,475.1 | 11.80 | 87.92 | 542.1 | 584.1 | 652.7 | 2.1 | 31,412 | 3.1 | 5.2 | 49 | 12.51 | 0.07 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-48conn | read-bidi-x-java-48conn-2026-11-05T08-20-28.333278733Z | 26,237 | 0 | 1,467.2 | 11.74 | 87.45 | 546.2 | 581.4 | 638.8 | 2.1 | 5,029 | 2.8 | 1.5 | 48 | 12.44 | 0.07 |  |
| rust-48conn | read-bidi-x-rust-48conn-2026-11-05T08-27-01.863767290Z | 26,379 | 0 | 1,475.1 | 11.80 | 87.92 | 542.1 | 584.1 | 652.7 | 2.1 | 31,412 | 3.1 | 5.2 | 49 | 12.51 | 0.07 |  |

</details>

## BIDI_APPENDABLE_WRITE

threads=48, measured=300 s per run, machine=c4-standard-192 (192 vCPU), zone=us-central1-f, bucket=dhriti-zonal-read-west1

Median over rounds (min..max in brackets for the first column).

| config | rounds | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| java-48conn | 1 | 0 | 6,033.0 | 48.26 | 30.13 | 1,171.2 | 1,905.5 | 11,412.5 | 26.0 | 22,638 | 25.4 | 4.1 | 48 | 0.73 | 50.61 |
| rust-48conn | 1 | 0 | 4,741.0 | 37.93 | 23.73 | 1,770.0 | 2,967.5 | 7,355.9 | 2.9 | 10,211 | 4.2 | 2.7 | 48 | 0.58 | 38.16 |

<details><summary>Every run</summary>

| config | run | ops | errors | MB/s | Gbps | ops/s | p50 ms | p90 ms | p99 ms | proc CPU % | peak RSS MiB | VM CPU % mean | VM mem % peak | TCP :443 | NIC rx Gbps | NIC tx Gbps | first error |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| java-48conn | write-bidi-x-java-48conn-2026-11-05T08-40-17.240605744Z | 9,041 | 0 | 6,033.0 | 48.26 | 30.13 | 1,171.2 | 1,905.5 | 11,412.5 | 26.0 | 22,638 | 25.4 | 4.1 | 48 | 0.73 | 50.61 |  |
| rust-48conn | write-bidi-x-rust-48conn-2026-11-05T08-33-35.916756808Z | 7,118 | 0 | 4,741.0 | 37.93 | 23.73 | 1,770.0 | 2,967.5 | 7,355.9 | 2.9 | 10,211 | 4.2 | 2.7 | 48 | 0.58 | 38.16 |  |

</details>

