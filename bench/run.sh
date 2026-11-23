#!/usr/bin/env bash
# 跑一个压测用例 RUNS 次（默认 3），结果放 bench/results/raw/<case>/run-N.{json,log}。
#
# 用法：bench/run.sh <case> <k6-script> [KEY=VALUE ...]
#   环境变量 CS / GW / CONSUMER / MOCK：分别是 comment-service / ai-gateway / consumer / mock-llm 的开关 JSON，
#   每次运行前先恢复默认（优化版），再应用这里给的覆盖值。
#   例：CS='{"likeMode":"DB"}' bench/run.sh like-storm-naive k6/03-like-storm.js
#
# 每次运行前等 Kafka 积压清零并冷却 COOLDOWN 秒；运行后从 Prometheus 取该时间窗口的服务端指标。
set -euo pipefail
cd "$(dirname "$0")"
source ./common.sh
install_toggle_reset_trap
name="$1"; script="$2"; shift 2
[ ! -f "$script" ] && [ -f "${script#bench/}" ] && script="${script#bench/}"
runs="${RUNS:-3}"
out="results/raw/${name}"
rm -rf "$out"; mkdir -p "$out"
envs=()
for kv in "$@"; do envs+=(-e "$kv"); done

# K6_DOCKER=1：k6 在 Colima VM 里跑（见 common.sh 的 k6_in_vm），场景 4 用
if [ -n "${K6_DOCKER:-}" ]; then
  k6() { k6_in_vm "$@"; }
fi

apply_toggles() {
  reset_toggles
  [ -n "${CS:-}" ] && post localhost:8080/admin/toggles "$CS"
  [ -n "${GW:-}" ] && post localhost:8081/admin/toggles "$GW"
  [ -n "${CONSUMER:-}" ] && post localhost:8082/admin/toggles "$CONSUMER"
  [ -n "${MOCK:-}" ] && post localhost:8000/admin/config "$MOCK"
  return 0
}

wait_lag() {
  for _ in $(seq 1 300); do
    lag=$(curl -fsS 'http://localhost:9095/api/v1/query?query=sum(kafka_consumergroup_lag)' 2>/dev/null \
          | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(max(0, int(float(r[0]["value"][1]))) if r else 0)')
    [ "$lag" -le 0 ] && return 0
    sleep 2
  done
  echo "warn: kafka lag still $lag" >&2
}

for i in $(seq 1 "$runs"); do
  wait_lag; sleep "${COOLDOWN:-10}"
  apply_toggles
  [ -n "${BEFORE:-}" ] && eval "$BEFORE"
  echo "== $name run $i/$runs"
  start=$(date +%s)
  k6 run --quiet ${envs[@]+"${envs[@]}"} -e OUT="$out/run-$i.json" "$script" > "$out/run-$i.log" 2>&1 &
  K6_PID=$!
  wait "$K6_PID" || true
  K6_PID=
  end=$(date +%s)
  grep -E '^qps=|breaker|FAULT|RECOVER' "$out/run-$i.log" || true
  sleep 6  # 等 Prometheus 抓到最后一个点
  python3 prom.py "$out/run-$i.json" "$start" "$end"
done
python3 report.py "$name"
