# syntax=docker/dockerfile:1
#
# QR Utility — self-hosted download site.
#
# Stage 1 compiles the Android APK from source on THIS machine (no prebuilt
# binary is pulled from anywhere). Stage 2 is a tiny nginx image that serves
# the freshly compiled APK plus a landing page.
#
# Build context is the repository root (see docker-compose.yml), so the whole
# Android project is available to the builder stage.

# ----------------------------------------------------------------------------
# Stage 1 — build the APK
# ----------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_VERSION=8.7 \
    ANDROID_CMDLINE_TOOLS=commandlinetools-linux-11076708_latest.zip \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Android SDK: command-line tools + the platform/build-tools this app targets.
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
 && curl -fsSL "https://dl.google.com/android/repository/${ANDROID_CMDLINE_TOOLS}" -o /tmp/cmdtools.zip \
 && unzip -q /tmp/cmdtools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
 && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
 && rm /tmp/cmdtools.zip
ENV PATH="${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools"
RUN yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

# Gradle (the project intentionally ships no wrapper).
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
 && unzip -q /tmp/gradle.zip -d /opt \
 && rm /tmp/gradle.zip
ENV PATH="${PATH}:/opt/gradle-${GRADLE_VERSION}/bin"

WORKDIR /src
COPY . /src

# Compile the debug APK and record what we built.
ARG BUILD_COMMIT=unknown
ARG BUILD_DATE=unknown
RUN set -eu \
 && gradle --no-daemon assembleDebug \
 && cp app/build/outputs/apk/debug/app-debug.apk /qr-utility.apk \
 && VNAME="$(sed -n 's/.*versionName[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle | head -n1)" \
 && VCODE="$(sed -n 's/.*versionCode[[:space:]]*\([0-9][0-9]*\).*/\1/p' app/build.gradle | head -n1)" \
 && SIZE="$(stat -c%s /qr-utility.apk)" \
 && printf '{"available":true,"version_name":"%s","version_code":"%s","size_bytes":%s,"commit":"%s","built_at":"%s","download_url":"/download/qr-utility.apk"}\n' \
      "${VNAME:-1.0}" "${VCODE:-1}" "${SIZE}" "${BUILD_COMMIT}" "${BUILD_DATE}" > /version.json

# ----------------------------------------------------------------------------
# Stage 2 — serve it
# ----------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

LABEL org.opencontainers.image.title="qr-utility-web" \
      org.opencontainers.image.description="Self-hosted QR Utility APK download site (compiles the APK from source)" \
      org.opencontainers.image.source="https://github.com/xattribution/qr-utility"

COPY web/nginx.conf /etc/nginx/conf.d/default.conf
COPY web/index.html /usr/share/nginx/html/index.html
COPY --from=builder /qr-utility.apk /usr/share/nginx/html/download/qr-utility.apk
COPY --from=builder /version.json  /usr/share/nginx/html/version.json

EXPOSE 80
# nginx:alpine already provides the correct default CMD/entrypoint.
