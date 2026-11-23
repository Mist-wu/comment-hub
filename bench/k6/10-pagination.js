// LIMIT offset vs 游标：在最热的视频（默认 1 号）上，分别测第 1、100、1000 页。
// setup 里先用游标顺序翻到第 1000 页，记下第 1/100/1000 页的游标。
import http from 'k6/http';
import { check } from 'k6';
import { BASE, summary, overrideToggles, setToggles } from './lib/common.js';

const VIDEO = __ENV.VIDEO || '1';
const PAGES = [1, 100, 1000];
export const options = {
  setupTimeout: '300s',
  scenarios: Object.fromEntries(PAGES.flatMap((p, i) => [
    [`offset_p${p}`, { executor: 'constant-vus', vus: 10, duration: '20s', startTime: `${i * 44}s`, exec: 'offset', env: { PAGE: String(p) } }],
    [`cursor_p${p}`, { executor: 'constant-vus', vus: 10, duration: '20s', startTime: `${i * 44 + 22}s`, exec: 'cursor', env: { PAGE: String(p) } }],
  ])),
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
  thresholds: Object.fromEntries(PAGES.flatMap((p) => [
    [`http_req_duration{scenario:offset_p${p}}`, ['p(99)<60000']], [`http_req_duration{scenario:cursor_p${p}}`, ['p(99)<60000']]])),
};

export function setup() {
  const previous = overrideToggles({ cacheEnabled: false, summaryEnabled: false });
  const cursors = { 1: null };
  let c = null;
  for (let p = 1; p < 1000; p++) {
    const r = http.get(`${BASE}/api/videos/${VIDEO}/comments?sort=latest&size=20${c ? `&cursor=${c}` : ''}`, { tags: { name: 'setup' } });
    c = r.json('nextCursor');
    if (PAGES.includes(p + 1)) cursors[p + 1] = c;
  }
  return { cursors, previous };
}

export function offset() {
  const r = http.get(`${BASE}/api/videos/${VIDEO}/comments/by-page?page=${__ENV.PAGE}&size=20`);
  check(r, { ok: (res) => res.status === 200 && res.json('items').length === 20 });
}

export function cursor({ cursors }) {
  const c = cursors[__ENV.PAGE];
  const r = http.get(`${BASE}/api/videos/${VIDEO}/comments?sort=latest&size=20${c ? `&cursor=${c}` : ''}`);
  check(r, { ok: (res) => res.status === 200 && res.json('items').length === 20 });
}

export function teardown({ previous }) { setToggles(previous); }
export { summary as handleSummary };
