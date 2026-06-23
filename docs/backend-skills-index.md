# 백엔드 스킬 인덱스 — 이 레포에서 무엇을 배우나

> 이 레포가 시연하는 협업 편집 / 문서 AI 백엔드 패턴을 **"무엇 → 이 레포 어디서 → 왜(ADR) → 더 깊은 이론(dev-lab)"** 으로 잇는 학습용 인덱스.
> "이 패턴 공부하려면 어디부터 보나"의 진입점. 설명을 다시 쓰지 않고 코드·결정·이론으로 연결만 한다.

## 동시 편집 정합성 (OT)

| 패턴/주제 | 코드 위치 | ADR(왜) | 이론(dev-lab) |
|-----------|-----------|---------|---------------|
| **OT transform / compose / apply** | `collab-domain` `TextOperation` + `transform()` (insert/delete inclusion-transform) | [ADR-0002](adr/0002-ot-vs-crdt.md) | `dev-lab/concurrency` (OT vs CRDT, TP1 수렴) |
| **OT 경계 규칙 (delete 범위 내부 insert)** | `collab-domain` `transform(ins, del)` — `q < p < q+n` 시 위치 clamp, property test 로 고정 | [ADR-0003](adr/0003-ot-edge-rule.md) | `dev-lab/concurrency` (transform 대칭성 / 수렴 증명) |
| **server-authoritative 동시성 (rebase)** | `collab-application` `ApplyEditService.apply` — baseVersion 초과 커밋 op 들에 순서대로 transform, 커밋측 우선(결정론) | [ADR-0002](adr/0002-ot-vs-crdt.md) | `dev-lab/concurrency` (낙관적 동시성 / 권위 서버 정렬) |

## 헥사고날 아키텍처 (ports & adapters)

| 패턴/주제 | 코드 위치 | ADR(왜) | 이론(dev-lab) |
|-----------|-----------|---------|---------------|
| **헥사고날 5모듈 + 도메인 의존성 방향** | `collab-domain` / `-application` / `-adapter-in` / `-adapter-out` / `-bootstrap` | [ADR-0001](adr/0001-architecture.md), [ADR-0006](adr/0006-hexagonal-module-boundaries.md) | `dev-lab/system-design` (헥사고날 / 포트&어댑터 / 모듈 경계) |
| **port 뒤 인프라 교체 (검색/AI/presence/blob)** | `collab-application` out port + `collab-adapter-out` (in-memory ↔ OpenSearch / Redis / 실 LLM) | [ADR-0006](adr/0006-hexagonal-module-boundaries.md) | `dev-lab/system-design` (의존성 역전 / adapter swap) |

## 문서 AI (RAG)

| 패턴/주제 | 코드 위치 | ADR(왜) | 이론(dev-lab) |
|-----------|-----------|---------|---------------|
| **RAG 문서 AI (ask + 추출 요약)** | `collab-application` RAG 파이프라인(chunk → 검색 → 답변 조립) + `AiController` `/ask` · `/summarize` | [ADR-0004](adr/0004-zero-infra-and-offline-ai.md) | `dev-lab/llm` (RAG 파이프라인 / 임베딩 / 청크 검색) |
| **offline 결정론 AI vs 실 LLM (port)** | `collab-adapter-out` AI 어댑터 — offline 결정론 모드 / Spring AI 실연동 | [ADR-0004](adr/0004-zero-infra-and-offline-ai.md) | `dev-lab/llm` (LLM port 추상화 / 결정론 테스트) |

## 실시간 fan-out + presence

| 패턴/주제 | 코드 위치 | ADR(왜) | 이론(dev-lab) |
|-----------|-----------|---------|---------------|
| **presence fan-out (out port)** | `collab-application` `PresencePort` — `publishEdit` / `publishPresence` / `subscribe`, 커밋 직후 호출 | [ADR-0005](adr/0005-presence-and-fanout.md) | `dev-lab/realtime` (pub/sub fan-out / presence) |
| **WebSocket edge + in-memory ↔ Redis pub/sub** | `collab-adapter-in` `CollabWebSocketHandler` + `collab-adapter-out` presence 어댑터(memory 기본 / Redis prod) | [ADR-0005](adr/0005-presence-and-fanout.md) | `dev-lab/realtime` (WebSocket 스케일아웃 / Redis pub/sub) |

## Zero-infra / offline 부팅

| 패턴/주제 | 코드 위치 | ADR(왜) | 이론(dev-lab) |
|-----------|-----------|---------|---------------|
| **zero-infra 기본 부팅 (H2 + in-memory + offline AI)** | `collab-bootstrap` 기본 프로필 — Docker / 키 없이 전체 기능 동작 | [ADR-0004](adr/0004-zero-infra-and-offline-ai.md) | `dev-lab/system-design` (프로필 분리 / 로컬-우선 부팅) |
| **dev=무의존 / prod=실인프라 같은 코드** | `collab-bootstrap` `prod` 프로필 — 같은 use-case 가 Postgres / OpenSearch / Redis / 실 LLM 로 | [ADR-0006](adr/0006-hexagonal-module-boundaries.md) | `dev-lab/system-design` (12-factor 백킹 서비스) |

## 학습 순서 제안 (이 레포 기준)

1. **[README](../README.md) 상단 + OT 동시성 스토리** → 동시 편집이 왜 어려운지 전체 그림
2. **[ADR-0001](adr/0001-architecture.md)** (아키텍처 / 모듈 경계) → 코드 읽기 전 지도
3. **OT 표** (transform / 경계 규칙 / rebase) → `collab-domain` 코드 + ADR-0002/0003 + `dev-lab/concurrency`
4. **헥사고날 표** → ADR-0006 + `dev-lab/system-design` (port 교체로 dev=무의존 / prod=실인프라)
5. **RAG / presence 표** → ADR-0004/0005 + `dev-lab/llm`·`dev-lab/realtime`
6. **zero-infra 표** → ADR-0004 (정직 표기 — offline AI 가 진짜 LLM 인 척하지 않음)

> 짝 학습 레포: [dev-lab](https://github.com/ssa1004/dev-lab) (이론) ↔ 이 레포 (구현). 이론에서 "왜"를, 여기서 "실제로 어떻게"를 본다. 특히 `dev-lab/concurrency` 의 OT / TP1 수렴을 먼저 보면 이 레포의 `transform()` 경계 규칙이 빠르게 잡힌다.
