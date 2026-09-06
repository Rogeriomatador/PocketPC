# Graphics pipeline research — Alpha 4

## Current evidence

- Android PackageManager Vulkan feature reporting: **IMPLEMENTED source**
- NDK Vulkan capability probe: **IMPLEMENTED source**
- NDK/CMake/APK build of probe: **NOT CI VALIDATED**
- physical-device Vulkan enumeration: **NOT DEVICE TESTED**
- renderer/swapchain/presentation: **DESIGN**
- Pocket vGPU: **DESIGN**

## Native capability probe

The packaged Runtime Host now contains a read-only Vulkan probe that attempts to report:

- loader/instance API version;
- physical-device count;
- selected non-CPU physical device where available;
- device name/type;
- device Vulkan API version;
- driver version;
- vendor/device IDs;
- queue-family count;
- presence of a graphics-capable queue;
- device-extension count;
- VK_KHR_swapchain support;
- VK_GOOGLE_display_timing when exposed.

The probe creates a minimal Vulkan 1.0 instance for broad compatibility. It does not create a logical device, surface, swapchain, command buffers or rendered frames.

## Why this is not a vGPU yet

Capability enumeration does not translate graphics APIs and does not accelerate anything. The next gates are:

### G0 — probe [source implemented]

Build + device-test the capability probe.

### G1 — controlled Vulkan renderer

Create an Android-owned surface/swapchain, render a deterministic pattern and collect presentation/frame timing.

### G2 — frame pacing experiment

Compare uncapped/basic presentation with a measured pacing policy. No FPS claim without A/B evidence.

### G3 — Linux graphics bridge

Only after Linux execution is real, connect a controlled Linux graphical workload to the Android presentation path.

### G4 — DirectX compatibility

Only after x86/x64 + Wine exists, evaluate DXVK/VKD3D.

## Device selection policy

Software/CPU Vulkan devices must be recognized distinctly from hardware GPU devices. A future performance-sensitive renderer should not silently treat a CPU Vulkan implementation as hardware acceleration.

## Explicit limits

- no arbitrary Android-game injection;
- no fabricated VRAM;
- no RTX-class naming claims;
- no frame-generation claim from ordinary interpolation experiments;
- no performance claim from capability enumeration.
