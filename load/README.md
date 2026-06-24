# Load test (k6)

collab-docs 의 3 가지 부하 시나리오. 단순 RPS 가 아니라 이 service 특유의 비용을 본다 —
**동시 편집 contention 에서의 OT rebase**(= 여러 명이 같은 문서를 동시에 칠 때 서로 자리를 다투는 상황에서, 늦게 도착한 편집을 먼저 반영된 편집들 위로 자리만 보정해 다시 얹는 합치기 비용), 검색 query latency, RAG(ask) 파이프라인.

기본(zero-infra)(= Docker·DB·API 키 같은 외부 준비물 없이 바로 켜지는 부팅) 프로필이면 H2 + in-memory search/presence + offline 결정론 AI(= 인터넷·API 키 없이 돌고 같은 질문엔 늘 같은 답을 내는 데모용 AI — 진짜 LLM 아님) 로 돌아
외부 인프라 없이 세 시나리오가 모두 동작한다 (ADR-0004). 절대 latency 는 prod 프로필
(Postgres + OpenSearch + Redis + 실 LLM) 과 다르므로 thresholds 는 환경에 맞게 본다.

## 디렉토리

```
load/
├── README.md
└── k6/
    ├── lib/
    │   ├── auth.js          # Authorization: Bearer <userId> 헤더 헬퍼 (dev)
    │   └── config.js        # BASE_URL, 사용자 / 질문 / 검색어 pool
    └── scenarios/
        ├── concurrent-edit.js   # POST /edit — baseVersion contention + OT rebase
        ├── search.js            # GET  /api/search — 검색 latency
        └── ask.js               # POST /ask — RAG (offline 결정론 AI)
```

## 사전 준비

### 1) 대상 app 띄우기

zero-infra 부팅 (외부 의존성 0):

```bash
./gradlew :collab-bootstrap:bootRun        # http://localhost:8080
```

또는 통합 compose 로 띄운 본체에 대고:

```bash
docker compose -f docker-compose.integration.yml up -d --build
export BASE_URL=http://localhost:18088
```

### 2) k6 설치 — 셋 중 하나

```bash
brew install k6 && k6 version                                   # A. 로컬 설치
docker run --rm -i grafana/k6 run - < load/k6/scenarios/search.js   # B. docker
```

C. docker 로 본 app 과 같은 네트워크에서 돌릴 때는 `--network` 와 `BASE_URL` 을 컨테이너
DNS 이름으로 맞춘다.

## 시나리오별 실행

각 시나리오는 `setup()` 단계에서 대상 문서를 직접 시드하므로, 빈 DB 에서 바로 실행해도
hit / 편집 대상이 비지 않는다.

### 1) concurrent-edit — 동시 편집 contention + OT rebase

이 service 의 핵심 부하. 여러 VU(사용자)가 setup 에서 만든 **하나의 공유 문서**를 동시에
친다. 각 edit 은 `baseVersion` 을 현재 서버 버전보다 일부러 뒤처지게 보내 **OT rebase
경로를 강제**한다 (ADR-0002/0003). insert 위치를 0 으로 고정해 동시 insert 의 transform
경계(동시 insert tie-break)를 가장 자주 친다.

```bash
k6 run load/k6/scenarios/concurrent-edit.js
```

| metric | 의미 / 기준 |
|---|---|
| `http_req_duration{name:edit}` p95 / p99 | < 150ms / 400ms (in-memory presence + H2 기준) |
| `edit_latency_ms` p95 (보조) | server-side 편집 latency |
| `edit_rebased` count | transform 으로 위치가 옮겨진 op 수 — contention 이 실제로 일어났는지 |
| `edit_stale_rejected` count | stale baseVersion 을 rebase 대신 거부한 수 (정책 관측 — invariant 아님) |
| `edit_lost` count | **0 (invariant)** — 2xx 인데 transformedOp 가 없으면 위반 |
| `http_req_failed` | < 5% (stale 거부 정책일 때 4xx 여유) |

### 2) search — 검색 latency

`GET /api/search?q=...&limit=...`. 기본은 in-memory 인덱스, prod 는 OpenSearch — 동일
endpoint 라 같은 시나리오로 백엔드만 바꿔 비교한다.

```bash
k6 run load/k6/scenarios/search.js
```

| metric | 기준 |
|---|---|
| `http_req_duration{name:search}` p95 / p99 | < 100ms / 250ms |
| `search_query_latency_ms` p95 (보조 — server-side TTFB) | < 100ms |
| `http_req_failed` | < 1% |

### 3) ask — RAG 질의 (offline 결정론 AI)

`POST /api/documents/{id}/ask`. 기본은 offline 결정론 AI (ADR-0004) — 외부 LLM round-trip
이 없어 응답이 결정론적이다. 관심은 RAG 파이프라인(검색 → context 조립 → 답변 생성)의
server-side 비용과, `citedOrdinals` / `answer` 가 일관되게 채워지는지다.

```bash
k6 run load/k6/scenarios/ask.js
```

| metric | 기준 |
|---|---|
| `http_req_duration{name:ask}` p95 / p99 | < 200ms / 500ms (offline 기준) |
| `ask_latency_ms` p95 (보조) | server-side RAG latency |
| `ask_no_answer` count | **0 (invariant)** — offline 은 항상 답 문자열을 채운다 |
| `ask_offline_responses` count | `offline=true` 응답 수 — 기본 프로필에서 전부여야 |
| `http_req_failed` | < 1% |

## 환경변수

| key | 기본 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | HTTP base (compose 면 `:18088`) |
| `K6_USERS` | `alice,bob,carol,dave,erin,frank` | 동시 편집 사용자 pool (첫 사용자가 owner) |
| `K6_TOKEN` | (빈 값) | prod 프로필 resource server 일 때만 — 외부 발급 RS256 토큰 |
| `K6_QUERIES` | 6개 영문 토큰 | search round-robin 검색어 CSV |
| `K6_QUESTIONS` | 4개 질문 | ask round-robin 질문 (`|` 구분) |

dev 프로필은 `Authorization: Bearer <userId>` 의 평문을 그대로 userId 로 받는다
(DevSecurityConfig — 서명 검증 X). 그래서 부하 시나리오는 토큰을 사용자 이름으로 바꿔
N명을 흉내낸다. prod 프로필에서 부하를 줄 때는 `K6_TOKEN` 으로 실 JWT 를 주입해야 한다.

## 결과 수치에 대하여

**이 README 는 측정 결과 수치를 싣지 않는다.** p95/p99 절대값은 실행 머신(CPU/메모리),
프로필(zero-infra vs prod), 백엔드(in-memory vs OpenSearch/Redis/Postgres)에 따라
크게 갈리므로, 임의의 숫자를 적어두는 것보다 **재현 절차**를 남기는 쪽이 정직하다.
직접 수치를 뽑으려면 각 시나리오를 JSON summary 로 떨군다:

```bash
k6 run --summary-export build/k6/concurrent-edit-summary.json \
       load/k6/scenarios/concurrent-edit.js
# 또는 시계열 전체를 남기려면:
k6 run --out json=build/k6/concurrent-edit.json load/k6/scenarios/concurrent-edit.js
```

zero-infra 의 절대 latency 와 prod 프로필의 절대 latency 는 다르다. 비교가 목적이면
같은 머신에서 프로필만 바꿔(`SPRING_PROFILES_ACTIVE=prod` + 인프라 기동) 두 번 측정해
delta 를 본다. threshold 비교 자체를 끄고 분포만 볼 때는 `--no-thresholds`.

## k6 표준 metric 해석

| metric | 의미 |
|---|---|
| `vus` / `vus_max` | 현재 / 최대 VU |
| `iter_duration` | 한 default 함수 실행 시간 (sleep 포함) |
| `http_req_duration` | HTTP 응답 소요 — connect / TLS / waiting 합 |
| `http_req_waiting` | TTFB (server-side latency 의 근사) |
| `http_req_failed` | non-2xx 비율 |

### 부하 모델

| 시나리오 | executor | 모델 |
|---|---|---|
| concurrent-edit | ramping-vus | 0 → N(사용자 수) VU, 80s. 한 문서 동시 편집 압력 |
| search | constant-arrival-rate | 200 req/s, 60s. read throughput |
| ask | constant-arrival-rate | 50 req/s, 60s. RAG 파이프라인 |

`ramping-vus` 는 connection / 누적 상태(편집 버전)가 중요한 동시 편집에, `constant-
arrival-rate` 는 throughput 기준의 read 경로(search / ask)에 쓴다.
