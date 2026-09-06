# Roadmap

## 0.1.0-alpha14 — automated physical evidence runner

- Alpha 13 ADB install gate;
- debug-only ADB evidence Activity;
- automated Device Evidence collection;
- app-specific external evidence export for adb pull;
- one-script physical orchestrator;
- filesystem PASS requirement;
- Native Runtime Host load requirement;
- full-chain self-test;
- final physical-validation record;
- final record verifier/self-test.

## Next real step

Generate a clean Alpha 14 APK on Windows and run validate-device-windows.ps1 against a physical ARM64 Android phone.

If the script returns PHYSICAL_DEVICE_CHAIN_VERIFIED, the first build/install/runtime-smoke evidence chain is complete.

PRoot/Linux remain separate later gates.
