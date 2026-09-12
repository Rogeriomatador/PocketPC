#!/usr/bin/env bash
set -euo pipefail

LOCK_FILE="${1:-toolchains/android-build-lock.json}"
test -f "$LOCK_FILE" || { echo "ANDROID_LOCK_MISSING:$LOCK_FILE" >&2; exit 2; }

readarray -t VALUES < <(python3 - "$LOCK_FILE" <<'PY'
import json,sys
lock=json.load(open(sys.argv[1],encoding="utf-8"))
a=lock["android"]
print(lock["jdk"]["major"])
print(lock["gradle"]["version"])
print(a["platformPackage"])
print(a["buildTools"])
print(a["ndk"])
print(a["cmake"])
PY
)
JDK_MAJOR="${VALUES[0]}"
GRADLE_VERSION="${VALUES[1]}"
PLATFORM_PACKAGE="${VALUES[2]}"
BUILD_TOOLS="${VALUES[3]}"
NDK="${VALUES[4]}"
CMAKE="${VALUES[5]}"

test "$JDK_MAJOR" = "17" || { echo "UNSUPPORTED_JDK:$JDK_MAJOR" >&2; exit 3; }

mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" /opt/gradle "$GRADLE_USER_HOME"

if ! command -v sdkmanager >/dev/null 2>&1; then
  python3 - <<'PY'
import re,urllib.request
xml=urllib.request.urlopen("https://dl.google.com/android/repository/repository2-3.xml",timeout=30).read().decode()
# Pick the newest Linux command line tools archive declared by Google's repository metadata.
blocks=re.findall(r'<remotePackage path="cmdline-tools;([^"]+)">(.*?)</remotePackage>',xml,re.S)
candidates=[]
for version,block in blocks:
    m=re.search(r'<archive>.*?<host-os>linux</host-os>.*?<complete>.*?<url>([^<]+)</url>.*?<checksum[^>]*>([0-9a-fA-F]{64})</checksum>',block,re.S)
    if not m: continue
    def key(v):
        return tuple(int(x) if x.isdigit() else -1 for x in re.split(r'[._-]',v))
    candidates.append((key(version),m.group(1),m.group(2).lower()))
if not candidates:
    raise SystemExit("ANDROID_CMDLINE_TOOLS_METADATA_NOT_FOUND")
_,url,sha=max(candidates)
print(url)
print(sha)
open("/tmp/android-cmdline-tools.meta","w").write(url+"\n"+sha+"\n")
PY
  URL="$(sed -n '1p' /tmp/android-cmdline-tools.meta)"
  EXPECTED_SHA="$(sed -n '2p' /tmp/android-cmdline-tools.meta)"
  curl -fsSL "https://dl.google.com/android/repository/$URL" -o /tmp/android-cmdline-tools.zip
  echo "$EXPECTED_SHA  /tmp/android-cmdline-tools.zip" | sha256sum -c -
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mkdir -p /tmp/android-cmdline-tools
  unzip -q /tmp/android-cmdline-tools.zip -d /tmp/android-cmdline-tools
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  cp -a /tmp/android-cmdline-tools/cmdline-tools/. "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
fi

yes | sdkmanager --licenses >/dev/null || true
sdkmanager \
  "platform-tools" \
  "$PLATFORM_PACKAGE" \
  "build-tools;$BUILD_TOOLS" \
  "ndk;$NDK" \
  "cmake;$CMAKE"

if ! command -v gradle >/dev/null 2>&1 || ! gradle --version | grep -q "Gradle $GRADLE_VERSION"; then
  curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o /tmp/gradle.zip
  python3 - "$LOCK_FILE" /tmp/gradle.zip <<'PY'
import hashlib,json,sys
lock=json.load(open(sys.argv[1],encoding="utf-8"))
want=lock["gradle"]["distributionSha256"].lower()
got=hashlib.sha256(open(sys.argv[2],"rb").read()).hexdigest()
if got != want:
    raise SystemExit(f"GRADLE_SHA256_MISMATCH:{got}")
PY
  rm -rf "/opt/gradle/gradle-$GRADLE_VERSION"
  unzip -q /tmp/gradle.zip -d /opt/gradle
  ln -sfn "/opt/gradle/gradle-$GRADLE_VERSION/bin/gradle" /usr/local/bin/gradle
fi

echo "POCKETPC_RENDER_ANDROID_TOOLCHAIN_READY"
echo "JDK=$JDK_MAJOR"
echo "GRADLE=$GRADLE_VERSION"
echo "PLATFORM=$PLATFORM_PACKAGE"
echo "BUILD_TOOLS=$BUILD_TOOLS"
echo "NDK=$NDK"
echo "CMAKE=$CMAKE"
