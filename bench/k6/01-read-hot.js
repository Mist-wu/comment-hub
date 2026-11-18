// 场景 1：热点视频读评论。视频按 Zipf(1.1) 选，70% 最新首页、20% 最热首页、10% 翻到第二页。
import http from 'k6/http';
import { check } from 'k6';
import { BASE, zipfVideo, summary } from './lib/common.js';

export const options = {
  scenarios: {
    reads: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '200'), duration: __ENV.DURATION || '60s' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
  thresholds: { 'http_req_duration{name:latest}': ['p(99)<60000'], 'http_req_duration{name:hot}': ['p(99)<60000'] },
};

export default function () {
  const v = zipfVideo();
  const x = Math.random();
  if (x < 0.7) {
    const r = http.get(`${BASE}/api/videos/${v}/comments?sort=latest`, { tags: { name: 'latest' } });
    check(r, { ok: (res) => res.status === 200 });
  } else if (x < 0.9) {
    const r = http.get(`${BASE}/api/videos/${v}/comments?sort=hot`, { tags: { name: 'hot' } });
    check(r, { ok: (res) => res.status === 200 });
  } else {
    const r = http.get(`${BASE}/api/videos/${v}/comments?sort=latest`, { tags: { name: 'latest' } });
    if (r.status === 200) {
      const c = r.json('nextCursor');
      if (c) check(http.get(`${BASE}/api/videos/${v}/comments?sort=latest&cursor=${c}`, { tags: { name: 'page2' } }), { ok: (res) => res.status === 200 });
    }
  }
}

export function handleSummary(d) { return summary(d); }
