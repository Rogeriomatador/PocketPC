#!/usr/bin/env bash
set -euo pipefail

echo "POCKETPC_RENDER_BUILDER_BEGIN"
ROOT="$(pwd)"
TOOLS="$ROOT/.render-toolchain"
ANDROID_HOME="$TOOLS/android-sdk"
GRADLE_HOME="$TOOLS/gradle-9.6.0"
JDK_HOME="$TOOLS/jdk17"
mkdir -p "$TOOLS" "$ANDROID_HOME"

export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$ROOT/.gradle-render"
export POCKETPC_SOURCE_REVISION="${RENDER_GIT_COMMIT:-LOCAL_UNPINNED}"
export JAVA_TOOL_OPTIONS="-Xmx384m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8"

if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | head -n 1 | grep -q '"17'; then
  if [ ! -x "$JDK_HOME/bin/java" ]; then
    echo "POCKETPC_RENDER_DOWNLOAD_JDK17"
    curl -fsSL --retry 4 --retry-delay 2 \
      "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" \
      -o "$TOOLS/jdk17.tar.gz"
    mkdir -p "$JDK_HOME"
    tar -xzf "$TOOLS/jdk17.tar.gz" --strip-components=1 -C "$JDK_HOME"
  fi
  export JAVA_HOME="$JDK_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version

CMDLINE="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$CMDLINE" ]; then
  echo "POCKETPC_RENDER_DOWNLOAD_ANDROID_CLI"
  curl -fsSL --retry 4 --retry-delay 2 \
    "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" \
    -o "$TOOLS/android-cli.zip"
  rm -rf "$ANDROID_HOME/cmdline-tools"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q "$TOOLS/android-cli.zip" -d "$TOOLS/android-cli-unpacked"
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  cp -a "$TOOLS/android-cli-unpacked/cmdline-tools/." "$ANDROID_HOME/cmdline-tools/latest/"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "POCKETPC_RENDER_INSTALL_ANDROID_LOCK"
yes | sdkmanager --licenses >/dev/null || true
sdkmanager \
  "platform-tools" \
  "platforms;android-37.0" \
  "build-tools;36.0.0" \
  "ndk;29.0.14206865" \
  "cmake;3.22.1"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  echo "POCKETPC_RENDER_DOWNLOAD_GRADLE"
  curl -fsSL --retry 4 --retry-delay 2 \
    "https://services.gradle.org/distributions/gradle-9.6.0-bin.zip" \
    -o "$TOOLS/gradle.zip"
  echo "bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01  $TOOLS/gradle.zip" | sha256sum -c -
  unzip -q "$TOOLS/gradle.zip" -d "$TOOLS"
fi
export PATH="$GRADLE_HOME/bin:$PATH"

python3 scripts/verify-android-build-lock.py
python3 scripts/test-python-script-syntax.py
python3 scripts/test-update-feed-policy.py

echo "POCKETPC_RENDER_GRADLE_BEGIN"
gradle --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx384m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8" \
  :app:testDebugUnitTest \
  :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
mkdir -p render-artifacts
cp "$APK" render-artifacts/PocketPC-render-debug.apk
sha256sum render-artifacts/PocketPC-render-debug.apk | tee render-artifacts/PocketPC-render-debug.apk.sha256
python3 - <<'PY'
import json, os
from pathlib import Path
apk=Path("render-artifacts/PocketPC-render-debug.apk")
evidence={
  "schemaVersion":1,
  "sourceRevision":os.environ.get("POCKETPC_SOURCE_REVISION","LOCAL_UNPINNED"),
  "androidDebugBuildExecuted":True,
  "unitTestsExecuted":True,
  "wineV52BuildExecuted":False,
  "runtimeExecuted":False,
  "physicalVisibleFrame":False,
  "robloxExecuted":False,
  "apkBytes":apk.stat().st_size,
}
Path("render-artifacts/render-build-evidence.json").write_text(json.dumps(evidence,indent=2)+"\n")
PY
echo "POCKETPC_RENDER_ANDROID_BUILD_OK"
