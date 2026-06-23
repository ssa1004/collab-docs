# collab-docs — 자주 쓰는 명령 단일 진입점 (실시간 협업 문서 편집 + 문서 AI)
#
#   make run      앱 호스트 실행 (:collab-bootstrap bootRun, :8080 — 외부 인프라 0)
#   make demo     동시 편집 → 검색 → RAG/요약 한 cycle 데모 (부팅 → 실행 → 종료)
#   make build    전체 gradle 빌드 (테스트 제외)
#   make test     전체 테스트 (./gradlew test)
#   make down     호스트에 떠 있는 잔여 bootRun 프로세스 정리
#   make clean    gradle clean + 데모 산출물 제거
#   make urls     주요 UI / 엔드포인트
#
# 기본 프로필은 외부 인프라 0 으로 뜬다 — Docker / Postgres / Redis / OpenSearch / LLM 키 없이
# H2 + in-memory 검색·presence + offline 결정론 AI 로 전체 기능(동시 편집·검색·AI)이 돈다.
# 같은 코드가 prod 프로필에서 실인프라(Postgres / OpenSearch / Redis / 실 LLM)로 뜬다 —
# 어댑터만 port 뒤에서 교체된다. 자세한 건 README "Quickstart" 와 ADR-0004 참조.

GRADLE := ./gradlew

.DEFAULT_GOAL := help
.PHONY: help build test run demo down clean urls

help: ## 이 도움말
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

build: ## 전체 gradle 빌드 (테스트 제외)
	$(GRADLE) build -x test

test: ## 전체 테스트
	$(GRADLE) test

run: ## 앱 호스트 실행 (:8080 — 외부 인프라 0, 기본 프로필)
	$(GRADLE) :collab-bootstrap:bootRun

demo: ## 동시 편집 → 검색 → RAG/요약 한 cycle 데모 (스스로 부팅·종료)
	./scripts/demo.sh

down: ## 호스트에 떠 있는 잔여 bootRun 프로세스 정리 (데모는 스스로 종료됨)
	@pkill -f 'collab-bootstrap:bootRun' 2>/dev/null || true
	@echo "→ 잔여 bootRun 프로세스 정리 완료"

clean: ## gradle clean + 데모 산출물 제거
	$(GRADLE) clean
	@rm -f /tmp/collab-docs.*.yaml 2>/dev/null || true

urls: ## 주요 UI / 엔드포인트
	@echo "Swagger UI     http://localhost:8080/swagger-ui.html"
	@echo "OpenAPI (yaml) http://localhost:8080/v3/api-docs.yaml"
	@echo "WebSocket      ws://localhost:8080/ws/documents/{id}"
	@echo "Actuator       http://localhost:8080/actuator/health"
	@echo "collab-docs    :8080  (기본 프로필 = zero-infra)"
