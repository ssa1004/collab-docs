// 문서 검색 latency 시나리오.
//
// GET /api/search?q=...&limit=... — 키워드 full-text 검색. 기본(zero-infra)은 in-memory
// 인덱스, prod 프로필은 OpenSearch (ADR-0001). 어느 백엔드든 동일 endpoint 라 같은
// 시나리오로 비교 가능하다.
//
// setup 에서 검색 대상 문서 몇 개를 시드해 hit 이 비지 않도록 한다.
//
// thresholds:
//   - http_req_duration{name:search} p95 < 100ms / p99 < 250ms
//     (in-memory 기준. OpenSearch 면 절대값이 다르므로 환경에 맞게 조정)
//   - http_req_failed rate < 1%

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, OWNER, randomQuery } from '../lib/config.js';
import { authHeader, jsonHeaders } from '../lib/auth.js';

const searchLatency = new Trend('search_query_latency_ms', true);

export const options = {
  scenarios: {
    search: {
      executor: 'constant-arrival-rate',
      rate: 200,            // 초당 200 req — read 경로라 RPS 를 높게.
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:search}': ['p(95)<100', 'p(99)<250'],
    search_query_latency_ms: ['p(95)<100'],
  },
};

// setup: 검색어 pool 의 토큰이 본문에 등장하는 문서들을 시드. 인덱스가 비면 검색 hit 이
// 항상 0 이라 latency 분포는 봐도 정확성 check 가 무의미해지므로, 몇 개 미리 만든다.
export function setup() {
  const seeds = [
    { title: 'Operational transform 개요', content: 'Operational transform keeps concurrent edits convergent across a shared document.' },
    { title: '검색 동작', content: 'Search indexes document content so a keyword query returns ranked hits.' },
    { title: '편집 충돌', content: 'When two users edit at the same position, the server transforms one op to converge.' },
  ];
  let created = 0;
  for (const s of seeds) {
    const r = http.post(`${BASE_URL}/api/documents`, JSON.stringify(s), {
      headers: jsonHeaders(OWNER),
    });
    if (r.status === 200) created += 1;
  }
  return { seeded: created };
}

export default function () {
  const q = randomQuery();
  const url = `${BASE_URL}/api/search?q=${encodeURIComponent(q)}&limit=10`;

  const res = http.get(url, {
    headers: authHeader(OWNER),
    tags: { name: 'search' },
  });
  searchLatency.add(res.timings.waiting); // TTFB — server-side query latency 근사.

  check(res, {
    'status 200': (r) => r.status === 200,
    'body is array': (r) => {
      const b = r.body || '';
      return b.startsWith('[');
    },
  });

  sleep(0.05);
}
