#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
DOCKER=ROOT/"infra/render/Dockerfile"
INSTALL=ROOT/"infra/render/install-android-toolchain.sh"
BUILD=ROOT/"scripts/render-paired-v52-builder.sh"

def req(source,needle,label):
    if needle not in source:
        raise SystemExit(f"RENDER_BUILDER_POLICY_MISSING:{label}:{needle}")

def main():
    docker=DOCKER.read_text()
    install=INSTALL.read_text()
    build=BUILD.read_text()
    for needle,label in (
        ("openjdk-17-jdk-headless","jdk17"),
        ("gcc-mingw-w64-x86-64","mingw"),
        ("libvulkan-dev","vulkan"),
        ("install-android-toolchain","android-toolchain"),
    ): req(docker,needle,label)
    for needle,label in (
        ("android-build-lock.json","lock-input"),
        ("distributionSha256","gradle-sha-lock"),
        ("sdkmanager","sdk-manager"),
    ): req(install,needle,label)
    for needle,label in (
        ("POCKETPC_RENDER_SOURCE_REVISION_NOT_PINNED","revision-pin"),
        ("test-update-feed-policy.py","source-policy"),
        (":app:testDebugUnitTest","unit-tests"),
        (":app:assembleDebug","debug-apk"),
        ("build-v52-continuous-present-smoke.py","v52-smoke"),
        ("build-wine-x86_64-v52-experimental.py","wine-v52"),
        ('"runtimeExecuted":False',"runtime-fail-closed"),
        ('"physicalVisibleFrame":False',"physical-fail-closed"),
        ('"robloxExecuted":False',"roblox-fail-closed"),
    ): req(build,needle,label)
    print("POCKETPC_RENDER_BUILDER_POLICY_OK")
    print("RUNTIME_EXECUTED=0")
    print("PHYSICAL_VISIBLE_FRAME=0")
    print("ROBLOX_EXECUTED=0")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
