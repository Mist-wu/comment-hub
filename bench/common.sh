# shellcheck shell=bash
# bench/run.sh 和 scripts/incidents/lib.sh 共用：管理接口、开关默认值、在 Colima VM 里跑 k6。
COMMON_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

post() { curl -fsS -X POST "$1" -H 'Content-Type: application/json' -d "${2:-{\}}" >/dev/null; }

# 四个服务的开关默认值，和代码里的默认值（优化版）一致
CS_DEFAULT='{"hotMode":"ZSET","cacheEnabled":true,"breakdown":"MUTEX","mutexLockTtlMs":30000,"mutexFallback":"FAIL","penetration":"BLOOM","cacheTtlSeconds":300,"ttlJitterSeconds":60,"localCache":true,"hotKeyThreshold":200,"localTtlMillis":1000,"likeMode":"REDIS","countMode":"COUNTER","syncWrite":false,"summaryEnabled":true,"summaryDeadlineMs":150,"summaryMinComments":1000}'
GW_DEFAULT='{"llmTimeoutMs":3000,"bulkheadEnabled":true,"bulkheadMaxConcurrent":32,"bulkheadWaitMs":20,"summaryAsync":true,"rateLimitEnabled":true,"circuitBreakerEnabled":true}'
CONSUMER_DEFAULT='{"moderationFailRate":0.0,"moderationDeadlineMs":5000}'
MOCK_DEFAULT='{"latencyMs":300,"jitterMs":200,"errorRate":0.0,"timeoutRate":0.0,"hangMs":120000,"badJsonRate":0.0}'

reset_toggles() {
  local rc=0
  post localhost:8080/admin/toggles "$CS_DEFAULT" || rc=1
  post localhost:8081/admin/toggles "$GW_DEFAULT" || rc=1
  post localhost:8082/admin/toggles "$CONSUMER_DEFAULT" || rc=1
  post localhost:8000/admin/config "$MOCK_DEFAULT" || rc=1
  post localhost:8081/admin/breaker/reset || rc=1
  return "$rc"
}

# 事故脚本删索引后设置恢复函数。
TOGGLE_RESET_HOOK=:
# run.sh 把 k6 放到后台再 wait，这里记下 pid。信号到来时先停掉它，恢复开关不用等它跑完。
K6_PID=

# 正常结束、set -e 中途失败、INT、TERM 都回到默认开关，不把本次覆盖留在进程里。
on_toggle_exit() {
  local rc=$?
  trap - EXIT INT TERM
  if [ -n "${K6_PID:-}" ]; then
    kill "$K6_PID" 2>/dev/null || true
    wait "$K6_PID" 2>/dev/null || true
    K6_PID=
  fi
  "$TOGGLE_RESET_HOOK" || rc=1
  reset_toggles || rc=1
  exit "$rc"
}
install_toggle_reset_trap() {
  trap on_toggle_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

# k6 在 Colima VM 里的容器中运行、直接走 compose 网络，不经过宿主机的端口转发。
# 模型故障场景的朴素版和复盘 02 会堆积大量挂起连接，Colima 的端口转发在这种负载下两次断开过。
k6_in_vm() {
  colima ssh -- docker run --rm --user "$(id -u):$(id -g)" --network comment-hub_default -v "$COMMON_ROOT:$COMMON_ROOT" -w "$PWD" \
    -e BASE=http://comment-service:8080 -e GATEWAY=http://ai-gateway:8081 -e MOCK=http://mock-llm:8000 \
    -e VUS="${VUS:-}" -e DURATION="${DURATION:-}" grafana/k6:2.4.0 "$@"
}
