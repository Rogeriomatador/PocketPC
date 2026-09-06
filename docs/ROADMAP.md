# Roadmap

## 0.1.0-alpha6 — execution foundation

- Alpha 5 safe rootfs data installation;
- structured bind policy;
- minimal environment;
- PRoot argv planner;
- one-shot process supervisor;
- executor explicitly disabled;
- execution-policy unit-test sources.

## Next — substrate audit

- PRoot source/build/license audit;
- dependency audit;
- reproducible or documented Android build path;
- artifact SHA-256;
- nativeLibraryDir packaging verification.

## 0.2 — first Linux ARM smoke

- resolve guest link semantics;
- enable only a non-interactive, allowlisted command path;
- run /bin/sh -c style guest smoke without host shell composition;
- capture exit/logs;
- deterministic stop/cleanup;
- physical-device evidence.

## 0.2.x — interactive Linux

- PTY;
- terminal resize;
- signals;
- process-tree handling;
- runtime home/package bootstrap.

## 0.3 — graphics

- controlled Vulkan renderer;
- presentation timing;
- Linux graphical bridge.

## 0.4 — Windows compatibility

- translation layer;
- Wine;
- DXVK/VKD3D;
- compatibility database.

## 0.5 — measured performance engine

No optimization claim without reproducible A/B evidence.
