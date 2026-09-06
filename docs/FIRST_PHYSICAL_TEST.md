# First Physical Test — Alpha 17

Public entry point remains scripts/first-physical-test-windows.ps1.

Alpha 17 splits orchestration into a wrapper and first-physical-test-core-windows.ps1.

The core performs the full Alpha 16 preflight/build/install/evidence/final-verification chain.

The wrapper adds automatic failure triage and then rethrows the original error.

Success still requires POCKETPC_FIRST_PHYSICAL_TEST_OK.
