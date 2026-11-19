#!/usr/bin/env python3
"""把 Prometheus 时间序列画成 PNG（复盘里的“监控图”），同时输出 CSV 方便核对。

用法：promchart.py <out.png> <start_epoch> <end_epoch> "<标题>" "<PromQL>|<图例>" ["<PromQL>|<图例>" ...]
      可选环境变量 MARKS="epoch:文字,epoch:文字" 在图上画竖线（故障注入 / 修复时刻）。
"""
import csv
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402

for f in ["/System/Library/Fonts/Hiragino Sans GB.ttc", "/System/Library/Fonts/STHeiti Medium.ttc",
          "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]:
    if os.path.exists(f):
        font_manager.fontManager.addfont(f)
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=f).get_name()
        break
plt.rcParams["axes.unicode_minus"] = False

PROM = os.environ.get("PROM", "http://localhost:9095")


def rng(expr, start, end, step=2):
    url = f"{PROM}/api/v1/query_range?" + urllib.parse.urlencode({"query": expr, "start": start, "end": end, "step": step})
    with urllib.request.urlopen(url, timeout=20) as r:
        return json.load(r)["data"]["result"]


def main():
    out, start, end, title = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    specs = sys.argv[5:]
    fig, axes = plt.subplots(len(specs), 1, figsize=(10, 2.6 * len(specs)), sharex=True)
    if len(specs) == 1:
        axes = [axes]
    rows = []
    for ax, spec in zip(axes, specs):
        expr, label = spec.rsplit("|", 1)
        for s in rng(expr, start, end):
            xs = [float(t) - start for t, _ in s["values"]]
            ys = [float(v) for _, v in s["values"]]
            name = ",".join(f"{v}" for k, v in sorted(s["metric"].items()) if k not in ("__name__", "job", "instance")) or label
            ax.plot(xs, ys, label=name, linewidth=1.4)
            rows += [(label, name, int(x), y) for x, y in zip(xs, ys)]
        ax.set_ylabel(label, fontsize=9)
        ax.grid(alpha=0.3)
        if len(ax.get_lines()) > 1:
            ax.legend(fontsize=7, loc="upper right")
        for m in filter(None, os.environ.get("MARKS", "").split(",")):
            t, text = m.split(":", 1)
            ax.axvline(int(t) - start, color="red", linestyle="--", linewidth=1)
            if ax is axes[0]:
                ax.text(int(t) - start, ax.get_ylim()[1], " " + text, color="red", fontsize=8, va="top")
    axes[-1].set_xlabel(f"秒（从 {datetime.fromtimestamp(start):%H:%M:%S} 起）")
    axes[0].set_title(title)
    fig.tight_layout()
    fig.savefig(out, dpi=110)
    with open(out.rsplit(".", 1)[0] + ".csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["panel", "series", "t_sec", "value"])
        w.writerows(rows)
    print("wrote", out)


if __name__ == "__main__":
    main()
