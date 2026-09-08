#!/data/data/com.termux/files/usr/bin/bash
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/toolchains/android-build-lock.json"

echo "PocketPC Termux on-device preflight"
echo "Classification : DIAGNOSTIC_ONLY_NOT_A_BUILD"
echo

if [ ! -f "$LOCK" ]; then
  echo "LOCK_MISSING=$LOCK"
  exit 2
fi

read_lock() {
  python - "$LOCK" "$1" <<'PY'
import json, sys
path, key = sys.argv[1], sys.argv[2]
data = json.load(open(path, "r", encoding="utf-8"))
cur = data
for part in key.split("."):
    cur = cur[part]
print(cur)
PY
}

ARCH="$(uname -m 2>/dev/null || echo unknown)"
API="$(getprop ro.build.version.sdk 2>/dev/null || echo unknown)"
MODEL="$(getprop ro.product.model 2>/dev/null || echo unknown)"
ABI="$(getprop ro.product.cpu.abi 2>/dev/null || echo unknown)"
VERSION_NAME="$(read_lock app.versionName 2>/dev/null || echo unknown)"
VERSION_CODE="$(read_lock app.versionCode 2>/dev/null || echo unknown)"
NDK_VERSION="$(read_lock android.ndk 2>/dev/null || echo unknown)"
BUILD_TOOLS="$(read_lock android.buildTools 2>/dev/null || echo unknown)"
PLATFORM_PACKAGE="$(read_lock android.platformPackage 2>/dev/null || echo unknown)"
GRADLE_REQUIRED="$(read_lock gradle.version 2>/dev/null || echo unknown)"
JDK_REQUIRED="$(read_lock jdk.major 2>/dev/null || echo unknown)"

echo "Device"
echo "  model=$MODEL"
echo "  arch=$ARCH"
echo "  abi=$ABI"
echo "  api=$API"
echo
echo "PocketPC lock"
echo "  version=$VERSION_NAME"
echo "  version_code=$VERSION_CODE"
echo "  gradle=$GRADLE_REQUIRED"
echo "  jdk=$JDK_REQUIRED"
echo "  ndk=$NDK_VERSION"
echo "  build_tools=$BUILD_TOOLS"
echo "  platform=$PLATFORM_PACKAGE"
echo

has() {
  command -v "$1" >/dev/null 2>&1
}

tool_line() {
  local name="$1"
  if has "$name"; then
    local path
    path="$(command -v "$name")"
    echo "  $name=FOUND:$path"
  else
    echo "  $name=MISSING"
  fi
}

PINNED_GRADLE="$HOME/.local/pocketpc/gradle/gradle-$GRADLE_REQUIRED/bin/gradle"
if [ -x "$PINNED_GRADLE" ]; then
  export POCKETPC_GRADLE_HOME="$(dirname "$(dirname "$PINNED_GRADLE")")"
  export PATH="$POCKETPC_GRADLE_HOME/bin:$PATH"
fi

echo "Tooling"
for tool in git python java javac aapt2 gradle cmake ninja clang; do
  tool_line "$tool"
done
if [ -x "$PINNED_GRADLE" ]; then
  echo "  pinned_gradle=FOUND:$PINNED_GRADLE"
else
  echo "  pinned_gradle=MISSING"
fi
echo

JAVA_OK=false
if has java; then
  JAVA_MAJOR="$(
    java -version 2>&1 |
      awk -F'[".]' '/version/ {print $2; exit}'
  )"
  if [ "$JAVA_MAJOR" = "$JDK_REQUIRED" ]; then
    JAVA_OK=true
  fi
  echo "java_major=${JAVA_MAJOR:-unknown}"
  echo "java_matches_lock=$JAVA_OK"
fi

GRADLE_OK=false
if has gradle; then
  GRADLE_VERSION="$(
    gradle --version 2>/dev/null |
      awk '/^Gradle / {print $2; exit}'
  )"
  if [ "$GRADLE_VERSION" = "$GRADLE_REQUIRED" ]; then
    GRADLE_OK=true
  fi
  echo "gradle_version=${GRADLE_VERSION:-unknown}"
  echo "gradle_matches_lock=$GRADLE_OK"
fi

AAPT2_OK=false
if has aapt2; then
  AAPT2_OK=true
fi

ANDROID_HOME_CANDIDATE="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
ANDROID_JAR=""
for candidate in   "$ANDROID_HOME_CANDIDATE/platforms/android-37/android.jar"   "$ANDROID_HOME_CANDIDATE/platforms/android-37.0/android.jar"
do
  if [ -f "$candidate" ]; then
    ANDROID_JAR="$candidate"
    break
  fi
done

echo
echo "Android SDK"
echo "  root=$ANDROID_HOME_CANDIDATE"
if [ -n "$ANDROID_JAR" ]; then
  echo "  android_jar=FOUND:$ANDROID_JAR"
else
  echo "  android_jar=MISSING"
fi

NDK_ROOT="${ANDROID_NDK_HOME:-$ANDROID_HOME_CANDIDATE/ndk/$NDK_VERSION}"
NDK_CLANG=""
if [ -d "$NDK_ROOT/toolchains/llvm/prebuilt" ]; then
  for host in "$NDK_ROOT"/toolchains/llvm/prebuilt/*; do
    if [ -x "$host/bin/clang" ]; then
      NDK_CLANG="$host/bin/clang"
      break
    fi
  done
fi

NATIVE_READY=false
echo
echo "Native build"
echo "  ndk_root=$NDK_ROOT"
if [ -n "$NDK_CLANG" ]; then
  echo "  ndk_clang=$NDK_CLANG"
  if "$NDK_CLANG" --version >/dev/null 2>&1; then
    NATIVE_READY=true
    echo "  ndk_host_executable=true"
  else
    echo "  ndk_host_executable=false"
    if has file; then
      echo "  ndk_clang_file=$(file "$NDK_CLANG" 2>/dev/null || true)"
    fi
  fi
else
  echo "  ndk_clang=MISSING"
fi

STATIC_READY=false
if has git && has python && [ "$JAVA_OK" = true ]; then
  STATIC_READY=true
fi

JAVA_UI_CANDIDATE=false
if [ "$STATIC_READY" = true ] &&
   [ "$AAPT2_OK" = true ] &&
   [ "$GRADLE_OK" = true ] &&
   [ -n "$ANDROID_JAR" ]; then
  JAVA_UI_CANDIDATE=true
fi

FULL_BUILD_READY=false
if [ "$JAVA_UI_CANDIDATE" = true ] &&
   [ "$NATIVE_READY" = true ]; then
  FULL_BUILD_READY=true
fi

echo
echo "Installed PocketPC"
PACKAGE_PATH="$(
  /system/bin/cmd package path dev.pocketpc.core 2>/dev/null |
    head -1 || true
)"
if [ -n "$PACKAGE_PATH" ]; then
  echo "  package_path=$PACKAGE_PATH"
else
  echo "  package_path=UNAVAILABLE_FROM_TERMUX"
fi

SIGNING_KEY="${POCKETPC_SIGNING_KEYSTORE:-}"
echo
echo "Signing"
if [ -n "$SIGNING_KEY" ] && [ -f "$SIGNING_KEY" ]; then
  echo "  keystore=FOUND"
  echo "  in_place_update=UNVERIFIED_SIGNATURE_MATCH"
else
  echo "  keystore=MISSING"
  echo "  in_place_update=BLOCKED_NO_MATCHING_SIGNING_KEY"
fi

echo
echo "Results"
echo "  static_ready=$STATIC_READY"
echo "  java_ui_build_candidate=$JAVA_UI_CANDIDATE"
echo "  native_build_ready=$NATIVE_READY"
echo "  full_build_ready=$FULL_BUILD_READY"

if [ "$FULL_BUILD_READY" = true ]; then
  echo "Classification : TERMUX_FULL_BUILD_CANDIDATE_NOT_EXECUTED"
elif [ "$JAVA_UI_CANDIDATE" = true ]; then
  echo "Classification : TERMUX_JAVA_UI_CANDIDATE_NATIVE_BLOCKED"
elif [ "$STATIC_READY" = true ]; then
  echo "Classification : TERMUX_STATIC_TEST_READY"
else
  echo "Classification : TERMUX_TOOLING_INCOMPLETE"
fi

echo
echo "Important:"
echo "  This script does not build, install, sign, or update PocketPC."
echo "  A generated APK can update the installed app only with a compatible signing identity."
