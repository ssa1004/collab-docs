// 동시 편집 contention 시나리오 — 본 service 의 핵심 부하.
//
// 여러 VU(사용자)가 setup 에서 만든 하나의 공유 문서를 동시에 친다. 각 edit 은
// POST /api/documents/{id}/edit 으로 {op, baseVersion} 을 보낸다. 핵심은 baseVersion 을
// "현재 서버 버전보다 일부러 뒤처지게" 보내 OT rebase 경로를 강제하는 것 (ADR-0002/0003).
// 서버는 stale baseVersion 의 op 를 그 사이 커밋된 op 들에 대해 transform 한 뒤 적용한다.
//
// 측정:
//   - http_req_duration{name:edit} — 편집 1회의 end-to-end latency
//   - edit_rebased — transform 으로 위치가 옮겨진(=동시 편집과 충돌한) op 수
//   - edit_stale_rejected — 서버가 baseVersion 을 거부한 수 (409/422 등). 0 이 기대값이
//     아니라 분포 관측용 — 정책에 따라 rebase 대신 reject 할 수도 있어 invariant 가 아님.
//   - edit_lost (invariant) — 2xx 인데 transformedOp 가 없는 경우. 0 이어야 한다.
//
// thresholds:
//   - http_req_duration{name:edit} p95 < 150ms / p99 < 400ms
//     (in-memory presence + H2 기준. prod=Redis/Postgres 면 절대값이 다르므로
//      --no-thresholds 로 풀거나 환경에 맞게 조정)
//   - http_req_failed rate < 5% (stale 거부 정책일 때 4xx 여유)
//   - edit_lost count == 0

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, OWNER, USERS, userFor, insertText } from '../lib/config.js';
import { authHeader, jsonHeaders } from '../lib/auth.js';

const editLatency = new Trend('edit_latency_ms', true);
const editRebased = new Counter('edit_rebased');
const editStaleRejected = new Counter('edit_stale_rejected');
const editLost = new Counter('edit_lost');

export const options = {
  scenarios: {
    concurrent_edit: {
      executor: 'ramping-vus',
      // 0 → 사용자 수 만큼 ramp 해 한 문서에 대한 동시 편집 압력을 높인다.
      startVUs: 1,
      stages: [
        { duration: '10s', target: USERS.length },
        { duration: '60s', target: USERS.length },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    'http_req_duration{name:edit}': ['p(95)<150', 'p(99)<400'],
    edit_latency_ms: ['p(95)<150'],
    edit_lost: ['count==0'],
  },
};

// setup: owner 가 문서 하나를 만들고 나머지 사용자에게 EDITOR 로 공유.
// 반환한 documentId 를 모든 VU 가 공유해 동시 편집 contention 을 만든다.
export function setup() {
  const createRes = http.post(
    `${BASE_URL}/api/documents`,
    JSON.stringify({
      title: 'k6 concurrent-edit target',
      content: 'collab-docs concurrent edit load target. Operational transform keeps concurrent edits convergent.',
    }),
    { headers: jsonHeaders(OWNER) },
  );
  check(createRes, { 'setup: doc created (200)': (r) => r.status === 200 });
  const docId = createRes.json('id');
  if (!docId) {
    throw new Error(`setup 실패: 문서 생성 응답에 id 없음 (status ${createRes.status})`);
  }

  // owner 외 사용자에게 EDITOR 공유 — 편집 권한이 있어야 contention 이 성립.
  for (const u of USERS.slice(1)) {
    http.put(
      `${BASE_URL}/api/documents/${docId}/share`,
      JSON.stringify({ targetUserId: u, role: 'EDITOR' }),
      { headers: jsonHeaders(OWNER) },
    );
  }
  return { docId };
}

export default function (data) {
  const docId = data.docId;
  const user = userFor(__VU);

  // 현재 서버 버전 조회 — GET 으로 version 을 읽는다.
  const getRes = http.get(`${BASE_URL}/api/documents/${docId}`, {
    headers: authHeader(user),
    tags: { name: 'get-doc' },
  });
  const currentVersion = getRes.json('version');

  // baseVersion 을 현재보다 1~2 뒤처지게 보내 rebase 경로를 강제한다.
  // (음수 방지로 0 하한). 동시에 여러 VU 가 stale 을 보내면 서버가 그 사이 커밋된 op 에
  // 대해 transform 하므로 edit_rebased 가 쌓인다.
  const lag = currentVersion >= 2 ? 2 : 0;
  const baseVersion = Math.max(0, (currentVersion || 0) - lag);

  // insert 위치를 0 으로 고정하면 모든 동시 insert 가 같은 지점을 노려 transform 경계
  // (ADR-0003: 동시 insert tie-break) 를 가장 자주 친다.
  const body = JSON.stringify({
    op: { type: 'insert', position: 0, text: insertText(user) },
    baseVersion,
  });

  const res = http.post(`${BASE_URL}/api/documents/${docId}/edit`, body, {
    headers: jsonHeaders(user),
    tags: { name: 'edit' },
  });
  editLatency.add(res.timings.duration);

  if (res.status === 200) {
    const transformed = res.json('transformedOp');
    if (!transformed) {
      editLost.add(1); // 2xx 인데 transformedOp 가 없으면 invariant 위반.
    } else {
      // position 이 우리가 보낸 0 과 다르면 서버가 동시 편집에 대해 rebase 한 것.
      const pos = res.json('transformedOp.position');
      if (typeof pos === 'number' && pos !== 0) editRebased.add(1);
    }
  } else if (res.status === 409 || res.status === 422 || res.status === 400) {
    // stale baseVersion 을 rebase 대신 거부하는 정책일 때. invariant 가 아니라 분포 관측.
    editStaleRejected.add(1);
  }

  check(res, {
    'edit 2xx or stale-reject': (r) =>
      r.status === 200 || r.status === 409 || r.status === 422 || r.status === 400,
  });

  sleep(0.1);
}
