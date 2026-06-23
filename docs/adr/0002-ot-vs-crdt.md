# ADR-0002: 동시 편집 모델 — OT 를 선택하고 CRDT 는 보류

- 상태: Accepted
- 맥락: 여러 사용자가 같은 plain-text 문서를 동시에 편집할 때 충돌 없이 **같은 최종 상태로 수렴**시켜야 한다. 사실상의 두 후보는 **OT(Operational Transformation)** 와 **CRDT(Conflict-free Replicated Data Type)** 다. 이 학습/포트폴리오 프로젝트의 목표는 "동시성 정합성을 직접 구현하고 단위 테스트로 증명하기"이므로, 둘 중 무엇을 핵심 도메인 로직으로 둘지 결정한다.

## 두 모델 요약

| | OT (선택) | CRDT (보류) |
|---|---|---|
| 핵심 아이디어 | op `(insert/delete)` 를 동시 op 에 대해 **transform** 해 위치를 rebase | 각 문자에 전역 유일·정렬가능 id 를 부여, **교환법칙**으로 병합 |
| 권위 | **서버 권위**(중앙 서버가 정렬·transform) 가 자연스럽다 | P2P/오프라인-우선까지 자연스럽다(서버 불필요) |
| 메타데이터 | op 만 보관(문서는 plain string) | 삭제 문자도 **tombstone** 으로 남음 → 메타데이터 누적 |
| 정확성 난이도 | **transform 함수의 경계 케이스**가 어렵다(TP1/TP2) | 자료구조 불변식이 핵심, transform 불필요 |
| 디버깅/가시성 | op 로그가 사람이 읽기 쉬움(버전 = 정수) | 내부 id/tombstone 은 직관적이지 않음 |

## 결정: 서버 권위 OT (plain text)

핵심 도메인을 **서버 권위 inclusion-transform OT** 로 구현한다.

- 클라이언트는 `(op, baseVersion)` 을 보낸다. 서버는 `baseVersion` 이후 커밋된 동시 op 들에 대해 들어온 op 를 `transform` 으로 rebase 한 뒤 적용하고, `newVersion` 과 변환된 op 를 협업자에게 fan-out 한다(`ApplyEditService`).
- 문서 상태는 그냥 `String` 이고 버전은 단조 증가하는 `Int` 다. tombstone 도, 문자별 id 도 없다.
- transform 의 어려운 경계(동시 delete 범위 내부로 들어오는 insert)는 별도로 ADR-0003 에 규칙을 고정하고, 속성 테스트로 TP1(두 transform 순서의 최종 문자열 동일성)을 증명한다.

### 왜 OT 인가 (이 프로젝트 기준)

1. **서버 권위 모델과 잘 맞는다.** 이미 REST/JPA 백엔드와 버전 정수가 있는 중앙 서버 구조다. 중앙 서버가 op 순서를 정하므로 OT 가 가장 까다로워지는 부분인 TP2(분산/재정렬 환경에서 transform 의 결합 일관성)를 **회피**할 수 있다. 모든 op 가 서버에서 한 줄로 직렬화되므로 우리는 TP1 만 만족하면 된다.
2. **학습 가치가 직접적이다.** transform/compose/apply 를 직접 짜고 동시성 시나리오를 단위 테스트로 고정하는 것이 이 레포의 목적이다. CRDT 라이브러리(Yjs/Automerge)를 끼우면 "정합성을 직접 구현"하는 학습 목표가 사라진다.
3. **상태가 작고 검사 가능하다.** 문서가 plain string + 정수 버전이라 검색 색인/요약/RAG 같은 다운스트림이 단순하다. CRDT 의 tombstone·내부 구조를 검색/AI 파이프라인에 다시 매핑할 필요가 없다.
4. **가시성.** `GET /api/documents/{id}/versions` 가 op 로그를 사람이 읽을 수 있는 형태로 노출한다. 디버깅과 포트폴리오 설명에 유리하다.

## 트레이드오프 (정직하게)

OT 를 고르면서 받아들인 비용:

- **transform 의 경계 케이스 부담.** 동시 insert/delete 의 위치 관계마다 규칙이 필요하고, 한 쌍이라도 어긋나면 발산한다. 이 비용을 ADR-0003 + 속성 테스트로 갚는다.
- **서버 권위 가정.** 진짜 오프라인-우선/P2P 가 들어오면 OT 의 강점(중앙 직렬화)이 사라지고 TP2 까지 풀어야 한다 — 그때는 OT 가 CRDT 보다 불리하다.
- **plain text 한정.** rich-text(서식/표/임베드)는 attribute 를 함께 transform 해야 해서 규칙 수가 폭증한다. 이 레포는 의도적으로 **plain text insert/delete 만** 다룬다(ADR-0001 범위). rich-text 는 범위 밖이라고 명시한다.

반대로 CRDT 를 **선택하지 않은** 이유의 비용도 인지한다: tombstone 누적으로 인한 메모리/GC(가비지 컬렉션) 설계, 문자별 id 정렬 비용, 검색/AI 로의 재매핑 복잡도. 서버 권위·plain text·중앙 검색이라는 우리 제약에서는 이 비용이 이득보다 크다.

## 언제 다시 볼 것인가 (revisit triggers)

다음 중 하나라도 현실이 되면 CRDT(예: Yjs/Automerge 임베드) 전환을 재검토한다:

1. **오프라인-우선 / P2P** 가 요구된다(클라이언트가 서버 없이 오래 편집 후 병합). → 서버 권위가 깨지므로 OT 의 TP2 부담이 커지고 CRDT 가 유리.
2. **Rich-text** (서식/표/공동 셀 편집)로 범위가 확장된다. → attribute transform 폭증, CRDT(Yjs `Y.Text`/`Y.XmlFragment`)의 성숙한 생태계가 유리.
3. **다중 리전 active-active**(여러 권위 서버 간 병합)로 확장된다. → 중앙 직렬화가 사라져 OT 의 가정이 무너진다.
4. transform 경계 케이스 테스트 매트릭스가 유지보수 불가능할 만큼 커진다.

그 전까지는, 서버 권위 + plain text 라는 명확한 제약 안에서 OT 가 더 단순하고 더 검사 가능하며 학습 목표에 더 잘 맞는다.

## 결과

핵심 동시성 로직(`TextOperation`, `transform`, `compose`, `apply`)이 `collab-domain` 에 프레임워크 의존성 0 으로 산다. 속성 테스트가 수렴(TP1)을 고정한다. CRDT 전환 트리거를 위에 명시해, 범위를 정직하게 한정하면서도 진화 경로를 열어 둔다.
