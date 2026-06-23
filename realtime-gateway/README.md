# realtime-gateway

Edge WebSocket gateway for **collab-docs**. It authenticates clients and relays
real-time edit ops and presence between browser clients and the authoritative
Kotlin backend. This is a personal learning / portfolio project.

```
 browser / client          realtime-gateway (this)            Kotlin backend
   ws CLIENT      <----->     ws SERVER  +  ws CLIENT   <----->   ws SERVER
                              (auth + fan-out + relay)          (authoritative OT)
```

## Edge vs. core split (per ADR-0001)

ADR-0001 deliberately splits this system into a **core** and an **edge**:

- **Core — Kotlin / Spring Boot** (`collab-domain` … `collab-bootstrap`). It owns
  all correctness-critical logic: the **Operational Transform (OT) engine**,
  document versioning, access control, search and the RAG/AI pipeline. The OT
  engine is the hard part of collaborative editing — concurrent inserts/deletes
  have to converge — so it lives in pure, framework-free Kotlin pinned by unit
  tests. The backend is the single source of truth: it applies every op
  authoritatively and broadcasts the transformed result to all collaborators.

- **Edge — Node.js / TypeScript** (this module). It owns the **connection plane**:
  terminating client WebSockets, verifying JWTs, grouping connections into
  document rooms, and relaying frames to/from the core. **It does not do OT** and
  holds no document state — it is a transparent, authenticated pipe.

The gateway is intentionally "dumb" about document semantics. If a frame is a
valid client frame it is relayed **verbatim** upstream; whatever the backend
sends back is relayed **verbatim** downstream. That keeps the OT contract in
exactly one place (the core) and lets the edge scale connections independently.

### Why Node for the WebSocket edge

- **Connection scale-out.** The edge's job is holding tens of thousands of mostly
  idle long-lived sockets and shuffling small JSON frames. Node's single-threaded
  event loop with `ws` is a natural, memory-light fit for that I/O-bound fan-out
  workload, and it scales horizontally behind a load balancer without sharing
  state (each connection is independent; the core does the coordination).
- **Polyglot learning goal.** ADR-0001 calls out polyglot edge/core separation as
  an explicit learning objective: keep the JVM where the rich domain logic and
  transactions live, and put the lightweight real-time fan-out tier in Node.
- **Clean seam.** Auth, rate-limiting, origin checks and connection management are
  edge concerns. Pushing them to a small TypeScript service keeps the Spring
  backend focused on the domain and gives one obvious place to evolve transport.

## What it does

1. **Authenticate on connect.** Clients present a JWT as
   `Authorization: Bearer <token>` (or `?access_token=<token>` for browser
   `WebSocket` clients that cannot set headers). The gateway verifies **HS256**
   against a shared dev secret (`JWT_SECRET`) and uses the `sub` claim as the
   `userId`.
   - **Dev-permissive fallback.** With no `JWT_SECRET` set, the gateway decodes
     (does not verify) the token and trusts its `sub`, defaulting to `demo-user`.
     This mirrors the backend's dev profile (`DevSecurityConfig`) so the whole
     stack boots with **zero infrastructure and no IdP**. It is documented as
     **not for production**; production sets a real secret and strict verification.
2. **Join a room.** The path `/ws/documents/{id}` selects the document room. Each
   client connection joins exactly one room (`RoomManager`).
3. **Relay to the core.** For each client the gateway opens one upstream
   WebSocket to `BACKEND_WS_URL/{id}`, forwarding the resolved `userId` so the
   backend attributes ops to the right principal. Client `edit`/`presence` frames
   go upstream; the backend's `edit`/`presence`/`ack`/`welcome`/`error` frames
   come back down — all verbatim. The backend performs the OT.
4. **Health.** `GET /healthz` returns gateway status (active connections, rooms,
   backend URL, auth mode) for liveness/readiness probes.

## Wire protocol

The protocol is defined by the backend's `CollabWebSocketHandler`. All frames are
JSON text. The only frames the gateway originates itself are:

- `{"type":"gateway-welcome","documentId":...,"userId":...,"source":"gateway"}` —
  sent immediately on accept, while the upstream link opens.
- `{"type":"error","message":...,"source":"gateway"}` — for malformed or
  unsupported client frames, or backend unavailability.

Everything else (`edit`, `presence`, `ack`, `welcome`) passes through untouched.

## Configuration

Copy `.env.example` to `.env` (gitignored) and adjust:

| Variable          | Default                               | Meaning                                                            |
| ----------------- | ------------------------------------- | ------------------------------------------------------------------ |
| `PORT`            | `8090`                                | Edge WS + `/healthz` HTTP port.                                    |
| `JWT_SECRET`      | _(empty)_                             | HS256 secret. Empty ⇒ dev-permissive (no signature check).        |
| `BACKEND_WS_URL`  | `ws://localhost:8080/ws/documents`    | Authoritative backend WS base; `{id}` is appended per connection. |
| `LOG_LEVEL`       | `info`                                | `debug` \| `info` \| `warn` \| `error`.                           |
| `JWT_STRICT`      | `true`                                | When a secret is set, reject unverifiable tokens (else fall back). |

## Develop & run

```bash
npm install
npm run build        # tsc -> dist/
npm test             # vitest unit + integration tests
npm run lint         # eslint (flat config)
npm run dev          # tsx watch src/server.ts
npm start            # node dist/server.js (after build)
```

### Smoke against the real backend (optional)

```bash
# 1) boot the Kotlin backend (zero-infra) on 8080
./gradlew :collab-bootstrap:bootRun --args='--server.port=8080'
# 2) boot the gateway pointing at it
PORT=8090 BACKEND_WS_URL=ws://localhost:8080/ws/documents npm run dev
# 3) connect a client to ws://localhost:8090/ws/documents/<docId> and send
#    {"type":"edit","op":{...},"baseVersion":0}
```

## Layout

```
src/
  server.ts         # entrypoint: HTTP /healthz, WS upgrade+auth, wiring
  auth.ts           # JWT (HS256) verify/reject + dev-permissive fallback
  room.ts           # RoomManager: per-document connection tracking + fan-out
  backend-relay.ts  # one upstream ws client per connection; buffer-until-open
  protocol.ts       # frame parse / validation / gateway control frames
  config.ts         # env -> typed GatewayConfig
  logger.ts         # minimal structured JSON logger
test/               # vitest: auth, room, relay framing, protocol, server e2e
```

## Scope / honesty

This module is transport only. It does **not** implement OT, persistence, search
or AI — those are the Kotlin core's job (ADR-0001). The dev-permissive auth mode
exists so the stack runs with zero infra for learning and is clearly not a
production security posture.
