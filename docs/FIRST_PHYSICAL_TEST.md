# First Physical Test — Alpha 17

The simplest Windows entry point is `PocketPC-Test-Windows.bat`. The public PowerShell entry point remains `scripts/first-physical-test-windows.ps1`.

Alpha 17 splits orchestration into a wrapper and `first-physical-test-core-windows.ps1`.

The core performs the full Alpha 16 preflight/build/install/evidence/final-verification chain.

The wrapper adds automatic failure triage and then rethrows the original error.

Success requires `POCKETPC_FIRST_PHYSICAL_TEST_OK`. After automated evidence verification succeeds, the harness starts `MainActivity` again so PocketPC remains open for manual testing.
