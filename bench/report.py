#!/usr/bin/env python3
"""汇总 bench/results/raw/<case>/run-*.json，取 3 次的中位数，按场景更新 bench/results/summary.tsv。

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


def qps_of(run, tag):
    """全程 QPS：k6 的 rate（次数 / 整次运行秒数）。没有同 tag 的 http_reqs 时不借用总量。"""
    m = run["metrics"]
    reqs = m.get("http_reqs" + tag)
    if not reqs:
        return None
    if reqs.get("rate") is not None:
        return reqs["rate"]
    secs = run["state"]["testRunDurationMs"] / 1000
    count = reqs.get("count")
    if count is None or not secs:
        return None
    return count / secs


def scenario_qps(run, tag):
    """场景 QPS：次数 / extra.scenarioWindows 里的窗口秒数。旧 raw 没有这个字段，返回 None。"""
    windows = (run.get("extra") or {}).get("scenarioWindows") or {}
    window = windows.get(tag)
    if not window:
        return None
    reqs = run["metrics"].get("http_reqs" + tag)
    if not reqs or reqs.get("count") is None:
        return None
    return reqs["count"] / window


def err_of(run, tag):
    failed = run["metrics"].get("http_req_failed" + tag)
    if failed is None:
        return None
    return (failed.get("rate") or 0) * 100


def rows(case, runs):
    out = []
    dur_keys = sorted({k for r in runs for k in r["metrics"] if k.startswith("http_req_duration")})
    for k in dur_keys:
        tag = k[len("http_req_duration"):]
        if tag == "{expected_response:true}":
            continue
        qps, p50, p99, err = [], [], [], []
        scenario = []
        for r in runs:
            d = r["metrics"].get(k, {})
            qps.append(qps_of(r, tag))
            p50.append(d.get("med"))
            p99.append(d.get("p(99)"))
            err.append(err_of(r, tag))
            scenario.append(scenario_qps(r, tag))
        out.append((case, k, med(qps), med(p50), med(p99), med(err), len(runs)))
        if any(x is not None for x in scenario):
            out.append((case, "scenario_qps" + tag, med(scenario), med(p50), med(p99), med(err), len(runs)))
    return out


def prom_rows(case, runs):
    """服务端指标：每个 (指标, 标签) 取 3 次运行的中位数。"""
    vals = {}
    for r in runs:
        for k, series in (r.get("prom") or {}).items():
            if not isinstance(series, dict) or "error" in series:
                continue
            for label, v in series.items():
                vals.setdefault((k, label), []).append(v)
    return [(case, k, label, med(v)) for (k, label), v in sorted(vals.items())]


def fmt(x, nd=1):
    return "-" if x is None else f"{x:.{nd}f}"


def replace_cases(existing_body, cases, new_lines):
    """按 case 换掉旧行，其它 case 原样保留，结果按整行排序。"""
    drop = set(cases)
    kept = [line for line in existing_body if line and line.split("\t")[0] not in drop]
    return sorted(kept + list(new_lines))


def main():
    cases = sys.argv[1:] or sorted(os.listdir(os.path.join(HERE, "results", "raw")))
    path = os.path.join(HERE, "results", "summary.tsv")
    existing = []
    if os.path.exists(path):
        with open(path) as fh:
            existing = fh.read().splitlines()[1:]
    new = []
    pnew = []
    for c in cases:
        runs = load(c)
        for r in rows(c, runs):
            line = "\t".join([r[0], r[1], fmt(r[2], 0), fmt(r[3]), fmt(r[4]), fmt(r[5], 2), str(r[6])])
            new.append(line)
            print(line)
        for r in prom_rows(c, runs):
            pnew.append("\t".join([r[0], r[1], r[2], fmt(r[3], 3)]))
    with open(path, "w") as fh:
        fh.write("case\tmetric\tqps\tp50_ms\tp99_ms\terr_pct\truns\n")
        fh.write("\n".join(replace_cases(existing, cases, new)) + "\n")

    ppath = os.path.join(HERE, "results", "prom.tsv")
    pexisting = []
    if os.path.exists(ppath):
        with open(ppath) as fh:
            pexisting = fh.read().splitlines()[1:]
    with open(ppath, "w") as fh:
        fh.write("case\tquery\tlabels\tmedian\n")
        fh.write("\n".join(replace_cases(pexisting, cases, pnew)) + "\n")


if __name__ == "__main__":
    main()
