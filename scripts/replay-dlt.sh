#!/usr/bin/env bash
# 重放死信：把 <topic>-dlt 里的消息发回 <topic>。消费者按事件 ID 幂等，重复重放是安全的。
# 用法：scripts/replay-dlt.sh comment-created   （或 like-changed）
set -euo pipefail
topic="${1:?usage: $0 <topic>}"
curl -fsS -X POST "http://${CONSUMER_HOST:-localhost}:8082/admin/dlt/replay?topic=${topic}"
echo
