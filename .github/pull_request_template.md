<!--
PR 제목은 Conventional Commits 규칙을 따릅니다 (예: feat(domain):, fix(adapter-in):, docs:, ci:).
Squash and merge 를 사용하므로 PR 제목이 최종 commit 제목이 됩니다.
CONTRIBUTING.md 의 commit / 브랜치 규칙을 참고하세요.
-->

## 변경 요약

<!-- 무엇을, 왜 바꿨는지 1~3줄. -->

## 변경 유형

- [ ] feat — 기능 추가
- [ ] fix — 버그 수정
- [ ] refactor — 동작 변화 없는 구조 개선
- [ ] perf — 성능 개선
- [ ] test — 테스트 추가/수정
- [ ] docs — 문서
- [ ] chore / build / ci — 빌드·도구·파이프라인

## 영향 범위

<!-- 예: domain / application / adapter-in / adapter-out / bootstrap / ci -->

## 테스트

<!-- 실행한 검증을 구체적으로. 해당되는 항목 체크. -->

- [ ] 로컬에서 `./gradlew test` 통과
- [ ] OT / 동시성 변경 시: `./gradlew :collab-domain:test` (수렴 / 경계 규칙 property test) 통과
- [ ] 워크플로 변경 시: `actionlint` 통과
- [ ] 공개 API 변경 시: `docs/openapi/collab-docs.yaml` 재생성 + drift gate 통과

## 체크리스트

- [ ] PR 제목이 Conventional Commits 형식
- [ ] OT 수렴 / 권한(ACL) 동작에 의도치 않은 변화가 없음 (있다면 본문에 명시)
- [ ] 비밀 / 자격증명을 커밋하지 않음
- [ ] 빌드 산출물(build/, *.jar)을 커밋하지 않음
- [ ] 필요한 문서(README / ADR)를 갱신함

## 관련 이슈

<!-- Closes #123 -->
