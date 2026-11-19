# 复盘脚本公共函数。在仓库根目录执行。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
PY="$ROOT/.venv/bin/python"; [ -x "$PY" ] || PY=python3
post() { curl -fsS -X POST "$1" -H 'Content-Type: application/json' -d "${2:-{\}}" >/dev/null; }
my() { docker compose exec -T mysql mysql --default-character-set=utf8mb4 -uroot -proot comment_hub "$@" 2>/dev/null; }
reset_all() {
  post localhost:8080/admin/toggles '{"hotMode":"ZSET","cacheEnabled":true,"breakdown":"MUTEX","mutexLockTtlMs":30000,"mutexFallback":"FAIL","penetration":"BLOOM","cacheTtlSeconds":300,"ttlJitterSeconds":60,"localCache":true,"likeMode":"REDIS","countMode":"COUNTER","syncWrite":false,"summaryEnabled":true,"summaryDeadlineMs":150}'
  post localhost:8081/admin/toggles '{"llmTimeoutMs":3000,"bulkheadEnabled":true,"summaryAsync":true,"rateLimitEnabled":true,"circuitBreakerEnabled":true}'
  post localhost:8000/admin/config '{"latencyMs":300,"jitterMs":200,"errorRate":0.0,"timeoutRate":0.0,"hangMs":120000,"badJsonRate":0.0}'
  post localhost:8081/admin/breaker/reset
}
# k6 在 Colima VM 里的容器中运行、直接走 compose 网络（见 bench/run.sh 的 K6_DOCKER）。
# 复盘 02 会堆积大量挂起连接，宿主机的端口转发扛不住。
k6() {
  colima ssh -- docker run --rm --network comment-hub_default -v "$ROOT:$ROOT" -w "$ROOT" \
    -e BASE=http://comment-service:8080 -e GATEWAY=http://ai-gateway:8081 -e MOCK=http://mock-llm:8000 \
    -e VUS="${VUS:-}" -e DURATION="${DURATION:-}" grafana/k6:2.4.0 "$@"
}
# k6 一次，结果写到 $1.json / $1.log，打印摘要行
k6run() {
  local name="$1" script="$2"; shift 2
  local envs=(); for kv in "$@"; do envs+=(-e "$kv"); done
  k6 run --quiet "${envs[@]}" -e OUT="$EV/$name.json" "$script" > "$EV/$name.log" 2>&1 || true
  echo "[$name] $(grep -E '^qps=' "$EV/$name.log" | tail -1)"
}
chart() { "$PY" scripts/promchart.py "$@"; }
