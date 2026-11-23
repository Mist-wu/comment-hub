// 场景 4：模型故障。读流量（带摘要）+ 少量写流量（触发异步审核）持续 TOTAL 秒；
// 第 FAULT_AT 秒把 mock-llm 改成 10 秒延迟 + 50% 错误，第 RECOVER_AT 秒恢复。
// 另一个场景每秒查一次熔断器状态，打印状态变化的时刻。
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, GATEWAY, MOCK, zipfVideo, summary, admin, JSON_HDR } from './lib/common.js';

const TOTAL = parseInt(__ENV.TOTAL || '180');
const FAULT_AT = parseInt(__ENV.FAULT_AT || '30');
const RECOVER_AT = parseInt(__ENV.RECOVER_AT || '120');
const RATE = parseInt(__ENV.RATE || '800');

export const options = {
  scenarios: {
    reads: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${TOTAL}s`, preAllocatedVUs: 200, maxVUs: 2000, exec: 'reads' },
    writes: { executor: 'constant-arrival-rate', rate: 30, timeUnit: '1s', duration: `${TOTAL}s`, preAllocatedVUs: 20, exec: 'writes' },
    chaos: { executor: 'shared-iterations', vus: 1, iterations: 1, exec: 'chaos', maxDuration: `${TOTAL + 10}s` },
    probe: { executor: 'constant-vus', vus: 1, duration: `${TOTAL}s`, exec: 'probe' },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
  thresholds: { 'http_req_duration{scenario:reads}': ['p(99)<60000'], 'http_req_failed{scenario:reads}': ['rate<1'],
                'http_req_duration{scenario:writes}': ['p(99)<60000'], 'http_req_failed{scenario:writes}': ['rate<1'],
                'http_reqs{scenario:reads}': ['count>0'], 'http_reqs{scenario:writes}': ['count>0'] },
};

export function reads() {
  // 只打评论数 ≥1000 的热门视频（前几百名），每次首页都会尝试拿摘要
  let v = zipfVideo();
  if (v > 300) v = (v % 300) + 1;
  const r = http.get(`${BASE}/api/videos/${v}/comments?sort=latest`, { tags: { name: 'latest' }, timeout: '60s' });
  check(r, { ok: (res) => res.status === 200 });
}

export function writes() {
  const body = JSON.stringify({ userId: Math.floor(Math.random() * 1e6) + 1, content: `故障期间的评论 ${__ITER}` });
  check(http.post(`${BASE}/api/videos/${zipfVideo()}/comments`, body, Object.assign({ timeout: '60s' }, JSON_HDR)), { created: (res) => res.status === 201 });
}

export function chaos() {
  admin(`${MOCK}/admin/config`, { latencyMs: 300, jitterMs: 200, errorRate: 0, timeoutRate: 0 });
  sleep(FAULT_AT);
  // 清掉摘要缓存，让读流量真的去调模型
  admin(`${BASE}/admin/cache/flush?prefix=sum:`);
  admin(`${MOCK}/admin/config`, { latencyMs: 10000, jitterMs: 0, errorRate: 0.5 });
  console.log(`t=${FAULT_AT}s FAULT injected: latency=10s errorRate=50%`);
  sleep(RECOVER_AT - FAULT_AT);
  admin(`${MOCK}/admin/config`, { latencyMs: 300, jitterMs: 200, errorRate: 0 });
  console.log(`t=${RECOVER_AT}s RECOVERED`);
}

let last = '';
const t0 = Date.now();
export function probe() {
  const r = http.get(`${GATEWAY}/admin/breaker`, { tags: { name: 'probe' } });
  if (r.status === 200) {
    const s = r.json('state');
    if (s !== last) { console.log(`t=${((Date.now() - t0) / 1000).toFixed(1)}s breaker ${last || '-'} -> ${s} failureRate=${r.json('failureRate')} slowRate=${r.json('slowCallRate')}`); last = s; }
  }
  sleep(1);
}

export { summary as handleSummary };
