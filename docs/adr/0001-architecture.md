# ADR-0001: 전체 아키텍처와 모듈 경계

- 상태: Accepted
- 날짜: 2026-06-23
- 맥락: 여러 사용자가 동시에 같은 문서를 편집하고, 문서를 검색하고, 문서 내용에 대해 AI 질의를 하는 학습/포트폴리오용 백엔드를 만든다. 핵심 난이도는 (1) 동시 편집 충돌 해결, (2) 실시간 fan-out, (3) 문서 검색, (4) 문서 기반 RAG AI 다.

## 결정

### 헥사고날 멀티모듈 (의존성은 안쪽=도메인 방향)
- **collab-domain** — 순수 Kotlin. Spring/JPA 의존성 0. 핵심 aggregate(Document, DocumentVersion, Folder, ShareAcl, Comment)와 **OT(Operational Transform) 엔진**(TextOperation, transform, compose, apply)이 여기 산다. 동시 편집 정합성의 핵심 로직이므로 프레임워크와 분리해 단위 테스트로 고정한다.
- **collab-application** — use case(@Service) + inbound/outbound port 인터페이스. 트랜잭션 경계, 권한 검사, OT 적용 오케스트레이션, RAG 파이프라인 조립.
- **collab-adapter-in** — REST controller + **WebSocket/SSE** 핸들러(실시간 편집 op 브로드캐스트 + presence). Spring Security(JWT resource server).
- **collab-adapter-out** — 영속/검색/presence/AI/blob 구현체. JPA(Postgres/H2), 문서 검색(OpenSearch + in-memory fallback), presence(Redis pub/sub + in-memory fallback), AI(LLM/임베딩 port — offline 결정론 모드 + Spring AI 실연동), blob store(S3/DynamoDB 추상화 + in-memory fallback).
- **collab-bootstrap** — Spring Boot main + profile 조립.
- **e2e-tests** — Testcontainers 통합 시나리오.
- **realtime-gateway** (별도 Node.js/TypeScript) — JWT 인증 + WebSocket 멀티플렉싱 edge 게이트웨이. 클라이언트 WS 연결을 받아 백엔드로 op를 중계하고 브로드캐스트를 fan-out 한다. **권위(authoritative) OT 적용은 Kotlin 백엔드가 담당**하고 게이트웨이는 전송/인증/팬아웃 edge 역할만 한다(폴리글랏 + 연결 스케일아웃 학습 목적).

### 동시 편집 모델: 서버 권위 OT (plain text)
- 클라이언트는 `(op, baseVersion)`을 보낸다. 서버는 baseVersion 이후 커밋된 동시 op들에 대해 **transform** 후 적용하고, 새 version과 변환된 op를 모든 협업자에게 브로드캐스트한다.
- 범위: **plain text insert/delete** OT를 정확히 구현하고 동시성 시나리오를 단위 테스트로 증명한다. rich-text/CRDT는 범위 밖(ADR-0002에서 OT vs CRDT 비교, 향후 과제로 명시).

### Zero-infra 부팅 (default profile)
- 다른 포트폴리오 레포와 동일하게, **프로필 없이 `./gradlew :collab-bootstrap:bootRun`** 하면 외부 인프라 0으로 뜬다: H2 in-memory + in-memory 검색 + in-memory presence + **offline 결정론 AI 모드**(추출 요약/키워드 검색 기반 답변, API 키 불필요). `prod` 프로필에서 Postgres/OpenSearch/Redis/실 LLM 활성화.
- AI offline 모드는 "데모용 결정론 응답"임을 응답과 문서에 정직하게 표기한다(과장 금지).

### 기술 스택
- Kotlin 2.0 / JDK 21 / Spring Boot 3.5.15 / Spring for WebSocket / Spring Security(JWT)
- PostgreSQL + Flyway / H2(dev) / Redis(Lettuce) / OpenSearch(dev=in-memory)
- Spring AI(또는 LLM HTTP port) — offline stub + 실연동 선택형
- Node.js 20 / TypeScript / ws — realtime-gateway
- Gradle 멀티모듈 / Docker(멀티스테이지 non-root) / Helm / GitHub Actions(CI + Trivy + OpenAPI drift)

## 결과
프레임워크와 분리된 OT 엔진을 단위 테스트로 고정하고, 검색/AI/presence/blob를 port 뒤에 두어 dev는 무의존, prod는 실 인프라로 같은 코드가 동작한다. 폴리글랏(Java/Kotlin + Node.js) edge-core 분리로 연결 스케일아웃을 학습한다.

## 용어 풀이 (쉽게)

- **헥사고날 아키텍처(Hexagonal, port/adapter)** — 핵심 로직을 가운데 두고 DB·검색·웹은 콘센트(port)와 플러그(adapter)로만 연결해, 바깥을 바꿔도 핵심 코드는 안 건드리는 구조.
- **OT(Operational Transformation, 운영 변환)** — 여러 명이 같은 문서를 동시에 고칠 때 편집 위치를 서로 보정해 충돌 없이 합쳐, 누가 먼저 들어오든 모두 같은 결과로 수렴시키는 것.
- **권위(authoritative) OT** — 편집 충돌을 푸는 '심판'을 중앙 서버 한 곳에만 두고 모든 편집을 서버가 한 줄로 줄 세우는 것. 심판이 하나라 규칙이 단순하고 결과가 항상 같다.
- **aggregate(애그리거트)** — 함께 묶여 한 덩어리로 다뤄지는 도메인 객체 묶음(주문과 주문항목처럼). 여기선 Document·Folder 등 문서의 핵심 단위.
- **fan-out(팬아웃)** — 한 사람의 편집·커서 움직임을 같은 문서를 보는 모든 접속자에게 한꺼번에 쫙 뿌리는 것. 선풍기 날개처럼 하나가 여러 갈래로.
- **RAG(검색증강생성)** — AI가 막연히 지어내지 않게, 먼저 문서에서 관련 대목을 찾아와(retrieve) 그걸 근거로 답을 만드는 방식. 오픈북 시험처럼 책을 펴놓고 답한다.
- **폴리글랏(polyglot)** — 한 시스템에 언어를 섞어 쓰는 것. 여기선 정합성은 Kotlin, 연결 처리는 Node.js로 역할에 맞는 언어를 골라 쓴다.

## 참고

- [Alistair Cockburn — Hexagonal Architecture (Ports and Adapters)](https://alistair.cockburn.us/hexagonal-architecture/)
- [Spring Modulith Reference](https://docs.spring.io/spring-modulith/reference/) — 모듈 경계 런타임 검사(향후 검토)
- 모듈 경계 규칙의 상세는 ADR-0006 참조
