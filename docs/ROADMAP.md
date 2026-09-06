# Roadmap

## 0.1.0-alpha12 — reproducible local Windows build

- Alpha 11 evidence bundle and build identity;
- Android toolchain lock;
- Gradle distribution SHA-256 pin;
- clean-tree gate;
- dirty-tree LOCAL_UNPINNED behavior;
- JDK/Android SDK discovery;
- optional sdkmanager installation;
- Python policy gate;
- unit test/lint/assemble pipeline;
- APK structure gate;
- APK signing identity capture;
- APK SHA-256;
- local build record;
- independent build-record verifier/self-test;
- Windows Actions harness definition.

## Highest-value next step

Run the Alpha 12 builder on a real Windows development machine.

If it succeeds: preserve the build record, APK hash/signing evidence, install that exact APK, run Device Evidence Harness, export the evidence bundle and verify it against the same commit.

If it fails: the first real toolchain/compiler/test error becomes actionable evidence and should be fixed directly.

PRoot integration and Linux execution remain separate later gates.
