#!/usr/bin/env bash
# 复盘 03：热点 key 过期 → 击穿 → 数据库连接池耗尽。
# 所有请求打 1 号视频最热首页，每 10 秒让这个 key 过期一次。三个阶段：
#   A 不加锁（breakdown=NONE），连接池被回源打满
#   B A 结束后立刻换成修复前的互斥锁：锁租期 5 秒，等锁 2 秒超时后自己回源
#   C 冷却后再来一次 A，结束后立刻换成修复后的互斥锁：锁租期 30 秒，等锁超时返回 503
# B、C 都在“数据库还没从 A 恢复”时开始，这正是事故现场：先止血上了互斥锁，但库还在吃力。
# 这次事故发生在修复最热排序慢 SQL（idx_video_like）之前：回源是 150 万行 filesort。
# 为了复现当时的状态，脚本先删掉 idx_video_like，结束时再建回来。
source "$(dirname "$0")/lib.sh"
EV=docs/incidents/evidence/03; rm -rf "$EV"; mkdir -p "$EV"
reset_all
my -e "ALTER TABLE comment DROP INDEX idx_video_like" || true
trap 'my -e "ALTER TABLE comment ADD KEY idx_video_like (video_id, like_count, id, status)" || true; reset_all' EXIT
export VUS=300

lines() { echo $(( $(wc -l < logs/comment-service.log) + 1 )); }
phase() {  # phase <名字> <MODE> [结束后等待秒数]
  local name="$1" mode="$2" rest="${3:-0}" from t0; from=$(lines); t0=$(date +%s)
  k6run "$name" bench/k6/12-breakdown.js MODE="$mode"
  tail -n +"$from" logs/comment-service.log > "$EV/comment-service-$name.log"
  # 每秒“拿不到连接”的错误数：grep + awk 按秒聚合
  # 异常行没有时间戳，取它前面最近一条日志行的时间
  awk '/^20[0-9][0-9]-/ {ts=substr($2,1,8)} /Connection is not available/ {print ts}' "$EV/comment-service-$name.log" \
    | sort | uniq -c > "$EV/pool-timeout-per-sec-$name.txt" || true
  printf '%-10s pool timeouts=%s  lock-wait timeouts(load directly)=%s\n' "$name" \
    "$(awk '{s+=$1} END {print s+0}' "$EV/pool-timeout-per-sec-$name.txt")" \
    "$(grep -c 'mutex wait timeout' "$EV/comment-service-$name.log" || true)" | tee -a "$EV/summary.txt"
  # 这一阶段的慢查询，按 SQL 指纹聚合（慢查询日志按结束时间记录，阶段结束时还在跑的会算到下一阶段）
  docker compose exec -T mysql sh -c 'cat /var/log/mysql/slow.log' 2>/dev/null \
    | awk -v a="$(date -u -r "$t0" +%Y-%m-%dT%H:%M:%S)" '/^# Time: /{p=(substr($3,1,19)>=a)} p' > "$EV/slow-$name.log"
  awk '/^# Query_time/{qt=$3; for(i=1;i<=NF;i++) if($i=="Rows_examined:") re=$(i+1); getline; while($0 ~ /^(SET|use)/) getline;
       s=$0; gsub(/[0-9]+/,"N",s); n[s]++; t[s]+=qt; r[s]+=re}
       END{for(s in n) printf "%5d %8.1fs avg_query_time=%.2fs avg_rows_examined=%d  %s\n", n[s], t[s], t[s]/n[s], r[s]/n[s], substr(s,1,120)}' \
    "$EV/slow-$name.log" | sort -k2 -rn > "$EV/slow-top-$name.txt"
  sleep "$rest"
}

T0=$(date +%s)
phase A1-none NONE
grep -m1 -B2 -A12 'Connection is not available' "$EV/comment-service-A1-none.log" > "$EV/sample-error.txt" || true
T1=$(date +%s)
post localhost:8080/admin/toggles '{"mutexLockTtlMs":5000,"mutexFallback":"DB"}'
phase B-mutex-old MUTEX 60

T2=$(date +%s)
phase A2-none NONE
T3=$(date +%s)
post localhost:8080/admin/toggles '{"mutexLockTtlMs":30000,"mutexFallback":"FAIL"}'
phase C-mutex-new MUTEX
T4=$(date +%s)

sleep 8
MARKS="$T0:A 不加锁,$T1:B 旧互斥锁,$T2:A 不加锁,$T3:C 新互斥锁" chart docs/incidents/img/03-breakdown-pool.png "$T0" "$T4" "复盘 03：热点 key 过期引发击穿" \
  'max(hikaricp_connections_active{application="comment-service"})|连接池 active' \
  'max(hikaricp_connections_pending{application="comment-service"})|连接池 pending' \
  'sum(increase(hub_cache_db_load_total[15s]))/15|回源次数/秒' \
  'mysql_global_status_threads_running|MySQL threads_running' \
  'sum by (status) (rate(http_server_requests_seconds_count{application="comment-service",status=~"5.."}[15s]))|5xx/秒' \
  'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="comment-service",uri="/api/videos/{videoId}/comments"}[15s])))|P99 (s)'
echo "$T0 $T1 $T2 $T3 $T4" > "$EV/times.txt"
