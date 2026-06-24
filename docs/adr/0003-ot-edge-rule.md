# ADR-0003: OT 경계 규칙 — "동시 delete 범위 내부로 들어오는 insert"

- 상태: Accepted
- 날짜: 2026-06-23
- 맥락: plain-text inclusion-transform 에서 가장 까다로운 경계는 **한 사용자의 insert 위치가, 동시에 적용된 다른 사용자의 delete 범위 "내부"에 떨어지는 경우**다. 이 케이스의 처리 규칙을 양측 transform 이 동일하게 적용해야 수렴(TP1)이 깨지지 않는다.

## 다루는 케이스

base 문서 `doc` 에서 동시에 만들어진 두 연산:
- `ins = Insert(p, s)`
- `del = Delete(q, n)`  (범위 `[q, q+n)`)

위치 관계가 `q < p < q+n` 일 때(=insert 지점이 delete 범위 *내부*)가 문제다.
경계(`p == q`, `p == q+n`)는 내부가 아니므로 단순 shift 로 처리한다.

## 결정한 규칙

1. **insert 는 살아남는다 (survive).** 사용자가 친 글자를 동시 삭제가 "먹어버리는" 것은
   직관과 어긋나고 데이터 손실이다. 따라서 `transform(ins, del)` 결과 insert 의 위치는
   **delete 시작점 `q` 로 클램프**한다 → `Insert(q, s)`.
   (delete 가 먼저 적용되면 `[q, q+n)` 이 사라지므로, 그 자리에 글자를 끼워 넣는다.)

2. **delete 는 insert 를 삭제하지 않는다 (split).** 대칭으로, `transform(del, ins)` 는
   삭제 범위가 새로 끼어든 글자 `s` 를 건너뛰도록 **두 조각으로 쪼갠다**:
   `[q, p)` 와 `[p + |s|, q + n + |s|)`.
   단일 `Delete` 로는 표현할 수 없으므로 `TextOperation.Composite([Delete, Delete])` 로 돌려준다
   (뒤 조각을 먼저 지워 앞 조각 인덱스가 흔들리지 않게 정렬).

이 두 규칙은 짝(pair)이다. "insert survive + delete split" 을 양측이 함께 적용하면 수렴한다.
반대로 "insert survive + delete absorb(insert까지 삭제)" 또는 "insert die + delete split" 처럼
짝을 어긋나게 섞으면 TP1 이 깨진다(실제로 발산함 — 아래 예시).

## 수렴 예시 (이 규칙으로 수렴)

`doc = "ABCDE"`, `ins = Insert(2,"X")`, `del = Delete(1,3)` (범위 `[1,4)`, "BCD" 삭제).

- **경로 A** — del 먼저: `apply(doc, del) = "AE"`.
  `transform(ins, del, ·)`: `p=2` 가 `(1,4)` 내부 → 클램프 `Insert(1,"X")` → `"AE"` 에 적용 → **"AXE"**.
- **경로 B** — ins 먼저: `apply(doc, ins) = "ABXCDE"`.
  `transform(del, ins, ·)`: insert(2) 가 delete 범위 `[1,4)` 내부 → split:
  `[1,2)` 와 `[2+1, 4+1)=[3,5)` → 뒤 조각 `Delete(3,2)`(="CD") 먼저, 앞 조각 `Delete(1,1)`(="B") →
  `"ABXCDE"` → `"ABXE"` → **"AXE"**.

양 경로 모두 **"AXE"** → 수렴. 살아남은 글자 `X` 가 원래 의도대로 `A` 와 `E` 사이에 남는다.

## 대안과 기각 사유

- **insert die (삭제가 글자까지 먹음)**: 데이터 손실 + split 규칙과 짝이 맞아야만 수렴.
  사용자 입력 보존이 협업 편집기의 기본 기대치이므로 기각.
- **CRDT(tombstone) 로 전환**: 정확하지만 범위 밖. ADR-0001 에서 OT plain-text 로 한정,
  OT vs CRDT 비교는 향후 과제로 명시.

## 결과

`transform` 은 단일 op 또는 `Composite` 를 돌려줄 수 있고, `apply` 는 Composite 를
순차 적용한다. 속성 테스트(랜덤 동시 op 쌍 다수)가 두 transform 순서의 최종 문자열 동일성을
검증해 이 규칙의 수렴을 고정한다.

## 용어 풀이 (쉽게)

- **inclusion-transform(포함 변환)** — 동시에 일어난 남의 편집을 '이미 반영됐다고 치고' 내 편집의 위치를 그만큼 밀어 보정하는 OT의 기본 셈법.
- **클램프(clamp, 위치 고정)** — 어긋난 위치 값을 허용 범위의 끝으로 끌어다 붙이는 것. 삭제된 구간 안에 끼던 글자를 그 구간 시작점으로 당겨 살려둔다.
- **split(분할)** — 하나의 삭제를 새로 끼어든 글자를 건너뛰도록 두 토막으로 쪼개는 것. 그래야 남의 글자를 같이 지우지 않는다.
- **Composite(복합 연산)** — 삭제 두 조각처럼 여러 편집을 한 묶음으로 돌려주는 형태. apply는 이를 순서대로 차례차례 적용한다.
- **TP1 수렴(convergence)** — 두 편집을 'A 먼저'로 합치든 'B 먼저'로 합치든 최종 글자가 똑같아야 한다는 규칙. 협업 편집기의 생명.
- **속성 테스트(property test)** — 일일이 정한 예시 대신, 무작위 입력을 수없이 만들어 '항상 성립해야 할 규칙(여기선 수렴)'이 깨지는지 자동으로 찾아내는 테스트.

## 참고

- Sun, Ellis — [Operational transformation in real-time group editors: issues, algorithms, and achievements](https://dl.acm.org/doi/10.1145/289444.289469) (1998) — TP1(수렴 속성)의 정의
- Nichols 외 — [High-latency, low-bandwidth windowing in the Jupiter collaboration system](https://dl.acm.org/doi/10.1145/215585.215706) (1995) — inclusion transform 규칙의 출처
- [Daniel Spiewak — Understanding and Applying Operational Transformation](http://www.codecommit.com/blog/java/understanding-and-applying-operational-transformation) — insert/delete inclusion-transform 케이스 해설
