# Roadmap

## 0.1.0-alpha8 — artifact quarantine & ELF gate

- Alpha 7 guest filesystem semantics;
- exact PRoot source baseline;
- talloc license conflict made explicit;
- AArch64 ELF artifact contract;
- quarantined build script;
- ELF/DT_NEEDED/SONAME auditor;
- deterministic ELF-policy self-test;
- review-only artifact candidate generator;
- manual quarantine workflow;
- prootReady requires explicit artifact approval;
- artifact approval remains false.

## Supply-chain evidence gate

Need:

- PRoot Source Audit PASS;
- real quarantine build PASS;
- ELF report;
- exact artifact hashes;
- talloc source-license determination;
- dependency review.

## Physical-device substrate gate

Only after supply-chain review:

- package reviewed aliases;
- inspect nativeLibraryDir;
- test loader path;
- test symlink/hardlink behavior;
- test NOFOLLOW cleanup;
- no Linux executor yet.

## 0.2 — first one-shot Linux command

Only after an explicit reviewed artifact approval commit:

- separate executor feature gate;
- non-interactive /bin/sh;
- bounded output;
- timeout;
- deterministic stop;
- physical-device evidence.

## 0.2.x — interactive Linux

- PTY;
- resize;
- signals;
- process tree;
- package/bootstrap;
- user storage bridge.

## 0.3 — graphics

- controlled Vulkan renderer;
- frame timing;
- Linux graphics bridge.

## 0.4 — Windows compatibility

- x86/x64 translation;
- Wine;
- DXVK/VKD3D.
