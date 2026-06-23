# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────────────────
# collab-docs — Kotlin/Spring Boot app image (multi-stage, non-root).
# Stage 1 builds the bootJar with the Gradle wrapper (JDK 21 toolchain).
# Stage 2 is a slim, hardened JRE runtime that runs as an unprivileged user.
# ─────────────────────────────────────────────────────────────────────────────

# ---------- build stage ----------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Copy only the wrapper + build scripts first so dependency resolution is cached
# independently of source changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY collab-domain/build.gradle.kts ./collab-domain/
COPY collab-application/build.gradle.kts ./collab-application/
COPY collab-adapter-in/build.gradle.kts ./collab-adapter-in/
COPY collab-adapter-out/build.gradle.kts ./collab-adapter-out/
COPY collab-bootstrap/build.gradle.kts ./collab-bootstrap/
# Keep the empty e2e-tests project dir so settings.gradle.kts resolves; it has no
# build script of its own and contributes no tasks to the bootJar build.
RUN mkdir -p e2e-tests && chmod +x gradlew && ./gradlew --version > /dev/null

# Now copy sources and build the executable jar (tests run in CI, not here).
COPY collab-domain/src ./collab-domain/src
COPY collab-application/src ./collab-application/src
COPY collab-adapter-in/src ./collab-adapter-in/src
COPY collab-adapter-out/src ./collab-adapter-out/src
COPY collab-bootstrap/src ./collab-bootstrap/src
RUN ./gradlew :collab-bootstrap:bootJar --no-daemon -x test \
    && cp collab-bootstrap/build/libs/collab-docs.jar /workspace/app.jar

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Patch OS packages and add a curl for the HEALTHCHECK, then create a non-root user.
# hadolint ignore=DL3018
RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && addgroup -g 1000 -S app \
    && adduser -u 1000 -S -G app -h /app app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport" \
    SERVER_PORT=8080
EXPOSE 8080

USER app

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# OCI image metadata (kept last so it does not bust the build cache).
LABEL org.opencontainers.image.title="collab-docs" \
      org.opencontainers.image.description="Realtime collaborative document editing + document AI (learning/portfolio backend)" \
      org.opencontainers.image.source="https://github.com/ssa1004/collab-docs" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:21-jre-alpine"
