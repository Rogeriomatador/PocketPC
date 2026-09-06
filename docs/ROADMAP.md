# Roadmap

## 0.1.0-alpha16 — Windows preflight doctor

- non-destructive environment doctor;
- locked SDK component checks;
- JDK/Python/Git/ADB checks;
- physical-device authorization/ABI/API checks;
- device serial hashing;
- disk-space checks;
- optional network checks;
- preflight JSON + SHA-256;
- preflight verifier/self-test;
- two-pass integration into the one-command physical test.

## Immediate real step

Run first-physical-test-windows.ps1 on Windows with one authorized physical ARM64 Android device.

If preflight fails, fix the explicit FAIL item. If the complete chain succeeds, preserve the entire local-build output and physical-validation evidence.
