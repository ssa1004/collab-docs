// 시나리오 공통 설정.
//
// BASE_URL 은 환경변수로 덮어쓸 수 있도록. 기본은 zero-infra 부팅 (bootRun) 의 8080.
// docker-compose.integration.yml 로 띄웠다면 BASE_URL=http://localhost:18088 로 준다.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/**
 * 협업자 userId pool — 동시 편집 contention 을 만들려면 여러 사용자가 같은 문서를
 * 친다. dev 프로필(DevSecurityConfig)은 Authorization: Bearer <userId> 의 평문을
 * 그대로 userId 로 받으므로, 토큰을 이 pool 에서 골라 사용자 N명을 흉내낸다.
 *
 * 첫 사용자(pool[0])는 문서 owner 로 setup 단계에서 문서를 만들고 나머지에게 공유한다.
 */
export const USERS = (__ENV.K6_USERS || 'alice,bob,carol,dave,erin,frank')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

export const OWNER = USERS[0] || 'alice';

/**
 * VU 인덱스 → 사용자 매핑. 같은 VU 는 항상 같은 userId 로 친다 (편집 권한 / presence
 * 일관성). owner 까지 포함한 round-robin.
 */
export function userFor(vuId) {
  if (USERS.length === 0) return 'alice';
  return USERS[vuId % USERS.length];
}

/**
 * RAG ask 시나리오의 질문 pool — 시드 문서 내용에 답이 있는 자연어 질문.
 * offline 결정론 AI (ADR-0004) 가 문서에서 추출해 답하므로, 문서에 등장하는 키워드를
 * 포함한 질문이 citedOrdinals 를 채운다.
 */
export const QUESTIONS = (__ENV.K6_QUESTIONS
  || 'What keeps concurrent edits convergent?'
  + '|How does search work?'
  + '|What is operational transform?'
  + '|How are conflicts resolved?')
  .split('|')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

export function randomQuestion() {
  if (QUESTIONS.length === 0) return 'What does this document describe?';
  return QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)];
}

/**
 * 검색어 pool — 시드 문서 본문에 등장하는 토큰. in-memory / OpenSearch 어느 백엔드든
 * hit 이 나도록 흔한 영문 토큰을 둔다.
 */
export const QUERIES = (__ENV.K6_QUERIES || 'transform,edit,search,document,concurrent,convergent')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

export function randomQuery() {
  if (QUERIES.length === 0) return 'document';
  return QUERIES[Math.floor(Math.random() * QUERIES.length)];
}

/**
 * insert 할 짧은 텍스트 조각 — 편집 op 의 payload. 사용자 라벨을 붙여 어느 VU 가 친
 * insert 인지 수렴 결과에서 눈으로 확인 가능하게.
 */
export function insertText(user) {
  return `[${user}] `;
}
