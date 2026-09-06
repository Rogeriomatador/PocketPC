# PocketPC architecture — draft 0.4

## Layer 0 — Android host

Lifecycle, input, displays, SAF, power/thermal APIs, package capabilities, NDK packaging and future Vulkan access.

## Layer 1 — Desktop shell [IMPLEMENTED]

Compose desktop, taskbar, start menu, windows and Files/Terminal/Runtimes/System/Performance surfaces.

## Layer 2 — Host services [IMPLEMENTED baseline]

- StorageRepository — explicit SAF roots and file launching.
- LocalShellEngine — /system/bin/sh under ordinary app UID.
- TelemetryMonitor — app-scoped UI/process/memory/thermal signals.
- SystemSnapshot — hardware/OS capabilities visible through Android.
- PerformanceGovernor — advisory policy only.
- RuntimePackageManager — verified rootfs staging and inventory.
- NativeRuntimeHost — NDK/JNI executable foundation packaged with APK.

## Layer 3 — Linux execution substrate [DESIGN]

The rootfs is writable **data**. Executable runtime-loader code must follow Android's W^X/package rules.

Research target:

~~~text
verified aarch64 rootfs data
           │
           ▼
APK-packaged Runtime Host / loader
           │
           ▼
supervised Linux userspace
~~~

No direct execve(rootfs/files/...) assumption is allowed.

## Layer 4 — Runtime manager [PARTIAL]

Already owns manifest validation and verified staging. Future responsibilities:

- safe extraction;
- versioned installations;
- rootfs lifecycle;
- process supervision;
- environment;
- storage bridges;
- PTY;
- logs/crash cleanup.

## Layer 5 — Graphics bridge [DESIGN]

- Vulkan capability discovery;
- renderer/backend selection;
- buffers/presentation;
- timing;
- shader/pipeline cache;
- optional scaling;
- future DXVK/VKD3D interop.

It is not a fake high-end GPU.

## Layer 6 — Performance engine [PARTIAL]

Advisory governor exists. Active outputs come only after PocketPC owns a measurable renderer/runtime.
