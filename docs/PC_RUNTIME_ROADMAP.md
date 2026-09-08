# PocketPC PC Runtime Roadmap

This document is a planning/status map, not execution evidence.

## Evidence rule

Never convert NOT_EXECUTED into PASS.

Documentation, source locks, build recipes, authored tests and candidate artifacts
do not prove Android execution.

## Target

Windows x64 applications on Android ARM64 through a Linux userspace runtime,
without bypassing application or platform protections.

## Nine runtime gates

| # | Gate | Implementation status | Execution evidence |
|---|---|---|---|
| 1 | Native ARM64 host | Implemented native host + Vulkan probe | Current Alpha22 physical validation pending |
| 2 | PRoot/Linux substrate | ARM64 build recipe, ELF audit, approval/attestation and APK staging bridge implemented | Android PRoot execution NOT_EXECUTED |
| 3 | ARM64 rootfs | Ubuntu Base 24.04.4 source/hash pinned; package preparation, safe extraction, link and entrypoint gates implemented | Guest /bin/sh on current Alpha22 NOT_EXECUTED |
| 4 | Box64 x86_64 to ARM64 | v0.4.4 source pinned; review-only AArch64 build pipeline implemented | Build workflow currently not starting; guest Box64 NOT_EXECUTED |
| 5 | Wine/Win32 | Wine 11.0 source pinned; source verification/build plan and gated Box64-to-Wine launch planner implemented | Wine build and Win64 loader NOT_EXECUTED |
| 6 | Windows state | Safe persistent prefix layout planner implemented for /home/pocket/windows-prefixes | Prefix creation/registry/process backend NOT_EXECUTED |
| 7 | Direct3D to Vulkan | DXVK 3.0.2 and vkd3d-proton 3.0.1 exact sources pinned | Build/integration/render smoke tests NOT_EXECUTED |
| 8 | Audio/input/network | Android host capability probe implemented for audio outputs, network, keyboard, mouse and gamepad | Win32 bridge NOT_IMPLEMENTED / NOT_EXECUTED |
| 9 | Roblox Desktop | Target recognition and fail-closed compatibility gate exist | Roblox launch NOT_EXECUTED / compatibility UNKNOWN |

## Critical path

1. Restore an executing CI runner or reproduce the same builds locally.
2. Produce and review the exact PRoot APK staging candidate.
3. Complete source/license/device approval and attest packaged PRoot files.
4. Prepare/install the pinned Ubuntu ARM64 rootfs.
5. Execute and capture the first /bin/sh probe through PRoot.
6. Build Box64 v0.4.4 and install it as a separately attested guest tool.
7. Execute a harmless x86_64 Linux smoke binary through Box64.
8. Build/stage Wine 11.0 and create a persistent Win64 prefix.
9. Execute a minimal Win64 smoke application.
10. Connect Vulkan presentation into the guest and integrate DXVK/vkd3d-proton.
11. Bridge audio/input/network and child-process behavior.
12. Attempt Roblox Desktop without bypassing protections; record startup, graphics,
    input, network, stability and performance evidence.

## Current external blocker

GitHub Actions jobs on the current PR are being created but terminate in a few
seconds with no workflow steps exposed. Until a runner actually starts, those
runs are infrastructure failures and cannot validate or invalidate the new
build/runtime code.
