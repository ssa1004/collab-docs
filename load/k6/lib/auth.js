// 인증 헤더 헬퍼 — collab-docs dev 프로필 전용.
//
// dev 프로필(DevSecurityConfig)은 IdP 없이 동작한다: Authorization: Bearer <userId> 의
// 평문을 그대로 userId 로 받아 인증에 채운다 (서명 검증 X). 따라서 토큰이 곧 userId 다.
//   - `Authorization: Bearer alice` → userId=alice
//   - 토큰이 없으면 고정 demo 사용자(demo-user)
//
// prod 프로필(ProdSecurityConfig)은 resource server JWT 검증을 켜므로, 그 환경에서
// 부하를 줄 때는 K6_TOKEN 으로 외부 발급 RS256 토큰을 주입해야 한다. 본 시나리오들은
// 동시 편집 / 검색 / RAG 의 server-side 비용 측정이 목적이라 dev 프로필을 기준으로 둔다.

const ENV_TOKEN = __ENV.K6_TOKEN || '';

/**
 * 주어진 userId 로 Authorization 헤더를 만든다.
 *
 * K6_TOKEN 이 주어지면 (prod 게이트 환경) 그 토큰을 우선 사용 — 이 경우 userId 는
 * 토큰의 sub claim 이 결정하므로 인자는 무시된다.
 */
export function authHeader(userId) {
  const token = ENV_TOKEN || userId;
  return { Authorization: `Bearer ${token}` };
}

/**
 * JSON 본문 + 인증 헤더를 한 번에.
 */
export function jsonHeaders(userId) {
  return Object.assign({ 'Content-Type': 'application/json' }, authHeader(userId));
}
