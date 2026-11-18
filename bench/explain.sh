#!/usr/bin/env bash
# 收集关键 SQL 的 EXPLAIN 和 EXPLAIN ANALYZE（实际执行，给出真实扫描行数和耗时），输出到 bench/results/explain.md。
# 视频 1 是最热的视频（约 150 万条评论）。游标取自第 N 页的上一页最后一条，由 SQL 直接算出。
set -euo pipefail
cd "$(dirname "$0")"
V="${VIDEO:-1}"
out=results/explain.md
my() { docker compose -f ../docker-compose.yml exec -T mysql mysql -uroot -proot comment_hub "$@" 2>/dev/null; }

cursor_at() { # 第 $1 页的游标 = 第 ($1-1)*20 条的 (created_at, id)
  local off=$(( ($1 - 1) * 20 - 1 ))
  my -N -e "SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'), id FROM comment WHERE video_id=$V AND status<>3 ORDER BY created_at DESC, id DESC LIMIT $off,1"
}

{
  echo "# 关键 SQL 的执行计划"
  echo
  echo "数据：视频 $V，$(my -N -e "SELECT COUNT(*) FROM comment WHERE video_id=$V") 条评论；comment 表共 $(my -N -e "SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='comment_hub' AND TABLE_NAME='comment'") 行（估算）。"
  echo "每条 SQL 先跑一次预热 buffer pool，再取 EXPLAIN ANALYZE。生成时间：$(date '+%F %T')。"
  for p in 1 100 1000; do
    off=$(( (p - 1) * 20 ))
    q="SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = $V AND status <> 3 ORDER BY created_at DESC, id DESC LIMIT $off, 20"
    echo; echo "## LIMIT offset，第 $p 页"; echo; echo '```sql'; echo "$q;"; echo '```'
    my -e "$q" >/dev/null
    echo '```'; my -t -e "EXPLAIN $q"; my -e "EXPLAIN ANALYZE $q" | sed 's/\\n/\n/g' | tail -n +2; echo '```'
    if [ "$p" -gt 1 ]; then
      IFS=$'\t' read -r ts id < <(cursor_at "$p")
      cq="SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = $V AND status <> 3 AND (created_at < '$ts' OR (created_at = '$ts' AND id < $id)) ORDER BY created_at DESC, id DESC LIMIT 20"
    else
      cq="SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = $V AND status <> 3 ORDER BY created_at DESC, id DESC LIMIT 20"
    fi
    echo; echo "## 游标，第 $p 页"; echo; echo '```sql'; echo "$cq;"; echo '```'
    my -e "$cq" >/dev/null
    echo '```'; my -t -e "EXPLAIN $cq"; my -e "EXPLAIN ANALYZE $cq" | sed 's/\\n/\n/g' | tail -n +2; echo '```'
  done
  for q in \
    "SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = $V AND status <> 3 ORDER BY like_count DESC, id DESC LIMIT 0, 20" \
    "SELECT COUNT(*) FROM comment WHERE video_id = $V"; do
    echo; echo "## $(echo "$q" | cut -c1-40)…"; echo; echo '```sql'; echo "$q;"; echo '```'
    my -e "$q" >/dev/null
    echo '```'; my -t -e "EXPLAIN $q"; my -e "EXPLAIN ANALYZE $q" | sed 's/\\n/\n/g' | tail -n +2; echo '```'
  done
} > "$out"
echo "wrote $out"
