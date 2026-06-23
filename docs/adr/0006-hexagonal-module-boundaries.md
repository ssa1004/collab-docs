# ADR-0006: 헥사고날 모듈 경계 (ports & adapters)

- 상태: Accepted
- 날짜: 2026-06-23
- 맥락: ADR-0001 이 6개 Gradle 모듈을 선언했다. 이 ADR 은 **모듈 경계가 무엇을 강제하는지**, 의존성 방향, 무엇이 어느 모듈에 살아야 하는지의 규칙을 고정한다. 핵심 목표는 동시성 정합성의 심장(OT)을 프레임워크에서 떼어내 단위 테스트로 묶고, 인프라(검색/AI/presence/blob/DB)를 port 뒤에 두어 dev=무의존 / prod=실인프라가 **같은 코드**로 돌게 하는 것.

## 결정: 의존성은 항상 안쪽(도메인)을 향한다

```
            ┌─────────────────────────────────────────────┐
            │              collab-bootstrap               │  Spring Boot main + profile 조립
            └───────────────┬─────────────────────────────┘
                            │ (런타임에만 어댑터 와이어링)
        ┌───────────────────┼───────────────────┐
        ▼                                       ▼
┌──────────────────┐                  ┌──────────────────────┐
│ collab-adapter-in│                  │  collab-adapter-out  │
│ REST · WS · 보안 │                  │ JPA·검색·presence·AI │
└────────┬─────────┘                  └───────────┬──────────┘
         │ inbound port 호출                       │ outbound port 구현
         └──────────────► collab-application ◄─────┘
                          use-case · port 인터페이스
                                   │
                                   ▼
                           collab-domain
                  순수 Kotlin · OT 엔진 · aggregate (의존성 0)
```

규칙:
1. **`collab-domain`** — 순수 Kotlin. Spring/JPA 의존성 **0**. aggregate(Document, DocumentVersion, ShareAcl, Comment, Folder)와 **OT 엔진**(`TextOperation`, `transform`, `compose`, `apply`, `DocumentChunk`)이 산다. 가장 안쪽 — 아무것도 import 하지 않는다.
2. **`collab-application`** — use-case(`@Service`) + **port 인터페이스**(inbound/outbound). 트랜잭션 경계, 권한 검사(`DocumentAuthorizer`), OT 적용 오케스트레이션, RAG 파이프라인 조립. domain 만 의존. **outbound port 를 여기서 소유**한다(어댑터 모듈이 아니라).
3. **`collab-adapter-in`** — driving 어댑터: REST 컨트롤러 + WebSocket 핸들러 + Spring Security. use-case 를 호출하고, 와이어 DTO ↔ 도메인 변환을 여기서만 한다.
4. **`collab-adapter-out`** — driven 어댑터: outbound port **구현체**. JPA(Postgres/H2), 검색(OpenSearch + in-memory), presence(Redis + in-memory), AI(LLM + offline), blob(S3 + in-memory). `@ConditionalOnProperty(collab.*)` 로 dev/prod 어댑터를 토글.
5. **`collab-bootstrap`** — Spring Boot main + `application.yml` 프로필. **여기서만** 모든 모듈이 만나 컴포넌트 스캔/와이어링된다.
6. **`e2e-tests`** — Testcontainers 로 prod 어댑터(실 Postgres 등)까지 포함한 통합 시나리오.

### port 를 application 이 소유하는 이유 (의존성 역전)

outbound port 인터페이스(`DocumentRepository`, `DocumentSearchPort`, `PresencePort`, `AiAssistPort`, `BlobStorePort`, ...)는 **`collab-application` 에 산다**. 어댑터 모듈이 application 을 의존하고 그 인터페이스를 구현한다 — 화살표가 안쪽을 향한다(DIP). 그래서 검색 엔진을 OpenSearch→다른 것으로 바꿔도 use-case 는 컴파일조차 다시 안 된다. 어댑터만 교체된다.

## 경계가 강제하는 것 (왜 모듈로 쪼개나)

단일 모듈 + 패키지 컨벤션으로도 헥사고날을 "흉내" 낼 수 있지만, **Gradle 모듈 경계는 위반을 컴파일 에러로 만든다**:

- `collab-domain` 의 build.gradle 엔 Spring/JPA 가 **없다** → 누가 도메인에서 `@Entity`/`@Service` 를 import 하려 하면 컴파일이 깨진다. OT 엔진이 프레임워크와 섞이는 걸 물리적으로 막는다.
- `collab-application` 은 어댑터 모듈을 의존하지 **않는다** → use-case 가 JPA 엔티티나 Spring MVC 타입을 만질 수 없다. 도메인 ↔ 영속/와이어 변환은 어댑터에 갇힌다.
- 따라서 OT 정합성 테스트(`OperationalTransformConvergenceTest` 등)는 Spring 컨텍스트 없이 순수 JVM 단위 테스트로 빠르게 돈다.

## 트레이드오프

- 모듈이 6개라 작은 변경에도 여러 build 파일/모듈을 건드릴 수 있다(보일러플레이트). 학습 목적상 경계의 명료함이 이 비용보다 가치 있다고 본다.
- DTO ↔ 도메인 매핑이 손으로 쓰는 코드라 약간 중복된다(adapter-in 의 `Dtos.kt`/`OperationDto.kt`). 매핑 라이브러리 대신 명시적 변환을 택해 경계를 읽기 쉽게 유지한다.
- 작은 도메인이라면 과한 구조다. 의도적으로 "헥사고날을 제대로 연습" 하는 포트폴리오 스코프라 받아들인다.

## 결과

도메인(특히 OT 엔진)이 프레임워크와 물리적으로 분리되어 빠른 단위 테스트로 고정된다. 모든 인프라가 application 이 소유한 port 뒤에 있어, dev(in-memory/offline)와 prod(실 인프라)가 같은 use-case 코드로 동작하고, 어댑터 교체가 안쪽 코드를 건드리지 않는다.

## 참고

- [Alistair Cockburn — Hexagonal Architecture (Ports and Adapters)](https://alistair.cockburn.us/hexagonal-architecture/)
- [Netflix Tech Blog — Ready for changes with Hexagonal Architecture](https://netflixtechblog.com/ready-for-changes-with-hexagonal-architecture-b315ec967749)
- 모듈 선언과 의존성 방향의 전체 그림은 ADR-0001 참조
