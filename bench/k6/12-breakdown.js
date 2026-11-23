// 缓存击穿：所有请求打同一个热点 key（1 号视频最热排序首页，回源是一次大 filesort），
// 每 10 秒让这个 key 过期一次。MODE=NONE|MUTEX|LOGICAL
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, summary, overrideToggles, setToggles, admin } from './lib/common.js';
import { Counter } from 'k6/metrics';

const errors = new Counter('hub_errors');
export const options = {
  scenarios: {
    reads: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '300'), duration: '60s', exec: 'reads' },
    expirer: { executor: 'constant-vus', vus: 1, duration: '60s', exec: 'expirer' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
  thresholds: { 'http_req_duration{scenario:reads}': ['p(99)<60000'], 'http_req_failed{scenario:reads}': ['rate<1'] },
};

export function setup() {
  const previous = overrideToggles({ hotMode: 'DB', cacheEnabled: true, breakdown: __ENV.MODE || 'MUTEX', localCache: false, summaryEnabled: false, cacheTtlSeconds: 300 });
  admin(`${BASE}/admin/cache/flush?prefix=cm:`);
  return previous;
}

export function reads() {
  const r = http.get(`${BASE}/api/videos/1/comments?sort=hot`, { timeout: '30s' });
  if (!check(r, { ok: (res) => res.status === 200 })) errors.add(1);
}

export function expirer() {
  sleep(10);
  admin(`${BASE}/admin/cache/expire?prefix=cm:hot:1&seconds=0`);
}

export { setToggles as teardown, summary as handleSummary };
