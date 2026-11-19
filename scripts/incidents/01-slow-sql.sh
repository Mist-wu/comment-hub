#!/usr/bin/env bash
# 复盘 01：误删索引 idx_video_created，造出慢 SQL。用慢查询日志 + EXPLAIN 定位，重建索引修复。
source "$(dirname "$0")/lib.sh"
EV=docs/incidents/evidence/01; rm -rf "$EV"; mkdir -p "$EV"
reset_all
export VUS=100 DURATION=60s
T0=$(date +%s)
k6run before bench/k6/01-read-hot.js
slow_before=$(docker compose exec -T mysql sh -c 'wc -l < /var/log/mysql/slow.log')

T1=$(date +%s)
echo "inject: DROP INDEX idx_video_created"
my -e "ALTER TABLE comment DROP INDEX idx_video_created"
k6run broken bench/k6/01-read-hot.js
T2=$(date +%s)

# —— 定位证据 ——
docker compose exec -T mysql sh -c "tail -n +$slow_before /var/log/mysql/slow.log" > "$EV/slow.log"
# 按 SQL 指纹聚合（镜像里没有 perl，用 awk 代替 mysqldumpslow）：次数、总耗时、平均扫描行数
awk '/^# Query_time/{qt=$3; for(i=1;i<=NF;i++){if($i=="Rows_examined:") re=$(i+1); if($i=="Sort_rows:") so=$(i+1)}; getline; while($0 ~ /^(SET|use)/) getline;
     s=$0; gsub(/[0-9]+/,"N",s); n[s]++; t[s]+=qt; r[s]+=re; sr[s]+=so}
     END{for(s in n) printf "%5d  %8.1fs  avg_rows_examined=%d avg_sort_rows=%d  %s\n", n[s], t[s], r[s]/n[s], sr[s]/n[s], substr(s,1,140)}' \
  "$EV/slow.log" | sort -k2 -rn > "$EV/slow-top.txt"
# 每条慢查询的扫描行数分布：awk 抽 Rows_examined
awk '/^# Query_time/ {for (i=1;i<=NF;i++) if ($i=="Rows_examined:") print $(i+1)}' "$EV/slow.log" \
  | sort -n | awk '{a[NR]=$1} END {if (NR) printf "slow queries=%d  rows_examined p50=%d max=%d\n", NR, a[int(NR/2)+1], a[NR]}' > "$EV/rows-examined.txt"
cat "$EV/rows-examined.txt"
my -t -e "EXPLAIN SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 42 AND status <> 3 ORDER BY created_at DESC, id DESC LIMIT 20" > "$EV/explain-broken.txt"
my -e "SHOW INDEX FROM comment" > "$EV/show-index-broken.txt"

echo "fix: ADD KEY idx_video_created"
T3=$(date +%s)
{ time my -e "ALTER TABLE comment ADD KEY idx_video_created (video_id, created_at, id)"; } 2> "$EV/rebuild-time.txt"
my -t -e "EXPLAIN SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 42 AND status <> 3 ORDER BY created_at DESC, id DESC LIMIT 20" > "$EV/explain-fixed.txt"
T4=$(date +%s)
k6run after bench/k6/01-read-hot.js
T5=$(date +%s)
sleep 8
MARKS="$T1:删索引,$T3:重建索引" chart docs/incidents/img/01-slow-sql.png "$T0" "$T5" "复盘 01：删索引前后" \
  'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="comment-service",uri="/api/videos/{videoId}/comments"}[10s])))|评论列表 P99 (s)' \
  'sum(rate(http_server_requests_seconds_count{application="comment-service",uri="/api/videos/{videoId}/comments"}[10s]))|QPS' \
  'rate(mysql_global_status_slow_queries[10s])|慢查询/秒' \
  'mysql_global_status_threads_running|MySQL threads_running' \
  'max(hikaricp_connections_pending{application="comment-service"})|连接池等待数'
echo "$T0 $T1 $T2 $T3 $T4 $T5" > "$EV/times.txt"
