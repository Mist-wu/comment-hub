#!/usr/bin/env bash
# 场景 4：模型故障。k6 在 VM 内的容器里跑（见 run.sh 的 K6_DOCKER）
set -uo pipefail
cd "$(dirname "$0")"
export K6_DOCKER=1
CS='{"summaryDeadlineMs":0}' GW='{"summaryAsync":false,"llmTimeoutMs":0,"bulkheadEnabled":false,"circuitBreakerEnabled":false,"rateLimitEnabled":false}' \
  ./run.sh llm-fault-naive k6/04-llm-fault.js
./run.sh llm-fault-optimized k6/04-llm-fault.js
echo ALL-DONE
