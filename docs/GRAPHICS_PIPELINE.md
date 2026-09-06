# Graphics pipeline research

Status: **DESIGN** except Android capability reporting.

## Definition of Pocket vGPU

Pocket vGPU is a proposed graphics abstraction/translation layer. It must never imply extra physical GPU compute or VRAM.

Potential responsibilities:

- capability discovery;
- API/backend selection;
- command/resource translation where needed;
- buffer lifecycle;
- shader/pipeline cache policy;
- presentation;
- timing;
- optional scaling;
- cooperation with a future performance governor.

## Staged gates

### G0 — capability probe

Record Android version, SoC/GPU information available to the app, Vulkan feature/version records and required extensions.

### G1 — native Vulkan presentation

Render a controlled test surface and collect timing without Linux/Windows translation.

### G2 — Linux accelerated presentation

Present a graphical Linux workload through a measured accelerated path.

### G3 — DirectX compatibility research

Only after Wine/x86 translation exists, evaluate DXVK/VKD3D compatibility. Record exact versions and per-title failures.

### G4 — optimization experiments

Frame pacing, pipeline caching, scaling and thermal policies. Every claim requires baseline vs candidate measurements.

## Explicit limits

- No arbitrary Android-game injection.
- No RTX-class claims from naming a virtual device.
- Shared RAM described as shared memory, not fabricated dedicated VRAM.
- Frame generation/upscaling must be labelled by what they actually do.
