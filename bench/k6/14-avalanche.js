// 缓存雪崩。预热最多 30 秒，读从第 10 秒开始、持续 90 秒。
// 不能把这套时间和历史表里的 “5 秒写入 3000 个 key” 当成同一件事。
// JITTER=0 对比 JITTER=30。warm 会检查 HTTP 状态；summary.extra 记下计划 key 数和两个时间窗口。
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { BASE, summary, toggles, admin } from './lib/common.js';

const N = parseInt(__ENV.N || '3000');
export const options = {
  scenarios: {
    warm: { executor: 'shared-iterations', vus: 100, iterations: N, maxDuration: '30s', exec: 'warm' },
    reads: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '200'), duration: '90s', startTime: '10s', exec: 'reads' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
  thresholds: { 'http_req_duration{scenario:reads}': ['p(99)<60000'] },
};

export function setup() {
  toggles({ hotMode: 'DB', cacheEnabled: true, breakdown: 'MUTEX', localCache: false, summaryEnabled: false,
            cacheTtlSeconds: 30, ttlJitterSeconds: parseInt(__ENV.JITTER || '0') });
  admin(`${BASE}/admin/cache/flush?prefix=cm:`);
}

export function warm() {
  const v = (exec.scenario.iterationInTest % N) + 1;
  const res = http.get(`${BASE}/api/videos/${v}/comments?sort=hot`, { tags: { name: 'warm' } });
  check(res, { 'warm ok': (r) => r.status === 200 });
}

export function reads() {
  const v = 1 + Math.floor(Math.random() * N);
  check(http.get(`${BASE}/api/videos/${v}/comments?sort=hot`, { timeout: '30s' }), { ok: (r) => r.status === 200 });
}

export function teardown() { toggles({ hotMode: 'ZSET', cacheTtlSeconds: 300, ttlJitterSeconds: 60, localCache: true, summaryEnabled: true }); }
export function handleSummary(d) {
  return summary(d, {
    script: '14-avalanche.js',
    plannedKeys: N,
    warmMaxDurationSec: 30,
    readsStartSec: 10,
    readsDurationSec: 90,
    scenarioWindows: { '{scenario:reads}': 90 },
    note: '预热最长 30 秒，读从第 10 秒开始。不能说明 3000 个 key 在 5 秒内写完。',
  });
}
