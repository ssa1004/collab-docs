# ADR-0004: Zero-infra 기본 부팅 + Offline 결정론 AI (정직 표기)

- 상태: Accepted
- 맥락: 학습/포트폴리오 백엔드는 "받아서 바로 뜨는" 경험이 중요하다. 리뷰어/채용자/나 자신이 Docker, Postgres, Redis, OpenSearch, LLM API 키를 세팅하지 않고도 `git clone` 후 한 줄로 전체 기능(동시 편집·검색·AI 질의/요약)을 돌려볼 수 있어야 한다. 동시에, 데모용 AI 가 진짜 LLM 인 것처럼 보이게 만드는 것은 **부정직**하다. 이 둘을 어떻게 양립시킬지 결정한다.

## 결정

### 1. 기본 프로필 = zero-infra (외부 인프라 0)

프로필 없이 `./gradlew :collab-bootstrap:bootRun` 하면 외부 의존성 **0** 으로 뜬다:

| 관심사 | 기본(zero-infra) | `prod` 프로필 |
|---|---|---|
| DB | **H2 in-memory** (`MODE=PostgreSQL`, 같은 Flyway `V1__schema.sql`) | PostgreSQL |
| 검색 | **in-memory 역색인** (`collab.search=memory`) | OpenSearch |
| presence/fan-out | **in-memory pub/sub** (`collab.presence=memory`) | Redis pub/sub |
| AI | **offline 결정론** (`collab.ai=offline`) | 실 LLM(`HttpLlmAdapter`) |
| blob | **in-memory** (`collab.blob=memory`) | S3 |
| 인증 | **dev permissive**(Bearer 평문=userId, 데모 사용자 폴백) | JWT resource server |

핵심 장치 두 가지:
- **port 뒤 어댑터 토글.** 각 outbound 관심사는 application 모듈의 port 인터페이스 뒤에 있고, 어댑터는 `@ConditionalOnProperty(collab.*)` 로 선택된다. dev 와 prod 가 **같은 use-case 코드**를 돌린다 — 바뀌는 건 어댑터뿐.
- **Redis 자동설정 비활성화.** `spring-boot-starter-data-redis` 가 클래스패스에 있어도 기본 프로필은 `RedisAutoConfiguration`/`RedisReactiveAutoConfiguration` 을 `spring.autoconfigure.exclude` 로 끈다. 그래야 Redis 가 없어도 헬스체크가 빨갛게 뜨지 않고 "외부 인프라 0" 이 진짜로 보장된다(`prod` 는 exclude 를 비워 다시 켠다).

H2 는 `MODE=PostgreSQL` 로 띄워 **같은 Flyway 스크립트**가 dev/prod 양쪽에서 통하게 한다 — 스키마 드리프트를 막고, Hibernate `ddl-auto=validate` 가 엔티티↔스키마 불일치를 부팅 시점에 잡는다.

### 2. Offline AI = 결정론 추출(extractive) 알고리즘 — LLM 아님, 정직 표기

기본 AI 어댑터(`OfflineAiAdapter`)는 **API 키가 필요 없는 결정론 알고리즘**이다. LLM 호출이 아니다.

- **embed**: bag-of-words 토큰을 고정 64차원에 해시-버킷으로 모은 L2-정규화 벡터(결정론).
- **summarize**: 문장 분리 → 리드 문장 + 키워드 점수 상위 문장 추출(extractive). 생성(generative)이 아니다.
- **ask(RAG)**: 본문을 결정론적으로 청크 분할 → 키워드 검색(미스 시 임베딩 코사인 fallback)으로 retrieve → 상위 청크에서 질문과 가장 관련 높은 **문장을 그대로 추출** + 사용한 청크 ordinal 을 인용으로 반환.

**정직 표기를 코드/응답/문서에 강제한다:**
- 모든 답변 텍스트는 `"[offline] 결정론 추출 모드(LLM 아님). "` 프리픽스로 시작한다.
- `AskResponse.offline` / `SummarizeResponse.offline` 응답 필드가 `true` 다.
- README 의 capability 표가 이 모드를 "데모용 결정론, 생성 아님" 으로 명시한다.

### 3. 실 LLM 은 같은 port 뒤에 pluggable

`collab.ai=llm` + `prod` 프로필에서 `HttpLlmAdapter` 가 활성화되어 같은 `AiAssistPort` 를 실 LLM HTTP 엔드포인트로 구현한다. use-case(`AskDocumentService`/`SummarizeDocumentService`)와 RAG retrieve 파이프라인은 **바뀌지 않는다** — 임베딩/생성만 실모델로 교체된다.

## 트레이드오프 / 정직성

- offline 모드는 **추출 요약·키워드 RAG** 라서 의미 추론·환언·다중 문서 종합은 못 한다. 같은 입력엔 항상 같은 출력(결정론) — 데모/테스트엔 장점, "지능" 으로 과대표현하면 부정직. 그래서 prefix + `offline=true` 로 못박는다.
- in-memory 검색/presence 는 **단일 인스턴스 한정**이고 프로세스 재시작 시 비영속이다(문서/버전 자체는 H2 in-memory 라 역시 재시작 시 사라짐 — 데모 목적). prod 어댑터에서 영속/스케일아웃을 다룬다(ADR-0005).
- H2(`MODE=PostgreSQL`)는 Postgres 와 100% 동일하지 않다(일부 함수/락 의미). Flyway 스크립트를 양쪽에서 공유해 드리프트를 줄이지만, prod 회귀는 Testcontainers(`e2e-tests`)로 따로 검증한다.

## 결과

`git clone` → 한 줄 부팅 → 동시 편집/검색/AI 의 전체 happy path 가 키·Docker 없이 돈다(`scripts/demo.sh` 가 이를 그대로 실행). 동시에 AI 가 LLM 인 척하지 않는다. prod 는 같은 코드에 어댑터만 바꿔 실 인프라로 동작한다.
