# shellcheck shell=bash
# 复盘脚本公共函数。在仓库根目录执行。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/bench/common.sh"
mkdir -p logs
EV=$(mktemp -d "logs/$(basename "$0" .sh).XXXXXX")
echo "本次复盘输出到 $EV"
install_toggle_reset_trap
PY="$ROOT/.venv/bin/python"; [ -x "$PY" ] || PY=python3
my() { docker compose exec -T mysql mysql --default-character-set=utf8mb4 -uroot -proot comment_hub "$@"; }
# 先确认索引存在，再登记清理动作，覆盖 DROP 执行期间收到信号的情况。
drop_index() {
  INDEX_NAME=$1
  INDEX_COLUMNS=$2
  INDEX_QUERY="SHOW INDEX FROM comment WHERE Key_name = '$INDEX_NAME'"
  local existing
  existing=$(my -N -e "$INDEX_QUERY")
  if [ -z "$existing" ]; then echo "缺少演练所需索引 $INDEX_NAME" >&2; return 1; fi
  TOGGLE_RESET_HOOK=restore_index
  my -e "ALTER TABLE comment DROP INDEX $INDEX_NAME"
}
restore_index() {
  local existing
  existing=$(my -N -e "$INDEX_QUERY") || return 1
  if [ -z "$existing" ]; then
    my -e "ALTER TABLE comment ADD KEY $INDEX_NAME ($INDEX_COLUMNS)" || return 1
  fi
  TOGGLE_RESET_HOOK=:
}
# 慢查询日志的 Time 是 UTC。BSD date 认 -r，GNU date 认 -d。
utc_stamp() { date -u -r "$1" +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -d "@$1" +%Y-%m-%dT%H:%M:%S; }
# 复盘 02 会堆积大量挂起连接，宿主机的端口转发扛不住，所以复盘的 k6 都在 VM 里跑
k6() { k6_in_vm "$@"; }
# k6 一次，结果写到 $1.json / $1.log，打印摘要行
k6run() {
  local name="$1" script="$2"; shift 2
  local envs=(); for kv in "$@"; do envs+=(-e "$kv"); done
  k6 run --quiet ${envs[@]+"${envs[@]}"} -e OUT="$EV/$name.json" "$script" > "$EV/$name.log" 2>&1 || true
  echo "[$name] $(grep -E '^qps=' "$EV/$name.log" | tail -1)"
}
chart() { "$PY" scripts/promchart.py "$@"; }
