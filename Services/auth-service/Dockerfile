# ==============================================================================
# NODE SERVICE  -  npm project, with or without a build step (NestJS, plain
# TypeScript, Express...). Build context = this folder. Needs BuildKit.
#
# Everything the app needs at run time (ports, DB, JWT, CORS...) comes from
# ENVIRONMENT VARIABLES set by docker-compose / Kubernetes, never from this file.
#
# WHAT TO CHANGE PER PROJECT (all are --build-arg, no editing needed):
#   NODE_VERSION        Node major version                                      (20)
#   NEEDS_BUILD         "true" runs `npm run <BUILD_SCRIPT>` (TypeScript/Nest);
#                       "false" for plain JavaScript that runs as it is          (true)
#   BUILD_SCRIPT        npm script that compiles the project                    (build)
#   START_CMD           the command that starts the app                         (node dist/main.js)
#                       plain JS: "node server.js"  or  "npm start"
#   APP_PORT            informational (EXPOSE); the real port is the app's own
#                       setting, passed as env at run time                       (3000)
#   WRITABLE_DIRS       space-separated folders the app writes to at run time
#                       (uploads, generated keys...). Created and made writable
#                       for the non-root user.                                   ("")
#   NATIVE_BUILD_DEPS   "true" installs python3/make/g++ for packages that
#                       compile native code (rare; most ship prebuilt binaries)  (false)
#
# Only npm is supported (package-lock.json). For yarn/pnpm change the two install lines.
# ==============================================================================

ARG NODE_VERSION=20

# ---------- Stage 1: install everything, build, then drop dev dependencies ----
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app

ARG NATIVE_BUILD_DEPS=false
RUN if [ "$NATIVE_BUILD_DEPS" = "true" ]; then apk add --no-cache python3 make g++; fi

COPY package.json package-lock.json* npm-shrinkwrap.json* ./
RUN npm ci --no-fund --no-audit || npm install --no-fund --no-audit

COPY . .
ARG NEEDS_BUILD=true
ARG BUILD_SCRIPT=build
RUN if [ "$NEEDS_BUILD" = "true" ]; then npm run ${BUILD_SCRIPT}; fi

# Leaves only production dependencies in node_modules.
RUN npm prune --omit=dev --no-fund --no-audit

# ---------- Stage 2: run ----------------------------------------------------
FROM node:${NODE_VERSION}-alpine AS runtime
ENV NODE_ENV=production
WORKDIR /app

# uid/gid 1000 is the image's built-in `node` user. A NUMERIC user lets Kubernetes
# enforce runAsNonRoot (it cannot verify a user given by name).
COPY --from=build --chown=1000:1000 /app /app

ARG WRITABLE_DIRS=""
RUN for d in $WRITABLE_DIRS; do mkdir -p "$d" && chown -R 1000:1000 "$d"; done

ARG APP_PORT=3000
ENV PORT=${APP_PORT}
EXPOSE ${APP_PORT}

USER 1000:1000

ARG START_CMD="node dist/main.js"
ENV START_CMD=${START_CMD}
# `exec` so the app is PID 1 and receives SIGTERM (clean shutdown on `docker stop` / pod delete).
ENTRYPOINT ["sh", "-c", "exec $START_CMD"]
