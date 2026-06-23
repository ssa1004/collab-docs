#!/usr/bin/env bash
#
# demo.sh — boot collab-docs zero-infra (H2 + in-memory search/presence + offline AI),
# run the full happy path, print each response, then shut down.
#
# Flow:
#   create doc -> two concurrent edits (same baseVersion) converge
#   -> search -> ask (RAG) -> summarize -> add comment -> list versions
#
# No Docker, no API keys, no external infra. macOS-friendly (no `timeout`; we poll+kill).
#
# Usage:
#   ./scripts/demo.sh                 # picks a free high port, boots, runs, shuts down
#   PORT=18099 ./scripts/demo.sh      # use a specific port
#   BASE_URL=http://localhost:8080 ./scripts/demo.sh   # run against an already-running app (no boot)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ---- pretty printing -------------------------------------------------------
if command -v yq >/dev/null 2>&1; then PRETTY="yq -p=json -o=json"; else PRETTY="cat"; fi
bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1;36m=== %s ===\033[0m\n' "$*"; }
show() { echo "$1" | $PRETTY; }   # pretty-print a JSON blob
field() { echo "$1" | yq -p=json "$2"; }  # extract one field

require() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' not found on PATH" >&2; exit 1; }; }
require curl
require yq

# ---- boot (unless BASE_URL points at an already-running app) ----------------
BOOT_PID=""
LOG="$(mktemp -t collab-demo.XXXXXX.log)"

cleanup() {
  if [ -n "$BOOT_PID" ]; then
    bold ""
    step "shutting down (pid $BOOT_PID)"
    # bootRun forks a toolchain JVM; kill the whole process group best-effort.
    kill "$BOOT_PID" 2>/dev/null || true
    pkill -P "$BOOT_PID" 2>/dev/null || true
    # also kill anything still holding the port
    if [ -n "${PORT:-}" ]; then
      lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | xargs -r kill 2>/dev/null || true
    fi
    echo "stopped."
  fi
  rm -f "$LOG"
}
trap cleanup EXIT INT TERM

if [ -n "${BASE_URL:-}" ]; then
  bold "Using already-running app at $BASE_URL (skipping boot)."
else
  # pick a free high port unless one was given
  if [ -z "${PORT:-}" ]; then
    for p in 18091 18092 18093 18094 18095 18096; do
      if ! lsof -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then PORT="$p"; break; fi
    done
  fi
  : "${PORT:?could not find a free port}"
  BASE_URL="http://localhost:$PORT"

  bold "Booting collab-docs zero-infra on port $PORT (H2 + in-memory search/presence + offline AI)..."
  ./gradlew --console=plain :collab-bootstrap:bootRun --args="--server.port=$PORT" >"$LOG" 2>&1 &
  BOOT_PID=$!

  # poll health (macOS has no `timeout`): up to ~150s
  printf "waiting for health"
  up=""
  for _ in $(seq 1 150); do
    if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then up="yes"; break; fi
    if ! kill -0 "$BOOT_PID" 2>/dev/null; then
      # the wrapper may have forked the real JVM and exited; only fail if the port is dead too
      curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1 && { up="yes"; break; }
    fi
    printf "."; sleep 1
  done
  echo
  if [ -z "$up" ]; then
    echo "ERROR: app did not become healthy. Last log lines:" >&2
    tail -30 "$LOG" >&2
    exit 1
  fi
  HEALTH="$(curl -fsS "$BASE_URL/actuator/health")"
  bold "Health: $(field "$HEALTH" '.status')  (db: $(field "$HEALTH" '.components.db.details.database'))"
fi

AUTH_ALICE=(-H 'Authorization: Bearer alice')   # dev: Bearer <plaintext> == userId
AUTH_BOB=(-H 'Authorization: Bearer bob')
JSON=(-H 'Content-Type: application/json')

# ---------------------------------------------------------------------------
step "1) create document (owner=alice)"
DOC="$(curl -fsS -X POST "$BASE_URL/api/documents" "${JSON[@]}" "${AUTH_ALICE[@]}" \
  -d '{"title":"Q3 Launch Plan","content":"The collab-docs project supports realtime editing. Operational transform keeps concurrent edits convergent. Search and document AI assist are built in."}')"
show "$DOC"
ID="$(field "$DOC" '.id')"
bold "documentId=$ID  version=$(field "$DOC" '.version')"

step "2) share with bob as EDITOR (so two real collaborators can edit)"
show "$(curl -fsS -X PUT "$BASE_URL/api/documents/$ID/share" "${JSON[@]}" "${AUTH_ALICE[@]}" \
  -d '{"targetUserId":"bob","role":"EDITOR"}')"

step "3) two CONCURRENT edits from the SAME baseVersion 0 -> server rebases the 2nd"
bold "alice inserts '[alice] ' at position 0 (baseVersion 0):"
E1="$(curl -fsS -X POST "$BASE_URL/api/documents/$ID/edit" "${JSON[@]}" "${AUTH_ALICE[@]}" \
  -d '{"op":{"type":"insert","position":0,"text":"[alice] "},"baseVersion":0}')"
show "$E1"
bold "bob ALSO inserts '[bob] ' at position 0 (still baseVersion 0 -> stale):"
E2="$(curl -fsS -X POST "$BASE_URL/api/documents/$ID/edit" "${JSON[@]}" "${AUTH_BOB[@]}" \
  -d '{"op":{"type":"insert","position":0,"text":"[bob] "},"baseVersion":0}')"
show "$E2"
bold "note: bob's op was rebased to position $(field "$E2" '.transformedOp.position') (server OT shifted it past alice's committed insert) -> no lost edit"

step "4) converged document (both inserts survive, deterministic order)"
CONVERGED="$(curl -fsS "$BASE_URL/api/documents/$ID" "${AUTH_ALICE[@]}")"
show "$CONVERGED"
bold "content -> \"$(field "$CONVERGED" '.content')\"  (version $(field "$CONVERGED" '.version'))"

step "5) full-text search (q=transform)"
show "$(curl -fsS "$BASE_URL/api/search?q=transform&limit=5" "${AUTH_ALICE[@]}")"

step "6) ask the document (RAG; offline deterministic AI)"
show "$(curl -fsS -X POST "$BASE_URL/api/documents/$ID/ask" "${JSON[@]}" "${AUTH_ALICE[@]}" \
  -d '{"question":"What keeps concurrent edits convergent?","topK":3}')"

step "7) summarize the document (extractive; offline=true)"
show "$(curl -fsS -X POST "$BASE_URL/api/documents/$ID/summarize" "${JSON[@]}" "${AUTH_ALICE[@]}")"

step "8) add a comment (range anchor)"
show "$(curl -fsS -X POST "$BASE_URL/api/documents/$ID/comments" "${JSON[@]}" "${AUTH_ALICE[@]}" \
  -d '{"anchor":{"kind":"range","start":0,"endExclusive":7},"body":"Who is editing here?"}')"

step "9) list version history (the OT edit log)"
show "$(curl -fsS "$BASE_URL/api/documents/$ID/versions" "${AUTH_ALICE[@]}")"

bold ""
bold "Happy path complete: create -> concurrent-edit-converge -> search -> ask -> summarize -> comment -> versions."
