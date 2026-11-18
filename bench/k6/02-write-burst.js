// 场景 2：突发写评论。10 秒内从 100/s 拉到 PEAK/s，保持 30 秒，再回落。视频按 Zipf 选。
import http from 'k6/http';
import { check } from 'k6';
import { BASE, zipfVideo, summary, JSON_HDR } from './lib/common.js';

const PEAK = parseInt(__ENV.PEAK || '3000');
export const options = {
  scenarios: {
    writes: {
      executor: 'ramping-arrival-rate', startRate: 100, timeUnit: '1s',
      preAllocatedVUs: 300, maxVUs: parseInt(__ENV.MAX_VUS || '1000'),
      stages: [{ target: PEAK, duration: '10s' }, { target: PEAK, duration: '30s' }, { target: 100, duration: '10s' }],
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
};

export default function () {
  const v = zipfVideo();
  const body = JSON.stringify({ userId: Math.floor(Math.random() * 1e6) + 1, content: `压测评论 ${__VU}-${__ITER} 这个视频真不错` });
  const r = http.post(`${BASE}/api/videos/${v}/comments`, body, JSON_HDR);
  check(r, { created: (res) => res.status === 201 });
}

export function handleSummary(d) { return summary(d); }
