# PocketPC architecture — draft 0.3

## Layer 0 — Android host

Lifecycle, input, displays, SAF, power/thermal APIs, package capabilities and future Vulkan access.

## Layer 1 — Desktop shell [IMPLEMENTED]

Compose desktop, taskbar, start menu, windows and app surfaces.

## Layer 2 — Host services [IMPLEMENTED baseline]

- StorageRepository — explicit SAF roots and file launching.
- LocalShellEngine — /system/bin/sh under the ordinary app UID.
- TelemetryMonitor — app-scoped UI/process/memory/thermal signals.
- SystemSnapshot — hardware/OS capabilities visible through Android.
- PerformanceGovernor — advisory policy only.

## Layer 3 — Runtime manager [DESIGN]

Own versioned runtime manifests, rootfs lifecycle, process supervision, environment variables, mounts exposed by the user, logs and crash cleanup.

First target: Linux ARM userspace. Windows compatibility only comes after the Linux/process substrate is measurable and stable.

## Layer 4 — Graphics bridge [DESIGN]

Responsibilities:

- Vulkan capability discovery;
- renderer/backend selection;
- graphics buffer lifecycle;
- presentation;
- timing telemetry;
- shader/pipeline cache policy;
- optional scaling;
- future DXVK/VKD3D interop where technically and legally appropriate.

It is not a fake high-end GPU.

## Layer 5 — Performance engine [PARTIAL]

The advisory governor exists. Future controlled outputs may include target FPS, resolution scale, background-work budget and cache/prewarm policy, but only for workloads PocketPC actually owns.

## Non-goals

- Inject into arbitrary third-party Android games.
- Claim system-wide GPU counters unavailable to the app.
- Depend on root for Alpha releases.
- Emulate x86 when native ARM software can do the same job more efficiently.
