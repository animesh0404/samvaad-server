# syntax=docker/dockerfile:1
# Samvaad production image (ADR 0012).
#
# Standard JVM/Spring Boot runtime only: Angular/Node/Gradle exist solely in
# the build stages. The runtime stage holds the executable JAR plus a JRE.
# PostgreSQL is never embedded; it is provided by the Compose stack.

# Stage 1: Angular web-admin production bundle.
FROM node:24-bookworm-slim AS webadmin
WORKDIR /build/web-admin
COPY web-admin/package.json web-admin/package-lock.json ./
RUN npm ci
COPY web-admin/ ./
RUN npm run build

# Stage 2: Spring Boot executable JAR. The Gradle build's own npm step is
# skipped (-x buildWebAdmin); the prebuilt bundle above is placed where the
# Gradle copy task expects it.
FROM gradle:9.7.0-jdk25 AS server-build
WORKDIR /build
COPY server/ ./server/
COPY --from=webadmin /build/web-admin/dist ./web-admin/dist/
WORKDIR /build/server
# The image already provides the matching Gradle release, so invoke it
# directly instead of the wrapper (which would re-download it).
#
# GitHub Packages authentication (ADR 0024): `com.samvaad:e2ee-client:0.1.0`
# resolves from GitHub Packages, which requires authentication even for
# reads. Credentials reach Gradle ONLY through BuildKit secret mounts
# (`--secret id=github_actor` / `--secret id=github_token`); they are
# never ARG/ENV, never COPYied, and never persist in any layer. The
# runtime stage below starts FROM a clean JRE image and copies only the
# built JAR, so no credential can leak into the final image. The RUN
# command intentionally never prints the secret values.
RUN --mount=type=secret,id=github_actor --mount=type=secret,id=github_token \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    gradle bootJar -x buildWebAdmin --no-daemon

# Stage 3: runtime. Alpine-based Temurin JRE: materially smaller with no
# compatibility cost for this pure-Java application (no JNI dependencies).
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=server-build /build/server/build/libs/samvaad-server-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
