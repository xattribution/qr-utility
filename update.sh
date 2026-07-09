#!/usr/bin/env bash
#
# QR Utility — update & (re)deploy the self-hosted download site.
#
# What it does:
#   1. pulls the latest source from GitHub
#   2. compiles the APK from that source (inside the Docker image build)
#   3. (re)starts the web container serving the freshly built APK
#
# The only thing this host needs is Docker (with the Compose v2 plugin).
# Everything else — JDK, Android SDK, Gradle — lives inside the build image.
#
# Usage:
#   ./update.sh                # clone/update into ~/qr-utility and deploy
#   QR_WEB_PORT=9000 ./update.sh
#   QR_WEB_DIR=/srv/qr ./update.sh
#
set -euo pipefail

REPO_URL="${QR_REPO_URL:-https://github.com/xattribution/qr-utility.git}"
BRANCH="${QR_BRANCH:-main}"
DIR="${QR_WEB_DIR:-$HOME/qr-utility}"
PORT="${QR_WEB_PORT:-8080}"

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null    || die "git is required."
command -v docker >/dev/null || die "Docker is required — see https://docs.docker.com/engine/install/"
docker compose version >/dev/null 2>&1 || die "The Docker Compose v2 plugin is required (docker compose)."

# 1. get / update the source
if [ -d "$DIR/.git" ]; then
  say "Updating source in $DIR"
  git -C "$DIR" fetch --prune origin "$BRANCH"
  git -C "$DIR" checkout -q "$BRANCH"
  git -C "$DIR" reset --hard "origin/$BRANCH"
else
  say "Cloning $REPO_URL into $DIR"
  git clone --branch "$BRANCH" "$REPO_URL" "$DIR"
fi

cd "$DIR"

# 2 + 3. compile the APK inside the image build, then (re)start the container
BUILD_COMMIT="$(git rev-parse --short HEAD)"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export BUILD_COMMIT BUILD_DATE QR_WEB_PORT="$PORT"

say "Building image (this compiles the APK from source; first run downloads the Android SDK)…"
docker compose build --pull

say "Starting the web container on port $PORT"
docker compose up -d --force-recreate

# tidy up the old dangling image layers from previous builds
docker image prune -f >/dev/null 2>&1 || true

say "Done."
echo
echo "  QR Utility is live at:  http://localhost:${PORT}"
echo "  (from another device:   http://<this-host-ip>:${PORT})"
echo
echo "  Re-run ./update.sh anytime to pull the latest source, recompile, and redeploy."
