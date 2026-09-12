FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG ANDROID_CMDLINE_TOOLS_REV=15859902
ARG ANDROID_CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG GRADLE_VERSION=9.6.0
ARG GRADLE_SHA256=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV GRADLE_USER_HOME=/opt/gradle-home
ENV PATH=/opt/gradle/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip zip git python3 python3-pip \
    openjdk-17-jdk-headless build-essential flex bison make pkg-config \
    gcc-mingw-w64-x86-64 libvulkan-dev cmake ninja-build \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_REV}_latest.zip" -o /tmp/android-tools.zip; \
    echo "${ANDROID_CMDLINE_TOOLS_SHA256}  /tmp/android-tools.zip" | sha256sum -c -; \
    mkdir -p /opt/android-sdk/cmdline-tools; \
    unzip -q /tmp/android-tools.zip -d /tmp/android-tools; \
    mv /tmp/android-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest; \
    rm -rf /tmp/android-tools /tmp/android-tools.zip; \
    yes | sdkmanager --licenses >/dev/null || true; \
    sdkmanager \
      "platform-tools" \
      "platforms;android-37.0" \
      "build-tools;36.0.0" \
      "ndk;29.0.14206865" \
      "cmake;3.22.1"

RUN set -eux; \
    curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip; \
    echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c -; \
    unzip -q /tmp/gradle.zip -d /opt; \
    mv "/opt/gradle-${GRADLE_VERSION}" /opt/gradle; \
    rm /tmp/gradle.zip; \
    gradle --version

WORKDIR /workspace
COPY . /workspace

RUN set -eux; \
    chmod +x scripts/render-paired-v52-builder.sh; \
    POCKETPC_RENDER_OUTPUT_DIR=/artifacts \
    POCKETPC_RENDER_BUILD_UNSIGNED_DEBUG=1 \
      scripts/render-paired-v52-builder.sh

COPY infra/render/serve_artifacts.py /serve_artifacts.py
ENV PORT=10000
EXPOSE 10000
CMD ["python3", "/serve_artifacts.py"]
