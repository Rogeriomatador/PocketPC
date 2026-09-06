# Roadmap

## 0.1.0-alpha13 — Device Install & Evidence Chain

- Alpha 12 reproducible Windows builder;
- local build re-verification before install;
- ADB physical-device selection;
- emulator refusal by default;
- device serial hashing;
- arm64-v8a gate;
- non-incremental APK installation;
- installed package/version validation;
- synchronized MainActivity launch;
- installed APK pull/hash attempt;
- structured install record + sidecar;
- install record verifier/self-test;
- full build/install/evidence cross-verifier.

## Next real evidence step

Run Alpha 12/13 scripts on the Windows development PC, install the exact APK on a physical ARM64 phone, run the in-app Device Evidence Harness, export the evidence bundle and verify the full chain.

PRoot supply-chain work remains separate and Linux execution remains disabled.
