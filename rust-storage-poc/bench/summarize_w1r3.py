#!/usr/bin/env python3
"""Summary tables for a w1r3 results dir (native_java vs Rust via JNI / Panama).

usage: summarize_w1r3.py <results_dir> > <results_dir>/summary.md

Inputs: r<N>-{native_java,rust_jni,rust_panama}.csv (one row per op, from W1R3Benchmark) and
r<N>-<impl>.proc.json (process wall / CPU / max RSS, from timed_run.py).

Per object size it prints three tables:
  latency       p50 (and p50 of each round), p90, p99, mean, max
  cost          whole-process CPU ns per byte, Java + Rust allocated bytes per byte, RSS
  bridge        op time minus time inside the Rust SDK (network time cancels out)
plus sanity checks and whole-process totals. DELETE is left out of the tables: the Rust SDK deletes
over gRPC (StorageControl), java-storage over JSON, so it isn't a like-for-like comparison.
"""

import csv
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

IMPLS = ["native_java", "rust_jni", "rust_panama"]
OPS = ["SINGLE-SHOT", "RESUMABLE", "READ"]
SIZE_LABEL = {100_000: "100 KB", 2_097_152: "2 MiB", 100_000_000: "100 MB"}
EXPECTED_CALLS = {"SINGLE-SHOT": {"rust_jni": 1, "rust_panama": 2},
                  "RESUMABLE": {"rust_jni": 1, "rust_panama": 2}}


def pct(values, p):
    s = sorted(values)
    if not s:
        return float("nan")
    return s[min(int(p / 100 * (len(s) - 1) + 0.5), len(s) - 1)]


def num(x):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return v if v == v else None


def op_group(op):
    return op.split("[")[0]


def load(results: Path):
    rows = []
    for f in sorted(results.glob("r*-*.csv")):
        m = re.match(r"r(\d+)-(native_java|rust_jni|rust_panama)\.csv$", f.name)
        if not m:
            continue
        with f.open() as fh:
            for r in csv.DictReader(fh):
                r["impl"], r["round"] = m.group(2), int(m.group(1))
                r["size"], r["group"] = int(r["object_size"]), op_group(r["op"])
                rows.append(r)
    procs = defaultdict(dict)
    for f in results.glob("r*-*.proc.json"):
        m = re.match(r"r(\d+)-([a-z_]+)\.proc\.json$", f.name)
        if m and m.group(2) in IMPLS:
            procs[m.group(2)][int(m.group(1))] = json.loads(f.read_text())
    return rows, procs


def fmt(v, d=2):
    return "–" if v is None or v != v else f"{v:.{d}f}"


def main(results: Path):
    rows, procs = load(results)
    idx = defaultdict(list)
    for r in rows:
        idx[(r["size"], r["group"], r["impl"])].append(r)
    sizes = sorted({r["size"] for r in rows})
    rounds = sorted({r["round"] for r in rows})
    total = len(rows)
    errors = sum(1 for r in rows if r["ok"] != "1")

    print("# w1r3 (JSON): Java SDK vs Rust SDK via JNI / Panama\n")
    per_impl = ", ".join(
        f"{i}: {sum(1 for r in rows if r['impl'] == i):,} / {sum(1 for r in rows if r['impl'] == i and r['ok'] != '1')} err"
        for i in IMPLS)
    print(f"**{total:,} measurements, {errors} errors** ({per_impl}).\n")

    # Sanity checks on the raw rows.
    problems = []
    for r in rows:
        if r["ok"] != "1":
            continue
        wall, sdk, calls = num(r["op_wall_s"]), num(r["rust_sdk_s"]), num(r["ffi_calls"])
        if r["impl"] == "native_java":
            if sdk:
                problems.append(f"native_java has Rust SDK time ({r['op']} #{r['iteration']})")
            continue
        if sdk is None or wall is None or sdk > wall + 1e-4:
            problems.append(f"{r['impl']} SDK time > op time ({r['op']} #{r['iteration']})")
        exp = EXPECTED_CALLS.get(r["group"], {}).get(r["impl"])
        if exp is not None and calls != exp:
            problems.append(f"{r['impl']} {r['op']} made {calls} native calls, expected {exp}")
        if r["group"] != "DELETE" and not num(r["rust_alloc_bytes_per_byte"]):
            problems.append(f"{r['impl']} {r['op']} recorded no Rust allocations")
    if problems:
        print(f"**Sanity checks: {len(problems)} problems**, e.g. {problems[:5]}\n")
    else:
        print("**Sanity checks:** all passed (Rust SDK time ≤ op time; native calls per upload as "
              "expected; no Rust SDK time on native_java; Rust allocations recorded).\n")

    env = results / "environment.txt"
    if env.exists():
        print("```\n" + env.read_text().strip() + "\n```\n")

    for size in sizes:
        label = SIZE_LABEL.get(size, f"{size:,} B")
        print(f"## {label}\n")
        print("**Latency** (ms; p50 per round in brackets)\n")
        print("| op | impl | n | err | p50 | p50 per round | p90 | p99 | mean | max | p50 vs native_java |")
        print("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
        base = {}
        for op in OPS:
            for impl in IMPLS:
                v = idx.get((size, op, impl), [])
                ok = [r for r in v if r["ok"] == "1"]
                lat = [num(r["latency_s"]) * 1e3 for r in ok]
                if not v:
                    continue
                p50 = pct(lat, 50)
                if impl == "native_java":
                    base[op] = p50
                per_round = [pct([num(r["latency_s"]) * 1e3 for r in ok if r["round"] == k], 50)
                             for k in rounds]
                per_round = [x for x in per_round if x == x]
                rng = f"{min(per_round):.1f}–{max(per_round):.1f}" if per_round else "–"
                ratio = p50 / base[op] if base.get(op) else float("nan")
                print(f"| {op} | {impl} | {len(v)} | {len(v) - len(ok)} | {fmt(p50)} | {rng} | "
                      f"{fmt(pct(lat, 90))} | {fmt(pct(lat, 99))} | {fmt(statistics.mean(lat))} | "
                      f"{fmt(max(lat))} | {ratio:.2f}x |")
        print()

        print("**Cost per op** (whole-process CPU; exact Java heap + Rust allocations; RSS)\n")
        print("| op | impl | proc CPU ns/B p50 | CPU vs native_java | Java alloc B/B p50 | "
              "Rust alloc B/B p50 | total alloc B/B p50 | peak RSS MiB max | RSS growth MiB p50 | "
              "RSS growth MiB max |")
        print("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|")
        for op in OPS:
            cbase = None
            for impl in IMPLS:
                ok = [r for r in idx.get((size, op, impl), []) if r["ok"] == "1"]
                if not ok:
                    continue
                cpu = pct([num(r["proc_cpu_ns_per_byte"]) for r in ok], 50)
                if impl == "native_java":
                    cbase = cpu
                ja = [num(r["java_alloc_bytes_per_byte"]) or 0.0 for r in ok]
                ra = [num(r["rust_alloc_bytes_per_byte"]) or 0.0 for r in ok]
                tot = [a + b for a, b in zip(ja, ra)]
                grow = [num(r["peak_rss_growth_mib"]) for r in ok]
                print(f"| {op} | {impl} | {fmt(cpu)} | {cpu / cbase if cbase else float('nan'):.2f}x | "
                      f"{fmt(pct(ja, 50))} | {fmt(pct(ra, 50))} | {fmt(pct(tot, 50))} | "
                      f"{max(num(r['peak_rss_mib']) for r in ok):.0f} | {pct(grow, 50):.1f} | "
                      f"{max(grow):.1f} |")
        print()

        print("**Bridge overhead** (op time − time inside the Rust SDK, same op)\n")
        print("| op | impl | op ms p50 | Rust SDK ms p50 | bridge ms p50 | bridge ms p90 | "
              "bridge % of op p50 | native calls/op |")
        print("|---|---|--:|--:|--:|--:|--:|--:|")
        for op in OPS:
            for impl in IMPLS[1:]:
                ok = [r for r in idx.get((size, op, impl), []) if r["ok"] == "1"]
                if not ok:
                    continue
                br = [num(r["bridge_s"]) * 1e3 for r in ok]
                share = [num(r["bridge_s"]) / num(r["op_wall_s"]) * 100 for r in ok]
                calls = statistics.median(num(r["ffi_calls"]) for r in ok)
                print(f"| {op} | {impl} | {pct([num(r['op_wall_s']) * 1e3 for r in ok], 50):.2f} | "
                      f"{pct([num(r['rust_sdk_s']) * 1e3 for r in ok], 50):.2f} | {pct(br, 50):.3f} | "
                      f"{pct(br, 90):.3f} | {pct(share, 50):.1f}% | {calls:.0f} |")
        print()

    if procs:
        print("## Whole processes (timed_run.py, all rounds)\n")
        print("| impl | wall s | CPU s (user+sys) | max RSS MiB | failed runs |")
        print("|---|--:|--:|--:|--:|")
        for impl in IMPLS:
            p = procs.get(impl, {})
            if not p:
                continue
            print(f"| {impl} | {sum(x['wall_s'] for x in p.values()):.0f} | "
                  f"{sum(x['user_s'] + x['sys_s'] for x in p.values()):.0f} | "
                  f"{max(x['max_rss_kib'] for x in p.values()) / 1024:.0f} | "
                  f"{sum(1 for x in p.values() if x['exit_code'] != 0)} |")
        print()

    print("## Notes\n")
    print("- Latency is measured around each call in the same harness for all three.")
    print("- DELETE is not in the tables (Rust: gRPC StorageControl; Java: JSON).")
    print("- RSS is dominated by the 1 GiB pre-touched Java heap; RSS growth shows memory held by the op.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: summarize_w1r3.py <results_dir>")
    main(Path(sys.argv[1]))
