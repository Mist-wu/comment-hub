#!/usr/bin/env bash
# 跑一个压测用例 3 次，结果放 bench/results/raw/<case>/run-N.json，日志放同目录 run-N.log。
# 用法：bench/run.sh <case-name> <k6-script> [KEY=VALUE ...]
#   bench/run.sh breakdown-none k6/12-breakdown.js MODE=NONE
# 每次运行前等 Kafka 积压清零、休息 10 秒，减少前一轮的影响。
set -euo pipefail
cd "$(dirname "$0")"
name="$1"; script="$2"; shift 2
runs="${RUNS:-3}"
out="results/raw/${name}"
mkdir -p "$out"
envs=()
for kv in "$@"; do envs+=(-e "$kv"); done

wait_lag() {
  for _ in $(seq 1 120); do
    lag=$(curl -fsS 'http://localhost:9095/api/v1/query?query=sum(kafka_consumergroup_lag)' 2>/dev/null \
          | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)' 2>/dev/null || echo 0)
    [ "$lag" -le 0 ] && return 0
    sleep 2
  done
  echo "warn: kafka lag still $lag" >&2
}

for i in $(seq 1 "$runs"); do
  wait_lag; sleep "${COOLDOWN:-10}"
  echo "== $name run $i/$runs"
  k6 run --quiet "${envs[@]}" -e OUT="$out/run-$i.json" "$script" 2>&1 | tee "$out/run-$i.log" | grep -E '^qps=|breaker|FAULT|RECOVER' || true
done
python3 report.py "$name"
