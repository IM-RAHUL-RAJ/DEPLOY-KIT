# ==============================================================================
# VARIANT: Java service that ALSO runs Python (the ETP executor starts a python
# subprocess). Use instead of Services/<svc>/Dockerfile:
#   docker build -f docker-variants/java-python.Dockerfile -t executor Services/executor-service
# Same build args as the Java Dockerfile. Edit the pip line for your Python deps.
# Runtime is Debian (glibc) so Python wheels work.
# ==============================================================================
ARG JAVA_VERSION=21
ARG MAVEN_VERSION=3.9.9

FROM maven:${MAVEN_VERSION}-eclipse-temurin-${JAVA_VERSION} AS build
WORKDIR /app
COPY . .
ARG MODULE=""
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests ${MODULE:+-pl "$MODULE" -am}
RUN set -eu; jar="$(find "${MODULE:-.}" -maxdepth 6 -path '*/target/*.jar' ! -name '*-plain.jar' ! -name 'original-*.jar' \
      ! -name '*-sources.jar' -printf '%s %p\n' | sort -rn | head -n1 | cut -d' ' -f2-)"; \
    mkdir -p /out && cp "$jar" /out/app.jar

FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip && rm -rf /var/lib/apt/lists/*
# Python dependencies of the subprocess, e.g.:  RUN pip3 install --no-cache-dir requests
RUN groupadd -r -g 10001 app && useradd -r -u 10001 -g app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /out/app.jar /app/app.jar
# If the python scripts live in the repo (e.g. src/main/python), copy them:
# COPY --chown=10001:10001 src/main/python /app/python
ARG APP_PORT=8080
ENV SERVER_PORT=${APP_PORT} JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE ${APP_PORT}
USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
