#!/usr/bin/env bash
# 不连正在跑的服务。用假的 curl / k6 / python3 看开关在正常结束和 TERM 之后是否回到默认值。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REAL_PY="$(command -v python3)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
BENCH="$tmp/bench"
mkdir -p "$BENCH"
cp "$ROOT/bench/run.sh" "$ROOT/bench/common.sh" "$BENCH/"
log="$tmp/curl.log"
mkdir -p "$tmp/bin" "$tmp/k6"

cat > "$tmp/bin/curl" << EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$log"
if [[ "\$*" == *9095* ]]; then
  printf '%s\n' '{"data":{"result":[]}}'
fi
exit 0
EOF
cat > "$tmp/bin/k6" << EOF
#!/usr/bin/env bash
if [[ "\${K6_SLEEP:-0}" != 0 ]]; then exec sleep "\$K6_SLEEP"; fi
exit 0
EOF
cat > "$tmp/bin/python3" << 'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "-c" ]]; then
  cat >/dev/null
  printf '%s\n' 0
fi
exit 0
EOF
chmod +x "$tmp/bin/curl" "$tmp/bin/k6" "$tmp/bin/python3"
touch "$tmp/k6/noop.js"

export PATH="$tmp/bin:$PATH"
export COOLDOWN=0
: > "$log"
RUNS=1 CS='{"likeMode":"DB"}' "$BENCH/run.sh" toggle-reset-fixture "$tmp/k6/noop.js" >/dev/null
"$REAL_PY" - "$log" << 'PY'
import sys
lines = open(sys.argv[1]).read().splitlines()
bodies = [line for line in lines if "admin/toggles" in line or "admin/config" in line or "breaker/reset" in line]
assert bodies, "no toggle posts"
assert any("likeMode" in b and "DB" in b for b in bodies), bodies
assert '"likeMode":"REDIS"' in bodies[-5]
assert '"breakdown":"MUTEX"' in bodies[-5]
assert '"hangMs":120000' in bodies[-2]
print("normal-exit ok")
PY

: > "$log"
RUNS=1 CS='{"likeMode":"DB"}' K6_SLEEP=30 "$BENCH/run.sh" toggle-reset-kill "$tmp/k6/noop.js" >/dev/null &
pid=$!
for _ in $(seq 1 50); do
  if grep -q '"likeMode":"DB"' "$log" 2>/dev/null; then break; fi
  sleep 0.1
done
kill -TERM "$pid"
status=0
wait "$pid" || status=$?
[ "$status" -eq 143 ]
"$REAL_PY" - "$log" << 'PY'
import sys
lines = open(sys.argv[1]).read().splitlines()
posts = [line for line in lines if "8080/admin/toggles" in line]
assert any('"likeMode":"DB"' in p for p in posts), posts
assert '"likeMode":"REDIS"' in posts[-1], posts[-1]
assert '"hotMode":"ZSET"' in posts[-1]
print("term-exit ok")
PY
echo "toggle reset ok"
