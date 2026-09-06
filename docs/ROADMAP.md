# Roadmap

## 0.1.0-alpha17 — automatic failure triage

- preserve Alpha 16 physical-test core;
- public wrapper around the core;
- automatic triage on any failure;
- local evidence/record harvesting;
- PocketPC package/process diagnostics;
- restricted logcat capture;
- serial hashing;
- per-file SHA-256 map;
- triage verifier and tamper self-test.

## Immediate real milestone

Run the public first-physical-test-windows.ps1 on Windows with a physical ARM64 Android phone authorized over ADB.

If the run succeeds, preserve the physical-validation directory.

If it fails, preserve the generated failure-triage directory; that failure becomes the next engineering task.

After the first physical host-app chain passes, advance to the separate PRoot artifact/source/license/device gate.
