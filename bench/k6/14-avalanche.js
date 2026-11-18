// 缓存雪崩：先在 5 秒内把前 3000 个视频的最热首页全部写进缓存（TTL 30 秒），然后均匀读 90 秒。
// 不加抖动时这批 key 会在同一时刻过期，回源集中爆发。JITTER=0 对比 JITTER=30。
import http from 'k6/http';
import { check } from 'k6';
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
  http.get(`${BASE}/api/videos/${(__ITER % N) + 1 + (__VU - 1) * 0}/comments?sort=hot`, { tags: { name: 'warm' } });
}

export function reads() {
  const v = 1 + Math.floor(Math.random() * N);
  check(http.get(`${BASE}/api/videos/${v}/comments?sort=hot`, { timeout: '30s' }), { ok: (r) => r.status === 200 });
}

export function teardown() { toggles({ hotMode: 'ZSET', cacheTtlSeconds: 300, ttlJitterSeconds: 60, localCache: true, summaryEnabled: true }); }
export function handleSummary(d) { return summary(d); }
