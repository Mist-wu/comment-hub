// 热 key：所有请求打同一个 key（1 号视频最新首页）。LOCAL=false 只走 Redis；LOCAL=true 热 key 进本地 Caffeine。
import http from 'k6/http';
import { check } from 'k6';
import { BASE, summary, toggles } from './lib/common.js';

export const options = {
  scenarios: { hot: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '200'), duration: '30s' } },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
};

export function setup() {
  toggles({ cacheEnabled: true, localCache: __ENV.LOCAL === 'true', likeMode: 'DB', summaryEnabled: false });
}

export default function () {
  check(http.get(`${BASE}/api/videos/1/comments?sort=latest`), { ok: (r) => r.status === 200 });
}

export function teardown() { toggles({ localCache: true, likeMode: 'REDIS', summaryEnabled: true }); }
export function handleSummary(d) { return summary(d); }
