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
| 4 | Box64 x86_64 to ARM64 | v0.4.4 source pinned; review-only AArch64 build pipeline, static x86_64 smoke and authenticated display-bridge fixture implemented | Native-host bridge fixture SOFTWARE INTEGRATION TEST PASS; Box64-on-Android execution NOT_EXECUTED |
| 5 | Wine/Win32 | Wine 11.0 source pinned; headless package builder, Win64 smoke fixtures and gated Box64-to-Wine launch planner implemented | Wine-on-Android build/execution NOT_EXECUTED |
| 6 | Windows state | Persistent prefix planner + prefix structural validation + Win64 CreateProcess/pipe IPC smoke implemented | Android Wine prefix/process smoke NOT_EXECUTED |
| 7 | Direct3D to Vulkan | DXVK 3.0.2/vkd3d-proton 3.0.1 source locks, package/deploy/rollback, D3D11 device + swapchain Present smoke implemented | Android D3D/Vulkan execution NOT_EXECUTED |
| 8 | Audio/input/network | Android host capability probe, Winsock/WinMM/Raw Input fixtures and authenticated ARM64↔x86_64 broker protocol implemented | Native broker/framebuffer SOFTWARE INTEGRATION TEST PASS; Android/Win32 event/audio/network round-trip NOT_EXECUTED |
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


## Guest-tool package chain

Box64/Wine are kept outside the immutable Ubuntu Base rootfs.

The implemented chain is:

1. pinned source identity;
2. review build outside app/src;
3. deterministic guest-tool ZIP;
4. manifest with architecture, guest root, entrypoint, source commit, file size and SHA-256;
5. trust policy matching the exact pinned version/commit/license;
6. bounded ZIP staging with traversal/duplicate/size rejection;
7. full package attestation;
8. transactional install under app-private noBackup storage;
9. second attestation after copy;
10. re-attestation before a SYSTEM bind is exposed under /opt/pocketpc/<tool>;
11. explicit user-approved PRoot diagnostic execution.

A modified installed tool therefore fails closed on a subsequent overlay plan.

This chain is IMPLEMENTED in source. Package build, Android import/install, guest bind and Box64 execution remain NOT_EXECUTED until valid runner/device evidence exists.


## Native display-bridge software evidence

The display bridge has a host-only/native software integration fixture that is
separate from Android/Box64 evidence.

Observed in the assistant Linux x86_64 environment:

- strict C11 compile with `-Wall -Wextra -Werror -Wpedantic`: PASS after fixing
  an `O_CLOEXEC` feature-macro issue;
- authenticated abstract AF_UNIX HELLO/ACK: PASS;
- guest window create + geometry lifecycle: PASS;
- host-provided 64x64 BGRA8888 shared framebuffer: PASS;
- deterministic guest pixel write + host pixel-by-pixel validation: PASS;
- host→guest pointer and keyboard messages: PASS;
- frame-ready / frame-presented acknowledgement: PASS;
- window destroy + clean guest exit: PASS.

The versioned reproducer is
`scripts/test-display-bridge-native-integration.py`.

Evidence class: **SOFTWARE INTEGRATION TEST (native Linux x86_64 fixture)**.

This does **not** prove PRoot, Box64, Wine, Vulkan, Android Surface,
AHardwareBuffer or Roblox execution on a physical Android device.
