# ==============================================================================
# JAVA SERVICE  -  Spring Boot (or any runnable jar) built with Maven or the Gradle
# wrapper. Used for BOTH order-service and executor-service: same file, same args.
# Build context = the service folder (the one with pom.xml / gradlew). Needs BuildKit.
#
# Everything the app needs at run time (DB, Kafka, JWT, ports...) comes from
# ENVIRONMENT VARIABLES set by docker-compose / Kubernetes, never from this file.
#
# WHAT TO CHANGE PER PROJECT (all are --build-arg, no editing needed):
#   JAVA_VERSION    must match the project's <java.version>                     (21)
#   MAVEN_VERSION   Maven used to build                                         (3.9.9)
#   APP_PORT        port the app listens on. Sets SERVER_PORT, which Spring Boot
#                   reads and which overrides server.port in application.yml    (8080)
#   MODULE          multi-module Maven only: the module to build, e.g. "app"    ("")
#   RUNTIME_IMAGE   the image the jar runs on. Alpine is small. Use
#                   eclipse-temurin:21-jre-jammy if the app needs glibc
#                   (native libraries, or a Python subprocess).                 (temurin jre-alpine)
#   JAVA_OPTS       extra JVM flags (also overridable at run time with -e)
#
# If the Java process starts a PYTHON subprocess (like the ETP executor does), use
# ../../docker-variants/java-python.Dockerfile instead.
# ==============================================================================

ARG JAVA_VERSION=21
ARG MAVEN_VERSION=3.9.9
ARG RUNTIME_IMAGE=eclipse-temurin:${JAVA_VERSION}-jre-alpine

# ---------- Stage 1: compile -------------------------------------------------
FROM maven:${MAVEN_VERSION}-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /app

# Whole folder (see .dockerignore). The dependency cache is a BuildKit cache mount, so
# it survives between builds even though source changes - and it works for multi-module
# projects, which the "copy pom.xml first" trick does not.
COPY . .

ARG MODULE=""
RUN --mount=type=cache,target=/root/.m2 --mount=type=cache,target=/root/.gradle \
    set -eu; \
    if [ -f pom.xml ]; then \
        mvn -B -q package -DskipTests ${MODULE:+-pl "$MODULE" -am}; \
    elif [ -f gradlew ]; then \
        chmod +x gradlew && ./gradlew --no-daemon -q assemble -x test; \
    else \
        echo "Neither pom.xml nor gradlew found in the build context" >&2; exit 1; \
    fi

# Pick the runnable jar: the biggest one that is not a plain/sources/javadoc/original jar.
RUN set -eu; \
    jar="$(find "${MODULE:-.}" -maxdepth 6 \( -path '*/target/*.jar' -o -path '*/build/libs/*.jar' \) \
          ! -name '*-plain.jar' ! -name 'original-*.jar' ! -name '*-sources.jar' \
          ! -name '*-javadoc.jar' ! -name '*-tests.jar' \
          -printf '%s %p\n' | sort -rn | head -n 1 | cut -d' ' -f2-)"; \
    [ -n "$jar" ] || { echo "No runnable jar found after the build" >&2; exit 1; }; \
    echo "Using $jar"; \
    mkdir -p /out && cp "$jar" /out/app.jar

# ---------- Stage 2: run -----------------------------------------------------
FROM ${RUNTIME_IMAGE} AS runtime
WORKDIR /app

# Numeric uid so Kubernetes can enforce runAsNonRoot. Works on Alpine and on Debian/Ubuntu.
RUN (addgroup -S -g 10001 app && adduser -S -u 10001 -G app app) 2>/dev/null \
    || (groupadd -r -g 10001 app && useradd -r -u 10001 -g app app)

COPY --from=build --chown=10001:10001 /out/app.jar /app/app.jar

ARG APP_PORT=8080
# MaxRAMPercentage sizes the heap from the container's memory limit, not the host's.
ENV SERVER_PORT=${APP_PORT} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE ${APP_PORT}

USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
