# Roadmap

## V0.1-A — Desktop proof
Compile/device-test the current shell.

## V0.1-B — Real files + terminal
- Storage Access Framework file browser.
- PTY-backed local shell.
- keyboard shortcuts and pointer polish.

## V0.2 — Linux ARM runtime
- rootfs manager;
- isolated userspace process supervisor;
- package bootstrap;
- terminal integration;
- benchmark harness.

## V0.3 — Accelerated Linux graphics
- Vulkan capability probe;
- graphics bridge prototype;
- window presentation path;
- frame-time telemetry.

## V0.4 — Windows compatibility research
- Box64/x86-64 translation feasibility;
- Wine bootstrap;
- DXVK/VKD3D compatibility matrix;
- legal/license audit for redistribution.

## V0.5 — Performance engine
- per-app profiles;
- frame pacing controller;
- shader/pipeline cache policy;
- thermal governor;
- dynamic resolution experiments.

## V1.0 gate
A reproducible benchmark suite must prove the project improves at least one meaningful metric (compatibility, frame-time stability, sustained performance, power, usability) versus an established baseline, without fabricated results.
