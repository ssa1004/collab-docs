# ADR-0005: Presence 와 실시간 Fan-out — in-memory 기본 / Redis prod / WS edge 게이트웨이

- 상태: Accepted
- 맥락: 한 문서를 여러 명이 동시에 편집할 때 (1) 서버가 OT 로 변환·커밋한 op 를 같은 문서를 보고 있는 **모든 협업자에게 즉시 fan-out** 하고, (2) 커서/선택/타이핑 같은 **presence** 를 퍼뜨려야 한다. fan-out 메커니즘과 연결(WebSocket) 스케일아웃을 어떻게 둘지 결정한다.

## 결정

### 1. fan-out 을 out port 뒤에 둔다 (`PresencePort`)

application 모듈이 `PresencePort` 를 정의한다 — `publishEdit`, `publishPresence`, `subscribe(documentId, listener) -> Subscription`. use-case 는 **"무엇을 퍼뜨리는가"** 만 알고 **"어떻게"** 는 모른다. 핵심: 권위 OT 를 적용하는 `ApplyEditService.apply` 가 커밋 직후 `presence.publishEdit(...)` 를 호출한다. 그래서 편집이 **REST 로 들어오든 WS 로 들어오든** 같은 경로로 모든 구독 세션에 동일하게 변환된 op 가 나간다.

### 2. 기본 어댑터 = in-memory pub/sub (`collab.presence=memory`)

`InMemoryPresenceAdapter` 가 문서별 listener 집합을 들고 `publish*` 시 동기 fan-out 한다. WS 핸들러는 접속 시 `subscribe(documentId) { event -> session.send(event) }` 로 자기 세션을 그 문서 room 에 등록하고, 종료 시 `Subscription.close()` 로 해지한다.

- 장점: 외부 인프라 0, 단일 인스턴스에서 완벽 동작 → zero-infra 부팅(ADR-0004)을 만족.
- 한계(정직): **단일 JVM 한정.** 인스턴스를 수평 확장하면 인스턴스 A 의 편집이 인스턴스 B 에 붙은 세션에 닿지 않는다. 데모/단일 노드에서만 유효.

### 3. prod 어댑터 = Redis pub/sub (`collab.presence=redis`)

`RedisPresenceAdapter` 가 같은 `PresencePort` 를 Redis pub/sub 으로 구현한다. 문서 채널을 토픽으로 매핑해 `publishEdit`/`publishPresence` 를 발행하고, 각 인스턴스가 구독해 자기에게 붙은 WS 세션으로 흘려보낸다. 이렇게 **여러 백엔드 인스턴스에 흩어진 세션**이 한 문서를 공유할 수 있다. use-case 코드는 그대로 — 어댑터만 교체.

### 4. WebSocket edge 게이트웨이 (Node.js) — 전송/인증/팬아웃만, OT 는 아님

`realtime-gateway`(별도 Node.js/TypeScript, `ws`)는 클라이언트 WS 연결을 받는 **edge** 다. 역할은 JWT 인증 + 연결 멀티플렉싱 + op 중계 + 브로드캐스트 fan-out. **권위 OT 적용은 항상 Kotlin 백엔드**가 한다(ADR-0001/0003). 게이트웨이는 op 를 변환하지 않는다 — 받아서 백엔드로 넘기고, 백엔드가 변환·커밋한 결과를 받아 클라이언트에 뿌릴 뿐이다.

왜 edge 와 core 를 쪼개나:
- **연결 스케일아웃 학습.** WebSocket fan-out/수만 연결은 Node 의 이벤트 루프가 잘 맞는 영역이고, JVM 백엔드는 정합성(OT/트랜잭션/검색)에 집중하게 한다.
- **폴리글랏 경계 학습.** "권위는 한 곳(Kotlin), edge 는 얇게(Node)" 라는 규칙을 명시적 ADR(0003)로 고정한다.
- 게이트웨이 없이도 동작한다 — 클라이언트가 백엔드의 `/ws/documents/{id}` 에 직접 붙어도 같은 프로토콜이다. 게이트웨이는 선택적 edge 계층.

## WS 와이어 프로토콜 (요약, `CollabWebSocketHandler`)

```
client → server:
  {"type":"edit","op":{<op>},"baseVersion":<int>}
  {"type":"presence","cursor":<int?>,"selectionStart":<int?>,"selectionEnd":<int?>,"typing":<bool>}
server → client:
  {"type":"welcome","documentId":..,"userId":..}
  {"type":"ack","op":{<transformed op>},"version":<int>}   # 보낸 사람에게 rebase 결과
  {"type":"edit","documentId":..,"op":{<op>},"version":<int>}   # room 전체 fan-out
  {"type":"presence","documentId":..,"userId":..,"cursor":..,..}
  {"type":"error","message":..}
```

`op` 는 REST 와 동일한 `OperationDto` 형식(`{"type":"insert|delete|composite",...}`)을 쓴다.

## 트레이드오프 / 정직성

- in-memory fan-out 은 **재시작 비영속·단일 노드 한정**. 영속·다중 노드는 Redis 어댑터의 몫.
- Redis pub/sub 은 **at-most-once**(구독 끊긴 동안 발행분 유실 가능). 권위 상태(문서/버전/EditLog)는 DB 에 있으므로, 재접속 클라이언트는 `GET /versions` 로 따라잡는 게 정합성의 근거다 — pub/sub 은 "빠른 라이브 채널" 일 뿐 진실의 원천이 아니다.
- presence(커서/타이핑)는 의도적으로 **비영속·best-effort**. 유실돼도 다음 업데이트로 자가 치유된다.

## 결과

fan-out 이 port 뒤에 있어 dev(in-memory)와 prod(Redis)가 같은 use-case 로 동작하고, 편집이 REST/WS 어디로 들어와도 동일 경로로 브로드캐스트된다. 권위 OT 는 Kotlin 백엔드 한 곳에 고정하고, Node edge 게이트웨이는 전송/인증/팬아웃만 맡아 연결 스케일아웃을 분리해 학습한다.
