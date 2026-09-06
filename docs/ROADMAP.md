# PocketPC roadmap

## V0.1-A — Desktop proof

**Source status: IMPLEMENTED**

- desktop shell;
- taskbar/start menu;
- movable/minimizable/maximizable windows;
- app-level telemetry.

Validation remains separate from implementation.

## V0.1-B — Files + executable sandbox terminal

**Source status: IMPLEMENTED on work branch**

- Storage Access Framework tree picker;
- persisted user-granted access;
- directory navigation;
- external file opening;
- sandbox `/system/bin/sh` command execution;
- working-directory state;
- timeout and bounded output;
- desktop controller JVM tests.

**Gate:** compile + device-test before promotion.

## V0.2 — Linux ARM runtime

- choose and document rootfs distribution strategy;
- rootfs integrity manifest;
- userspace isolation model;
- process supervisor;
- PTY implementation;
- package bootstrap;
- terminal integration;
- benchmark harness.

Do not label Linux as implemented until a rootfs actually boots inside PocketPC.

## V0.3 — Accelerated Linux graphics

- Vulkan capability probe;
- backend/capability matrix;
- graphics presentation bridge;
- frame-time telemetry;
- buffer-copy accounting;
- renderer smoke tests.

## V0.4 — Windows compatibility research

- Box64/x86-64 translation feasibility;
- Wine bootstrap;
- DXVK/VKD3D compatibility matrix;
- upstream licensing/redistribution audit;
- per-title compatibility records.

## V0.5 — Performance engine

- per-app profiles;
- frame pacing controller;
- shader/pipeline cache policy;
- thermal governor;
- memory-pressure response;
- dynamic-resolution experiments;
- sustained-performance test mode.

## V1.0 gate

A reproducible benchmark suite must demonstrate at least one meaningful improvement versus an established baseline: compatibility, frame-time stability, sustained performance, power use or desktop usability.

Peak FPS alone is insufficient.
