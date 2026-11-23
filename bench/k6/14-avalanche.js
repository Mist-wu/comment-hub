// 缓存雪崩。预热最多 30 秒，读从第 10 秒开始、持续 90 秒。
// 不能把这套时间和历史表里的 “5 秒写入 3000 个 key” 当成同一件事。
// JITTER=0 对比 JITTER=30。warm 检查首页是否有评论；summary.extra 记下计划 key 数和两个时间窗口。
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { BASE, summary, overrideToggles, setToggles, admin } from './lib/common.js';

const N = parseInt(__ENV.N || '3000');
export const options = {
  scenarios: {
    warm: { executor: 'shared-iterations', vus: Math.min(100, N), iterations: N, maxDuration: '30s', exec: 'warm' },
    reads: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '200'), duration: '90s', startTime: '10s', exec: 'reads' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
  thresholds: {
    'http_req_duration{scenario:reads}': ['p(99)<60000'],
    'http_reqs{scenario:reads}': ['count>0'],
    'checks{scenario:warm}': ['rate==1'],
  },
};

export function setup() {
  const previous = overrideToggles({ hotMode: 'DB', cacheEnabled: true, breakdown: 'MUTEX', localCache: false, summaryEnabled: false,
            cacheTtlSeconds: 30, ttlJitterSeconds: parseInt(__ENV.JITTER || '0') });
  admin(`${BASE}/admin/cache/flush?prefix=cm:`);
  return previous;
}

export function warm() {
  const v = exec.scenario.iterationInTest + 1;
  const res = http.get(`${BASE}/api/videos/${v}/comments?sort=hot`, { tags: { name: 'warm' } });
  check(res, { 'warm page has comments': (r) => r.status === 200 && r.json('items')?.length > 0 });
}

export function reads() {
  const v = 1 + Math.floor(Math.random() * N);
  check(http.get(`${BASE}/api/videos/${v}/comments?sort=hot`, { timeout: '30s' }), { ok: (r) => r.status === 200 });
}

export { setToggles as teardown };
export function handleSummary(d) {
  return summary(d, {
    script: '14-avalanche.js',
    plannedKeys: N,
    warmMaxDurationSec: 30,
    readsStartSec: 10,
    readsDurationSec: 90,
    scenarioWindows: { '{scenario:reads}': 90 },
  });
}
