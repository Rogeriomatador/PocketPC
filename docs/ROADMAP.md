# Roadmap

## 0.1.0-alpha4 — runtime foundation

- Alpha 3 desktop/files/local shell/telemetry;
- Runtimes UI;
- NDK r29 Runtime Host source;
- manifest schema;
- ARM64 compatibility validation;
- SHA-256 + declared-size verification;
- fail-closed rootfs staging;
- path traversal defenses;
- runtime inventory/removal;
- NDK/CMake CI definition.

Gate: build/JNI load on Android.

## 0.1.x — runtime safety

- choose rootfs archive format;
- safe data extraction;
- staging/install transaction journal;
- log export;
- more device UX/pointer/keyboard hardening.

## 0.2 — Linux ARM execution

- Android-compliant executable loader substrate;
- process supervisor;
- environment;
- PTY;
- first verified aarch64 shell;
- package/bootstrap research.

## 0.3 — accelerated Linux graphics

- native Vulkan probe;
- renderer/backend abstraction;
- buffers/presentation;
- first graphical Linux workload;
- frame-time telemetry.

## 0.4 — Windows compatibility

- x86/x64 translation feasibility;
- Wine;
- DXVK/VKD3D;
- licensing/redistribution audit;
- compatibility database.

## 0.5 — measured performance engine

- frame pacing;
- shader/pipeline cache;
- thermal governor connected to owned workload;
- dynamic resolution/upscaling experiments;
- sustained A/B benchmarks.

## 1.0 gate

At least one meaningful advantage must be reproducibly demonstrated without fabricated results.
