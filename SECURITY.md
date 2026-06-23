# Security Policy

본 저장소는 개인 학습 / 포트폴리오 목적의 데모 백엔드입니다. 운영 환경에서 그대로
사용하는 것을 전제로 하지 않으며, 발견된 취약점은 가능한 범위에서 패치합니다.

## 지원 버전

본 프로젝트는 학습 / 포트폴리오 목적이며, 정식 LTS 정책을 운영하지 않습니다.
보안 수정은 `main` 브랜치의 최신 커밋에만 적용됩니다.

| 버전 | 지원 |
| --- | --- |
| `main` (latest) | O |
| 그 외 (tag / 과거 commit) | X |

릴리스 태그는 아직 발급하지 않았습니다 (`0.1.0` SNAPSHOT 단계).

## 취약점 신고

공개 issue 로 보고하지 말고 아래 경로 중 하나로 비공개 연락 부탁드립니다.

1. GitHub Security Advisory — [Report a vulnerability](https://github.com/ssa1004/collab-docs/security/advisories/new)
   - 권장. 공개 전 fix 협의(coordinated disclosure)가 가능합니다.
2. 메일 — `wittyahn@gmail.com`
   - 제목 prefix `[collab-docs][SECURITY]` 권장.

신고 시 가능하면 다음 정보를 포함해 주세요.

- 영향받는 모듈 / endpoint / 커밋 hash
- 재현 절차 (가능한 minimal PoC)
- 예상 영향 범위 (정보 노출 / 권한 우회 / RCE / DoS 등)
- 권장 수정 방향 (있다면)

처음 영업일 기준 3일 이내에 수신 확인 회신을, 영향도 평가 후 합리적인 기간 내에 수정을
진행합니다. 조정된 공개(coordinated disclosure)에 협조하겠습니다. 보고자에 대한 보상(bounty)
프로그램은 운영하지 않습니다.

## 운영 시 주의

- 기본 프로필은 **dev 전용 permissive 인증**입니다 — `Authorization: Bearer <name>` 의 평문
  값을 그대로 userId 로 받습니다(서명 검증 없음, ADR-0004). 운영 배포 시 `prod` 프로필의
  JWT resource server(`spring.security.oauth2.resourceserver.jwt.issuer-uri`)로 반드시
  전환해야 합니다.
- zero-infra 기본값으로 띄운 H2 / in-memory 검색·presence 는 로컬 개발 전용입니다. 외부 노출
  환경에서 그대로 쓰지 마세요 — `prod` 프로필에서 Postgres / OpenSearch / Redis 로 교체됩니다.
- OWASP API Top 10 관점의 통제 매핑은 [`docs/security/owasp-mapping.md`](docs/security/owasp-mapping.md)
  에 정리되어 있습니다 (dev 전용 한계 포함 정직 표기).
