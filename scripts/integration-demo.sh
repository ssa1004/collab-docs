#!/usr/bin/env bash
# Cross-repo 통합 시연 — docker-compose.integration.yml 가 띄운 stub 묶음 위에서
# "문서 생성 → 공유 → 편집 → 댓글" 한 사이클을 collab-docs 본체에 실제로 돌리고,
# 그 결과로 발생하는 share / comment 도메인 event 가 notification-hub stub 에 도달하는지
# 까지 닫아서 보여준다.
#
# cross-repo 통합은 스펙 시연용입니다. auth-service / notification-hub 를 통째로 띄우지
# 않고, collab-docs 가 어떤 event 모양을 내보내는지와 인접 service 가 그걸 어떻게 받는지의
# 경계만 stub 로 닫는다.
#
# (정직 표기) collab-docs 의 현재 코드는 share / comment event 를 Kafka 로 직접 publish
# 하지 않는다 — 현 설계는 in-process 도메인 이벤트만 다룬다. 그래서 이 스크립트는 REST
# 흐름으로 실제 상태 변화를 만든 뒤, 도메인 service 의 outbox relay 역할을 모사해 같은
# event 를 collab.doc.events topic 으로 발사한다. 실제 연동 시점에는 collab-adapter-out 에
# NotificationPort 의 Kafka 어댑터를 붙이는 자리다.
#
# 전제: docker compose -f docker-compose.integration.yml up -d --build 가 이미 실행됨.
#
# 실행:
#   docker compose -f docker-compose.integration.yml up -d --build
#   ./scripts/integration-demo.sh
#   docker compose -f docker-compose.integration.yml down -v

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$REPO_ROOT/docker-compose.integration.yml}"
COMPOSE=(docker compose -f "$COMPOSE_FILE")

COLLAB_BASE="${COLLAB_BASE:-http://localhost:18088}"
AUTH_BASE="${AUTH_BASE:-http://localhost:18085}"
WAIT_SECONDS="${WAIT_SECONDS:-180}"

log()  { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
step() { printf '\n=== %s ===\n' "$*"; }
fail() { printf '\n[FAIL] %s\n' "$*" >&2; exit 1; }

require() {
    command -v "$1" >/dev/null 2>&1 || fail "필수 명령 없음: $1"
}
require curl
require jq
require docker

# base64url encode (no padding, +/ → -_).
b64url() {
    base64 | tr -d '=' | tr '/+' '_-' | tr -d '\n'
}

wait_for() {
    local name="$1" url="$2" deadline
    deadline=$(( $(date +%s) + WAIT_SECONDS ))
    log "[wait] $name 헬스 대기 ($url)"
    while (( $(date +%s) < deadline )); do
        if curl -sf "$url" >/dev/null 2>&1; then
            log "[ok]   $name 응답 OK"
            return 0
        fi
        sleep 2
    done
    fail "$name 가 $WAIT_SECONDS 초 안에 응답하지 않음"
}

# ---------- 0. compose 기동 확인 ----------
if ! "${COMPOSE[@]}" ps --status running --services | grep -q '^collab-docs$'; then
    echo "[demo] collab-docs 가 실행 중이 아닙니다." >&2
    echo "  먼저: docker compose -f docker-compose.integration.yml up -d --build" >&2
    exit 1
fi

# ---------- 1. 헬스 대기 ----------
step "1) 컨테이너 헬스 대기"
wait_for "auth-stub"   "$AUTH_BASE/healthz"
wait_for "collab-docs" "$COLLAB_BASE/actuator/health"

# ---------- 2. JWK Set 조회 ----------
step "2) auth-stub JWK Set 조회 (/.well-known/jwks.json)"
JWKS=$(curl -sf "$AUTH_BASE/.well-known/jwks.json") || fail "JWK Set 조회 실패"
echo "$JWKS" | jq '.keys[0] | {kty, use, kid, alg}'
KID=$(echo "$JWKS" | jq -r '.keys[0].kid')
log "[ok]   kid=$KID — prod 프로필이 jwk-set-uri 로 매핑하면 이 키로 서명 검증"

# ---------- 3. mock JWT 발급 ----------
# 현 dev 프로필(DevSecurityConfig)은 Authorization: Bearer <userId> 의 평문을 그대로
# userId 로 받는다 (서명 검증 X). 그래서 아래 alice / bob 처럼 평문 토큰이면 충분하다.
# 여기서는 추가로 "prod 가 받게 될 JWT 의 claim 모양" 을 시연용으로 한 번 출력한다.
step "3) mock JWT claim 모양 시연 (현 dev 는 Bearer 평문 userId 로 충분)"
NOW=$(date +%s)
EXP=$((NOW + 3600))
HEADER=$(printf '{"alg":"RS256","typ":"JWT","kid":"%s"}' "$KID" | b64url)
PAYLOAD=$(printf '{"iss":"http://auth-stub:8080","sub":"alice","aud":"collab-docs","exp":%d,"iat":%d,"scope":"docs.read docs.write"}' \
    "$EXP" "$NOW" | b64url)
SIG=$(printf 'stub-signature' | b64url)
JWT="$HEADER.$PAYLOAD.$SIG"
log "[ok]   JWT 앞 32자: ${JWT:0:32}... (sub=alice — prod 에서 이 값이 userId 가 됨)"
log "       (현 dev 컨트롤러는 'Authorization: Bearer alice' 평문으로 동일 userId 를 얻음)"

AUTH_ALICE=(-H "Authorization: Bearer alice")
AUTH_BOB=(-H "Authorization: Bearer bob")
JSON=(-H "Content-Type: application/json")

# ---------- 4. 문서 생성 ----------
step "4) POST /api/documents — owner=alice 로 문서 생성"
DOC=$(curl -sf -X POST "$COLLAB_BASE/api/documents" "${JSON[@]}" "${AUTH_ALICE[@]}" \
    -d '{"title":"통합 시연 문서","content":"collab-docs supports realtime editing. Operational transform keeps concurrent edits convergent."}') \
    || fail "문서 생성 실패"
echo "$DOC" | jq '{id, ownerId, version}'
DOC_ID=$(echo "$DOC" | jq -r '.id')
log "[ok]   documentId=$DOC_ID"

# ---------- 5. 공유 (share) ----------
step "5) PUT /api/documents/{id}/share — bob 에게 EDITOR 부여"
ACL=$(curl -sf -X PUT "$COLLAB_BASE/api/documents/$DOC_ID/share" "${JSON[@]}" "${AUTH_ALICE[@]}" \
    -d '{"targetUserId":"bob","role":"EDITOR"}') || fail "공유 실패"
echo "$ACL" | jq '{documentId, ownerId, entries}'
log "[ok]   bob 이 EDITOR 로 추가됨"

# ---------- 6. 편집 (OT) ----------
step "6) POST /api/documents/{id}/edit — bob 이 baseVersion 0 에서 insert"
EDIT=$(curl -sf -X POST "$COLLAB_BASE/api/documents/$DOC_ID/edit" "${JSON[@]}" "${AUTH_BOB[@]}" \
    -d '{"op":{"type":"insert","position":0,"text":"[bob] "},"baseVersion":0}') || fail "편집 실패"
echo "$EDIT" | jq '{newVersion, transformedOp}'
NEW_VERSION=$(echo "$EDIT" | jq -r '.newVersion')
log "[ok]   newVersion=$NEW_VERSION"

# ---------- 7. 댓글 ----------
step "7) POST /api/documents/{id}/comments — alice 가 range 앵커 댓글"
COMMENT=$(curl -sf -X POST "$COLLAB_BASE/api/documents/$DOC_ID/comments" "${JSON[@]}" "${AUTH_ALICE[@]}" \
    -d '{"anchor":{"kind":"range","start":0,"endExclusive":6},"body":"여기 누가 편집 중?"}') || fail "댓글 실패"
echo "$COMMENT" | jq '{id, authorId, body}'
COMMENT_ID=$(echo "$COMMENT" | jq -r '.id')
log "[ok]   commentId=$COMMENT_ID"

# ---------- 8. 도메인 event 발사 (outbox relay 모사) ----------
# collab-docs 가 현재 직접 publish 하지 않는 share / comment event 를, 도메인 service 의
# outbox relay 역할을 대신해 collab.doc.events 로 발사. 실제 연동 시 NotificationPort 의
# Kafka 어댑터가 이 자리를 담당한다.
step "8) collab.doc.events publish — notification-hub-stub 가 받는지 확인"
NOW_ISO=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

SHARE_EVENT=$(jq -nc \
    --arg doc "$DOC_ID" --arg now "$NOW_ISO" \
    '{type:"document.shared", documentId:$doc, ownerId:"alice", targetUserId:"bob", role:"EDITOR", occurredAt:$now}')
COMMENT_EVENT=$(jq -nc \
    --arg doc "$DOC_ID" --arg cid "$COMMENT_ID" --arg now "$NOW_ISO" \
    '{type:"comment.added", documentId:$doc, commentId:$cid, authorId:"alice", notify:["bob"], occurredAt:$now}')

log "produce document.shared + comment.added (key=documentId)"
printf '%s:%s\n%s:%s\n' "$DOC_ID" "$SHARE_EVENT" "$DOC_ID" "$COMMENT_EVENT" \
    | "${COMPOSE[@]}" exec -T domain-producer \
        /opt/kafka/bin/kafka-console-producer.sh \
            --bootstrap-server kafka:9092 \
            --topic collab.doc.events \
            --property "parse.key=true" \
            --property "key.separator=:"

log "[ok]   produce 완료 — notification-hub-stub 의 console-consumer 가 from-beginning 으로 tail 중"
sleep 3

# ---------- 9. 도달 확인 ----------
step "9) notification-hub-stub 로그에서 도메인 event 확인"
if "${COMPOSE[@]}" logs --tail 30 notification-hub-stub | grep -F "$DOC_ID"; then
    log "[ok]   notification-hub-stub 가 documentId=$DOC_ID event 수신 (share + comment)"
else
    fail "notification-hub-stub 가 documentId=$DOC_ID event 를 받지 못함"
fi

step "DONE — 통합 시연 완료"
log "정리: docker compose -f $COMPOSE_FILE down -v"
