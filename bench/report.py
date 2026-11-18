#!/usr/bin/env python3
"""汇总 bench/results/raw/<case>/run-*.json，取 3 次的中位数，追加到 bench/results/summary.tsv。

summary.tsv 每行：case  metric  qps  p50_ms  p99_ms  err_pct  runs
metric 是 k6 的指标名（含 tag 子指标），例如 http_req_duration{scenario:reads}。
"""
import glob
import json
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(case):
    runs = []
    for f in sorted(glob.glob(os.path.join(HERE, "results", "raw", case, "run-*.json"))):
        with open(f) as fh:
            runs.append(json.load(fh))
    return runs


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def rows(case, runs):
    out = []
    dur_keys = sorted({k for r in runs for k in r["metrics"] if k.startswith("http_req_duration")})
    for k in dur_keys:
        tag = k[len("http_req_duration"):]
        if tag == "{expected_response:true}":
            continue
        qps, p50, p99, err = [], [], [], []
        for r in runs:
            m = r["metrics"]
            d = m.get(k, {})
            secs = r["state"]["testRunDurationMs"] / 1000
            # 子指标没有直接的 rate，用 http_reqs 同 tag 的 count；拿不到时用总量
            reqs = m.get("http_reqs" + tag) or m.get("http_reqs", {})
            qps.append(reqs.get("rate") or (reqs.get("count", 0) / secs if secs else None))
            p50.append(d.get("med"))
            p99.append(d.get("p(99)"))
            f = m.get("http_req_failed" + tag) or m.get("http_req_failed", {})
            err.append((f.get("rate") or 0) * 100)
        out.append((case, k, med(qps), med(p50), med(p99), med(err), len(runs)))
    return out


def fmt(x, nd=1):
    return "-" if x is None else f"{x:.{nd}f}"


def main():
    cases = sys.argv[1:] or sorted(os.listdir(os.path.join(HERE, "results", "raw")))
    path = os.path.join(HERE, "results", "summary.tsv")
    existing = []
    if os.path.exists(path):
        with open(path) as fh:
            existing = [l for l in fh.read().splitlines()[1:] if l and l.split("\t")[0] not in cases]
    new = []
    for c in cases:
        for r in rows(c, load(c)):
            line = "\t".join([r[0], r[1], fmt(r[2], 0), fmt(r[3]), fmt(r[4]), fmt(r[5], 2), str(r[6])])
            new.append(line)
            print(line)
    with open(path, "w") as fh:
        fh.write("case\tmetric\tqps\tp50_ms\tp99_ms\terr_pct\truns\n")
        fh.write("\n".join(sorted(existing + new)) + "\n")


if __name__ == "__main__":
    main()
