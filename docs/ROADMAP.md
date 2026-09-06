# Roadmap

## 0.1.0-alpha15 — one-command first physical test

- Alpha 14 automated physical evidence runner;
- strict one-command Windows orchestration;
- clean-tree enforcement before the entire chain;
- final first-physical-test record;
- final record verifier/self-test;
- only one final PASS token after every prior gate succeeds.

## Immediate next real action

Run first-physical-test-windows.ps1 on the Windows development PC with one physical ARM64 Android device authorized over ADB.

If POCKETPC_FIRST_PHYSICAL_TEST_OK appears, preserve the entire physical-validation directory and its hashes.

Only after that should the project advance the separate PRoot artifact/device gate.
