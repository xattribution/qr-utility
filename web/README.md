# QR Utility — self-hosted download site

A tiny web page that explains what **QR Utility** is and lets you download the
Android APK — served from a single Docker container.

The container **compiles the APK from source on your own machine** (inside the
image build) and then serves it. Nothing prebuilt is pulled from GitHub — when
you update, it re-pulls the source, recompiles, and redeploys.

```
┌──────────────────────── docker image ────────────────────────┐
│  stage 1 (builder)          →   stage 2 (runtime)             │
│  JDK 17 + Android SDK 34         nginx:alpine                 │
│  + Gradle → assembleDebug        serves index.html            │
│  → app-debug.apk ────────────►   /download/qr-utility.apk     │
└───────────────────────────────────────────────────────────────┘
```

## Requirements

Just **Docker** with the **Compose v2** plugin. That's it — the JDK, Android
SDK, and Gradle all live inside the build image, so nothing else touches your
host.

- Install Docker Engine: https://docs.docker.com/engine/install/
- The Compose plugin ships with modern Docker; verify with `docker compose version`.

## Quick start

One command — it clones the repo (into `~/qr-utility`), compiles the APK, and
starts the site:

```bash
curl -fsSL https://raw.githubusercontent.com/xattribution/qr-utility/main/update.sh | bash
```

…or, if you've already cloned the repo:

```bash
./update.sh
```

When it finishes, open **http://localhost:8080** (or `http://<host-ip>:8080`
from your phone) and tap **Download APK**.

> The first build downloads the Android SDK + Gradle inside the image, so it
> takes a few minutes. Later rebuilds are cached and much faster.

## Updating

Re-run the same script whenever you want the latest version:

```bash
./update.sh
```

It pulls the newest source from `main`, **recompiles** the APK, and recreates
the container. The web page shows the freshly built version, size, and commit.

## Configuration

Environment variables (all optional):

| Variable       | Default            | Meaning                                  |
|----------------|--------------------|------------------------------------------|
| `QR_WEB_PORT`  | `8080`             | Host port the site listens on            |
| `QR_WEB_DIR`   | `~/qr-utility`     | Where `update.sh` clones/keeps the source|
| `QR_BRANCH`    | `main`             | Branch to build from                     |
| `QR_REPO_URL`  | this repo's URL    | Source repository                        |

Examples:

```bash
QR_WEB_PORT=9000 ./update.sh          # serve on :9000
QR_WEB_DIR=/srv/qr-utility ./update.sh # keep source under /srv
```

## Running it by hand (without update.sh)

```bash
# from the repository root
docker compose build --pull      # compiles the APK from the current source
docker compose up -d             # serve on :8080
docker compose logs -f           # watch it
docker compose down              # stop
```

Pass build metadata if you like:

```bash
BUILD_COMMIT=$(git rev-parse --short HEAD) \
BUILD_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ) \
docker compose build
```

## What gets served

| Path                        | What it is                                   |
|-----------------------------|----------------------------------------------|
| `/`                         | landing page (`index.html`)                  |
| `/download/qr-utility.apk`  | the compiled debug APK (attachment download) |
| `/version.json`             | build metadata the page reads                |

## Notes

- The APK is a **debug** build for personal sideloading — not a Play Store release.
- It requests no `INTERNET` permission; nothing about the app phones home.
- `restart: unless-stopped` keeps the container running across host reboots.
- To free old image layers after several rebuilds: `docker image prune -f`.
