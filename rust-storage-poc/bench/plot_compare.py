#!/usr/bin/env python3
"""Side-by-side bar charts: Java SDK vs Rust via JNI / Panama, per object size and op.

usage: plot_compare.py <results_dir> <out_dir>      (needs matplotlib + numpy)

Writes:
  compare_latency.png            p50 latency (whisker = p25–p75), ratio to native_java on each bar
  compare_latency_breakdown.png  JNI / Panama p50 split into time inside the Rust SDK + bridge
  compare_cpu.png                p50 whole-process CPU ns per byte
  compare_memory.png             p50 allocated bytes per byte (Java + Rust) and p50 RSS growth
DELETE is left out (Rust: gRPC StorageControl; Java: JSON).
"""

import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

IMPLS = ["native_java", "rust_jni", "rust_panama"]
LABEL = {"native_java": "Java SDK", "rust_jni": "Rust via JNI", "rust_panama": "Rust via Panama"}
COLOR = {"native_java": "#d62828", "rust_jni": "#1565c0", "rust_panama": "#2a9d8f"}
OPS = ["SINGLE-SHOT", "RESUMABLE", "READ"]
SIZE_LABEL = {100_000: "100 KB", 2_097_152: "2 MiB", 100_000_000: "100 MB"}


def num(x):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return v if v == v else None


def pct(v, p):
    s = sorted(x for x in v if x is not None)
    return s[min(int(p / 100 * (len(s) - 1) + 0.5), len(s) - 1)] if s else None


def load(results: Path):
    idx = defaultdict(list)
    for f in sorted(results.glob("r*-*.csv")):
        m = re.match(r"r\d+-(native_java|rust_jni|rust_panama)\.csv$", f.name)
        if not m:
            continue
        with f.open() as fh:
            for r in csv.DictReader(fh):
                op = r["op"].split("[")[0]
                if r["ok"] != "1" or op not in OPS:
                    continue
                ja = num(r["java_alloc_bytes_per_byte"]) or 0.0
                ra = num(r["rust_alloc_bytes_per_byte"]) or 0.0
                idx[(int(r["object_size"]), op, m.group(1))].append({
                    "latency": num(r["op_wall_s"]) * 1e3,
                    "sdk": (num(r["rust_sdk_s"]) or 0.0) * 1e3,
                    "cpu": num(r["proc_cpu_ns_per_byte"]),
                    "alloc": ja + ra,
                    "rss": num(r["peak_rss_growth_mib"]),
                })
    sizes = sorted({k[0] for k in idx})
    return idx, sizes


def stat(idx, size, op, impl, key, p=50):
    return pct([r[key] for r in idx.get((size, op, impl), [])], p)


def grouped(ax, idx, size, key, *, fmt, ylabel, log=False, whisker=None, ratio=True):
    x = np.arange(len(OPS))
    w = 0.27
    top = 0.0
    for k, impl in enumerate(IMPLS):
        vals = [stat(idx, size, op, impl, key) or 0.0 for op in OPS]
        pos = x + (k - 1) * w
        ax.bar(pos, vals, w, color=COLOR[impl], label=LABEL[impl], zorder=2)
        if whisker:
            lo = [stat(idx, size, op, impl, key, whisker[0]) or 0.0 for op in OPS]
            hi = [stat(idx, size, op, impl, key, whisker[1]) or 0.0 for op in OPS]
            ax.errorbar(pos, vals, yerr=[[v - l for v, l in zip(vals, lo)],
                                         [h - v for v, h in zip(vals, hi)]],
                        fmt="none", ecolor="black", capsize=3, lw=0.8, zorder=3)
            top = max(top, max(hi))
        top = max(top, max(vals))
        for j, (px, v) in enumerate(zip(pos, vals)):
            base = stat(idx, size, OPS[j], "native_java", key)
            text = fmt(v)
            if ratio and impl != "native_java" and base:
                text += f"\n{v / base:.2f}×"
            ax.text(px, v, text, ha="center", va="bottom", fontsize=7.5, zorder=4)
    ax.set_xticks(x, OPS)
    ax.set_title(SIZE_LABEL.get(size, f"{size:,} B"), fontweight="bold")
    ax.set_ylabel(ylabel)
    ax.grid(axis="y", alpha=0.3, zorder=0)
    if log:
        ax.set_yscale("symlog", linthresh=1.0)
        ax.set_ylim(0, max(top, 1) * 8)
    else:
        ax.set_ylim(0, top * 1.25 if top else 1)


def figure(idx, sizes, out, name, title, note, **kw):
    fig, axes = plt.subplots(1, len(sizes), figsize=(6 * len(sizes), 5))
    for ax, size in zip(np.atleast_1d(axes), sizes):
        grouped(ax, idx, size, **kw)
    handles, labels = np.atleast_1d(axes)[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=3, fontsize=10, bbox_to_anchor=(0.5, 0.93))
    fig.suptitle(title, fontsize=14, fontweight="bold")
    fig.text(0.5, 0.01, note, ha="center", fontsize=9, style="italic")
    fig.tight_layout(rect=(0, 0.04, 1, 0.88))
    fig.savefig(out / name, dpi=130)
    plt.close(fig)
    print("wrote", out / name)


def breakdown(idx, sizes, out):
    fig, axes = plt.subplots(1, len(sizes), figsize=(6 * len(sizes), 5))
    for ax, size in zip(np.atleast_1d(axes), sizes):
        x = np.arange(len(OPS))
        w = 0.27
        top = 0.0
        for k, impl in enumerate(IMPLS):
            pos = x + (k - 1) * w
            total = [stat(idx, size, op, impl, "latency") or 0.0 for op in OPS]
            if impl == "native_java":
                ax.bar(pos, total, w, color=COLOR[impl], label="Java SDK (whole call)", zorder=2)
            else:
                sdk = [min(stat(idx, size, op, impl, "sdk") or 0.0, t) for op, t in zip(OPS, total)]
                bridge = [t - s for t, s in zip(total, sdk)]
                ax.bar(pos, sdk, w, color=COLOR[impl], label=f"{LABEL[impl]}: inside Rust SDK",
                       zorder=2)
                ax.bar(pos, bridge, w, bottom=sdk, color="#ffb703", edgecolor="black", lw=0.4,
                       label="bridge (JNI / Panama + copies)" if k == 1 else None, zorder=2)
                for px, t, b in zip(pos, total, bridge):
                    ax.text(px, t, f"{t:.0f}\n+{b:.2f}", ha="center", va="bottom", fontsize=7)
            if impl == "native_java":
                for px, t in zip(pos, total):
                    ax.text(px, t, f"{t:.0f}", ha="center", va="bottom", fontsize=7)
            top = max(top, max(total))
        ax.set_xticks(x, OPS)
        ax.set_title(SIZE_LABEL.get(size, f"{size:,} B"), fontweight="bold")
        ax.set_ylabel("p50 latency (ms)")
        ax.set_ylim(0, top * 1.25)
        ax.grid(axis="y", alpha=0.3, zorder=0)
    handles, labels = np.atleast_1d(axes)[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=4, fontsize=9, bbox_to_anchor=(0.5, 0.93))
    fig.suptitle("Where the time goes: inside the Rust SDK vs the JNI / Panama bridge",
                 fontsize=14, fontweight="bold")
    fig.text(0.5, 0.01, "Label: p50 whole call (ms) and, under it, bridge ms (whole call − Rust SDK "
             "p50). The bridge is the thin yellow cap; it is only visible on 100 MB.",
             ha="center", fontsize=9, style="italic")
    fig.tight_layout(rect=(0, 0.04, 1, 0.88))
    fig.savefig(out / "compare_latency_breakdown.png", dpi=130)
    plt.close(fig)
    print("wrote", out / "compare_latency_breakdown.png")


def memory(idx, sizes, out):
    fig, axes = plt.subplots(2, len(sizes), figsize=(6 * len(sizes), 9))
    for col, size in enumerate(sizes):
        grouped(axes[0][col], idx, size, "alloc", fmt=lambda v: f"{v:.2f}",
                ylabel="allocated bytes / byte (log)", log=True, ratio=False)
        grouped(axes[1][col], idx, size, "rss", fmt=lambda v: f"{v:.1f}",
                ylabel="RSS growth during op (MiB)", ratio=False)
        axes[1][col].set_title("")
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=3, fontsize=10, bbox_to_anchor=(0.5, 0.95))
    fig.suptitle("Memory: allocated (top) and held (bottom), p50 per op",
                 fontsize=14, fontweight="bold")
    fig.text(0.5, 0.01, "Allocated = exact Java heap + Rust allocator bytes per byte moved. Held = "
             "peak RSS growth during the op (the 1 GiB Java heap is pre-touched, so Java shows ~0).",
             ha="center", fontsize=9, style="italic")
    fig.tight_layout(rect=(0, 0.03, 1, 0.91))
    fig.savefig(out / "compare_memory.png", dpi=130)
    plt.close(fig)
    print("wrote", out / "compare_memory.png")


def main(results: Path, out: Path):
    out.mkdir(parents=True, exist_ok=True)
    idx, sizes = load(results)
    figure(idx, sizes, out, "compare_latency.png", "Latency: p50 per op (lower is better)",
           "Bar = p50, whisker = p25–p75. Under each Rust value: ratio to the Java SDK.",
           key="latency", fmt=lambda v: f"{v:.0f}", ylabel="p50 latency (ms)", whisker=(25, 75))
    breakdown(idx, sizes, out)
    figure(idx, sizes, out, "compare_cpu.png", "CPU: whole-process CPU per byte, p50 (lower is better)",
           "clock_gettime(CLOCK_PROCESS_CPUTIME_ID) around each op, all threads. Under each Rust "
           "value: ratio to the Java SDK.",
           key="cpu", fmt=lambda v: f"{v:.2f}", ylabel="CPU ns / byte")
    memory(idx, sizes, out)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: plot_compare.py <results_dir> <out_dir>")
    main(Path(sys.argv[1]), Path(sys.argv[2]))
