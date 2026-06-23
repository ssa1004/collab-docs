#!/usr/bin/env bash
#
# gen-openapi.sh — fetch the live springdoc spec and normalize it deterministically.
#
# Single source of truth for both a human run and the CI drift gate, so both produce
# byte-identical output. The app must already be running zero-infra (default profile).
#
# Usage:
#   PORT=18081 ./scripts/gen-openapi.sh                 # write docs/openapi/collab-docs.yaml
#   PORT=18081 OUT=/tmp/regen.yaml ./scripts/gen-openapi.sh   # write elsewhere (drift check)
#
# Env:
#   PORT  app port (default 8080)
#   OUT   output path (default docs/openapi/collab-docs.yaml, relative to repo root)
set -euo pipefail

PORT="${PORT:-8080}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$REPO_ROOT/docs/openapi/collab-docs.yaml}"

command -v yq >/dev/null 2>&1 || { echo "ERROR: yq not found on PATH" >&2; exit 1; }

raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT

# springdoc serves the live spec; -f makes curl fail loudly if the app isn't up.
curl -fsS "http://localhost:${PORT}/v3/api-docs.yaml" -o "$raw"

# Deterministic normalization (see docs/openapi/README.md for the rationale):
#  - stable servers placeholder (drop the boot port/host)
#  - pin info.version
#  - sort every enum array, recursively
#  - sort every map's keys, recursively
yq '
  .servers = [{"url": "/", "description": "collab-docs API (server url normalized for spec stability)"}]
  | .info.version = "0.1.0"
  | (.. | select(has("enum")).enum) |= sort
  | sort_keys(..)
' "$raw" > "$OUT"

echo "wrote $OUT"
echo "paths:      $(yq '.paths | keys | length' "$OUT")"
echo "operations: $(yq '[.paths[] | keys | .[]] | length' "$OUT")"
