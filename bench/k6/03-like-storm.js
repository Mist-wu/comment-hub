// 场景 3：点赞风暴。所有请求点同一条评论，每次是不同用户（都是新点赞，不会被唯一键挡掉）。
import http from 'k6/http';
import { check } from 'k6';
import { BASE, summary, JSON_HDR } from './lib/common.js';

const COMMENT = __ENV.COMMENT || '2';
// 每轮用不同的用户号段，避免第二次跑时全变成重复点赞
const USER_BASE = parseInt(__ENV.USER_BASE || String(10_000_000 + Math.floor(Math.random() * 1e6) * 1000));

export const options = {
  scenarios: { likes: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '200'), duration: __ENV.DURATION || '30s' } },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
};

export default function () {
  const user = USER_BASE + __VU * 1_000_000 + __ITER;
  const r = http.post(`${BASE}/api/comments/${COMMENT}/like`, JSON.stringify({ userId: user }), JSON_HDR);
  check(r, { ok: (res) => res.status === 200 });
}

export { summary as handleSummary };
