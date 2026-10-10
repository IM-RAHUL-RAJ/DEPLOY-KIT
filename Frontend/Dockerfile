# ==============================================================================
# FRONTEND  -  Angular (or any npm-built single-page app) served by nginx.
#
# ONE image for every environment. The API addresses are NOT fixed at build time:
#   * the build bakes in harmless PLACEHOLDER addresses (PLACEHOLDER_ENV below);
#   * when the container starts, the entrypoint swaps each placeholder for the real
#     address taken from an environment variable of the same name.
# So the same image runs on EC2, on EKS behind 4 load balancers, behind an ingress,
# or is unpacked into S3. Changing an address = change the env var, restart. No rebuild.
#
# Needs BuildKit (Docker 23+ with the buildx plugin). Build context = this folder.
#
# WHAT TO CHANGE PER PROJECT (all are --build-arg, no editing needed):
#   NODE_VERSION     Node used to build. Angular 21 needs 22; Angular 16 needs 18.   (22)
#   BUILD_SCRIPT     the npm script that makes the production bundle                (build)
#   DIST_DIR         folder holding index.html after the build; "" = auto-detect    ("")
#   PLACEHOLDER_ENV  space-separated NAME=placeholder pairs. NAME is the env var the
#                    project's build reads for an API address (team3: sync-config.mjs
#                    reads AUTH_SERVICE_URL and ORDER_SERVICE_URL). The placeholder
#                    must be a VALID absolute URL, because bundles often run
#                    new URL(...) on it. At run time set the same NAME to the real URL.
#
#   A project whose environment.ts hard-codes e.g. http://localhost:3000 and reads
#   no env var: set PLACEHOLDER_ENV="" and instead edit environment.ts to read
#   window.__APP_CONFIG__ (this image writes /app-config.js for exactly that), or
#   use the literal as the "placeholder": PLACEHOLDER_ENV="API=http://localhost:3000".
#
# Server-side-rendered Angular (SSR) is not supported by this image.
# ==============================================================================

ARG NODE_VERSION=22
ARG PLACEHOLDER_ENV="AUTH_SERVICE_URL=http://auth-url.placeholder.invalid ORDER_SERVICE_URL=http://order-url.placeholder.invalid"

# ---------- Stage 1: build the static bundle ---------------------------------
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app

# Dependencies first, so this layer is reused until package*.json changes.
COPY package.json package-lock.json* ./
RUN npm ci --no-fund --no-audit || npm install --no-fund --no-audit

COPY . .

ARG PLACEHOLDER_ENV
ARG BUILD_SCRIPT=build
ARG DIST_DIR=""
# `export` makes each NAME=placeholder visible to the build (and to npm pre-scripts
# such as team3's `prebuild`, which writes src/environments/*.ts from them).
RUN export $PLACEHOLDER_ENV && npm run ${BUILD_SCRIPT}

# Find the folder that holds index.html (Angular 17+: dist/<app>/browser, older: dist/<app>).
RUN set -eu; \
    if [ -n "$DIST_DIR" ]; then idx="$DIST_DIR/index.html"; \
    else \
      idx="$(find dist -name index.html -path '*/browser/*' | head -n 1)"; \
      [ -n "$idx" ] || idx="$(find dist -name index.html | head -n 1)"; \
    fi; \
    [ -f "$idx" ] || { echo "No index.html found under dist/. Set --build-arg DIST_DIR=..." >&2; exit 1; }; \
    mkdir -p /site && cp -r "$(dirname "$idx")"/. /site/

# ---------- Stage 2: nginx serves the files ----------------------------------
FROM nginx:1.27-alpine AS runtime

ARG PLACEHOLDER_ENV
ENV PLACEHOLDER_ENV="${PLACEHOLDER_ENV}"

# The untouched build is kept aside; the entrypoint copies it over the web root on every
# start and then rewrites the placeholders. Restarting a container is therefore safe.
COPY --from=build /site /opt/site-pristine

COPY <<'NGINX' /etc/nginx/conf.d/default.conf
server {
  listen 80;
  server_name _;
  root /usr/share/nginx/html;
  index index.html;

  gzip on;
  gzip_min_length 1024;
  gzip_types text/css application/javascript application/json image/svg+xml;

  # Single-page app: unknown paths (/blotter, /admin/...) get index.html and the app routes them.
  location / {
    try_files $uri $uri/ /index.html;
  }

  # Code and markup are rewritten at start-up (addresses), so browsers must revalidate.
  # A missing bundle is a real 404, never index.html.
  location ~* \.(?:js|mjs|css|html|json|webmanifest)$ {
    add_header Cache-Control "no-cache";
    try_files $uri =404;
  }

  # Static assets that never change.
  location ~* \.(?:png|jpe?g|gif|svg|ico|webp|woff2?|ttf)$ {
    add_header Cache-Control "public, max-age=2592000";
    try_files $uri =404;
  }
}
NGINX

# nginx's official image runs every executable /docker-entrypoint.d/*.sh before starting.
COPY --chmod=755 <<'SCRIPT' /docker-entrypoint.d/40-runtime-urls.sh
#!/bin/sh
# Puts the real API addresses into the page. Runs on every container start.
set -eu
HTML_ROOT="${HTML_ROOT:-/usr/share/nginx/html}"
PRISTINE_ROOT="${PRISTINE_ROOT:-/opt/site-pristine}"

# Always start from the untouched build, so a changed address really takes effect.
mkdir -p "$HTML_ROOT"
find "$HTML_ROOT" -mindepth 1 -delete
cp -a "$PRISTINE_ROOT"/. "$HTML_ROOT"/

re()  { printf '%s' "$1" | sed -e 's/[][\.*^$]/\\&/g'; }   # escape for the sed pattern
rep() { printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'; }       # escape for the sed replacement

config_js=""
for pair in ${PLACEHOLDER_ENV:-}; do
  name="${pair%%=*}"
  sentinel="${pair#*=}"
  eval "value=\"\${$name:-}\""
  value="${value%/}"                                          # no trailing slash
  if [ -z "$value" ]; then
    echo "frontend: $name is not set; the page keeps the placeholder $sentinel" >&2
    continue
  fi
  n=0
  for f in $(grep -rlF -- "$sentinel" "$HTML_ROOT" 2>/dev/null || true); do
    sed -i "s|$(re "$sentinel")|$(rep "$value")|g" "$f"
    n=$((n + 1))
  done
  echo "frontend: $name = $value (placeholder replaced in $n file(s))"
  config_js="${config_js}${config_js:+,}\"$name\":\"$value\""
done

# For apps that read window.__APP_CONFIG__ instead (keys = the env var names above).
printf 'window.__APP_CONFIG__ = {%s};\n' "$config_js" > "$HTML_ROOT/app-config.js"
exit 0
SCRIPT

EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --start-period=5s --retries=5 \
    CMD wget -qO /dev/null http://127.0.0.1/ || exit 1
