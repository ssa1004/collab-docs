# OpenAPI spec — `collab-docs.yaml`

`collab-docs.yaml` is the **committed, normalized** OpenAPI 3.1 spec for the collab-docs REST API. It is generated **from the running application** (springdoc introspects the live Spring controllers), then normalized deterministically so it can be diffed in a CI drift gate.

> The WebSocket protocol (`/ws/documents/{id}`) is **not** part of this OpenAPI document — OpenAPI 3.1 has no first-class WebSocket model. The WS wire protocol is documented in the root `README.md` and in `CollabWebSocketHandler`'s KDoc.

## How it's generated

The app must be booted **zero-infra** (default profile = H2 + in-memory search/presence + offline AI — no Docker, no API keys). springdoc serves the spec at `/v3/api-docs.yaml`.

```bash
# 1) boot the app on a free high port (zero-infra; default profile)
./gradlew :collab-bootstrap:bootRun --args='--server.port=18081'

# 2) fetch + normalize into the committed file (see normalization rules below)
PORT=18081 ./scripts/gen-openapi.sh
```

`scripts/gen-openapi.sh` does exactly the steps below; it is the single source of truth so the drift gate and a human run produce byte-identical output. (`scripts/demo.sh` does **not** regenerate the spec — it only exercises the API.)

### Normalization rules (what the drift gate needs)

The raw springdoc output is **not** stable across runs/hosts: the `servers` URL embeds the boot port, and map-key / `enum` ordering can vary. We normalize with `yq` so the committed file only changes when the **API surface actually changes**:

1. **`servers`** is replaced with a single stable placeholder (`url: /`). Otherwise the file would flip every time you boot on a different port.
2. **`info.version`** is pinned to the project version (`0.1.0`) so a future springdoc default bump doesn't cause spurious drift.
3. **Every `enum` array is sorted** (`(.. | select(has("enum")).enum) |= sort`) — recursively, anywhere in the document.
4. **Every map's keys are sorted recursively** (`sort_keys(..)`).

The exact transform:

```bash
yq '
  .servers = [{"url": "/", "description": "collab-docs API (server url normalized for spec stability)"}]
  | .info.version = "0.1.0"
  | (.. | select(has("enum")).enum) |= sort
  | sort_keys(..)
' raw-api-docs.yaml > docs/openapi/collab-docs.yaml
```

## Reproducibility / drift gate

Because the transform is deterministic, regenerating twice yields **identical bytes**:

```bash
# regenerate into a temp file and diff against the committed spec
PORT=18081 OUT=/tmp/collab-docs.regen.yaml ./scripts/gen-openapi.sh
diff docs/openapi/collab-docs.yaml /tmp/collab-docs.regen.yaml   # exit 0 = no drift
```

A CI job boots the app, regenerates, and fails if `diff` is non-empty — forcing the committed spec to track the controllers. To refresh after an intentional API change: rerun `gen-openapi.sh` and commit the new `collab-docs.yaml`.

## Validity

```bash
yq '.openapi and .paths' docs/openapi/collab-docs.yaml   # -> true
```

The committed spec currently covers **10 paths / 13 operations** across the document, search, and AI controllers.
