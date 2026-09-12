#!/usr/bin/env bash
set -euo pipefail

ROOT="${POCKETPC_SOURCE_DIR:-$PWD}"
cd "$ROOT"

REVISION="${POCKETPC_SOURCE_REVISION:-${RENDER_GIT_COMMIT:-}}"
if [[ ! "$REVISION" =~ ^[0-9a-f]{40}$ ]]; then
  REVISION="$(git rev-parse HEAD 2>/dev/null || true)"
fi
if [[ ! "$REVISION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "POCKETPC_RENDER_SOURCE_REVISION_NOT_PINNED" >&2
  exit 10
fi
ACTUAL_REVISION="$(git rev-parse HEAD 2>/dev/null || true)"
if [[ "$ACTUAL_REVISION" =~ ^[0-9a-f]{40}$ && "$ACTUAL_REVISION" != "$REVISION" ]]; then
  echo "POCKETPC_RENDER_SOURCE_REVISION_MISMATCH:$ACTUAL_REVISION:$REVISION" >&2
  exit 11
fi

OUT="${POCKETPC_RENDER_OUTPUT_DIR:-/tmp/pocketpc-render-output}"
WINE_WORK="${POCKETPC_RENDER_WINE_WORK:-/tmp/pocketpc-wine-v52}"
SMOKE_WORK="${POCKETPC_RENDER_SMOKE_WORK:-/tmp/pocketpc-v52-smoke}"
rm -rf "$OUT" "$WINE_WORK" "$SMOKE_WORK"
mkdir -p "$OUT"

python3 scripts/test-python-script-syntax.py
python3 scripts/verify-android-build-lock.py
python3 scripts/test-update-feed-policy.py
python3 scripts/test-update-feed-v52-runtime-binding.py
python3 scripts/test-vulkan-continuous-present-v52-policy.py
python3 scripts/test-wine-x86_64-v52-source-integration.py
python3 scripts/test-runtime-v52-present-selection-policy.py
python3 scripts/test-v52-package-revision-binding.py
python3 scripts/test-v52-continuous-present-integration-validator.py

if command -v install-android-toolchain >/dev/null 2>&1; then
  install-android-toolchain toolchains/android-build-lock.json
else
  command -v sdkmanager >/dev/null
  command -v gradle >/dev/null
fi

VERSION_CODE="${POCKETPC_VERSION_CODE:-220000}"
VERSION_NAME="${POCKETPC_VERSION_NAME:-0.1.0-alpha22.render}"
export POCKETPC_SOURCE_REVISION="$REVISION"

if [[ "${POCKETPC_RENDER_BUILD_UNSIGNED_DEBUG:-1}" = "1" ]]; then
  POCKETPC_VERSION_CODE="$VERSION_CODE" \
  POCKETPC_VERSION_NAME="$VERSION_NAME" \
    gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
  APK="app/build/outputs/apk/debug/app-debug.apk"
  test -s "$APK"
  cp "$APK" "$OUT/PocketPC-$VERSION_NAME-debug.apk"
fi

python3 scripts/build-v52-continuous-present-smoke.py --output-dir "$SMOKE_WORK"
python3 scripts/build-wine-x86_64-v52-experimental.py --work "$WINE_WORK"

cp "$WINE_WORK/guest-package.zip" "$OUT/PocketPC-Wine-v52-${REVISION:0:12}.zip"
cp "$WINE_WORK/wine-v52-experimental-build-evidence.json" "$OUT/"
cp "$SMOKE_WORK/pocketpc-v52-continuous-present-smoke.exe" "$OUT/"
cp "$SMOKE_WORK/pocketpc-v52-continuous-present-smoke-build-evidence.json" "$OUT/"

python3 - "$OUT" "$REVISION" <<'PY'
import hashlib,json,sys
from pathlib import Path
out=Path(sys.argv[1]); rev=sys.argv[2]
def sha(p):
    h=hashlib.sha256()
    with p.open("rb") as f:
        for b in iter(lambda:f.read(1024*1024),b""): h.update(b)
    return h.hexdigest()
records=[]
for p in sorted(x for x in out.iterdir() if x.is_file()):
    records.append({"name":p.name,"bytes":p.stat().st_size,"sha256":sha(p)})
e={
 "schemaVersion":1,
 "sourceRevision":rev,
 "builder":"render",
 "sourcePoliciesExecuted":True,
 "apkDebugBuildExecuted":any(r["name"].endswith("-debug.apk") for r in records),
 "wineV52BuildExecuted":any(r["name"].startswith("PocketPC-Wine-v52-") for r in records),
 "runtimeExecuted":False,
 "integrationExecuted":False,
 "physicalVisibleFrame":False,
 "robloxExecuted":False,
 "artifacts":records,
 "classification":{
   "sourcePolicy":"SOFTWARE_TEST",
   "build":"SOFTWARE_BUILD_EXECUTED",
   "runtime":"NOT_EXECUTED",
   "integration":"NOT_EXECUTED",
   "physical":"NOT_EXECUTED",
   "roblox":"NOT_EXECUTED"
 }
}
(out/"render-build-evidence.json").write_text(json.dumps(e,indent=2)+"\n")
print("POCKETPC_RENDER_BUILD_READY")
print("RUNTIME_EXECUTED=0")
print("PHYSICAL_VISIBLE_FRAME=0")
print("ROBLOX_EXECUTED=0")
PY
