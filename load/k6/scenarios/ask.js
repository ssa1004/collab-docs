// 문서 RAG 질의 시나리오 (ask).
//
// POST /api/documents/{id}/ask — 문서 내용에 대한 자연어 질의. 기본(zero-infra)은 offline
// 결정론 AI (ADR-0004): 임베딩/LLM 외부 호출 없이 문서에서 추출해 답하므로 응답이
// 결정론적이고 네트워크 변수가 없다. prod 프로필은 Spring AI 로 실 LLM 에 붙는다.
//
// offline 모드는 LLM round-trip 이 없어 read 경로 중 가장 가볍다 — 부하의 관심은 RAG
// 파이프라인(검색 → context 조립 → 답변 생성)의 server-side 비용과, citedOrdinals 가
// 일관되게 채워지는지(invariant)다.
//
// setup 에서 답이 본문에 있는 문서 하나를 시드하고 모든 VU 가 그 문서에 질의한다.
//
// thresholds:
//   - http_req_duration{name:ask} p95 < 200ms / p99 < 500ms (offline 기준)
//   - http_req_failed rate < 1%
//   - ask_no_answer count == 0 (invariant — offline 은 항상 답 문자열을 채운다)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, OWNER, randomQuestion } from '../lib/config.js';
import { authHeader, jsonHeaders } from '../lib/auth.js';

const askLatency = new Trend('ask_latency_ms', true);
const askNoAnswer = new Counter('ask_no_answer');
const askOffline = new Counter('ask_offline_responses');

export const options = {
  scenarios: {
    ask: {
      executor: 'constant-arrival-rate',
      rate: 50,             // 초당 50 req — RAG 파이프라인이라 검색보다 무거워 보수적.
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 30,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:ask}': ['p(95)<200', 'p(99)<500'],
    ask_latency_ms: ['p(95)<200'],
    ask_no_answer: ['count==0'],
  },
};

// setup: 질문 pool 의 답이 본문에 있는 문서 하나를 만들어 docId 를 공유.
export function setup() {
  const res = http.post(
    `${BASE_URL}/api/documents`,
    JSON.stringify({
      title: 'k6 ask target',
      content:
        'collab-docs supports realtime editing. Operational transform keeps concurrent edits convergent. '
        + 'Search indexes the document so a keyword query returns ranked hits. '
        + 'When two users edit the same position, the server transforms one op so both survive.',
    }),
    { headers: jsonHeaders(OWNER) },
  );
  check(res, { 'setup: ask doc created': (r) => r.status === 200 });
  const docId = res.json('id');
  if (!docId) {
    throw new Error(`setup 실패: ask 대상 문서 id 없음 (status ${res.status})`);
  }
  return { docId };
}

export default function (data) {
  const body = JSON.stringify({ question: randomQuestion(), topK: 3 });

  const res = http.post(`${BASE_URL}/api/documents/${data.docId}/ask`, body, {
    headers: jsonHeaders(OWNER),
    tags: { name: 'ask' },
  });
  askLatency.add(res.timings.duration);

  if (res.status === 200) {
    const answer = res.json('answer');
    if (!answer || String(answer).length === 0) {
      askNoAnswer.add(1); // offline 은 항상 답을 채워야 한다 (invariant).
    }
    if (res.json('offline') === true) askOffline.add(1);
  }

  check(res, {
    'status 200': (r) => r.status === 200,
    'has answer': (r) => {
      try {
        return String(r.json('answer') || '').length > 0;
      } catch (_e) {
        return false;
      }
    },
  });

  sleep(0.1);
}
