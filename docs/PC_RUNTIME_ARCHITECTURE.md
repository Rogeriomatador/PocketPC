# PocketPC PC Runtime Architecture

Status: **DESIGN / NOT_IMPLEMENTED**

This document defines the candidate compatibility stack for running legitimate
Windows x86/x64 software on Android ARM64. It is not evidence that `.exe` files,
Roblox, Wine, Box64, DXVK or VKD3D currently execute inside PocketPC.

## Fail-closed rule

PocketPC exposes PC execution only when every required runtime gate has real
evidence. A downloaded Windows installer remains data in `P:\\Downloads` while
any required gate is BLOCKED, NOT_IMPLEMENTED or UNKNOWN.

## Candidate stack

1. **Android ARM64 host**
   - Existing `libpocketpc_runtime.so` remains the native host/probe boundary.
   - Android lifecycle, storage, displays, input, audio and Vulkan capabilities
     stay owned by PocketPC.

2. **Linux userspace / execution substrate**
   - Verified ARM64 rootfs.
   - PRoot/glibc-style userspace is a candidate.
   - Every executable artifact must pass source, SHA-256, ELF, architecture and
     license review before it can become executable.

3. **x86_64 -> ARM64 translation**
   - Primary candidate: Box64 built explicitly for the Android target.
   - No unreviewed prebuilt Box64 binary may be silently downloaded or bundled.

4. **Windows user-mode compatibility**
   - Primary candidate: Wine WoW64.
   - PocketPC needs a controlled prefix, PE/DLL loading, registry, environment,
     child-process lifecycle and IPC integration.

5. **Graphics**
   - D3D9/10/11 candidate: DXVK.
   - D3D12 candidate: VKD3D.
   - The final path must terminate in an Android-supported Vulkan driver.
   - Driver-specific components must be selected from real device capability
     evidence; PocketPC must not pretend every phone uses Adreno/Turnip.

6. **Audio, input and networking**
   - Android audio/input/network bridges need explicit adapters into the
     Windows compatibility environment.
   - Mouse, keyboard, gamepad and touch translation must remain independently
     testable.

7. **Application compatibility**
   - Each Windows app/game gets its own evidence profile.
   - Installer launch, application launch, graphics, input, audio, network,
     stability and performance are separate gates.
   - A general runtime PASS does not imply an individual game PASS.

## Roblox

Roblox desktop compatibility is **UNKNOWN**.

PocketPC will not bypass, patch around, disable or evade anti-cheat or platform
security. If the legitimate Windows client rejects Wine/translation or another
compatibility layer, that is a compatibility blocker rather than something the
project should hide.

## Artifact admission

A third-party runtime component can move from candidate to packaged only after:

- upstream/source identity is pinned;
- license is reviewed;
- source or release artifact SHA-256 is pinned;
- target architecture is inspected;
- executable/library dependencies are enumerated;
- no undeclared network bootstrap is required;
- the artifact passes local software tests;
- the artifact passes physical-device tests;
- update/replacement behavior is defined.

## Milestones

### R1 — ARM64 Linux command gate
Execute one controlled ARM64 binary inside the approved substrate and capture
stdout/stderr/exit code.

### R2 — Box64 probe
Execute a harmless x86_64 Linux probe through an approved Box64 build and verify
architecture translation.

### R3 — Wine probe
Launch a minimal Windows console program through Wine WoW64 and capture a clean
exit code.

### R4 — Graphics probe
Create a minimal translated Direct3D workload and prove the D3D -> Vulkan path
on the physical Android device.

### R5 — Desktop application profile
Install and launch a non-game Windows desktop application in an isolated prefix.

### R6 — Game profiles
Only after the previous gates pass, test individual games. Roblox remains
UNKNOWN until its own legitimate client passes the complete profile.

## Evidence labels

- DESIGN: architecture only.
- IMPLEMENTED: code exists.
- STATICALLY_VALIDATED: source/policy checks only.
- SOFTWARE_TEST: executable software test passed.
- INTEGRATION_TEST: multiple runtime layers passed together.
- PHYSICAL: passed on a real device.
- NOT_EXECUTED: no result exists yet.
