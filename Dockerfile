# =====================================================================================
#  One Dockerfile for all five services, parameterised by MODULE.
#
#  Five near-identical Dockerfiles is five places for a base image to go stale and for a
#  security fix to be applied four times. The only thing that differs between the services
#  is which module to build and which port it listens on, so those are build arguments.
#
#    docker build --build-arg MODULE=order-service --build-arg SERVICE_PORT=8081 \
#                 -t oms/order-service:local .
# =====================================================================================

# -------------------------------------------------------------------------------------
#  Stage 1: build
#
#  Builds only the requested module and what it depends on (-pl ... -am), so a change in
#  market-data-service does not rebuild order-service.
# -------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

ARG MODULE
WORKDIR /src

# POMs first, source second. Dependency resolution is the slow part of a Maven build, and
# this ordering means it is only redone when a POM changes rather than on every source edit.
COPY pom.xml .
COPY oms-common/pom.xml          oms-common/
COPY oms-web/pom.xml             oms-web/
COPY order-service/pom.xml       order-service/
COPY matching-engine/pom.xml     matching-engine/
COPY matching-bench/pom.xml      matching-bench/
COPY market-data-service/pom.xml market-data-service/
COPY position-service/pom.xml    position-service/
COPY api-gateway/pom.xml         api-gateway/

# A BuildKit cache mount keeps the local repository warm between builds without baking it
# into an image layer. Best-effort: go-offline cannot resolve reactor-internal artifacts
# that have not been built yet, which is expected, so a failure here must not fail the build.
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q dependency:go-offline -pl "${MODULE}" -am || true

COPY oms-common/src          oms-common/src
COPY oms-web/src             oms-web/src
COPY order-service/src       order-service/src
COPY matching-engine/src     matching-engine/src
COPY market-data-service/src market-data-service/src
COPY position-service/src    position-service/src
COPY api-gateway/src         api-gateway/src

# Tests are NOT run here. They run in CI, where a failure blocks the merge and the report is
# published. Running them again during an image build doubles build time and would make the
# image depend on Testcontainers being able to reach a Docker daemon from inside a build.
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests -pl "${MODULE}" -am package

# The Boot plugin gives the executable jar the "app" classifier so the plain jar stays usable
# as a library (matching-bench depends on matching-engine as one). Pick the executable one.
RUN cp "${MODULE}/target/${MODULE}"-*-app.jar /app.jar

# -------------------------------------------------------------------------------------
#  Stage 2: runtime
#
#  JRE, not JDK: no compiler and no jar tool in the shipped image. Smaller, and a smaller
#  attack surface.
# -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG SERVICE_PORT=8080

# curl for the healthcheck; Alpine ships none. tzdata so timestamps are not all UTC-naive.
RUN apk add --no-cache curl tzdata \
 && addgroup -S -g 10001 oms \
 && adduser  -S -u 10001 -G oms -s /sbin/nologin oms

WORKDIR /app
COPY --from=build --chown=10001:10001 /app.jar app.jar

# Non-root. A process that does not need root should not have it: a container escape from a
# root process is a root process on the host.
USER 10001:10001

ENV SERVICE_PORT=${SERVICE_PORT} \
    SPRING_PROFILES_ACTIVE=docker \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=50 -XX:+UseG1GC \
-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the cgroup limit, so one setting
# stays correct whatever memory the container is given. A hard-coded -Xmx is wrong the moment
# somebody changes the Kubernetes limit, and the failure mode is an OOMKill rather than an
# error message.
#
# ExitOnOutOfMemoryError is deliberate: a JVM that has exhausted its heap is not going to
# recover, and a process that limps along failing every request is worse for the platform
# than one that dies and lets the orchestrator replace it.

EXPOSE ${SERVICE_PORT}

# start-period is generous because a Spring context plus Flyway migrations plus a Kafka
# consumer-group join takes tens of seconds on a cold start. Too short a start period makes
# the orchestrator kill a container that was merely still booting.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS "http://localhost:${SERVICE_PORT}/actuator/health/readiness" || exit 1

# sh -c because JAVA_OPTS has to be word-split. The leading `exec` matters: without it the
# shell stays as PID 1, swallows SIGTERM, and the container has to be killed after the grace
# period instead of shutting down gracefully - which for order-service means abandoning
# in-flight orders and an undrained outbox.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
