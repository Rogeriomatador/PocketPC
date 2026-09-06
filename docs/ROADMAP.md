# Roadmap

## 0.1.0-alpha7 — guest filesystem semantics

- Alpha 6 execution foundation;
- metadata parser;
- guest path resolver;
- symlink/hardlink planning;
- transactional link preparation/recovery;
- hardlink inode verification;
- metadata hash-bound link marker;
- NOFOLLOW cleanup;
- guest-aware entrypoint resolution;
- Runtimes UI link preparation/verification.

## Alpha 7 device gate

- build APK;
- install on ARM64 Android;
- prepare a controlled rootfs with relative and absolute symlinks;
- verify hardlinks;
- remove runtime and prove external symlink target survives;
- export logs.

## Next — PRoot artifact build gate

- independently audit source archives;
- document ARM64 build adaptation;
- produce quarantined artifacts;
- audit ELF type/machine/dependencies/RPATH;
- record SHA-256;
- license/notice/source-distribution package;
- do not enable executor yet.

## 0.2 — first one-shot Linux command

- reviewed substrate packaged;
- explicit executor feature gate;
- /bin/sh non-interactive smoke;
- bounded logs/timeout;
- process cleanup;
- physical-device evidence.

## 0.2.x — interactive Linux

- PTY;
- resize/signals;
- process-tree lifecycle;
- package/bootstrap.

## 0.3 — graphics

- controlled Vulkan renderer;
- presentation timing;
- Linux graphical bridge.

## 0.4 — Windows compatibility

- x86/x64 translation;
- Wine;
- DXVK/VKD3D.
