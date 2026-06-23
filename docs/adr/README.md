# Architecture Decision Records (ADR)

본 디렉토리는 collab-docs 의 핵심 설계 결정과 그 근거를 담는다. 각 ADR 은 왜 그 결정을 했는지,
검토한 대안은 무엇이고 무엇을 포기했는지(단점 포함)를 짧게 읽고도 파악할 수 있도록 작성한다.
동시 편집 정합성(OT) · 실시간 fan-out · 문서 검색 · 문서 RAG AI 가 이 프로젝트의 난이도 핵심이라,
ADR 도 그 결정들에 집중된다.

| 번호 | 제목 |
|------|------|
| [ADR-0001](0001-architecture.md) | 전체 아키텍처와 모듈 경계 |
| [ADR-0002](0002-ot-vs-crdt.md) | 동시 편집 모델 — OT 를 선택하고 CRDT 는 보류 |
| [ADR-0003](0003-ot-edge-rule.md) | OT 경계 규칙 — "동시 delete 범위 내부로 들어오는 insert" |
| [ADR-0004](0004-zero-infra-and-offline-ai.md) | Zero-infra 기본 부팅 + Offline 결정론 AI (정직 표기) |
| [ADR-0005](0005-presence-and-fanout.md) | Presence 와 실시간 Fan-out — in-memory 기본 / Redis prod / WS edge 게이트웨이 |
| [ADR-0006](0006-hexagonal-module-boundaries.md) | 헥사고날 모듈 경계 (ports & adapters) |
