# Contributing

본 저장소의 개발 흐름과 commit 규칙을 정리한 문서입니다.

## 브랜치 전략

GitHub Flow 를 따릅니다. `main` 은 항상 배포 가능한 상태로 유지되며, 모든 작업은 feature
브랜치에서 진행됩니다.

```
main (protected)
  ├── feature/ot-edge-rule-property-test    ← 기능 브랜치
  ├── fix/presence-fanout-double-emit
  └── docs/adr-rag-pipeline
```

흐름은 `git checkout -b feature/<짧은-설명>` → 작업 → PR → 코드 리뷰 + CI 통과 → Squash and
merge 입니다. 머지 후 feature 브랜치는 즉시 삭제합니다.

## Commit 메시지

Conventional Commits 형식을 따릅니다.

```
<type>(<scope>): <짧은 설명, 50자 이내>

<상세 설명, 한 줄에 72자 이내>
- 무엇이 / 왜 변경되었는지
- 영향받는 모듈
```

사용하는 type: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`.
scope 에는 모듈명 (`domain`, `application`, `adapter-in`, `adapter-out`, `bootstrap`) 이
들어갑니다.

OT(Operational Transform) 정합성, 실시간 fan-out / presence, 문서 검색, 문서 기반 RAG AI 가
도메인의 핵심이므로 관련 commit 이 자주 발생합니다.

### 예시

```
feat(domain): delete 범위 내부 insert 의 inclusion-transform 규칙

- transform(ins, del) 에서 q < p < q+n 일 때 insert 위치를 q 로 clamp
- 양측 transform 이 동일 규칙을 적용해 TP1 수렴 보장 (ADR-0003)
- DocumentVersion 의 편집 로그에는 transform 된 위치로 저장
```

```
fix(adapter-in): 같은 op 가 두 세션에 두 번 fan-out 되던 회귀

ApplyEditService 가 커밋 직후 presence.publishEdit 를 호출하는데, WS 핸들러가
브로드캐스트한 op 를 자기 세션에도 echo 하면서 중복되던 문제. 송신 세션을
구독 listener 에서 제외(skip-self)하도록 수정합니다.
```

## Commit 단위

한 commit 은 한 가지 논리적 변경을 담는 것을 원칙으로 합니다. 새 기능 + 리팩터링 + 버그
수정이 한 commit 에 같이 포함되어 있다면 거의 항상 분리 가능합니다. WIP commit 은 PR 머지
전에 squash 합니다.

## 테스트

PR 전 `./gradlew test` 통과가 필수입니다. 빠른 단위 테스트만 별도로 실행하려면 다음 명령을
사용합니다.

- 도메인 (OT transform / 수렴 / ACL / 버전): `./gradlew :collab-domain:test`
- 유스케이스 (동시 편집 rebase / 권한 / RAG): `./gradlew :collab-application:test`
- adapter-in (REST + WebSocket 핸들러): `./gradlew :collab-adapter-in:test`
- adapter-out (영속 / 검색 / presence / AI 어댑터): `./gradlew :collab-adapter-out:test`
- e2e (전체 부팅 + REST + WS 시나리오): `./gradlew :e2e-tests:test`

OT 수렴 / 경계 규칙은 property 기반 테스트로 고정되어 있어 가장 자주 돌게 됩니다. 동시성
정합성을 건드리는 변경은 반드시 `:collab-domain:test` 를 먼저 통과시키세요.

## 코드 스타일

- Kotlin: 공식 코딩 컨벤션 (IntelliJ default) — 들여쓰기 4칸은 `.editorconfig` 로 강제
- 주석 / 문서는 자연스러운 한국어 (영어 직역체 지양)
- 패키지는 헥사고날 — `domain` / `application/{port,service}` / `adapter/{in,out}` /
  `bootstrap` 경계를 넘지 않습니다 (ADR-0006). 의존성은 항상 안쪽(도메인)을 향합니다.
- 전문 용어 옆에 짧은 한국어 풀이를 함께 적습니다 (예: OT — Operational Transformation,
  동시 편집 충돌 해결).
