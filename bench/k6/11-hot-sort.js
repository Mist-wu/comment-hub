// 最热排序：实时 ORDER BY like_count（不走缓存）vs 定时算好的 ZSet。MODE=DB|ZSET
import http from 'k6/http';
import { check } from 'k6';
import { BASE, zipfVideo, summary, toggles, admin } from './lib/common.js';

export const options = {
  setupTimeout: '300s',
  scenarios: { hot: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '50'), duration: __ENV.DURATION || '30s' } },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
};

export function setup() {
  toggles({ hotMode: __ENV.MODE || 'ZSET', cacheEnabled: false, summaryEnabled: false });
  if ((__ENV.MODE || 'ZSET') === 'ZSET') admin(`${BASE}/admin/hot-rank/rebuild`);
}

export default function () {
  let v = zipfVideo();
  if (v > 100) v = (v % 100) + 1; // 只测进榜单的前 100 个视频
  check(http.get(`${BASE}/api/videos/${v}/comments?sort=hot`, { timeout: '60s' }), { ok: (r) => r.status === 200 });
}

export function teardown() { toggles({ hotMode: 'ZSET', cacheEnabled: true, summaryEnabled: true }); }
export function handleSummary(d) { return summary(d); }
