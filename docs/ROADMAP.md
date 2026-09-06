# Roadmap

## 0.1.0-alpha5 — safe rootfs data install

- schema-v2 runtime manifests;
- tar/tar.gz declared format;
- strict bounded TAR extraction;
- guest links stored as metadata;
- transactional INSTALLED_DATA state;
- execution-substrate probe;
- structured launch blockers;
- CI rejection of unreviewed PRoot libraries;
- malicious archive test sources.

Gate: build + physical device verification.

## 0.1.x — execution substrate preparation

- source/build/license audit for PRoot and dependencies;
- guest link semantics design;
- PRoot argument model without shell-string concatenation;
- bind-mount allowlist;
- process supervisor and bounded logs;
- PTY design;
- runtime diagnostics export.

## 0.2 — first Linux ARM shell

- reviewed substrate packaged in APK;
- supervised PRoot invocation;
- /bin/sh proof;
- runtime home;
- stop/restart;
- basic package/bootstrap research.

## 0.3 — accelerated Linux graphics

- controlled Vulkan renderer;
- swapchain/presentation timing;
- Linux graphical workload bridge;
- frame-time measurements.

## 0.4 — Windows compatibility

- x86/x64 translation;
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

## 1.0

Must demonstrate a reproducible useful advantage without fabricated performance claims.
