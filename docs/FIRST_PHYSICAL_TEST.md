# First Physical Test — Alpha 15

Status: IMPLEMENTED SOURCE / REAL WINDOWS+PHONE EXECUTION PENDING

## Single entry point

scripts/first-physical-test-windows.ps1

## Mandatory sequence

1. clean Git tree;
2. strict reproducible Windows build;
3. LOCAL_BUILD_POLICY_CHECKED;
4. exact APK hash/signing evidence;
5. Device Install Gate;
6. automated Debug Evidence Runner;
7. Evidence Bundle verification;
8. build/install/device cross-verification;
9. filesystem critical PASS;
10. Native Runtime Host loaded;
11. physical-validation record verification;
12. final first-physical-test record verification.

Only then is POCKETPC_FIRST_PHYSICAL_TEST_OK printed.

## What this proves

It proves a traceable clean source-to-APK-to-device runtime-smoke evidence chain for the PocketPC host application.

It does not prove PRoot, Linux shell execution, PTY, Linux graphics or Windows compatibility.
