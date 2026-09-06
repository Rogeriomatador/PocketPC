# Roadmap

## 0.1.0-alpha10 — device evidence harness

- Alpha 9 runtime artifact attestation;
- app-private filesystem capability probe;
- relative symlink device test;
- absolute symlink device test;
- hardlink inode test;
- NOFOLLOW cleanup safety test;
- external target preservation check;
- structured device/substrate JSON evidence;
- SHA-256 sidecar;
- System UI trigger.

## Next evidence gate

- produce a build from a functioning Android toolchain;
- identify exact commit/APK;
- run Device Evidence Harness on physical ARM64 Android;
- preserve JSON + sidecar;
- classify results honestly.

## Supply-chain gate

Still requires:

- source archive audit;
- real quarantine build;
- ELF/dependency report;
- Android packaging blocker resolution;
- source-license review;
- final ARTIFACTS.lock.json.

## Linux executor gate

Still later and separate.

No /bin/sh execution should be enabled merely because filesystem evidence passes.
