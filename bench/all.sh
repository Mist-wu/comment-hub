#!/usr/bin/env bash
# 全部压测用例。每个用例跑 3 次取中位数；朴素版 / 优化版只差开关，同一个进程、同一份数据。
# 用法：bench/all.sh            全部
#       bench/all.sh main       只跑四个主场景
#       bench/all.sh micro      只跑专项对比
set -euo pipefail
cd "$(dirname "$0")"
what="${1:-all}"
R=./run.sh

# 朴素版读：不走缓存、最热实时排序、评论数 COUNT(*)、不拦不存在的视频
NAIVE_READ='{"cacheEnabled":false,"hotMode":"DB","localCache":false,"penetration":"NONE","breakdown":"NONE","countMode":"COUNT","likeMode":"DB"}'

main() {
  # 场景 1：热点读
  CS="$NAIVE_READ" $R read-hot-naive k6/01-read-hot.js
  $R read-hot-optimized k6/01-read-hot.js
  # 场景 2：突发写（朴素版在请求里同步计数、删缓存、调模型审核）
  CS='{"syncWrite":true}' $R write-burst-naive k6/02-write-burst.js
  $R write-burst-optimized k6/02-write-burst.js
  # 场景 3：点赞风暴
  CS='{"likeMode":"DB"}' $R like-storm-naive k6/03-like-storm.js
  $R like-storm-optimized k6/03-like-storm.js
  # 场景 4：模型故障（朴素版：摘要同步等模型、不设超时、无熔断无舱壁）
  CS='{"summaryDeadlineMs":0}' GW='{"summaryAsync":false,"llmTimeoutMs":0,"bulkheadEnabled":false,"circuitBreakerEnabled":false,"rateLimitEnabled":false}' \
    $R llm-fault-naive k6/04-llm-fault.js
  $R llm-fault-optimized k6/04-llm-fault.js
}

micro() {
  RUNS=3 $R pagination k6/10-pagination.js
  $R hot-sort-db k6/11-hot-sort.js MODE=DB
  $R hot-sort-zset k6/11-hot-sort.js MODE=ZSET
  for m in NONE MUTEX LOGICAL; do $R "breakdown-$(echo $m | tr A-Z a-z)" k6/12-breakdown.js MODE=$m; done
  for m in NONE NULL_VALUE BLOOM; do $R "penetration-$(echo $m | tr A-Z a-z | tr _ -)" k6/13-penetration.js MODE=$m; done
  $R avalanche-nojitter k6/14-avalanche.js JITTER=0
  $R avalanche-jitter k6/14-avalanche.js JITTER=30
  $R hotkey-redis k6/15-hot-key.js LOCAL=false
  $R hotkey-local k6/15-hot-key.js LOCAL=true
}

case "$what" in
  main) main ;;
  micro) micro ;;
  all) micro; main ;;
  *) "$what" ;;
esac
