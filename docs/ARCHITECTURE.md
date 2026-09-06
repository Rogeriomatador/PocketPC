# PocketPC architecture — draft 0.5

## Layer 0 — Android host [IMPLEMENTED baseline]

Lifecycle, input, displays, SAF, thermal/memory APIs, NDK packaging and Vulkan access.

## Layer 1 — Desktop shell [IMPLEMENTED]

Compose desktop, taskbar, start menu, windows and Files/Terminal/Runtimes/System/Performance surfaces.

## Layer 2 — Host services [IMPLEMENTED baseline]

- StorageRepository.
- LocalShellEngine.
- TelemetryMonitor.
- SystemSnapshot.
- PerformanceGovernor.
- NativeRuntimeHost.
- native Vulkan capability probe.

## Layer 3 — Runtime package pipeline [IMPLEMENTED source]

~~~text
manifest + archive
       ↓
schema/ABI/path validation
       ↓
streamed size + SHA-256
       ↓
STAGED_VERIFIED
       ↓
strict TAR/TAR.GZ extractor
       ↓
INSTALLED_DATA
~~~

Guest links remain metadata.

## Layer 4 — Execution substrate [DESIGN]

Expected shape:

~~~text
nativeLibraryDir
├── packaged PocketPC host
└── future reviewed PRoot/loader components
             │
             ▼
INSTALLED_DATA rootfs
             │
             ▼
supervised Linux process
~~~

ExecutionSubstrateProbe and RuntimeLaunchPlanner exist, but the executor does not.

## Layer 5 — Linux runtime services [DESIGN]

- guest link semantics;
- bind mapping;
- environment construction;
- process supervision;
- PTY;
- logs/crash cleanup;
- package/bootstrap behavior.

## Layer 6 — Graphics bridge [DESIGN after G0 source]

- native Vulkan capability probe exists;
- controlled Vulkan renderer next;
- timing/presentation;
- Linux graphical bridge;
- DXVK/VKD3D only after Windows compatibility substrate.

## Layer 7 — Performance engine [PARTIAL]

Advisory thermal/memory governor exists. Active rendering/runtime policy is blocked until a controlled workload exists.
