#!/usr/bin/env bash
# 复盘 02：模型调用不设超时。mock-llm 卡死不返回，摘要同步等模型、gRPC 不设 deadline、网关无超时无舱壁无熔断
# → comment-service 的 Tomcat 线程全部卡在 gRPC 调用上，连不调模型的接口也挂了。用 jstack 定位。
source "$(dirname "$0")/lib.sh"
EV=docs/incidents/evidence/02; rm -rf "$EV"; mkdir -p "$EV"
reset_toggles
export VUS=200 DURATION=60s

T0=$(date +%s)
k6run before bench/k6/01-read-hot.js

echo "inject: no timeout anywhere + mock-llm hangs"
post localhost:8080/admin/toggles '{"summaryDeadlineMs":0}'
post localhost:8081/admin/toggles '{"summaryAsync":false,"llmTimeoutMs":0,"bulkheadEnabled":false,"circuitBreakerEnabled":false,"rateLimitEnabled":false}'
post localhost:8000/admin/config '{"timeoutRate":1.0,"hangMs":120000}'
post "localhost:8080/admin/cache/flush?prefix=sum:"
T1=$(date +%s)
# 后台跑读流量，同时用一个探针打“不调模型”的评论数接口
k6 run --quiet -e OUT="$EV/broken.json" bench/k6/01-read-hot.js > "$EV/broken.log" 2>&1 &
K6=$!
sleep 25
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -m 5 -w "probe comment-count: http=%{http_code} time=%{time_total}s\n" localhost:8080/api/videos/5000/comment-count || true
done | tee "$EV/probe.txt"
# —— 定位证据：线程栈 ——
docker compose exec -T comment-service jstack 1 > "$EV/jstack-broken.txt"
docker compose exec -T comment-service jstat -gcutil 1 1000 3 > "$EV/jstat-broken.txt"
# 统计 Tomcat 工作线程卡在哪：取每个 http-nio 线程栈里第一条 io.commenthub / io.grpc 帧
awk '/^"http-nio-8080-exec/ {inT=1; st=""; next} inT && /java.lang.Thread.State/ {st=$2} inT && /at (io\.grpc|io\.commenthub)/ {print st, $2; inT=0} /^$/ {inT=0}' "$EV/jstack-broken.txt" \
  | sort | uniq -c | sort -rn | head -5 | tee "$EV/jstack-summary.txt"
grep -c '^"http-nio-8080-exec' "$EV/jstack-broken.txt" | sed 's/^/tomcat worker threads: /' | tee -a "$EV/jstack-summary.txt"
wait $K6 || true
echo "[broken] $(grep -E '^qps=' "$EV/broken.log" | tail -1)"
T2=$(date +%s)

echo "fix: restore deadline / async summary / timeout / bulkhead / breaker (mock-llm still hangs)"
docker compose restart comment-service >/dev/null   # 已卡住的线程要等 120 秒，直接重启释放
for _ in $(seq 1 60); do curl -fsS -o /dev/null localhost:8080/actuator/health && break; sleep 2; done
reset_toggles
post localhost:8000/admin/config '{"timeoutRate":1.0,"hangMs":120000}'
post "localhost:8080/admin/cache/flush?prefix=sum:"
T3=$(date +%s)
k6run after bench/k6/01-read-hot.js
docker compose exec -T comment-service jstack 1 > "$EV/jstack-fixed.txt"
curl -s localhost:8081/admin/breaker > "$EV/breaker-after.json"
T4=$(date +%s)
reset_toggles
sleep 8
MARKS="$T1:去掉超时+模型卡死,$T3:恢复超时/舱壁/熔断" chart docs/incidents/img/02-no-timeout.png "$T0" "$T4" "复盘 02：模型调用不设超时" \
  'max(tomcat_threads_busy_threads{application="comment-service"})|Tomcat 忙线程' \
  'sum(rate(http_server_requests_seconds_count{application="comment-service",uri!~"/actuator.*|/admin.*"}[10s]))|完成的请求/秒' \
  'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="comment-service",uri!~"/actuator.*|/admin.*"}[10s])))|P99 (s)' \
  'sum by (outcome) (rate(hub_ai_calls_total{task="summarize"}[10s]))|网关摘要调用结果/秒'
echo "$T0 $T1 $T2 $T3 $T4" > "$EV/times.txt"
