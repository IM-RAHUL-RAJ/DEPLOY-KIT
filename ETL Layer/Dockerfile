# ==============================================================================
# PYTHON WORKER  -  a long-running Python script (ETL, poller, consumer...).
# Build context = this folder. No web port: it talks to the database / Kafka.
#
# Settings come from ENVIRONMENT VARIABLES at run time (compose / Kubernetes).
#
# WHAT TO CHANGE PER PROJECT (--build-arg, no editing needed):
#   PYTHON_VERSION   Python minor version                                (3.12)
#   START_CMD        the command that starts the worker                  ("python main.py")
#   WRITABLE_DIRS    folders the worker writes to (data files, lock files).
#                    /app is always writable; the DuckDB volume mounts at /data. ("/data")
#   NATIVE_BUILD_DEPS "true" adds gcc/libpq-dev for packages that must compile ("false")
#
# Needs a requirements.txt. For poetry/pipenv projects export one first.
# ==============================================================================
ARG PYTHON_VERSION=3.12

FROM python:${PYTHON_VERSION}-slim AS build
ARG NATIVE_BUILD_DEPS=false
RUN if [ "$NATIVE_BUILD_DEPS" = "true" ]; then \
      apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev && rm -rf /var/lib/apt/lists/*; fi
COPY requirements.txt /tmp/requirements.txt
RUN python -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir -r /tmp/requirements.txt

FROM python:${PYTHON_VERSION}-slim AS runtime
ENV PATH="/opt/venv/bin:$PATH" PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
ARG NATIVE_BUILD_DEPS=false
RUN if [ "$NATIVE_BUILD_DEPS" = "true" ]; then \
      apt-get update && apt-get install -y --no-install-recommends libpq5 && rm -rf /var/lib/apt/lists/*; fi
RUN groupadd -r -g 10001 app && useradd -r -u 10001 -g app app
WORKDIR /app
COPY --from=build /opt/venv /opt/venv
COPY --chown=10001:10001 . /app
ARG WRITABLE_DIRS="/data"
RUN chown 10001:10001 /app && for d in $WRITABLE_DIRS; do mkdir -p "$d" && chown -R 10001:10001 "$d"; done
ARG START_CMD="python main.py"
ENV START_CMD=${START_CMD}
USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec $START_CMD"]
