# OWASP API Security Top 10 (2023) — collab-docs 매핑

본 문서는 OWASP API Security Top 10 (2023) 의 각 항목이 collab-docs 의 어디에 해당하고, 현재
어떻게 다루는지를 짧게 정리한다. 협업 편집 도메인의 특성상 **객체 단위 권한(API1)** 과 **함수 단위
권한(API5)** 이 가장 큰 표면이라(문서 ACL — OWNER / EDITOR / VIEWER) 그쪽에 방어가 들어가 있다.

정직 표기: **기본 프로필의 인증은 dev 전용 permissive** 다. `Authorization: Bearer <name>` 의
평문 값을 그대로 userId 로 받는다(서명 검증 없음, ADR-0004). 아래 "상태" 열의 *dev-only* 는 그
한계를 가리킨다. 운영에서는 `prod` 프로필의 JWT resource server 가 인증을, 그 뒤 도메인 ACL 이
인가를 책임진다.

## 요약

| ID    | 항목                                  | 표면 | 상태 (collab-docs) |
|-------|---------------------------------------|------|--------------------|
| API1  | Broken Object Level Authorization     | 큼   | **집중 방어** — 문서 ACL 로 모든 접근 게이트 |
| API2  | Broken Authentication                 | 중간 | prod=JWT 적용 / dev=permissive (정직 표기) |
| API3  | Broken Object Property Authorization  | 중간 | 응답 DTO 화이트리스트 |
| API4  | Unrestricted Resource Consumption     | 중간 | 일부 (입력 검증) / rate-limit 은 향후 |
| API5  | Broken Function Level Authorization   | 큼   | **적용** — 공유/권한 변경은 OWNER 만 |
| API6  | Unrestricted Access to Sensitive Flow | 작음 | 가정 (게이트웨이) |
| API7  | Server Side Request Forgery           | 작음 | 미해당 (외부 HTTP 호출 경로 없음) |
| API8  | Security Misconfiguration             | 중간 | 일부 (actuator / 에러 sanitize) |
| API9  | Improper Inventory Management         | 작음 | 적용 (단일 버전 + Backstage) |
| API10 | Unsafe Consumption of APIs            | 작음 | 가정 (외부 LLM port) |

---

## API1 — Broken Object Level Authorization (BOLA)

협업 편집의 핵심 표면. 문서는 본질적으로 다중 사용자 공유 객체다.

- 모든 문서 접근은 `DocumentAuthorizer` 가 문서 ACL(`ShareAcl`)을 로드해 도메인 정책(`Role`)으로
  판정한다 — 읽기(VIEWER 이상) / 편집(EDITOR 이상) / 공유(OWNER) 별로 요구 role 이 다르다.
- `ApplyEditService.apply` 는 편집 전 edit 권한을, 검색(`/api/search`)은 접근 가능한 문서로만
  결과를 좁힌다. 권한 위반은 도메인 `AccessDeniedException` → `GlobalExceptionHandler` 가 **403**.
- 사용자 식별은 path / body 의 ownerId 를 신뢰하지 않고, 인증 주체(`CurrentUser.id()`)에서 받는다.

**커버**: 객체 단위 권한이 application + domain 계층에서 강제됨. 단 인증 주체의 신뢰성은 프로필에
의존(API2 참조).

## API2 — Broken Authentication

- **prod** — `ProdSecurityConfig` 가 OAuth2 resource server(JWT)로 `anyRequest authenticated`.
  검증은 `spring.security.oauth2.resourceserver.jwt.issuer-uri` 로 게이트(IdP = auth-service).
- **dev (기본 프로필)** — `DevSecurityConfig` 가 `permitAll` + `DemoBearerAuthFilter`. Bearer 평문을
  userId 로 받는다(서명 검증 없음). **dev-only**, 로컬 데모 한정 — ADR-0004 와 SECURITY.md 에 명시.

**미해결 — dev 범위**: 기본 프로필은 서명 검증을 하지 않는다. 운영 노출 시 `prod` 프로필 필수.

## API3 — Broken Object Property Authorization

- 응답은 도메인 객체를 그대로 직렬화하지 않고 `dto/Dtos.kt` 의 response DTO(화이트리스트)로 매핑한다 —
  내부 메타가 응답 schema 로 자동으로 새지 않는 구조.
- `GlobalExceptionHandler` 는 5xx 에 내부 메시지/stacktrace 를 담지 않고 `ProblemDetail` 로만 응답.

**커버**: response DTO 가 read model 화이트리스트로 작용.

## API4 — Unrestricted Resource Consumption

- 입력 검증(Bean Validation): `@NotBlank`(title / question / comment body / targetUserId),
  `@Min(0)`(baseVersion), `@Min(1)`(RAG topK). 컨트롤러는 `@Valid` 로 강제하고, 위반은 **400**.
- OT 편집은 `baseVersion in 0..document.version` 범위를 `require` 로 검증 — 범위 밖이면 **409**.

**부분 미흡 — 향후**:
- 요청 rate-limit / 동시 편집 op throughput 제한은 **서비스 코드에 없다**. 운영에서는 API Gateway /
  WAF 단의 rate limit 을 전제로 하며, 자체 도입 시 별도 ADR. 현재는 *향후 개선 사항*.
- 검색 `limit` / RAG `topK` 상한은 명시 cap 이 느슨하다 — 운영 전 상한 강화 필요.

## API5 — Broken Function Level Authorization

- 공유 / 권한 변경(`ShareDocumentService.share` · `revoke`)은 요청자가 **OWNER 일 때만** 허용.
  위반 시 도메인 `AccessDeniedException` → **403**. 함수 단위 권한이 use-case 안에서 강제됨.

**커버**: 권한 변경 함수는 OWNER 게이트.

## API6 — Unrestricted Access to Sensitive Business Flows

- 문서 enumeration 은 ACL 로 막힌다(접근 권한 없는 문서는 조회/검색 결과에서 제외). 대량 자동화
  scraping 방지(anti-bot / rate limit)는 게이트웨이 / WAF 책임으로 가정.

## API7 — Server Side Request Forgery

- 서비스 코드에 사용자 입력으로 destination 이 결정되는 outbound HTTP 호출 경로가 없다. 외부 LLM
  연동은 `prod` 프로필의 AI 어댑터(고정 endpoint 설정값)로 국한되며, 사용자가 URL 을 좌우하지 않는다.

**커버**: SSRF 표면 미해당.

## API8 — Security Misconfiguration

- Actuator — `health` / `info` 등 최소만 노출, prod 보안 설정에서 `/actuator/health/**` · `/info` ·
  `/v3/api-docs/**` · `/swagger-ui/**` 만 `permitAll`, 나머지는 `authenticated`.
- 에러 — `GlobalExceptionHandler` 가 내부 메시지/stacktrace 노출 안 함(`ProblemDetail`).

**부분 미흡 — dev 범위**: zero-infra H2 콘솔 / in-memory 백엔드는 로컬 한정. HTTP security header
(HSTS / CSP) 미설정 — 게이트웨이 가정.

## API9 — Improper Inventory Management

- 모든 endpoint 가 `/api/**` 단일 표면. `catalog-info.yaml`(Backstage)가 service inventory 진실원본,
  `docs/openapi/collab-docs.yaml`(CI drift gate)가 REST 표면을 controller 와 동기 유지.

**커버**: 단일 버전 + Backstage catalog + drift-gated OpenAPI.

## API10 — Unsafe Consumption of APIs

- 외부 데이터 자동 import 경로 없음. 외부 LLM 은 port 뒤(`prod` 어댑터)에서만 호출되고, 기본은
  offline 결정론 AI — 외부 응답을 신뢰해 행위를 분기하지 않는다.

---

## 향후 개선 사항 (정직)

- 요청 / 편집 op rate-limit 을 서비스 코드 자체에 도입(현재는 게이트웨이 가정).
- 검색 `limit` / RAG `topK` / 코멘트 길이 등 입력 상한 강화 + cap 명시.
- WebSocket 핸드셰이크의 dev fallback(`?userId=`)을 prod 에서 비활성 보장하는 가드 추가.
