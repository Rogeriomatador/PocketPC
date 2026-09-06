# Device Evidence Chain — Alpha 13

Status: IMPLEMENTED HOST VERIFIER / REAL CHAIN PENDING

scripts/verify-device-chain.py combines three independently verified artifacts:

1. local-build-record.json;
2. device-install-record.json;
3. exported PocketPC evidence bundle.

It cross-checks:

- clean pinned source commit;
- package name;
- version name/code;
- local APK SHA-256 versus install record;
- build signing-certificate hashes versus installed app Build Identity;
- manufacturer/model/API;
- complete ABI set;
- MainActivity launch PASS.

The evidence bundle verifier is run with --expected-revision equal to the build commit.

A POCKETPC_DEVICE_CHAIN_OK result means the build/install/evidence artifacts are internally consistent with one another.

It still does not imply Linux execution or PRoot approval.
