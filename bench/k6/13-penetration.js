// 缓存穿透：一半请求查不存在的视频（随机 ID，无法靠缓存空值挡住），一半正常请求。MODE=NONE|NULL_VALUE|BLOOM
// 不存在的 ID 从固定的 2 万个里随机选，NULL_VALUE 在重复访问时才有效果。
import http from 'k6/http';
import { check } from 'k6';
import { BASE, VIDEOS, zipfVideo, summary, toggles, admin } from './lib/common.js';

export const options = {
  scenarios: { mix: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '200'), duration: '30s' } },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
  thresholds: { 'http_req_duration{name:missing}': ['p(99)<60000'], 'http_req_duration{name:normal}': ['p(99)<60000'] },
};

export function setup() {
  toggles({ penetration: __ENV.MODE || 'BLOOM', cacheEnabled: true, summaryEnabled: false });
  admin(`${BASE}/admin/cache/flush?prefix=cm:`);
}

export default function () {
  if (Math.random() < 0.5) {
    const id = VIDEOS + 1 + Math.floor(Math.random() * 20000);
    check(http.get(`${BASE}/api/videos/${id}/comments?sort=latest`, { tags: { name: 'missing' } }), { ok: (r) => r.status === 200 });
  } else {
    check(http.get(`${BASE}/api/videos/${zipfVideo()}/comments?sort=latest`, { tags: { name: 'normal' } }), { ok: (r) => r.status === 200 });
  }
}

export function teardown() { toggles({ penetration: 'BLOOM' }); }
export function handleSummary(d) { return summary(d); }
