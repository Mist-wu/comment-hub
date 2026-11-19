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
name="$1"; script="$2"; shift 2
runs="${RUNS:-3}"
out="results/raw/${name}"
rm -rf "$out"; mkdir -p "$out"
envs=()
for kv in "$@"; do envs+=(-e "$kv"); done

CS_DEFAULT='{"hotMode":"ZSET","cacheEnabled":true,"breakdown":"MUTEX","mutexLockTtlMs":30000,"mutexFallback":"FAIL","penetration":"BLOOM","cacheTtlSeconds":300,"ttlJitterSeconds":60,"localCache":true,"hotKeyThreshold":200,"localTtlMillis":1000,"likeMode":"REDIS","countMode":"COUNTER","syncWrite":false,"summaryEnabled":true,"summaryDeadlineMs":150,"summaryMinComments":1000}'
GW_DEFAULT='{"llmTimeoutMs":3000,"bulkheadEnabled":true,"bulkheadMaxConcurrent":32,"bulkheadWaitMs":20,"summaryAsync":true,"rateLimitEnabled":true,"circuitBreakerEnabled":true}'
CONSUMER_DEFAULT='{"moderationFailRate":0.0,"moderationDeadlineMs":5000}'
MOCK_DEFAULT='{"latencyMs":300,"jitterMs":200,"errorRate":0.0,"timeoutRate":0.0,"badJsonRate":0.0}'

# K6_DOCKER=1：k6 在 Colima VM 里的容器中运行、直接走 compose 网络，不经过宿主机的端口转发。
# 模型故障场景的朴素版会堆积大量挂起连接，Colima 的端口转发在这种负载下两次断开过。
if [ -n "${K6_DOCKER:-}" ]; then
  k6() {
    colima ssh -- docker run --rm --network comment-hub_default -v "$PWD:$PWD" -w "$PWD" \
      -e BASE=http://comment-service:8080 -e GATEWAY=http://ai-gateway:8081 -e MOCK=http://mock-llm:8000 \
      grafana/k6:2.4.0 "$@"
  }
fi

post() { curl -fsS -X POST "$1" -H 'Content-Type: application/json' -d "$2" >/dev/null; }

apply_toggles() {
  post localhost:8080/admin/toggles "$CS_DEFAULT";       [ -n "${CS:-}" ] && post localhost:8080/admin/toggles "$CS"
  post localhost:8081/admin/toggles "$GW_DEFAULT";       [ -n "${GW:-}" ] && post localhost:8081/admin/toggles "$GW"
  post localhost:8082/admin/toggles "$CONSUMER_DEFAULT"; [ -n "${CONSUMER:-}" ] && post localhost:8082/admin/toggles "$CONSUMER"
  post localhost:8000/admin/config "$MOCK_DEFAULT";      [ -n "${MOCK:-}" ] && post localhost:8000/admin/config "$MOCK"
  curl -fsS -X POST localhost:8081/admin/breaker/reset >/dev/null
  return 0
}

wait_lag() {
  for _ in $(seq 1 300); do
    lag=$(curl -fsS 'http://localhost:9095/api/v1/query?query=sum(kafka_consumergroup_lag)' 2>/dev/null \
          | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)' 2>/dev/null || echo 0)
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
  k6 run --quiet "${envs[@]}" -e OUT="$out/run-$i.json" "$script" > "$out/run-$i.log" 2>&1 || true
  end=$(date +%s)
  grep -E '^qps=|breaker|FAULT|RECOVER' "$out/run-$i.log" || true
  sleep 6  # 等 Prometheus 抓到最后一个点
  python3 prom.py "$out/run-$i.json" "$start" "$end"
done
apply_toggles
python3 report.py "$name"
