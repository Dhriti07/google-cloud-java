#!/usr/bin/env python3
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Draws the matched-connections chart (SVG, Python standard library only).

Usage:
  python3 charts_matched.py <results dir> <output.svg>

<results dir> holds one sub-directory per run (summary.json) with labels java-<N>conn / rust-<N>conn.
"""

import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from charts import JAVA, RUST, Svg, legend, nice_step  # noqa: E402

OPS = [("BIDI_READ", "16 MiB reads x48 (bidi)"), ("BIDI_APPENDABLE_WRITE", "200 MB writes x48 (bidi)")]
METRICS = [
    ("Throughput (MB/s)", lambda s: s.get("throughput_MBps")),
    ("Latency p50 (ms)", lambda s: s["latency_ms_p50"]),
    ("Latency p99 (ms)", lambda s: s["latency_ms_p99"]),
    ("VM CPU % (mean)", lambda s: s.get("cloud_monitoring", {}).get("cpu_utilization_percent", {}).get("mean")),
    ("VM peak memory %", lambda s: s.get("cloud_monitoring", {}).get("memory_percent_used", {}).get("peak")),
]


def main(results, out):
    runs = {}
    for f in glob.glob(os.path.join(results, "*", "summary.json")):
        s = json.load(open(f))
        engine = "java" if s["label"].startswith("java") else "rust"
        runs[(s["op"], engine)] = s
    conns = next(iter(runs.values()))["label"].split("-")[1].replace("conn", "")
    pw, ph = 250, 230
    svg = Svg(pw * len(METRICS) + 20, ph * len(OPS) + 80)
    svg.text(15, 25, f"Same number of connections ({conns} each, one client per engine)", 16, weight="bold")
    legend(svg, 15, 50)
    for r, (op, title) in enumerate(OPS):
        svg.text(15, 80 + r * ph, title, 13, weight="bold")
        for c, (mname, get) in enumerate(METRICS):
            ox, oy = 10 + c * pw, 90 + r * ph
            x0, x1, y0, y1 = ox + 45, ox + pw - 20, oy + 30, oy + ph - 40
            svg.text(ox + pw / 2, oy + 18, mname, 11, "middle", "#333")
            vals = [get(runs[(op, e)]) for e in ("java", "rust") if (op, e) in runs]
            vals = [v for v in vals if v is not None]
            if not vals:
                continue
            hi = max(vals) * 1.25
            step = nice_step(hi, 4)
            sy = lambda v: y1 - v / hi * (y1 - y0)
            for k in range(0, int(hi / step) + 1):
                t = k * step
                svg.line(x0, sy(t), x1, sy(t), "#eee")
                svg.text(x0 - 4, sy(t) + 3, f"{t:g}", 9, "end", "#666")
            svg.line(x0, y1, x1, y1, "#999")
            bw = (x1 - x0) / 5
            for j, (e, color) in enumerate((("java", JAVA), ("rust", RUST))):
                s = runs.get((op, e))
                v = get(s) if s else None
                if v is None:
                    continue
                bx = x0 + bw * (1 + j * 2)
                svg.rect(bx, sy(v), bw * 1.4, y1 - sy(v), color, 0.9)
                label = f"{v:,.0f}" if v >= 100 else f"{v:,.2f}"
                svg.text(bx + bw * 0.7, sy(v) - 4, label, 10, "middle", "#222")
            svg.text(x0 + bw * 1.7, y1 + 14, "Java", 10, "middle", "#444")
            svg.text(x0 + bw * 3.7, y1 + 14, "Rust", 10, "middle", "#444")
    svg.save(out)
    print("wrote", out)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
