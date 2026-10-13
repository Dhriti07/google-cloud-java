#!/usr/bin/env python3
"""Graphs for a w1r3 results dir (native_java vs Rust via JNI / Panama).

usage: plot_w1r3.py <results_dir> <out_dir>      (needs matplotlib + numpy)

Same inputs as summarize_w1r3.py. DELETE is left out (Rust: gRPC StorageControl; Java: JSON).
"""

import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from matplotlib.colors import TwoSlopeNorm  # noqa: E402

IMPLS = ["native_java", "rust_jni", "rust_panama"]
OPS = ["SINGLE-SHOT", "RESUMABLE", "READ"]
COLOR = {"native_java": "#d62828", "rust_jni": "#1565c0", "rust_panama": "#2a9d8f"}
# metric -> (label, scale, impls that have it)
METRICS = {
    "latency": ("latency (ms)", 1e3, IMPLS),
    "proc_cpu": ("process CPU ns / byte", 1.0, IMPLS),
    "alloc": ("Java + Rust allocated bytes / byte", 1.0, IMPLS),
    "bridge": ("bridge time (ms)", 1e3, IMPLS[1:]),
}
SIZE_LABEL = {100_000: "100 KB", 2_097_152: "2 MiB", 100_000_000: "100 MB"}


def size_label(s):
    return SIZE_LABEL.get(s, f"{s:,} B")


def size_tag(s):
    return size_label(s).replace(" ", "")


def pct(v, p):
    s = sorted(v)
    return s[min(int(p / 100 * (len(s) - 1) + 0.5), len(s) - 1)] if s else None


def num(x):
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    return v if v == v else None


class Data:
    def __init__(self, results: Path):
        self.rows = []
        for f in sorted(results.glob("r*-*.csv")):
            m = re.match(r"r(\d+)-(native_java|rust_jni|rust_panama)\.csv$", f.name)
            if not m:
                continue
            with f.open() as fh:
                for r in csv.DictReader(fh):
                    op = r["op"].split("[")[0]
                    if r["ok"] != "1" or op not in OPS:
                        continue
                    ja = num(r["java_alloc_bytes_per_byte"]) or 0.0
                    ra = num(r["rust_alloc_bytes_per_byte"]) or 0.0
                    self.rows.append({
                        "impl": m.group(2), "round": int(m.group(1)), "size": int(r["object_size"]),
                        "op": op, "iteration": int(r["iteration"]),
                        "latency": num(r["latency_s"]), "proc_cpu": num(r["proc_cpu_ns_per_byte"]),
                        "alloc": ja + ra, "bridge": num(r["bridge_s"])})
        self.procs = defaultdict(dict)
        for f in results.glob("r*-*.proc.json"):
            m = re.match(r"r(\d+)-([a-z_]+)\.proc\.json$", f.name)
            if m and m.group(2) in IMPLS:
                self.procs[m.group(2)][int(m.group(1))] = json.loads(f.read_text())
        self.sizes = sorted({r["size"] for r in self.rows})
        self.rounds = sorted({r["round"] for r in self.rows})
        self.idx = defaultdict(list)
        for r in self.rows:
            self.idx[(r["size"], r["op"], r["impl"])].append(r)

    def values(self, size, op, impl, metric, rnd=None):
        scale = METRICS[metric][1]
        return [r[metric] * scale for r in self.idx.get((size, op, impl), [])
                if r[metric] is not None and (rnd is None or r["round"] == rnd)]

    def p(self, size, op, impl, metric, q, rnd=None):
        return pct(self.values(size, op, impl, metric, rnd), q)


def save(fig, out: Path, name, title, rect=(0, 0, 1, 0.95)):
    fig.suptitle(title, fontsize=13, fontweight="bold")
    fig.tight_layout(rect=rect)
    fig.savefig(out / name, dpi=130)
    plt.close(fig)
    print("wrote", out / name)


def legend(fig, impls=IMPLS):
    from matplotlib.patches import Patch
    fig.legend(handles=[Patch(facecolor=COLOR[i], label=i) for i in impls], loc="upper center",
               ncol=len(impls), fontsize=9, bbox_to_anchor=(0.5, 0.94))


def fig_heatmap(d, out):
    cols = [(s, op) for s in d.sizes for op in OPS]
    fig, axes = plt.subplots(2, 1, figsize=(16, 6.5))
    for ax, metric, title in zip(axes, ["latency", "proc_cpu"], ["p50 latency", "p50 process CPU ns/B"]):
        impls = IMPLS[1:]
        m = np.full((len(impls), len(cols)), np.nan)
        for i, impl in enumerate(impls):
            for j, (s, op) in enumerate(cols):
                v, b = d.p(s, op, impl, metric, 50), d.p(s, op, "native_java", metric, 50)
                if v is not None and b:
                    m[i, j] = v / b
        im = ax.imshow(m, cmap="RdYlGn_r", norm=TwoSlopeNorm(vcenter=1.0, vmin=0.05, vmax=2.0),
                       aspect="auto")
        for i in range(len(impls)):
            for j in range(len(cols)):
                if np.isfinite(m[i, j]):
                    ax.text(j, i, f"{m[i, j]:.2f}x", ha="center", va="center", fontsize=9)
        ax.set_yticks(range(len(impls)), impls)
        ax.set_xticks(range(len(cols)), [f"{size_label(s)}\n{op}" for s, op in cols], fontsize=8)
        for k in range(1, len(d.sizes)):
            ax.axvline(k * len(OPS) - 0.5, color="black", lw=1.5)
        ax.set_title(f"{title} ÷ native_java  (<1 green = lower than the Java SDK)")
        fig.colorbar(im, ax=ax, fraction=0.02)
    save(fig, out, "0_overview_ratio_heatmap.png", "Overview: Rust via JNI / Panama vs the Java SDK")


def fig_vs_size(d, out, metric):
    label = METRICS[metric][0]
    fig, axes = plt.subplots(1, len(OPS), figsize=(16, 4.8))
    for ax, op in zip(axes, OPS):
        for impl in METRICS[metric][2]:
            ys = [d.p(s, op, impl, metric, 50) for s in d.sizes]
            ax.plot(d.sizes, [np.nan if y is None else y for y in ys], marker="o",
                    color=COLOR[impl], label=impl)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xticks(d.sizes, [size_label(s) for s in d.sizes])
        ax.minorticks_off()
        ax.set_title(op)
        ax.set_ylabel(label + " p50 (log)")
        ax.grid(alpha=0.3, which="both")
        ax.legend(fontsize=7)
    save(fig, out, f"0_{metric}_vs_object_size.png", f"p50 {label} vs object size")


def fig_process(d, out):
    impls = [i for i in IMPLS if i in d.procs]
    nr = max(len(v) for v in d.procs.values())
    fig, axes = plt.subplots(1, 3, figsize=(16, 4.8))
    x = np.arange(len(impls))
    w = 0.8 / nr
    for rk in range(nr):
        rnd = rk + 1
        get = lambda i, f: f(d.procs[i][rnd]) if rnd in d.procs[i] else 0  # noqa: E731
        off = x + (rk - (nr - 1) / 2) * w
        a = 1.0 - 0.18 * rk
        axes[0].bar(off, [get(i, lambda j: j["wall_s"]) for i in impls], w,
                    color=[COLOR[i] for i in impls], alpha=a)
        u = [get(i, lambda j: j["user_s"]) for i in impls]
        sy = [get(i, lambda j: j["sys_s"]) for i in impls]
        axes[1].bar(off, u, w, color=[COLOR[i] for i in impls], alpha=a)
        axes[1].bar(off, sy, w, bottom=u, color="grey", alpha=0.6 * a)
        axes[2].bar(off, [get(i, lambda j: j["max_rss_kib"] / 1024) for i in impls], w,
                    color=[COLOR[i] for i in impls], alpha=a)
    for ax, t in zip(axes, ["wall time per run (s)", "CPU per run (s): user + sys (grey)",
                            "max RSS per run (MiB)"]):
        ax.set_xticks(x, impls, fontsize=9)
        ax.set_title(t)
        ax.grid(axis="y", alpha=0.3)
    totals = {i: sum(p["user_s"] + p["sys_s"] for p in d.procs[i].values()) for i in impls}
    axes[1].text(0.02, 0.98, "total CPU s:\n" + "\n".join(f"{i}: {v:.0f}" for i, v in totals.items()),
                 transform=axes[1].transAxes, va="top", fontsize=8,
                 bbox={"facecolor": "white", "alpha": 0.8})
    save(fig, out, "0_process_wall_cpu_rss.png",
         "Whole process per run (bars left→right = round 1…N)")


def fig_percentiles(d, size, out):
    fig, axes = plt.subplots(1, len(OPS), figsize=(16, 5.2))
    for ax, op in zip(axes, OPS):
        x = np.arange(len(IMPLS))
        w = 0.26
        for j, (q, alpha) in enumerate([(50, 1.0), (90, 0.65), (99, 0.35)]):
            vals = [d.p(size, op, i, "latency", q) or 0 for i in IMPLS]
            ax.bar(x + (j - 1) * w, vals, w, alpha=alpha, color=[COLOR[i] for i in IMPLS])
            if q == 50:
                for xi, v in zip(x, vals):
                    ax.text(xi - w, v, f"{v:.0f}", ha="center", va="bottom", fontsize=7)
        ax.set_xticks(x, IMPLS, fontsize=8)
        ax.set_title(op)
        ax.set_ylabel("latency (ms)")
        ax.grid(axis="y", alpha=0.3)
    axes[-1].text(0.98, 0.98, "bars per impl: p50 | p90 | p99", transform=axes[-1].transAxes,
                  ha="right", va="top", fontsize=8)
    legend(fig)
    save(fig, out, f"{size_tag(size)}_1_latency_percentiles.png",
         f"Latency p50 / p90 / p99 — {size_label(size)}", rect=(0, 0, 1, 0.88))


def fig_cdf(d, size, out):
    fig, axes = plt.subplots(1, len(OPS), figsize=(16, 5))
    for ax, op in zip(axes, OPS):
        for impl in IMPLS:
            v = np.sort(d.values(size, op, impl, "latency"))
            if len(v):
                ax.step(v, np.arange(1, len(v) + 1) / len(v), where="post", color=COLOR[impl],
                        lw=1.8, label=impl)
        ax.set_xscale("log")
        ax.set_title(op)
        ax.set_xlabel("latency (ms, log)")
        ax.set_ylabel("fraction of ops ≤ x")
        ax.axhline(0.5, color="grey", lw=0.6, ls=":")
        ax.axhline(0.99, color="grey", lw=0.6, ls=":")
        ax.grid(alpha=0.3, which="both")
        ax.legend(fontsize=7, loc="lower right")
    save(fig, out, f"{size_tag(size)}_2_latency_cdf.png",
         f"Latency CDF — {size_label(size)} (dotted lines = p50, p99)")


def fig_per_round(d, size, out):
    fig, axes = plt.subplots(1, len(OPS), figsize=(16, 4.8))
    for ax, op in zip(axes, OPS):
        for impl in IMPLS:
            ys = [d.p(size, op, impl, "latency", 50, rnd) for rnd in d.rounds]
            ax.plot(d.rounds, [np.nan if y is None else y for y in ys], marker="o",
                    color=COLOR[impl], label=impl)
        ax.set_xticks(d.rounds, [f"round {k}" for k in d.rounds])
        ax.set_title(op)
        ax.set_ylabel("p50 latency (ms)")
        ax.grid(alpha=0.3)
        ax.legend(fontsize=7)
    save(fig, out, f"{size_tag(size)}_3_latency_p50_per_round.png",
         f"p50 latency in each round — {size_label(size)}")


def fig_timeline(d, size, out):
    nr = len(d.rounds)
    fig, axes = plt.subplots(nr, len(OPS), figsize=(16, 3.2 * nr), squeeze=False, sharex=True)
    for ri, rnd in enumerate(d.rounds):
        for ci, op in enumerate(OPS):
            ax = axes[ri][ci]
            for impl in IMPLS:
                pts = [(r["iteration"], r["latency"] * 1e3) for r in d.idx.get((size, op, impl), [])
                       if r["round"] == rnd]
                if pts:
                    xs, ys = zip(*pts)
                    ax.scatter(xs, ys, s=8, alpha=0.6, color=COLOR[impl], label=impl)
            ax.set_yscale("log")
            ax.grid(alpha=0.3, which="both")
            if ri == 0:
                ax.set_title(op)
            if ci == 0:
                ax.set_ylabel(f"round {rnd}\nlatency (ms, log)")
            if ri == nr - 1:
                ax.set_xlabel("iteration")
    axes[0][0].legend(fontsize=7)
    save(fig, out, f"{size_tag(size)}_4_latency_timeline.png",
         f"Every op over time, per round — {size_label(size)}")


def fig_bars(d, size, out, metric):
    label, _, impls = METRICS[metric]
    fig, axes = plt.subplots(1, len(OPS), figsize=(16, 5.2))
    for ax, op in zip(axes, OPS):
        x = np.arange(len(impls))
        p50 = [d.p(size, op, i, metric, 50) or 0 for i in impls]
        p10 = [d.p(size, op, i, metric, 10) or 0 for i in impls]
        p90 = [d.p(size, op, i, metric, 90) or 0 for i in impls]
        ax.bar(x, p50, 0.6, color=[COLOR[i] for i in impls])
        err = [[max(0, a - b) for a, b in zip(p50, p10)], [max(0, b - a) for a, b in zip(p50, p90)]]
        ax.errorbar(x, p50, yerr=err, fmt="none", ecolor="black", capsize=4, lw=1)
        for k, impl in enumerate(impls):
            for rnd in d.rounds:
                v = d.p(size, op, impl, metric, 50, rnd)
                if v is not None:
                    ax.scatter(k + 0.38, v, marker="_", color="black", s=50, zorder=3)
            ax.annotate(f"{p50[k]:.2f}", (k, max(p50[k], p90[k])), xytext=(0, 4),
                        textcoords="offset points", ha="center", fontsize=8, fontweight="bold")
        ax.set_xticks(x, impls, fontsize=8)
        ax.set_xlim(-0.6, len(impls) - 0.2)
        ax.margins(y=0.12)
        ax.set_title(op)
        ax.set_ylabel(label + " (p50)")
        if metric == "alloc":
            ax.set_yscale("symlog", linthresh=0.1)
        ax.grid(axis="y", alpha=0.3)
    fig.text(0.5, 0.01, "bar = p50 (value above), whisker = p10–p90, short ticks right of bar = p50 "
             "of each round", ha="center", fontsize=8, style="italic")
    legend(fig, impls)
    save(fig, out, f"{size_tag(size)}_5_{metric}_bars.png", f"{label} — {size_label(size)}",
         rect=(0, 0.04, 1, 0.88))


def main(results: Path, out: Path):
    out.mkdir(parents=True, exist_ok=True)
    d = Data(results)
    fig_heatmap(d, out)
    fig_vs_size(d, out, "latency")
    fig_vs_size(d, out, "proc_cpu")
    fig_process(d, out)
    for s in d.sizes:
        fig_percentiles(d, s, out)
        fig_cdf(d, s, out)
        fig_per_round(d, s, out)
        fig_timeline(d, s, out)
        fig_bars(d, s, out, "proc_cpu")
        fig_bars(d, s, out, "alloc")
        fig_bars(d, s, out, "bridge")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: plot_w1r3.py <results_dir> <out_dir>")
    main(Path(sys.argv[1]), Path(sys.argv[2]))
