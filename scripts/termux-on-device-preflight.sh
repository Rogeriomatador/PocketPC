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
COMPILE_SDK="$(read_lock android.compileSdk 2>/dev/null || echo unknown)"
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
echo "  compile_sdk=$COMPILE_SDK"
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
JAVA_LOCK_EXACT=false
if has java; then
  JAVA_MAJOR="$(
    java -version 2>&1 |
      awk -F'[".]' '/version/ {print $2; exit}'
  )"

  if [ "$JAVA_MAJOR" = "$JDK_REQUIRED" ]; then
    JAVA_LOCK_EXACT=true
  fi

  if [ "${JAVA_MAJOR:-0}" -ge "$JDK_REQUIRED" ] 2>/dev/null &&
     [ "${JAVA_MAJOR:-99}" -le 26 ] 2>/dev/null; then
    JAVA_OK=true
  fi

  echo "java_major=${JAVA_MAJOR:-unknown}"
  echo "java_matches_lock=$JAVA_LOCK_EXACT"
  echo "java_meets_build_minimum=$JAVA_OK"
  if [ "$JAVA_OK" = true ] &&
     [ "$JAVA_LOCK_EXACT" != true ]; then
    echo "java_compatibility_variance=TERMUX_NEWER_JDK_THAN_LOCK"
  fi
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
TERMUX_VARIANT="$(bash "$ROOT/scripts/termux-detect-variant.sh" --value 2>/dev/null || printf '%s' classic_or_unknown)"
LOCAL_AAPT2="$HOME/.local/pocketpc/android-build-tools/16.0.0.4/bin/aapt2"
if [ -x "$LOCAL_AAPT2" ]; then
  AAPT2_CANDIDATE="$LOCAL_AAPT2"
  AAPT2_SOURCE="pocketpc_local"
elif has aapt2; then
  AAPT2_CANDIDATE="$(command -v aapt2)"
  AAPT2_SOURCE="termux_system"
else
  AAPT2_CANDIDATE=""
  AAPT2_SOURCE="missing"
fi

ANDROID_HOME_CANDIDATE="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
PLATFORM_DIR_NAME="${PLATFORM_PACKAGE#platforms;}"
PLATFORM_DIR="$ANDROID_HOME_CANDIDATE/platforms/$PLATFORM_DIR_NAME"
ANDROID_JAR="$PLATFORM_DIR/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
  ANDROID_JAR=""
fi

echo
echo "Termux repository"
bash "$ROOT/scripts/termux-repository-check.sh" diagnostic || true

echo
echo "Android SDK"
echo "  root=$ANDROID_HOME_CANDIDATE"
if [ -n "$ANDROID_JAR" ]; then
  echo "  android_jar=FOUND:$ANDROID_JAR"
else
  echo "  android_jar=MISSING"
fi

BUILD_TOOLS_DIR="$ANDROID_HOME_CANDIDATE/build-tools/$BUILD_TOOLS"
BUILD_TOOLS_READY=false
if [ -f "$BUILD_TOOLS_DIR/source.properties" ] &&
   [ -f "$BUILD_TOOLS_DIR/lib/d8.jar" ]; then
  BUILD_TOOLS_READY=true
  echo "  build_tools=FOUND:$BUILD_TOOLS_DIR"
else
  echo "  build_tools=MISSING_OR_INCOMPLETE:$BUILD_TOOLS_DIR"
fi

if [ -n "$AAPT2_CANDIDATE" ]; then
  echo "  aapt2=$AAPT2_CANDIDATE"
  echo "  aapt2_source=$AAPT2_SOURCE"
  echo "  aapt2_version=$("$AAPT2_CANDIDATE" version 2>&1 | head -1)"
  if command -v dpkg-query >/dev/null 2>&1; then
    AAPT2_PACKAGE_VERSION="$(dpkg-query -W -f='${Version}' aapt 2>/dev/null || true)"
    echo "  aapt2_package_version=${AAPT2_PACKAGE_VERSION:-unknown}"
  fi

  if [ -n "$ANDROID_JAR" ]; then
    AAPT2_PROBE_DIR="$(mktemp -d)"
    cat > "$AAPT2_PROBE_DIR/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.pocketpc.preflight">
    <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="$COMPILE_SDK" />
    <application />
</manifest>
EOF

    if "$AAPT2_CANDIDATE" link \
      -o "$AAPT2_PROBE_DIR/probe.apk" \
      -I "$ANDROID_JAR" \
      --manifest "$AAPT2_PROBE_DIR/AndroidManifest.xml" \
      >"$AAPT2_PROBE_DIR/link.log" 2>&1; then
      AAPT2_OK=true
      echo "  aapt2_platform_compatible=true"
    else
      echo "  aapt2_platform_compatible=false"
      AAPT2_PROBE_ERROR="$(head -1 "$AAPT2_PROBE_DIR/link.log" 2>/dev/null || true)"
      echo "  aapt2_platform_probe_error=${AAPT2_PROBE_ERROR:-unknown}"
      if [ "$TERMUX_VARIANT" = "googleplay" ] &&
         [ "$AAPT2_SOURCE" = "termux_system" ]; then
        echo "  aapt2_blocker=GOOGLE_PLAY_SYSTEM_AAPT2_API37"
        echo "  aapt2_recovery=bash scripts/termux-build-modern-aapt2.sh"
      fi
    fi
    rm -rf "$AAPT2_PROBE_DIR"
  else
    echo "  aapt2_platform_compatible=false"
    echo "  aapt2_platform_probe_error=ANDROID_JAR_MISSING"
  fi
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
   [ "$BUILD_TOOLS_READY" = true ] &&
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
