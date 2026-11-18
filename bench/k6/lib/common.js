// 公共工具：Zipf 采样（与 datagen 同一公式）、管理接口、summary 输出
import http from 'k6/http';

export const BASE = __ENV.BASE || 'http://localhost:8080';
export const GATEWAY = __ENV.GATEWAY || 'http://localhost:8081';
export const MOCK = __ENV.MOCK || 'http://localhost:8000';
export const VIDEOS = parseInt(__ENV.VIDEOS || '10000');
export const ZIPF_S = parseFloat(__ENV.ZIPF_S || '1.1');

let cdf = null;
function buildCdf() {
  cdf = new Float64Array(VIDEOS);
  let sum = 0;
  for (let r = 1; r <= VIDEOS; r++) { sum += 1 / Math.pow(r, ZIPF_S); cdf[r - 1] = sum; }
  for (let i = 0; i < VIDEOS; i++) cdf[i] /= sum;
}

/** 返回 1..VIDEOS 的视频 ID，1 号最热 */
export function zipfVideo() {
  if (cdf === null) buildCdf();
  const x = Math.random();
  let lo = 0, hi = VIDEOS - 1;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (cdf[mid] < x) lo = mid + 1; else hi = mid; }
  return lo + 1;
}

export function toggles(patch) {
  const r = http.post(`${BASE}/admin/toggles`, JSON.stringify(patch), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'admin' } });
  if (r.status !== 200) throw new Error(`toggles failed: ${r.status} ${r.body}`);
  return JSON.parse(r.body);
}

export function admin(url, body) {
  return http.post(url, body ? JSON.stringify(body) : null, { headers: { 'Content-Type': 'application/json' }, tags: { name: 'admin' } });
}

export const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };

/** 写一份精简 JSON（bench/report.py 读它）+ 标准文本摘要 */
export function summary(data, extra) {
  const out = { metrics: {}, extra: extra || {} };
  for (const [k, v] of Object.entries(data.metrics)) {
    if (k.startsWith('http_req_duration') || k.startsWith('http_reqs') || k.startsWith('http_req_failed') ||
        k.startsWith('checks') || k.startsWith('iterations') || k.startsWith('hub_')) out.metrics[k] = v.values;
  }
  out.state = { testRunDurationMs: data.state.testRunDurationMs };
  const file = __ENV.OUT || 'summary.json';
  return { [file]: JSON.stringify(out, null, 1), stdout: textLine(out) };
}

function textLine(out) {
  const d = out.metrics['http_req_duration{expected_response:true}'] || out.metrics['http_req_duration'] || {};
  const r = out.metrics['http_reqs'] || {};
  const f = out.metrics['http_req_failed'] || {};
  return `\nqps=${(r.rate || 0).toFixed(0)} p50=${(d.med || 0).toFixed(1)}ms p99=${(d['p(99)'] || 0).toFixed(1)}ms err=${((f.rate || 0) * 100).toFixed(2)}%\n`;
}
