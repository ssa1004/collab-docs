# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────────────────
# realtime-gateway — Node.js/TypeScript edge WebSocket gateway (non-root).
# Stage 1 installs all deps and compiles TS -> dist/.
# Stage 2 keeps only production deps + compiled output and runs as 'node'.
# ─────────────────────────────────────────────────────────────────────────────

# ---------- build stage ----------
FROM node:26-alpine AS build
WORKDIR /app

# Install with the lockfile for reproducible builds; cache this layer on
# package metadata only.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# Compile TypeScript to dist/, then drop devDependencies so they are not
# carried into the runtime image.
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

# ---------- runtime stage ----------
FROM node:26-alpine AS runtime

# Patch base OS packages and add a curl for the HEALTHCHECK. The 'node' image
# already ships an unprivileged 'node' user (uid 1000); reuse it.
# hadolint ignore=DL3018
RUN apk upgrade --no-cache && apk add --no-cache curl

WORKDIR /app
ENV NODE_ENV=production \
    PORT=8090

COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node package.json ./

EXPOSE 8090
USER node

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/healthz" || exit 1

ENTRYPOINT ["node", "dist/server.js"]

LABEL org.opencontainers.image.title="collab-realtime-gateway" \
      org.opencontainers.image.description="JWT-authenticated WebSocket edge gateway for collab-docs" \
      org.opencontainers.image.source="https://github.com/ssa1004/collab-docs" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="docker.io/library/node:20-alpine"
