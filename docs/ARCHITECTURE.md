# PocketPC architecture — draft 0.1

## Layer 0 — Android host
Provides lifecycle, input, display, storage permissions, power/thermal APIs and Vulkan access.

## Layer 1 — Desktop Shell [IMPLEMENTED baseline]
Owns desktop composition, taskbar, start menu, window model and adaptive layout.

## Layer 2 — Runtime Manager [PLANNED]
Starts and supervises isolated runtimes. First target: Linux ARM userspace. Later: x86/x64 translation and Wine.

## Layer 3 — Graphics Bridge [PLANNED]
A backend abstraction, not a fake high-end GPU. Intended responsibilities:
- Vulkan capability discovery;
- renderer/backend selection;
- graphics buffer lifecycle;
- frame pacing telemetry;
- shader/pipeline cache policy;
- optional scaling pipeline;
- future DXVK/VKD3D interop where licensing/technical constraints permit.

## Layer 4 — Performance Governor [PLANNED]
Inputs:
- frame time/FPS;
- process CPU time;
- memory pressure;
- thermal headroom/status;
- future GPU timing/counters when exposed by the backend.

Outputs:
- target FPS;
- runtime quality hints;
- resolution scale;
- background work budget;
- cache/prewarming policy.

## Non-goals
- Claiming virtual VRAM creates physical memory.
- Claiming a virtual GPU creates RTX-class compute power.
- Injecting into arbitrary third-party Android games.
- Depending on root for the first app release.
