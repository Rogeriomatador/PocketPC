# Roadmap

## 0.1.0-alpha9 — runtime artifact attestation

- Alpha 8 artifact quarantine;
- Android packaging blocker detection;
- embedded approval manifest;
- source/contract/final-lock digest binding;
- native artifact bytes/SHA-256 verification;
- approved alias validation;
- unexpected sensitive artifact rejection;
- approval policy CI script;
- policy files packaged as assets;
- current approval locked false.

## Immediate evidence gates

1. fix/restore GitHub runner execution;
2. PRoot Source Audit PASS;
3. real PRoot Quarantine Build;
4. inspect real DT_NEEDED/SONAME;
5. resolve Android talloc packaging if blocked;
6. finish source-license audit;
7. create final reviewed ARTIFACTS.lock.json;
8. physical device package/link test.

## Approval transition

Only after all evidence exists:

- create APPROVED artifact lock;
- set approval manifest true;
- bind all three policy hashes;
- include exact final artifact aliases/hashes/bytes;
- update CI from binary rejection to exact-hash allowlist;
- device-test runtime attestation.

## 0.2 — one-shot Linux executor

Only after Alpha 9 attestation passes on a device:

- separate executor enable switch;
- /bin/sh one-shot smoke;
- bounded logs;
- timeout;
- deterministic process cleanup.

## 0.2.x — interactive Linux

PTY, signals, resize, process tree and bootstrap.

## 0.3 — graphics

Controlled Vulkan renderer and Linux graphics bridge.

## 0.4 — Windows compatibility

Translation, Wine, DXVK/VKD3D.
