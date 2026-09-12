FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/opt/gradle-home \
    PATH=/opt/android-sdk/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin:/opt/gradle/bin:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip zip git python3 python3-venv python3-pip \
      openjdk-17-jdk-headless build-essential flex bison make pkg-config \
      gcc-mingw-w64-x86-64 libvulkan-dev jq gh \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY infra/render/install-android-toolchain.sh /usr/local/bin/install-android-toolchain
RUN chmod +x /usr/local/bin/install-android-toolchain

COPY scripts/render-paired-v52-builder.sh /usr/local/bin/pocketpc-render-build
RUN chmod +x /usr/local/bin/pocketpc-render-build

CMD ["bash", "-lc", "python3 -m http.server ${PORT:-10000} --bind 0.0.0.0"]
