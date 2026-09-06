# Roadmap

## 0.1.0-alpha6 — execution foundation

Implemented source:

- Alpha 5 safe rootfs data installation;
- structured bind policy;
- minimal environment;
- PRoot argv planner;
- PROOT_LOADER packaging alias contract;
- read-only bind fail-closed blocker;
- one-shot process supervisor;
- executor explicitly disabled;
- PRoot/libandroid-shmem/libtalloc source metadata lock;
- independent source archive audit script/workflow definition.

## Supply-chain gate

Pinned recipe authority:
termux/termux-packages@32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68

Pinned baseline:

- PRoot 5.1.107.92
- libandroid-shmem 0.7
- libtalloc 2.4.3

Next evidence step: independently download/recalculate all three archive hashes with scripts/audit-proot-sources.py.

## Next — substrate build audit

- document exact ARM64 PRoot build adaptation;
- package proot as libproot.so;
- package upstream loader as libproot_loader.so;
- record produced artifact SHA-256;
- dynamic dependency/linker test;
- full GPL/BSD redistribution compliance;
- keep executor disabled.

## 0.2 — first Linux ARM smoke

Only after all preceding gates:

- resolve guest link semantics;
- enable an allowlisted non-interactive command path;
- invoke guest /bin/sh through reviewed PRoot;
- capture exit/logs;
- deterministic stop/cleanup;
- physical-device evidence.

## 0.2.x — interactive Linux

- PTY;
- terminal resize;
- signals;
- process-tree handling;
- package/bootstrap;
- user storage bridge.

## 0.3 — graphics

- controlled Vulkan renderer;
- presentation timing;
- Linux graphical bridge.

## 0.4 — Windows compatibility

- x86/x64 translation;
- Wine;
- DXVK/VKD3D;
- compatibility database.

## 0.5 — measured performance engine

No optimization claim without reproducible A/B evidence.
